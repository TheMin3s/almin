package com.schecks.almin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the rows actually meant.
 *
 * <p>The activity log records events: {@code break Oak Log at 214,71,-88},
 * four hundred times. Nobody wants to read that, and reading it is not how
 * anyone finds out what happened. What happened is "someone cleared the trees
 * behind spawn", and that sentence is not in the log — it is in the
 * <em>shape</em> of the log: which blocks, over what ground, in what order,
 * how tall, how long it took.
 *
 * <p>This finds that shape. Rows are cut into runs — one player, one place,
 * no long pause — and each run is classified by its geometry and its
 * materials: a column of logs four blocks wide and six tall is a tree; twelve
 * hundred stone breaks in a line two blocks high at y 11 is a tunnel; a
 * hundred placements inside a box with height is a building. The result is a
 * short list of episodes with a sentence each.
 *
 * <h3>Why it is not the model's job</h3>
 * A language model could be handed the raw rows, but it would be handed
 * thousands of them, most of which are the same row, and asked to do
 * arithmetic on coordinates — which is the part it is worst at and the part a
 * loop is best at. So the counting happens here, deterministically and for
 * free, and {@link AiInsights} hands the model the episodes instead. That
 * makes the prompt small enough for a 3B model running on the same machine,
 * and it means everything below still works with no model at all.
 *
 * <h3>What it cannot do</h3>
 * Block names come out of the game already translated, so the material rules
 * read English. On a server running in another language the material tests
 * simply miss and classification falls back to geometry — a shaft is still a
 * shaft, but "chopped down a tree" becomes "broke 40 blocks". Nothing here
 * knows intent, either: a hole is a hole whether it was a mine or a grief.
 */
public final class Episodes {

    /**
     * One stretch of one player doing one thing.
     *
     * @param kind     machine-readable class: {@code tree}, {@code shaft}, …
     * @param headline the sentence a person reads
     * @param weight   0–100, how much it deserves attention
     */
    public record Episode(String kind, String headline, String player, String uuid,
                          String dim, long from, long to,
                          int x, int y, int z, int spanXZ, int spanY,
                          int events, int weight, String tool) {

        public long durationMs() { return Math.max(0, to - from); }
    }

    /**
     * The tool this stretch of work would have been done with.
     *
     * <p>Only ever a picture on a map: the log does not record what anyone was
     * holding, so this is read off what they broke. It is worth doing because
     * "someone was digging here" and "someone was chopping here" are different
     * things at a glance, and a pickaxe and an axe say which without a word.
     */
    private static String toolFor(String kind, Stats s) {
        return switch (kind) {
            case "pvp", "fight" -> "sword";
            case "death" -> "skull";
            case "tree" -> "axe";
            case "farm" -> "hoe";
            case "build" -> "hammer";
            case "loot" -> "chest";
            case "travel" -> "boots";
            case "pace" -> "loop";
            default -> {
                if (s == null) yield "pickaxe";
                // Whatever there was most of. Sand and gravel are a shovel's
                // job, wood is an axe's, everything else is a pickaxe's.
                int soft = s.dirt + s.sand, wood = s.logs + s.leaves;
                if (wood > s.stone && wood > soft) yield "axe";
                if (soft > s.stone && soft > wood) yield "shovel";
                yield "pickaxe";
            }
        };
    }

    /** A pause this long ends a run: after it, whatever they do next is new. */
    private static final long IDLE_MS = 150_000;

    /** How far from a run's centre a row can be before it belongs to another. */
    private static final int NEAR = 64;

    /** Below this many events a run is noise, unless somebody died in it. */
    private static final int MIN_EVENTS = 4;

    /** Ceiling on returned episodes, so a busy week cannot flood the panel. */
    private static final int MAX_EPISODES = 240;

    private Episodes() {}

    // ---------- the pass over the log ----------

    /**
     * Every episode in the given rows, most notable first.
     *
     * @param rows any order; rows without a place are ignored, since an
     *             episode is a thing that happened somewhere
     */
    public static List<Episode> of(List<ActivityLog.Entry> rows) {
        Map<String, List<ActivityLog.Entry>> byPlayer = new LinkedHashMap<>();
        for (ActivityLog.Entry e : rows) {
            if (e == null || e.dim() == null || e.dim().isEmpty()) continue;
            byPlayer.computeIfAbsent(e.player(), k -> new ArrayList<>()).add(e);
        }

        List<Episode> out = new ArrayList<>();
        for (Map.Entry<String, List<ActivityLog.Entry>> who : byPlayer.entrySet()) {
            List<ActivityLog.Entry> mine = new ArrayList<>(who.getValue());
            mine.sort(Comparator.comparingLong(ActivityLog.Entry::at));

            List<ActivityLog.Entry> run = new ArrayList<>();
            for (ActivityLog.Entry e : mine) {
                if (!run.isEmpty() && !continues(run, e)) {
                    Episode ep = classify(run);
                    if (ep != null) out.add(ep);
                    run = new ArrayList<>();
                }
                run.add(e);
            }
            if (!run.isEmpty()) {
                Episode ep = classify(run);
                if (ep != null) out.add(ep);
            }
        }

        out.sort(Comparator.comparingInt(Episode::weight).reversed()
            .thenComparing(Comparator.comparingLong(Episode::to).reversed()));
        return out.size() > MAX_EPISODES ? new ArrayList<>(out.subList(0, MAX_EPISODES)) : out;
    }

    /** Whether a row belongs to the run being built: same place, no long pause. */
    private static boolean continues(List<ActivityLog.Entry> run, ActivityLog.Entry e) {
        ActivityLog.Entry last = run.get(run.size() - 1);
        if (!last.dim().equals(e.dim())) return false;
        if (e.at() - last.at() > IDLE_MS) return false;
        long sx = 0, sz = 0;
        for (ActivityLog.Entry r : run) { sx += r.x(); sz += r.z(); }
        int cx = (int) (sx / run.size()), cz = (int) (sz / run.size());
        return Math.abs(e.x() - cx) <= NEAR && Math.abs(e.z() - cz) <= NEAR;
    }

    // ---------- what a run was ----------

    /** Counts and extents for one run. Everything classification looks at. */
    private static final class Stats {
        int breaks, places, attacks, hurts, deaths, containers, items, interacts, uses;
        int chats, commands, afk, joins;
        int logs, leaves, ores, crops, stone, dirt, sand;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        long from = Long.MAX_VALUE, to = 0;
        final Map<String, Integer> broke = new HashMap<>();
        final Map<String, Integer> built = new HashMap<>();
        final Map<String, Integer> foes = new HashMap<>();
        String deathMessage = "";

        int events() { return breaks + places + attacks + hurts + deaths + containers
                            + items + interacts + uses; }
        int spanX() { return maxX - minX; }
        int spanY() { return maxY - minY; }
        int spanZ() { return maxZ - minZ; }
        int spanXZ() { return Math.max(spanX(), spanZ()); }
        int cx() { return (minX + maxX) / 2; }
        int cy() { return (minY + maxY) / 2; }
        int cz() { return (minZ + maxZ) / 2; }
    }

    private static Stats measure(List<ActivityLog.Entry> run) {
        Stats s = new Stats();
        for (ActivityLog.Entry e : run) {
            int n = Math.max(1, e.count());
            String d = e.detail() == null ? "" : e.detail().toLowerCase(Locale.ROOT);
            switch (e.action()) {
                case "break" -> { s.breaks += n; bump(s.broke, e.detail(), n); material(s, d, n); }
                case "place" -> { s.places += n; bump(s.built, e.detail(), n); }
                case "use" -> s.uses += n;
                case "attack" -> { s.attacks += n; bump(s.foes, e.detail(), n); }
                case "hurt" -> { s.hurts += n; bump(s.foes, before(e.detail()), n); }
                case "death" -> { s.deaths += n; if (s.deathMessage.isEmpty() && e.detail() != null)
                                      s.deathMessage = e.detail(); }
                case "container" -> s.containers += n;
                case "item" -> s.items += n;
                case "interact" -> s.interacts += n;
                case "chat" -> s.chats += n;
                case "command" -> s.commands += n;
                case "afk" -> s.afk += n;
                case "join", "leave" -> s.joins += n;
                default -> { }
            }
            s.minX = Math.min(s.minX, e.x()); s.maxX = Math.max(s.maxX, e.x());
            s.minY = Math.min(s.minY, e.y()); s.maxY = Math.max(s.maxY, e.y());
            s.minZ = Math.min(s.minZ, e.z()); s.maxZ = Math.max(s.maxZ, e.z());
            s.from = Math.min(s.from, e.at());
            s.to = Math.max(s.to, e.at());
        }
        return s;
    }

    /** Which kind of block a break was, as far as the block's name gives it away. */
    private static void material(Stats s, String lower, int n) {
        if (lower.contains("log") || lower.contains("stem") || lower.contains("hyphae")) s.logs += n;
        else if (lower.contains("leaves")) s.leaves += n;
        else if (lower.contains(" ore") || lower.endsWith("ore")
                 || lower.contains("ancient debris")) s.ores += n;
        else if (isCrop(lower)) s.crops += n;
        else if (lower.contains("stone") || lower.contains("deepslate")
                 || lower.contains("granite") || lower.contains("andesite")
                 || lower.contains("diorite") || lower.contains("tuff")
                 || lower.contains("netherrack")) s.stone += n;
        else if (lower.contains("dirt") || lower.contains("grass block")
                 || lower.contains("gravel") || lower.contains("mud")) s.dirt += n;
        else if (lower.contains("sand")) s.sand += n;
    }

    private static boolean isCrop(String l) {
        return l.contains("wheat") || l.contains("carrot") || l.contains("potato")
            || l.contains("beetroot") || l.contains("melon") || l.contains("pumpkin")
            || l.contains("nether wart") || l.contains("sugar cane") || l.contains("cocoa")
            || l.contains("berr") || l.contains("kelp") || l.contains("bamboo");
    }

    /** "Zombie  5 damage" → "Zombie". The hurt detail packs two fields into one. */
    private static String before(String detail) {
        if (detail == null) return "";
        int cut = detail.indexOf("  ");
        return cut > 0 ? detail.substring(0, cut) : detail;
    }

    private static void bump(Map<String, Integer> m, String key, int n) {
        if (key == null || key.isBlank()) return;
        m.merge(key, n, Integer::sum);
    }

    private static String top(Map<String, Integer> m) {
        String best = "";
        int most = 0;
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            if (e.getValue() > most) { most = e.getValue(); best = e.getKey(); }
        }
        return best;
    }

    /**
     * The rules, in the order they are allowed to win.
     *
     * <p>Order is the whole design: a run where somebody died is a fight even
     * if they also broke forty blocks, and a shaft is a shaft even though a
     * shaft is also, numerically, mining. Each test is on the shape of the run
     * rather than on any one row, which is why "tunnelled 200 blocks" can be
     * said at all — no row knows it was part of a tunnel.
     */
    private static Episode classify(List<ActivityLog.Entry> run) {
        Stats s = measure(run);
        if (s.events() < MIN_EVENTS && s.deaths == 0) return null;

        ActivityLog.Entry any = run.get(0);
        String kind, headline;
        int weight;

        int mins = (int) Math.max(1, Math.round((s.to - s.from) / 60000.0));

        if (s.deaths > 0) {
            kind = "death";
            headline = s.deathMessage.isEmpty()
                ? any.player() + " died"
                : trim(s.deathMessage);
            weight = 92;
        } else if (s.attacks + s.hurts >= 6 && !top(s.foes).isEmpty()) {
            String foe = top(s.foes);
            boolean pvp = looksLikePlayer(foe, run);
            kind = pvp ? "pvp" : "fight";
            headline = pvp
                ? "Traded blows with " + foe
                : "Fought off " + plural(s.attacks + s.hurts, "hit") + " around " + foe;
            weight = pvp ? 88 : 52;
        } else if (s.logs >= 4 && s.spanXZ() <= 12 && s.spanY() >= 3) {
            kind = "tree";
            int trees = Math.max(1, Math.round(s.logs / 6f));
            headline = trees == 1
                ? "Chopped down a tree" + (s.leaves > 0 ? " and cleared the leaves" : "")
                : "Chopped down about " + trees + " trees";
            weight = 34;
        } else if (s.breaks >= 8 && s.spanY() >= 8 && s.spanXZ() <= 4) {
            kind = "shaft";
            headline = "Dug a shaft from y " + s.maxY + " down to y " + s.minY;
            weight = 44;
        } else if (s.breaks >= 12 && s.spanY() <= 4
                   && Math.max(s.spanX(), s.spanZ()) >= 4 * Math.max(1, Math.min(s.spanX(), s.spanZ()))) {
            kind = "tunnel";
            int len = Math.max(s.spanX(), s.spanZ());
            headline = "Tunnelled " + len + " blocks " + axis(s) + " at y " + s.cy();
            weight = 46;
        } else if (s.breaks >= 10 && s.cy() < 50) {
            kind = "mine";
            headline = "Mined " + plural(s.breaks, "block") + " around y " + s.cy()
                + (s.ores > 0 ? ", hitting " + plural(s.ores, "ore") : "");
            weight = s.ores > 0 ? 40 : 26;
        } else if (s.crops >= 6) {
            kind = "farm";
            headline = "Worked a field — " + plural(s.crops, "crop") + " over "
                + plural(mins, "minute");
            weight = 22;
        } else if (s.breaks >= 20 && s.spanY() <= 4 && s.spanXZ() >= 8) {
            kind = "clear";
            headline = "Cleared " + s.spanX() + "×" + s.spanZ() + " of ground at y " + s.cy();
            weight = 58;
        } else if (s.places >= 8) {
            kind = "build";
            String of = top(s.built);
            if (s.spanY() <= 1) {
                headline = "Laid " + plural(s.places, "block") + " flat"
                    + (of.isEmpty() ? "" : " of " + of) + " — a floor or a path";
                weight = 30;
            } else {
                headline = "Built something " + Math.max(1, s.spanXZ()) + " across and "
                    + Math.max(1, s.spanY()) + " high"
                    + (of.isEmpty() ? "" : ", mostly " + of);
                weight = 60;
            }
        } else if (s.breaks >= 10) {
            kind = "dig";
            String of = top(s.broke);
            headline = "Broke " + plural(s.breaks, "block")
                + (of.isEmpty() ? "" : ", mostly " + of) + " at y " + s.cy();
            weight = 36;
        } else if (s.containers >= 4) {
            kind = "loot";
            headline = "Went through " + plural(s.containers, "container");
            weight = 30;
        } else if (s.interacts + s.items >= 8) {
            kind = "busy";
            headline = "Busy with things — " + plural(s.interacts + s.items, "interaction");
            weight = 14;
        } else {
            kind = "about";
            headline = "Around " + s.cx() + "," + s.cz() + " for " + plural(mins, "minute");
            weight = 8;
        }

        return new Episode(kind, headline, any.player(), any.uuid(), any.dim(),
            s.from, s.to, s.cx(), s.cy(), s.cz(), s.spanXZ(), s.spanY(), s.events(), weight,
            toolFor(kind, s));
    }

    /** Which way a tunnel runs, from which axis is longer. */
    private static String axis(Stats s) {
        return s.spanX() >= s.spanZ() ? "east-west" : "north-south";
    }

    /**
     * Whether the thing on the other end of a fight was a person.
     *
     * <p>Mob names are translated display names and player names are account
     * names, and nothing in a row says which is which — so the test is whether
     * anyone else in the same rows is called that. It says yes only for a
     * player who is also being recorded, which is the case that matters:
     * "two players fought" is a different event from "someone fought a
     * zombie", and getting it wrong in that direction is the safe way round.
     */
    private static boolean looksLikePlayer(String foe, List<ActivityLog.Entry> run) {
        if (foe == null || foe.isEmpty()) return false;
        if (!foe.matches("[A-Za-z0-9_]{3,16}")) return false;
        for (ActivityLog.Entry e : run) {
            if (foe.equalsIgnoreCase(e.player())) return false;   // hitting yourself: no
        }
        return PlayerTracks.uuidOf(foe) != null;
    }

    private static String plural(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    private static String trim(String s) {
        String t = s.replace('\n', ' ').trim();
        return t.length() <= 120 ? t : t.substring(0, 119) + "…";
    }

    // ---------- movement, which is not in the log at all ----------

    /**
     * What the paths say that the rows do not.
     *
     * <p>The activity log only has rows where something happened. Walking is
     * not an event, so a player who spent twenty minutes going somewhere
     * leaves no trace in it at all — and "went somewhere" and "did not leave
     * this room" are both things you want to know. Those come from the
     * position samples instead.
     *
     * @param tracks name to that player's samples, oldest first
     */
    public static List<Episode> ofMovement(Map<String, List<PlayerTracks.Point>> tracks) {
        List<Episode> out = new ArrayList<>();
        for (Map.Entry<String, List<PlayerTracks.Point>> who : tracks.entrySet()) {
            List<PlayerTracks.Point> pts = who.getValue();
            if (pts == null || pts.size() < 6) continue;
            int i = 0;
            while (i < pts.size() - 1) {
                int j = i;
                double walked = 0;
                // Extend while the samples keep coming and stay in one
                // dimension: a gap means they logged off or went through a
                // portal, and either way the line between is not a walk.
                while (j + 1 < pts.size()
                       && pts.get(j + 1).at() - pts.get(j).at() <= IDLE_MS
                       && pts.get(j + 1).dim().equals(pts.get(j).dim())) {
                    walked += dist(pts.get(j), pts.get(j + 1));
                    j++;
                }
                if (j - i >= 5) {
                    Episode ep = movement(who.getKey(), pts.subList(i, j + 1), walked);
                    if (ep != null) out.add(ep);
                }
                i = Math.max(j, i + 1);
            }
        }
        out.sort(Comparator.comparingInt(Episode::weight).reversed()
            .thenComparing(Comparator.comparingLong(Episode::to).reversed()));
        return out;
    }

    /**
     * One stretch of walking: a journey, a patrol, or standing about.
     *
     * <p>The test is the ratio of how far they walked to how far they got. Ten
     * to one over a small area is not travel, it is pacing — someone searching
     * for something, waiting for someone, or a farm being run in a loop.
     */
    private static Episode movement(String name, List<PlayerTracks.Point> pts, double walked) {
        PlayerTracks.Point a = pts.get(0), b = pts.get(pts.size() - 1);
        double net = dist(a, b);
        int mins = (int) Math.max(1, Math.round((b.at() - a.at()) / 60000.0));
        int radius = 0;
        for (PlayerTracks.Point p : pts) {
            radius = Math.max(radius, (int) Math.round(dist(p, a)));
        }
        String uuid = idOf(name);

        if (net >= 200 && walked >= net * 0.9) {
            // Marked where they arrived, not at the midpoint of the walk. The
            // middle of a journey is usually open ocean or the inside of a
            // hill — a badge floating there says nothing, and the useful
            // question about a journey is where it went.
            return new Episode("travel",
                "Travelled " + Math.round(net) + " blocks — " + a.x() + "," + a.z()
                    + " to " + b.x() + "," + b.z() + ", " + plural(mins, "minute"),
                name, uuid, b.dim(), a.at(), b.at(), b.x(), b.y(), b.z(),
                (int) Math.round(net), 0, pts.size(), 24, "boots");
        }
        if (walked >= 220 && net < walked / 6 && radius <= 40 && mins >= 3) {
            return new Episode("pace",
                "Back and forth around " + b.x() + "," + b.z() + " — "
                    + Math.round(walked) + " blocks walked without leaving "
                    + plural(radius, "block") + ", over " + plural(mins, "minute"),
                name, uuid, b.dim(), a.at(), b.at(), b.x(), b.y(), b.z(), radius, 0,
                pts.size(), 42, "loop");
        }
        return null;
    }

    private static String idOf(String name) {
        java.util.UUID id = PlayerTracks.uuidOf(name);
        return id == null ? "" : id.toString();
    }

    private static double dist(PlayerTracks.Point a, PlayerTracks.Point b) {
        double dx = a.x() - b.x(), dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dz * dz);
    }
}
