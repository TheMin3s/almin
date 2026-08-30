import com.schecks.almin.ActivityLog;
import com.schecks.almin.ActivityPayload;
import com.schecks.almin.AlminConfig;
import com.schecks.almin.PlayerTracks;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * The player activity log: folding, the entry cap, expiry, the disk round-trip
 * and the wire format.
 *
 * The exclusion rule needs a live ServerPlayer, so it is checked against the
 * source instead — the two conditions that must be there, and the fact that
 * nothing else weakens them.
 */
public class ActivityTests {
    static int fail = 0;
    static void ck(String w, boolean ok, String d) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + w + (ok ? "" : "  -> " + d));
        if (!ok) fail++;
    }

    static AlminConfig cfg;
    static Method store, flush, prune, load;
    static Field entriesF, fileF;

    public static void main(String[] a) throws Exception {
        Constructor<AlminConfig> cc = AlminConfig.class.getDeclaredConstructor();
        cc.setAccessible(true);
        cfg = cc.newInstance();
        Field inst = AlminConfig.class.getDeclaredField("instance");
        inst.setAccessible(true); inst.set(null, cfg);

        store = ActivityLog.class.getDeclaredMethod("store", String.class, String.class,
            String.class, String.class, String.class, int.class, int.class, int.class,
            boolean.class);
        store.setAccessible(true);
        flush = m("flush"); prune = m("prune"); load = m("load");
        entriesF = ActivityLog.class.getDeclaredField("entries"); entriesF.setAccessible(true);
        fileF = ActivityLog.class.getDeclaredField("file"); fileF.setAccessible(true);

        defaults();
        folding();
        cap();
        expiry();
        disk();
        codec();
        exclusion();
        admins();
        placing();

        coordinates();
        System.out.println(fail == 0 ? "\nACTIVITY TESTS PASSED" : "\n" + fail + " FAILED");
        System.exit(fail == 0 ? 0 : 1);
    }

    /**
     * Placing a block and interacting with one used to be the same row.
     *
     * <p>{@code UseBlockCallback} fires before the interaction resolves, so
     * dirt-on-dirt was logged as "used Dirt on Dirt" — wrong, and the opposite
     * of what a grief report needs, because placements are the rows that
     * matter most. The row is now held back until the tick is over and
     * upgraded if a placement actually happened.
     */
    static void placing() throws Exception {
        String hooks = Files.readString(Path.of(
            "src/main/java/com/schecks/almin/events/ActivityHooks.java"));
        ck("a right-click no longer records straight away",
            !hooks.contains("ActivityLog.recordBlock(p, \"use\""), "still immediate");
        ck("...it waits to see what it turned into", hooks.contains("pending.put("), "no hold");
        ck("a placement is its own action", hooks.contains("\"place\""), "no place action");
        int placed = hooks.indexOf("public static void placed(");
        ck("...and cancels the held-back use, so one click is one row",
            placed > 0 && hooks.indexOf("pending.remove(", placed) > placed
                && hooks.indexOf("pending.remove(", placed) < hooks.indexOf("recordBlock", placed),
            "not cancelled first");
        ck("anything not upgraded is written out at the end of the tick",
            hooks.contains("END_SERVER_TICK") && hooks.contains("flushPending"), "no flush");

        String mixin = Files.readString(Path.of(
            "src/main/java/com/schecks/almin/mixin/BlockPlaceActivityMixin.java"));
        ck("placement is observed where it is actually decided",
            mixin.contains("@Mixin(BlockItem.class)") && mixin.contains("method = \"place\""),
            "wrong target");
        ck("...at RETURN, so a refused placement is not one",
            mixin.contains("@At(\"RETURN\")") && mixin.contains("consumesAction()"), "no check");
        ck("...and never cancels",
            !mixin.contains("cir.setReturnValue") && !mixin.contains("ci.cancel"), "it cancels");

        String json = Files.readString(Path.of("src/main/resources/almin.mixins.json"));
        ck("the mixin is actually registered", json.contains("BlockPlaceActivityMixin"), "missing");

        // The target has to exist, or a required mixin refuses the whole mod.
        // Loaded without initialising: touching Item's static block outside a
        // running game trips Minecraft's registry bootstrap.
        ClassLoader cl = ActivityTests.class.getClassLoader();
        Class<?> blockItem = Class.forName("net.minecraft.world.item.BlockItem", false, cl);
        Method place = blockItem.getDeclaredMethod("place",
            Class.forName("net.minecraft.world.item.context.BlockPlaceContext", false, cl));
        ck("BlockItem.place is still there to inject into",
            place.getReturnType().getName().equals("net.minecraft.world.InteractionResult"),
            place.getReturnType().getName());
    }

    /** The map needs real numbers, and negative ones are the common case. */
    static void coordinates() throws Exception {
        reset();
        store.invoke(null, "Steve", "u", "break", "Stone", "the_nether", -1500, 31, 2400, false);
        ActivityLog.Entry e = deque().peekLast();
        ck("coordinates survive as numbers",
            e.x() == -1500 && e.y() == 31 && e.z() == 2400, e.toString());
        ck("the dimension is kept", "the_nether".equals(e.dim()), e.dim());
        ck("where() still reads as a line",
            "the_nether -1500,31,2400".equals(e.where()), e.where());

        // Rows written before the place became numbers must still load.
        Path dir = Files.createTempDirectory("alminold");
        Path f = dir.resolve("activity.log");
        fileF.set(null, f);
        Files.writeString(f, "{\"at\":" + System.currentTimeMillis()
            + ",\"player\":\"Old\",\"uuid\":\"u\",\"action\":\"chat\",\"detail\":\"hi\","
            + "\"where\":\"overworld -12,64,300\",\"count\":1}\n");
        reset();
        load.invoke(null);
        ck("an old row still loads", deque().size() == 1, String.valueOf(deque().size()));
        ActivityLog.Entry old = deque().peekFirst();
        ck("...with its place read back out of the old string",
            old != null && old.x() == -12 && old.y() == 64 && old.z() == 300
                && "overworld".equals(old.dim()),
            old == null ? "none" : old.toString());
        fileF.set(null, null);
    }

    static Method m(String n) throws Exception {
        Method x = ActivityLog.class.getDeclaredMethod(n); x.setAccessible(true); return x;
    }
    static void rec(String who, String action, String detail, boolean fold) throws Exception {
        store.invoke(null, who, "uuid-" + who, action, detail, "overworld", 0, 64, 0, fold);
    }
    @SuppressWarnings("unchecked")
    static Deque<ActivityLog.Entry> deque() throws Exception {
        return (Deque<ActivityLog.Entry>) entriesF.get(null);
    }
    static void reset() throws Exception {
        deque().clear();
        Field p = ActivityLog.class.getDeclaredField("pending"); p.setAccessible(true);
        ((java.util.Queue<?>) p.get(null)).clear();
    }
    static Object get(String f) throws Exception {
        Field x = AlminConfig.class.getDeclaredField(f); x.setAccessible(true); return x.get(cfg);
    }
    static void set(String f, Object v) throws Exception {
        Field x = AlminConfig.class.getDeclaredField(f); x.setAccessible(true); x.set(cfg, v);
    }

    static void defaults() throws Exception {
        ck("recording is on by default", (Boolean) get("activityLog"), "");
        ck("retention defaults to five days",
            (Integer) get("activityRetentionMinutes") == 7200, String.valueOf(get("activityRetentionMinutes")));
        ck("retentionMillis matches the setting",
            ActivityLog.retentionMillis() == 7200L * 60_000, String.valueOf(ActivityLog.retentionMillis()));
        set("activityRetentionMinutes", 30);
        ck("a shorter retention is honoured",
            ActivityLog.retentionMillis() == 30L * 60_000, String.valueOf(ActivityLog.retentionMillis()));
        set("activityRetentionMinutes", 7200);

        // The bounds are what stop someone setting a retention of "forever".
        AlminConfig.Key k = AlminConfig.keyByName("activity-retention-minutes");
        ck("retention is a bounded setting", k != null && k.min == 5 && k.max == 43200,
            k == null ? "missing" : k.min + ".." + k.max);
        boolean rejects = false;
        try { k.parse("9999999"); } catch (IllegalArgumentException e) { rejects = true; }
        ck("an unbounded retention is refused", rejects, "");
    }

    /** Block spam must fold, and only when it is genuinely the same thing. */
    static void folding() throws Exception {
        reset();
        for (int i = 0; i < 50; i++) rec("Steve", "break", "Stone", true);
        ck("50 identical breaks fold into one row", deque().size() == 1, String.valueOf(deque().size()));
        ck("...carrying the count", deque().peekLast().count() == 50,
            String.valueOf(deque().peekLast().count()));

        rec("Steve", "break", "Dirt", true);
        ck("a different block starts a new row", deque().size() == 2, String.valueOf(deque().size()));
        rec("Alex", "break", "Dirt", true);
        ck("a different player starts a new row", deque().size() == 3, String.valueOf(deque().size()));

        reset();
        for (int i = 0; i < 5; i++) rec("Steve", "chat", "hello", false);
        ck("chat never folds, even when repeated", deque().size() == 5, String.valueOf(deque().size()));

        reset();
        rec("Steve", "break", "Stone", true);
        // An old tail must not absorb a new event.
        ActivityLog.Entry old = deque().pollLast();
        deque().addLast(new ActivityLog.Entry(System.currentTimeMillis() - 120_000,
            old.player(), old.uuid(), old.action(), old.detail(),
            old.dim(), old.x(), old.y(), old.z(), old.count()));
        rec("Steve", "break", "Stone", true);
        ck("folding stops once the window has passed", deque().size() == 2, String.valueOf(deque().size()));
    }

    static void cap() throws Exception {
        reset();
        set("activityMaxEntries", 500);
        for (int i = 0; i < 700; i++) rec("P" + i, "chat", "line " + i, false);
        ck("the log is capped", deque().size() == 500, String.valueOf(deque().size()));
        ck("...dropping the oldest first", deque().peekFirst().detail().equals("line 200"),
            deque().peekFirst().detail());
        ck("recent() returns newest first",
            ActivityLog.recent(3).get(0).detail().equals("line 699"),
            ActivityLog.recent(3).get(0).detail());
        ck("recent() honours its limit", ActivityLog.recent(3).size() == 3, "");
    }

    /** The whole point: rows go away on their own. */
    static void expiry() throws Exception {
        reset();
        set("activityRetentionMinutes", 60);
        long now = System.currentTimeMillis();
        deque().addLast(new ActivityLog.Entry(now - 120 * 60_000L, "Old", "u", "chat", "ancient", "overworld", 1, 2, 3, 1));
        deque().addLast(new ActivityLog.Entry(now - 61 * 60_000L, "Old", "u", "chat", "just too old", "overworld", 1, 2, 3, 1));
        deque().addLast(new ActivityLog.Entry(now - 59 * 60_000L, "New", "u", "chat", "still here", "overworld", 1, 2, 3, 1));
        deque().addLast(new ActivityLog.Entry(now, "New", "u", "chat", "now", "overworld", 1, 2, 3, 1));

        ck("size() drops what has expired", ActivityLog.size() == 2, String.valueOf(ActivityLog.size()));
        List<ActivityLog.Entry> got = ActivityLog.recent(10);
        ck("...and so does recent()", got.size() == 2, String.valueOf(got.size()));
        ck("the row just inside the window survives",
            got.stream().anyMatch(e -> e.detail().equals("still here")), got.toString());
        ck("the row just outside it is gone",
            got.stream().noneMatch(e -> e.detail().equals("just too old")), got.toString());
        set("activityRetentionMinutes", 7200);
    }

    /** It has to survive a restart, and expired rows must not come back. */
    static void disk() throws Exception {
        reset();
        Path dir = Files.createTempDirectory("alminactivity");
        Path f = dir.resolve("activity.log");
        fileF.set(null, f);

        rec("Steve", "chat", "written to disk", false);
        rec("Alex", "command", "/home", false);
        flush.invoke(null);
        ck("flush writes a file", Files.isRegularFile(f), String.valueOf(f));
        ck("...one JSON row per line", Files.readAllLines(f).size() == 2,
            String.valueOf(Files.readAllLines(f).size()));

        // Reload as a restart would.
        reset();
        load.invoke(null);
        ck("a restart reads the log back", deque().size() == 2, String.valueOf(deque().size()));
        ck("...with the fields intact",
            deque().peekFirst().detail().equals("written to disk"), deque().peekFirst().toString());

        // An expired row on disk must not survive the read.
        reset();
        Files.writeString(f, "{\"at\":1,\"player\":\"Ghost\",\"uuid\":\"u\",\"action\":\"chat\","
            + "\"detail\":\"from 1970\",\"dim\":\"overworld\",\"count\":1}\n");
        load.invoke(null);
        ck("an expired row is not loaded back", deque().isEmpty(), String.valueOf(deque().size()));

        // A corrupt file must not stop the server.
        reset();
        Files.writeString(f, "not json at all\n{\"at\":" + System.currentTimeMillis()
            + ",\"player\":\"Real\",\"action\":\"chat\",\"detail\":\"ok\"}\n");
        load.invoke(null);
        ck("a corrupt line is skipped, not fatal", deque().size() == 1, String.valueOf(deque().size()));

        // Prune rewrites the file from memory, dropping what expired.
        reset();
        long now = System.currentTimeMillis();
        // Older than the five-day window, whatever the window happens to be.
        deque().addLast(new ActivityLog.Entry(now - ActivityLog.retentionMillis() - 60_000L,
            "Old", "u", "chat", "gone", "overworld", 1, 2, 3, 1));
        deque().addLast(new ActivityLog.Entry(now, "New", "u", "chat", "kept", "overworld", 1, 2, 3, 1));
        prune.invoke(null);
        String after = Files.readString(f);
        ck("prune rewrites the file without expired rows",
            after.contains("kept") && !after.contains("gone"), after);

        ck("clear() empties memory and deletes the file",
            ActivityLog.clear() && deque().isEmpty() && !Files.exists(f), "");
        fileF.set(null, null);
    }

    /** Thirteen-ish fields over the wire; hand-written codecs get order wrong. */
    static void codec() throws Exception {
        List<ActivityLog.Entry> rows = List.of(
            new ActivityLog.Entry(1234567890L, "Steve", "uuid-1", "break", "Stone",
                "overworld", -1200, 64, 3400, 42),
            new ActivityLog.Entry(1234567999L, "Alex", "uuid-2", "chat", "hi there",
                "the_nether", 4, -5, 6, 1));
        // Paths travel with the rows now, so the map can be drawn in game.
        List<ActivityPayload.Track> tracks = List.of(
            new ActivityPayload.Track("Steve", List.of(
                new PlayerTracks.Point(1234567800L, "overworld", -1200, 64, 3400),
                new PlayerTracks.Point(1234567850L, "overworld", -1150, 70, 3390))),
            new ActivityPayload.Track("Alex", List.of(
                new PlayerTracks.Point(1234567900L, "the_nether", 4, -5, 6))));
        ActivityLog.AdminPolicy policy = new ActivityLog.AdminPolicy(true, true, false);
        ActivityPayload sent = new ActivityPayload(rows, 99, 720, true, tracks, policy);

        Method w = ActivityPayload.class.getDeclaredMethod("write",
            RegistryFriendlyByteBuf.class, ActivityPayload.class);
        Method r = ActivityPayload.class.getDeclaredMethod("read", RegistryFriendlyByteBuf.class);
        w.setAccessible(true); r.setAccessible(true);

        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), null);
        w.invoke(null, buf, sent);
        ActivityPayload back = (ActivityPayload) r.invoke(null, buf);
        ck("the activity packet round-trips", sent.equals(back), back.toString());
        ck("the buffer is fully consumed", buf.readableBytes() == 0,
            buf.readableBytes() + " bytes left");

        // More rows than fit must be trimmed, not overflow the declared cap.
        java.util.List<ActivityLog.Entry> many = new java.util.ArrayList<>();
        for (int i = 0; i < ActivityPayload.MAX_ROWS + 250; i++) {
            many.add(new ActivityLog.Entry(i, "P", "u", "chat", "x".repeat(400), "overworld", 1, 2, 3, 1));
        }
        RegistryFriendlyByteBuf b2 = new RegistryFriendlyByteBuf(Unpooled.buffer(), null);
        java.util.List<ActivityPayload.Track> fatTracks = new java.util.ArrayList<>();
        for (int i = 0; i < 80; i++) {
            java.util.List<PlayerTracks.Point> pts = new java.util.ArrayList<>();
            for (int j = 0; j < 400; j++) {
                pts.add(new PlayerTracks.Point(j, "overworld", j, 64, j));
            }
            fatTracks.add(new ActivityPayload.Track("Player" + i, pts));
        }
        w.invoke(null, b2, new ActivityPayload(many, many.size(), 60, true,
            fatTracks, new ActivityLog.AdminPolicy(false, false, false)));
        int size = b2.readableBytes();
        ActivityPayload trimmed = (ActivityPayload) r.invoke(null, b2);
        ck("an over-long list is trimmed to MAX_ROWS",
            trimmed.entries().size() == ActivityPayload.MAX_ROWS,
            String.valueOf(trimmed.entries().size()));
        ck("...and stays inside the declared packet cap",
            size <= ActivityPayload.MAX_BYTES, size + " > " + ActivityPayload.MAX_BYTES);
        ck("the true total still travels", trimmed.total() == many.size(),
            String.valueOf(trimmed.total()));

        int points = 0;
        for (ActivityPayload.Track t : trimmed.tracks()) points += t.points().size();
        ck("too many path points are trimmed as well",
            points <= ActivityPayload.MAX_TRACK_POINTS, String.valueOf(points));
        ck("...and so are too many players",
            trimmed.tracks().size() <= 64, String.valueOf(trimmed.tracks().size()));
    }

    /**
     * Who is in the log, and the run-only override.
     *
     * <p>The default matters more than the switch: the log is read by the
     * people it would otherwise be about, so leaving admins out is what stops
     * it being a way for staff to watch each other.
     */
    static void admins() throws Exception {
        Constructor<AlminConfig> cc = AlminConfig.class.getDeclaredConstructor();
        cc.setAccessible(true);
        AlminConfig fresh = cc.newInstance();
        Field f = AlminConfig.class.getDeclaredField("activityIncludeAdmins");
        f.setAccessible(true);
        ck("admins are excluded by default", !((boolean) f.get(fresh)), "");

        AlminConfig.Key k = AlminConfig.keyByName("activity-include-admins");
        ck("it is a setting you can find", k != null, "missing");
        ck("...and it is a switch", k != null && k.type == AlminConfig.Type.BOOL,
            k == null ? "-" : k.type.name());

        ActivityLog.setTemporaryIncludeAdmins(null);
        AlminConfig.get().activityIncludeAdmins = false;
        ck("with nothing set, the setting decides", !ActivityLog.includeAdmins(), "");
        ck("...and says so", !ActivityLog.adminPolicy().temporary(), "");

        ActivityLog.setTemporaryIncludeAdmins(Boolean.TRUE);
        ck("the run-only override wins", ActivityLog.includeAdmins(), "");
        ck("...and is marked as temporary", ActivityLog.adminPolicy().temporary(), "");
        ck("...without touching the saved setting",
            !AlminConfig.get().activityIncludeAdmins
                && !ActivityLog.adminPolicy().configured(), "config changed");

        // It has to be able to turn admins OFF too, on a server that saved it on.
        AlminConfig.get().activityIncludeAdmins = true;
        ActivityLog.setTemporaryIncludeAdmins(Boolean.FALSE);
        ck("the override can also exclude them again", !ActivityLog.includeAdmins(), "");
        ck("...while the saved setting still reads on",
            ActivityLog.adminPolicy().configured(), "");

        ActivityLog.setTemporaryIncludeAdmins(null);
        ck("clearing the override hands control back", ActivityLog.includeAdmins(), "");
        AlminConfig.get().activityIncludeAdmins = false;
        ActivityLog.setTemporaryIncludeAdmins(null);

        // An admin's own /almin commands are never recorded, whatever the
        // setting says: the log is read through /almin, and recording that
        // means every admin who opens the activity screen fills it with rows
        // about having opened it.
        java.lang.reflect.Method own = ActivityLog.class.getDeclaredMethod(
            "isOwnCommand", String.class, String.class);
        own.setAccessible(true);
        for (String typed : new String[]{"almin", "/almin", "almin op activity",
                                         "/almin op web", "  /almin  op dir",
                                         "almin mods list"}) {
            ck("'" + typed + "' is Almin's own command",
                (boolean) own.invoke(null, "command", typed), "not matched");
        }
        for (String typed : new String[]{"alminx", "/alminx op", "admin", "/gamemode creative",
                                         "say almin", "/tell x almin op"}) {
            ck("'" + typed + "' is somebody else's and stays in the log",
                !((boolean) own.invoke(null, "command", typed)), "wrongly dropped");
        }
        ck("the rule only applies to commands",
            !((boolean) own.invoke(null, "chat", "/almin op web")), "dropped a chat line");
        ck("a null command is not one of ours",
            !((boolean) own.invoke(null, "command", null)), "");

        away();

        // Nothing may persist the temporary one — that is the whole point.
        String src = Files.readString(Path.of(
            "src/main/java/com/schecks/almin/ActivityLog.java"));
        int at = src.indexOf("setTemporaryIncludeAdmins");
        int end = src.indexOf("public static boolean watched", at);
        ck("the override is never written to the config",
            at > 0 && end > at && !src.substring(at, end).contains("AlminConfig.save"),
            "it saves");

        String net = Files.readString(Path.of(
            "src/main/java/com/schecks/almin/ActivityNet.java"));
        ck("an admin change from the screen is still checked for trust",
            net.indexOf("TrustedOps.isTrusted") < net.indexOf("setAdmins(player"), "unchecked");
    }

    /**
     * The exclusion rule cannot be exercised without a live player, so it is
     * asserted against the source: both conditions present, and nothing that
     * would let an op through.
     */
    static void exclusion() throws Exception {
        String src = Files.readString(Path.of("src/main/java/com/schecks/almin/ActivityLog.java"));
        // Who counts as an admin lives in one place; watched() and the
        // /almin-command rule both go through it.
        int a = src.indexOf("private static boolean isAdmin(ServerPlayer");
        ck("there is one definition of an admin", a > 0, "isAdmin is gone");
        String admin = src.substring(a, src.indexOf("\n    }", a));
        ck("trusted UUIDs count as admins", admin.contains("TrustedOps.isTrusted"), admin);
        ck("ops count as admins", admin.contains("COMMANDS_MODERATOR"), admin);

        int i = src.indexOf("public static boolean watched");
        int end = src.indexOf("\n    }", i);
        String body = src.substring(i, end);
        ck("...and watched() excludes them", body.contains("return !isAdmin(player)"), body);
        ck("...unless admin tracking is on", body.contains("includeAdmins()"), body);

        // The /almin rule must not be reachable only through watched(): it has
        // to hold even when admin tracking is turned on.
        int r = src.indexOf("private static void record(ServerPlayer");
        String rec = src.substring(r, src.indexOf("\n    }", r));
        ck("an admin's own /almin commands are dropped before the admin check",
            rec.indexOf("ownCommand(") > 0
                && rec.indexOf("ownCommand(") < rec.indexOf("watched(player)"),
            rec);

        // Every recording path must reach the check. The two public entries
        // now share a private one, so what matters is that each either checks
        // itself or hands straight to something that does.
        for (String fn : new String[]{"private static void record(ServerPlayer",
                                      "public static void recordBlock(ServerPlayer"}) {
            int j = src.indexOf(fn);
            ck(fn + " exists", j >= 0, "missing");
            String b = src.substring(j, src.indexOf("\n    }", j));
            ck("  " + fn.split("\\(")[0].replace("private static void ", "")
                    .replace("public static void ", "") + " checks watched()",
                b.contains("watched(player)"), b);
        }
        for (String fn : new String[]{"public static void record(ServerPlayer",
                                      "public static void recordFolded(ServerPlayer"}) {
            int j = src.indexOf(fn);
            String b = src.substring(j, src.indexOf("\n    }", j));
            ck("  " + fn.split("\\(")[0].replace("public static void ", "")
                    + " delegates to the checked path",
                b.contains("record(player, action, detail,"), b);
        }

        // And the hooks must not record anyone directly.
        String hooks = Files.readString(Path.of("src/main/java/com/schecks/almin/events/ActivityHooks.java"));
        ck("hooks only record through ActivityLog",
            !hooks.contains("entries.add") && !hooks.contains("store("), "");
    }

    /**
     * Going away. The threshold is the whole of the logic, and it is worth
     * pinning: a player who has just stopped is not away, and one who stopped
     * a while ago is, whatever the log's own rules say about recording them.
     */
    static void away() throws Exception {
        Class<?> afk = Class.forName("com.schecks.almin.Afk");
        Class<?> still = Class.forName("com.schecks.almin.Afk$Still");
        java.lang.reflect.Constructor<?> mk = still.getDeclaredConstructor(
            long.class, String.class, int.class, int.class, int.class, boolean.class);
        mk.setAccessible(true);
        java.lang.reflect.Field seenF = afk.getDeclaredField("seen");
        seenF.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<java.util.UUID, Object> seen =
            (java.util.Map<java.util.UUID, Object>) seenF.get(null);
        java.lang.reflect.Method isAfk = afk.getMethod("isAfk", java.util.UUID.class);
        java.lang.reflect.Method clear = afk.getMethod("clear");

        AlminConfig.get().activityAfkSeconds = 20;
        java.util.UUID id = java.util.UUID.randomUUID();
        long now = System.currentTimeMillis();

        clear.invoke(null);
        ck("somebody nobody has looked at is not away",
            !((boolean) isAfk.invoke(null, id)), "");

        seen.put(id, mk.newInstance(now - 5_000, "overworld", 0, 64, 0, false));
        ck("five seconds still is not away", !((boolean) isAfk.invoke(null, id)), "");

        seen.put(id, mk.newInstance(now - 25_000, "overworld", 0, 64, 0, false));
        ck("twenty-five seconds still is away", (boolean) isAfk.invoke(null, id), "");

        // Someone the log never records gets no row, so the "logged" flag can
        // never be set for them; the clock still has to answer.
        seen.put(id, mk.newInstance(now - 25_000, "overworld", 0, 64, 0, false));
        ck("...even for a player who is never written down",
            (boolean) isAfk.invoke(null, id), "the flag was doing the work");

        // A row already written stays true regardless of the clock.
        seen.put(id, mk.newInstance(now, "overworld", 0, 64, 0, true));
        ck("once recorded away, still away", (boolean) isAfk.invoke(null, id), "");

        AlminConfig.get().activityAfkSeconds = 0;
        seen.put(id, mk.newInstance(now - 99_000, "overworld", 0, 64, 0, false));
        ck("with the setting off, nobody is ever away",
            !((boolean) isAfk.invoke(null, id)), "");
        AlminConfig.get().activityAfkSeconds = 20;

        clear.invoke(null);
        ck("clearing forgets everyone", seen.isEmpty(), seen.size() + " left");
        ck("online with no server is empty, not a crash",
            ((java.util.List<?>) afk.getMethod("online",
                net.minecraft.server.MinecraftServer.class)
                .invoke(null, new Object[]{null})).isEmpty(), "");

        AlminConfig.Key k = AlminConfig.keyByName("activity-afk-seconds");
        ck("the threshold is a setting", k != null && k.type == AlminConfig.Type.INT,
            k == null ? "missing" : k.type.name());
        ck("...and 0 is allowed, meaning never", k != null && k.min == 0, "");

        // The row must go through the ordinary path, so the ordinary rules
        // about who is recorded apply to it.
        String src = Files.readString(Path.of("src/main/java/com/schecks/almin/Afk.java"));
        ck("an away row goes through ActivityLog.record",
            src.contains("ActivityLog.record(p, \"afk\""), "it writes its own row");
    }
}
