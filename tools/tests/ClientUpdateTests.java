import com.schecks.almin.client.ClientConfig;

import java.lang.reflect.*;

/**
 * The client's own update check: when it decides to download, when it does
 * not, and the switch that turns the whole thing off.
 *
 * The download itself needs GitHub and a game directory; the decision does
 * not, and the decision is where re-downloading the same jar every few hours
 * would have hidden.
 */
public class ClientUpdateTests {
    static int fail = 0;
    static void ck(String w, boolean ok, String d) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + w + (ok ? "" : "  -> " + d));
        if (!ok) fail++;
    }

    static Method worth;
    static Method assetVersion;

    public static void main(String[] a) throws Exception {
        Class<?> updater = Class.forName("com.schecks.almin.client.ClientUpdater");
        worth = updater.getDeclaredMethod("worthInstalling", String.class, String.class, String.class);
        worth.setAccessible(true);
        assetVersion = Class.forName("com.schecks.almin.UpdateChecker")
            .getDeclaredMethod("assetVersion", String.class, String.class, String.class);
        assetVersion.setAccessible(true);

        decisions();
        versions();
        config();
        wiring();

        System.out.println(fail == 0 ? "\nCLIENT-UPDATE TESTS PASSED" : "\n" + fail + " FAILED");
        System.exit(fail == 0 ? 0 : 1);
    }

    static String av(String name, String side, String fallback) throws Exception {
        return (String) assetVersion.invoke(null, name, side, fallback);
    }

    static void versions() throws Exception {
        ck("a client asset carries its own version",
            av("almin-2.35.0-client.jar", "client", "2.36.4").equals("2.35.0"), "");
        ck("a server asset carries its own version",
            av("almin-2.36.4-server.jar", "server", "2.35.0").equals("2.36.4"), "");
        ck("a legacy jar keeps the release tag",
            av("almin-1.18.9.jar", "client", "1.18.9").equals("1.18.9"), "");
    }

    static boolean w(String candidate, String current, String staged) throws Exception {
        return (Boolean) worth.invoke(null, candidate, current, staged);
    }

    static void decisions() throws Exception {
        ck("a newer release is taken", w("2.12.0", "2.11.0", ""), "");
        ck("the same version is not", !w("2.11.0", "2.11.0", ""), "");
        ck("an older release is not", !w("2.10.0", "2.11.0", ""), "");
        ck("a patch bump counts", w("2.11.1", "2.11.0", ""), "");
        ck("a major bump counts", w("3.0.0", "2.11.0", ""), "");

        // The running version does not change until a restart, so without this
        // every check would fetch the same jar again, forever.
        ck("what is already staged is not fetched again",
            !w("2.12.0", "2.11.0", "2.12.0"), "");
        ck("...but something newer than the staged one is",
            w("2.13.0", "2.11.0", "2.12.0"), "");
        ck("...and something older than it is not",
            !w("2.11.5", "2.11.0", "2.12.0"), "");

        ck("nothing is not a version", !w("", "2.11.0", ""), "");
        ck("null is not a version", !w(null, "2.11.0", ""), "");
        ck("an empty staged value does not block", w("2.12.0", "2.11.0", ""), "");
        ck("a null staged value does not block", w("2.12.0", "2.11.0", null), "");
    }

    static void config() throws Exception {
        ClientConfig defaults = ClientConfig.class.getDeclaredConstructor().newInstance();
        ck("auto-update is on by default", defaults.autoUpdate, "");
        ck("it re-checks a few times a day", defaults.checkHours == 3,
            String.valueOf(defaults.checkHours));

        // The field has to be reachable, or nobody can turn it off.
        Field f = ClientConfig.class.getDeclaredField("autoUpdate");
        ck("the switch is public", Modifier.isPublic(f.getModifiers()), "");
    }

    /** The check must actually be started, and must respect the switch. */
    static void wiring() throws Exception {
        String client = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/main/java/com/schecks/almin/client/AlminClient.java"));
        ck("the client entrypoint starts the checker",
            client.contains("ClientUpdater.startBackgroundChecks()"), "not called");

        String updater = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/main/java/com/schecks/almin/client/ClientUpdater.java"));
        int i = updater.indexOf("public static synchronized void startBackgroundChecks");
        String body = updater.substring(i, updater.indexOf("\n    }", i));
        ck("...and checks the switch before scheduling anything",
            body.contains("ClientConfig.get().autoUpdate"), body);
        ck("...on a daemon thread, so it cannot hold the game open",
            body.contains("setDaemon(true)"), body);
        ck("...guarded, so one failure does not end all checks",
            body.contains("guarded("), body);

        int j = updater.indexOf("private static void checkGitHub");
        String check = updater.substring(j, updater.indexOf("\n    }", j));
        ck("the check itself re-reads the switch",
            check.contains("ClientConfig.get().autoUpdate"), check);
        ck("...and only ever asks the hardcoded repo",
            check.contains("REPO") && !check.contains("http"), check);

        String join = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/main/java/com/schecks/almin/events/JoinHandler.java"));
        ck("the server advertises the required client build, not its own version",
            join.contains("new ServerVersionPayload(UpdateChecker.clientVersion())"), join);

        String release = java.nio.file.Files.readString(java.nio.file.Path.of("release.sh"));
        ck("server-only releases retain the prior client build",
            release.contains("CLIENT_VERSION=\"$CURRENT_CLIENT\"") &&
            release.contains("--client") && release.contains("client_version"), release);
    }
}
