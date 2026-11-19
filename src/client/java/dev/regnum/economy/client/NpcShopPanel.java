package dev.regnum.economy.client;

import dev.regnum.economy.npc.NpcPackets;
import dev.regnum.economy.npc.NpcPackets.ShopAction;
import dev.regnum.economy.npc.NpcPackets.ShopView;
import dev.sakhno.regnum.nations.client.ui.KingdomUi;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

final class NpcShopPanel {
    private static final List<ShopAction> LISTING_ACTIONS = List.of(ShopAction.FIRST, ShopAction.SECOND, ShopAction.THIRD);
    private static final List<ShopAction> NAVIGATION = List.of(ShopAction.BACK, ShopAction.PREVIOUS, ShopAction.NEXT);
    private static final int LISTING_HEIGHT = 25;

    private final Font font;
    private final NpcPackets.Page page;
    private final NpcPackets.Shop shop;
    private final KingdomUi.FolioBounds bounds;
    private final int top;
    private final int listWidth;
    private final int detailX;
    private final List<ClippedText> clipped = new ArrayList<>();

    NpcShopPanel(Font font, NpcPackets.Page page, KingdomUi.FolioBounds bounds) {
        this.font = font;
        this.page = page;
        this.shop = page.shop();
        this.bounds = bounds;
        top = bounds.y() + 52;
        listWidth = bounds.contentWidth() * 2 / 5;
        detailX = bounds.left() + listWidth + KingdomUi.GAP;
    }

    List<KingdomUi.RoyalButton> buttons(IntConsumer select, Runnable close, boolean pending) {
        List<KingdomUi.RoyalButton> buttons = new ArrayList<>();
        int left = bounds.left();
        int footer = bounds.footerY();
        if (shop.review()) {
            int column = (bounds.contentWidth() - KingdomUi.GAP) / 2;
            buttons.add(button(ShopAction.CANCEL, left, footer, column, npc("cancel"), select, pending));
            buttons.add(button(ShopAction.CONFIRM, bounds.right() - column, footer, column,
                    shopText(shop.view() == ShopView.PURCHASE ? "confirm_buy" : "confirm_sell"), select, pending));
            return buttons;
        }

        for (int i = 0; i < shop.listings().size(); i++) {
            int y = top + 17 + i * LISTING_HEIGHT;
            var item = shop.listings().get(i);
            var button = button(LISTING_ACTIONS.get(i), left + 25, y, listWidth - 27, item.getHoverName(), select, pending);
            button.selected(i == shop.selected());
            buttons.add(button);
        }
        if (!shop.listings().isEmpty()) {
            int actionWidth = Math.min(102, (bounds.right() - detailX) / 2);
            int x = bounds.right() - actionWidth - 5;
            if (shop.view() == ShopView.CATALOGUE) {
                buttons.add(button(ShopAction.BUY, x, top + 42, actionWidth, shopText("buy_lot"), select, pending));
            }
            buttons.add(button(ShopAction.SELL, x, top + saleOffset(), actionWidth, shopText("sell_lot"), select, pending));
        }

        int column = (bounds.contentWidth() - KingdomUi.GAP * 3) / 4;
        for (int i = 0; i < NAVIGATION.size(); i++) {
            ShopAction action = NAVIGATION.get(i);
            String key = switch (action) {
                case BACK -> "dialogue";
                case PREVIOUS -> "previous";
                default -> "next";
            };
            if (shop.pages() > 1 || action == ShopAction.BACK) {
                buttons.add(button(action, left + i * (column + KingdomUi.GAP), footer, column, shopText(key), select, pending));
            }
        }
        buttons.add(new KingdomUi.RoyalButton(bounds.right() - column, footer, column, npc("close"), ignored -> close.run()));
        return buttons;
    }

    private KingdomUi.RoyalButton button(ShopAction action, int x, int y, int width, Component fallback,
                                         IntConsumer select, boolean pending) {
        int index = shop.actions().indexOf(action);
        Component label = index >= 0 ? page.choices().get(index) : fallback;
        var style = switch (action) {
            case BUY, CONFIRM -> KingdomUi.ButtonStyle.PRIMARY;
            default -> KingdomUi.ButtonStyle.NORMAL;
        };
        var button = new KingdomUi.RoyalButton(x, y, width, label, ignored -> select.accept(index), style);
        button.active = index >= 0 && !pending;
        Component issue = action == ShopAction.BUY ? shop.buyIssue() : action == ShopAction.SELL ? shop.sellIssue() : Component.empty();
        if (!issue.equals(Component.empty())) button.setTooltip(Tooltip.create(issue));
        return button;
    }

    void render(GuiGraphics graphics, Component pending) {
        clipped.clear();
        int left = bounds.left();
        int right = bounds.right();
        int header = bounds.y() + 37;
        String heading = switch (shop.view()) {
            case BUYER -> "buyer";
            case CATALOGUE -> "catalogue";
            case PURCHASE -> "purchase";
            case SALE -> "sale";
        };
        line(graphics, shopText(heading), left, header, listWidth, KingdomUi.GOLD);
        Component wallet = shopText("wallet", Long.toString(shop.wallet()));
        int walletWidth = Math.min(font.width(wallet), bounds.contentWidth() - listWidth - KingdomUi.GAP);
        line(graphics, wallet, right - walletWidth, header, walletWidth, KingdomUi.INK);

        if (shop.review()) renderReview(graphics);
        else if (!shop.listings().isEmpty()) renderCatalogue(graphics);

        Component message = pending.equals(Component.empty()) ? page.body() : pending;
        if (!message.equals(Component.empty())) {
            int y = shop.listings().isEmpty() && !shop.review() ? top + 8 : bounds.footerY() - 26;
            var lines = font.split(message, bounds.contentWidth());
            for (int i = 0; i < Math.min(2, lines.size()); i++) {
                graphics.drawString(font, lines.get(i), left, y + 10 * i, KingdomUi.GOLD, false);
            }
            if (lines.size() > 2) clipped.add(new ClippedText(message, left, y, bounds.contentWidth(), 20));
        }
    }

    private void renderCatalogue(GuiGraphics graphics) {
        int left = bounds.left();
        int right = bounds.right();
        KingdomUi.panel(graphics, left, top, left + listWidth, top + 110);
        KingdomUi.panel(graphics, detailX, top, right, top + 110);
        line(graphics, shopText("page", shop.page(), shop.pages()), left + 6, top + 5, listWidth - 12, KingdomUi.MUTED);
        for (int i = 0; i < shop.listings().size(); i++) {
            int y = top + 17 + i * LISTING_HEIGHT;
            icon(graphics, shop.listings().get(i), left + 3, y);
        }

        int x = detailX + 6;
        int width = right - x - 6;
        line(graphics, page.item().getHoverName(), x, top + 6, width, KingdomUi.INK);
        line(graphics, shopText("lot_stock", shop.quantity(), shop.stock()), x, top + 18, width, KingdomUi.MUTED);
        line(graphics, shopText("matching", shop.matching()), x, top + 29, width, KingdomUi.MUTED);
        if (shop.view() == ShopView.CATALOGUE) {
            renderTerms(graphics, x, width, top + 42, "cost", shop.ask(), shop.buyIssue(), KingdomUi.GOLD);
        }
        renderTerms(graphics, x, width, top + saleOffset(), "payout", shop.bid(), shop.sellIssue(), KingdomUi.INK);
    }

    private void renderTerms(GuiGraphics graphics, int x, int width, int y, String label, long price, Component issue, int accent) {
        graphics.fill(x, y - 3, x + width, y - 2, KingdomUi.BORDER);
        line(graphics, shopText(label), x, y + 1, width / 2 - 4, KingdomUi.MUTED);
        line(graphics, price == 0 ? shopText("disabled") : npc("merchant.copper", Long.toString(price)),
                x, y + 11, width / 2 - 4, accent);
        if (price > 0 && !issue.equals(Component.empty())) {
            line(graphics, issue, x, y + 22, width, KingdomUi.ERROR);
        }
    }

    private int saleOffset() { return shop.view() == ShopView.BUYER ? 53 : 76; }

    Component narration() {
        var message = shopText("wallet", Long.toString(shop.wallet())).copy().append(". ");
        if (!page.item().isEmpty()) {
            message.append(page.item().getHoverName()).append(". ");
            if (shop.review()) {
                boolean purchase = shop.view() == ShopView.PURCHASE;
                message.append(shopText("quantity", shop.quantity())).append(". ")
                        .append(shopText(purchase ? "total_cost" : "total_payout", Long.toString(purchase ? shop.ask() : shop.bid())));
            } else {
                message.append(shopText("lot_stock", shop.quantity(), shop.stock())).append(". ")
                        .append(shopText("matching", shop.matching())).append(". ");
                if (shop.view() == ShopView.CATALOGUE) narrateTerms(message, "cost", shop.ask(), shop.buyIssue());
                narrateTerms(message, "payout", shop.bid(), shop.sellIssue());
            }
        }
        return message.append(". ").append(page.body());
    }

    private void narrateTerms(net.minecraft.network.chat.MutableComponent message, String label, long price, Component issue) {
        message.append(shopText(label)).append(" ")
                .append(price == 0 ? shopText("disabled") : npc("merchant.copper", Long.toString(price)))
                .append(". ").append(issue).append(". ");
    }

    private void renderReview(GuiGraphics graphics) {
        int left = bounds.left();
        int right = bounds.right();
        int x = left + 39;
        int width = right - x - 10;
        boolean purchase = shop.view() == ShopView.PURCHASE;
        KingdomUi.panel(graphics, left, top, right, top + 92);
        icon(graphics, page.item(), left + 10, top + 10);
        line(graphics, page.item().getHoverName(), x, top + 15, width, KingdomUi.INK);
        line(graphics, shopText("quantity", shop.quantity()), left + 10, top + 42, right - left - 20, KingdomUi.INK);
        graphics.fill(left + 10, top + 57, right - 10, top + 58, KingdomUi.BORDER);
        line(graphics, shopText(purchase ? "total_cost" : "total_payout", Long.toString(purchase ? shop.ask() : shop.bid())),
                left + 10, top + 68, right - left - 20, KingdomUi.GOLD);
    }

    private void icon(GuiGraphics graphics, ItemStack item, int x, int y) {
        graphics.fill(x, y, x + 20, y + 20, KingdomUi.BORDER);
        graphics.fill(x + 1, y + 1, x + 19, y + 19, KingdomUi.RECESS);
        graphics.renderItem(item, x + 2, y + 2);
    }

    private void line(GuiGraphics graphics, Component text, int x, int y, int width, int color) {
        graphics.drawString(font, KingdomUi.ellipsize(font, text, width), x, y, color, false);
        if (font.width(text) > width) clipped.add(new ClippedText(text, x, y, width, font.lineHeight));
    }

    void renderTooltip(GuiGraphics graphics, int x, int y) {
        int iconX = bounds.left() + (shop.review() ? 10 : 3);
        if (x >= iconX && x < iconX + 20) {
            if (shop.review() && y >= top + 10 && y < top + 30) {
                graphics.renderTooltip(font, page.item(), x, y);
                return;
            }
            for (int i = 0; i < shop.listings().size(); i++) {
                int iconY = top + 17 + i * LISTING_HEIGHT;
                if (y >= iconY && y < iconY + 20) {
                    graphics.renderTooltip(font, shop.listings().get(i), x, y);
                    return;
                }
            }
        }
        for (var line : clipped) {
            if (x >= line.x && x < line.x + line.width && y >= line.y && y < line.y + line.height) {
                KingdomUi.tooltip(graphics, font, line.text, x, y);
                return;
            }
        }
    }

    private static Component npc(String key, Object... args) { return Component.translatable("npc.regnum-economy." + key, args); }
    private static Component shopText(String key, Object... args) { return npc("shop." + key, args); }
    private record ClippedText(Component text, int x, int y, int width, int height) { }
}
