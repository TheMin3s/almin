import com.schecks.almin.client.Text;

import java.lang.reflect.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Text that has to stay inside the box it was given.
 *
 * Minecraft's Font needs a running game, so the measuring is driven through a
 * stand-in that charges a fixed width per character — enough to prove the
 * arithmetic, which is where the overflow was.
 */
public class TextFitTests {
    static int fail = 0;
    static void ck(String w, boolean ok, String d) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + w + (ok ? "" : "  -> " + d));
        if (!ok) fail++;
    }

    /** 6px per char, the rough width of Minecraft's default font. */
    static final int PX = 6;

    /** Measures like the real font would, without needing one. */
    static final java.util.function.ToIntFunction<String> WIDTH = s -> s.length() * PX;

    static Method fit, split;

    public static void main(String[] a) throws Exception {
        fit = Text.class.getDeclaredMethod("fit",
            java.util.function.ToIntFunction.class, String.class, int.class);
        fit.setAccessible(true);
        split = Text.class.getDeclaredMethod("split",
            java.util.function.ToIntFunction.class, String.class, String.class,
            int.class, float.class);
        split.setAccessible(true);

        basics();
        boundaries();
        applied();

        System.out.println(fail == 0 ? "\nTEXT-FIT TESTS PASSED" : "\n" + fail + " FAILED");
        System.exit(fail == 0 ? 0 : 1);
    }

    static String fit(String s, int w) throws Exception {
        return (String) fit.invoke(null, WIDTH, s, w);
    }
    static int[] split(String label, String value, int total, float share) throws Exception {
        return (int[]) split.invoke(null, WIDTH, label, value, total, share);
    }

    static void basics() throws Exception {
        // Font.width on a hollow instance would NPE, so these go through the
        // real code path only where it does not measure.
        ck("null is empty", fit(null, 100).isEmpty(), "");
        ck("empty is empty", fit("", 100).isEmpty(), "");
        ck("no room means nothing", fit("hello", 0).isEmpty(), "");
        ck("negative room means nothing", fit("hello", -5).isEmpty(), "");
    }

    /** The search itself, at every edge it could be wrong by one. */
    static void boundaries() throws Exception {
        ck("text that already fits is untouched", fit("hello", 30).equals("hello"),
            fit("hello", 30));
        ck("an exact fit is untouched", fit("hello", 5 * PX).equals("hello"), fit("hello", 5 * PX));
        ck("one pixel short truncates", !fit("hello", 5 * PX - 1).equals("hello"),
            fit("hello", 5 * PX - 1));

        // "hello" is 30px; at 24px only "hel" + the ellipsis (4 chars, 24px) fits.
        ck("it truncates to the longest prefix that fits",
            fit("hello", 4 * PX).equals("hel…"), fit("hello", 4 * PX));
        ck("the result never exceeds the budget",
            WIDTH.applyAsInt(fit("a-very-long-label-indeed", 60)) <= 60,
            fit("a-very-long-label-indeed", 60));

        ck("room for only the ellipsis gives the ellipsis",
            fit("hello", PX).equals("…"), fit("hello", PX));
        ck("less room than the ellipsis gives nothing",
            fit("hello", PX - 1).isEmpty(), fit("hello", PX - 1));

        // Long input is where the linear version was slow and the search matters.
        String huge = "x".repeat(20000);
        String cut = fit(huge, 600);
        ck("a very long line is cut to fit", WIDTH.applyAsInt(cut) <= 600, String.valueOf(cut.length()));
        ck("...and keeps as much as it can", cut.length() == 100, String.valueOf(cut.length()));

        // Every width from nothing to plenty must produce something that fits.
        boolean always = true;
        for (int w = 0; w <= 200; w++) {
            if (WIDTH.applyAsInt(fit("the quick brown fox", w)) > w) { always = false; break; }
        }
        ck("no width produces something too wide", always, "");

        // ---- split ----
        int[] room = split("label", "value", 120, 0.5f);
        ck("split gives the value what it asks for", room[1] == 5 * PX, String.valueOf(room[1]));
        ck("...and the label the rest", room[0] == 120 - 5 * PX - 8, String.valueOf(room[0]));
        int[] greedy = split("l", "a-very-long-value-here", 100, 0.5f);
        ck("a greedy value is capped at its share", greedy[1] <= 50, String.valueOf(greedy[1]));
        ck("...leaving the label something", greedy[0] > 0, String.valueOf(greedy[0]));
        int[] tiny = split("label", "value", 10, 0.5f);
        ck("neither half goes negative when there is no room",
            tiny[0] >= 0 && tiny[1] >= 0, tiny[0] + "," + tiny[1]);
    }

    /** Every screen that draws a value it does not control must clip it. */
    static void applied() throws Exception {
        record Site(String file, List<String> mustClip) {}
        List<Site> sites = List.of(
            new Site("DashboardScreen.java", List.of("row.label()", "row.value()")),
            new Site("ActivityScreen.java", List.of("row.player()", "row.detail()")),
            new Site("DirBrowserScreen.java", List.of("label")),
            new Site("ConsoleScreen.java", List.of("line")),
            new Site("PanelScreen.java", List.of("row.label()", "row.value()")),
            new Site("WebPanelScreen.java", List.of("state.url()", "state.lastError()")));

        for (Site s : sites) {
            String src = Files.readString(
                Path.of("src/main/java/com/schecks/almin/client/" + s.file()));
            ck(s.file() + " uses the shared clipper", src.contains("Text.fit("), "no Text.fit call");
            for (String what : s.mustClip()) {
                // The value must appear inside a Text.fit(...) call somewhere.
                boolean clipped = false;
                int i = 0;
                while ((i = src.indexOf("Text.fit(", i)) >= 0) {
                    int end = src.indexOf(')', i);
                    if (end > 0 && src.substring(i, Math.min(end + 40, src.length())).contains(what)) {
                        clipped = true;
                        break;
                    }
                    i += 9;
                }
                ck("  " + s.file() + " clips " + what, clipped, "drawn unclipped");
            }
        }

        // And nobody should be hand-rolling their own trimmer any more.
        for (String f : new String[]{"ActivityScreen.java", "WebPanelScreen.java"}) {
            String src = Files.readString(Path.of("src/main/java/com/schecks/almin/client/" + f));
            ck(f + " has no private trimmer left",
                !src.contains("private String trim(") && !src.contains("private static String clip("), "");
        }
    }
}
