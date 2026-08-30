import com.schecks.almin.client.WebPanelScreen;

import java.lang.reflect.Method;

/**
 * The Web tab's geometry. The screen itself can't be rendered outside the game,
 * so the layout is plain arithmetic that can be — this checks it stays on
 * screen and clear of the tab strip at every window size Minecraft allows.
 */
public class WebScreenTests {
    static int fail = 0;
    static void ck(String w, boolean ok, String d) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + w + (ok ? "" : "  -> " + d));
        if (!ok) fail++;
    }

    static Method cw, cx, rowY, bottom, navY;

    public static void main(String[] a) throws Exception {
        cw = m("contentWidth", int.class);
        cx = m("contentX", int.class);
        rowY = m("rowY", int.class);
        bottom = m("contentBottom");
        navY = m("navY", int.class);

        // Minecraft clamps GUI scale so the logical size never drops below
        // 320x240; the smaller entries here are margin, not a real case.
        for (int w : new int[]{1920, 1280, 854, 640, 480, 400, 320, 260, 240}) {
            int width = i(cw, w), x = i(cx, w);
            ck("content fits a " + w + "px-wide screen",
                x >= 0 && x + width <= w, "x=" + x + " w=" + width);

            // Every row's own arithmetic has to stay positive and inside it.
            int quarter = (width - 9) / 4;
            int lastRun = width - (quarter + 3) * 3;
            ck("  run controls fit at " + w, quarter >= 20 && lastRun >= 20,
                "quarter=" + quarter + " last=" + lastRun);

            int half = (width - 3) / 2;
            ck("  toggles fit at " + w, half >= 20 && width - half - 3 >= 20,
                "half=" + half);

            int bindW = width - 42 * 2 - 54 - 9;
            ck("  settings row fits at " + w, bindW >= 20, "bind=" + bindW);

            int setW = 96;
            ck("  password row fits at " + w, width - setW - 3 >= 20,
                "pw=" + (width - setW - 3));
        }

        int contentBottom = i(bottom);
        for (int h : new int[]{240, 260, 300, 360, 480, 720, 1080}) {
            ck("content clears the tab strip at " + h + "px tall",
                contentBottom < i(navY, h), contentBottom + " vs nav " + i(navY, h));
        }

        // Rows must not overlap each other: each is 20 tall on a 24 pitch.
        boolean stacked = true;
        for (int r = 1; r < 5; r++) stacked &= i(rowY, r) - i(rowY, r - 1) >= 20;
        ck("rows don't overlap", stacked, "");
        ck("first row clears the status lines", i(rowY, 0) >= 42, String.valueOf(i(rowY, 0)));

        System.out.println(fail == 0 ? "\nWEB-SCREEN TESTS PASSED" : "\n" + fail + " FAILED");
        System.exit(fail == 0 ? 0 : 1);
    }

    static Method m(String name, Class<?>... args) throws Exception {
        Method x = WebPanelScreen.class.getDeclaredMethod(name, args);
        x.setAccessible(true);
        return x;
    }
    static int i(Method x, Object... args) throws Exception {
        return (int) x.invoke(null, args);
    }
}
