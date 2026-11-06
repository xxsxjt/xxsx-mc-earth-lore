package com.xxsx.earthlore.client;

import com.xxsx.earthlore.data.Discipline;
import java.util.Set;

/** Color helpers for 06 册 §1.1: dark and light variants and readable text on a filled box. */
final class LoreColors {
    /** AST, FUT and XUA are too dark on dark backgrounds; they use their highlight there. */
    private static final Set<String> DARK = Set.of("AST", "FUT", "XUA");

    private LoreColors() {
    }

    static int argb(int rgb) {
        return 0xFF000000 | (rgb & 0xFFFFFF);
    }

    static int highlight(int rgb) {
        int r = ((rgb >> 16) & 0xFF) + 255 >> 1;
        int g = ((rgb >> 8) & 0xFF) + 255 >> 1;
        int b = (rgb & 0xFF) + 255 >> 1;
        return (r << 16) | (g << 8) | b;
    }

    static int shade(int rgb) {
        int r = (int) (((rgb >> 16) & 0xFF) * 0.6);
        int g = (int) (((rgb >> 8) & 0xFF) * 0.6);
        int b = (int) ((rgb & 0xFF) * 0.6);
        return (r << 16) | (g << 8) | b;
    }

    /** Text color for tooltips and dark screens. */
    static int onDark(Discipline discipline) {
        return DARK.contains(discipline.code()) ? highlight(discipline.color()) : discipline.color();
    }

    /** Black or white, whichever reads better on the given fill. */
    static int textOn(int rgb) {
        double luminance = 0.2126 * ((rgb >> 16) & 0xFF) + 0.7152 * ((rgb >> 8) & 0xFF) + 0.0722 * (rgb & 0xFF);
        return luminance > 140 ? 0xFF202020 : 0xFFFFFFFF;
    }
}
