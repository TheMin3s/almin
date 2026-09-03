package com.schecks.almin;

import java.util.regex.Pattern;

/**
 * Taking the grid references back out of a sentence.
 *
 * <h3>Why this is needed at all</h3>
 * Almost everywhere Almin shows a position, the panel builds the string and
 * can simply not build it. Free text written by a language model is the
 * exception: the model is handed the log with coordinates in it, because that
 * is what lets it say two things happened in the same place, and it will
 * quite reasonably repeat them back. A summary is also cached and shared by
 * everyone who asks for that period, so it cannot be generated differently
 * for an account that is not shown coordinates.
 *
 * <p>So a sentence on its way to such an account has the numbers taken out of
 * it here. This is the only place in Almin that edits a model's words, and it
 * does so by replacing rather than deleting: a reader sees that something was
 * removed and what kind of thing it was, instead of a sentence that has
 * quietly stopped making sense.
 *
 * <h3>What it will and will not catch</h3>
 * It matches the shapes coordinates are actually written in — the
 * comma-separated triple the prompt uses, and the {@code X 1 / Y 2 / Z 3}
 * form the panel writes — and leaves everything else alone. It is not a
 * guarantee: a model that spells a position out in words defeats it, as would
 * any scrubber. The account-level restriction is a narrowing of what somebody
 * is shown, and this is the part of it that has to work on prose.
 */
public final class Coords {

    /** What is put in a sentence where a position was. */
    private static final String INSTEAD = "somewhere";

    /** {@code 120,64,-77} and {@code 120, 64, -77}, with or without decimals. */
    private static final Pattern TRIPLE = Pattern.compile(
        "-?\\d+(?:\\.\\d+)?\\s*,\\s*-?\\d+(?:\\.\\d+)?\\s*,\\s*-?\\d+(?:\\.\\d+)?");

    /** {@code X 120 / Y 64 / Z -77}, in any case, with slashes or commas. */
    private static final Pattern NAMED = Pattern.compile(
        "(?i)x\\s*-?\\d+(?:\\.\\d+)?\\s*[/,]\\s*y\\s*-?\\d+(?:\\.\\d+)?\\s*[/,]\\s*z\\s*-?\\d+(?:\\.\\d+)?");

    /** A lone {@code x=120} or {@code x: 120} pair, which models also produce. */
    private static final Pattern SINGLE = Pattern.compile(
        "(?i)\\b([xyz])\\s*[=:]\\s*-?\\d+(?:\\.\\d+)?");

    private Coords() {}

    /** The text with anything shaped like a position replaced. */
    public static String scrub(String text) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        String out = NAMED.matcher(text).replaceAll(INSTEAD);
        out = TRIPLE.matcher(out).replaceAll(INSTEAD);
        out = SINGLE.matcher(out).replaceAll(INSTEAD);
        return out;
    }

    /** {@link #scrub} only when this account is not shown coordinates. */
    public static String scrubFor(Accounts.Account who, String text) {
        return who != null && who.coordsHidden() ? scrub(text) : text;
    }
}
