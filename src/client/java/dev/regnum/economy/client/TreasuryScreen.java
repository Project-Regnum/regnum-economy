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
    private KingdomUi.RoyalButton deposit, withdraw, refresh, reviewButton;
    private boolean withdrawing;
    private long nextRequest, latestRequest, pending, sentAt, lastReadAt;
    private boolean needsRefresh, needsContext;
    private int review = -1;
    private long reviewAmount, reviewRevision;
    private UUID reviewSession;
    private Component feedback = text("loading");

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
        bounds = KingdomUi.folioBounds(width, height, 420, 280);
        int x = bounds.left(), y = bounds.y(), content = bounds.contentWidth();
        String previous = amount == null ? "" : amount.getValue();
        if (review >= 0) {
            Component wallet = text("wallet_name"), treasury = text("account_name", context.entity().name());
            addRenderableWidget(KingdomUi.paragraph(font, x, y + 80, content - 6, bounds.footerY() - y - 112,
                    text("review", review == TreasuryPackets.DEPOSIT ? wallet : treasury,
                            review == TreasuryPackets.DEPOSIT ? treasury : wallet, reviewAmount)));
            int half = (content - 6) / 2;
            var cancel = addRenderableWidget(new KingdomUi.RoyalButton(x + half + 6, bounds.footerY(), half, text("cancel"), b -> cancelReview()));
            addRenderableWidget(new KingdomUi.RoyalButton(x, bounds.footerY(), half, text("confirm"), b -> {
                if (state == null || pending != 0 || needsRefresh || needsContext || state.revision() != reviewRevision || !state.session().equals(reviewSession)) { cancelReview(); return; }
                int action = review;
                review = -1; initForm(); amount.setValue(Long.toString(reviewAmount)); send(action);
            }, KingdomUi.ButtonStyle.PRIMARY));
            setInitialFocus(cancel);
            return;
        }
        amount = addRenderableWidget(KingdomUi.field(font, x, y + 138, content, text("amount")));
        amount.setMaxLength(19);
        amount.setFilter(value -> value.isEmpty() || parse(value) >= 0);
        amount.setValue(previous);
        amount.setHint(text("amount"));
        int half = (content - KingdomUi.GAP) / 2;
        deposit = addRenderableWidget(new KingdomUi.RoyalButton(x, y + 110, half, text("deposit"), b -> { withdrawing = false; initForm(); }));
        withdraw = addRenderableWidget(new KingdomUi.RoyalButton(x + half + KingdomUi.GAP, y + 110, half, text("withdraw"), b -> { withdrawing = true; initForm(); }));
        refresh = addRenderableWidget(new KingdomUi.RoyalButton(bounds.right() - 64, bounds.footerY(), 64, text("refresh"), b -> {
            if (needsContext) onClose();
            else send(TreasuryPackets.OPEN);
        }));
        addRenderableWidget(new KingdomUi.RoyalButton(x, bounds.footerY(), 64, text("back"), b -> onClose()));
        reviewButton = addRenderableWidget(new KingdomUi.RoyalButton(x + 70, bounds.footerY(), content - 140,
                text("review_action"), b -> review(withdrawing ? TreasuryPackets.WITHDRAW : TreasuryPackets.DEPOSIT), KingdomUi.ButtonStyle.PRIMARY));
        deposit.selected(!withdrawing); withdraw.selected(withdrawing);
        if (state == null && pending == 0) send(TreasuryPackets.OPEN);
    }

    private void initForm() { rebuildWidgets(); }
    private void cancelReview() { review = -1; initForm(); }
    private void review(int action) {
        long coins = parse(amount.getValue());
        if (state == null || pending != 0 || needsRefresh || needsContext || coins <= 0
                || (action == TreasuryPackets.DEPOSIT ? !state.deposit() || coins > state.wallet() : !state.withdraw() || coins > state.balance())) return;
        review = action; reviewAmount = coins; reviewRevision = state.revision(); reviewSession = state.session();
        rebuildWidgets();
    }

    @Override protected void setInitialFocus() { if (getFocused() == null) super.setInitialFocus(); }

    private static long parse(String value) {
        if (value.isEmpty() || !value.chars().allMatch(c -> c >= '0' && c <= '9')) return -1;
        try { return Long.parseLong(value); } catch (NumberFormatException ignored) { return -1; }
    }

    private void send(int action) {
        if (pending != 0 || nextRequest == Long.MAX_VALUE || !ClientPlayNetworking.canSend(TreasuryPackets.Request.TYPE)) return;
        if (action != TreasuryPackets.OPEN && (state == null || needsRefresh || needsContext || parse(amount.getValue()) < 1)) return;
        pending = latestRequest = ++nextRequest;
        sentAt = System.currentTimeMillis();
        ClientPlayNetworking.send(new TreasuryPackets.Request(screen, pending, state == null ? NONE : state.session(),
                state == null ? 0 : state.revision(), action, context.entity().ref(), parse(amount.getValue())));
    }

    void accept(TreasuryPackets.State state) {
        if (!screen.equals(state.screen())) return;
        if (state.request() != 0 && state.request() != latestRequest) return;
        this.state = state;
        pending = 0;
        lastReadAt = System.currentTimeMillis();
        needsContext = state.result().equals("stale_context") || state.result().equals("missing_entity");
        needsRefresh = state.balance() < 0 || state.result().equals("stale");
        feedback = text(state.result().equals("ready") && !state.deposit() && !state.withdraw() ? "permission" : state.result());
        if (review >= 0) cancelReview();
    }

    @Override
    public void tick() {
        if (minecraft.player == null || minecraft.level == null) { minecraft.setScreen(null); return; }
        if (pending != 0 && System.currentTimeMillis() - sentAt > 10_000) {
            pending = 0;
            needsRefresh = true;
            feedback = text("retry");
        }
        if (review < 0 && pending == 0 && state != null && !needsRefresh && !needsContext
                && System.currentTimeMillis() - lastReadAt > 5_000) send(TreasuryPackets.OPEN);
    }

    @Override public void onClose() {
        if (review >= 0) { cancelReview(); return; }
        if (minecraft.player != null && minecraft.level != null && state != null
                && nextRequest < Long.MAX_VALUE && ClientPlayNetworking.canSend(TreasuryPackets.Request.TYPE)) {
            ClientPlayNetworking.send(new TreasuryPackets.Request(screen, ++nextRequest, state.session(), state.revision(),
                    TreasuryPackets.CLOSE, context.entity().ref(), 0));
        }
        minecraft.setScreen(minecraft.player == null || minecraft.level == null ? null : returnTo);
    }
    @Override public void renderBackground(GuiGraphics graphics, int x, int y, float delta) { }
    @Override public boolean isPauseScreen() { return false; }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        long coins = parse(amount.getValue());
        boolean ready = pending == 0 && !needsRefresh && !needsContext && state != null && state.balance() >= 0;
        deposit.active = ready && state.deposit();
        withdraw.active = ready && state.withdraw();
        reviewButton.active = ready && coins > 0 && (withdrawing ? state.withdraw() && coins <= state.balance() : state.deposit() && coins <= state.wallet());
        refresh.active = pending == 0;
        deposit.setTooltip(net.minecraft.client.gui.components.Tooltip.create(text(state != null && !state.deposit() ? "permission" : "deposit_help")));
        withdraw.setTooltip(net.minecraft.client.gui.components.Tooltip.create(text(state != null && !state.withdraw() ? "permission" : "withdraw_help")));
        graphics.fill(0, 0, width, height, KingdomUi.BACKGROUND);
        KingdomUi.frame(graphics, font, title, bounds.x(), bounds.y(), bounds.width(), bounds.height());
        if (!needsContext) NationsIdentity.draw(graphics, font, context.entity(), bounds.left(), bounds.y() + 42, bounds.contentWidth());
        else graphics.drawString(font, KingdomUi.ellipsize(font, text("stale_identity"), bounds.contentWidth()),
                bounds.left(), bounds.y() + 42, KingdomUi.MUTED, false);
        if (review >= 0) { super.render(graphics, mouseX, mouseY, delta); return; }
        if (state != null && state.balance() >= 0) {
            graphics.drawString(font, KingdomUi.ellipsize(font, text(needsRefresh ? "cached_balance" : "balance", state.balance()), bounds.contentWidth()), bounds.left(), bounds.y() + 82, KingdomUi.INK, false);
            graphics.drawString(font, KingdomUi.ellipsize(font, text("wallet", state.wallet()), bounds.contentWidth()), bounds.left(), bounds.y() + 96, KingdomUi.MUTED, false);
        }
        KingdomUi.feedback(graphics, font, pending != 0 ? text("loading") : feedback,
                bounds.left(), bounds.footerY() - 16, bounds.contentWidth(),
                needsRefresh || needsContext ? KingdomUi.Feedback.ERROR : KingdomUi.Feedback.NEUTRAL);
        super.render(graphics, mouseX, mouseY, delta);
        if (mouseX >= bounds.left() && mouseX < bounds.right()) {
            if (mouseY >= bounds.footerY()-16 && mouseY < bounds.footerY()) KingdomUi.tooltip(graphics, font, feedback, mouseX, mouseY);
            else if (state != null && mouseY >= bounds.y()+82 && mouseY < bounds.y()+108)
                KingdomUi.tooltip(graphics, font, text("balance", state.balance()).copy().append(". ").append(text("wallet", state.wallet())), mouseX, mouseY);
        }
    }
    @Override public Component getNarrationMessage() {
        return title.copy().append(". ").append(context.entity().name()).append(". ").append(feedback)
                .append(state == null ? Component.empty() : text("balance", state.balance()).copy().append(". ").append(text("wallet", state.wallet())));
    }
}
