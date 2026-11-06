package com.xxsx.earthlore.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** Drawing helpers shared by the research table and lore tree screens (06 册 §2 palette: paper, walnut, brass). */
final class LoreGui {
    static final int PAPER = 0xFFE8DCC0;
    static final int PAPER_LIGHT = 0xFFF6EEDB;
    static final int PAPER_SHADE = 0xFFB9A889;
    static final int PAPER_SELECTED = 0xFFD9C79E;
    static final int WALNUT = 0xFF5B3A29;
    static final int BRASS = 0xFFB5893B;
    static final int INK = 0xFF3A2A1E;
    static final int INK_SOFT = 0xFF6B5A48;
    static final int OK = 0xFF3F9A4A;
    static final int BAD = 0xFFB8392E;
    static final int WARN = 0xFFC8761E;
    static final int UNKNOWN = 0xFF8A8A8A;
    static final int MISSING_FILL = 0xFF6E6E6E;

    private LoreGui() {
    }

    /** Paper panel with a walnut rim and a bevel, the size of the screen image. */
    static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x + 1, y, x + w - 1, y + h, WALNUT);
        g.fill(x, y + 1, x + w, y + h - 1, WALNUT);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, PAPER);
        g.fill(x + 1, y + 1, x + w - 2, y + 3, PAPER_LIGHT);
        g.fill(x + 1, y + 1, x + 3, y + h - 2, PAPER_LIGHT);
        g.fill(x + 3, y + h - 3, x + w - 1, y + h - 1, PAPER_SHADE);
        g.fill(x + w - 3, y + 3, x + w - 1, y + h - 1, PAPER_SHADE);
    }

    /** Sunken light area for lists. */
    static void inset(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, PAPER_SHADE);
        g.fill(x + 1, y + 1, x + w, y + h, PAPER_LIGHT);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFFF1E7CF);
    }

    /** Vanilla-style 18x18 slot frame; x and y are the slot position minus one. */
    static void slot(GuiGraphicsExtractor g, int x, int y) {
        g.fill(x, y, x + 18, y + 18, 0xFF8B8B8B);
        g.fill(x, y, x + 17, y + 1, 0xFF373737);
        g.fill(x, y, x + 1, y + 17, 0xFF373737);
        g.fill(x + 1, y + 17, x + 18, y + 18, 0xFFFFFFFF);
        g.fill(x + 17, y + 1, x + 18, y + 18, 0xFFFFFFFF);
    }

    /** 26x26 output frame with a brass rim; x and y are the slot position minus five. */
    static void bigSlot(GuiGraphicsExtractor g, int x, int y) {
        g.fill(x, y, x + 26, y + 26, BRASS);
        slot(g, x + 4, y + 4);
    }

    /** 6x6 status square: green when met, red when missing, gray when only the server can tell. */
    static void dot(GuiGraphicsExtractor g, int x, int y, Boolean ok) {
        int color = ok == null ? UNKNOWN : ok ? OK : BAD;
        g.fill(x, y, x + 6, y + 6, 0xFF2A1F16);
        g.fill(x + 1, y + 1, x + 5, y + 5, color);
    }

    /** 7x9 padlock. */
    static void lock(GuiGraphicsExtractor g, int x, int y, int color) {
        g.fill(x + 1, y, x + 6, y + 1, color);
        g.fill(x + 1, y, x + 2, y + 4, color);
        g.fill(x + 5, y, x + 6, y + 4, color);
        g.fill(x, y + 4, x + 7, y + 9, color);
        g.fill(x + 3, y + 5, x + 4, y + 7, 0xFF2A1F16);
    }

    static void dashedHorizontal(GuiGraphicsExtractor g, int x0, int x1, int y, int color) {
        int from = Math.min(x0, x1);
        int to = Math.max(x0, x1);
        for (int x = from; x < to; x += 5) {
            g.fill(x, y, Math.min(x + 3, to), y + 1, color);
        }
    }

    static void dashedVertical(GuiGraphicsExtractor g, int x, int y0, int y1, int color) {
        int from = Math.min(y0, y1);
        int to = Math.max(y0, y1);
        for (int y = from; y < to; y += 5) {
            g.fill(x, y, x + 1, Math.min(y + 3, to), color);
        }
    }

    static void outline(GuiGraphicsExtractor g, int x, int y, int w, int h, int color, boolean dashed) {
        if (!dashed) {
            g.outline(x, y, w, h, color);
            return;
        }
        dashedHorizontal(g, x, x + w, y, color);
        dashedHorizontal(g, x, x + w, y + h - 1, color);
        dashedVertical(g, x, y, y + h, color);
        dashedVertical(g, x + w - 1, y, y + h, color);
    }

    static Component fit(Font font, Component text, int width) {
        if (font.width(text) <= width) {
            return text;
        }
        String ellipsis = "...";
        return Component.literal(font.plainSubstrByWidth(text.getString(), Math.max(0, width - font.width(ellipsis))) + ellipsis)
                .withStyle(text.getStyle());
    }

    static boolean inside(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }
}

