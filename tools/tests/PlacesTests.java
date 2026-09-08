import com.schecks.almin.ActivityEntry;
import com.schecks.almin.Places;

import java.util.*;

/**
 * What is where.
 *
 * Places are not episodes: an episode is one stretch of work, a place is
 * somewhere people keep coming back to. So most of what these check is the
 * difference — that one long afternoon does not make a base, that a thousand
 * chat lines in one spot make nothing at all, and that two bases with a mining
 * trail between them stay two bases rather than becoming one region.
 *
 * The classification rules are the substance of the feature, so a wrong answer
 * here is a wrong mark on somebody's map.
 */
public class PlacesTests {
    static int failures = 0;

    /** A fortnight ago, so "days" are whole and in the past. */
    static final long START = (System.currentTimeMillis() / 86_400_000L - 14) * 86_400_000L;

    static void check(String what, boolean ok, String saw) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what + (ok ? "" : "  -> " + saw));
        if (!ok) failures++;
    }

    /** One visit's worth of rows, on evening {@code day}, all at one spot. */
    static void visit(List<ActivityEntry> out, int day, String who, String action,
                      String detail, int x, int y, int z, int times) {
        long at = START + day * 86_400_000L + 19 * 3600_000L;
        for (int i = 0; i < times; i++) {
            out.add(new ActivityEntry(at + i * 1500L, who, "uuid-" + who, action, detail,
                "overworld", x + (i % 5), y, z + (i % 3), 1));
        }
    }

    static Places.Place kindAt(List<ActivityEntry> rows, String kind) {
        for (Places.Place p : Places.of(rows)) if (p.kind().equals(kind)) return p;
        return null;
    }

    static String kinds(List<ActivityEntry> rows) {
        List<String> out = new ArrayList<>();
        for (Places.Place p : Places.of(rows)) out.add(p.kind() + "@" + p.x() + "," + p.z());
        return out.isEmpty() ? "(nothing)" : String.join(" ", out);
    }

    /** Chests, building and a bed, on six evenings. */
    static List<ActivityEntry> base(String who, int x, int z) {
        List<ActivityEntry> rows = new ArrayList<>();
        for (int day = 0; day < 6; day++) {
            visit(rows, day, who, "place", "Oak Planks", x, 64, z, 8);
            visit(rows, day, who, "container", "Chest", x + 2, 64, z + 2, 3);
        }
        visit(rows, 3, who, "sleep", "Red Bed", x + 1, 64, z + 1, 1);
        return rows;
    }

    public static void main(String[] args) {
        // ---- recurrence is the whole test ----
        {
            List<ActivityEntry> rows = base("Ivy", 100, 100);
            Places.Place p = kindAt(rows, "base");
            check("chests, building and a bed on six evenings is a base", p != null,
                kinds(rows));
            check("...and it is attributed to whoever did it",
                p != null && p.player().equals("Ivy") && p.people() == 1,
                p == null ? "no place" : p.player() + " of " + p.people());
            check("...and it counts the visits rather than the rows",
                p != null && p.visits() >= 6 && p.days() >= 6,
                p == null ? "no place" : p.visits() + " visits, " + p.days() + " days");
            check("...and it says what the evidence was",
                p != null && p.headline().contains("slept here")
                    && p.headline().contains("chest visits"),
                p == null ? "no place" : p.headline());
        }
        {
            // The same amount of work, all in one sitting. That is an episode,
            // and the timeline is where it belongs.
            List<ActivityEntry> rows = new ArrayList<>();
            visit(rows, 0, "Ivy", "place", "Oak Planks", 100, 64, 100, 48);
            visit(rows, 0, "Ivy", "container", "Chest", 102, 64, 102, 18);
            check("one long afternoon in one spot is not a place",
                Places.of(rows).isEmpty(), kinds(rows));
        }
        {
            // Two visits is a coincidence. Three is somewhere you go.
            List<ActivityEntry> rows = new ArrayList<>();
            for (int day = 0; day < 2; day++) {
                visit(rows, day, "Ivy", "place", "Oak Planks", 100, 64, 100, 15);
                visit(rows, day, "Ivy", "container", "Chest", 102, 64, 102, 4);
            }
            check("...and neither is going back once", Places.of(rows).isEmpty(), kinds(rows));
        }

        // ---- what the rows have to be about ----
        {
            // Talking happens wherever somebody is standing. Counting it would
            // put a place under every conversation anybody ever had.
            List<ActivityEntry> rows = new ArrayList<>();
            for (int day = 0; day < 8; day++) {
                visit(rows, day, "Ivy", "chat", "hello", 100, 64, 100, 40);
                visit(rows, day, "Ivy", "command", "/home", 100, 64, 100, 10);
            }
            check("talking somewhere every night does not make it a place",
                Places.of(rows).isEmpty(), kinds(rows));
        }

        // ---- the other kinds ----
        {
            List<ActivityEntry> rows = new ArrayList<>();
            for (int day = 0; day < 5; day++) {
                visit(rows, day, "Rune", "break", "Stone", -400, 12, 300, 30);
            }
            Places.Place p = kindAt(rows, "mine");
            check("breaking deep, with nothing built, is a mine", p != null, kinds(rows));
            check("...and it says how deep",
                p != null && p.headline().contains("y 12") && p.y() == 12,
                p == null ? "no place" : p.headline());
        }
        {
            List<ActivityEntry> rows = new ArrayList<>();
            for (int day = 0; day < 5; day++) {
                visit(rows, day, "Rune", "break", "Wheat", 600, 63, -200, 10);
                visit(rows, day, "Rune", "place", "Wheat Seeds", 600, 63, -200, 8);
            }
            Places.Place p = kindAt(rows, "farm");
            check("planting and cutting the same crop is a farm", p != null, kinds(rows));
            check("...and it names the crop",
                p != null && p.headline().contains("wheat"),
                p == null ? "no place" : p.headline());
        }
        {
            // A farm is mostly breaking too, so it has to be decided before
            // depth or it reads as a shallow mine.
            List<ActivityEntry> rows = new ArrayList<>();
            for (int day = 0; day < 5; day++) {
                visit(rows, day, "Rune", "break", "Nether Wart", 900, 40, 900, 22);
            }
            check("a crop below ground is still a farm, not a mine",
                kindAt(rows, "farm") != null, kinds(rows));
        }
        {
            List<ActivityEntry> rows = new ArrayList<>();
            for (int day = 0; day < 4; day++) {
                visit(rows, day, "Ivy", "portal", "Nether Portal", 40, 70, 40, 4);
                visit(rows, day, "Ivy", "place", "Obsidian", 40, 70, 40, 3);
            }
            check("a spot people keep crossing is a portal",
                kindAt(rows, "portal") != null, kinds(rows));
        }
        {
            List<ActivityEntry> rows = new ArrayList<>();
            for (String who : List.of("Ivy", "Rune", "Sol", "Wren", "Ash")) {
                for (int day = 0; day < 4; day++) {
                    visit(rows, day, who, "interact", "Item Frame", 0, 64, 0, 5);
                }
            }
            Places.Place p = kindAt(rows, "hub");
            check("somewhere everybody goes is a gathering place", p != null, kinds(rows));
            check("...and a shared place is nobody's in particular",
                p != null && p.player().isEmpty() && p.people() == 5,
                p == null ? "no place" : p.player() + " of " + p.people());
        }

        // ---- keeping places apart ----
        {
            List<ActivityEntry> rows = new ArrayList<>(base("Ivy", 100, 100));
            rows.addAll(base("Rune", 2000, 2000));
            List<Places.Place> found = Places.of(rows);
            check("two bases far apart stay two places", found.size() == 2, kinds(rows));
            check("...each attributed to its own builder",
                found.size() == 2
                    && !found.get(0).player().equals(found.get(1).player()),
                kinds(rows));
        }
        {
            // A corridor of a few broken blocks per cell must not conduct: a
            // trail joining two bases would swallow both into one region
            // stretching across the map.
            List<ActivityEntry> rows = new ArrayList<>(base("Ivy", 100, 100));
            rows.addAll(base("Rune", 700, 100));
            for (int x = 150; x < 700; x += 20) {
                visit(rows, 2, "Ivy", "break", "Stone", x, 40, 100, 2);
            }
            List<Places.Place> found = Places.of(rows);
            boolean apart = found.size() == 2
                && found.stream().allMatch(p -> p.radius() <= 160);
            check("a mining trail between two bases does not merge them", apart, kinds(rows));
        }

        // ---- the shape of what comes out ----
        {
            List<ActivityEntry> rows = new ArrayList<>(base("Ivy", 100, 100));
            Places.Place p = Places.of(rows).get(0);
            boolean near = Math.abs(p.x() - 100) <= 48 && Math.abs(p.z() - 100) <= 48;
            check("a place is centred on what happened in it", near,
                p.x() + "," + p.y() + "," + p.z());
            check("...and carries a radius a map can draw",
                p.radius() >= 12 && p.radius() <= 160, String.valueOf(p.radius()));
            check("...and a span of time",
                p.from() > 0 && p.to() >= p.from(), p.from() + ".." + p.to());
            check("...and a weight worth sorting on",
                p.weight() >= 1 && p.weight() <= 100, String.valueOf(p.weight()));
        }
        {
            // Best first, so a map that can only draw a few draws the ones
            // worth drawing.
            List<ActivityEntry> rows = new ArrayList<>(base("Ivy", 100, 100));
            for (int day = 0; day < 3; day++) {
                visit(rows, day, "Rune", "break", "Stone", 3000, 12, 3000, 12);
            }
            List<Places.Place> found = Places.of(rows);
            boolean ordered = true;
            for (int i = 1; i < found.size(); i++) {
                if (found.get(i - 1).weight() < found.get(i).weight()) ordered = false;
            }
            check("places come out best first", ordered && found.size() >= 1, kinds(rows));
        }
        {
            // Rows with no dimension are the ones that did not happen anywhere.
            List<ActivityEntry> rows = new ArrayList<>();
            for (int day = 0; day < 6; day++) {
                long at = START + day * 86_400_000L;
                for (int i = 0; i < 10; i++) {
                    rows.add(new ActivityEntry(at + i * 1500L, "Ivy", "u", "place",
                        "Oak Planks", "", 100, 64, 100, 1));
                }
            }
            check("a row that happened nowhere is not somewhere",
                Places.of(rows).isEmpty(), kinds(rows));
        }

        {
            // Places grow from their busiest cell outwards. That is what makes
            // the answer independent of the order the rows arrive in — and a
            // mark that moved between two refreshes would be worse than no
            // mark, because it would look like something had happened.
            List<ActivityEntry> rows = new ArrayList<>(base("Ivy", 100, 100));
            rows.addAll(base("Rune", 700, 100));
            for (int day = 0; day < 5; day++) {
                visit(rows, day, "Sol", "break", "Stone", -900, 12, 40, 26);
            }
            String first = kinds(rows);
            boolean same = true;
            for (int seed = 0; seed < 6; seed++) {
                List<ActivityEntry> shuffled = new ArrayList<>(rows);
                Collections.shuffle(shuffled, new Random(seed));
                if (!kinds(shuffled).equals(first)) same = false;
            }
            check("the same rows in any order give the same places", same, first);
        }

        System.out.println(failures == 0 ? "PLACES OK" : failures + " FAILED");
        if (failures > 0) System.exit(1);
    }
}
