package dev.regnum.economy.trade;

import dev.regnum.economy.inventory.InventoryPlan;
import dev.regnum.economy.money.CoinBalances;
import dev.regnum.economy.money.WalletPersistence;
import com.mojang.logging.LogUtils;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

final class TradeSession {
    private static final int COUNTDOWN_TICKS = 60;
    private static final String MESSAGE = "message.regnum-economy.trade.";

    final Side first;
    final Side second;
    private final UUID session = UUID.randomUUID();
    private long revision;
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
        if (!session.equals(action.session()) || action.action() != TradePackets.Action.CANCEL && action.revision() != revision) {
            side.lastState = null;
            sync();
            return;
        }
        if (revision == Long.MAX_VALUE) { cancel(null); return; }
        revision++;
        if (action.action() == TradePackets.Action.CANCEL) {
            cancel(null);
        } else if (action.action() == TradePackets.Action.OFFER_COINS && !locked()
                && action.coins() >= 0 && side.coins != action.coins()) {
            side.coins = action.coins();
            resetConfirmations();
            sync();
        } else if (action.action() == TradePackets.Action.CONFIRM) {
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
        sync();
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
        if (revision < Long.MAX_VALUE) revision++;
        issue = 0;
        first.confirmed = false;
        second.confirmed = false;
        ticks = 0;
    }

    private void complete() {
        if (!first.player.containerMenu.getCarried().isEmpty() || !second.player.containerMenu.getCarried().isEmpty()) {
            pause(TradePackets.State.INVENTORY_SPACE);
            return;
        }
        InventoryPlan firstInventory = new InventoryPlan(first.player.getInventory());
        InventoryPlan secondInventory = new InventoryPlan(second.player.getInventory());
        if (!fits(firstInventory, second.items) || !fits(secondInventory, first.items)) {
            pause(TradePackets.State.INVENTORY_SPACE);
            return;
        }
        boolean saved = false;
        try (var operation = WalletPersistence.begin(first.player, second.player)) {
            if (!CoinBalances.exchange(first.player, first.coins, second.player, second.coins)) {
                pause(TradePackets.State.COIN_BALANCE);
                return;
            }
            firstInventory.apply();
            secondInventory.apply();
            TradeEscrow.save(first.player, List.of());
            TradeEscrow.save(second.player, List.of());
            saved = operation.commit();
        } catch (RuntimeException failure) {
            LogUtils.getLogger().error("Trade completion failed", failure);
        }
        if (!saved) {
            pause(TradePackets.State.SAVE_FAILED);
            notifyPlayers("save_failed");
            return;
        }
        finish(null);
        notifyPlayers("completed");
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
        if (closed) return;
        boolean saved = false;
        boolean firstReturned = false, secondReturned = false;
        try (var operation = WalletPersistence.begin(first.player, second.player)) {
            firstReturned = TradeEscrow.stageReturn(first.player, first.items.getItems(), cursor(first));
            secondReturned = TradeEscrow.stageReturn(second.player, second.items.getItems(), cursor(second));
            if (viewing(first)) first.player.containerMenu.setCarried(ItemStack.EMPTY);
            if (viewing(second)) second.player.containerMenu.setCarried(ItemStack.EMPTY);
            saved = operation.commit();
        } catch (RuntimeException failure) {
            LogUtils.getLogger().error("Trade cancellation could not commit; returns remain in escrow", failure);
        }
        // On failure, rollback restored each durable escrow/cursor receipt. Clear only the
        // live menu cursor before closing; pending-claim restoration can retry later.
        finish(closingPlayer);
        if (saved) notifyPlayers("cancelled");
        if (!saved || !firstReturned) first.player.sendSystemMessage(Component.translatable(MESSAGE + "refund_pending"));
        if (!saved || !secondReturned) second.player.sendSystemMessage(Component.translatable(MESSAGE + "refund_pending"));
    }

    private ItemStack cursor(Side side) {
        return viewing(side) ? side.player.containerMenu.getCarried().copy() : ItemStack.EMPTY;
    }

    private void finish(ServerPlayer closingPlayer) {
        if (closed) {
            return;
        }
        closed = true;
        Trades.forget(this);
        if (viewing(first)) first.player.containerMenu.setCarried(ItemStack.EMPTY);
        if (viewing(second)) second.player.containerMenu.setCarried(ItemStack.EMPTY);
        first.items.clearContent();
        second.items.clearContent();
        close(first, closingPlayer);
        close(second, closingPlayer);
    }

    private void close(Side side, ServerPlayer closingPlayer) {
        if (side.player != closingPlayer && viewing(side)) {
            side.player.closeContainer();
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
        var state = new TradePackets.State(side.player.containerMenu.containerId, session, revision, side.coins,
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
