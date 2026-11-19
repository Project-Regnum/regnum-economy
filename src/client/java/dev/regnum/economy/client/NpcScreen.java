package dev.regnum.economy.client;

import dev.regnum.economy.npc.NpcPackets;
import dev.regnum.economy.npc.RegnumNpc;
import dev.sakhno.regnum.nations.client.ui.KingdomUi;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.FittingMultiLineTextWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class NpcScreen extends Screen {
    private NpcPackets.Page page;
    private KingdomUi.FolioBounds bounds;
    private final List<KingdomUi.RoyalButton> choices = new ArrayList<>();
    private EditBox input;
    private Paragraph paragraph;
    private NpcShopPanel shopPanel;
    private int itemX;
    private int itemY;
    private String displayName;
    private long sentAt;
    private Component feedback = Component.empty();
    private boolean timedOut;
    private GuiEventListener resizeFocus;

    NpcScreen(NpcPackets.Page page) {
        super(page.name());
        this.page = page;
    }

    void update(NpcPackets.Page replacement) {
        page = replacement;
        sentAt = 0;
        timedOut = false;
        feedback = Component.empty();
        input = null;
        paragraph = null;
        shopPanel = null;
        choices.clear();
        rebuildWidgets();
    }

    @Override
    protected void repositionElements() {
        resizeFocus = getFocused();
        super.repositionElements();
    }

    @Override
    protected void init() {
        var focusedWidget = resizeFocus;
        resizeFocus = null;
        int focused = choices.indexOf(focusedWidget);
        boolean wasInput = focusedWidget != null && focusedWidget == input;
        boolean wasParagraph = focusedWidget != null && focusedWidget == paragraph;
        String value = input == null ? page.inputValue() : input.getValue();
        double offset = paragraph == null ? 0 : paragraph.offset();
        choices.clear();
        input = null;
        int maxHeight = page.shop() == null ? 280 : page.shop().review() ? 208 : 224;
        bounds = KingdomUi.folioBounds(width, height, 424, maxHeight);
        int left = bounds.left(), content = bounds.contentWidth();
        displayName = KingdomUi.ellipsize(font, page.name(), content);
        if (page.shop() != null) {
            shopPanel = new NpcShopPanel(font, page, bounds);
            choices.addAll(shopPanel.buttons(this::select, this::onClose, sentAt != 0));
            choices.forEach(this::addRenderableWidget);
            if (focused >= 0 && focused < choices.size() && choices.get(focused).active) {
                setInitialFocus(choices.get(focused));
            } else {
                choices.stream().filter(button -> button.active).findFirst().ifPresent(this::setInitialFocus);
            }
            return;
        }
        int count = page.choices().size() + 1;
        int stride = KingdomUi.CONTROL + KingdomUi.GAP;
        int actionsTop = bounds.footerY() - (Math.ceilDiv(count, 2) - 1) * stride;
        int bodyTop = bounds.y() + 54;
        itemX = left + 4;
        itemY = bodyTop + 5;
        int itemSpace = page.item().isEmpty() ? 0 : 30;
        int fieldSpace = page.inputLimit() > 0 ? stride : 0;
        int bodyHeight = Math.max(12, actionsTop - bodyTop - fieldSpace - 19);
        paragraph = addRenderableWidget(new Paragraph(font, left + itemSpace, bodyTop, content - itemSpace - 7,
                bodyHeight, page.body()));
        paragraph.offset(offset);

        if (page.inputLimit() > 0) {
            input = addRenderableWidget(KingdomUi.field(font, left, actionsTop - stride, content, page.inputHint()));
            input.setMaxLength(Math.clamp(page.inputLimit(), 1, RegnumNpc.DIALOGUE_LIMIT));
            input.setHint(page.inputHint());
            input.setValue(value);
            input.setEditable(sentAt == 0);
        }
        int column = (content - KingdomUi.GAP) / 2;
        for (int index = 0; index < count; index++) {
            boolean close = index == count - 1;
            int x = left + (index % 2) * (column + KingdomUi.GAP);
            int y = actionsTop + index / 2 * stride;
            int buttonWidth = close && index % 2 == 0 ? content : (index % 2 == 0 ? column : content - column - KingdomUi.GAP);
            int selection = index;
            Component label = close ? Component.translatable("npc.regnum-economy.close") : page.choices().get(index);
            var button = addRenderableWidget(new KingdomUi.RoyalButton(x, y, buttonWidth, label,
                    ignored -> { if (close) onClose(); else select(selection); }));
            if (font.width(label) > buttonWidth - 12) button.setTooltip(Tooltip.create(label));
            button.active = close || sentAt == 0;
            choices.add(button);
        }
        if (wasParagraph) setInitialFocus(paragraph);
        else if (wasInput && input != null) setInitialFocus(input);
        else if (focused >= 0 && focused < choices.size()) setInitialFocus(choices.get(focused));
        else setInitialFocus(input == null ? choices.getFirst() : input);
    }

    private void select(int choice) {
        if (sentAt != 0 || !ClientPlayNetworking.canSend(NpcPackets.Select.TYPE)) return;
        sentAt = Util.getMillis();
        feedback = Component.translatable("npc.regnum-economy.pending");
        int serverControls = page.shop() != null && page.shop().review() ? choices.size() : choices.size() - 1;
        for (int i = 0; i < serverControls; i++) choices.get(i).active = false;
        if (input != null) input.setEditable(false);
        ClientPlayNetworking.send(new NpcPackets.Select(page.token(), choice, input == null ? "" : input.getValue()));
    }

    @Override
    public void tick() {
        if (sentAt != 0 && !timedOut && Util.getMillis() - sentAt >= 8_000) {
            timedOut = true;
            feedback = Component.translatable("npc.regnum-economy.reopen");
        }
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override public Component getNarrationMessage() {
        var message = page.name().copy().append(". ").append(page.role()).append(". ");
        if (page.shop() != null) message.append(shopPanel.narration()).append(". ");
        return message.append(feedback);
    }

    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float delta) { }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, KingdomUi.BACKGROUND);
        KingdomUi.frame(graphics, font, page.name(), bounds.x(), bounds.y(), bounds.width(), bounds.height());
        if (shopPanel != null) {
            shopPanel.render(graphics, feedback);
        } else {
            KingdomUi.heading(graphics, font, page.role(), bounds.left(), bounds.y() + 38, bounds.contentWidth());
        }
        if (shopPanel == null && !page.item().isEmpty()) {
            KingdomUi.panel(graphics, itemX - 3, itemY - 3, itemX + 19, itemY + 19);
            graphics.renderItem(page.item(), itemX, itemY);
        }
        if (shopPanel == null && sentAt != 0) {
            int y = paragraph.getBottom() + 4;
            graphics.drawString(font, KingdomUi.ellipsize(font, feedback, bounds.contentWidth()),
                    bounds.left(), y, KingdomUi.GOLD, false);
        }
        super.render(graphics, mouseX, mouseY, delta);
        if (shopPanel != null) shopPanel.renderTooltip(graphics, mouseX, mouseY);
        if (shopPanel == null && !page.item().isEmpty() && mouseX >= itemX - 3 && mouseX < itemX + 19 && mouseY >= itemY - 3 && mouseY < itemY + 19) {
            graphics.renderTooltip(font, page.item(), mouseX, mouseY);
        } else if (!displayName.equals(page.name().getString()) && mouseX >= bounds.left() && mouseX <= bounds.right()
                && mouseY >= bounds.y() + 8 && mouseY <= bounds.y() + 26) {
            KingdomUi.tooltip(graphics, font, page.name(), mouseX, mouseY);
        }
    }

    private static final class Paragraph extends FittingMultiLineTextWidget {
        Paragraph(Font font, int x, int y, int width, int height, Component message) {
            super(x, y, width, height, message, font);
            setColor(KingdomUi.INK);
        }

        @Override protected void renderBackground(GuiGraphics graphics) {
            if (showingScrollBar() || isFocused()) {
                KingdomUi.panel(graphics, getX() - 1, getY() - 1, getRight() + 1, getBottom() + 1);
            }
        }

        double offset() { return scrollAmount(); }
        void offset(double saved) { setScrollAmount(saved); }
    }

}
