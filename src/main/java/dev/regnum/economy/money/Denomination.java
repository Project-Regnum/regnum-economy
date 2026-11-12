package dev.regnum.economy.money;

import dev.regnum.economy.RegnumEconomy;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public enum Denomination {
    COPPER("copper_coin", 1),
    SILVER("silver_coin", 100),
    GOLD("gold_coin", 10_000);

    private final String id;
    private final int copperValue;
    private Item item;

    Denomination(String id, int copperValue) {
        this.id = id;
        this.copperValue = copperValue;
    }

    public int copperValue() {
        return copperValue;
    }

    public Item item() {
        return item;
    }

    public static Denomination of(ItemStack stack) {
        for (Denomination coin : values()) {
            if (stack.is(coin.item)) {
                return coin;
            }
        }
        return null;
    }

    public static void registerItems() {
        for (Denomination coin : values()) {
            coin.item = Registry.register(BuiltInRegistries.ITEM, RegnumEconomy.id(coin.id),
                    new Item(new Item.Properties()));
        }
    }
}
