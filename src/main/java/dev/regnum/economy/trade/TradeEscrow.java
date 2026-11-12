package dev.regnum.economy.trade;

import dev.regnum.economy.RegnumEconomy;
import dev.regnum.economy.integration.AuthAccess;
import dev.regnum.economy.inventory.InventoryPlan;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

final class TradeEscrow {
    private static final AttachmentType<List<ItemStack>> ITEMS = AttachmentRegistry.<List<ItemStack>>builder()
            .persistent(ItemStack.CODEC.listOf())
            .copyOnDeath()
            .buildAndRegister(RegnumEconomy.id("trade_escrow"));

    private TradeEscrow() {
    }

    static void register() {
        // Register the persistent attachment during mod initialization.
    }

    static void save(ServerPlayer player, List<ItemStack> stacks) {
        List<ItemStack> items = stacks.stream().filter(stack -> !stack.isEmpty()).map(ItemStack::copy).toList();
        if (items.isEmpty()) {
            player.removeAttached(ITEMS);
        } else {
            player.setAttached(ITEMS, items);
        }
    }

    static boolean restore(ServerPlayer player) {
        List<ItemStack> pending = player.getAttached(ITEMS);
        if (pending == null) {
            return true;
        }
        if (!AuthAccess.permits(player)) {
            return false;
        }
        InventoryPlan inventory = new InventoryPlan(player.getInventory());
        List<ItemStack> remaining = new ArrayList<>();
        for (ItemStack stack : pending) {
            ItemStack leftover = inventory.add(stack);
            if (!leftover.isEmpty()) {
                remaining.add(leftover);
            }
        }
        if (ItemStack.listMatches(pending, remaining)) {
            return false;
        }
        inventory.apply();
        save(player, remaining);
        return remaining.isEmpty();
    }
}
