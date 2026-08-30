import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/**
 * Snapshots stored as differences: that a difference is written when it should
 * be, that reading one gives back the whole picture, that a keyframe is not
 * deleted out from under something that depends on it, and that old filenames
 * still parse.
 *
 * Drives the real class with its private fields pointed at a temp directory,
 * because everything above capture() needs no world.
 */
public class SnapshotTests {
    static int failures = 0;
    static Class<?> WS;
    static Path dir;

    static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failures++;
    }

    public static void main(String[] a) throws Exception {
        dir = Files.createTempDirectory("almin-snap");
        WS = Class.forName("com.schecks.almin.WorldSnapshots");

        Field fdir = WS.getDeclaredField("dir"); fdir.setAccessible(true);
        fdir.set(null, dir);
        Field fw = WS.getDeclaredField("writer"); fw.setAccessible(true);
        // Same thread, so a write has happened by the time store() returns.
        fw.set(null, new java.util.concurrent.AbstractExecutorService() {
            public void shutdown() {}
            public List<Runnable> shutdownNow() { return List.of(); }
            public boolean isShutdown() { return false; }
            public boolean isTerminated() { return false; }
            public boolean awaitTermination(long t, java.util.concurrent.TimeUnit u) { return true; }
            public void execute(Runnable r) { r.run(); }
        });

        setKeep(500);
        long T = System.currentTimeMillis() - 600_000L;
        int size = 96, n = size * size;
        int[] one = new int[n];
        Random rnd = new Random(3);
        for (int i = 0; i < n; i++) one[i] = 0xFF000000 | rnd.nextInt(0xFFFFFF);

        store("overworld", T + 1000, 0, 0, size, 1, one, size);
        List<?> shots = all();
        check("first capture is a whole picture", shots.size() == 1 && whole(shots.get(0)));
        long wholeBytes = Files.size(dir.resolve(file(shots.get(0))));

        // A handful of blocks change.
        int[] two = one.clone();
        for (int i = 0; i < 400; i++) two[rnd.nextInt(n)] = 0xFF00FF00;
        store("overworld", T + 2000, 0, 0, size, 1, two, size);
        shots = all();
        Object delta = shots.get(1);
        check("second capture is a difference", !whole(delta) && base(delta) == T + 1000);
        long deltaBytes = Files.size(dir.resolve(file(delta)));
        check("the difference is far smaller (" + deltaBytes + " vs " + wholeBytes + ")",
            deltaBytes < wholeBytes / 4);

        byte[] png = (byte[]) read().invoke(null, delta);
        check("a difference reads back as a whole picture", png != null && same(png, two, size));

        // Most of the map changes: not worth a difference.
        int[] three = new int[n];
        for (int i = 0; i < n; i++) three[i] = 0xFF000000 | rnd.nextInt(0xFFFFFF);
        store("overworld", T + 3000, 0, 0, size, 1, three, size);
        check("a mostly-changed capture is a whole picture again", whole(all().get(2)));

        // Nothing changed at all: nothing written.
        int before = all().size();
        store("overworld", T + 4000, 0, 0, size, 1, three, size);
        check("an unchanged capture writes nothing", all().size() == before);

        // Moving the window enough writes a keyframe; a small move does not,
        // because the window is snapped to a grid.
        store("overworld", T + 5000, 0, 0, size, 1, one, size);
        check("moving back to a known frame is still tracked", all().size() == before + 1);

        // Pruning must not orphan a keyframe.
        Method prune = WS.getDeclaredMethod("prune"); prune.setAccessible(true);
        setKeep(2);
        prune.invoke(null);
        List<?> left = all();
        boolean orphan = false;
        for (Object s : left) {
            if (whole(s)) continue;
            boolean found = false;
            for (Object k : left) if (whole(k) && at(k) == base(s)) found = true;
            if (!found) orphan = true;
        }
        check("pruning never orphans a difference", !orphan);
        for (Object s : left) {
            check("  " + file(s) + " still on disk", Files.exists(dir.resolve(file(s))));
        }

        // Old six-field names still parse.
        Method parse = WS.getDeclaredMethod("parse", String.class); parse.setAccessible(true);
        Object old = parse.invoke(null, "the_nether@1700000000000@-64@128@384@1.png");
        check("a pre-difference filename still parses", old != null && whole(old) && at(old) == 1700000000000L);
        Object neu = parse.invoke(null, "overworld@1700000000001@-64@128@384@1@1700000000000.png");
        check("a difference filename parses", neu != null && !whole(neu) && base(neu) == 1700000000000L);

        // ---- thinning with age ----
        Method spacing = WS.getDeclaredMethod("spacingFor", long.class);
        spacing.setAccessible(true);
        long minute = 60_000L, hour = 3600_000L, day = 86_400_000L;
        check("the last half hour keeps everything",
            (Long) spacing.invoke(null, 10 * minute) == 0L);
        check("an hour back is one a minute",
            (Long) spacing.invoke(null, hour) == minute);
        check("a day back is four an hour",
            (Long) spacing.invoke(null, 20 * hour) == 15 * minute);
        check("a week back is one an hour",
            (Long) spacing.invoke(null, 5 * day) == hour);
        check("a month back is six a day",
            (Long) spacing.invoke(null, 20 * day) == 4 * hour);
        check("the curve only ever gets coarser", coarsens(spacing));

        // A month of pictures every half minute, thinned.
        setKeep(4000);
        setDays(30);
        clearShots();
        long now = System.currentTimeMillis();
        int made = 0;
        for (long age = 0; age < 30 * day; age += 30_000L) {
            addShot(now - age, "overworld", 0, 0);
            made++;
        }
        int madeAll = all().size();
        prune.invoke(null);
        int after = all().size();
        System.out.printf("       %,d pictures over 30 days thinned to %,d%n", madeAll, after);
        check("thinning keeps a small fraction of a month", after > 200 && after < 1200);
        check("  and does not simply drop the old ones", oldestAge(now) > 25 * day);
        check("  and keeps the recent ones untouched", within(now, 20 * minute) >= 38);

        // Ordering: what survives a slot is the newest picture in it.
        clearShots();
        long base = now - 10 * day;
        for (int i = 0; i < 12; i++) addShot(base + i * 10 * minute, "overworld", 0, 0);
        prune.invoke(null);
        List<?> left2 = all();
        boolean newestKept = false;
        for (Object o : left2) if (at(o) == base + 11 * 10 * minute) newestKept = true;
        check("the newest picture in a slot is the one kept", newestKept);

        // Two places watched at once must not thin each other away.
        clearShots();
        for (int i = 0; i < 6; i++) {
            addShot(base + i * 10 * minute, "overworld", 0, 0);
            addShot(base + i * 10 * minute, "overworld", 512, 512);
        }
        prune.invoke(null);
        boolean bothPlaces = false, otherPlace = false;
        for (Object o : all()) {
            if (minX(o) == 0) bothPlaces = true;
            if (minX(o) == 512) otherPlace = true;
        }
        check("two areas are thinned separately", bothPlaces && otherPlace);

        System.out.println(failures == 0 ? "SNAPSHOT OK" : "SNAPSHOT FAILURES: " + failures);
        if (failures > 0) System.exit(1);
    }

    static boolean coarsens(Method spacing) throws Exception {
        long last = -1;
        for (long age = 0; age < 40L * 86_400_000L; age += 600_000L) {
            long v = (Long) spacing.invoke(null, age);
            if (v < last) return false;
            last = v;
        }
        return true;
    }

    static void setDays(int days) throws Exception {
        Class<?> cfg = Class.forName("com.schecks.almin.AlminConfig");
        Object c = cfg.getMethod("get").invoke(null);
        cfg.getField("mapSnapshotDays").setInt(c, days);
        cfg.getField("mapSnapshotThin").setBoolean(c, true);
        Field inst = cfg.getDeclaredField("instance"); inst.setAccessible(true);
        inst.set(null, c);
    }

    @SuppressWarnings("unchecked")
    static void clearShots() throws Exception {
        Field f = WS.getDeclaredField("shots"); f.setAccessible(true);
        ((List<Object>) f.get(null)).clear();
    }

    @SuppressWarnings("unchecked")
    static void addShot(long at, String dim, int minX, int minZ) throws Exception {
        Class<?> S = Class.forName("com.schecks.almin.WorldSnapshots$Shot");
        Object shot = S.getConstructors()[0].newInstance(at, dim, minX, minZ, 384, 1, 0L,
            dim + "@" + at + "@" + minX + "@" + minZ + "@384@1@0.png");
        Field f = WS.getDeclaredField("shots"); f.setAccessible(true);
        ((List<Object>) f.get(null)).add(shot);
    }

    static long oldestAge(long now) throws Exception {
        long oldest = 0;
        for (Object o : all()) oldest = Math.max(oldest, now - at(o));
        return oldest;
    }

    static int within(long now, long window) throws Exception {
        int n = 0;
        for (Object o : all()) if (now - at(o) <= window) n++;
        return n;
    }

    static int minX(Object shot) throws Exception {
        return (Integer) shot.getClass().getMethod("minX").invoke(shot);
    }

    static void setKeep(int keep) throws Exception {
        Class<?> cfg = Class.forName("com.schecks.almin.AlminConfig");
        Object c = cfg.getMethod("get").invoke(null);
        cfg.getField("mapSnapshotKeep").setInt(c, keep);
        Field inst = cfg.getDeclaredField("instance"); inst.setAccessible(true);
        inst.set(null, c);
        // retention long enough that only the count cap bites
        cfg.getField("activityRetentionMinutes").setInt(c, 10000);
    }

    static void store(String dim, long at, int minX, int minZ, int blocks, int scale,
                      int[] px, int size) throws Exception {
        Method m = WS.getDeclaredMethod("store", String.class, long.class, int.class, int.class,
            int.class, int.class, int[].class, int[].class, int.class);
        m.setAccessible(true);
        int[] heights = new int[px.length];
        java.util.Arrays.fill(heights, 70);
        m.invoke(null, dim, at, minX, minZ, blocks, scale, px, heights, size);
    }

    static List<?> all() throws Exception { return (List<?>) WS.getMethod("all").invoke(null); }
    static Method read() throws Exception {
        Method m = WS.getMethod("read", Class.forName("com.schecks.almin.WorldSnapshots$Shot"));
        m.setAccessible(true); return m;
    }
    static boolean whole(Object shot) throws Exception {
        return (Boolean) shot.getClass().getMethod("whole").invoke(shot);
    }
    static long base(Object shot) throws Exception {
        return (Long) shot.getClass().getMethod("base").invoke(shot);
    }
    static long at(Object shot) throws Exception {
        return (Long) shot.getClass().getMethod("at").invoke(shot);
    }
    static String file(Object shot) throws Exception {
        return (String) shot.getClass().getMethod("file").invoke(shot);
    }

    static boolean same(byte[] png, int[] want, int size) throws Exception {
        Class<?> P = Class.forName("com.schecks.almin.Png");
        Method dec = P.getDeclaredMethod("decode", byte[].class); dec.setAccessible(true);
        Object img = dec.invoke(null, (Object) png);
        int[] got = (int[]) img.getClass().getMethod("argb").invoke(img);
        if (got.length != want.length) return false;
        for (int i = 0; i < got.length; i++) if (got[i] != want[i]) return false;
        return true;
    }
}
