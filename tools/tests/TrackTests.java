import com.schecks.almin.AlminConfig;
import com.schecks.almin.PlayerTracks;

import java.lang.reflect.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The movement track behind the map: what it keeps, what it refuses to keep,
 * and that it cannot grow without bound.
 */
public class TrackTests {
    static int fail = 0;
    static void ck(String w, boolean ok, String d) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + w + (ok ? "" : "  -> " + d));
        if (!ok) fail++;
    }

    static Method record, expire;
    static UUID steve = UUID.nameUUIDFromBytes("steve".getBytes());
    static UUID alex = UUID.nameUUIDFromBytes("alex".getBytes());

    public static void main(String[] a) throws Exception {
        Constructor<AlminConfig> cc = AlminConfig.class.getDeclaredConstructor();
        cc.setAccessible(true);
        Field inst = AlminConfig.class.getDeclaredField("instance");
        inst.setAccessible(true); inst.set(null, cc.newInstance());

        record = PlayerTracks.class.getDeclaredMethod("record",
            UUID.class, String.class, PlayerTracks.Point.class);
        record.setAccessible(true);
        expire = PlayerTracks.class.getDeclaredMethod("expire", long.class);
        expire.setAccessible(true);

        sampling();
        standingStill();
        dimensions();
        cap();
        expiry();
        lookup();
        everyone();
        settings();

        System.out.println(fail == 0 ? "\nTRACK TESTS PASSED" : "\n" + fail + " FAILED");
        System.exit(fail == 0 ? 0 : 1);
    }

    static void put(UUID id, String name, long at, String dim, int x, int z) throws Exception {
        record.invoke(null, id, name, new PlayerTracks.Point(at, dim, x, 64, z));
    }

    static void sampling() throws Exception {
        PlayerTracks.clear();
        long t = System.currentTimeMillis();
        put(steve, "Steve", t, "overworld", 0, 0);
        put(steve, "Steve", t + 1000, "overworld", 100, 0);
        put(steve, "Steve", t + 2000, "overworld", 200, 0);
        List<PlayerTracks.Point> track = PlayerTracks.of(steve);
        ck("moving is recorded", track.size() == 3, String.valueOf(track.size()));
        ck("...oldest first", track.get(0).x() == 0 && track.get(2).x() == 200,
            track.get(0).x() + ".." + track.get(2).x());
        ck("...with the coordinates intact", track.get(1).x() == 100 && track.get(1).z() == 0,
            track.get(1).toString());
    }

    /** A player idling must not fill the track with the same point. */
    static void standingStill() throws Exception {
        PlayerTracks.clear();
        long t = System.currentTimeMillis();
        put(steve, "Steve", t, "overworld", 500, 500);
        for (int i = 1; i <= 100; i++) {
            put(steve, "Steve", t + i * 1000L, "overworld", 500, 500);
        }
        ck("standing still adds nothing after the first point",
            PlayerTracks.of(steve).size() == 1, String.valueOf(PlayerTracks.of(steve).size()));

        // A small shuffle is still standing still.
        put(steve, "Steve", t + 200_000, "overworld", 503, 502);
        ck("a step or two is not travel", PlayerTracks.of(steve).size() == 1,
            String.valueOf(PlayerTracks.of(steve).size()));
        put(steve, "Steve", t + 300_000, "overworld", 520, 500);
        ck("...but twenty blocks is", PlayerTracks.of(steve).size() == 2,
            String.valueOf(PlayerTracks.of(steve).size()));
    }

    /** Nether and overworld coordinates are different places at the same numbers. */
    static void dimensions() throws Exception {
        PlayerTracks.clear();
        long t = System.currentTimeMillis();
        put(steve, "Steve", t, "overworld", 100, 100);
        put(steve, "Steve", t + 1000, "the_nether", 100, 100);
        ck("the same numbers in another dimension is a new point",
            PlayerTracks.of(steve).size() == 2, String.valueOf(PlayerTracks.of(steve).size()));
        ck("...and the dimension travels with it",
            PlayerTracks.of(steve).get(1).dim().equals("the_nether"),
            PlayerTracks.of(steve).get(1).dim());
    }

    static void cap() throws Exception {
        PlayerTracks.clear();
        long t = System.currentTimeMillis();
        for (int i = 0; i < 3000; i++) put(steve, "Steve", t + i * 1000L, "overworld", i * 10, 0);
        int size = PlayerTracks.of(steve).size();
        ck("a track is capped", size == 2000, String.valueOf(size));
        ck("...dropping the oldest", PlayerTracks.of(steve).get(0).x() == 1000 * 10,
            String.valueOf(PlayerTracks.of(steve).get(0).x()));
    }

    /** The whole point: a record of someone's movements does not last. */
    static void expiry() throws Exception {
        PlayerTracks.clear();
        long now = System.currentTimeMillis();
        put(steve, "Steve", now - 7_200_000, "overworld", 0, 0);
        put(steve, "Steve", now - 3_600_000, "overworld", 500, 0);
        put(steve, "Steve", now, "overworld", 1000, 0);
        expire.invoke(null, now - 5_400_000);
        List<PlayerTracks.Point> left = PlayerTracks.of(steve);
        ck("old points are dropped", left.size() == 2, String.valueOf(left.size()));
        ck("...the oldest first", left.get(0).x() == 500, String.valueOf(left.get(0).x()));

        expire.invoke(null, now + 1000);
        ck("expiring everything leaves no track", PlayerTracks.of(steve).isEmpty(),
            String.valueOf(PlayerTracks.of(steve).size()));
        ck("...and the player drops off the list",
            !PlayerTracks.tracked().containsKey("Steve"), PlayerTracks.tracked().toString());
    }

    static void lookup() throws Exception {
        PlayerTracks.clear();
        long t = System.currentTimeMillis();
        put(steve, "Steve", t, "overworld", 0, 0);
        put(alex, "Alex", t, "overworld", 900, 900);
        Map<String, Integer> all = PlayerTracks.tracked();
        ck("both players are listed", all.size() == 2, all.toString());
        ck("by name, case-insensitively", PlayerTracks.of("steve").size() == 1,
            String.valueOf(PlayerTracks.of("steve").size()));
        ck("an unknown name gives nothing", PlayerTracks.of("nobody").isEmpty(), "");
        ck("tracks are per player",
            PlayerTracks.of("Alex").get(0).x() == 900,
            String.valueOf(PlayerTracks.of("Alex").get(0).x()));

        PlayerTracks.clear();
        ck("clear() empties everything", PlayerTracks.tracked().isEmpty(), "");
    }

    /**
     * Everyone's path at once, thinned to fit one packet.
     *
     * <p>The in-game map has to receive this over the network, and full
     * tracks for a busy server are far past what one packet should carry.
     * Thinning takes every nth point rather than the most recent ones: a
     * shorter path over the whole period is a truer picture than a complete
     * path over the last five minutes.
     */
    static void everyone() throws Exception {
        PlayerTracks.clear();
        long t = System.currentTimeMillis();
        for (int i = 0; i < 900; i++) put(steve, "Steve", t + i * 1000L, "overworld", i * 20, 0);
        for (int i = 0; i < 900; i++) put(alex, "Alex", t + i * 1000L, "overworld", 0, i * 20);

        Map<String, List<PlayerTracks.Point>> all = PlayerTracks.everyone(200);
        ck("everyone is included", all.size() == 2, all.keySet().toString());
        int total = 0;
        for (List<PlayerTracks.Point> pts : all.values()) total += pts.size();
        ck("the budget is respected", total <= 200 + all.size(), String.valueOf(total));
        ck("...and it is not simply empty", total > 100, String.valueOf(total));

        List<PlayerTracks.Point> thin = all.get("Steve");
        List<PlayerTracks.Point> full = PlayerTracks.of("Steve");
        ck("the path still spans the whole period, not just the end",
            thin.get(0).at() == full.get(0).at(), thin.get(0).at() + " vs " + full.get(0).at());
        ck("...and still ends where the player actually is",
            thin.get(thin.size() - 1).x() == full.get(full.size() - 1).x(),
            thin.get(thin.size() - 1).x() + " vs " + full.get(full.size() - 1).x());

        // A budget larger than the data must not invent or drop anything.
        Map<String, List<PlayerTracks.Point>> whole = PlayerTracks.everyone(100000);
        ck("a generous budget returns the lot",
            whole.get("Steve").size() == full.size(),
            whole.get("Steve").size() + " vs " + full.size());

        PlayerTracks.clear();
        ck("no tracks is an empty answer, not a crash",
            PlayerTracks.everyone(500).isEmpty(), "");
    }

    static void settings() throws Exception {
        AlminConfig.Key k = AlminConfig.keyByName("activity-track-seconds");
        ck("the sample interval is a setting", k != null, "missing");
        ck("...and can be turned off with 0", k != null && k.min == 0, String.valueOf(k.min));
        ck("...and is bounded", k != null && k.max == 300, String.valueOf(k.max));
        Constructor<AlminConfig> cc = AlminConfig.class.getDeclaredConstructor();
        cc.setAccessible(true);
        ck("it defaults to a few seconds", "5".equals(k.display(cc.newInstance())),
            k.display(cc.newInstance()));
    }
}
