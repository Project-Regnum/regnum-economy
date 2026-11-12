package dev.regnum.economy.trade;

import dev.regnum.economy.RegnumEconomy;
import dev.regnum.economy.integration.AuthAccess;
import dev.regnum.economy.inventory.InventoryPlan;
import dev.regnum.economy.money.WalletPersistence;
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
    private static final AttachmentType<ItemStack> CURSOR = AttachmentRegistry.<ItemStack>builder()
            .persistent(ItemStack.CODEC).copyOnDeath().buildAndRegister(RegnumEconomy.id("trade_cursor"));

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

    static void saveCursor(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) player.removeAttached(CURSOR);
        else player.setAttached(CURSOR, stack.copy());
    }

    static boolean restore(ServerPlayer player) {
        List<ItemStack> offered = player.getAttachedOrElse(ITEMS, List.of());
        ItemStack cursor = player.getAttachedOrElse(CURSOR, ItemStack.EMPTY);
        List<ItemStack> pending = new ArrayList<>(offered);
        if (!cursor.isEmpty()) pending.add(cursor);
        if (pending.isEmpty()) {
            return true;
        }
        if (!AuthAccess.permits(player)) {
            return false;
        }
        InventoryPlan inventory = new InventoryPlan(player.getInventory());
        InventoryPlan rollback = new InventoryPlan(player.getInventory());
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
        saveCursor(player, ItemStack.EMPTY);
        if (!WalletPersistence.commit(player)) {
            rollback.apply();
            save(player, offered);
            saveCursor(player, cursor);
            return false;
        }
        return remaining.isEmpty();
    }
}
