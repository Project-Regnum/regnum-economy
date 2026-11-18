package dev.regnum.economy.market;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class MerchantPersistenceTests implements FabricGameTest {
    public static boolean fundCity(net.minecraft.server.level.ServerPlayer player, UUID city, long amount) {
        var data = MarketData.get(player.server);
        return data.transaction(player, () -> {
            if (!dev.regnum.economy.money.CoinBalances.debit(player, amount)) return 0;
            data.treasury(city).coins += amount;
            return 1;
        }) == 1;
    }

    public static long cityFunds(net.minecraft.server.level.ServerPlayer player, UUID city) {
        return MarketData.get(player.server).treasury(city).coins;
    }

    @GameTest(template = EMPTY_STRUCTURE, batch = "npc_integration")
    public void malformedEscrowLocksAndPreservesOriginalData(GameTestHelper h) throws Exception {
        var registries = h.getLevel().registryAccess();
        var original = new MarketData();
        UUID id = UUID.randomUUID();
        var stock = new MerchantStock(UUID.randomUUID(), null);
        stock.coins = 19;
        stock.goods.add(new MerchantStock.Good(UUID.randomUUID(), new ItemStack(Items.STONE), 2, 10, 8, 2304));
        original.merchants.put(id, stock);
        var serialized = original.save(new CompoundTag(), registries);
        var load = MarketData.class.getDeclaredMethod("load", CompoundTag.class, HolderLookup.Provider.class);
        load.setAccessible(true);
        var restored = (MarketData) load.invoke(null, serialized.copy(), registries);
        h.assertTrue(restored.available() && restored.merchants.get(id).coins == 19
                && restored.merchants.get(id).goods.getFirst().stock == 2304, "Maximum escrow round-trips exactly");
        List<Consumer<CompoundTag>> corruptions = List.of(
                tag -> tag.putInt("version", 4),
                tag -> merchant(tag).putLong("coins", -1),
                tag -> merchant(tag).putInt("coins", 19),
                tag -> good(tag).putInt("stock", 2305),
                tag -> good(tag).putInt("quantity", 0),
                tag -> good(tag).putLong("bid", -1),
                tag -> merchant(tag).getList("goods", Tag.TAG_COMPOUND).add(good(tag).copy()),
                tag -> good(tag).getCompound("sample").putInt("count", 2),
                tag -> good(tag).getCompound("sample").putString("id", "regnum-economy:missing_item"),
                tag -> tag.getList("merchants", Tag.TAG_COMPOUND).add(merchant(tag).copy()));
        for (var corrupt : corruptions) {
            var damaged = serialized.copy();
            corrupt.accept(damaged);
            var rejected = (MarketData) load.invoke(null, damaged, registries);
            h.assertTrue(!rejected.available() && rejected.save(new CompoundTag(), registries).equals(damaged),
                    "Malformed escrow is locked and its exact original NBT survives save");
        }
        for (int version : new int[]{1, 2}) {
            var legacy = new MarketData().save(new CompoundTag(), registries);
            legacy.putInt("version", version);
            legacy.remove("merchants");
            var migrated = (MarketData) load.invoke(null, legacy, registries);
            h.assertTrue(migrated.available() && migrated.merchants.isEmpty(), "Legacy market versions migrate with empty NPC escrow");
        }
        h.succeed();
    }

    private static CompoundTag merchant(CompoundTag tag) {
        return tag.getList("merchants", Tag.TAG_COMPOUND).getCompound(0);
    }

    private static CompoundTag good(CompoundTag tag) {
        return merchant(tag).getList("goods", Tag.TAG_COMPOUND).getCompound(0);
    }
}
