package dev.regnum.economy.client;

import dev.sakhno.regnum.nations.client.ui.KingdomUi;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** The Nations binding at 1:1 logical pixels, with the trade ledger's shorter header. */
final class TradeFolio {
    static final int BRASS = 0xffa78e61;
    static final int LIGHT_BRASS = 0xffd3bc89;

    private static final int LEATHER = 0xff101714;
    private static final int TILE = 64;
    private static final int CORNER = 16;
    private static final int EDGE = 8;
    private static final ResourceLocation PARCHMENT =
            ResourceLocation.fromNamespaceAndPath("regnum-nations", "textures/gui/atlas/folio_tile.png");
    private static final ResourceLocation ORNAMENTS =
            ResourceLocation.fromNamespaceAndPath("regnum-nations", "textures/gui/atlas/ornaments.png");

    private TradeFolio() {}

    static void binding(GuiGraphics graphics, int x, int y, int width, int height) {
        int right = x + width;
        int bottom = y + height;
        graphics.fill(x + 4, y, right - 4, bottom, LEATHER);
        graphics.fill(x, y + 4, right, bottom - 4, LEATHER);
        for (int row = y + 4; row < bottom - 4; row += TILE) {
            for (int column = x + 4; column < right - 4; column += TILE) {
                int tileWidth = Math.min(TILE, right - 4 - column);
                int tileHeight = Math.min(TILE, bottom - 4 - row);
                graphics.blit(PARCHMENT, column, row, 0f, 0f, tileWidth, tileHeight, TILE, TILE);
            }
        }

        for (int column = x + CORNER; column < right - CORNER; column += CORNER) {
            int length = Math.min(CORNER, right - CORNER - column);
            ornament(graphics, column, y, 0, 16, length, EDGE);
            ornament(graphics, column, bottom - EDGE, 0, 24, length, EDGE);
        }
        for (int row = y + CORNER; row < bottom - CORNER; row += CORNER) {
            int length = Math.min(CORNER, bottom - CORNER - row);
            ornament(graphics, x, row, 16, 16, EDGE, length);
            ornament(graphics, right - EDGE, row, 24, 16, EDGE, length);
        }
        ornament(graphics, x, y, 0, 0, CORNER, CORNER);
        ornament(graphics, right - CORNER, y, 16, 0, CORNER, CORNER);
        ornament(graphics, x, bottom - CORNER, 32, 0, CORNER, CORNER);
        ornament(graphics, right - CORNER, bottom - CORNER, 48, 0, CORNER, CORNER);
    }

    static void rule(GuiGraphics graphics, int left, int right, int y, int accent) {
        graphics.fill(left, y, right, y + 1, accent);
        graphics.fill(left, y + 1, right, y + 2, KingdomUi.RECESS);
    }

    static void headerRule(GuiGraphics graphics, int left, int right, int y) {
        rule(graphics, left, right, y, 0xff77715a);
        ornament(graphics, (left + right) / 2 - 16, y - 4, 32, 16, 32, 8);
    }

    private static void ornament(GuiGraphics graphics, int x, int y, int u, int v, int width, int height) {
        graphics.blit(ORNAMENTS, x, y, (float) u, (float) v, width, height, 256, 128);
    }
}
