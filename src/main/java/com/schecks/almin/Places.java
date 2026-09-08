package com.schecks.almin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The places on a server, worked out from what keeps happening in them.
 *
 * <p>{@link Episodes} answers "what happened" — one player, one stretch of
 * time, one piece of work. This answers a different question, and the one
 * people actually ask of a map: "what is <em>there</em>". A base is not an
 * event. Nobody builds a base; they put a chest down on Tuesday, sleep there
 * on Wednesday, come back on Friday with cobblestone, and after a fortnight
 * of that there is obviously a base at those coordinates — but no single row
 * in the log says so, and neither does any single episode.
 *
 * <p>What says so is <em>recurrence</em>. So the test here is not how much
 * happened somewhere but how many separate times somebody came back to it. A
 * thousand blocks broken in one afternoon is an episode and belongs on the
 * timeline; forty blocks broken on nine different evenings is a place and
 * belongs on the map. That single rule is what keeps this from marking every
 * hole anyone ever dug.
 *
 * <h3>How</h3>
 * Rows are dropped into {@value #CELL}-block cells, cells with enough in them
 * are joined to their neighbours, and each resulting cluster is measured:
 * which actions, how many people, how many visits, how many days, how deep.
 * The classification is then a short ordered list of rules over those counts —
 * a bed or chests and building means a base, deep breaking with almost no
 * building means a mine, crops planted and cut again means a farm.
 *
 * <h3>What it cannot do</h3>
 * The same limit as {@link Episodes}: block names arrive already translated,
 * so the material rules read English. On a server running in another language
 * a farm is still a place with a lot of recurrence in it, it is just called a
 * worked spot instead. Nothing here knows intent either — a base somebody
 * abandoned a month ago still reads as a base until the log rolls past it.
 *
 * <h3>Threading</h3>
 * Pure: it is handed rows and returns a list. The caller does the caching.
 */
public final class Places {

    /**
     * One place, and the evidence for calling it one.
     *
     * @param kind    machine-readable class: {@code base}, {@code mine}, …
     * @param headline the evidence, as a sentence fragment
     * @param player  whoever did most of it, or empty when it is shared
     * @param people  how many different players were involved
     * @param visits  separate arrivals, which is the whole test
     * @param days    separate calendar days, on the server's clock
     * @param weight  0–100, how much it deserves a mark on the map
     */
    public record Place(String kind, String headline, String dim,
                        int x, int y, int z, int radius,
                        long from, long to, int events, int visits, int days,
                        int weight, String player, String uuid, int people) {}

    /** Blocks across one bucket. About a small building. */
    private static final int CELL = 48;

    /** A cell with less than this in it cannot join a cluster. */
    private static final int CELL_FLOOR = 4;

    /** A gap this long means they went away and came back. */
    private static final long VISIT_GAP = 25 * 60_000L;

    /** Below these, it is something that happened rather than somewhere. */
    private static final int MIN_EVENTS = 24;
    private static final int MIN_VISITS = 3;

    /** A worked spot has to be well above the bar to earn a mark of its own. */
    private static final int SPOT_WEIGHT = 46;

    private static final int MAX_PLACES = 40;

    /** Past this the mark stops being a place and starts being a region. */
    private static final int MAX_RADIUS = 160;

    /**
     * How far from its busiest cell a place may reach, in cells.
     *
     * <p>Without a limit, a flood fill is only as good as the emptiest cell it
     * is allowed through — and somebody who strip-mines a corridor between two
     * bases leaves a line of cells with a handful of broken blocks in each,
     * which is enough to conduct. Both bases then became one place with its
     * mark halfway between them, on top of nothing. Two cells out is about a
     * quarter of a kilometre across, which is larger than anything anyone
     * builds and small enough that it cannot walk.
     */
    private static final int SPREAD = 2;

    /**
     * Rows that mean somebody was doing something where they stood.
     *
     * <p>Chat and commands are left out on purpose: they happen wherever
     * somebody happens to be standing, so counting them would smear a place
     * across everywhere anyone has ever talked. Joining and leaving are left
     * out of the count for the same reason but kept as evidence — where people
     * log off is a genuinely strong hint about where they live, it is just not
     * work.
     */
    private static final Set<String> WORK = Set.of(
        "place", "break", "container", "craft", "enchant", "sleep", "interact",
        "use", "item", "drop", "trade", "sign", "portal", "kill", "death", "attack");

    /** Things you plant and cut again. */
    private static final String[] CROPS = {
        "wheat", "carrot", "potato", "beetroot", "melon", "pumpkin", "sugar cane",
        "bamboo", "kelp", "cocoa", "nether wart", "sweet berr", "cactus", "hay"};

    private Places() {}

    /**
     * The places in these rows, best first.
     *
     * @param rows any order; only rows carrying a dimension are considered.
     *             Filter for what the reader is allowed to see <em>before</em>
     *             calling, so a place is never built out of somebody else's
     *             activity and then shown without their name on it.
     */
    public static List<Place> of(List<ActivityEntry> rows) {
        Map<String, Cell> cells = new HashMap<>();
        for (ActivityEntry e : rows) {
            if (e.dim() == null || e.dim().isEmpty()) continue;
            String action = e.action() == null ? "" : e.action();
            boolean work = WORK.contains(action);
            boolean edge = action.equals("join") || action.equals("leave");
            if (!work && !edge) continue;
            cells.computeIfAbsent(keyOf(e), k -> new Cell()).add(e, work);
        }

        // Busiest cell first, so a place grows outwards from its own centre
        // rather than from whichever cell the map happened to hand over first
        // — which also makes the answer the same every time it is asked.
        List<String> seeds = new ArrayList<>(cells.keySet());
        seeds.sort(Comparator.comparingInt((String k) -> -cells.get(k).work).thenComparing(k -> k));

        List<Place> out = new ArrayList<>();
        Set<String> done = new HashSet<>();
        for (String seed : seeds) {
            if (done.contains(seed)) continue;
            if (cells.get(seed).work < CELL_FLOOR) continue;
            Cluster c = spread(seed, cells, done);
            Place place = classify(c);
            if (place != null) out.add(place);
        }
        out.sort(Comparator.comparingInt(Place::weight).reversed()
            .thenComparing(Comparator.comparingLong(Place::to).reversed()));
        return out.size() > MAX_PLACES ? new ArrayList<>(out.subList(0, MAX_PLACES)) : out;
    }

    // ---------- buckets ----------

    private static String keyOf(ActivityEntry e) {
        return e.dim() + "|" + Math.floorDiv(e.x(), CELL) + "|" + Math.floorDiv(e.z(), CELL);
    }

    private static String keyOf(String dim, int cx, int cz) {
        return dim + "|" + cx + "|" + cz;
    }

    /** One bucket's worth of rows, before anything has been decided about it. */
    private static final class Cell {
        final List<ActivityEntry> rows = new ArrayList<>();
        int work;

        void add(ActivityEntry e, boolean counted) {
            rows.add(e);
            if (counted) work++;
        }
    }

    /**
     * A busy cell and its neighbourhood, joined into one place.
     *
     * <p>Two limits, and both are load-bearing. A cell has to have something
     * in it to be joined, so a corridor of three broken blocks per cell does
     * not conduct; and nothing is joined more than {@link #SPREAD} cells from
     * the seed, so even a corridor that <em>is</em> busy cannot walk a place
     * across the map. Between them, a strip mine dug from one base to another
     * leaves two bases with a thin line between them rather than one place
     * marked on the empty ground halfway along it.
     */
    private static Cluster spread(String from, Map<String, Cell> cells, Set<String> done) {
        String[] seed = from.split("\\|");
        if (seed.length != 3) return new Cluster();
        String dim = seed[0];
        int seedX = Integer.parseInt(seed[1]), seedZ = Integer.parseInt(seed[2]);

        Cluster c = new Cluster();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(from);
        done.add(from);
        while (!queue.isEmpty()) {
            String key = queue.poll();
            Cell cell = cells.get(key);
            if (cell == null) continue;
            c.take(cell);
            String[] bits = key.split("\\|");
            if (bits.length != 3) continue;
            int cx = Integer.parseInt(bits[1]), cz = Integer.parseInt(bits[2]);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    int nx = cx + dx, nz = cz + dz;
                    if (Math.abs(nx - seedX) > SPREAD || Math.abs(nz - seedZ) > SPREAD) continue;
                    String next = keyOf(dim, nx, nz);
                    if (done.contains(next)) continue;
                    Cell near = cells.get(next);
                    if (near == null || near.work < CELL_FLOOR) continue;
                    done.add(next);
                    queue.add(next);
                }
            }
        }
        return c;
    }

    // ---------- measuring ----------

    /** Everything counted about one cluster, before it is given a name. */
    private static final class Cluster {
        final List<ActivityEntry> rows = new ArrayList<>();

        void take(Cell cell) { rows.addAll(cell.rows); }
    }

    private static Place classify(Cluster c) {
        Map<String, Integer> by = new HashMap<>();
        Map<String, Integer> byPlayer = new HashMap<>();
        Map<String, String> uuids = new HashMap<>();
        Map<String, Integer> crops = new HashMap<>();
        List<Long> moments = new ArrayList<>();
        List<Integer> depths = new ArrayList<>();
        long sumX = 0, sumZ = 0;
        int work = 0;

        for (ActivityEntry e : c.rows) {
            String action = e.action() == null ? "" : e.action();
            by.merge(action, Math.max(1, e.count()), Integer::sum);
            if (!WORK.contains(action)) continue;
            work++;
            sumX += e.x();
            sumZ += e.z();
            moments.add(e.at());
            if (action.equals("place") || action.equals("break")) depths.add(e.y());
            byPlayer.merge(e.player(), 1, Integer::sum);
            if (e.uuid() != null && !e.uuid().isEmpty()) uuids.put(e.player(), e.uuid());
            String crop = cropIn(e.detail());
            if (crop != null) crops.merge(crop, 1, Integer::sum);
        }
        if (work < MIN_EVENTS || moments.isEmpty()) return null;

        moments.sort(null);
        int visits = 1;
        for (int i = 1; i < moments.size(); i++) {
            if (moments.get(i) - moments.get(i - 1) > VISIT_GAP) visits++;
        }
        if (visits < MIN_VISITS) return null;

        Set<Long> days = new HashSet<>();
        for (long at : moments) days.add(at / 86_400_000L);

        int cx = (int) (sumX / work), cz = (int) (sumZ / work);
        int radius = 0;
        for (ActivityEntry e : c.rows) {
            if (!WORK.contains(e.action() == null ? "" : e.action())) continue;
            int dx = e.x() - cx, dz = e.z() - cz;
            radius = Math.max(radius, (int) Math.round(Math.sqrt((double) dx * dx + (double) dz * dz)));
        }
        radius = Math.max(12, Math.min(MAX_RADIUS, radius));

        depths.sort(null);
        int cy = depths.isEmpty() ? 64 : depths.get(depths.size() / 2);

        String top = "";
        int topCount = 0;
        for (Map.Entry<String, Integer> p : byPlayer.entrySet()) {
            if (p.getValue() > topCount) { topCount = p.getValue(); top = p.getKey(); }
        }
        boolean owned = !byPlayer.isEmpty() && topCount * 10 >= work * 6;

        int places = by.getOrDefault("place", 0);
        int breaks = by.getOrDefault("break", 0);
        String kind = kindOf(by, crops, places, breaks, cy, byPlayer.size(), work);
        if (kind == null) return null;

        int weight = weigh(kind, work, visits, days.size(), byPlayer.size());
        if (kind.equals("spot") && weight < SPOT_WEIGHT) return null;

        return new Place(kind, headline(kind, by, crops, cy, visits, days.size()),
            c.rows.get(0).dim(), cx, cy, cz, radius,
            moments.get(0), moments.get(moments.size() - 1),
            work, visits, days.size(), weight,
            owned ? top : "", owned ? uuids.getOrDefault(top, "") : "", byPlayer.size());
    }

    /**
     * What to call it.
     *
     * <p>Ordered, first match wins, and the order is the point. A portal is
     * unmistakable and goes first. A bed outranks everything else because
     * somebody chose to sleep there. Crops are checked before depth, because a
     * farm is mostly breaking too and would otherwise read as a shallow mine.
     */
    private static String kindOf(Map<String, Integer> by, Map<String, Integer> crops,
                                 int places, int breaks, int y, int people, int work) {
        int containers = by.getOrDefault("container", 0);
        int slept = by.getOrDefault("sleep", 0);
        int crafted = by.getOrDefault("craft", 0) + by.getOrDefault("enchant", 0);
        int cropWork = crops.values().stream().mapToInt(Integer::intValue).sum();

        if (by.getOrDefault("portal", 0) >= 3) return "portal";
        if (slept > 0 || (containers >= 3 && places >= 15)) return "base";
        if (cropWork >= 20 && cropWork * 3 >= places + breaks) return "farm";
        if (breaks >= 60 && y <= 45 && places * 6 < breaks) return "mine";
        if (crafted + containers >= 8 && places >= 10) return "workshop";
        if (people >= 4 && work >= 60) return "hub";
        return "spot";
    }

    private static int weigh(String kind, int work, int visits, int days, int people) {
        int score = Math.min(34, visits * 2)
            + Math.min(22, days * 3)
            + Math.min(20, work / 12)
            + Math.min(8, (people - 1) * 3)
            + switch (kind) {
                case "base" -> 16;
                case "portal", "farm" -> 10;
                case "mine", "workshop" -> 8;
                case "hub" -> 6;
                default -> 0;
            };
        return Math.max(1, Math.min(100, score));
    }

    /**
     * The evidence, in a sentence.
     *
     * <p>Without a name in it. Whether this is "Ivy's base" or "a shared base"
     * is a question about masks, and masks are settled where the panel already
     * settles them rather than baked into a string here.
     */
    private static String headline(String kind, Map<String, Integer> by,
                                   Map<String, Integer> crops, int y, int visits, int days) {
        List<String> bits = new ArrayList<>();
        switch (kind) {
            case "base" -> {
                if (by.getOrDefault("sleep", 0) > 0) bits.add("slept here");
                add(bits, by.getOrDefault("container", 0), "chest visit", "chest visits");
                add(bits, by.getOrDefault("place", 0), "block placed", "blocks placed");
            }
            case "mine" -> {
                add(bits, by.getOrDefault("break", 0), "block broken", "blocks broken");
                bits.add("around y " + y);
            }
            case "farm" -> {
                String crop = biggest(crops);
                int n = crops.getOrDefault(crop, 0);
                bits.add(n + " " + crop + " planted or cut");
                add(bits, by.getOrDefault("container", 0), "chest visit", "chest visits");
            }
            case "portal" -> {
                add(bits, by.getOrDefault("portal", 0), "crossing", "crossings");
                bits.add("y " + y);
            }
            case "workshop" -> {
                add(bits, by.getOrDefault("craft", 0), "crafting", "craftings");
                add(bits, by.getOrDefault("enchant", 0), "enchant", "enchants");
                add(bits, by.getOrDefault("container", 0), "chest visit", "chest visits");
            }
            case "hub" -> {
                add(bits, by.getOrDefault("place", 0), "block placed", "blocks placed");
                add(bits, by.getOrDefault("container", 0), "chest visit", "chest visits");
            }
            default -> {
                add(bits, by.getOrDefault("break", 0), "block broken", "blocks broken");
                add(bits, by.getOrDefault("place", 0), "block placed", "blocks placed");
            }
        }
        String what = bits.isEmpty() ? "worked repeatedly" : String.join(", ", bits);
        String when = visits + (visits == 1 ? " visit" : " separate visits")
            + (days > 1 ? " across " + days + " days" : "");
        return what + ", over " + when;
    }

    private static void add(List<String> bits, int n, String one, String many) {
        if (n <= 0) return;
        bits.add(n + " " + (n == 1 ? one : many));
    }

    private static String biggest(Map<String, Integer> counts) {
        String best = "crop";
        int most = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > most) { most = e.getValue(); best = e.getKey(); }
        }
        return best;
    }

    private static String cropIn(String detail) {
        if (detail == null || detail.isEmpty()) return null;
        String low = detail.toLowerCase(Locale.ROOT);
        for (String crop : CROPS) if (low.contains(crop)) return crop;
        return null;
    }
}
