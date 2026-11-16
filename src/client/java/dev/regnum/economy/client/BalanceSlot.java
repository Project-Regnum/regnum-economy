package dev.regnum.economy.client;

import dev.regnum.economy.RegnumEconomy;
import dev.regnum.economy.client.mixin.ContainerScreenAccess;
import dev.regnum.economy.money.WalletPackets;
import dev.sakhno.regnum.nations.client.ui.KingdomUi;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

final class BalanceSlot extends AbstractButton {
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

        // Keep the creative-inventory badge outside the container; use the opposite edge if needed.
        if (getRight() > screen.width - 2) setX(layout.regnumEconomy$getLeftPos() - getWidth() - 4);
        visible &= getX() >= 2 && getRight() <= screen.width - 2 && getBottom() <= screen.height - 2;
        if (visible && displayedBalance != balance) {
            displayedBalance = balance;
            displayCount = compactCount(balance);
            setMessage(Component.translatable("screen.regnum-economy.balance", Long.toString(balance)));
            setTooltip(Tooltip.create(getMessage().copy().append(". ").append(Component.translatable("screen.regnum-economy.wallet.open"))));
            setTooltipDelay(java.time.Duration.ofMillis(500));
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
    public void onPress() {
        if (ClientPlayNetworking.canSend(WalletPackets.Open.TYPE)) {
            ClientPlayNetworking.send(new WalletPackets.Open());
        }
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        int x = getX();
        int y = getY();
        graphics.fill(x, y, x + 20, y + 20, isHoveredOrFocused() ? KingdomUi.INK : KingdomUi.GOLD);
        graphics.fill(x + 1, y + 1, x + 19, y + 19, KingdomUi.RECESS);
        graphics.blit(ICON, x + 2, y + 2, 0f, 0f, 16, 16, 16, 16);
        String label = KingdomUi.ellipsize(client.font, Component.literal(displayCount), 18);
        graphics.drawString(client.font, label, x + 19 - client.font.width(label), y + 19 - client.font.lineHeight, 0xffffffff, true);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narration) {
        narration.add(NarratedElementType.TITLE, getMessage());
    }
}
