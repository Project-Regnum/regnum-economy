package dev.regnum.economy.market;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class MarketData extends SavedData {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int VERSION = 1;
    private static final String FILE_ID = "regnum_economy_markets";
    static final int MAX_ORDERS = 128;
    static final int MAX_QUANTITY = 2304;
    static final int MAX_CLAIM_STACKS = MAX_QUANTITY;
    private static final Factory<MarketData> FACTORY = new Factory<>(MarketData::new, MarketData::load, null);
    private static final MarketData UNAVAILABLE = new MarketData();

    static {
        UNAVAILABLE.unreadable = new CompoundTag();
    }

    record Site(ResourceLocation dimension, BlockPos position) {
        Site {
            position = position.immutable();
        }

        static Site at(ServerLevel level, BlockPos position) {
            return new Site(level.dimension().location(), position);
        }
    }

    enum Kind {
        SELL, BUY
    }

    static final class Market {
        final Site site;
        final UUID city;
        final UUID founder;
        final String name;
        final boolean standalone;
        final Map<Integer, Order> orders = new LinkedHashMap<>();
        final Map<UUID, Claim> claims = new HashMap<>();

        Market(Site site, UUID city, UUID founder, String name, boolean standalone) {
            this.site = site;
            this.city = city;
            this.founder = founder;
            this.name = name;
            this.standalone = standalone;
        }

        Claim claim(UUID player) {
            return claims.computeIfAbsent(player, ignored -> new Claim());
        }
    }

    static final class Order {
        final int id;
        final Kind kind;
        final UUID owner;
        final ItemStack item;
        final long price;
        final int taxRate;
        int quantity;
        int taxRemainder;

        Order(int id, Kind kind, UUID owner, ItemStack item, int quantity, long price, int taxRate) {
            if (id < 1 || item.isEmpty() || item.getCount() != 1 || quantity < 1 || quantity > MAX_QUANTITY
                    || price < 1 || price > Long.MAX_VALUE / quantity || taxRate < 0 || taxRate > 100) {
                throw new IllegalArgumentException("Invalid market order");
            }
            this.id = id;
            this.kind = kind;
            this.owner = owner;
            this.item = item;
            this.quantity = quantity;
            this.price = price;
            this.taxRate = taxRate;
        }

        long total(int amount) {
            return price * amount;
        }

        long tax(int amount) {
            long gross = total(amount);
            return gross / 100 * taxRate + (gross % 100 * taxRate + taxRemainder) / 100;
        }
    }

    static final class Claim {
        final List<ItemStack> items = new ArrayList<>();
        long coins;

        int extraStacks(ItemStack item, int quantity) {
            int max = item.getMaxStackSize();
            for (ItemStack stack : items) {
                if (ItemStack.isSameItemSameComponents(stack, item)) {
                    quantity -= Math.max(0, max - stack.getCount());
                    if (quantity <= 0) {
                        return 0;
                    }
                }
            }
            return Math.ceilDiv(quantity, max);
        }

        void addItems(ItemStack item, int quantity) {
            int max = item.getMaxStackSize();
            for (ItemStack stack : items) {
                if (ItemStack.isSameItemSameComponents(stack, item)) {
                    int moved = Math.min(quantity, Math.max(0, max - stack.getCount()));
                    stack.grow(moved);
                    quantity -= moved;
                    if (quantity == 0) {
                        return;
                    }
                }
            }
            while (quantity > 0) {
                int moved = Math.min(quantity, max);
                items.add(item.copyWithCount(moved));
                quantity -= moved;
            }
        }

        boolean empty() {
            return coins == 0 && items.isEmpty();
        }
    }

    static final class Treasury {
        int taxRate;
        long coins;
    }

    final Map<Site, Market> markets = new HashMap<>();
    final Map<UUID, Treasury> treasuries = new HashMap<>();
    int nextOrder = 1;
    private CompoundTag unreadable;

    static MarketData get(MinecraftServer server) {
        var storage = server.overworld().getDataStorage();
        MarketData data = storage.get(FACTORY, FILE_ID);
        if (data != null) {
            return data;
        }
        // Vanilla returns null both when the file is missing and when decoding it fails.
        if (!Files.notExists(server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(FILE_ID + ".dat"))) {
            return UNAVAILABLE;
        }
        data = new MarketData();
        storage.set(FILE_ID, data);
        return data;
    }

    boolean available() {
        return unreadable == null;
    }

    Treasury treasury(UUID city) {
        return treasuries.computeIfAbsent(city, ignored -> new Treasury());
    }

    private static MarketData load(CompoundTag tag, HolderLookup.Provider registries) {
        MarketData data = new MarketData();
        try {
            if (!tag.contains("version", Tag.TAG_INT) || tag.getInt("version") != VERSION) {
                throw new IllegalArgumentException("Unsupported market data");
            }
            data.nextOrder = tag.getInt("next_order");
            if (!tag.contains("next_order", Tag.TAG_INT) || data.nextOrder < 1) {
                throw new IllegalArgumentException("Invalid market order counter");
            }
            var orderIds = new HashSet<Integer>();
            for (Tag value : compounds(tag, "markets")) {
                Market market = loadMarket((CompoundTag) value, registries);
                for (int id : market.orders.keySet()) {
                    if (id >= data.nextOrder || !orderIds.add(id)) {
                        throw new IllegalArgumentException("Duplicate or invalid market order ID");
                    }
                }
                if (data.markets.putIfAbsent(market.site, market) != null) {
                    throw new IllegalArgumentException("Duplicate market location");
                }
            }
            for (Tag value : compounds(tag, "treasuries")) {
                CompoundTag entry = (CompoundTag) value;
                Treasury treasury = new Treasury();
                treasury.taxRate = entry.getInt("rate");
                treasury.coins = entry.getLong("coins");
                if (!entry.contains("rate", Tag.TAG_INT) || !entry.contains("coins", Tag.TAG_LONG)
                        || treasury.taxRate < 0 || treasury.taxRate > 100 || treasury.coins < 0
                        || data.treasuries.putIfAbsent(entry.getUUID("city"), treasury) != null) {
                    throw new IllegalArgumentException("Invalid treasury");
                }
            }
        } catch (RuntimeException e) {
            LOGGER.error("Regnum Economy could not read the market data. Markets are locked until it is repaired.", e);
            data.unreadable = tag.copy();
        }
        return data;
    }

    private static Market loadMarket(CompoundTag entry, HolderLookup.Provider registries) {
        if (!entry.contains("pos", Tag.TAG_LONG) || !entry.contains("standalone", Tag.TAG_BYTE)
                || entry.getString("name").isBlank()) {
            throw new IllegalArgumentException("Invalid market location or name");
        }
        Site site = new Site(ResourceLocation.parse(entry.getString("dimension")), BlockPos.of(entry.getLong("pos")));
        Market market = new Market(site, entry.getUUID("city"), entry.getUUID("founder"), entry.getString("name"), entry.getBoolean("standalone"));
        for (Tag value : compounds(entry, "orders")) {
            CompoundTag order = (CompoundTag) value;
            if (!order.contains("id", Tag.TAG_INT) || !order.contains("quantity", Tag.TAG_INT)
                    || !order.contains("price", Tag.TAG_LONG) || !order.contains("tax_rate", Tag.TAG_INT)
                    || !order.contains("tax_remainder", Tag.TAG_INT)) {
                throw new IllegalArgumentException("Invalid market order fields");
            }
            ItemStack item = ItemStack.parse(registries, order.get("item")).orElseThrow();
            Order loaded = new Order(order.getInt("id"), Kind.valueOf(order.getString("kind")), order.getUUID("owner"),
                    item, order.getInt("quantity"), order.getLong("price"), order.getInt("tax_rate"));
            loaded.taxRemainder = order.getInt("tax_remainder");
            if (loaded.taxRemainder < 0 || loaded.taxRemainder >= 100
                    || market.orders.putIfAbsent(loaded.id, loaded) != null) {
                throw new IllegalArgumentException("Invalid market order ID");
            }
        }
        for (Tag value : compounds(entry, "claims")) {
            CompoundTag claim = (CompoundTag) value;
            Claim loaded = new Claim();
            loaded.coins = claim.getLong("coins");
            for (Tag item : compounds(claim, "items")) {
                loaded.items.add(ItemStack.parse(registries, item).orElseThrow());
            }
            if (!claim.contains("coins", Tag.TAG_LONG) || loaded.coins < 0
                    || market.claims.putIfAbsent(claim.getUUID("player"), loaded) != null) {
                throw new IllegalArgumentException("Invalid market claim");
            }
        }
        return market;
    }

    private static ListTag compounds(CompoundTag tag, String key) {
        if (!(tag.get(key) instanceof ListTag list) || (!list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND)) {
            throw new IllegalArgumentException("Invalid market data list: " + key);
        }
        return list;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (unreadable != null) {
            return unreadable.copy();
        }
        tag.putInt("version", VERSION);
        tag.putInt("next_order", nextOrder);
        ListTag locations = new ListTag();
        for (Market market : markets.values()) {
            locations.add(saveMarket(market, registries));
        }
        tag.put("markets", locations);
        ListTag balances = new ListTag();
        for (var entry : treasuries.entrySet()) {
            CompoundTag treasury = new CompoundTag();
            treasury.putUUID("city", entry.getKey());
            treasury.putInt("rate", entry.getValue().taxRate);
            treasury.putLong("coins", entry.getValue().coins);
            balances.add(treasury);
        }
        tag.put("treasuries", balances);
        return tag;
    }

    private static CompoundTag saveMarket(Market market, HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putString("dimension", market.site.dimension().toString());
        tag.putLong("pos", market.site.position().asLong());
        tag.putUUID("city", market.city);
        tag.putUUID("founder", market.founder);
        tag.putString("name", market.name);
        tag.putBoolean("standalone", market.standalone);
        ListTag orders = new ListTag();
        for (Order order : market.orders.values()) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("id", order.id);
            entry.putString("kind", order.kind.name());
            entry.putUUID("owner", order.owner);
            entry.put("item", order.item.save(registries));
            entry.putInt("quantity", order.quantity);
            entry.putLong("price", order.price);
            entry.putInt("tax_rate", order.taxRate);
            entry.putInt("tax_remainder", order.taxRemainder);
            orders.add(entry);
        }
        tag.put("orders", orders);
        ListTag claims = new ListTag();
        for (var claim : market.claims.entrySet()) {
            if (!claim.getValue().empty()) {
                CompoundTag entry = new CompoundTag();
                entry.putUUID("player", claim.getKey());
                entry.putLong("coins", claim.getValue().coins);
                ListTag items = new ListTag();
                for (ItemStack stack : claim.getValue().items) {
                    items.add(stack.save(registries));
                }
                entry.put("items", items);
                claims.add(entry);
            }
        }
        tag.put("claims", claims);
        return tag;
    }
}
