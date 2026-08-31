import com.schecks.almin.PlayerTrackPoint;
import com.schecks.almin.PlayerTracks;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/**
 * Paths that survive a restart.
 *
 * They used to be memory-only, which read as a privacy measure and was really
 * an inconsistency — every activity row carries the coordinates it happened at
 * and is written to disk — and it meant a player who was offline had no path
 * at all, which is exactly when you want one.
 */
public class TrackDiskTests {
    static int failures = 0;
    static final UUID A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    static final UUID B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failures++;
    }

    public static void main(String[] a) throws Exception {
        Path dir = Files.createTempDirectory("almin-tracks");
        Path file = dir.resolve("tracks.json");
        setFile(file);
        setRetention(10080);

        long now = System.currentTimeMillis();
        for (int i = 0; i < 30; i++) {
            record(A, "Steve", now - (30 - i) * 60_000L, "overworld", i * 12, 64, i * 3);
        }
        record(B, "Alex", now - 90_000L, "the_nether", 5, 40, 8);

        PlayerTracks.save();
        check("a file is written", Files.exists(file));
        long size = Files.size(file);
        check("and it is not enormous (" + size + " bytes for 31 points)", size < 4096);

        // What a restart does.
        clearMemory();
        check("memory really is empty", PlayerTracks.tracked().isEmpty());
        Method load = PlayerTracks.class.getDeclaredMethod("load");
        load.setAccessible(true);
        load.invoke(null);

        check("both players come back", PlayerTracks.tracked().size() == 2);
        List<PlayerTrackPoint> steve = PlayerTracks.of("Steve");
        check("every point comes back", steve.size() == 30);
        check("in the order they were recorded",
            steve.get(0).at() < steve.get(29).at());
        check("with their coordinates intact",
            steve.get(29).x() == 29 * 12 && steve.get(29).z() == 29 * 3);
        check("and their dimension", PlayerTracks.of("Alex").get(0).dim().equals("the_nether"));
        check("a name still finds a path", !PlayerTracks.of("steve").isEmpty());
        check("the uuid does too", !PlayerTracks.of(A).isEmpty());

        // Expiry still applies to what was loaded.
        setRetention(5);
        Method expire = PlayerTracks.class.getDeclaredMethod("expire", long.class);
        expire.setAccessible(true);
        expire.invoke(null, now - 5 * 60_000L);
        check("points past the window are dropped on load",
            PlayerTracks.of("Steve").size() < 30);
        check("...and a player with nothing left goes with them",
            PlayerTracks.of("Steve").stream().allMatch(p -> p.at() >= now - 5 * 60_000L));

        // Clearing the log takes the file too.
        PlayerTracks.clear();
        check("clearing deletes the file", !Files.exists(file));
        check("...and memory", PlayerTracks.tracked().isEmpty());

        // A corrupt file is not a failed start.
        Files.writeString(file, "{ this is not json");
        clearMemory();
        load.invoke(null);
        check("a corrupt file is survivable", PlayerTracks.tracked().isEmpty());

        System.out.println(failures == 0 ? "TRACK DISK OK" : "TRACK DISK FAILURES: " + failures);
        if (failures > 0) System.exit(1);
    }

    static void record(UUID id, String name, long at, String dim, int x, int y, int z)
            throws Exception {
        Method m = PlayerTracks.class.getDeclaredMethod("record", UUID.class, String.class,
            PlayerTrackPoint.class);
        m.setAccessible(true);
        m.invoke(null, id, name, new PlayerTrackPoint(at, dim, x, y, z));
    }

    static void setFile(Path f) throws Exception {
        Field field = PlayerTracks.class.getDeclaredField("file");
        field.setAccessible(true);
        field.set(null, f);
    }

    static void clearMemory() throws Exception {
        for (String name : new String[]{"tracks", "names"}) {
            Field f = PlayerTracks.class.getDeclaredField(name);
            f.setAccessible(true);
            ((Map<?, ?>) f.get(null)).clear();
        }
    }

    static void setRetention(int minutes) throws Exception {
        Class<?> cfg = Class.forName("com.schecks.almin.AlminConfig");
        Object c = cfg.getMethod("get").invoke(null);
        cfg.getField("activityRetentionMinutes").setInt(c, minutes);
        Field inst = cfg.getDeclaredField("instance"); inst.setAccessible(true);
        inst.set(null, c);
    }
}
