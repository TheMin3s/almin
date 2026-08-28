package com.schecks.almin.client;

import net.minecraft.client.gui.Font;

import java.util.function.ToIntFunction;

/**
 * Text that stays inside the box it was given.
 *
 * <p>Almin's screens show values it does not control — world names, block
 * names, chat lines, file paths, settings someone typed. Drawn straight, a long
 * one runs off the edge of the window or underneath the value next to it, and
 * whether that happens depends on the player's GUI scale rather than on
 * anything the server did. So every variable string goes through here first.
 *
 * <p>Truncation is by measured width, not character count: proportional fonts
 * make "WWWW" three times the width of "iiii", and a character budget would be
 * wrong for one of them whichever number was picked.
 */
public final class Text {
    private static final String ELLIPSIS = "…";

    private Text() {}

    /**
     * {@code text}, shortened with an ellipsis until it fits {@code maxWidth}
     * pixels. Returns "" when there is no room for anything legible.
     */
    public static String fit(Font font, String text, int maxWidth) {
        return fit(font::width, text, maxWidth);
    }

    /**
     * The same, measured by any width function.
     *
     * <p>Split out because {@code Font} needs a running game and this search is
     * exactly the kind of arithmetic that is wrong by one until something
     * checks it.
     */
    static String fit(ToIntFunction<String> width, String text, int maxWidth) {
        if (text == null || text.isEmpty()) return "";
        if (maxWidth <= 0) return "";
        if (width.applyAsInt(text) <= maxWidth) return text;
        if (width.applyAsInt(ELLIPSIS) > maxWidth) return "";

        // Binary search the longest prefix that still fits with the ellipsis.
        // Linear trimming is fine for a label and awful for a console line.
        int lo = 0;
        int hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (width.applyAsInt(text.substring(0, mid) + ELLIPSIS) <= maxWidth) lo = mid;
            else hi = mid - 1;
        }
        return lo == 0 ? ELLIPSIS : text.substring(0, lo) + ELLIPSIS;
    }

    /**
     * Splits a label/value pair into what each may occupy.
     *
     * <p>The value gets what it needs up to {@code valueShare} of the space,
     * because it is usually the shorter and always the more specific of the
     * two; the label takes the rest. This is what stops a long setting name
     * from being drawn underneath its own value.
     *
     * @return {@code {labelWidth, valueWidth}}
     */
    public static int[] split(Font font, String label, String value, int total, float valueShare) {
        return split(font::width, label, value, total, valueShare);
    }

    static int[] split(ToIntFunction<String> width, String label, String value,
                       int total, float valueShare) {
        int gap = 8;
        int valueWanted = width.applyAsInt(value);
        int valueMax = Math.max(0, (int) (total * valueShare));
        int valueWidth = Math.min(valueWanted, valueMax);
        int labelWidth = Math.max(0, total - valueWidth - gap);
        return new int[]{labelWidth, valueWidth};
    }
}
