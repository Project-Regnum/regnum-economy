package dev.regnum.economy.client;

import dev.regnum.economy.market.TreasuryPackets;
import dev.sakhno.regnum.nations.api.client.v1.EconomyContext;
import dev.sakhno.regnum.nations.api.client.v1.NationsIdentity;
import dev.sakhno.regnum.nations.client.ui.KingdomUi;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.UUID;

final class TreasuryScreen extends Screen {
    private static final UUID NONE = new UUID(0, 0);
    private final UUID screen = UUID.randomUUID();
    private final EconomyContext context;
    private final Screen returnTo;
    private KingdomUi.FolioBounds bounds;
    private TreasuryPackets.State state;
    private EditBox amount;
    private KingdomUi.RoyalButton deposit, withdraw, refresh;
    private boolean pending;
    private long sentAt;

    TreasuryScreen(EconomyContext context, Screen returnTo) {
        super(text("title"));
        this.context = context;
        this.returnTo = returnTo;
    }

    private static Component text(String key, Object... args) {
        return Component.translatable("screen.regnum-economy.treasury." + key, args);
    }

    @Override
    protected void init() {
        bounds = KingdomUi.folioBounds(width, height, 420, 250);
        int x = bounds.left(), y = bounds.y(), content = bounds.contentWidth();
        String previous = amount == null ? "" : amount.getValue();
        amount = addRenderableWidget(KingdomUi.field(font, x, y + 112, content, text("amount")));
        amount.setMaxLength(19);
        amount.setFilter(value -> value.isEmpty() || parse(value) >= 0);
        amount.setValue(previous);
        int half = (content - KingdomUi.GAP) / 2;
        deposit = addRenderableWidget(new KingdomUi.RoyalButton(x, y + 138, half, text("deposit"), b -> send(TreasuryPackets.DEPOSIT)));
        withdraw = addRenderableWidget(new KingdomUi.RoyalButton(x + half + KingdomUi.GAP, y + 138, half, text("withdraw"), b -> send(TreasuryPackets.WITHDRAW)));
        refresh = addRenderableWidget(new KingdomUi.RoyalButton(x, bounds.footerY(), half, text("refresh"), b -> send(TreasuryPackets.OPEN)));
        addRenderableWidget(new KingdomUi.RoyalButton(x + half + KingdomUi.GAP, bounds.footerY(), half, text("back"), b -> onClose()));
        if (state == null && !pending) send(TreasuryPackets.OPEN);
    }

    private static long parse(String value) {
        if (value.isEmpty() || !value.chars().allMatch(c -> c >= '0' && c <= '9')) return -1;
        try { return Long.parseLong(value); } catch (NumberFormatException ignored) { return -1; }
    }

    private void send(int action) {
        if (pending || !ClientPlayNetworking.canSend(TreasuryPackets.Request.TYPE) || action != TreasuryPackets.OPEN && state == null) return;
        pending = true;
        sentAt = System.currentTimeMillis();
        ClientPlayNetworking.send(new TreasuryPackets.Request(screen, state == null ? NONE : state.session(),
                state == null ? 0 : state.revision(), action, context.entity().ref(), parse(amount.getValue())));
    }

    void accept(TreasuryPackets.State state) {
        if (!screen.equals(state.screen())) return;
        this.state = state;
        pending = false;
    }

    @Override
    public void tick() {
        if (minecraft.player == null || minecraft.level == null) { onClose(); return; }
        if (pending && System.currentTimeMillis() - sentAt > 10_000) {
            pending = false;
            state = null;
        }
    }

    @Override public void onClose() { minecraft.setScreen(returnTo); }
    @Override public boolean isPauseScreen() { return false; }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        long coins = parse(amount.getValue());
        boolean ready = !pending && state != null && state.balance() >= 0;
        deposit.active = ready && state.deposit() && coins > 0 && coins <= state.wallet();
        withdraw.active = ready && state.withdraw() && coins > 0 && coins <= state.balance();
        refresh.active = !pending;
        graphics.fill(0, 0, width, height, KingdomUi.BACKGROUND);
        KingdomUi.frame(graphics, font, title, bounds.x(), bounds.y(), bounds.width(), bounds.height());
        NationsIdentity.draw(graphics, font, context.entity(), bounds.left(), bounds.y() + 42, bounds.contentWidth());
        if (state != null && state.balance() >= 0) {
            graphics.drawString(font, KingdomUi.ellipsize(font, text("balance", state.balance()), bounds.contentWidth()), bounds.left(), bounds.y() + 82, KingdomUi.INK, false);
            graphics.drawString(font, KingdomUi.ellipsize(font, text("wallet", state.wallet()), bounds.contentWidth()), bounds.left(), bounds.y() + 96, KingdomUi.MUTED, false);
        }
        Component feedback = pending ? text("loading") : state == null ? text("retry") : text(state.result());
        KingdomUi.feedback(graphics, font, feedback, bounds.left(), bounds.footerY() - 16, bounds.contentWidth(), KingdomUi.Feedback.NEUTRAL);
        super.render(graphics, mouseX, mouseY, delta);
    }
}
