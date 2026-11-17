package dev.regnum.economy.npc;

import dev.regnum.economy.TestPlayers;
import dev.regnum.economy.market.NpcMerchants;
import dev.regnum.economy.market.NpcMerchants.Result;
import dev.regnum.economy.money.CoinBalances;
import dev.regnum.economy.money.JournalReplay;
import dev.regnum.economy.money.WalletPersistence;
import dev.sakhno.regnum.nations.api.v1.EconomyPermission;
import dev.sakhno.regnum.nations.api.v1.EntityRef;
import dev.sakhno.regnum.nations.api.v1.NationsApi;
import dev.sakhno.regnum.nations.block.CityObeliskBlockEntity;
import dev.sakhno.regnum.nations.block.ModBlocks;
import dev.sakhno.regnum.nations.kingdom.FoundingException;
import dev.sakhno.regnum.nations.kingdom.KingdomService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

public final class NpcIntegrationTests implements FabricGameTest {
    @GameTest(template = EMPTY_STRUCTURE, batch = "npc_integration")
    public void createdNpcUsesVanillaIdentityAndRejectsMalformedOrDuplicateData(GameTestHelper h) throws Exception {
        var proprietor = TestPlayers.create(h);
        var visitor = TestPlayers.create(h);
        var level = h.getLevel();
        Vec3 position = aboveTest(h);
        proprietor.setPos(position);
        visitor.setPos(position.add(2, 0, 0));
        h.assertTrue(Npcs.create(proprietor, NpcRole.GUARD, "Sentry") == null, "Wilderness creation requires operator access");
        var players = level.getServer().getPlayerList();
        players.getOps().add(new net.minecraft.server.players.ServerOpListEntry(proprietor.getGameProfile(), 2, false));
        RegnumNpc npc;
        try {
            npc = Npcs.create(proprietor, NpcRole.GUARD, "Sentry");
        } finally {
            players.deop(proprietor.getGameProfile());
        }
        h.assertTrue(npc != null, "Operator can place an NPC");
        h.assertTrue(Npcs.canManage(proprietor, npc) && !Npcs.canManage(visitor, npc), "Only the wilderness proprietor can edit");
        var nameTag = new ItemStack(Items.NAME_TAG);
        nameTag.set(DataComponents.CUSTOM_NAME, Component.literal("Unauthorized name"));
        visitor.setItemInHand(InteractionHand.MAIN_HAND, nameTag);
        npc.interact(visitor, InteractionHand.MAIN_HAND);
        h.assertTrue(npc.getName().getString().equals("Sentry") && nameTag.getCount() == 1,
                "Vanilla name tags cannot bypass NPC configuration permissions");
        npc.appearance(NpcAppearance.OFFICIAL);
        npc.roleTitle("Watch captain");
        npc.greeting("Welcome, traveler.");
        npc.details("The road leads east.");
        var saved = npc.saveWithoutId(new CompoundTag());
        var reloaded = Npcs.TYPE.create(level);
        reloaded.load(saved.copy());
        h.assertTrue(reloaded.getUUID().equals(npc.getUUID()) && reloaded.role() == NpcRole.GUARD
                        && reloaded.appearance() == NpcAppearance.OFFICIAL && reloaded.greeting().equals(npc.greeting())
                        && reloaded.details().equals(npc.details()) && reloaded.roleTitle().equals("Watch captain")
                        && reloaded.proprietor().equals(proprietor.getUUID()),
                "Vanilla entity serialization retains NPC identity and configuration");
        h.assertTrue(!level.addFreshEntity(reloaded), "A loaded UUID cannot be added twice");
        npc.discard();
        h.assertTrue(level.addFreshEntity(reloaded) && Npcs.canInteract(visitor, reloaded), "The restored entity is interactable");
        visitor.setPos(position.add(7, 0, 0));
        h.assertTrue(!Npcs.canInteract(visitor, reloaded), "Interaction outside the server range is denied");
        var invalid = saved.copy();
        invalid.getCompound("RegnumNpc").putInt("version", Integer.MAX_VALUE);
        var rejected = Npcs.TYPE.create(level);
        rejected.load(invalid);
        h.assertTrue(!rejected.active() && rejected.proprietor() == null
                        && rejected.saveWithoutId(new CompoundTag()).get("RegnumNpc").equals(invalid.get("RegnumNpc")),
                "Unknown NPC data stays inactive and is preserved");
        var commands = level.getServer().getCommands().getDispatcher();
        visitor.setPos(position.add(2, 0, 0));
        String remove = "economy npc remove " + reloaded.getStringUUID();
        h.assertTrue(commands.execute(remove, visitor.createCommandSourceStack()) == 0 && reloaded.isAlive(),
                "Unauthorized removal through Brigadier is rejected");
        h.assertTrue(commands.execute(remove, proprietor.createCommandSourceStack()) == 1
                        && !Npcs.canInteract(proprietor, reloaded), "The proprietor can remove the live entity");
        h.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE, batch = "npc_integration")
    public void merchantConservesItemsAndCopperAndConsumesConfirmOnce(GameTestHelper h) throws Exception {
        var seller = TestPlayers.create(h);
        var shopper = TestPlayers.create(h);
        Vec3 position = aboveTest(h);
        var npc = placeMerchant(h, seller, position, null);
        shopper.setPos(position.add(2, 0, 0));
        var named = new ItemStack(Items.STONE, 6);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Mason's stone"));
        seller.getInventory().setItem(0, named.copy());
        var offered = NpcMerchants.offer(seller, npc, 2, 10, 8);
        h.assertTrue(offered == Result.SUCCESS, "Owner configures both sides of an offer: " + offered
                + ", visible=" + seller.hasLineOfSight(npc) + ", tracked=" + (h.getLevel().getEntity(npc.getUUID()) == npc));
        var initial = quote(seller, npc);
        h.assertTrue(NpcMerchants.trade(shopper, npc, initial, true) == Result.STOCK, "No stock is created by a listing");
        h.assertTrue(NpcMerchants.trade(shopper, npc, initial, false) == Result.MERCHANT_FUNDS, "Empty escrow cannot pay a bid");
        h.assertTrue(NpcMerchants.transferStock(seller, npc, 1, 4, true) == Result.SUCCESS
                && seller.getMainHandItem().getCount() == 2, "Restocking moves real matching items into escrow");
        h.assertTrue(NpcMerchants.trade(shopper, npc, initial, true) == Result.FUNDS, "An empty wallet cannot purchase");
        credit(h, seller, 100);
        credit(h, shopper, 100);
        h.assertTrue(NpcMerchants.transferFunds(seller, npc, 30, true) == Result.SUCCESS
                && CoinBalances.get(seller) == 70, "Wilderness escrow is funded from the owner's wallet");
        for (int slot = 0; slot < shopper.getInventory().items.size(); slot++) {
            shopper.getInventory().setItem(slot, new ItemStack(Items.DIAMOND, 64));
        }
        h.assertTrue(NpcMerchants.trade(shopper, npc, initial, true) == Result.SPACE
                && CoinBalances.get(shopper) == 100 && quote(seller, npc).stock() == 4,
                "Full inventory leaves stock and money untouched");
        shopper.getInventory().clearContent();
        h.assertTrue(NpcMerchants.offer(seller, npc, 2, 11, 8) == Result.SUCCESS
                && NpcMerchants.trade(shopper, npc, initial, true) == Result.STALE, "Edited terms invalidate an open quote");

        var current = quote(seller, npc);
        UUID token = NpcInteractions.issue(shopper, npc, false, 0, List.of(new NpcInteractions.Choice(Component.empty(),
                ignored -> h.assertTrue(NpcMerchants.trade(shopper, npc, current, true) == Result.SUCCESS, "Confirmed purchase succeeds"))));
        var confirmation = new NpcPackets.Select(token, 0, "");
        NpcInteractions.select(seller, confirmation);
        NpcInteractions.select(shopper, new NpcPackets.Select(token, 1, ""));
        NpcInteractions.select(shopper, new NpcPackets.Select(token, 0, "untrusted"));
        NpcInteractions.select(shopper, confirmation);
        NpcInteractions.select(shopper, confirmation);
        h.assertTrue(CoinBalances.get(shopper) == 89 && quote(seller, npc).stock() == 2
                        && shopper.getMainHandItem().getCount() == 2
                        && ItemStack.isSameItemSameComponents(shopper.getMainHandItem(), named),
                "Only one valid request trades and delivers the exact components and lot size");

        shopper.getInventory().setItem(0, new ItemStack(Items.STONE, 2));
        h.assertTrue(NpcMerchants.trade(shopper, npc, current, false) == Result.ITEM, "A near match with different components is rejected");
        shopper.getInventory().setItem(0, named.copyWithCount(2));
        var world = h.getLevel().getServer().getWorldPath(LevelResource.ROOT);
        var marketFile = world.resolve("data/regnum_economy_markets.dat");
        var beforeSale = NbtIo.readCompressed(marketFile, NbtAccounter.unlimitedHeap());
        h.assertTrue(NpcMerchants.trade(shopper, npc, current, false) == Result.SUCCESS
                && shopper.getMainHandItem().isEmpty() && CoinBalances.get(shopper) == 97
                && NpcMerchants.catalog(seller, npc).funds() == 33 && quote(seller, npc).stock() == 4,
                "The sale moves exact items back into stock and pays out of escrow");
        JournalReplay.replayInterruptedMarketInstall(h, shopper, beforeSale);
        var market = NbtIo.readCompressed(marketFile, NbtAccounter.unlimitedHeap());
        var stored = market.getCompound("data").getList("merchants", Tag.TAG_COMPOUND);
        boolean found = stored.stream().map(CompoundTag.class::cast).anyMatch(data -> data.getUUID("npc").equals(npc.getUUID())
                && data.getLong("coins") == 33 && data.getList("goods", Tag.TAG_COMPOUND).getCompound(0).getInt("stock") == 4);
        h.assertTrue(found && !java.nio.file.Files.exists(world.resolve("data/regnum_economy_transaction.dat")),
                "Committed stock and escrow are installed durably by the shared journal");

        credit(h, shopper, Long.MAX_VALUE - CoinBalances.get(shopper));
        h.assertTrue(NpcMerchants.offer(seller, npc, 2, Long.MAX_VALUE, 8) == Result.SUCCESS
                        && NpcMerchants.trade(shopper, npc, quote(seller, npc), true) == Result.LIMIT
                        && NpcMerchants.trade(shopper, npc, quote(seller, npc), false) == Result.LIMIT,
                "Neither participant can overflow during a trade");
        npc.discard();
        h.assertTrue(NpcMerchants.settle(shopper, npc.getUUID()) == Result.PERMISSION
                        && NpcMerchants.settle(seller, npc.getUUID()) == Result.SUCCESS
                        && NpcMerchants.settle(seller, npc.getUUID()) == Result.INVALID
                        && CoinBalances.get(seller) == 103 && seller.getMainHandItem().getCount() == 6,
                "Only the proprietor can recover a missing merchant's escrow, once");
        h.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE, batch = "npc_integration")
    public void cityAuthorityAndTerritoryAreRechecked(GameTestHelper h) {
        var center = h.absolutePos(BlockPos.ZERO);
        // Use a different chunk row from the wilderness fixtures.
        var pos = new BlockPos((center.getX() & ~15) + 8, 64, (center.getZ() & ~15) + 24);
        var chunk = new net.minecraft.world.level.ChunkPos(pos);
        h.getLevel().setChunkForced(chunk.x, chunk.z, true);
        h.runAfterDelay(10, () -> {
            try {
                checkCityAuthority(h, pos);
            } catch (FoundingException failure) {
                throw new IllegalStateException("Could not prepare the NPC city permission fixture", failure);
            } finally {
                h.getLevel().setChunkForced(chunk.x, chunk.z, false);
            }
        });
    }

    private static void checkCityAuthority(GameTestHelper h, BlockPos pos) throws FoundingException {
        var owner = TestPlayers.create(h);
        var citizen = TestPlayers.create(h);
        var level = h.getLevel();
        owner.setPos(Vec3.atCenterOf(pos));
        level.setBlockAndUpdate(pos, ModBlocks.CITY_OBELISK.defaultBlockState());
        var service = KingdomService.get(level.getServer());
        var block = (CityObeliskBlockEntity) level.getBlockEntity(pos);
        service.cities().select(owner, pos);
        var city = service.cities().found(owner, block.getObeliskId(), "NPC Integration", 2);
        var ref = EntityRef.city(city.id());
        var api = NationsApi.get(level.getServer());
        owner.setPos(Vec3.atBottomCenterOf(pos.offset(2, 0, 0)));
        var npc = Npcs.create(owner, NpcRole.MERCHANT, "City merchant");
        h.assertTrue(npc != null && city.id().equals(npc.city()), "City creation binds the live Nations city");
        citizen.setPos(npc.position().add(2, 0, 0));
        service.citizenship().invite(owner, citizen);
        service.citizenship().accept(citizen, city.id());
        h.assertTrue(!Npcs.canManage(citizen, npc), "Citizens do not inherit city management");
        h.assertTrue(api.setRolePermissions(owner, ref, "CITIZEN", Set.of(EconomyPermission.ECONOMY_MANAGE_MARKET)).valid(),
                "Owner can delegate market management");
        h.assertTrue(Npcs.canManage(citizen, npc) && !Npcs.canManageStock(citizen, npc),
                "Market management alone cannot spend city funds or stock; nearby=" + Npcs.nearby(citizen, npc)
                        + ", tracked=" + (level.getEntity(npc.getUUID()) == npc));
        h.assertTrue(api.setRolePermissions(owner, ref, "CITIZEN", Set.of()).valid()
                        && api.transferCity(owner, city.id(), citizen.getUUID()).valid()
                        && Npcs.canManageStock(citizen, npc) && !Npcs.canManage(owner, npc),
                "The new city owner gets current authority and the previous owner loses it");
        h.assertTrue(NpcMerchants.transferFunds(citizen, npc, 1, true) == Result.FUNDS,
                "An unfunded city treasury cannot fund NPC stock");
        npc.setPos(npc.position().add(32, 0, 0));
        citizen.setPos(npc.position().add(2, 0, 0));
        h.assertTrue(!Npcs.inOriginalTerritory(citizen, npc) && !Npcs.canInteract(citizen, npc) && !Npcs.canManage(citizen, npc),
                "A city NPC outside its original claim is inactive");
        npc.discard();
        h.succeed();
    }

    private static Vec3 aboveTest(GameTestHelper h) {
        var origin = h.absolutePos(BlockPos.ZERO);
        return new Vec3(origin.getX() + 0.5, 64, origin.getZ() + 0.5);
    }

    private static RegnumNpc placeMerchant(GameTestHelper h, ServerPlayer seller, Vec3 position, UUID city) {
        var npc = Npcs.TYPE.create(h.getLevel());
        npc.setPos(position);
        npc.configure(seller.getUUID(), city, NpcRole.MERCHANT, "Mason");
        h.assertTrue(h.getLevel().addFreshEntity(npc), "Test merchant joins the level");
        seller.setPos(position.add(-2, 0, 0));
        return npc;
    }

    private static NpcMerchants.Quote quote(ServerPlayer player, RegnumNpc npc) {
        return NpcMerchants.catalog(player, npc).goods().getFirst();
    }

    private static void credit(GameTestHelper h, ServerPlayer player, long amount) {
        try (var operation = WalletPersistence.begin(player)) {
            h.assertTrue(CoinBalances.credit(player, amount) && operation.commit(), "Test balance commits");
        }
    }
}
