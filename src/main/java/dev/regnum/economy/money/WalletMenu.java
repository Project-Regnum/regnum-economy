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

    public static void open(ServerPlayer player) {
        if (player.isAlive() && !player.hasDisconnected() && AuthAccess.permits(player)
                && player.containerMenu == player.inventoryMenu && player.containerMenu.getCarried().isEmpty()
                && ServerPlayNetworking.canSend(player, WalletPackets.State.TYPE)) {
            player.openMenu(new SimpleMenuProvider((id, inventory, owner) -> new WalletMenu(id, inventory),
                    Component.translatable("container.regnum-economy.wallet")));
        }
    }

    private WalletMenu(int id, Inventory inventory) {
        super(type, id);
        owner = inventory.player;
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, 9 + row * 9 + column, 12 + column * 18, 134 + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, 12 + column * 18, 192));
        }
        addSlot(new Slot(inventory, 40, 156, 102));
    }

    void handle(ServerPlayer player, WalletPackets.Action action) {
        // Refresh before comparing: pickup, hopper-like mods, grants and other changes invalidate old requests.
        broadcastChanges();
        if (!stillValid(player) || action.menuId() != containerId || !session.equals(action.session())
                || action.revision() != revision || revision == Long.MAX_VALUE) {
            return;
        }
        revision++;
        CoinConversion.Result outcome = switch (action.action()) {
            case WalletPackets.Action.DEPOSIT_STACK -> CoinConversion.deposit(player, action.slot(), false);
            case WalletPackets.Action.DEPOSIT_ALL -> CoinConversion.deposit(player, -1, true);
            case WalletPackets.Action.WITHDRAW -> CoinConversion.withdraw(player, action.amount());
            default -> CoinConversion.Result.INVALID;
        };
        result = outcome.ordinal();
        broadcastChanges();
        sendState(player);
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (!(owner instanceof ServerPlayer player)) {
            return;
        }
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
            sendState(player);
        }
    }

    private void sendState(ServerPlayer player) {
        if (ServerPlayNetworking.canSend(player, WalletPackets.State.TYPE)) {
            ServerPlayNetworking.send(player, new WalletPackets.State(containerId, session, revision,
                    CoinBalances.get(player), result));
        }
    }

    public WalletPackets.State state() {
        return state;
    }

    public void setState(WalletPackets.State state) {
        if (state.menuId() == containerId && (this.state == null || state.revision() >= this.state.revision())) {
            this.state = state;
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return player == owner && player.isAlive() && (!(player instanceof ServerPlayer serverPlayer)
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
