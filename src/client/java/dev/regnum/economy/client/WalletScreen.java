package dev.regnum.economy.client;

import dev.regnum.economy.money.CoinConversion;
import dev.regnum.economy.money.Denomination;
import dev.regnum.economy.money.WalletMenu;
import dev.regnum.economy.money.WalletPackets;
import dev.sakhno.regnum.nations.client.ui.KingdomUi;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

final class WalletScreen extends AbstractContainerScreen<WalletMenu> {
    private static final String TEXT = "screen.regnum-economy.wallet.";
    private static final UUID NONE = new UUID(0, 0);
    private final List<CoinButton> selection = new ArrayList<>();
    private EditBox amount;
    private Button deposit, depositAll, withdraw, refresh;
    private int selected;
    private long nextRequest, latestRequest, pending, sentAt;
    private boolean needsRefresh;
    private Component feedback = Component.translatable(TEXT + "select");

    WalletScreen(WalletMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 186;
        imageHeight = 218;
        inventoryLabelX = 12;
        inventoryLabelY = 122;
        selected = 27 + inventory.selected;
    }

    @Override
    protected void init() {
        super.init();
        String previous = amount == null ? "" : amount.getValue();
        amount = addRenderableWidget(KingdomUi.field(font, leftPos + 12, topPos + 78, 78,
                Component.translatable(TEXT + "amount")));
        amount.setMaxLength(19);
        amount.setFilter(value -> value.isEmpty() || parse(value) >= 0);
        amount.setValue(previous);
        deposit = addRenderableWidget(new KingdomUi.RoyalButton(leftPos + 12, topPos + 52, 78,
                Component.translatable(TEXT + "deposit"), button -> send(WalletPackets.Action.DEPOSIT_STACK)));
        depositAll = addRenderableWidget(new KingdomUi.RoyalButton(leftPos + 96, topPos + 52, 78,
                Component.translatable(TEXT + "deposit_all"), button -> send(WalletPackets.Action.DEPOSIT_ALL)));
        withdraw = addRenderableWidget(new KingdomUi.RoyalButton(leftPos + 96, topPos + 78, 78,
                Component.translatable(TEXT + "withdraw"), button -> send(WalletPackets.Action.WITHDRAW)));
        refresh = addRenderableWidget(new KingdomUi.RoyalButton(leftPos + 150, topPos + 32, 24,
                Component.translatable(TEXT + "refresh"), button -> send(WalletPackets.Action.REFRESH)));
        selection.clear();
        for (int i = 0; i < menu.slots.size(); i++) selection.add(addRenderableWidget(new CoinButton(i)));
    }

    private static long parse(String text) {
        if (text.isEmpty() || !text.chars().allMatch(value -> value >= '0' && value <= '9')) return -1;
        try { return Long.parseLong(text); } catch (NumberFormatException ignored) { return -1; }
    }

    private void send(int action) {
        WalletPackets.State state = menu.state();
        if (pending != 0 || nextRequest == Long.MAX_VALUE || !ClientPlayNetworking.canSend(WalletPackets.Action.TYPE)) return;
        if (action != WalletPackets.Action.REFRESH && (state == null || needsRefresh)) return;
        if (action == WalletPackets.Action.WITHDRAW && parse(amount.getValue()) < 1) return;
        pending = latestRequest = ++nextRequest;
        sentAt = System.currentTimeMillis();
        int inventorySlot = selected < 0 ? -1 : menu.slots.get(selected).getContainerSlot();
        ClientPlayNetworking.send(new WalletPackets.Action(menu.containerId, state == null ? NONE : state.session(),
                state == null ? 0 : state.revision(), pending, action, inventorySlot, parse(amount.getValue())));
    }

    void accept(WalletPackets.State state) {
        if (state.menuId() != menu.containerId || state.request() != 0 && state.request() != latestRequest) return;
        menu.setState(state);
        if (state.request() == 0) return;
        pending = 0;
        needsRefresh = state.result() == CoinConversion.Result.STALE.ordinal()
                || state.result() == CoinConversion.Result.SESSION_MISMATCH.ordinal()
                || state.result() == CoinConversion.Result.UNAVAILABLE.ordinal();
        feedback = state.result() >= 0 && state.result() < CoinConversion.Result.values().length
                ? Component.translatable("message.regnum-economy.wallet."
                    + CoinConversion.Result.values()[state.result()].name().toLowerCase(Locale.ROOT))
                : Component.translatable(TEXT + "updated");
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (pending != 0 && System.currentTimeMillis() - sentAt > 10_000) {
            pending = 0;
            needsRefresh = true;
            feedback = Component.translatable(TEXT + "timeout");
        }
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        for (CoinButton slot : selection) {
            if (slot.isMouseOver(x, y)) {
                if (button == 0) slot.mouseClicked(x, y, button);
                return true; // Selection never falls through to vanilla pickup, drag or cloning.
            }
        }
        return super.mouseClicked(x, y, button);
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (amount.isFocused() && key != GLFW.GLFW_KEY_ESCAPE && key != GLFW.GLFW_KEY_TAB) {
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) send(WalletPackets.Action.WITHDRAW);
            else amount.keyPressed(key, scanCode, modifiers);
            return true;
        }
        int movement = switch (key) {
            case GLFW.GLFW_KEY_LEFT -> -1;
            case GLFW.GLFW_KEY_RIGHT -> 1;
            case GLFW.GLFW_KEY_UP -> -9;
            case GLFW.GLFW_KEY_DOWN -> 9;
            default -> 0;
        };
        if (movement != 0) {
            selected = selected == 36 ? 8 : selected == 8 && movement == -9 ? 36
                    : Math.floorMod(selected + movement, 36);
            setFocused(selection.get(selected));
            return true;
        }
        if (minecraft.options.keyDrop.matches(key, scanCode) || minecraft.options.keySwapOffhand.matches(key, scanCode)
                || minecraft.options.keyPickItem.matches(key, scanCode)) return true;
        for (var hotbar : minecraft.options.keyHotbarSlots) if (hotbar.matches(key, scanCode)) return true;
        return super.keyPressed(key, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics graphics, int x, int y, float delta) {
        WalletPackets.State state = menu.state();
        boolean ready = state != null && pending == 0 && !needsRefresh;
        deposit.active = ready && selected >= 0 && Denomination.of(menu.slots.get(selected).getItem()) != null;
        depositAll.active = ready;
        withdraw.active = ready && parse(amount.getValue()) > 0 && parse(amount.getValue()) <= state.balance();
        refresh.active = pending == 0;
        selection.forEach(CoinButton::update);
        super.render(graphics, x, y, delta);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float delta, int x, int y) {
        KingdomUi.frame(graphics, font, title, leftPos, topPos, imageWidth, imageHeight);
        for (Slot slot : menu.slots) {
            int sx = leftPos + slot.x - 1, sy = topPos + slot.y - 1;
            graphics.fill(sx, sy, sx + 18, sy + 18, KingdomUi.BORDER);
            graphics.fill(sx + 1, sy + 1, sx + 17, sy + 17, KingdomUi.RECESS);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int x, int y) {
        WalletPackets.State state = menu.state();
        if (state != null) fitted(graphics, Component.translatable("screen.regnum-economy.balance",
                Long.toString(state.balance())), 12, 38, 132);
        fitted(graphics, pending != 0 ? Component.translatable(TEXT + "pending") : feedback, 12, 103, 138);
        graphics.drawString(font, KingdomUi.ellipsize(font, Component.translatable(TEXT + "selection_mode"), 162),
                inventoryLabelX, inventoryLabelY, KingdomUi.MUTED, false);
    }

    private void fitted(GuiGraphics graphics, Component text, int x, int y, int width) {
        float scale = Math.min(1f, (float) width / Math.max(1, font.width(text)));
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0);
        graphics.pose().scale(scale, scale, 1);
        graphics.drawString(font, text, 0, 0, KingdomUi.INK, false);
        graphics.pose().popPose();
    }

    private final class CoinButton extends AbstractButton {
        private final int index;
        private ItemStack last = ItemStack.EMPTY;
        private boolean chosen;

        CoinButton(int index) {
            super(leftPos + menu.slots.get(index).x - 1, topPos + menu.slots.get(index).y - 1, 18, 18, Component.empty());
            this.index = index;
            update();
        }

        void update() {
            ItemStack stack = menu.slots.get(index).getItem();
            if (getMessage().getString().isEmpty() || chosen != (selected == index) || !ItemStack.matches(last, stack)) {
                last = stack.copy();
                chosen = selected == index;
                setMessage(Component.translatable(TEXT + (chosen ? "selected_stack" : "select_stack"),
                        index + 1, stack.isEmpty() ? Component.translatable(TEXT + "empty") : stack.getHoverName(), stack.getCount()));
                setTooltip(Tooltip.create(getMessage().copy().append("\n").append(Component.translatable(TEXT + "selection_help"))));
            }
        }

        @Override public void onPress() { selected = index; WalletScreen.this.setFocused(this); }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, getMessage());
            output.add(NarratedElementType.USAGE, Component.translatable(TEXT + "selection_help"));
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
            if (chosen || isHoveredOrFocused()) {
                int x = getX(), y = getY(), color = chosen ? KingdomUi.GOLD : KingdomUi.INK;
                graphics.fill(x, y, x + 18, y + 1, color);
                graphics.fill(x, y + 17, x + 18, y + 18, color);
                graphics.fill(x, y, x + 1, y + 18, color);
                graphics.fill(x + 17, y, x + 18, y + 18, color);
            }
        }
    }
}
