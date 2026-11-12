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
import com.mojang.logging.LogUtils;

final class TradeEscrow {
    enum RestoreResult {
        NOTHING_RESTORED, PARTIAL, COMPLETE, FAILED, RECOVERY_PENDING
    }

    private static final AttachmentType<List<ItemStack>> ITEMS = AttachmentRegistry.<List<ItemStack>>builder()
            .persistent(ItemStack.CODEC.listOf())
            .copyOnDeath()
            .buildAndRegister(RegnumEconomy.id("trade_escrow"));
    private static final AttachmentType<ItemStack> CURSOR = AttachmentRegistry.<ItemStack>builder()
            .persistent(ItemStack.CODEC).copyOnDeath().buildAndRegister(RegnumEconomy.id("trade_cursor"));

    private TradeEscrow() {
    }

    static void register() {
        WalletPersistence.registerPlayerState(player -> {
            List<ItemStack> existing = player.getAttached(ITEMS);
            List<ItemStack> before = existing == null ? null : existing.stream().map(ItemStack::copy).toList();
            ItemStack existingCursor = player.getAttached(CURSOR);
            ItemStack cursor = existingCursor == null ? null : existingCursor.copy();
            return () -> {
                player.setAttached(ITEMS, before);
                player.setAttached(CURSOR, cursor);
            };
        });
    }

    static void save(ServerPlayer player, List<ItemStack> stacks) {
        if (!WalletPersistence.changing(player)) throw new IllegalStateException("Escrow changes require an economy operation");
        List<ItemStack> items = stacks.stream().filter(stack -> !stack.isEmpty()).map(ItemStack::copy).toList();
        if (items.isEmpty()) {
            player.removeAttached(ITEMS);
        } else {
            player.setAttached(ITEMS, items);
        }
    }

    static void saveCursor(ServerPlayer player, ItemStack stack) {
        if (!WalletPersistence.changing(player)) throw new IllegalStateException("Cursor changes require an economy operation");
        if (stack.isEmpty()) player.removeAttached(CURSOR);
        else player.setAttached(CURSOR, stack.copy());
    }

    /** Receipts are changed only inside an operation; compare them before releasing a failed trade's live state. */
    static boolean covers(ServerPlayer player, List<ItemStack> offered, ItemStack cursor) {
        List<ItemStack> items = offered.stream().filter(stack -> !stack.isEmpty()).toList();
        return ItemStack.listMatches(player.getAttachedOrElse(ITEMS, List.of()), items)
                && ItemStack.matches(player.getAttachedOrElse(CURSOR, ItemStack.EMPTY), cursor);
    }

    static RestoreResult restore(ServerPlayer player) {
        List<ItemStack> offered = player.getAttachedOrElse(ITEMS, List.of());
        ItemStack cursor = player.getAttachedOrElse(CURSOR, ItemStack.EMPTY);
        List<ItemStack> pending = new ArrayList<>(offered);
        if (!cursor.isEmpty()) pending.add(cursor);
        if (pending.isEmpty()) {
            return RestoreResult.COMPLETE;
        }
        if (!WalletPersistence.available(player.getServer())) return RestoreResult.RECOVERY_PENDING;
        if (!AuthAccess.permits(player) || !player.isAlive() || player.hasDisconnected()
                || player.containerMenu != player.inventoryMenu || !player.containerMenu.getCarried().isEmpty()) {
            return RestoreResult.NOTHING_RESTORED;
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
            return RestoreResult.NOTHING_RESTORED;
        }
        boolean committed;
        try (var operation = WalletPersistence.begin(player)) {
            inventory.apply();
            save(player, remaining);
            saveCursor(player, ItemStack.EMPTY);
            committed = operation.commit();
        } catch (RuntimeException failure) {
            LogUtils.getLogger().error("Trade escrow return failed; the claim was not released", failure);
            return WalletPersistence.available(player.getServer()) ? RestoreResult.FAILED : RestoreResult.RECOVERY_PENDING;
        }
        if (!WalletPersistence.available(player.getServer())) return RestoreResult.RECOVERY_PENDING;
        if (!committed) return RestoreResult.FAILED;
        return remaining.isEmpty() ? RestoreResult.COMPLETE : RestoreResult.PARTIAL;
    }

    /** Stage one participant's cancellation return, without committing it independently. */
    static boolean stageReturn(ServerPlayer player, List<ItemStack> offered, ItemStack cursor) {
        List<ItemStack> pending = new ArrayList<>();
        for (ItemStack stack : offered) if (!stack.isEmpty()) pending.add(stack.copy());
        if (!cursor.isEmpty()) pending.add(cursor.copy());
        List<ItemStack> remaining = new ArrayList<>();
        if (AuthAccess.permits(player) && player.isAlive()) {
            InventoryPlan inventory = new InventoryPlan(player.getInventory());
            for (ItemStack stack : pending) {
                ItemStack leftover = inventory.add(stack);
                if (!leftover.isEmpty()) remaining.add(leftover);
            }
            inventory.apply();
        } else {
            remaining.addAll(pending);
        }
        save(player, remaining);
        saveCursor(player, ItemStack.EMPTY);
        return remaining.isEmpty();
    }
}
