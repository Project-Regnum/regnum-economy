package dev.regnum.economy.market;

import dev.regnum.economy.RegnumEconomy;
import dev.regnum.economy.integration.AuthAccess;
import dev.regnum.economy.integration.NationsAccess;
import dev.regnum.economy.inventory.InventoryPlan;
import dev.regnum.economy.money.CoinBalances;
import dev.regnum.economy.money.Denomination;
import dev.regnum.economy.money.WalletPersistence;
import dev.sakhno.regnum.nations.api.v1.EconomyPermission;
import dev.sakhno.regnum.nations.api.v1.EntityRef;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static dev.regnum.economy.market.MarketData.*;

public final class Markets {
    private static final String TEXT = "message.regnum-economy.market.";
    private static final int PAGE_SIZE = 8;
    private static final Map<UUID, Site> SELECTED = new HashMap<>();
    private static final Block BLOCK = new Block(BlockBehaviour.Properties.of()
            .strength(2.5f, 1200f).pushReaction(PushReaction.BLOCK));

    private Markets() {
    }

    public static boolean blocksSave(Object data) {
        return data instanceof MarketData && WalletPersistence.blocksMarketSave();
    }

    public static void register() {
        var id = RegnumEconomy.id("market");
        Registry.register(BuiltInRegistries.BLOCK, id, BLOCK);
        Registry.register(BuiltInRegistries.ITEM, id, new BlockItem(BLOCK, new Item.Properties()));

        // Consume only a normal click on the market itself. Other interactions still reach Nations.
        var event = UseBlockCallback.EVENT;
        var phase = RegnumEconomy.id("public_market");
        event.addPhaseOrdering(phase, Event.DEFAULT_PHASE);
        event.register(phase, (player, level, hand, hit) -> {
            BlockPos pos = hit.getBlockPos();
            if (!level.getBlockState(pos).is(BLOCK) || player.isShiftKeyDown()) {
                return InteractionResult.PASS;
            }
            if (player instanceof ServerPlayer serverPlayer && hand == InteractionHand.MAIN_HAND
                    && serverPlayer.canInteractWithBlock(pos, 0)) {
                open(serverPlayer, Site.at(serverPlayer.serverLevel(), pos), 1);
            }
            return InteractionResult.SUCCESS;
        });

        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, entity) -> {
            if (level instanceof ServerLevel serverLevel && state.is(BLOCK)) {
                var data = MarketData.get(serverLevel.getServer());
                if (!data.available() || data.markets.containsKey(Site.at(serverLevel, pos))) {
                    player.displayClientMessage(text("protected"), true);
                    return false;
                }
            }
            return true;
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> SELECTED.remove(handler.player.getUUID()));
        MarketCommands.register();
        TreasuryPackets.register();
    }

    static Component text(String key, Object... args) {
        return Component.translatable(TEXT + key, args);
    }

    static int fail(ServerPlayer player, String key) {
        player.sendSystemMessage(text(key).copy().withStyle(ChatFormatting.RED));
        return 0;
    }

    private static boolean canUse(ServerPlayer player) {
        return player.isAlive() && !player.isSpectator() && !player.hasDisconnected() && AuthAccess.permits(player)
                && player.getServer().getPlayerList().getPlayer(player.getUUID()) == player
                && player.containerMenu == player.inventoryMenu && player.containerMenu.getCarried().isEmpty();
    }

    private static Site lookingAtMarket(ServerPlayer player) {
        HitResult target = player.pick(player.blockInteractionRange(), 1.0f, false);
        if (target instanceof BlockHitResult blockHit && target.getType() == HitResult.Type.BLOCK
                && player.serverLevel().getBlockState(blockHit.getBlockPos()).is(BLOCK)) {
            return Site.at(player.serverLevel(), blockHit.getBlockPos());
        }
        return null;
    }

    private static boolean nearby(ServerPlayer player, Site site) {
        ServerLevel level = player.serverLevel();
        return site != null && level.dimension().location().equals(site.dimension())
                && player.canInteractWithBlock(site.position(), 0)
                && level.getBlockState(site.position()).is(BLOCK);
    }

    private static Market selected(ServerPlayer player, MarketData data) {
        if (!canUse(player)) {
            fail(player, "login");
            return null;
        }
        if (!data.available()) {
            fail(player, "unavailable");
            return null;
        }
        Site site = lookingAtMarket(player);
        if (!nearby(player, site)) {
            site = SELECTED.get(player.getUUID());
        }
        if (!nearby(player, site)) {
            fail(player, "nearby");
            return null;
        }
        Market market = data.markets.get(site);
        if (market == null) {
            fail(player, "unregistered");
            return null;
        }
        SELECTED.put(player.getUUID(), site);
        return market;
    }

    private static NationsAccess.City owningCity(ServerPlayer player, Market market) {
        if (market.standalone) {
            return null;
        }
        var city = NationsAccess.cityAt(player.serverLevel(), market.site.position());
        return city != null && city.id().equals(market.city) ? city : null;
    }

    private static boolean active(Market market, NationsAccess.City city) {
        return market.standalone ? !NationsAccess.installed() : city != null;
    }

    private static boolean manages(ServerPlayer player, Market market, EconomyPermission permission) {
        // Old standalone sites have no political entity; only explicit admin recovery remains.
        return market.standalone ? player.hasPermissions(3)
                : NationsAccess.permits(player, market.city, permission);
    }

    static int registerMarket(ServerPlayer player, String testName) {
        return MarketData.get(player.server).transaction(player, () -> registerMarketImpl(player, testName));
    }

    private static int registerMarketImpl(ServerPlayer player, String testName) {
        if (!canUse(player)) {
            return fail(player, "login");
        }
        var data = MarketData.get(player.server);
        if (!data.available()) {
            return fail(player, "unavailable");
        }
        Site site = lookingAtMarket(player);
        if (site == null || !nearby(player, site)) {
            return fail(player, "nearby");
        }
        if (data.markets.containsKey(site)) {
            return fail(player, "already_registered");
        }

        var city = NationsAccess.cityAt(player.serverLevel(), site.position());
        boolean standalone = !NationsAccess.installed();
        if (!standalone && city == null) {
            return fail(player, "needs_city");
        }
        if (standalone ? !player.hasPermissions(3)
                : !NationsAccess.permits(player, city.id(), EconomyPermission.ECONOMY_CREATE_MARKET)) {
            return fail(player, "permission");
        }
        String name = standalone ? testName == null ? site.position().toShortString() : testName.strip() : city.name();
        if (name.isEmpty() || name.length() > 48) {
            return fail(player, "name");
        }
        Market market = new Market(site, standalone ? UUID.randomUUID() : city.id(),
                player.getUUID(), name, standalone);
        data.markets.put(site, market);
        data.setDirty();
        SELECTED.put(player.getUUID(), site);
        data.success(player, text("registered", name));
        return 1;
    }

    static int closeMarket(ServerPlayer player) {
        return MarketData.get(player.server).transaction(player, () -> closeMarketImpl(player));
    }

    private static int closeMarketImpl(ServerPlayer player) {
        var data = MarketData.get(player.server);
        Market market = selected(player, data);
        if (market == null) {
            return 0;
        }
        if (!manages(player, market, EconomyPermission.ECONOMY_MANAGE_MARKET)) {
            return fail(player, "permission");
        }
        if (!market.orders.isEmpty() || market.claims.values().stream().anyMatch(claim -> !claim.empty())) {
            return fail(player, "assets_pending");
        }
        var treasury = data.treasuries.get(EntityRef.city(market.city));
        if (treasury != null && treasury.coins > 0
                && data.markets.values().stream().noneMatch(other -> other != market && other.city.equals(market.city))) {
            return fail(player, "treasury_pending");
        }
        data.markets.remove(market.site);
        if (market.standalone) {
            data.treasuries.remove(EntityRef.city(market.city));
        }
        SELECTED.remove(player.getUUID());
        data.setDirty();
        data.success(player, text("closed"));
        return 1;
    }

    private static void open(ServerPlayer player, Site site, int page) {
        if (!canUse(player)) {
            fail(player, "login");
            return;
        }
        if (nearby(player, site)) {
            SELECTED.put(player.getUUID(), site);
            list(player, page, false);
        }
    }

    static int list(ServerPlayer player, int page, boolean own) {
        var data = MarketData.get(player.server);
        Market market = selected(player, data);
        if (market == null) {
            return 0;
        }
        var city = owningCity(player, market);
        String name = city == null ? market.name : city.name();
        player.sendSystemMessage(text(own ? "my_orders" : "title", name).copy().withStyle(ChatFormatting.GOLD));
        boolean open = active(market, city);
        if (!open) {
            player.sendSystemMessage(text("inactive"));
        }
        var orders = market.orders.values().stream().filter(order -> !own || order.owner.equals(player.getUUID())).toList();
        int pages = Math.max(1, (orders.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.min(page, pages);
        if (orders.isEmpty()) {
            player.sendSystemMessage(text("empty"));
        }
        for (int i = (current - 1) * PAGE_SIZE; i < Math.min(current * PAGE_SIZE, orders.size()); i++) {
            Order order = orders.get(i);
            MutableComponent line = text(order.kind == Kind.SELL ? "sell_row" : "buy_row",
                    order.id, order.quantity, order.item.getHoverName(), Long.toString(order.price)).copy();
            long total = order.total(order.quantity);
            if (order.taxRate > 0) {
                line.append(" ").append(text("net", Long.toString(total - order.tax(order.quantity)), order.taxRate));
            }
            if (order.owner.equals(player.getUUID())) {
                line.append(" ").append(action("cancel_action", "/market cancel " + order.id, ClickEvent.Action.SUGGEST_COMMAND));
            } else if (open) {
                boolean sale = order.kind == Kind.SELL;
                line.append(" ").append(action(sale ? "buy_action" : "deliver_action",
                        "/market " + (sale ? "buy " : "deliver ") + order.id + " 1", ClickEvent.Action.SUGGEST_COMMAND));
            }
            player.sendSystemMessage(line);
        }
        String command = own ? "/market mine " : "/market list ";
        MutableComponent navigation = text("page", current, pages).copy();
        if (current > 1) {
            navigation.append(" ").append(action("previous", command + (current - 1), ClickEvent.Action.RUN_COMMAND));
        }
        if (current < pages) {
            navigation.append(" ").append(action("next", command + (current + 1), ClickEvent.Action.RUN_COMMAND));
        }
        navigation.append(" ").append(action(own ? "list_action" : "mine_action", own ? "/market list" : "/market mine",
                ClickEvent.Action.RUN_COMMAND));
        Claim claim = market.claims.get(player.getUUID());
        if (claim != null && !claim.empty()) {
            long items = claim.items.stream().mapToLong(ItemStack::getCount).sum();
            navigation.append(" ").append(text("pending", items, Long.toString(claim.coins)))
                    .append(" ").append(action("claim_action", "/market claim", ClickEvent.Action.RUN_COMMAND));
        }
        player.sendSystemMessage(navigation);
        return 1;
    }

    private static MutableComponent action(String label, String command, ClickEvent.Action type) {
        return text(label).copy().withStyle(style ->
                style.withColor(ChatFormatting.AQUA).withClickEvent(new ClickEvent(type, command)));
    }

    static int post(ServerPlayer player, Kind kind, ItemStack item, int quantity, long price) {
        return MarketData.get(player.server).transaction(player, () -> postImpl(player, kind, item, quantity, price));
    }

    private static int postImpl(ServerPlayer player, Kind kind, ItemStack item, int quantity, long price) {
        var data = MarketData.get(player.server);
        Market market = selected(player, data);
        if (market == null) {
            return 0;
        }
        if (!active(market, owningCity(player, market))) {
            return fail(player, "inactive");
        }
        if (market.orders.size() >= MAX_ORDERS || data.nextOrder == Integer.MAX_VALUE) {
            return fail(player, "full");
        }
        if (quantity < 1 || quantity > MAX_QUANTITY || price < 1 || price > Long.MAX_VALUE / quantity
                || item.isEmpty() || Denomination.of(item) != null || (kind == Kind.SELL && quantity > item.getCount())) {
            return fail(player, "offer_invalid");
        }
        if (kind == Kind.SELL && !hasClaimSpace(market, player.getUUID(), Math.ceilDiv(quantity, item.getMaxStackSize()))) {
            return fail(player, "claim_storage");
        }
        var order = new Order(data.nextOrder, kind, player.getUUID(), item.copyWithCount(1),
                quantity, price, data.treasury(market.city).taxRate);
        if (kind == Kind.BUY) {
            if (!CoinBalances.debit(player, order.total(quantity))) {
                return fail(player, "funds");
            }
        } else {
            item.shrink(quantity);
            player.getInventory().setChanged();
        }
        market.orders.put(order.id, order);
        data.nextOrder++;
        data.setDirty();
        data.success(player, text("posted", order.id, Long.toString(order.total(quantity)),
                Long.toString(order.total(quantity) - order.tax(quantity))));
        return 1;
    }

    static int fulfill(ServerPlayer player, Kind kind, int id, int quantity) {
        return MarketData.get(player.server).transaction(player, () -> fulfillImpl(player, kind, id, quantity));
    }

    private static int fulfillImpl(ServerPlayer player, Kind kind, int id, int quantity) {
        var data = MarketData.get(player.server);
        Market market = selected(player, data);
        if (market == null) {
            return 0;
        }
        if (!active(market, owningCity(player, market))) {
            return fail(player, "inactive");
        }
        Order order = market.orders.get(id);
        if (order == null || order.kind != kind || quantity < 1 || quantity > order.quantity) {
            return fail(player, "order_missing");
        }
        if (order.owner.equals(player.getUUID())) {
            return fail(player, "own_order");
        }

        long gross = order.total(quantity);
        long tax = order.tax(quantity);
        long net = gross - tax;
        var treasury = data.treasury(market.city);
        var sellerId = kind == Kind.SELL ? order.owner : player.getUUID();
        var buyerId = kind == Kind.SELL ? player.getUUID() : order.owner;
        Claim buyer = market.claims.get(buyerId);
        int extraStacks = buyer == null ? Math.ceilDiv(quantity, order.item.getMaxStackSize())
                : buyer.extraStacks(order.item, quantity);
        if (!hasClaimSpace(market, buyerId, extraStacks)) {
            return fail(player, "claim_storage");
        }
        Claim seller = market.claims.get(sellerId);
        long pending = seller == null ? 0 : seller.coins;
        if (net > Long.MAX_VALUE - pending || tax > Long.MAX_VALUE - treasury.coins) {
            return fail(player, "capacity");
        }

        if (kind == Kind.SELL) {
            if (!CoinBalances.debit(player, gross)) {
                return fail(player, "funds");
            }
        } else if (!removeItems(player.getInventory(), order.item, quantity)) {
            return fail(player, "items");
        }

        market.claim(buyerId).addItems(order.item, quantity);
        if (net > 0) {
            market.claim(sellerId).coins += net;
        }
        treasury.coins += tax;
        order.taxRemainder = (int) ((gross % 100 * order.taxRate + order.taxRemainder) % 100);
        order.quantity -= quantity;
        if (order.quantity == 0) {
            market.orders.remove(id);
        }
        data.setDirty();
        data.success(player, text("filled", quantity, order.item.getHoverName(), Long.toString(gross),
                Long.toString(net)).copy().append(" ").append(action("claim_action", "/market claim", ClickEvent.Action.RUN_COMMAND)));
        return 1;
    }

    private static boolean hasClaimSpace(Market market, UUID player, int extraStacks) {
        if (extraStacks == 0) {
            return true;
        }
        Claim pending = market.claims.get(player);
        int used = pending == null ? 0 : pending.items.size();
        for (Order order : market.orders.values()) {
            // Reserve enough space to return every unsold stack if the owner cancels the listing.
            if (order.kind == Kind.SELL && order.owner.equals(player)) {
                used += Math.ceilDiv(order.quantity, order.item.getMaxStackSize());
            }
        }
        return extraStacks <= MAX_CLAIM_STACKS - used;
    }

    private static boolean removeItems(Inventory inventory, ItemStack template, int quantity) {
        long available = 0;
        for (ItemStack stack : inventory.items) {
            if (ItemStack.isSameItemSameComponents(stack, template)) {
                available += stack.getCount();
            }
        }
        if (available < quantity) {
            return false;
        }
        for (int slot = 0; slot < inventory.items.size() && quantity > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (ItemStack.isSameItemSameComponents(stack, template)) {
                int removed = Math.min(quantity, stack.getCount());
                inventory.removeItem(slot, removed);
                quantity -= removed;
            }
        }
        inventory.setChanged();
        return true;
    }

    static int cancel(ServerPlayer player, int id) {
        return MarketData.get(player.server).transaction(player, () -> cancelImpl(player, id));
    }

    private static int cancelImpl(ServerPlayer player, int id) {
        var data = MarketData.get(player.server);
        Market market = selected(player, data);
        if (market == null) {
            return 0;
        }
        Order order = market.orders.get(id);
        if (order == null || !order.owner.equals(player.getUUID())) {
            return fail(player, "order_missing");
        }
        Claim claim = market.claim(player.getUUID());
        if (order.kind == Kind.SELL) {
            claim.addItems(order.item, order.quantity);
        } else {
            long refund = order.total(order.quantity);
            if (refund > Long.MAX_VALUE - claim.coins) {
                return fail(player, "capacity");
            }
            claim.coins += refund;
        }
        market.orders.remove(id);
        data.setDirty();
        data.success(player, text("cancelled").copy().append(" ")
                .append(action("claim_action", "/market claim", ClickEvent.Action.RUN_COMMAND)));
        return 1;
    }

    static int claim(ServerPlayer player) {
        return MarketData.get(player.server).transaction(player, () -> claimImpl(player));
    }

    private static int claimImpl(ServerPlayer player) {
        var data = MarketData.get(player.server);
        Market market = selected(player, data);
        if (market == null) {
            return 0;
        }
        Claim claim = market.claims.get(player.getUUID());
        if (claim == null || claim.empty()) {
            return fail(player, "nothing_to_claim");
        }

        InventoryPlan plan = new InventoryPlan(player.getInventory());
        var remaining = new ArrayList<ItemStack>();
        long receivedItems = 0;
        for (ItemStack item : claim.items) {
            ItemStack left = plan.add(item);
            receivedItems += item.getCount() - left.getCount();
            if (!left.isEmpty()) {
                remaining.add(left);
            }
        }
        long coins = Math.min(claim.coins, Long.MAX_VALUE - CoinBalances.get(player));
        if (receivedItems == 0 && coins == 0) {
            return fail(player, "claim_full");
        }
        if (coins > 0 && !CoinBalances.credit(player, coins)) {
            return fail(player, "claim_full");
        }
        plan.apply();
        claim.coins -= coins;
        claim.items.clear();
        claim.items.addAll(remaining);
        if (claim.empty()) {
            market.claims.remove(player.getUUID());
        }
        data.setDirty();
        data.success(player, text("claimed", receivedItems, Long.toString(coins)));
        return 1;
    }

    static int treasury(ServerPlayer player, Integer rate, long withdraw) {
        return rate == null && withdraw == 0 ? treasuryImpl(player, null, 0)
                : MarketData.get(player.server).transaction(player, () -> treasuryImpl(player, rate, withdraw));
    }

    private static int treasuryImpl(ServerPlayer player, Integer rate, long withdraw) {
        var data = MarketData.get(player.server);
        Market market = selected(player, data);
        if (market == null) {
            return 0;
        }
        var city = owningCity(player, market);
        EconomyPermission permission = rate != null ? EconomyPermission.ECONOMY_SET_TAX
                : withdraw > 0 ? EconomyPermission.ECONOMY_WITHDRAW_TREASURY : EconomyPermission.ECONOMY_VIEW_TREASURY;
        if (!manages(player, market, permission) || !manages(player, market, EconomyPermission.ECONOMY_VIEW_TREASURY)
                || (rate != null && !active(market, city))) {
            return fail(player, "permission");
        }
        Treasury treasury = data.treasury(market.city);
        if (rate != null) {
            treasury.taxRate = rate;
            data.setDirty();
        }
        if (withdraw > 0) {
            if (withdraw > treasury.coins) {
                return fail(player, "funds");
            }
            if (!CoinBalances.credit(player, withdraw)) {
                return fail(player, "capacity");
            }
            treasury.coins -= withdraw;
            data.setDirty();
        }
        data.success(player, text("treasury", Long.toString(treasury.coins), treasury.taxRate));
        return 1;
    }
}
