package dev.regnum.economy.inventory;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

public final class InventoryPlan {
    private final Inventory inventory;
    private final List<ItemStack> slots;

    public InventoryPlan(Inventory inventory) {
        this.inventory = inventory;
        slots = new ArrayList<>(inventory.items.size());
        for (ItemStack stack : inventory.items) {
            slots.add(stack.copy());
        }
    }

    public ItemStack add(ItemStack stack) {
        int remaining = stack.getCount();
        int limit = Math.min(inventory.getMaxStackSize(), stack.getMaxStackSize());
        for (ItemStack existing : slots) {
            if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, stack)) {
                int moved = Math.min(remaining, Math.max(0, limit - existing.getCount()));
                existing.grow(moved);
                remaining -= moved;
                if (remaining == 0) {
                    return ItemStack.EMPTY;
                }
            }
        }
        for (int i = 0; i < slots.size() && remaining > 0; i++) {
            if (slots.get(i).isEmpty()) {
                int moved = Math.min(remaining, limit);
                slots.set(i, stack.copyWithCount(moved));
                remaining -= moved;
            }
        }
        return remaining == 0 ? ItemStack.EMPTY : stack.copyWithCount(remaining);
    }

    /** Remove only matching components from main inventory. Apply the plan only on success. */
    public boolean remove(ItemStack sample, int count) {
        for (int i = 0; i < slots.size() && count > 0; i++) {
            ItemStack stack = slots.get(i);
            if (!matches(sample, stack)) continue;
            int moved = Math.min(count, stack.getCount());
            count -= moved;
            stack.shrink(moved);
        }
        return count == 0;
    }

    public static int countMatching(Inventory inventory, ItemStack sample) {
        return inventory.items.stream().filter(stack -> matches(sample, stack)).mapToInt(ItemStack::getCount).sum();
    }

    private static boolean matches(ItemStack sample, ItemStack stack) {
        return !stack.isEmpty() && ItemStack.isSameItemSameComponents(sample, stack);
    }

    public void apply() {
        boolean changed = false;
        for (int i = 0; i < slots.size(); i++) {
            if (!ItemStack.matches(inventory.getItem(i), slots.get(i))) {
                inventory.setItem(i, slots.get(i));
                changed = true;
            }
        }
        if (changed) {
            inventory.setChanged();
        }
    }
}
