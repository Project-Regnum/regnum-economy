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
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
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
import java.util.ArrayList;
import java.util.UUID;
import java.util.HashSet;
import java.util.function.Function;

public final class WalletPersistence {
    private static final String COMMIT_ID = "wallet_commit";
    private static final AttachmentType<Long> COMMIT = AttachmentRegistry.<Long>builder()
            .persistent(Codec.LONG).copyOnDeath().buildAndRegister(RegnumEconomy.id(COMMIT_ID));
    private static final String JOURNAL = "data/regnum_economy_transaction.dat";
    private static final Set<MinecraftServer> LOCKED = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Map<MinecraftServer, Operation> ACTIVE = new IdentityHashMap<>();
    private static final Map<MinecraftServer, Set<UUID>> RECOVERED = new IdentityHashMap<>();
    private static final List<Function<ServerPlayer, Runnable>> PLAYER_STATE = new ArrayList<>();

    private WalletPersistence() {
    }

    static void register() {
        ServerLifecycleEvents.SERVER_STARTING.register(WalletPersistence::recover);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            LOCKED.remove(server);
            ACTIVE.remove(server);
            RECOVERED.remove(server);
        });
    }

    public static boolean available(MinecraftServer server) {
        return !LOCKED.contains(server);
    }

    public static boolean recoveryPending() {
        return !LOCKED.isEmpty();
    }

    public static boolean blocksPlayerSave(ServerPlayer player) {
        return !available(player.getServer()) || changing(player);
    }

    public static boolean blocksMarketSave() {
        return recoveryPending() || !ACTIVE.isEmpty();
    }

    public static boolean changing(ServerPlayer player) {
        Operation operation = ACTIVE.get(player.getServer());
        return operation != null && operation.before.containsKey(player);
    }

    public static Operation begin(ServerPlayer... players) {
        return new Operation(List.of(players));
    }

    /** Lifecycle cleanup must wait until the current inventory operation has committed or rolled back. */
    public static boolean afterCurrentOperation(MinecraftServer server, Runnable action) {
        if (!server.isSameThread()) throw new IllegalStateException("Economy cleanup requires the server thread");
        Operation operation = ACTIVE.get(server);
        if (operation == null) return false;
        operation.afterClose.add(java.util.Objects.requireNonNull(action));
        return true;
    }

    public static void stopForRecovery(MinecraftServer server, String reason) {
        LOCKED.add(server);
        LogUtils.getLogger().error("Economy stopped for recovery: {}", reason);
        server.halt(false);
    }

    /** Register only Economy-owned attachment snapshots during common mod initialization. */
    public static void registerPlayerState(Function<ServerPlayer, Runnable> snapshot) {
        PLAYER_STATE.add(java.util.Objects.requireNonNull(snapshot));
    }

    private record PlayerSnapshot(CompoundTag data, AbstractContainerMenu menu, ItemStack carried,
                                  List<ItemStack> inventory, int selected, List<Runnable> attachments) {
    }

    /** All participating player changes and optional market data share one commit point.
     * Uncommitted/exceptional exits restore every participant, including live menu cursors.
     */
    public static final class Operation implements AutoCloseable {
        private final MinecraftServer server;
        private final Map<ServerPlayer, PlayerSnapshot> before = new LinkedHashMap<>();
        private final List<Runnable> rollback = new ArrayList<>();
        private final List<Runnable> afterClose = new ArrayList<>();
        private boolean attempted;
        private boolean committed;
        private boolean closed;

        private Operation(List<ServerPlayer> players) {
            if (players.isEmpty()) throw new IllegalArgumentException("An economy operation needs a player");
            server = players.getFirst().getServer();
            if (!server.isSameThread() || !available(server) || ACTIVE.containsKey(server)) {
                throw new IllegalStateException("Economy operations require an available server thread and cannot nest");
            }
            Set<UUID> accounts = new HashSet<>();
            for (ServerPlayer player : players) {
                if (player.getServer() != server || !accounts.add(player.getUUID())) {
                    throw new IllegalArgumentException("Invalid or duplicate economy participant");
                }
                List<ItemStack> inventory = new ArrayList<>();
                for (int i = 0; i < player.getInventory().getContainerSize(); i++) inventory.add(player.getInventory().getItem(i).copy());
                List<Runnable> attachments = new ArrayList<>();
                Long checkpoint = player.getAttached(COMMIT);
                attachments.add(() -> player.setAttached(COMMIT, checkpoint));
                for (var capture : PLAYER_STATE) attachments.add(capture.apply(player));
                before.put(player, new PlayerSnapshot(player.saveWithoutId(new CompoundTag()), player.containerMenu,
                        player.containerMenu.getCarried().copy(), inventory, player.getInventory().selected, attachments));
            }
            ACTIVE.put(server, this);
        }

        public void onRollback(Runnable action) {
            if (attempted || closed) throw new IllegalStateException("Economy operation has already finished");
            rollback.add(action);
        }

        public boolean commit() {
            return commit(null);
        }

        public boolean commit(CompoundTag marketData) {
            if (attempted || closed) throw new IllegalStateException("Economy operation has already finished");
            attempted = true;
            if (marketData == null && before.entrySet().stream().allMatch(entry ->
                    entry.getValue().data().equals(entry.getKey().saveWithoutId(new CompoundTag()))
                            && ItemStack.matches(entry.getValue().carried(), entry.getValue().menu().getCarried()))) {
                committed = true;
                return true;
            }
            committed = commitFiles(List.copyOf(before.keySet()), marketData);
            return committed;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            RuntimeException failure = null;
            try {
                if (!committed) {
                    for (int i = rollback.size() - 1; i >= 0; i--) {
                        try { rollback.get(i).run(); }
                        catch (RuntimeException exception) { failure = exception; }
                    }
                    for (var entry : before.entrySet()) {
                        try {
                            ServerPlayer player = entry.getKey();
                            PlayerSnapshot snapshot = entry.getValue();
                            for (Runnable restore : snapshot.attachments()) restore.run();
                            for (int i = 0; i < snapshot.inventory().size(); i++) {
                                player.getInventory().setItem(i, snapshot.inventory().get(i).copy());
                            }
                            player.getInventory().selected = snapshot.selected();
                            player.getInventory().setChanged();
                            snapshot.menu().setCarried(snapshot.carried().copy());
                        } catch (RuntimeException exception) { failure = exception; }
                    }
                }
            } finally {
                ACTIVE.remove(server, this);
            }
            if (failure != null) {
                LOCKED.add(server);
                LogUtils.getLogger().error("Economy rollback failed; stopping instead of continuing with partial state", failure);
                server.halt(false);
                finishCleanup();
                throw failure;
            }
            for (ServerPlayer player : before.keySet()) {
                try {
                    CoinBalances.sync(player);
                    if (!committed) player.containerMenu.broadcastFullState();
                } catch (RuntimeException exception) {
                    // Notification failure cannot turn a durable commit into a failed payment.
                    LogUtils.getLogger().warn("Could not synchronize economy state for {} after operation", player.getUUID(), exception);
                }
            }
            finishCleanup();
        }

        private void finishCleanup() {
            for (Runnable cleanup : afterClose) {
                try { cleanup.run(); }
                catch (RuntimeException failure) {
                    LogUtils.getLogger().error("Deferred economy cleanup failed", failure);
                    stopForRecovery(server, "Deferred cleanup could not preserve ownership");
                }
            }
        }
    }

    public static Optional<CompoundTag> recoverHost(ServerPlayer player, Optional<CompoundTag> loaded) {
        // Integrated servers prefer the owner snapshot embedded in level.dat. A conversion
        // may have committed a newer player file since that world snapshot was written.
        if (!player.getServer().isSingleplayerOwner(player.getGameProfile())) {
            return loaded;
        }
        Path path = player.getServer().getWorldPath(LevelResource.PLAYER_DATA_DIR)
                .resolve(player.getStringUUID() + ".dat");
        if (Files.notExists(path)) {
            return loaded;
        }
        try {
            CompoundTag checkpoint = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
            long savedRevision = revision(checkpoint);
            long loadedRevision = loaded.map(WalletPersistence::revision).orElse(0L);
            Set<UUID> recovered = RECOVERED.get(player.getServer());
            boolean fromJournal = recovered != null && recovered.remove(player.getUUID());
            boolean journalOwner = fromJournal && savedRevision == loadedRevision;
            if (savedRevision > loadedRevision || journalOwner) {
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
    private static boolean commitFiles(List<ServerPlayer> players, CompoundTag marketData) {
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
            if (!Files.notExists(journal)) {
                LOCKED.add(server);
                server.halt(false);
                throw new IOException("An unrecovered economy journal already exists; refusing to replace it");
            }
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
        if (Files.notExists(journal)) return;
        try {
            CompoundTag transaction = NbtIo.readCompressed(journal, NbtAccounter.unlimitedHeap());
            install(server, transaction);
            Set<UUID> recovered = new HashSet<>();
            for (Tag value : transaction.getList("entries", Tag.TAG_COMPOUND)) {
                String path = ((CompoundTag) value).getString("path");
                if (path.startsWith("playerdata/")) recovered.add(UUID.fromString(path.substring(11, path.length() - 4)));
            }
            RECOVERED.put(server, recovered);
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
