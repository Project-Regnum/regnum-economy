package dev.regnum.economy.client;

import dev.regnum.economy.RegnumEconomy;
import dev.regnum.economy.client.mixin.ContainerScreenAccess;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

final class BalanceSlot extends AbstractWidget {
    private static final ResourceLocation ICON = RegnumEconomy.id("textures/item/gold_coin.png");
    private static final String[] UNITS = {"", "k", "M", "B", "T", "Qa", "Qi"};
    private static long balance = -1;

    private final Minecraft client;
    private long displayedBalance = -1;
    private String displayCount = "";

    private BalanceSlot(Minecraft client) {
        super(0, 0, 20, 20, Component.empty());
        this.client = client;
    }

    static void setBalance(long amount) {
        balance = amount;
    }

    static void addToInventory(Minecraft client, Screen screen, int scaledWidth, int scaledHeight) {
        if (!(screen instanceof InventoryScreen || screen instanceof CreativeModeInventoryScreen)) {
            return;
        }

        BalanceSlot slot = new BalanceSlot(client);
        slot.update(screen);
        Screens.getButtons(screen).add(slot);
        ScreenEvents.beforeRender(screen).register((current, graphics, x, y, delta) -> slot.update(current));
    }

    private void update(Screen screen) {
        ContainerScreenAccess layout = (ContainerScreenAccess) screen;
        visible = balance >= 0;
        setY(layout.regnumEconomy$getTopPos() + 60);
        if (screen instanceof InventoryScreen inventoryScreen) {
            visible &= screen.width >= 379 || !inventoryScreen.getRecipeBookComponent().isVisible();
            setX(layout.regnumEconomy$getLeftPos() + 152);
        } else {
            setX(layout.regnumEconomy$getLeftPos() + layout.regnumEconomy$getImageWidth() + 4);
        }

        if (visible && displayedBalance != balance) {
            displayedBalance = balance;
            displayCount = compactCount(balance);
            setMessage(Component.translatable("screen.regnum-economy.balance", Long.toString(balance)));
            setTooltip(Tooltip.create(getMessage()));
        }
    }

    private static String compactCount(long count) {
        int unit = 0;
        while (count >= 1000 && unit < UNITS.length - 1) {
            count /= 1000;
            unit++;
        }
        return count + UNITS[unit];
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        return isMouseOver(x, y);
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        int x = getX();
        int y = getY();
        graphics.fill(x, y, x + 20, y + 20, isHoveredOrFocused() ? 0xffffffff : 0xff373737);
        graphics.fill(x + 1, y + 1, x + 19, y + 19, 0xff8b8b8b);
        graphics.blit(ICON, x + 2, y + 2, 0f, 0f, 16, 16, 16, 16);
        int width = client.font.width(displayCount);
        float scale = Math.min(1f, 18f / Math.max(1, width));
        graphics.pose().pushPose();
        graphics.pose().translate(x + 19 - width * scale, y + 19 - client.font.lineHeight * scale, 0);
        graphics.pose().scale(scale, scale, 1);
        graphics.drawString(client.font, displayCount, 0, 0, 0xffffffff, true);
        graphics.pose().popPose();
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narration) {
        narration.add(NarratedElementType.TITLE, getMessage());
    }
}
