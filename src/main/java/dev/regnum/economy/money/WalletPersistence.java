package dev.regnum.economy.money;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import dev.regnum.economy.RegnumEconomy;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class WalletPersistence {
    private static final String COMMIT_ID = "wallet_commit";
    private static final AttachmentType<Long> COMMIT = AttachmentRegistry.<Long>builder()
            .persistent(Codec.LONG).copyOnDeath().buildAndRegister(RegnumEconomy.id(COMMIT_ID));
    private static final String JOURNAL = "data/regnum_economy_transaction.dat";
    private static final Set<MinecraftServer> LOCKED = Collections.newSetFromMap(new IdentityHashMap<>());

    private WalletPersistence() {
    }

    static void register() {
        ServerLifecycleEvents.SERVER_STARTING.register(WalletPersistence::recover);
        ServerLifecycleEvents.SERVER_STOPPED.register(LOCKED::remove);
    }

    public static boolean available(MinecraftServer server) {
        return !LOCKED.contains(server);
    }

    public static Optional<CompoundTag> recoverHost(ServerPlayer player, Optional<CompoundTag> loaded) {
        // Integrated servers prefer the owner snapshot embedded in level.dat. A conversion
        // may have committed a newer player file since that world snapshot was written.
        if (!player.getServer().isSingleplayerOwner(player.getGameProfile())) {
            return loaded;
        }
        Path path = player.getServer().getWorldPath(LevelResource.PLAYER_DATA_DIR)
                .resolve(player.getStringUUID() + ".dat");
        if (!Files.exists(path)) {
            return loaded;
        }
        try {
            CompoundTag checkpoint = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
            long savedRevision = revision(checkpoint);
            long loadedRevision = loaded.map(WalletPersistence::revision).orElse(0L);
            if (savedRevision > loadedRevision) {
                player.load(checkpoint);
                return Optional.of(checkpoint);
            }
            return loaded;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read the owner's wallet checkpoint", exception);
        }
    }

    private static long revision(CompoundTag data) {
        return data.getCompound(AttachmentTarget.NBT_ATTACHMENT_KEY).getLong(RegnumEconomy.id(COMMIT_ID).toString());
    }

    // A single durable journal is the commit point, even when player and market files differ.
    // Recovery installs the complete post-state before worlds or player accounts are loaded.
    public static boolean commit(ServerPlayer player) {
        return commit(List.of(player), null);
    }

    public static boolean commitPlayers(ServerPlayer... players) {
        return commit(List.of(players), null);
    }

    public static boolean commitMarket(ServerPlayer player, CompoundTag marketData) {
        return commit(List.of(player), marketData);
    }

    private static boolean commit(List<ServerPlayer> players, CompoundTag marketData) {
        MinecraftServer server = players.getFirst().getServer();
        if (!server.isSameThread()) {
            throw new IllegalStateException("Wallet commits require the server thread");
        }
        if (!available(server)) {
            return false;
        }
        Map<ServerPlayer, Long> revisions = new LinkedHashMap<>();
        Map<String, CompoundTag> files = new LinkedHashMap<>();
        Path journal = server.getWorldPath(LevelResource.ROOT).resolve(JOURNAL);
        boolean committed = false;
        try {
            for (ServerPlayer player : players) {
                long previous = player.getAttachedOrElse(COMMIT, 0L);
                if (player.getServer() != server || previous < 0 || previous == Long.MAX_VALUE
                        || revisions.putIfAbsent(player, previous) != null) {
                    throw new IllegalStateException("Invalid economy transaction participants");
                }
                player.setAttached(COMMIT, previous + 1);
                if (files.putIfAbsent("playerdata/" + player.getStringUUID() + ".dat",
                        player.saveWithoutId(new CompoundTag())) != null) {
                    throw new IllegalStateException("Duplicate economy account participant");
                }
            }
            if (marketData != null) {
                CompoundTag root = new CompoundTag();
                root.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
                root.put("data", marketData);
                files.put("data/regnum_economy_markets.dat", root);
            }
            CompoundTag transaction = new CompoundTag();
            transaction.putInt("version", 1);
            ListTag entries = new ListTag();
            files.forEach((path, data) -> {
                CompoundTag entry = new CompoundTag();
                entry.putString("path", path);
                entry.put("data", data);
                entries.add(entry);
            });
            transaction.put("entries", entries);
            writeAtomic(journal, transaction);
            committed = true;
            install(server, transaction);
            Files.delete(journal);
            return true;
        } catch (IOException | RuntimeException exception) {
            if (!committed) {
                revisions.forEach((player, previous) -> player.setAttached(COMMIT, previous));
                LogUtils.getLogger().error("Could not commit Regnum economy transaction", exception);
                return false;
            }
            // The journal is already durable. Never refund a committed transaction.
            // Stop further gameplay; restart will replay all participants together.
            LOCKED.add(server);
            LogUtils.getLogger().error("Economy transaction committed but could not be installed; stopping for recovery", exception);
            server.halt(false);
            return true;
        }
    }

    private static void recover(MinecraftServer server) {
        Path journal = server.getWorldPath(LevelResource.ROOT).resolve(JOURNAL);
        if (!Files.exists(journal)) return;
        try {
            install(server, NbtIo.readCompressed(journal, NbtAccounter.unlimitedHeap()));
            Files.delete(journal);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Regnum economy transaction recovery failed; original journal retained", exception);
        }
    }

    private static void install(MinecraftServer server, CompoundTag transaction) throws IOException {
        if (transaction.getInt("version") != 1 || !(transaction.get("entries") instanceof ListTag entries)
                || entries.isEmpty() || entries.getElementType() != Tag.TAG_COMPOUND) {
            throw new IOException("Invalid economy transaction journal");
        }
        Map<Path, CompoundTag> files = new LinkedHashMap<>();
        for (Tag value : entries) {
            CompoundTag entry = (CompoundTag) value;
            String relative = entry.getString("path");
            if (!(relative.equals("data/regnum_economy_markets.dat")
                    || relative.matches("playerdata/[0-9a-fA-F-]{36}\\.dat")) || !entry.contains("data", Tag.TAG_COMPOUND)) {
                throw new IOException("Invalid economy transaction destination");
            }
            Path path = server.getWorldPath(LevelResource.ROOT).resolve(relative);
            if (files.putIfAbsent(path, entry.getCompound("data")) != null) {
                throw new IOException("Duplicate economy transaction destination");
            }
        }
        for (var file : files.entrySet()) writeAtomic(file.getKey(), file.getValue());
    }

    private static void writeAtomic(Path destination, CompoundTag data) throws IOException {
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempFile(destination.getParent(), "regnum-economy-", ".tmp");
        try {
            NbtIo.writeCompressed(data, temporary);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException | RuntimeException exception) {
                LogUtils.getLogger().warn("Could not remove economy temporary file {}", temporary, exception);
            }
        }
    }
}
