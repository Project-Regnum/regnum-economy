package dev.regnum.economy.trade;

import dev.regnum.economy.RegnumEconomy;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public final class TradeMenu extends AbstractContainerMenu {
    public static final int INVENTORY_X = 42;
    public static final int INVENTORY_Y = 156;

    static final int OFFER_SLOTS = 9;
    private static final int INVENTORY_START = 2 * OFFER_SLOTS;
    private static MenuType<TradeMenu> type;

    private final Player owner;
    private final TradeSession session;
    private final TradeSession.Side side;
    private final SimpleContainer ownItems;
    private final SimpleContainer otherItems;
    private TradePackets.State state;

    static void register() {
        type = Registry.register(BuiltInRegistries.MENU, RegnumEconomy.id("trade"),
                new MenuType<>(TradeMenu::new, FeatureFlags.DEFAULT_FLAGS));
    }

    public static MenuType<TradeMenu> type() {
        return type;
    }

    private TradeMenu(int id, Inventory inventory) {
        this(id, inventory, null, null, new SimpleContainer(OFFER_SLOTS), new SimpleContainer(OFFER_SLOTS));
    }

    TradeMenu(int id, Inventory inventory, TradeSession session, TradeSession.Side side, TradeSession.Side other) {
        this(id, inventory, session, side, side.items, other.items);
    }

    private TradeMenu(int id, Inventory inventory, TradeSession session, TradeSession.Side side,
                      SimpleContainer ownItems, SimpleContainer otherItems) {
        super(type, id);
        this.owner = inventory.player;
        this.session = session;
        this.side = side;
        this.ownItems = ownItems;
        this.otherItems = otherItems;

        addOfferSlots(ownItems, 33, true);
        addOfferSlots(otherItems, 157, false);
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, 9 + row * 9 + column,
                        INVENTORY_X + column * 18, INVENTORY_Y + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, INVENTORY_X + column * 18, INVENTORY_Y + 58));
        }
    }

    private void addOfferSlots(SimpleContainer items, int x, boolean owned) {
        for (int i = 0; i < OFFER_SLOTS; i++) {
            addSlot(new Slot(items, i, x + i % 3 * 18, 31 + i / 3 * 18) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return owned && !locked();
                }

                @Override
                public boolean mayPickup(Player player) {
                    return owned && !locked();
                }
            });
        }
    }

    private boolean locked() {
        return session != null ? session.locked() : state != null && state.seconds() > 0;
    }

    boolean belongsTo(TradeSession current) {
        return session == current;
    }

    void handle(ServerPlayer player, TradePackets.Action action) {
        if (session != null && player == owner) {
            session.handle(side, action);
        }
    }

    public TradePackets.State state() {
        return state;
    }

    public void setState(TradePackets.State state) {
        if (session == null && state.menuId() == containerId) {
            this.state = state;
        }
    }

    public boolean hasOwnItems() {
        return !ownItems.isEmpty();
    }

    public boolean hasOtherItems() {
        return !otherItems.isEmpty();
    }

    @Override
    public boolean stillValid(Player player) {
        return player == owner && (session == null || session.canInteract());
    }

    @Override
    public void clicked(int slot, int button, ClickType type, Player player) {
        if (!stillValid(player) || (slot >= 0 && slot < INVENTORY_START
                && (slot >= OFFER_SLOTS || locked() || type == ClickType.THROW || type == ClickType.CLONE))) {
            return;
        }
        super.clicked(slot, button, type, player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (locked() || !stillValid(player) || index < 0 || index >= slots.size()
                || (index >= OFFER_SLOTS && index < INVENTORY_START)) {
            return ItemStack.EMPTY;
        }
        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }

        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (index < OFFER_SLOTS) {
            if (!moveItemStackTo(stack, INVENTORY_START, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (!moveItemStackTo(stack, 0, OFFER_SLOTS, false)) {
            return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return original;
    }

    @Override
    public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
        return slot.container != otherItems && (slot.container != ownItems || !locked())
                && super.canTakeItemForPickAll(stack, slot);
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (session != null && player instanceof ServerPlayer serverPlayer) {
            session.cancel(serverPlayer);
        }
    }
}
