package dev.regnum.economy.money;

import dev.regnum.economy.RegnumEconomy;
import dev.regnum.economy.integration.AuthAccess;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class WalletMenu extends AbstractContainerMenu {
    private static MenuType<WalletMenu> type;
    private final Player owner;
    private final UUID session = UUID.randomUUID();
    private long revision;
    private long lastBalance = -1;
    private int result = -1;
    private long lastRequest;
    private int lastResult = -1;
    private List<ItemStack> snapshot = List.of();
    private WalletPackets.State state;

    public static void register() {
        type = Registry.register(BuiltInRegistries.MENU, RegnumEconomy.id("wallet"),
                new MenuType<>(WalletMenu::new, FeatureFlags.DEFAULT_FLAGS));
        WalletPackets.register();
        WalletPersistence.register();
        CoinIssuance.register();
    }

    public static MenuType<WalletMenu> type() {
        return type;
    }

    public static boolean open(ServerPlayer player) {
        String rejection = !AuthAccess.permits(player) ? "login"
                : !WalletPersistence.available(player.getServer()) ? "unavailable"
                : !ServerPlayNetworking.canSend(player, WalletPackets.State.TYPE) ? "client_version"
                : !player.isAlive() || player.isSpectator() || player.hasDisconnected()
                    || player.containerMenu != player.inventoryMenu || !player.containerMenu.getCarried().isEmpty() ? "close_menus" : null;
        if (rejection != null) {
            player.sendSystemMessage(Component.translatable("message.regnum-economy.wallet." + rejection));
            return false;
        }
        return player.openMenu(new SimpleMenuProvider((id, inventory, owner) -> new WalletMenu(id, inventory),
                Component.translatable("container.regnum-economy.wallet"))).isPresent();
    }

    private WalletMenu(int id, Inventory inventory) {
        super(type, id);
        owner = inventory.player;
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addReadOnlySlot(inventory, 9 + row * 9 + column, 12 + column * 18, 134 + row * 18);
            }
        }
        for (int column = 0; column < 9; column++) {
            addReadOnlySlot(inventory, column, 12 + column * 18, 192);
        }
        addReadOnlySlot(inventory, 40, 156, 102);
    }

    private void addReadOnlySlot(Inventory inventory, int index, int x, int y) {
        addSlot(new Slot(inventory, index, x, y) {
            @Override public boolean mayPlace(ItemStack stack) { return false; }
            @Override public boolean mayPickup(Player player) { return false; }
        });
    }

    void handle(ServerPlayer player, WalletPackets.Action action) {
        if (!stillValid(player)) {
            BalancePayload.hideFrom(player);
            player.closeContainer();
            return;
        }
        if (action.menuId() != containerId || action.request() < 1) return;
        broadcastChanges();
        if (action.action() == WalletPackets.Action.REFRESH) {
            sendState(player, action.request(), -1);
            return;
        }
        if (!session.equals(action.session())) {
            sendState(player, action.request(), CoinConversion.Result.SESSION_MISMATCH.ordinal());
            return;
        }
        if (action.request() <= lastRequest) {
            sendState(player, action.request(), action.request() == lastRequest ? lastResult : CoinConversion.Result.STALE.ordinal());
            return;
        }
        lastRequest = action.request();
        if (action.revision() != revision || revision == Long.MAX_VALUE) {
            lastResult = CoinConversion.Result.STALE.ordinal();
            sendState(player, action.request(), lastResult);
            return;
        }
        revision++;
        CoinConversion.Result outcome;
        try {
            outcome = switch (action.action()) {
                case WalletPackets.Action.DEPOSIT_STACK -> CoinConversion.deposit(player, action.slot(), false);
                case WalletPackets.Action.DEPOSIT_ALL -> CoinConversion.deposit(player, -1, true);
                case WalletPackets.Action.WITHDRAW -> CoinConversion.withdraw(player, action.amount());
                default -> CoinConversion.Result.INVALID;
            };
        } catch (RuntimeException failure) {
            com.mojang.logging.LogUtils.getLogger().error("Wallet operation failed", failure);
            outcome = CoinConversion.Result.SAVE_FAILED;
        }
        result = outcome.ordinal();
        lastResult = result;
        broadcastChanges();
        sendState(player, action.request(), result);
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (!(owner instanceof ServerPlayer player)) {
            return;
        }
        if (!stillValid(player)) return;
        long balance = CoinBalances.get(player);
        List<ItemStack> current = new ArrayList<>();
        for (Slot slot : slots) {
            current.add(slot.getItem().copy());
        }
        if (balance != lastBalance || !ItemStack.listMatches(snapshot, current)) {
            if (revision < Long.MAX_VALUE) {
                revision++;
            }
            snapshot = current;
            lastBalance = balance;
            sendState(player, 0, result);
        }
    }

    private void sendState(ServerPlayer player, long request, int outcome) {
        if (stillValid(player) && ServerPlayNetworking.canSend(player, WalletPackets.State.TYPE)) {
            ServerPlayNetworking.send(player, new WalletPackets.State(containerId, session, revision, request,
                    CoinBalances.get(player), outcome));
        }
    }

    public WalletPackets.State state() {
        return state;
    }

    public void setState(WalletPackets.State state) {
        if (state.menuId() == containerId && (this.state == null || state.request() > 0
                || this.state.session().equals(state.session()) && state.revision() >= this.state.revision())) {
            this.state = state;
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return player == owner && player.isAlive() && !player.isSpectator() && (!(player instanceof ServerPlayer serverPlayer)
                || (!serverPlayer.hasDisconnected() && AuthAccess.permits(serverPlayer)));
    }

    @Override
    public void clicked(int slot, int button, ClickType type, Player player) {
        // Slots select coins in the client screen; they never hold an independent pouch inventory.
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }
}
