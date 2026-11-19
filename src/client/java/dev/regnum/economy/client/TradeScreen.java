package dev.regnum.economy.client;

import dev.regnum.economy.trade.TradeMenu;
import dev.regnum.economy.trade.TradePackets;
import dev.sakhno.regnum.nations.client.ui.KingdomUi;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import org.lwjgl.glfw.GLFW;

final class TradeScreen extends AbstractContainerScreen<TradeMenu> {
    private static final String TEXT = "screen.regnum-economy.trade.";
    private static final int MARGIN = 8, CARD_GAP = 6, PAD = 9;
    private static final int OWN_PANEL = TradeMenu.OWN_OFFER_X - PAD;
    private static final int OTHER_PANEL = TradeMenu.OTHER_OFFER_X - PAD;
    private static final int PANEL_WIDTH = OTHER_PANEL - OWN_PANEL - CARD_GAP;
    private static final int WIDTH = OTHER_PANEL + PANEL_WIDTH + MARGIN;
    private static final int HEIGHT = TradeMenu.INVENTORY_Y + TradeMenu.HOTBAR_OFFSET + TradeMenu.SLOT_SIZE + 7;
    private static final int LEDGER_LEFT = 12, LEDGER_RIGHT = WIDTH - LEDGER_LEFT;
    private static final int SPINE = WIDTH / 2;
    private static final int PANEL_TOP = TradeMenu.OFFER_Y - 12;
    private static final int OFFER_ROWS = TradeMenu.OFFER_SLOTS / TradeMenu.OFFER_COLUMNS;
    private static final int FIELD_TOP = TradeMenu.OFFER_Y + (OFFER_ROWS - 1) * TradeMenu.SLOT_PITCH + TradeMenu.SLOT_SIZE + 12;
    private static final int FIELD_WIDTH = PANEL_WIDTH - 2 * PAD;
    private static final int PANEL_BOTTOM = FIELD_TOP + KingdomUi.CONTROL + 2;
    private static final int DETAILS_X = PAD + TradeMenu.OFFER_COLUMNS * TradeMenu.SLOT_PITCH + 4;
    private static final int DETAILS_WIDTH = PANEL_WIDTH - DETAILS_X - 6;
    private static final int STATUS_TOP = PANEL_BOTTOM + 3, STATUS_BOTTOM = TradeMenu.INVENTORY_Y - 10;
    private static final int WALLET_LEFT = OTHER_PANEL + PAD;
    private static final int TITLE_X = OWN_PANEL + PAD;
    private static final int WALLET_RIGHT = WIDTH - TITLE_X;
    private static final int TITLE_WIDTH = WALLET_LEFT - TITLE_X - PAD;
    private static final int INVENTORY_EDGE = TradeMenu.INVENTORY_X + TradeMenu.INVENTORY_COLUMNS * TradeMenu.SLOT_PITCH;
    private static final int ACTIONS_X = INVENTORY_EDGE + 14;
    private static final int ACTIONS_WIDTH = WALLET_RIGHT - ACTIONS_X;
    private static final int ACTIONS_TOP = TradeMenu.INVENTORY_Y + 8;
    private static final int CANCEL_TOP = ACTIONS_TOP + KingdomUi.CONTROL + 7;
    private static final int STATUS_PADDING = 8, COUNTDOWN_INDICATOR_WIDTH = 34;

    // Translucent warmth leaves the Nations parchment grain visible beneath both offers.
    private static final int LEDGER_WASH_TOP = 0x506c6244, LEDGER_WASH_BOTTOM = 0x326c6244;
    private static final int LINE = KingdomUi.BORDER, RECESS = KingdomUi.RECESS;
    private static final int EDITABLE_SLOT = 0xff30332a, VIEW_SLOT = 0xff292d27;
    private static final int EDITABLE_LIP = 0xff807350, SLOT_HOVER = 0xffa39776;
    private static final int INK = KingdomUi.INK, MUTED = KingdomUi.MUTED;
    private static final int BRASS = TradeFolio.BRASS, LIGHT_BRASS = TradeFolio.LIGHT_BRASS;
    private static final int WARNING = LIGHT_BRASS, ERROR = KingdomUi.ERROR;

    private static final Component CONFIRM = text("confirm");
    private static final Component PAUSE = text("pause");
    private static final Component CANCEL = text("cancel");
    private static final Component OWN_HEADING = text("own_panel");
    private static final Component OTHER_HEADING = text("other_panel");
    private static final Component WALLET_LABEL = text("wallet_available");
    private static final Component VIRTUAL_COINS = text("virtual_coins");
    private static final Component NO_AMOUNT = text("no_amount");
    private static final Component EDITABLE = text("slots_editable");
    private static final Component READ_ONLY_LABEL = text("slots_read_only");
    private static final Component LOCKED = text("slots_locked");
    private static final Component AGREED = text("agreed");
    private static final Component EDITING = text("editing");
    private static final Component OWN_ITEMS_HELP = text("own_items_help");
    private static final Component OTHER_ITEMS_HELP = text("other_items_help");
    private static final Component LOCKED_ITEMS_HELP = text("locked_items_help");
    private static final Component TIMEOUT_HELP = text("timeout_help");

    private EditBox amount;
    private KingdomUi.RoyalButton confirm;
    private KingdomUi.RoyalButton cancel;
    private long enteredCoins;
    private boolean settingAmount;
    private long submittedRevision = -1;
    private long submittedAt;
    private int submittedAction = -1;

    private TradePackets.State displayedState;
    private Component walletCoins = NO_AMOUNT;
    private Component partnerCoins = NO_AMOUNT;
    private Component partnerAmountTooltip = Component.empty();
    private Component walletTooltip = Component.empty();
    private Status status;
    private int countdownSeconds;
    private Component feedback = Component.empty();
    private List<FormattedCharSequence> statusLines = List.of();
    private boolean amountEditable;

    TradeScreen(TradeMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = WIDTH;
        imageHeight = HEIGHT;
        inventoryLabelX = TradeMenu.INVENTORY_X;
        inventoryLabelY = STATUS_BOTTOM + 1;
    }

    @Override
    protected void init() {
        super.init();
        if (amount == null) {
            amount = KingdomUi.field(font, 0, 0, FIELD_WIDTH, VIRTUAL_COINS);
            amount.setMaxLength(19);
            amount.setFilter(value -> parseAmount(value) >= 0);
            amount.setHint(NO_AMOUNT);
            amount.setResponder(value -> {
                enteredCoins = parseAmount(value);
                if (!settingAmount && enteredCoins >= 0) send(TradePackets.Action.OFFER_COINS, enteredCoins);
            });
            confirm = new KingdomUi.RoyalButton(0, 0, ACTIONS_WIDTH, CONFIRM,
                    button -> send(TradePackets.Action.CONFIRM, 0), KingdomUi.ButtonStyle.PRIMARY);
            cancel = new KingdomUi.RoyalButton(0, 0, ACTIONS_WIDTH, CANCEL,
                    button -> send(TradePackets.Action.CANCEL, 0), KingdomUi.ButtonStyle.DANGER);
        }
        place(amount, OWN_PANEL + PAD, FIELD_TOP);
        place(confirm, ACTIONS_X, ACTIONS_TOP);
        place(cancel, ACTIONS_X, CANCEL_TOP);
        updateControls();
    }

    private void place(AbstractWidget widget, int x, int y) {
        widget.setX(leftPos + x);
        widget.setY(topPos + y);
        addRenderableWidget(widget);
    }

    @Override
    protected void repositionElements() {
        var focused = getFocused();
        super.repositionElements();
        if (focused != null && children().contains(focused)) setInitialFocus(focused);
    }

    private void setServerAmount(long coins) {
        settingAmount = true;
        amount.setValue(Long.toString(coins));
        settingAmount = false;
    }

    private static long parseAmount(String value) {
        if (value.isEmpty()) return 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) < '0' || value.charAt(i) > '9') return -1;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private boolean send(int action, long coins) {
        TradePackets.State state = menu.state();
        if (state == null || !ClientPlayNetworking.canSend(TradePackets.Action.TYPE)) return false;
        if (state.revision() == submittedRevision && (action != TradePackets.Action.CANCEL || submittedAction == action)) return false;
        submittedRevision = state.revision();
        submittedAction = action;
        submittedAt = System.currentTimeMillis();
        ClientPlayNetworking.send(new TradePackets.Action(menu.containerId, state.session(), state.revision(), action, coins));
        return true;
    }

    private boolean hasOffer(TradePackets.State state) {
        return menu.hasOwnItems() || menu.hasOtherItems() || state.ownCoins() > 0 || state.otherCoins() > 0;
    }

    private void updateControls() {
        TradePackets.State state = menu.state();
        if (state == null) {
            amount.setEditable(false);
            confirm.active = false;
            cancel.active = false;
            updateStatus(Status.OPENING, 0);
            return;
        }
        if (displayedState == null) setServerAmount(state.ownCoins());
        if (state != displayedState) updateReadouts(state);
        boolean locked = state.seconds() > 0;
        boolean pending = state.revision() == submittedRevision;
        // Coalesce edits only after the preceding revision has been acknowledged.
        if (!locked && !pending && enteredCoins >= 0 && enteredCoins != state.ownCoins()) {
            pending = send(TradePackets.Action.OFFER_COINS, enteredCoins);
        }
        if (locked && enteredCoins != state.ownCoins()) setServerAmount(state.ownCoins());

        boolean hasOffer = hasOffer(state);
        boolean canConfirm = !pending && (state.ownConfirmed() || (hasOffer
                && enteredCoins == state.ownCoins() && state.ownCoins() <= state.available()));
        amountEditable = !locked && !state.ownConfirmed() && !(pending && submittedAction != TradePackets.Action.OFFER_COINS);
        amount.setEditable(amountEditable);

        var label = state.ownConfirmed() ? PAUSE : CONFIRM;
        boolean labelChanged = confirm.getMessage() != label;
        if (labelChanged) {
            confirm.setMessage(label);
            confirm.style(state.ownConfirmed() ? KingdomUi.ButtonStyle.NORMAL : KingdomUi.ButtonStyle.PRIMARY);
        }
        boolean statusChanged = updateStatus(status(state, pending, locked, hasOffer), state.seconds());
        if (labelChanged || statusChanged || confirm.active != canConfirm) {
            confirm.setTooltip(Tooltip.create(canConfirm ? label : feedback));
        }
        confirm.active = canConfirm;
        cancel.active = !(pending && submittedAction == TradePackets.Action.CANCEL);
    }

    private void updateReadouts(TradePackets.State state) {
        if (displayedState == null || state.available() != displayedState.available()) {
            walletCoins = Component.literal(Long.toString(state.available()));
            walletTooltip = text("available", walletCoins);
            amount.setTooltip(Tooltip.create(walletTooltip));
        }
        if (displayedState == null || state.otherCoins() != displayedState.otherCoins()) {
            partnerCoins = Component.literal(Long.toString(state.otherCoins()));
            partnerAmountTooltip = text("coin_count", partnerCoins);
        }
        displayedState = state;
    }

    private Status status(TradePackets.State state, boolean pending, boolean locked, boolean hasOffer) {
        if (pending) {
            if (System.currentTimeMillis() - submittedAt > 10_000) return Status.TIMEOUT;
            return switch (submittedAction) {
                case TradePackets.Action.OFFER_COINS -> Status.PENDING_COINS;
                case TradePackets.Action.CONFIRM -> state.ownConfirmed() ? Status.PENDING_PAUSE : Status.PENDING_CONFIRM;
                case TradePackets.Action.CANCEL -> Status.PENDING_CANCEL;
                default -> Status.PENDING;
            };
        }
        if (locked) return Status.COUNTDOWN;
        if (enteredCoins > state.available()) return Status.INSUFFICIENT;
        if (state.issue() == TradePackets.State.INVENTORY_SPACE) return Status.INVENTORY_SPACE;
        if (state.issue() == TradePackets.State.COIN_BALANCE) return Status.BALANCE;
        if (state.issue() == TradePackets.State.SAVE_FAILED) return Status.SAVE_FAILED;
        if (state.ownConfirmed() && state.otherConfirmed()) return Status.BOTH_CONFIRMED;
        if (state.ownConfirmed()) return Status.WAITING;
        if (state.otherConfirmed()) return Status.PARTNER_CONFIRMED;
        return hasOffer ? Status.REVIEW : Status.EMPTY;
    }

    private boolean updateStatus(Status next, int seconds) {
        int visibleSeconds = next == Status.COUNTDOWN ? seconds : 0;
        if (next == status && visibleSeconds == countdownSeconds) return false;
        status = next;
        countdownSeconds = visibleSeconds;
        feedback = next == Status.COUNTDOWN ? text("countdown", seconds) : next.message;
        int textWidth = LEDGER_RIGHT - LEDGER_LEFT - 2 * STATUS_PADDING
                - (next == Status.COUNTDOWN ? COUNTDOWN_INDICATOR_WIDTH : 0);
        statusLines = font.split(feedback, textWidth);
        return true;
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
        var help = hoverHelp(mouseX - leftPos, mouseY - topPos);
        if (help != null && menu.getCarried().isEmpty()) {
            KingdomUi.tooltip(graphics, font, font.split(help, Math.min(200, width / 2)), mouseX, mouseY, 6);
        }
        renderTooltip(graphics, mouseX, mouseY);
    }

    private Component hoverHelp(int x, int y) {
        if (x < MARGIN || x >= WIDTH - MARGIN) return null;
        if (y >= PANEL_TOP && y < PANEL_BOTTOM) {
            if (displayedState != null && y >= TradeMenu.OFFER_Y && y < FIELD_TOP - 10) {
                if (x >= OWN_PANEL + DETAILS_X && x < OWN_PANEL + PANEL_WIDTH) {
                    return displayedState.seconds() > 0 ? LOCKED_ITEMS_HELP : OWN_ITEMS_HELP;
                }
                if (x >= OTHER_PANEL + DETAILS_X) return OTHER_ITEMS_HELP;
            }
            if (displayedState != null && x >= WALLET_LEFT && y >= FIELD_TOP) return partnerAmountTooltip;
        } else if (y >= STATUS_TOP && y < STATUS_BOTTOM) {
            return status == Status.TIMEOUT ? TIMEOUT_HELP : feedback;
        } else if (y >= 4 && y < PANEL_TOP - 4) {
            if (x >= WALLET_LEFT && displayedState != null) return walletTooltip;
            if (x < WALLET_LEFT && font.width(title) > TITLE_WIDTH) return title;
        }
        return null;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float delta, int mouseX, int mouseY) {
        TradeFolio.binding(graphics, leftPos, topPos, WIDTH, HEIGHT);
        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(leftPos, topPos, 0);
        graphics.fillGradient(LEDGER_LEFT, PANEL_TOP - 3, LEDGER_RIGHT, PANEL_BOTTOM,
                LEDGER_WASH_TOP, LEDGER_WASH_BOTTOM);
        TradeFolio.headerRule(graphics, LEDGER_LEFT, LEDGER_RIGHT, PANEL_TOP - 4);
        graphics.fill(SPINE, PANEL_TOP + 4, SPINE + 1, PANEL_BOTTOM - 4, LINE);
        graphics.fill(SPINE + 1, PANEL_TOP + 4, SPINE + 2, PANEL_BOTTOM - 4, RECESS);

        drawOfferRules(graphics, OWN_PANEL, displayedState != null && displayedState.ownConfirmed());
        drawOfferRules(graphics, OTHER_PANEL, displayedState != null && displayedState.otherConfirmed());
        graphics.fill(WALLET_LEFT - 1, FIELD_TOP - 1, WALLET_LEFT + FIELD_WIDTH + 1,
                FIELD_TOP + KingdomUi.CONTROL + 1, LINE);
        graphics.fill(WALLET_LEFT, FIELD_TOP, WALLET_LEFT + FIELD_WIDTH, FIELD_TOP + KingdomUi.CONTROL, VIEW_SLOT);
        TradeFolio.rule(graphics, LEDGER_LEFT, LEDGER_RIGHT, STATUS_TOP - 3, status.accent);
        TradeFolio.rule(graphics, LEDGER_LEFT, LEDGER_RIGHT, STATUS_BOTTOM - 1, LINE);

        boolean locked = displayedState == null || displayedState.seconds() > 0;
        int localMouseX = mouseX - leftPos;
        int localMouseY = mouseY - topPos;
        for (int i = 0; i < menu.slots.size(); i++) {
            var slot = menu.slots.get(i);
            boolean editable = i < TradeMenu.OFFER_SLOTS && !locked;
            boolean inventory = i >= 2 * TradeMenu.OFFER_SLOTS;
            int x = slot.x, y = slot.y;
            int right = x + TradeMenu.SLOT_SIZE, bottom = y + TradeMenu.SLOT_SIZE;
            boolean hovered = slot.isActive() && localMouseX >= x && localMouseX < right
                    && localMouseY >= y && localMouseY < bottom;
            int lip = hovered ? SLOT_HOVER : editable ? EDITABLE_LIP : LINE;
            graphics.fill(x - 1, y - 1, right + 1, bottom + 1, hovered ? SLOT_HOVER : RECESS);
            graphics.fill(x, y, right, bottom, inventory || editable ? EDITABLE_SLOT : VIEW_SLOT);
            graphics.fill(x - 1, y - 1, right + 1, y, lip);
            graphics.fill(x - 1, y, x, bottom, lip);
        }
        pose.popPose();
    }

    private static void drawOfferRules(GuiGraphics graphics, int panel, boolean confirmed) {
        int x = panel + DETAILS_X;
        TradeFolio.rule(graphics, x, x + DETAILS_WIDTH, TradeMenu.OFFER_Y + 24, confirmed ? BRASS : LINE);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        drawFitted(graphics, title, TITLE_X, 11, TITLE_WIDTH, LIGHT_BRASS);
        drawRight(graphics, WALLET_LABEL, WALLET_RIGHT, 6, FIELD_WIDTH, MUTED);
        drawRight(graphics, walletCoins, WALLET_RIGHT, 15, FIELD_WIDTH, INK);
        drawOfferLabels(graphics, OWN_PANEL, true);
        drawOfferLabels(graphics, OTHER_PANEL, false);
        drawFitted(graphics, partnerCoins, WALLET_LEFT + 4, FIELD_TOP + 6, FIELD_WIDTH - 8,
                displayedState == null ? MUTED : INK);
        drawFitted(graphics, playerInventoryTitle, inventoryLabelX, inventoryLabelY,
                INVENTORY_EDGE - inventoryLabelX, INK);
        drawStatus(graphics);
    }

    private void drawOfferLabels(GuiGraphics graphics, int panel, boolean own) {
        drawFitted(graphics, own ? OWN_HEADING : OTHER_HEADING, panel + PAD, PANEL_TOP + 2, FIELD_WIDTH, INK);
        drawFitted(graphics, VIRTUAL_COINS, panel + PAD, FIELD_TOP - 10, FIELD_WIDTH, own && amountEditable ? INK : MUTED);
        if (displayedState == null) return;
        boolean confirmed = own ? displayedState.ownConfirmed() : displayedState.otherConfirmed();
        int x = panel + DETAILS_X;
        int statusY = TradeMenu.OFFER_Y + 9;
        if (confirmed) {
            // A small check remains legible with resource packs that omit the checkmark glyph.
            graphics.fill(x, statusY + 4, x + 2, statusY + 5, BRASS);
            graphics.fill(x + 1, statusY + 5, x + 3, statusY + 6, BRASS);
            graphics.fill(x + 2, statusY + 4, x + 4, statusY + 5, BRASS);
            graphics.fill(x + 3, statusY + 3, x + 5, statusY + 4, BRASS);
            graphics.fill(x + 4, statusY + 2, x + 6, statusY + 3, BRASS);
        }
        drawFitted(graphics, confirmed ? AGREED : EDITING, x + (confirmed ? 9 : 0), statusY,
                DETAILS_WIDTH - (confirmed ? 9 : 0), confirmed ? WARNING : INK);
        Component access = own ? (displayedState.seconds() > 0 ? LOCKED : EDITABLE) : READ_ONLY_LABEL;
        drawFitted(graphics, access, x, TradeMenu.OFFER_Y + 33, DETAILS_WIDTH,
                own && displayedState.seconds() == 0 ? INK : MUTED);
    }

    private void drawStatus(GuiGraphics graphics) {
        int lineCount = Math.min(2, statusLines.size());
        int y = STATUS_TOP + (STATUS_BOTTOM - STATUS_TOP - lineCount * font.lineHeight) / 2;
        int center = WIDTH / 2 - (countdownSeconds > 0 ? COUNTDOWN_INDICATOR_WIDTH / 2 : 0);
        for (int i = 0; i < lineCount; i++) {
            var line = statusLines.get(i);
            graphics.drawString(font, line, center - font.width(line) / 2, y + i * font.lineHeight, status.foreground, false);
        }
        if (countdownSeconds > 0) {
            int right = LEDGER_RIGHT - STATUS_PADDING;
            for (int i = 0; i < 3; i++) {
                int x = right - COUNTDOWN_INDICATOR_WIDTH + i * 12;
                graphics.fill(x, STATUS_TOP + 8, x + 8, STATUS_TOP + 10, i < countdownSeconds ? BRASS : LINE);
            }
        }
    }

    @Override
    public Component getNarrationMessage() {
        var narration = title.copy().append(". ").append(feedback);
        var state = menu.state();
        if (state != null) {
            narration.append(". ").append(text("summary", state.available(), state.ownCoins(), state.otherCoins(),
                    text(state.ownConfirmed() ? "confirmed" : "editing"), text(state.otherConfirmed() ? "confirmed" : "editing")));
        }
        return narration;
    }

    private void drawFitted(GuiGraphics graphics, Component value, int x, int y, int maxWidth, int color) {
        graphics.drawString(font, KingdomUi.ellipsize(font, value, maxWidth), x, y, color, false);
    }

    private void drawRight(GuiGraphics graphics, Component value, int right, int y, int maxWidth, int color) {
        String fitted = KingdomUi.ellipsize(font, value, maxWidth);
        graphics.drawString(font, fitted, right - font.width(fitted), y, color, false);
    }

    private static Component text(String key, Object... args) {
        return Component.translatable(TEXT + key, args);
    }

    private enum Status {
        OPENING("opening", MUTED, LINE),
        PENDING("pending", MUTED, BRASS),
        PENDING_COINS("pending_coins", INK, BRASS),
        PENDING_CONFIRM("pending_confirm", INK, BRASS),
        PENDING_PAUSE("pending_pause", INK, BRASS),
        PENDING_CANCEL("pending_cancel", INK, BRASS),
        TIMEOUT("timeout", WARNING, BRASS),
        COUNTDOWN("countdown", WARNING, BRASS),
        INSUFFICIENT("insufficient", WARNING, BRASS),
        INVENTORY_SPACE("message.regnum-economy.trade.space", WARNING, BRASS),
        BALANCE("message.regnum-economy.trade.balance", WARNING, BRASS),
        SAVE_FAILED("message.regnum-economy.trade.save_failed", ERROR, KingdomUi.BURGUNDY),
        BOTH_CONFIRMED("both_confirmed", INK, BRASS),
        WAITING("waiting", INK, BRASS),
        PARTNER_CONFIRMED("other_confirmed", INK, BRASS),
        REVIEW("review", MUTED, LINE),
        EMPTY("empty_offer", MUTED, LINE);

        private final Component message;
        private final int foreground;
        private final int accent;

        Status(String key, int foreground, int accent) {
            message = key.startsWith("message.") ? Component.translatable(key) : text(key);
            this.foreground = foreground;
            this.accent = accent;
        }
    }
}
