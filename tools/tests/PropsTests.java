import com.schecks.almin.ServerProperties;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/**
 * server.properties, edited without being rewritten.
 *
 * The point of the whole class is that a file people read by hand comes back
 * looking like a file people read by hand: same comments, same order, one line
 * different. Properties.store would pass a round-trip test and fail every one
 * of these.
 */
public class PropsTests {
    static int failures = 0;
    static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failures++;
    }

    static final String SAMPLE = String.join("\n",
        "#Minecraft server properties",
        "#Tue Jan 02 03:04:05 GMT 2024",
        "enable-jmx-monitoring=false",
        "rcon.port=25575",
        "level-seed=",
        "gamemode=survival",
        "enable-command-block=false",
        "",
        "# how far people can see",
        "view-distance=10",
        "rcon.password=hunter2",
        "motd=A Minecraft Server",
        "level-name=world") + "\n";

    public static void main(String[] a) throws Exception {
        Path dir = Files.createTempDirectory("almin-props");
        Path file = dir.resolve("server.properties");
        Files.writeString(file, SAMPLE);

        List<ServerProperties.Entry> rows = ServerProperties.read(file);
        check("every setting is read (" + rows.size() + ")", rows.size() == 9);
        check("...in the order the file has them",
            rows.get(0).key().equals("enable-jmx-monitoring")
            && rows.get(3).key().equals("gamemode"));
        check("a true/false value is offered as one",
            find(rows, "enable-command-block").type().equals("BOOL"));
        check("a number is offered as one", find(rows, "view-distance").type().equals("INT"));
        check("everything else is text", find(rows, "motd").type().equals("TEXT"));
        check("an empty value does not become a number",
            find(rows, "level-seed").type().equals("TEXT"));

        ServerProperties.Entry pw = find(rows, "rcon.password");
        check("a password never leaves the server", pw.secret() && !pw.value().equals("hunter2"));
        check("...and is shown as a mask", pw.value().equals(ServerProperties.MASK));
        check("a port beside it is not treated as a secret", !find(rows, "rcon.port").secret());

        // ---- writing ----
        int changed = ServerProperties.write(file, Map.of(
            "view-distance", "16", "motd", "Somewhere else"));
        check("only what changed is written", changed == 2);
        String after = Files.readString(file);
        check("the comments survive", after.contains("#Minecraft server properties")
            && after.contains("# how far people can see"));
        check("the blank line survives", after.contains("enable-command-block=false\n\n#"));
        check("the order survives",
            after.indexOf("gamemode") < after.indexOf("view-distance"));
        check("the new value is there", after.contains("view-distance=16"));
        check("and the old one is not", !after.contains("view-distance=10"));
        check("the other value took too", after.contains("motd=Somewhere else"));
        check("nothing else moved", after.lines().count() == SAMPLE.lines().count());

        // The mask coming back means "leave it alone".
        ServerProperties.write(file, Map.of("rcon.password", ServerProperties.MASK));
        check("sending the mask back does not overwrite the password",
            Files.readString(file).contains("rcon.password=hunter2"));

        ServerProperties.write(file, Map.of("rcon.password", "correct horse"));
        check("a real new password does get written",
            Files.readString(file).contains("rcon.password=correct horse"));

        // A key the file does not have yet.
        ServerProperties.write(file, Map.of("simulation-distance", "8"));
        check("a setting that was not there is appended",
            Files.readString(file).trim().endsWith("simulation-distance=8"));

        // ---- what it refuses ----
        check("a value cannot smuggle in another line", throwsIo(file,
            Map.of("motd", "hello\nop-permission-level=4")));
        check("a name has to look like one", throwsIo(file,
            Map.of("motd\nrcon.password", "x")));
        check("an absurd value is refused", throwsIo(file,
            Map.of("motd", "x".repeat(5000))));

        // ---- escaping ----
        ServerProperties.write(file, Map.of("motd", "back\\slash and : colon"));
        List<ServerProperties.Entry> back = ServerProperties.read(file);
        check("a backslash survives a round trip",
            find(back, "motd").value().equals("back\\slash and : colon"));
        ServerProperties.write(file, Map.of("motd", "  leading space"));
        check("a leading space survives too",
            find(ServerProperties.read(file), "motd").value().equals("  leading space"));

        // A file that is not there is a message, not a stack trace.
        check("a missing file says so",
            throwsIoRead(dir.resolve("nope.properties")));

        System.out.println(failures == 0 ? "PROPS OK" : "PROPS FAILURES: " + failures);
        if (failures > 0) System.exit(1);
    }

    static ServerProperties.Entry find(List<ServerProperties.Entry> rows, String key) {
        for (ServerProperties.Entry e : rows) if (e.key().equals(key)) return e;
        throw new IllegalStateException("no such key: " + key);
    }

    static boolean throwsIo(Path file, Map<String, String> changes) {
        try { ServerProperties.write(file, changes); return false; }
        catch (Exception e) { return e instanceof java.io.IOException; }
    }

    static boolean throwsIoRead(Path file) {
        try { ServerProperties.read(file); return false; }
        catch (Exception e) { return e instanceof java.io.IOException; }
    }
}
