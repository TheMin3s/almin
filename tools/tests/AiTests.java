import java.lang.reflect.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;

/**
 * The model layer, without a model.
 *
 * Everything here is the part that has to be right whatever the model does:
 * that a reply wrapped in prose and code fences is still read, that a moment
 * is anchored to an episode's real coordinates rather than to numbers the
 * model wrote, that the prompt withholds chat when told to, that a
 * misconfiguration is named instead of being a failed request, and that the
 * API key is not reachable through the file browser.
 */
public class AiTests {
    static int failures = 0;
    static Class<?> AI, CFG, EP;

    static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failures++;
    }

    public static void main(String[] a) throws Exception {
        AI = Class.forName("com.schecks.almin.AiInsights");
        CFG = Class.forName("com.schecks.almin.AlminConfig");
        EP = Class.forName("com.schecks.almin.Episodes");

        // ---- pulling JSON out of whatever came back ----
        Method first = AI.getDeclaredMethod("firstObject", String.class);
        first.setAccessible(true);
        check("plain JSON is read",
            first.invoke(null, "{\"summary\":\"hi\"}").equals("{\"summary\":\"hi\"}"));
        check("JSON in a code fence is read",
            first.invoke(null, "Sure!\n```json\n{\"summary\":\"hi\"}\n```\n")
                .equals("{\"summary\":\"hi\"}"));
        check("a brace inside a string does not end the object",
            first.invoke(null, "{\"summary\":\"a } brace\",\"x\":1}")
                .equals("{\"summary\":\"a } brace\",\"x\":1}"));
        check("an escaped quote does not end the string",
            first.invoke(null, "{\"summary\":\"say \\\"hi\\\"\"}")
                .equals("{\"summary\":\"say \\\"hi\\\"\"}"));
        check("nested objects come back whole",
            first.invoke(null, "x {\"a\":{\"b\":2},\"c\":3} y")
                .equals("{\"a\":{\"b\":2},\"c\":3}"));
        check("no JSON at all is no JSON", first.invoke(null, "I could not do that").equals(""));

        // ---- reading a report ----
        Object cfg = CFG.getMethod("get").invoke(null);
        CFG.getField("aiProvider").set(cfg, "local");
        CFG.getField("aiModel").set(cfg, "qwen2.5:3b");
        CFG.getField("aiBaseUrl").set(cfg, "http://127.0.0.1:11434/v1");
        CFG.getField("aiEnabled").setBoolean(cfg, true);
        Field inst = CFG.getDeclaredField("instance"); inst.setAccessible(true);
        inst.set(null, cfg);

        long at = System.currentTimeMillis() - 60_000;
        Object episode = episode("shaft", "Dug a shaft", "Steve", at, 111, 12, 222);
        List<Object> episodes = List.of(episode);

        Class<?> scopeCls = Class.forName("com.schecks.almin.AiInsights$Scope");
        Object all = scopeCls.getMethod("all").invoke(null);
        Method parse = AI.getDeclaredMethod("parse", String.class, long.class, long.class,
            CFG, List.class, scopeCls);
        parse.setAccessible(true);
        Object report = parse.invoke(null,
            "Here you go:\n{\"summary\":\"Quiet night.\",\"moments\":[{\"at\":" + at +
            ",\"label\":\"A shaft\",\"why\":\"Straight down.\",\"player\":\"Steve\"," +
            "\"weight\":70}]}", at - 1000, at, cfg, episodes, all);
        check("the summary is read", get(report, "summary").equals("Quiet night."));
        List<?> moments = (List<?>) get(report, "moments");
        check("the moment is read", moments.size() == 1);
        Object m = moments.get(0);
        check("its place comes from the episode, not from the model",
            ((Integer) get(m, "x")) == 111 && ((Integer) get(m, "z")) == 222
            && get(m, "dim").equals("overworld"));

        // A model that invents a timestamp must not borrow an episode's place.
        report = parse.invoke(null,
            "{\"summary\":\"s\",\"moments\":[{\"at\":12345,\"label\":\"Made up\"}]}",
            at - 1000, at, cfg, episodes, all);
        m = ((List<?>) get(report, "moments")).get(0);
        check("a made-up timestamp gets no coordinates",
            ((Integer) get(m, "x")) == 0 && get(m, "dim").equals(""));

        report = parse.invoke(null, "I am just going to talk instead.", at - 1000, at,
            cfg, episodes, all);
        check("prose with no JSON becomes the summary",
            get(report, "summary").equals("I am just going to talk instead."));
        check("  and is not an error", (Boolean) report.getClass().getMethod("ok").invoke(report));

        // ---- the prompt ----
        Method prompt = AI.getDeclaredMethod("prompt", scopeCls, List.class, List.class,
            List.class, long.class, long.class, int.class, boolean.class);
        prompt.setAccessible(true);
        Class<?> entry = Class.forName("com.schecks.almin.ActivityEntry");
        Object chat = entry.getConstructors()[0].newInstance(at, "Steve", "u", "chat",
            "meet me at spawn", "overworld", 1, 2, 3, 1);
        String withChat = (String) prompt.invoke(null, all, episodes, List.of(chat),
            List.of(chat), at - 60000, at, 2, true);
        String without = (String) prompt.invoke(null, all, episodes, List.of(chat),
            List.of(chat), at - 60000, at, 2, false);
        check("the prompt carries the episodes", withChat.contains("Dug a shaft"));
        check("chat is included when allowed", withChat.contains("meet me at spawn"));
        check("and withheld when not", !without.contains("meet me at spawn"));
        check("the prompt stays small", withChat.length() < 6000);

        // ---- which subject it is about ----
        Object mine = scopeCls.getMethod("of", String.class).invoke(null, "Steve");
        Object here = scopeCls.getMethod("area", String.class, int.class, int.class, int.class)
            .invoke(null, "overworld", 100, 200, 64);
        String onePlayer = (String) prompt.invoke(null, mine, episodes, List.of(chat),
            List.of(chat), at - 60000, at, 2, true);
        String oneArea = (String) prompt.invoke(null, here, episodes, List.of(chat),
            List.of(chat), at - 60000, at, 2, true);
        check("a player prompt says whose it is", onePlayer.contains("one player: Steve"));
        check("an area prompt says where it is",
            oneArea.contains("64 blocks of 100,200"));
        check("the whole server says that too", withChat.contains("the whole server"));

        // Three subjects, three cached answers: asking about one player must
        // not quietly replace the summary of everything.
        check("each subject is its own answer",
            !scopeCls.getMethod("key").invoke(mine)
                .equals(scopeCls.getMethod("key").invoke(all)));
        Object near = scopeCls.getMethod("area", String.class, int.class, int.class, int.class)
            .invoke(null, "overworld", 104, 202, 64);
        check("...but nudging the map is not a new question",
            scopeCls.getMethod("key").invoke(here)
                .equals(scopeCls.getMethod("key").invoke(near)));

        // ---- the timeline the model needs to find a rhythm ----
        StringBuilder tl = new StringBuilder();
        Method timeline = AI.getDeclaredMethod("timeline", StringBuilder.class, List.class,
            long.class, long.class);
        timeline.setAccessible(true);
        List<Object> many = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) {
            many.add(entry.getConstructors()[0].newInstance(at + i * 60_000L, "Steve", "u",
                "break", "Stone", "overworld", 10 + i, 30, 20, 1));
        }
        timeline.invoke(null, tl, many, at, at + 40 * 60_000L);
        String table = tl.toString();
        check("the log is also shown cut by time, not only by place",
            table.contains("quarter-hour") && table.contains("Steve"));
        check("  and it counts rather than lists", table.contains("break")
            && !table.contains("Stone"));
        check("  and stays small: " + table.length() + " chars", table.length() < 2000);

        // ---- what it found that the rules could not ----
        long from = at - 60000, to = at;
        Object found = parse.invoke(null,
            "{\"summary\":\"s\",\"patterns\":[" +
            "{\"from\":" + from + ",\"to\":" + to + ",\"player\":\"Alex\"," +
            "\"label\":\"Back every evening\",\"why\":\"Four visits.\"}," +
            "{\"from\":1,\"to\":2,\"label\":\"Last Tuesday\"}]}",
            from, to, cfg, episodes, all);
        List<?> patterns = (List<?>) get(found, "found");
        check("a pattern inside the window is kept", patterns.size() == 1);
        check("  and one outside it is dropped, not clamped into view",
            patterns.size() == 1 && get(patterns.get(0), "label").equals("Back every evening"));

        // ---- what is wrong, said plainly ----
        Method problem = AI.getMethod("problem");
        check("a configured local model has no problem",
            ((String) problem.invoke(null)).isEmpty());

        CFG.getField("aiBaseUrl").set(cfg, "127.0.0.1:11434");
        check("a base URL with no scheme is named: " + problem.invoke(null),
            ((String) problem.invoke(null)).contains("http://"));

        CFG.getField("aiBaseUrl").set(cfg, "http://user:pw@localhost:1234/v1");
        check("a key smuggled into the URL is refused",
            ((String) problem.invoke(null)).contains("key file"));

        CFG.getField("aiBaseUrl").set(cfg, "http://127.0.0.1:11434/v1");
        CFG.getField("aiProvider").set(cfg, "anthropic");
        check("a hosted provider with no key says so: " + problem.invoke(null),
            ((String) problem.invoke(null)).contains("API key"));

        CFG.getField("aiEnabled").setBoolean(cfg, false);
        check("switched off is the first thing said",
            ((String) problem.invoke(null)).contains("off"));
        CFG.getField("aiEnabled").setBoolean(cfg, true);
        CFG.getField("aiProvider").set(cfg, "local");

        // Slow local and custom servers get the timeout the admin chose, up
        // to the same one-hour bound accepted by config and the settings UI.
        Method timeout = AI.getDeclaredMethod("timeout");
        timeout.setAccessible(true);
        CFG.getField("aiTimeoutSeconds").setInt(cfg, 1800);
        check("a custom model can wait for the configured half hour",
            ((java.time.Duration) timeout.invoke(null)).toSeconds() == 1800);
        CFG.getField("aiTimeoutSeconds").setInt(cfg, 9999);
        check("the runtime timeout still has a one-hour safety bound",
            ((java.time.Duration) timeout.invoke(null)).toSeconds() == 3600);
        Object timeoutKey = CFG.getMethod("keyByName", String.class)
            .invoke(null, "ai-timeout-seconds");
        check("the config accepts custom waits up to one hour",
            timeoutKey != null && timeoutKey.getClass().getField("min").getInt(timeoutKey) == 5
                && timeoutKey.getClass().getField("max").getInt(timeoutKey) == 3600);
        CFG.getField("aiTimeoutSeconds").setInt(cfg, 45);

        // ---- the key file ----
        Path dir = Files.createTempDirectory("almin-ai");
        AI.getMethod("init", Path.class).invoke(null, dir);
        check("no key to start with", !(Boolean) AI.getMethod("hasKey").invoke(null));
        AI.getMethod("setKey", String.class).invoke(null, "sk-secret-value");
        check("a key can be saved", (Boolean) AI.getMethod("hasKey").invoke(null));
        Path f = dir.resolve("config").resolve("almin").resolve("ai-key");
        check("it is kept outside config.json", Files.isRegularFile(f));
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(f);
            check("and only the account running the server can read it",
                perms.equals(EnumSet.of(PosixFilePermission.OWNER_READ,
                                        PosixFilePermission.OWNER_WRITE)));
        } catch (UnsupportedOperationException e) {
            System.out.println("  --   no POSIX permissions on this filesystem");
        }
        AI.getMethod("setKey", String.class).invoke(null, "");
        check("and forgotten", !(Boolean) AI.getMethod("hasKey").invoke(null)
            && !Files.exists(f));

        // ---- and the browser will not serve it ----
        Class<?> WF = Class.forName("com.schecks.almin.WebFiles");
        Method resolve = WF.getMethod("resolveUnder", Path.class, String.class);
        check("the file browser refuses the key by path",
            resolve.invoke(null, dir, "config/almin/ai-key") == null);
        check("  and still serves the config next to it",
            resolve.invoke(null, dir, "config/almin/config.json") != null);
        check("  and is not fooled by a roundabout route",
            resolve.invoke(null, dir, "config/almin/../almin/ai-key") == null);

        System.out.println(failures == 0 ? "AI OK" : "AI FAILURES: " + failures);
        if (failures > 0) System.exit(1);
    }

    static Object episode(String kind, String headline, String who, long at,
                          int x, int y, int z) throws Exception {
        Class<?> E = Class.forName("com.schecks.almin.Episodes$Episode");
        return E.getConstructors()[0].newInstance(kind, headline, who, "uuid", "overworld",
            at - 5000, at, x, y, z, 4, 3, 20, 40, "pickaxe");
    }

    static Object get(Object rec, String field) throws Exception {
        return rec.getClass().getMethod(field).invoke(rec);
    }
}
