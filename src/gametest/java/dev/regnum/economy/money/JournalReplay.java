package dev.regnum.economy.money;

import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

public final class JournalReplay {
    private JournalReplay() { }

    public static void replayInterruptedMarketInstall(GameTestHelper test, ServerPlayer player, CompoundTag beforeMarket)
            throws java.io.IOException {
        var server = player.getServer();
        var root = server.getWorldPath(LevelResource.ROOT);
        String marketPath = "data/regnum_economy_markets.dat";
        String playerPath = "playerdata/" + player.getStringUUID() + ".dat";
        Path journal = root.resolve("data/regnum_economy_transaction.dat");
        test.assertTrue(Files.notExists(journal), "No earlier transaction may occupy the recovery journal");
        CompoundTag afterMarket = read(root.resolve(marketPath));
        CompoundTag afterPlayer = read(root.resolve(playerPath));
        var entries = new ListTag();
        entries.add(entry(playerPath, afterPlayer));
        entries.add(entry(marketPath, afterMarket));
        var transaction = new CompoundTag();
        transaction.putInt("version", 1);
        transaction.put("entries", entries);
        NbtIo.writeCompressed(transaction, journal);
        // Model an interruption after the journal and player file were installed.
        NbtIo.writeCompressed(beforeMarket, root.resolve(marketPath));
        WalletPersistence.recover(server);
        WalletPersistence.recover(server);
        test.assertTrue(Files.notExists(journal) && read(root.resolve(marketPath)).equals(afterMarket)
                && read(root.resolve(playerPath)).equals(afterPlayer), "Recovery installs the exact committed wallet, items and stock once");
    }

    private static CompoundTag read(Path path) throws java.io.IOException {
        return NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
    }

    private static CompoundTag entry(String path, CompoundTag data) {
        var entry = new CompoundTag();
        entry.putString("path", path);
        entry.put("data", data);
        return entry;
    }
}
