package dev.regnum.economy.trade;

import dev.regnum.economy.inventory.InventoryPlan;
import dev.regnum.economy.money.CoinBalances;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class TradeSession {
    private static final int COUNTDOWN_TICKS = 60;
    private static final String MESSAGE = "message.regnum-economy.trade.";

    final Side first;
    final Side second;
    private int revision;
    private int ticks;
    private int issue;
    private boolean closed;

    TradeSession(ServerPlayer first, ServerPlayer second) {
        this.first = new Side(first);
        this.second = new Side(second);
    }

    boolean open() {
        if (!open(first, second) || !open(second, first)) {
            cancel(null);
            return false;
        }
        sync();
        return true;
    }

    private boolean open(Side side, Side other) {
        return side.player.openMenu(new SimpleMenuProvider(
                (id, inventory, player) -> new TradeMenu(id, inventory, this, side, other),
                Component.translatable("container.regnum-economy.trade", other.player.getDisplayName()))).isPresent();
    }

    boolean locked() {
        return ticks > 0;
    }

    void handle(Side side, TradePackets.Action action) {
        if (!canInteract()) {
            return;
        }
        if (action.action() == TradePackets.Action.CANCEL) {
            cancel(null);
        } else if (action.action() == TradePackets.Action.OFFER_COINS && !locked()
                && action.coins() >= 0 && side.coins != action.coins()) {
            side.coins = action.coins();
            resetConfirmations();
            sync();
        } else if (action.action() == TradePackets.Action.CONFIRM && action.revision() == revision) {
            if (side.confirmed) {
                resetConfirmations();
            } else if (ready()) {
                issue = 0;
                side.confirmed = true;
                if (first.confirmed && second.confirmed) {
                    ticks = COUNTDOWN_TICKS;
                }
            }
            sync();
        }
    }

    void tick() {
        if (!canInteract() || !viewing(first) || !viewing(second)) {
            cancel(null);
            return;
        }
        if ((first.confirmed || second.confirmed) && !ready()) {
            resetConfirmations();
            issue = TradePackets.State.COIN_BALANCE;
        } else if (locked() && --ticks == 0) {
            complete();
        }
        sync();
    }

    boolean canInteract() {
        return !closed && Trades.inRange(first.player, second.player)
                && Trades.bothAuthenticated(first.player, second.player);
    }

    private boolean viewing(Side side) {
        return side.player.containerMenu instanceof TradeMenu menu && menu.belongsTo(this);
    }

    private boolean ready() {
        return (first.hasOffer() || second.hasOffer())
                && CoinBalances.get(first.player) >= first.coins && CoinBalances.get(second.player) >= second.coins;
    }

    private void resetConfirmations() {
        revision++;
        issue = 0;
        first.confirmed = false;
        second.confirmed = false;
        ticks = 0;
    }

    private void complete() {
        InventoryPlan firstInventory = new InventoryPlan(first.player.getInventory());
        InventoryPlan secondInventory = new InventoryPlan(second.player.getInventory());
        if (!fits(firstInventory, second.items) || !fits(secondInventory, first.items)) {
            pause(TradePackets.State.INVENTORY_SPACE);
            return;
        }
        if (!CoinBalances.exchange(first.player, first.coins, second.player, second.coins)) {
            pause(TradePackets.State.COIN_BALANCE);
            return;
        }

        firstInventory.apply();
        secondInventory.apply();
        TradeEscrow.save(first.player, List.of());
        TradeEscrow.save(second.player, List.of());
        finish(null, true);
    }

    private static boolean fits(InventoryPlan inventory, SimpleContainer items) {
        for (int slot = 0; slot < items.getContainerSize(); slot++) {
            ItemStack stack = items.getItem(slot);
            if (!stack.isEmpty() && !inventory.add(stack).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private void pause(int reason) {
        resetConfirmations();
        issue = reason;
    }

    void cancel(ServerPlayer closingPlayer) {
        finish(closingPlayer, false);
    }

    private void finish(ServerPlayer closingPlayer, boolean success) {
        if (closed) {
            return;
        }
        closed = true;
        Trades.forget(this);
        close(first, closingPlayer);
        close(second, closingPlayer);
        if (!success) {
            refund(first.player);
            refund(second.player);
        }
        notifyPlayers(success ? "completed" : "cancelled");
    }

    private void close(Side side, ServerPlayer closingPlayer) {
        if (side.player != closingPlayer && viewing(side)) {
            side.player.closeContainer();
        }
    }

    private static void refund(ServerPlayer player) {
        if (!TradeEscrow.restore(player)) {
            player.sendSystemMessage(Component.translatable(MESSAGE + "refund_pending"));
        }
    }

    private void notifyPlayers(String key) {
        Component message = Component.translatable(MESSAGE + key);
        first.player.sendSystemMessage(message);
        second.player.sendSystemMessage(message);
    }

    private void sync() {
        if (!closed) {
            sync(first, second);
            sync(second, first);
        }
    }

    private void sync(Side side, Side other) {
        if (!viewing(side) || !ServerPlayNetworking.canSend(side.player, TradePackets.State.TYPE)) {
            return;
        }
        int seconds = (ticks + 19) / 20;
        var state = new TradePackets.State(side.player.containerMenu.containerId, revision, side.coins,
                other.coins, CoinBalances.get(side.player), side.confirmed, other.confirmed, seconds, issue);
        if (!state.equals(side.lastState)) {
            side.player.containerMenu.broadcastChanges();
            side.lastState = state;
            ServerPlayNetworking.send(side.player, state);
        }
    }

    final class Side {
        final ServerPlayer player;
        final SimpleContainer items = new SimpleContainer(TradeMenu.OFFER_SLOTS);
        private List<ItemStack> snapshot = Collections.nCopies(TradeMenu.OFFER_SLOTS, ItemStack.EMPTY);
        private long coins;
        private boolean confirmed;
        private TradePackets.State lastState;

        Side(ServerPlayer player) {
            this.player = player;
            items.addListener(container -> changed());
        }

        private boolean hasOffer() {
            return coins > 0 || !items.isEmpty();
        }

        private void changed() {
            if (closed || ItemStack.listMatches(snapshot, items.getItems())) {
                return;
            }
            List<ItemStack> current = new ArrayList<>(items.getContainerSize());
            for (int i = 0; i < items.getContainerSize(); i++) {
                current.add(items.getItem(i).copy());
            }
            snapshot = current;
            TradeEscrow.save(player, snapshot);
            resetConfirmations();
        }
    }
}
