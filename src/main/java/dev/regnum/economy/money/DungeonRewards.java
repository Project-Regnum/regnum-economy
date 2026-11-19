package dev.regnum.economy.money;

import com.mojang.serialization.Codec;
import dev.regnum.economy.RegnumEconomy;
import dev.regnum.economy.integration.AuthAccess;
import dev.regnum.economy.inventory.InventoryPlan;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Commits each player's receipt, wallet and loot through the wallet recovery journal. */
public final class DungeonRewards {
    private static final AttachmentType<List<String>> RECEIPTS = AttachmentRegistry.<List<String>>builder()
            .persistent(Codec.STRING.listOf()).copyOnDeath().buildAndRegister(RegnumEconomy.id("dungeon_receipts"));

    private DungeonRewards() {}

    static void register() {
        WalletPersistence.registerPlayerState(player -> {
            var before = player.getAttached(RECEIPTS);
            return () -> player.setAttached(RECEIPTS, before);
        });
    }

    public static boolean grant(ServerPlayer player, String receipt, long coins, List<ItemStack> loot) {
        var server = player.getServer();
        if (server == null || !server.isSameThread() || !AuthAccess.permits(player)
                || !WalletPersistence.available(server) || player.hasDisconnected() || !player.isAlive()
                || player.isSpectator() || coins < 0 || coins > 100_000_000 || receipt.length() > 100
                || loot.size() > 16) return false;

        var previous = player.getAttachedOrElse(RECEIPTS, List.of());
        if (previous.contains(receipt)) return true;

        // Cursor and crafting-grid items are absent from the player checkpoint.
        if (player.containerMenu != player.inventoryMenu || !player.containerMenu.getCarried().isEmpty()
                || !player.inventoryMenu.getCraftSlots().isEmpty()) return false;

        var inventory = new InventoryPlan(player.getInventory());
        for (ItemStack stack : loot) {
            if (!inventory.add(stack).isEmpty()) return false;
        }

        try (var operation = WalletPersistence.begin(player)) {
            if (coins > 0 && !CoinBalances.credit(player, coins)) return false;
            inventory.apply();
            var next = new ArrayList<>(previous);
            next.add(receipt);
            player.setAttached(RECEIPTS, List.copyOf(next));
            return operation.commit();
        }
    }
}
