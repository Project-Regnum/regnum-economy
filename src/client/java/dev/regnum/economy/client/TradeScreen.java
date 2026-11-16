package dev.regnum.economy.client;

import dev.regnum.economy.trade.TradeMenu;
import dev.sakhno.regnum.nations.client.ui.KingdomUi;
import net.minecraft.client.gui.components.Tooltip;
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
    private static final int LABEL = KingdomUi.INK;

    private EditBox amount;
    private Button confirm;
    private Component feedback = Component.translatable(TEXT + "pending");
    private boolean settingAmount;
    private long submittedRevision = -1;
    private long submittedAt;
    private int submittedAction = -1;

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

        confirm = addRenderableWidget(new KingdomUi.RoyalButton(leftPos + 10, topPos + 120, 110, Component.translatable(TEXT + "confirm"),
                button -> send(TradePackets.Action.CONFIRM, 0), KingdomUi.ButtonStyle.PRIMARY));
        addRenderableWidget(new KingdomUi.RoyalButton(leftPos + 126, topPos + 120, 110, Component.translatable(TEXT + "cancel"),
                button -> send(TradePackets.Action.CANCEL, 0)));
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
        if (state != null && (action == TradePackets.Action.CANCEL || state.revision() != submittedRevision)
                && ClientPlayNetworking.canSend(TradePackets.Action.TYPE)) {
            submittedRevision = state.revision();
            submittedAction = action;
            submittedAt = System.currentTimeMillis();
            ClientPlayNetworking.send(new TradePackets.Action(menu.containerId, state.session(), state.revision(), action, coins));
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
        amount.setTooltip(Tooltip.create(Component.translatable(TEXT + "available", Long.toString(state.available()))));
        boolean locked = state.seconds() > 0;
        boolean pending = state.revision() == submittedRevision;
        // Coalesce edits typed while the preceding amount is awaiting acknowledgement.
        if (!locked && !pending && parseAmount(amount.getValue()) >= 0
                && parseAmount(amount.getValue()) != state.ownCoins()) {
            send(TradePackets.Action.OFFER_COINS, parseAmount(amount.getValue()));
            pending = true;
        }
        if (locked && parseAmount(amount.getValue()) != state.ownCoins()) {
            settingAmount = true;
            amount.setValue(Long.toString(state.ownCoins()));
            settingAmount = false;
        }
        amount.setEditable(!locked && !state.ownConfirmed() && !(pending && submittedAction == TradePackets.Action.CONFIRM));
        confirm.setMessage(Component.translatable(TEXT + (state.ownConfirmed() ? "pause" : "confirm")));
        confirm.active = !pending && (state.ownConfirmed() || (hasOffer(state)
                && parseAmount(amount.getValue()) == state.ownCoins()
                && state.ownCoins() <= state.available()));
        if (pending && System.currentTimeMillis() - submittedAt > 10_000) {
            // Do not resend a financial confirmation. Closing safely returns escrow.
            confirm.active = false;
        }
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
        if (mouseX >= leftPos + 8 && mouseX < leftPos + imageWidth - 8) {
            if (mouseY >= topPos + 106 && mouseY < topPos + 119) KingdomUi.tooltip(graphics, font, feedback, mouseX, mouseY);
            else if (menu.state() != null && mouseY >= topPos + 4 && mouseY < topPos + 18)
                KingdomUi.tooltip(graphics, font, title.copy().append(". ").append(Component.translatable(TEXT + "available", menu.state().available())), mouseX, mouseY);
        }
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float delta, int mouseX, int mouseY) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, KingdomUi.GOLD);
        graphics.fill(leftPos + 1, topPos + 1, leftPos + imageWidth - 1, topPos + imageHeight - 1, KingdomUi.BACKGROUND);
        graphics.fill(leftPos + 123, topPos + 20, leftPos + 124, topPos + 104, KingdomUi.BORDER);

        for (Slot slot : menu.slots) {
            int x = leftPos + slot.x - 1;
            int y = topPos + slot.y - 1;
            graphics.fill(x, y, x + 18, y + 18, KingdomUi.BORDER);
            graphics.fill(x + 1, y + 1, x + 18, y + 18, KingdomUi.BORDER);
            graphics.fill(x + 1, y + 1, x + 17, y + 17, KingdomUi.RECESS);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        drawFitted(graphics, title, 8, 6, 112);
        if (menu.state() != null) drawFitted(graphics, Component.translatable(TEXT + "balance", menu.state().available()), 126, 6, 110);
        graphics.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, LABEL, false);
        TradePackets.State state = menu.state();
        label(graphics, "own_offer", 12, 20, state != null && state.ownConfirmed() ? KingdomUi.GOLD : LABEL);
        label(graphics, "other_offer", 132, 20, state != null && state.otherConfirmed() ? KingdomUi.GOLD : LABEL);
        label(graphics, "coins", 12, 93, LABEL);
        if (state == null) {
            drawFitted(graphics, Component.translatable(TEXT + "pending"), 12, 108, 224);
            return;
        }
        drawFitted(graphics, Component.translatable(TEXT + "coin_count", Long.toString(state.otherCoins())), 132, 93, 104);
        Component status;
        if (state.revision() == submittedRevision && System.currentTimeMillis() - submittedAt > 10_000) {
            status = Component.translatable(TEXT + "timeout");
        } else if (state.revision() == submittedRevision) {
            status = Component.translatable(TEXT + "pending");
        } else if (state.seconds() > 0) {
            status = Component.translatable(TEXT + "countdown", state.seconds());
        } else if (parseAmount(amount.getValue()) > state.available()) {
            status = Component.translatable(TEXT + "insufficient");
        } else if (state.issue() == TradePackets.State.INVENTORY_SPACE) {
            status = Component.translatable("message.regnum-economy.trade.space");
        } else if (state.issue() == TradePackets.State.COIN_BALANCE) {
            status = Component.translatable("message.regnum-economy.trade.balance");
        } else if (state.issue() == TradePackets.State.SAVE_FAILED) {
            status = Component.translatable("message.regnum-economy.trade.save_failed");
        } else if (state.ownConfirmed()) {
            status = Component.translatable(TEXT + "waiting");
        } else if (state.otherConfirmed()) {
            status = Component.translatable(TEXT + "other_confirmed");
        } else {
            status = Component.translatable(TEXT + (hasOffer(state) ? "review" : "empty_offer"));
        }
        feedback = status;
        drawFitted(graphics, status, 12, 108, 224);
    }

    @Override public Component getNarrationMessage() {
        var state = menu.state();
        return title.copy().append(". ").append(feedback).append(". ").append(state == null ? Component.empty()
                : Component.translatable(TEXT + "summary", state.available(), state.ownCoins(), state.otherCoins(),
                        Component.translatable(TEXT + (state.ownConfirmed() ? "confirmed" : "editing")),
                        Component.translatable(TEXT + (state.otherConfirmed() ? "confirmed" : "editing"))));
    }

    private void label(GuiGraphics graphics, String key, int x, int y, int color) {
        Component label = Component.translatable(TEXT + key);
        if (!key.equals("coins") && menu.state() != null) {
            boolean confirmed = key.equals("own_offer") ? menu.state().ownConfirmed() : menu.state().otherConfirmed();
            label = Component.translatable(TEXT + (confirmed ? "confirmed_items" : "editing_items"), label);
        }
        graphics.drawString(font, KingdomUi.ellipsize(font, label, key.equals("coins") ? 34 : 104), x, y, color, false);
    }

    private void drawFitted(GuiGraphics graphics, Component text, int x, int y, int width) {
        graphics.drawString(font, KingdomUi.ellipsize(font, text, width), x, y, LABEL, false);
    }
}
