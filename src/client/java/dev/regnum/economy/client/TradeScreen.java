package dev.regnum.economy.client;

import dev.regnum.economy.trade.TradeMenu;
import dev.regnum.economy.trade.TradePackets;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import org.lwjgl.glfw.GLFW;

final class TradeScreen extends AbstractContainerScreen<TradeMenu> {
    private static final String TEXT = "screen.regnum-economy.trade.";
    private static final int LABEL = 0xff404040;

    private EditBox amount;
    private Button confirm;
    private boolean settingAmount;

    TradeScreen(TradeMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 246;
        imageHeight = 236;
        inventoryLabelX = TradeMenu.INVENTORY_X;
        inventoryLabelY = TradeMenu.INVENTORY_Y - 11;
    }

    @Override
    protected void init() {
        super.init();
        String previous = amount == null ? Long.toString(menu.state() == null ? 0 : menu.state().ownCoins()) : amount.getValue();
        amount = addRenderableWidget(new EditBox(font, leftPos + 49, topPos + 88, 66, 16,
                Component.translatable(TEXT + "coins")));
        amount.setMaxLength(19);
        amount.setFilter(value -> value.isEmpty() || parseAmount(value) >= 0);
        amount.setValue(previous);
        amount.setResponder(value -> {
            long coins = parseAmount(value);
            if (!settingAmount && coins >= 0 && menu.state() != null) {
                send(TradePackets.Action.OFFER_COINS, coins);
            }
        });

        confirm = addRenderableWidget(Button.builder(Component.translatable(TEXT + "confirm"),
                button -> send(TradePackets.Action.CONFIRM, 0))
                .bounds(leftPos + 10, topPos + 120, 110, 18).build());
        addRenderableWidget(Button.builder(Component.translatable(TEXT + "cancel"),
                button -> send(TradePackets.Action.CANCEL, 0))
                .bounds(leftPos + 126, topPos + 120, 110, 18).build());
        updateControls();
    }

    private static long parseAmount(String value) {
        if (value.isEmpty()) {
            return 0;
        }
        if (!value.chars().allMatch(character -> character >= '0' && character <= '9')) {
            return -1;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private void send(int action, long coins) {
        TradePackets.State state = menu.state();
        if (state != null && ClientPlayNetworking.canSend(TradePackets.Action.TYPE)) {
            ClientPlayNetworking.send(new TradePackets.Action(menu.containerId, state.revision(), action, coins));
        }
    }

    private boolean hasOffer(TradePackets.State state) {
        return menu.hasOwnItems() || menu.hasOtherItems() || state.ownCoins() > 0 || state.otherCoins() > 0;
    }

    private void updateControls() {
        TradePackets.State state = menu.state();
        if (state == null) {
            amount.setEditable(false);
            confirm.active = false;
            return;
        }
        boolean locked = state.seconds() > 0;
        if (locked && parseAmount(amount.getValue()) != state.ownCoins()) {
            settingAmount = true;
            amount.setValue(Long.toString(state.ownCoins()));
            settingAmount = false;
        }
        amount.setEditable(!locked);
        confirm.setMessage(Component.translatable(TEXT + (state.ownConfirmed() ? "pause" : "confirm")));
        confirm.active = state.ownConfirmed() || (hasOffer(state)
                && parseAmount(amount.getValue()) == state.ownCoins()
                && state.ownCoins() <= state.available());
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (amount.isFocused() && amount.isActive() && key != GLFW.GLFW_KEY_ESCAPE && key != GLFW.GLFW_KEY_TAB) {
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                setFocused(null);
                amount.setFocused(false);
            } else {
                amount.keyPressed(key, scanCode, modifiers);
            }
            return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        updateControls();
        super.render(graphics, mouseX, mouseY, delta);
        renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float delta, int mouseX, int mouseY) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xff222222);
        graphics.fill(leftPos + 1, topPos + 1, leftPos + imageWidth - 1, topPos + imageHeight - 1, 0xff555555);
        graphics.fill(leftPos + 1, topPos + 1, leftPos + imageWidth - 2, topPos + imageHeight - 2, 0xffffffff);
        graphics.fill(leftPos + 2, topPos + 2, leftPos + imageWidth - 2, topPos + imageHeight - 2, 0xffc6c6c6);

        for (Slot slot : menu.slots) {
            int x = leftPos + slot.x - 1;
            int y = topPos + slot.y - 1;
            graphics.fill(x, y, x + 18, y + 18, 0xff373737);
            graphics.fill(x + 1, y + 1, x + 18, y + 18, 0xffffffff);
            graphics.fill(x + 1, y + 1, x + 17, y + 17, 0xff8b8b8b);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        drawFitted(graphics, title, 8, 6, 226);
        graphics.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, LABEL, false);
        TradePackets.State state = menu.state();
        label(graphics, "own_offer", 12, 20, state != null && state.ownConfirmed() ? 0xff246233 : LABEL);
        label(graphics, "other_offer", 132, 20, state != null && state.otherConfirmed() ? 0xff246233 : LABEL);
        label(graphics, "coins", 12, 93, LABEL);
        if (state == null) {
            return;
        }
        drawFitted(graphics, Component.translatable(TEXT + "coin_count", Long.toString(state.otherCoins())), 132, 93, 104);
        Component status;
        if (state.seconds() > 0) {
            status = Component.translatable(TEXT + "countdown", state.seconds());
        } else if (parseAmount(amount.getValue()) > state.available()) {
            status = Component.translatable(TEXT + "insufficient");
        } else if (state.issue() == TradePackets.State.INVENTORY_SPACE) {
            status = Component.translatable("message.regnum-economy.trade.space");
        } else if (state.issue() == TradePackets.State.COIN_BALANCE) {
            status = Component.translatable("message.regnum-economy.trade.balance");
        } else if (state.ownConfirmed()) {
            status = Component.translatable(TEXT + "waiting");
        } else if (state.otherConfirmed()) {
            status = Component.translatable(TEXT + "other_confirmed");
        } else {
            status = Component.translatable(TEXT + (hasOffer(state) ? "review" : "empty_offer"));
        }
        drawFitted(graphics, status, 12, 108, 224);
    }

    private void label(GuiGraphics graphics, String key, int x, int y, int color) {
        graphics.drawString(font, Component.translatable(TEXT + key), x, y, color, false);
    }

    private void drawFitted(GuiGraphics graphics, Component text, int x, int y, int width) {
        float scale = Math.min(1f, (float) width / Math.max(1, font.width(text)));
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0);
        graphics.pose().scale(scale, scale, 1);
        graphics.drawString(font, text, 0, 0, LABEL, false);
        graphics.pose().popPose();
    }
}
