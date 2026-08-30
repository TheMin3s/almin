import com.schecks.almin.ActivityLog;
import com.schecks.almin.Episodes;
import com.schecks.almin.PlayerTracks;

import java.util.*;

/**
 * What the log meant.
 *
 * Builds runs of rows shaped like the real thing — a tree is logs in a narrow
 * column, a shaft is one column going down, a tunnel is a line two blocks high
 * — and asks Episodes what it made of them. These are the sentences the panel
 * shows and the model is given, so a wrong one is wrong twice.
 */
public class EpisodeTests {
    static int failures = 0;
    static long T = System.currentTimeMillis() - 3600_000L;
    static long clock;

    static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failures++;
    }

    static ActivityLog.Entry row(String who, String action, String detail,
                                 int x, int y, int z, int count) {
        clock += 1500;
        return new ActivityLog.Entry(clock, who, "uuid-" + who, action, detail,
            "overworld", x, y, z, count);
    }

    static Episodes.Episode only(List<ActivityLog.Entry> rows) {
        List<Episodes.Episode> out = Episodes.of(rows);
        return out.isEmpty() ? null : out.get(0);
    }

    static Episodes.Episode of(List<ActivityLog.Entry> rows, String kind) {
        for (Episodes.Episode e : Episodes.of(rows)) if (e.kind().equals(kind)) return e;
        return null;
    }

    public static void main(String[] a) {
        // ---- a tree ----
        clock = T;
        List<ActivityLog.Entry> tree = new ArrayList<>();
        for (int y = 64; y < 71; y++) tree.add(row("Steve", "break", "Oak Log", 100, y, 200, 1));
        for (int i = 0; i < 6; i++) tree.add(row("Steve", "break", "Oak Leaves", 101, 71, 201, 1));
        Episodes.Episode e = only(tree);
        check("logs in a narrow column are a tree", e != null && e.kind().equals("tree"));
        check("  and it says so: " + (e == null ? "-" : e.headline()),
            e != null && e.headline().toLowerCase().contains("chopped"));

        // ---- a shaft ----
        clock = T;
        List<ActivityLog.Entry> shaft = new ArrayList<>();
        for (int y = 64; y > 20; y--) shaft.add(row("Steve", "break", "Stone", 50, y, 50, 1));
        e = only(shaft);
        check("one column going down is a shaft", e != null && e.kind().equals("shaft"));
        check("  and it names the depths: " + (e == null ? "-" : e.headline()),
            e != null && e.headline().contains("y 64") && e.headline().contains("y 21"));

        // ---- a tunnel ----
        clock = T;
        List<ActivityLog.Entry> tunnel = new ArrayList<>();
        for (int x = 0; x < 60; x++) {
            tunnel.add(row("Steve", "break", "Deepslate", x, 11, 300, 1));
            tunnel.add(row("Steve", "break", "Deepslate", x, 12, 300, 1));
        }
        e = of(tunnel, "tunnel");
        check("a long line two blocks high is a tunnel", e != null);
        check("  and it says how far and which way: " + (e == null ? "-" : e.headline()),
            e != null && e.headline().contains("east-west") && e.headline().contains("y 11"));

        // ---- a build ----
        clock = T;
        List<ActivityLog.Entry> build = new ArrayList<>();
        for (int y = 64; y < 71; y++)
            for (int x = 0; x < 6; x++)
                build.add(row("Alex", "place", "Oak Planks", 400 + x, y, 400, 1));
        e = only(build);
        check("placements inside a box with height are a build", e != null && e.kind().equals("build"));
        check("  and it says how big and of what: " + (e == null ? "-" : e.headline()),
            e != null && e.headline().contains("high") && e.headline().contains("Oak Planks"));

        // A nearby click/player event and one stray placement used to become
        // part of the build's box, turning this 4x2 wall into "45 across and
        // 120 high". Only the connected block heap is work geometry.
        clock = T;
        List<ActivityLog.Entry> small = new ArrayList<>();
        for (int y = 64; y <= 65; y++)
            for (int x = 100; x < 104; x++)
                small.add(row("Alex", "place", "Oak Planks", x, y, 100, 1));
        small.add(row("Alex", "place", "Oak Planks", 145, 176, 100, 1));
        for (int i = 0; i < 4; i++)
            small.add(row("Alex", "interact", "Chest", 140, 176, 100, 1));
        e = only(small);
        check("unrelated coordinates do not inflate a small build",
            e != null && e.kind().equals("build") && e.spanXZ() == 3 && e.spanY() == 1
                && e.x() >= 101 && e.x() <= 102 && e.y() == 64);
        check("block dimensions count blocks rather than coordinate gaps: "
                + (e == null ? "-" : e.headline()),
            e != null && e.headline().contains("4 across and 2 high"));

        // ---- a floor is not a building ----
        // Twenty by twenty at one height. It used to be twenty by one, which
        // is not a floor at all — it is a path, and is now classified as one.
        clock = T;
        List<ActivityLog.Entry> floor = new ArrayList<>();
        for (int x = 0; x < 20; x++)
            for (int z = 0; z < 20; z++)
                floor.add(row("Alex", "place", "Stone Bricks", 500 + x, 64, 500 + z, 1));
        e = only(floor);
        check("one layer of placements is a floor, not a building",
            e != null && e.kind().equals("build") && e.headline().contains("floor"));

        // ---- a death wins over everything else in the run ----
        clock = T;
        List<ActivityLog.Entry> died = new ArrayList<>();
        for (int i = 0; i < 12; i++) died.add(row("Steve", "break", "Stone", 60, 30, 60, 1));
        died.add(row("Steve", "death", "Steve was blown up by a Creeper", 60, 30, 60, 1));
        e = only(died);
        check("a run somebody died in is about the death", e != null && e.kind().equals("death"));
        check("  and it keeps the game's own words: " + (e == null ? "-" : e.headline()),
            e != null && e.headline().contains("Creeper"));

        // ---- a fight with a mob is not player-versus-player ----
        clock = T;
        List<ActivityLog.Entry> fight = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            fight.add(row("Steve", "attack", "Zombie", 70, 64, 70, 1));
            fight.add(row("Steve", "hurt", "Zombie  3 damage", 70, 64, 70, 1));
        }
        e = only(fight);
        check("swings and hits are a fight", e != null && e.kind().equals("fight"));
        check("  and a mob is not called a player", e != null && !e.kind().equals("pvp"));

        // ---- runs are cut by time and by distance ----
        clock = T;
        List<ActivityLog.Entry> two = new ArrayList<>();
        for (int i = 0; i < 8; i++) two.add(row("Steve", "break", "Stone", 10, 40, 10, 1));
        clock += 20 * 60_000L;                       // twenty minutes later
        for (int i = 0; i < 8; i++) two.add(row("Steve", "break", "Stone", 12, 40, 12, 1));
        check("a long pause starts a new episode", Episodes.of(two).size() == 2);

        clock = T;
        List<ActivityLog.Entry> apart = new ArrayList<>();
        for (int i = 0; i < 8; i++) apart.add(row("Steve", "break", "Stone", 10, 40, 10, 1));
        for (int i = 0; i < 8; i++) apart.add(row("Steve", "break", "Stone", 900, 40, 900, 1));
        check("somewhere else starts a new episode", Episodes.of(apart).size() == 2);

        // ---- folded rows count as their count ----
        clock = T;
        List<ActivityLog.Entry> folded = new ArrayList<>();
        folded.add(row("Steve", "break", "Stone", 10, 12, 10, 30));
        folded.add(row("Steve", "break", "Stone", 11, 12, 11, 30));
        folded.add(row("Steve", "break", "Stone", 12, 12, 12, 30));
        folded.add(row("Steve", "break", "Stone", 13, 12, 13, 30));
        e = only(folded);
        check("a folded row is worth its count, not one",
            e != null && e.headline().contains("120"));

        // ---- noise is not an episode ----
        clock = T;
        check("two stray rows are not an episode",
            Episodes.of(List.of(row("Steve", "item", "Bread", 1, 64, 1, 1),
                                row("Steve", "item", "Bread", 1, 64, 1, 1))).isEmpty());

        // ---- movement ----
        long m = T;
        List<PlayerTracks.Point> walk = new ArrayList<>();
        for (int i = 0; i <= 30; i++) walk.add(new PlayerTracks.Point(m + i * 5000L,
            "overworld", i * 30, 64, 0));
        List<Episodes.Episode> moves = Episodes.ofMovement(Map.of("Steve", walk));
        check("a long straight walk is travel",
            moves.stream().anyMatch(x -> x.kind().equals("travel")));

        List<PlayerTracks.Point> pacing = new ArrayList<>();
        for (int i = 0; i <= 60; i++) pacing.add(new PlayerTracks.Point(m + i * 5000L,
            "overworld", (i % 2 == 0) ? 0 : 20, 64, 0));
        moves = Episodes.ofMovement(Map.of("Alex", pacing));
        check("walking a long way without getting anywhere is pacing",
            moves.stream().anyMatch(x -> x.kind().equals("pace")));
        for (Episodes.Episode p : moves) {
            if (p.kind().equals("pace")) System.out.println("       " + p.headline());
        }

        // ---- the new shapes ----
        clock = T;
        List<ActivityLog.Entry> lava = new ArrayList<>();
        for (int i = 0; i < 4; i++) lava.add(row("Alex", "place", "Lava", 300, 64, 300, 1));
        e = only(lava);
        check("lava going down is called out on its own", e != null && e.kind().equals("hazard"));
        check("  and it outranks everything: " + (e == null ? "-" : e.weight()),
            e != null && e.weight() > 92);

        // Even with a death in the same run, which otherwise wins.
        clock = T;
        List<ActivityLog.Entry> both = new ArrayList<>(lava);
        both.add(row("Alex", "death", "Alex burned to death", 300, 64, 300, 1));
        check("  and still wins when somebody also died",
            only(both) != null && only(both).kind().equals("hazard"));

        // A lava bucket lands as a 'use', not a 'place'.
        clock = T;
        List<ActivityLog.Entry> bucket = new ArrayList<>();
        for (int i = 0; i < 5; i++)
            bucket.add(row("Alex", "use", "Stone with Lava Bucket", 300, 64, 300, 1));
        check("  including lava poured from a bucket",
            only(bucket) != null && only(bucket).kind().equals("hazard"));

        // A campfire is not an alarm.
        clock = T;
        List<ActivityLog.Entry> camp = new ArrayList<>();
        for (int i = 0; i < 12; i++) camp.add(row("Alex", "place", "Campfire", 300 + i, 64, 300, 1));
        check("  but a campfire is not one",
            only(camp) != null && !only(camp).kind().equals("hazard"));

        clock = T;
        List<ActivityLog.Entry> farm = new ArrayList<>();
        for (int i = 0; i < 20; i++) farm.add(row("Steve", "kill", "Zombie", 400, 30, 400, 1));
        e = only(farm);
        check("twenty mobs killed in one spot is a grinder",
            e != null && e.kind().equals("grind"));
        check("  and it says what of: " + (e == null ? "-" : e.headline()),
            e != null && e.headline().contains("Zombie"));

        clock = T;
        List<ActivityLog.Entry> tower = new ArrayList<>();
        for (int y = 64; y < 80; y++) tower.add(row("Steve", "place", "Dirt", 500, y, 500, 1));
        e = only(tower);
        check("one column going up is a tower", e != null && e.kind().equals("tower"));
        check("  and it names the heights: " + (e == null ? "-" : e.headline()),
            e != null && e.headline().contains("y 64") && e.headline().contains("y 79"));

        clock = T;
        List<ActivityLog.Entry> bridge = new ArrayList<>();
        for (int x = 0; x < 30; x++) bridge.add(row("Steve", "place", "Stone", 600 + x, 70, 600, 1));
        e = only(bridge);
        check("a line of placed blocks is a path", e != null && e.kind().equals("bridge"));

        clock = T;
        List<ActivityLog.Entry> wire = new ArrayList<>();
        for (int i = 0; i < 5; i++) wire.add(row("Steve", "place", "Redstone Dust", 700 + i, 64, 700, 1));
        for (int i = 0; i < 4; i++) wire.add(row("Steve", "place", "Repeater", 700 + i, 64, 701, 1));
        e = only(wire);
        check("redstone parts are wiring, not building", e != null && e.kind().equals("redstone"));

        clock = T;
        List<ActivityLog.Entry> made = new ArrayList<>();
        for (int i = 0; i < 10; i++) made.add(row("Steve", "craft", "Stick", 800, 64, 800, 1));
        check("crafting is its own stretch",
            only(made) != null && only(made).kind().equals("craft"));

        clock = T;
        List<ActivityLog.Entry> traded = new ArrayList<>();
        for (int i = 0; i < 6; i++) traded.add(row("Steve", "trade", "Emerald", 810, 64, 810, 1));
        check("trading is too", only(traded) != null && only(traded).kind().equals("trade"));

        clock = T;
        List<ActivityLog.Entry> wrote = new ArrayList<>();
        wrote.add(row("Steve", "sign", "keep out / this means you", 820, 64, 820, 1));
        for (int i = 0; i < 4; i++) wrote.add(row("Steve", "place", "Oak Sign", 820, 64, 820, 1));
        e = only(wrote);
        check("a sign is a stretch worth reading", e != null && e.kind().equals("sign"));
        check("  and it quotes it: " + (e == null ? "-" : e.headline()),
            e != null && e.headline().contains("keep out"));

        clock = T;
        List<ActivityLog.Entry> slept = new ArrayList<>();
        slept.add(row("Steve", "sleep", "went to bed", 830, 64, 830, 1));
        for (int i = 0; i < 4; i++) slept.add(row("Steve", "interact", "Bed", 830, 64, 830, 1));
        check("sleeping says where home is",
            of(slept, "camp") != null || (only(slept) != null && only(slept).kind().equals("camp")));

        clock = T;
        List<ActivityLog.Entry> dumped = new ArrayList<>();
        for (int i = 0; i < 8; i++) dumped.add(row("Steve", "drop", "64× Cobblestone", 840, 64, 840, 1));
        e = only(dumped);
        check("an inventory on the floor is a stretch of its own",
            e != null && e.kind().equals("dump"));

        // ---- movement the rules did not have before ----
        long m2 = T;
        List<PlayerTracks.Point> flying = new ArrayList<>();
        for (int i = 0; i <= 40; i++) {
            flying.add(new PlayerTracks.Point(m2 + i * 1000L, "overworld", i * 30, 120, 0));
        }
        List<Episodes.Episode> fast = Episodes.ofMovement(Map.of("Alex", flying));
        check("moving faster than anyone runs is called out",
            fast.stream().anyMatch(x -> x.kind().equals("flight")));

        List<PlayerTracks.Point> wandering = new ArrayList<>();
        for (int i = 0; i <= 80; i++) {
            // A wide loop that comes back: far from home, not far from where
            // it started.
            double turn = i * Math.PI / 20;
            wandering.add(new PlayerTracks.Point(m2 + i * 20_000L, "overworld",
                (int) Math.round(Math.cos(turn) * 140), 64,
                (int) Math.round(Math.sin(turn) * 140)));
        }
        List<Episodes.Episode> around = Episodes.ofMovement(Map.of("Alex", wandering));
        check("a wide loop that comes home is wandering, not pacing",
            around.stream().anyMatch(x -> x.kind().equals("roam")));
        for (Episodes.Episode x : around) {
            if (x.kind().equals("roam")) System.out.println("       " + x.headline());
        }

        // ---- the most notable comes first ----
        clock = T;
        List<ActivityLog.Entry> mixed = new ArrayList<>(died);
        clock = T;
        for (int i = 0; i < 10; i++) mixed.add(row("Alex", "item", "Bread", 800, 64, 800, 1));
        List<Episodes.Episode> ordered = Episodes.of(mixed);
        check("the death is at the top of the list",
            !ordered.isEmpty() && ordered.get(0).kind().equals("death"));

        System.out.println(failures == 0 ? "EPISODES OK" : "EPISODE FAILURES: " + failures);
        if (failures > 0) System.exit(1);
    }
}
