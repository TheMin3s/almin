import com.schecks.almin.client.AlminNav;
import net.minecraft.client.gui.components.Button;
import java.util.List;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** The nav strip's contents and the dashboard-breadcrumb state machine. */
public class NavTests {
    static int fail = 0;
    static void ck(String w, boolean ok, String d) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + w + (ok ? "" : "  -> " + d));
        if (!ok) fail++;
    }
    static List<String> labels(List<Button> bs) {
        return bs.stream().map(b -> b.getMessage().getString()).toList();
    }

    /** 26.2's Button.onPress takes an input event; call the handler directly. */
    static void press(Button b) throws Exception {
        Field f = Button.class.getDeclaredField("onPress");
        f.setAccessible(true);
        Object handler = f.get(b);
        Method m = handler.getClass().getMethod("onPress", Button.class);
        m.setAccessible(true);
        m.invoke(handler, b);
    }

    static Button named(List<Button> bs, String label) {
        return bs.stream().filter(b -> b.getMessage().getString().equals(label)).findFirst().orElseThrow();
    }

    public static void main(String[] a) throws Exception {
        // ---- breadcrumb state ----
        AlminNav.leftAdminUi();
        ck("starts without a Back entry", !AlminNav.cameFromDashboard(), "");
        AlminNav.launchedFromDashboard();
        ck("dashboard launch sets the breadcrumb", AlminNav.cameFromDashboard(), "");
        AlminNav.leftAdminUi();
        ck("closing to the game clears it", !AlminNav.cameFromDashboard(), "");

        // ---- tabs respect the trusted flag ----
        AlminNav.setTrusted(false);
        AlminNav.leftAdminUi();
        var plain = labels(AlminNav.bar(800, 100, "", c -> {}));
        ck("untrusted: no Console tab", !plain.contains("Console"), plain.toString());
        ck("untrusted: no Files tab", !plain.contains("Files"), plain.toString());
        ck("untrusted: still gets Shared/Mods/Config",
            plain.contains("Shared") && plain.contains("Mods") && plain.contains("Config"), plain.toString());
        ck("untrusted: no Back when not from dashboard",
            plain.stream().noneMatch(s -> s.contains("Dashboard")), plain.toString());

        AlminNav.setTrusted(true);
        var trusted = labels(AlminNav.bar(800, 100, "", c -> {}));
        ck("trusted: Console and Files appear",
            trusted.contains("Console") && trusted.contains("Files"), trusted.toString());

        AlminNav.launchedFromDashboard();
        var withBack = labels(AlminNav.bar(800, 100, "Console", c -> {}));
        ck("Back appears once launched from dashboard",
            withBack.stream().anyMatch(s -> s.contains("Dashboard")), withBack.toString());
        ck("Back is first", withBack.get(0).contains("Dashboard"), withBack.toString());

        // ---- current tab is shown but not clickable ----
        var bs = AlminNav.bar(800, 100, "Console", c -> {});
        Button console = bs.stream().filter(b -> b.getMessage().getString().equals("Console")).findFirst().orElse(null);
        Button files   = bs.stream().filter(b -> b.getMessage().getString().equals("Files")).findFirst().orElse(null);
        ck("current tab still drawn", console != null, "");
        ck("current tab is disabled", console != null && !console.active, "");
        ck("other tabs stay enabled", files != null && files.active, "");

        // ---- clicking dispatches the right command ----
        StringBuilder sent = new StringBuilder();
        var bs2 = AlminNav.bar(800, 100, "Console", sent::append);
        press(named(bs2, "Files"));
        ck("Files tab issues 'almin op dir'", sent.toString().equals("almin op dir"), sent.toString());

        sent.setLength(0);
        var bs3 = AlminNav.bar(800, 100, "Console", sent::append);
        press(named(bs3, "Console"));
        ck("current tab dispatches nothing", sent.length() == 0, sent.toString());

        sent.setLength(0);
        var bs4 = AlminNav.bar(800, 100, "", sent::append);
        press(bs4.get(0));
        ck("Back issues plain 'almin'", sent.toString().equals("almin"), sent.toString());

        // ---- layout stays on screen at a small width ----
        for (int w : new int[]{1920, 854, 640, 480, 400, 320, 240}) {
            var bs5 = AlminNav.bar(w, 100, "", c -> {});
            final int width = w;
            boolean onScreen = bs5.stream().allMatch(b -> b.getX() >= 0 && b.getX() + b.getWidth() <= width);
            boolean legible = bs5.stream().allMatch(b -> b.getWidth() >= 44);
            ck("fits a " + w + "px screen (" + bs5.size() + " tabs)", onScreen && legible,
                bs5.stream().map(b -> b.getX() + "+" + b.getWidth()).toList().toString());
        }

        System.out.println(fail == 0 ? "\nNAV TESTS PASSED" : "\n" + fail + " FAILED");
        System.exit(fail == 0 ? 0 : 1);
    }
}
