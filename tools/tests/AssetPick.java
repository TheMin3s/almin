import com.schecks.almin.UpdateChecker;
import java.lang.reflect.Method;

/** Checks all three updater artifacts select only themselves from one release. */
public class AssetPick {
    static int fail = 0;
    static Method parse;

    static void ck(String what, boolean ok, String detail) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what
            + (ok ? "" : "  -> " + detail));
        if (!ok) fail++;
    }

    static String body(String assets) {
        return "{\"tag_name\":\"v2.40.0\",\"assets\":[" + assets + "]}";
    }
    static String asset(String name) {
        return "{\"name\":\"" + name
            + "\",\"browser_download_url\":\"https://example.invalid/" + name + "\"}";
    }

    public static void main(String[] args) throws Exception {
        parse = UpdateChecker.class.getDeclaredMethod(
            "releaseFromJson", String.class, String.class);
        parse.setAccessible(true);

        String three = body(
            asset("almin-2.40.0-server.jar") + ","
                + asset("almin-2.37.0-client.jar") + ","
                + asset("almin-2.39.0-admin.jar"));
        String reversed = body(
            asset("almin-2.39.0-admin.jar") + ","
                + asset("almin-2.37.0-client.jar") + ","
                + asset("almin-2.40.0-server.jar"));
        String missingAdmin = body(
            asset("almin-2.40.0-server.jar") + ","
                + asset("almin-2.37.0-client.jar"));
        String legacy = body(asset("almin-1.18.9.jar"));

        ck("server picks the server jar", pick(three, "server").endsWith("-server.jar"),
            pick(three, "server"));
        ck("base client picks the client jar", pick(three, "client").endsWith("-client.jar"),
            pick(three, "client"));
        ck("admin extension picks the admin jar", pick(three, "admin").endsWith("-admin.jar"),
            pick(three, "admin"));
        ck("upload order does not affect admin selection",
            pick(reversed, "admin").equals("almin-2.39.0-admin.jar"),
            pick(reversed, "admin"));
        ck("a split release never falls back to another artifact",
            pick(missingAdmin, "admin").equals("null"), pick(missingAdmin, "admin"));
        ck("a pre-split universal jar remains a base/server fallback",
            pick(legacy, "client").equals("almin-1.18.9.jar"), pick(legacy, "client"));

        System.out.println(fail == 0 ? "\nASSET-PICK TESTS PASSED"
            : "\n" + fail + " FAILED");
        System.exit(fail == 0 ? 0 : 1);
    }

    static String pick(String json, String want) throws Exception {
        Object release = parse.invoke(null, json, want);
        Method jarName = release.getClass().getDeclaredMethod("jarName");
        return String.valueOf(jarName.invoke(release));
    }
}
