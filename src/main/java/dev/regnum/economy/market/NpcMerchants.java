package dev.regnum.economy.market;

import dev.regnum.economy.integration.NationsAccess;
import dev.regnum.economy.inventory.InventoryPlan;
import dev.regnum.economy.money.CoinBalances;
import dev.regnum.economy.npc.NpcRole;
import dev.regnum.economy.npc.Npcs;
import dev.regnum.economy.npc.RegnumNpc;
import dev.sakhno.regnum.nations.api.v1.EconomyPermission;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

public final class NpcMerchants {
    public static final int OFFER_LIMIT = 16;
    public static final int STOCK_LIMIT = 2304;
    public static final int LOT_LIMIT = 64;

    private NpcMerchants() { }

    public enum Result {
        SUCCESS, UNAVAILABLE, STALE, INVALID, PERMISSION, STOCK, STOCK_FULL, ITEM, SPACE, FUNDS, MERCHANT_FUNDS, LIMIT, SAVE_FAILED;

        public Component message() {
            return Npcs.text("result." + name().toLowerCase(Locale.ROOT));
        }
    }

    /** Prices are per lot. Zero disables that direction. */
    public record Quote(UUID id, ItemStack item, int quantity, long ask, long bid, int stock) {
        static Quote of(MerchantStock.Good good) {
            return new Quote(good.id, good.sample.copy(), good.quantity, good.ask, good.bid, good.stock);
        }

        boolean matches(MerchantStock.Good good) {
            return quantity == good.quantity && ask == good.ask && bid == good.bid
                    && ItemStack.isSameItemSameComponents(item, good.sample);
        }

        public long price(boolean purchase) {
            return purchase ? ask : bid;
        }
    }

    public record Catalog(List<Quote> goods, long funds, boolean available) { }

    static boolean validTerms(int quantity, long ask, long bid) {
        return quantity >= 1 && quantity <= LOT_LIMIT && ask >= 0 && bid >= 0;
    }

    public static Catalog catalog(ServerPlayer player, RegnumNpc npc) {
        MarketData data = MarketData.get(player.server);
        MerchantStock merchant = data.merchants.get(npc.getUUID());
        if (!data.available() || !matches(npc, merchant)) return new Catalog(List.of(), 0, false);
        return merchant == null ? new Catalog(List.of(), 0, true)
                : new Catalog(merchant.goods.stream().map(Quote::of).toList(), merchant.coins, true);
    }

    /** Called with a server-created quote held by the current interaction session. */
    public static Result trade(ServerPlayer player, RegnumNpc npc, Quote quote, boolean purchase) {
        if (!isMerchant(npc) || !Npcs.canInteract(player, npc)) return Result.UNAVAILABLE;
        if (purchase && npc.role().feature() == NpcRole.Feature.BUYER) return Result.STALE;
        MarketData data = MarketData.get(player.server);
        MerchantStock merchant = data.merchants.get(npc.getUUID());
        if (!data.available() || !matches(npc, merchant)) return Result.UNAVAILABLE;
        if (merchant == null || quote.price(purchase) <= 0) return Result.STALE;
        var good = merchant.goods.stream().filter(entry -> entry.id.equals(quote.id())).findFirst().orElse(null);
        if (good == null || !quote.matches(good)) return Result.STALE;
        long price = quote.price(purchase);
        int amount = good.quantity;
        InventoryPlan inventory = new InventoryPlan(player.getInventory());
        Result readiness = prepare(inventory, Quote.of(good), CoinBalances.get(player), merchant.coins, purchase);
        if (readiness != Result.SUCCESS) return readiness;
        return commit(data, player, () -> {
            boolean transferred = purchase ? CoinBalances.debit(player, price) : CoinBalances.credit(player, price);
            if (!transferred) return Result.FUNDS;
            merchant.coins += purchase ? price : -price;
            good.stock += purchase ? -amount : amount;
            inventory.apply();
            return Result.SUCCESS;
        });
    }

    /** Read-only preview using the same inventory and balance checks as settlement. */
    public static Result preview(ServerPlayer player, Quote quote, long merchantFunds, boolean purchase) {
        return prepare(new InventoryPlan(player.getInventory()), quote, CoinBalances.get(player), merchantFunds, purchase);
    }

    private static Result prepare(InventoryPlan inventory, Quote quote, long wallet, long merchantFunds, boolean purchase) {
        long price = quote.price(purchase);
        if (price <= 0) return Result.STALE;
        if (purchase) {
            if (quote.quantity() > quote.stock()) return Result.STOCK;
            if (price > wallet) return Result.FUNDS;
            if (price > Long.MAX_VALUE - merchantFunds) return Result.LIMIT;
            return inventory.add(quote.item().copyWithCount(quote.quantity())).isEmpty() ? Result.SUCCESS : Result.SPACE;
        }
        if (quote.quantity() > STOCK_LIMIT - quote.stock()) return Result.STOCK_FULL;
        if (price > merchantFunds) return Result.MERCHANT_FUNDS;
        if (price > Long.MAX_VALUE - wallet) return Result.LIMIT;
        return inventory.remove(quote.item(), quote.quantity()) ? Result.SUCCESS : Result.ITEM;
    }

    public static Result offer(ServerPlayer player, RegnumNpc npc, int quantity, long ask, long bid) {
        if (!isMerchant(npc) || !Npcs.canManageStock(player, npc) || !Npcs.inOriginalTerritory(player, npc)) {
            return Result.PERMISSION;
        }
        if (!validTerms(quantity, ask, bid) || player.getMainHandItem().isEmpty()
                || npc.role().feature() == NpcRole.Feature.BUYER && ask != 0) return Result.INVALID;
        ItemStack sample = player.getMainHandItem().copyWithCount(1);
        MarketData data = MarketData.get(player.server);
        if (!matches(npc, data.merchants.get(npc.getUUID()))) return Result.UNAVAILABLE;
        return commit(data, player, () -> {
            MerchantStock merchant = data.merchants.computeIfAbsent(npc.getUUID(),
                    ignored -> new MerchantStock(npc.proprietor(), npc.city()));
            for (var good : merchant.goods) {
                if (ItemStack.isSameItemSameComponents(good.sample, sample)) {
                    good.quantity = quantity;
                    good.ask = ask;
                    good.bid = bid;
                    return Result.SUCCESS;
                }
            }
            if (merchant.goods.size() >= OFFER_LIMIT) return Result.LIMIT;
            merchant.goods.add(new MerchantStock.Good(UUID.randomUUID(), sample, quantity, ask, bid, 0));
            return Result.SUCCESS;
        });
    }

    public static Result removeOffer(ServerPlayer player, RegnumNpc npc, int slot) {
        if (!isMerchant(npc) || !Npcs.canManageStock(player, npc)) return Result.PERMISSION;
        MarketData data = MarketData.get(player.server);
        MerchantStock merchant = data.merchants.get(npc.getUUID());
        if (!matches(npc, merchant)) return Result.UNAVAILABLE;
        var good = goodAt(merchant, slot);
        if (good == null) return Result.INVALID;
        if (good.stock != 0) return Result.STOCK;
        return commit(data, player, () -> {
            merchant.goods.remove(good);
            return Result.SUCCESS;
        });
    }

    public static Result transferStock(ServerPlayer player, RegnumNpc npc, int slot, int amount, boolean deposit) {
        if (!isMerchant(npc) || !Npcs.canManageStock(player, npc)
                || (deposit && !Npcs.inOriginalTerritory(player, npc))) return Result.PERMISSION;
        MarketData data = MarketData.get(player.server);
        MerchantStock merchant = data.merchants.get(npc.getUUID());
        if (!matches(npc, merchant)) return Result.UNAVAILABLE;
        var good = goodAt(merchant, slot);
        if (good == null || amount <= 0 || amount > STOCK_LIMIT) return Result.INVALID;
        if (deposit ? amount > STOCK_LIMIT - good.stock : amount > good.stock) return Result.STOCK;
        InventoryPlan inventory = new InventoryPlan(player.getInventory());
        if (deposit) {
            if (!inventory.remove(good.sample, amount)) return Result.ITEM;
        } else if (!inventory.add(good.sample.copyWithCount(amount)).isEmpty()) return Result.SPACE;
        return commit(data, player, () -> {
            good.stock += deposit ? amount : -amount;
            inventory.apply();
            return Result.SUCCESS;
        });
    }

    public static Result transferFunds(ServerPlayer player, RegnumNpc npc, long amount, boolean deposit) {
        if (!isMerchant(npc) || !Npcs.canManageStock(player, npc)
                || (deposit && !Npcs.inOriginalTerritory(player, npc))) return Result.PERMISSION;
        if (amount <= 0) return Result.INVALID;
        if (npc.city() != null && !deposit
                && !NationsAccess.permits(player, npc.city(), EconomyPermission.ECONOMY_DEPOSIT_TREASURY)) {
            return Result.PERMISSION;
        }
        MarketData data = MarketData.get(player.server);
        if (!matches(npc, data.merchants.get(npc.getUUID()))) return Result.UNAVAILABLE;
        return commit(data, player, () -> {
            MerchantStock merchant = data.merchants.computeIfAbsent(npc.getUUID(),
                    ignored -> new MerchantStock(npc.proprietor(), npc.city()));
            MarketData.Treasury treasury = npc.city() == null ? null : data.treasury(npc.city());
            long source = treasury == null ? CoinBalances.get(player) : treasury.coins;
            if (deposit ? amount > source : amount > merchant.coins) return Result.FUNDS;
            if (deposit ? amount > Long.MAX_VALUE - merchant.coins : amount > Long.MAX_VALUE - source) return Result.LIMIT;
            if (treasury != null) {
                treasury.coins += deposit ? -amount : amount;
            } else {
                boolean transferred = deposit ? CoinBalances.debit(player, amount) : CoinBalances.credit(player, amount);
                if (!transferred) return Result.FUNDS;
            }
            merchant.coins += deposit ? amount : -amount;
            return Result.SUCCESS;
        });
    }

    /** Return the entire escrow even if its entity is missing. Political accounts are never reassigned. */
    public static Result settle(ServerPlayer player, UUID npc) {
        if (!Npcs.canAct(player)) return Result.PERMISSION;
        MarketData data = MarketData.get(player.server);
        if (!data.available()) return Result.UNAVAILABLE;
        MerchantStock merchant = data.merchants.get(npc);
        if (merchant == null) return Result.INVALID;
        if (merchant.city == null ? !merchant.proprietor.equals(player.getUUID())
                : !NationsAccess.permits(player, merchant.city, EconomyPermission.ECONOMY_MANAGE_MARKET)
                    || !NationsAccess.permits(player, merchant.city, EconomyPermission.ECONOMY_WITHDRAW_TREASURY)
                    || !NationsAccess.permits(player, merchant.city, EconomyPermission.ECONOMY_DEPOSIT_TREASURY)) {
            return Result.PERMISSION;
        }
        InventoryPlan inventory = new InventoryPlan(player.getInventory());
        for (var good : merchant.goods) {
            if (good.stock > 0 && !inventory.add(good.sample.copyWithCount(good.stock)).isEmpty()) return Result.SPACE;
        }
        return commit(data, player, () -> {
            if (merchant.city == null) {
                if (merchant.coins > 0 && !CoinBalances.credit(player, merchant.coins)) return Result.LIMIT;
            } else {
                var treasury = data.treasury(merchant.city);
                if (merchant.coins > Long.MAX_VALUE - treasury.coins) return Result.LIMIT;
                treasury.coins += merchant.coins;
            }
            inventory.apply();
            data.merchants.remove(npc);
            return Result.SUCCESS;
        });
    }

    public static Result clearForRemoval(ServerPlayer player, RegnumNpc npc) {
        MarketData data = MarketData.get(player.server);
        if (!data.available()) return Result.UNAVAILABLE;
        MerchantStock merchant = data.merchants.get(npc.getUUID());
        if (merchant == null) return Result.SUCCESS;
        if (!matches(npc, merchant) || !merchant.empty()) return Result.STOCK;
        return commit(data, player, () -> {
            data.merchants.remove(npc.getUUID());
            return Result.SUCCESS;
        });
    }

    private static Result commit(MarketData data, ServerPlayer player, Supplier<Result> transaction) {
        if (!data.available()) return Result.UNAVAILABLE;
        // Cursor and inventory-crafting items are not included in vanilla player
        // NBT. Do not checkpoint an inventory from which they have been removed.
        if (!Npcs.canAct(player) || player.containerMenu != player.inventoryMenu
                || !player.containerMenu.getCarried().isEmpty()
                || !player.inventoryMenu.getCraftSlots().isEmpty()) return Result.UNAVAILABLE;
        Result[] result = {Result.SAVE_FAILED};
        int committed = data.transaction(player, () -> {
            result[0] = transaction.get();
            return result[0] == Result.SUCCESS ? 1 : 0;
        });
        return committed == 0 && result[0] == Result.SUCCESS ? Result.SAVE_FAILED : result[0];
    }

    private static boolean isMerchant(RegnumNpc npc) {
        return npc.role().usesMerchantStock();
    }

    private static boolean matches(RegnumNpc npc, MerchantStock merchant) {
        return merchant == null || merchant.proprietor.equals(npc.proprietor()) && Objects.equals(merchant.city, npc.city());
    }

    private static MerchantStock.Good goodAt(MerchantStock merchant, int slot) {
        return merchant == null || slot < 1 || slot > merchant.goods.size() ? null : merchant.goods.get(slot - 1);
    }
}
