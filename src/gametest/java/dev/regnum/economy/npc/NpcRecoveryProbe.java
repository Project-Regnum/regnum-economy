package dev.regnum.economy.npc;

import com.mojang.authlib.GameProfile;
import dev.regnum.economy.TestPlayers;
import dev.regnum.economy.market.NpcMerchants;
import dev.regnum.economy.market.NpcMerchants.Result;
import dev.regnum.economy.money.CoinBalances;
import dev.regnum.economy.money.WalletPersistence;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

/** Run only with -Precovery_probe=interrupt, then verify twice in separate JVMs. */
public final class NpcRecoveryProbe implements FabricGameTest {
    @GameTest(template = EMPTY_STRUCTURE, batch = "recovery_probe", timeoutTicks = 300)
    public void recoverRealInterruptedMerchantCommitAndPersistAllRoles(GameTestHelper h) throws Exception {
        var root = h.getLevel().getServer().getWorldPath(LevelResource.ROOT);
        if (System.getProperty("regnumEconomy.recoveryProbe").equals("interrupt")) interrupt(h, root);
        else verify(h, root);
    }

    private static void interrupt(GameTestHelper h, Path root) throws Exception {
        var owner = TestPlayers.create(h);
        var origin = h.absolutePos(BlockPos.ZERO);
        var position = new Vec3(origin.getX() + 0.5, 64, origin.getZ() + 0.5);
        var manifest = new CompoundTag();
        var definitions = new ListTag();
        RegnumNpc merchant = null;
        int offset = 0;
        for (var role : new NpcRole[]{NpcRole.MERCHANT, NpcRole.BANKER, NpcRole.OFFICIAL, NpcRole.GUARD, NpcRole.GENERIC}) {
            var npc = Npcs.TYPE.create(h.getLevel());
            npc.setPos(position.add(offset++, 0, 0));
            npc.configure(owner.getUUID(), null, role, "Persistent " + role.id().getPath());
            npc.greeting("Saved greeting");
            npc.details("Saved details");
            npc.active(role != NpcRole.BANKER);
            h.assertTrue(h.getLevel().addFreshEntity(npc), "Persistent fixture NPC is added");
            var definition = new CompoundTag();
            definition.putUUID("uuid", npc.getUUID());
            definition.putLong("pos", npc.blockPosition().asLong());
            definition.putString("role", role.id().toString());
            definition.putBoolean("active", npc.active());
            definitions.add(definition);
            if (role == NpcRole.MERCHANT) merchant = npc;
        }
        owner.setPos(position.add(-2, 0, 0));
        owner.getInventory().setItem(0, new ItemStack(Items.STONE, 4));
        try (var operation = WalletPersistence.begin(owner)) {
            h.assertTrue(CoinBalances.credit(owner, 100) && operation.commit(), "Seed wallet is durable");
        }
        h.assertTrue(NpcMerchants.offer(owner, merchant, 2, 10, 8) == Result.SUCCESS
                && NpcMerchants.transferStock(owner, merchant, 1, 4, true) == Result.SUCCESS, "Seed merchant stock is durable");
        manifest.putUUID("player", owner.getUUID());
        manifest.putUUID("merchant", merchant.getUUID());
        manifest.put("npcs", definitions);
        NbtIo.writeCompressed(manifest, root.resolve("recovery-probe.dat"));
        h.getLevel().getServer().saveEverything(false, true, true);
        var market = root.resolve("data/regnum_economy_markets.dat");
        byte[] before = Files.readAllBytes(market);
        // An occupied directory rejects the atomic market install on both Windows
        // and Linux. Restore the old destination before halting, retaining the journal.
        var backup = root.resolve("data/market-before-probe.dat");
        Files.move(market, backup);
        Files.createDirectory(market);
        Files.createFile(market.resolve("block-install"));
        try {
            var quote = NpcMerchants.catalog(owner, merchant).goods().getFirst();
            h.assertTrue(NpcMerchants.trade(owner, merchant, quote, true) == Result.SUCCESS,
                    "The durable journal is the commit point even when installation fails");
            h.assertTrue(!WalletPersistence.available(owner.server)
                    && Files.isRegularFile(root.resolve("data/regnum_economy_transaction.dat"))
                    && CoinBalances.get(owner) == 90 && owner.getInventory().countItem(Items.STONE) == 2,
                    "Real market install failure locks gameplay and retains the committed journal without refunding");
        } finally {
            Files.delete(market.resolve("block-install"));
            Files.delete(market);
            Files.move(backup, market);
        }
        h.assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(market)), "The old market file remains the pre-state");
        System.out.println("RECOVERY PROBE: durable journal, player installed, market blocked; halting JVM with code 86");
        System.out.flush();
        Runtime.getRuntime().halt(86);
    }

    private static void verify(GameTestHelper h, Path root) throws Exception {
        var manifest = NbtIo.readCompressed(root.resolve("recovery-probe.dat"), NbtAccounter.unlimitedHeap());
        h.assertTrue(Files.notExists(root.resolve("data/regnum_economy_transaction.dat"))
                && WalletPersistence.available(h.getLevel().getServer()), "Startup replay installs and removes the journal before gameplay");
        var id = manifest.getUUID("player");
        var restored = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), new GameProfile(id, "probe-owner"),
                ClientInformation.createDefault());
        TestPlayers.connect(restored);
        restored.load(NbtIo.readCompressed(root.resolve("playerdata/" + id + ".dat"), NbtAccounter.unlimitedHeap()));
        h.assertTrue(CoinBalances.get(restored) == 90 && restored.getInventory().countItem(Items.STONE) == 2,
                "Restart restores exactly the committed wallet and purchased items");
        var chunks = new ArrayList<ChunkPos>();
        for (Tag value : manifest.getList("npcs", Tag.TAG_COMPOUND)) {
            var definition = (CompoundTag) value;
            var chunk = new ChunkPos(BlockPos.of(definition.getLong("pos")));
            h.getLevel().setChunkForced(chunk.x, chunk.z, true);
            chunks.add(chunk);
        }
        h.runAfterDelay(40, () -> {
            try {
                for (Tag value : manifest.getList("npcs", Tag.TAG_COMPOUND)) {
                    var definition = (CompoundTag) value;
                    var entity = h.getLevel().getEntity(definition.getUUID("uuid"));
                    h.assertTrue(entity instanceof RegnumNpc, "The saved NPC UUID loads from the entity region after process restart");
                    var npc = (RegnumNpc) entity;
                    h.assertTrue(npc.role().id().toString().equals(definition.getString("role"))
                            && npc.active() == definition.getBoolean("active") && npc.proprietor().equals(id)
                            && npc.greeting().equals("Saved greeting") && npc.details().equals("Saved details"),
                            "All five roles retain identity, configuration and activation");
                    if (npc.getUUID().equals(manifest.getUUID("merchant"))) {
                        var catalog = NpcMerchants.catalog(restored, npc);
                        h.assertTrue(catalog.available() && catalog.funds() == 10 && catalog.goods().getFirst().stock() == 2,
                                "Market escrow recovers the matching post-state, without duplicate items or copper");
                    }
                }
                System.out.println("RECOVERY PROBE PASS: exact wallet, inventory, escrow and all five persisted NPC roles");
                h.succeed();
            } finally {
                for (var chunk : chunks) h.getLevel().setChunkForced(chunk.x, chunk.z, false);
            }
        });
    }
}
