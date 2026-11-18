package dev.regnum.economy.market;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/** Money and item escrow protected by the existing market journal. */
final class MerchantStock {
    final UUID proprietor;
    final UUID city;
    final List<Good> goods = new ArrayList<>();
    long coins;

    MerchantStock(UUID proprietor, UUID city) {
        this.proprietor = Objects.requireNonNull(proprietor);
        this.city = city;
    }

    boolean empty() {
        return coins == 0 && goods.stream().allMatch(good -> good.stock == 0);
    }

    static final class Good {
        final UUID id;
        final ItemStack sample;
        int quantity;
        long ask;
        long bid;
        int stock;

        Good(UUID id, ItemStack sample, int quantity, long ask, long bid, int stock) {
            if (sample.isEmpty() || sample.getCount() != 1 || !NpcMerchants.validTerms(quantity, ask, bid)
                    || stock < 0 || stock > NpcMerchants.STOCK_LIMIT) throw new IllegalArgumentException("Invalid merchant good");
            this.id = id;
            this.sample = sample;
            this.quantity = quantity;
            this.ask = ask;
            this.bid = bid;
            this.stock = stock;
        }
    }

    static MerchantStock load(CompoundTag tag, HolderLookup.Provider registries) {
        MerchantStock merchant = new MerchantStock(tag.getUUID("proprietor"), tag.contains("city") ? tag.getUUID("city") : null);
        merchant.coins = tag.getLong("coins");
        if (!tag.contains("coins", Tag.TAG_LONG) || merchant.coins < 0
                || !(tag.get("goods") instanceof ListTag goods) || goods.size() > NpcMerchants.OFFER_LIMIT
                || (!goods.isEmpty() && goods.getElementType() != Tag.TAG_COMPOUND)) {
            throw new IllegalArgumentException("Invalid merchant escrow");
        }
        for (Tag value : goods) {
            CompoundTag entry = (CompoundTag) value;
            if (!entry.contains("quantity", Tag.TAG_INT) || !entry.contains("ask", Tag.TAG_LONG)
                    || !entry.contains("bid", Tag.TAG_LONG) || !entry.contains("stock", Tag.TAG_INT)) {
                throw new IllegalArgumentException("Invalid merchant terms");
            }
            Good good = new Good(entry.getUUID("id"), ItemStack.parse(registries, entry.get("sample")).orElseThrow(),
                    entry.getInt("quantity"), entry.getLong("ask"), entry.getLong("bid"), entry.getInt("stock"));
            if (merchant.goods.stream().anyMatch(prior -> prior.id.equals(good.id)
                    || ItemStack.isSameItemSameComponents(prior.sample, good.sample))) {
                throw new IllegalArgumentException("Duplicate merchant good");
            }
            merchant.goods.add(good);
        }
        return merchant;
    }

    CompoundTag save(UUID npc, HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("npc", npc);
        tag.putUUID("proprietor", proprietor);
        if (city != null) tag.putUUID("city", city);
        tag.putLong("coins", coins);
        ListTag serialized = new ListTag();
        for (Good good : goods) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("id", good.id);
            entry.put("sample", good.sample.save(registries));
            entry.putInt("quantity", good.quantity);
            entry.putLong("ask", good.ask);
            entry.putLong("bid", good.bid);
            entry.putInt("stock", good.stock);
            serialized.add(entry);
        }
        tag.put("goods", serialized);
        return tag;
    }
}
