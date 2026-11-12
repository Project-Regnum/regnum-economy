package dev.regnum.economy.client;

import dev.regnum.economy.money.CoinConversion;
import dev.regnum.economy.money.Denomination;
import dev.regnum.economy.money.WalletMenu;
import dev.regnum.economy.money.WalletPackets;
import dev.sakhno.regnum.nations.client.ui.KingdomUi;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

final class WalletScreen extends AbstractContainerScreen<WalletMenu> {
    private static final String TEXT = "screen.regnum-economy.wallet.";
    private EditBox amount;
    private Button deposit;
    private Button depositAll;
    private Button withdraw;
    private int selected = -1;
    private long submittedRevision = -1;

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
    }

    private static long parse(String text) {
        if (text.isEmpty() || !text.chars().allMatch(value -> value >= '0' && value <= '9')) {
            return -1;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private void send(int action) {
        WalletPackets.State state = menu.state();
        if (state == null || state.revision() == submittedRevision || !ClientPlayNetworking.canSend(WalletPackets.Action.TYPE)) {
            return;
        }
        submittedRevision = state.revision();
        int inventorySlot = selected < 0 ? -1 : menu.slots.get(selected).getContainerSlot();
        ClientPlayNetworking.send(new WalletPackets.Action(menu.containerId, state.session(), state.revision(),
                action, inventorySlot, parse(amount.getValue())));
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (button == 0) {
            for (int index = 0; index < menu.slots.size(); index++) {
                Slot slot = menu.slots.get(index);
                if (x >= leftPos + slot.x && x < leftPos + slot.x + 16
                        && y >= topPos + slot.y && y < topPos + slot.y + 16) {
                    selected = index;
                    return true;
                }
            }
        }
        return super.mouseClicked(x, y, button);
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (amount.isFocused() && key != GLFW.GLFW_KEY_ESCAPE && key != GLFW.GLFW_KEY_TAB) {
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                send(WalletPackets.Action.WITHDRAW);
            } else {
                amount.keyPressed(key, scanCode, modifiers);
            }
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
            selected = Math.floorMod(selected + movement, menu.slots.size());
            return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics graphics, int x, int y, float delta) {
        WalletPackets.State state = menu.state();
        boolean ready = state != null && state.revision() != submittedRevision;
        deposit.active = ready && selected >= 0 && Denomination.of(menu.slots.get(selected).getItem()) != null;
        depositAll.active = ready;
        withdraw.active = ready && parse(amount.getValue()) > 0 && parse(amount.getValue()) <= state.balance();
        super.render(graphics, x, y, delta);
        renderTooltip(graphics, x, y);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float delta, int x, int y) {
        KingdomUi.frame(graphics, font, title, leftPos, topPos, imageWidth, imageHeight);
        for (int index = 0; index < menu.slots.size(); index++) {
            Slot slot = menu.slots.get(index);
            int sx = leftPos + slot.x - 1;
            int sy = topPos + slot.y - 1;
            graphics.fill(sx, sy, sx + 18, sy + 18, index == selected ? KingdomUi.GOLD : KingdomUi.BORDER);
            graphics.fill(sx + 1, sy + 1, sx + 17, sy + 17, KingdomUi.RECESS);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int x, int y) {
        WalletPackets.State state = menu.state();
        if (state != null) {
            fitted(graphics, Component.translatable("screen.regnum-economy.balance", Long.toString(state.balance())), 12, 38, 162);
            if (state.result() >= 0 && state.result() < CoinConversion.Result.values().length) {
                String result = CoinConversion.Result.values()[state.result()].name().toLowerCase(Locale.ROOT);
                fitted(graphics, Component.translatable("message.regnum-economy.wallet." + result), 12, 103, 138);
            } else {
                fitted(graphics, Component.translatable(TEXT + "select"), 12, 103, 138);
            }
        }
        graphics.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, KingdomUi.MUTED, false);
    }

    private void fitted(GuiGraphics graphics, Component text, int x, int y, int width) {
        float scale = Math.min(1f, (float) width / Math.max(1, font.width(text)));
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0);
        graphics.pose().scale(scale, scale, 1);
        graphics.drawString(font, text, 0, 0, KingdomUi.INK, false);
        graphics.pose().popPose();
    }
}
