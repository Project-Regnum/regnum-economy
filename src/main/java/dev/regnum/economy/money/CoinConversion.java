package dev.regnum.economy.money;

import dev.regnum.economy.integration.AuthAccess;
import dev.regnum.economy.inventory.InventoryPlan;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

public final class CoinConversion {
    public enum Result {
        DEPOSITED, WITHDRAWN, INVALID, CAPACITY, FUNDS, SPACE, SAVE_FAILED, STALE, SESSION_MISMATCH, UNAVAILABLE
    }

    private CoinConversion() {
    }

    static Result deposit(ServerPlayer player, int selected, boolean all) {
        if (!WalletPersistence.available(player.getServer())) return Result.UNAVAILABLE;
        if (!allowed(player)) {
            return Result.INVALID;
        }
        var inventory = player.getInventory();
        Map<Integer, ItemStack> consumed = new LinkedHashMap<>();
        long value = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            // Include the offhand; exclude armor and any container/trade escrow slots.
            if ((slot >= inventory.items.size() && slot != 40) || (!all && slot != selected)) {
                continue;
            }
            ItemStack stack = inventory.getItem(slot);
            Denomination coin = Denomination.of(stack);
            if (coin == null || stack.isEmpty() || stack.getCount() > stack.getMaxStackSize()) {
                continue;
            }
            long worth = (long) coin.copperValue() * stack.getCount();
            if (worth > Long.MAX_VALUE - value) {
                return Result.CAPACITY;
            }
            value += worth;
            consumed.put(slot, stack.copy());
        }
        if (value == 0) {
            return Result.INVALID;
        }
        long before = CoinBalances.get(player);
        if (value > Long.MAX_VALUE - before) {
            return Result.CAPACITY;
        }
        try (var operation = WalletPersistence.begin(player)) {
            consumed.keySet().forEach(slot -> inventory.setItem(slot, ItemStack.EMPTY));
            inventory.setChanged();
            CoinBalances.set(player, before + value);
            return operation.commit() ? Result.DEPOSITED : Result.SAVE_FAILED;
        }
    }

    static Result withdraw(ServerPlayer player, long amount) {
        if (!WalletPersistence.available(player.getServer())) return Result.UNAVAILABLE;
        if (!allowed(player) || amount < 1) {
            return Result.INVALID;
        }
        long before = CoinBalances.get(player);
        if (amount > before) {
            return Result.FUNDS;
        }
        var inventory = player.getInventory();
        InventoryPlan delivery = new InventoryPlan(inventory);
        long remaining = amount;
        Denomination[] denominations = {Denomination.GOLD, Denomination.SILVER, Denomination.COPPER};
        for (Denomination coin : denominations) {
            long count = remaining / coin.copperValue();
            remaining %= coin.copperValue();
            // Bound planning work even for a malicious Long.MAX_VALUE request.
            if (count > (long) inventory.items.size() * 64) {
                return Result.SPACE;
            }
            while (count > 0) {
                int size = (int) Math.min(count, coin.item().getDefaultMaxStackSize());
                if (!delivery.add(new ItemStack(coin.item(), size)).isEmpty()) {
                    return Result.SPACE;
                }
                count -= size;
            }
        }
        try (var operation = WalletPersistence.begin(player)) {
            CoinBalances.set(player, before - amount);
            delivery.apply();
            return operation.commit() ? Result.WITHDRAWN : Result.SAVE_FAILED;
        }
    }

    private static boolean allowed(ServerPlayer player) {
        return player.getServer().isSameThread() && WalletPersistence.available(player.getServer())
                && player.isAlive() && !player.hasDisconnected()
                && AuthAccess.permits(player) && player.containerMenu instanceof WalletMenu
                && player.containerMenu.getCarried().isEmpty();
    }

}
