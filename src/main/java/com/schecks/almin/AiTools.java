package com.schecks.almin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the model is allowed to look up for itself.
 *
 * <h3>Why this exists</h3>
 * The older AI features work by writing the answer into the question: gather
 * episodes, gather rows, gather chat, paste it all into a prompt and hope the
 * interesting part survived the cut. That has two failure modes and Almin hit
 * both. Anything trimmed away is unanswerable no matter how good the model is,
 * and everything kept is paid for on every request, so the prompt grows until
 * asking is slow and expensive whether or not the question needed any of it.
 *
 * <p>Tools invert that. The model is told only what it may ask for, and it
 * asks. A question about one player in one hour costs one small lookup rather
 * than a transcript of the whole server, and a question that needs three
 * different lookups can make them — which pasting it all in could never do at
 * any size.
 *
 * <h3>Every tool runs as the person who asked</h3>
 * This is the part to be careful with. A tool is a new way to reach the same
 * data, so it is also a new way around the rules on that data, and a model is
 * a poor place to enforce anything — it can be talked out of it. So no rule
 * lives in the prompt. {@link #forAccount} hands out only the tools an account
 * may use, {@link #run} re-checks on every call, and the readers below go
 * through the same {@code detailFor}, {@code onlyPlayer} and {@code visible}
 * helpers the panel's own pages use. An account that cannot read chat cannot
 * read it by asking the model nicely, and one restricted to its own activity
 * gets only its own activity here too.
 *
 * <h3>Answers are small on purpose</h3>
 * Each result is capped ({@link #MAX_RESULT}) and each list has a ceiling the
 * model may lower but not raise. The cap is not a safety net, it is the
 * feature: a tool that could return the whole log would be the old prompt with
 * extra steps. When a result is cut the model is told so inside the result,
 * together with how much it did not see — which it can then go and ask for,
 * because narrowing and paging are what the arguments are for.
 */
final class AiTools {

    /** One thing the model may ask for, and the shape of a valid request. */
    record Tool(String name, String description, JsonObject schema) {}

    /**
     * The outcome of one call: {@code json} goes back to the model, {@code note}
     * is the one line the panel shows a person so the answer is not a black box.
     */
    record Result(String json, String note) {}

    /** Ceiling on one result. Past this the model is paying for scrollback. */
    static final int MAX_RESULT = 12 * 1024;

    /** Rows one call may return. The model may ask for fewer, never more. */
    static final int MAX_ROWS = 60;

    /** Positions one call may return, after thinning. Paths are repetitive. */
    static final int MAX_POINTS = 80;

    private AiTools() {}

    // ---------- the catalogue ----------

    /**
     * The tools this account may use, which is the only list the model is ever
     * shown. Withholding a tool rather than refusing the call matters: a model
     * that can see a tool it may not use will keep trying it, narrate the
     * refusal, and turn a permission boundary into a conversation about the
     * permission boundary.
     */
    static List<Tool> forAccount(Accounts.Account me) {
        List<Tool> out = new ArrayList<>();
        if (may(me, "activity")) {
            out.add(searchActivitySpec());
            out.add(countActivitySpec());
            out.add(overviewSpec());
            if (!coordsHidden(me)) {
                out.add(positionsSpec());
                out.add(placesSpec());
            }
        }
        if (may(me, "players")) {
            out.add(listPlayersSpec());
            out.add(playerSummarySpec());
        }
        if (may(me, "dash")) out.add(serverStatusSpec());
        if (may(me, "mods")) out.add(listModsSpec());
        if (may(me, "settings")) out.add(settingsSpec());
        return out;
    }

    private static boolean may(Accounts.Account me, String menu) {
        return me != null && me.canRead(menu);
    }

    private static boolean coordsHidden(Accounts.Account me) {
        return me != null && me.coordsHidden();
    }

    private static Tool searchActivitySpec() {
        JsonObject p = props();
        string(p, "find", "Free text matched against player, action, detail and place. "
            + "Leave it out to match everything.");
        string(p, "player", "Only this player's rows. Exact name, case-insensitive.");
        string(p, "action", "Only this kind of row, e.g. break, place, chat, death, join, quit.");
        string(p, "dimension", "Only this dimension: overworld, the_nether or the_end.");
        integer(p, "since_minutes", "Only rows from the last N minutes. Omit for all of them.");
        integer(p, "before_minutes", "Only rows older than N minutes. Pairs with since_minutes "
            + "to ask about a window in the middle of the log.");
        integer(p, "limit", "How many rows to return, 1-" + MAX_ROWS + ". Default 25.");
        integer(p, "offset", "How many matching rows to skip, for reading past the first page.");
        return new Tool("search_activity",
            "Read the recorded activity log, newest first. This is the main record of what has "
            + "happened on the server and it is kept on disk, so it reaches back past restarts "
            + "and is not limited to the session running now. Returns the matching rows plus "
            + "how many matched altogether, so a search matching thousands still costs one "
            + "small answer.",
            schema(p));
    }

    private static Tool countActivitySpec() {
        JsonObject p = props();
        string(p, "group_by", "What to count by: player, action, dimension, day or hour.");
        string(p, "find", "Optional free text the rows must match first.");
        string(p, "player", "Optional player the rows must belong to.");
        string(p, "action", "Optional kind of row to count.");
        integer(p, "since_minutes", "Only count rows from the last N minutes.");
        required(p, "group_by");
        return new Tool("count_activity",
            "Count log rows grouped by player, action, dimension, day or hour, without "
            + "returning the rows themselves. Use this first for anything shaped like 'who did "
            + "the most', 'what happens most often' or 'when was it busiest': it reads the "
            + "whole log and answers in a few lines, where search_activity would need many "
            + "pages to reach the same conclusion.",
            schema(p));
    }

    private static Tool overviewSpec() {
        return new Tool("activity_overview",
            "How much history there is to work with: how many rows are kept, how far back the "
            + "oldest goes, when the newest was, which players and which kinds of action appear "
            + "at all, and what is excluded from recording. Cheap. Worth calling first when a "
            + "question depends on whether the log even covers the period being asked about, "
            + "so the answer can say 'the log only goes back to X' instead of guessing.",
            schema(props()));
    }

    private static Tool placesSpec() {
        JsonObject p = props();
        string(p, "kind", "Only places of this kind: base, mine, farm, portal, workshop, "
            + "hub or spot.");
        string(p, "player", "Only places this player did most of. Exact name, "
            + "case-insensitive.");
        integer(p, "limit", "How many to return, 1-40. Default 20.");
        return new Tool("list_places",
            "The places on the server: somewhere people keep coming back to, with what kind "
            + "it looks like, who does most of it, where it is and how many separate visits "
            + "it has had. This is the tool for anything shaped like 'where does X live', "
            + "'where are the bases' or 'what is at these coordinates' \u2014 it is worked out "
            + "from the whole log at once, so it answers in a few lines where searching the "
            + "rows would take many pages and still not add up to a place.",
            schema(p));
    }

    private static Tool positionsSpec() {
        JsonObject p = props();
        string(p, "player", "Whose path to read. Exact name, case-insensitive.");
        integer(p, "since_minutes", "Only samples from the last N minutes.");
        integer(p, "limit", "How many positions to return, 1-" + MAX_POINTS + ". Default 40.");
        required(p, "player");
        return new Tool("player_positions",
            "Where one player has been: sampled positions with times and dimensions, thinned "
            + "evenly across the window so a long path still comes back as a usable shape "
            + "rather than the first few minutes of it.",
            schema(p));
    }

    private static Tool listPlayersSpec() {
        JsonObject p = props();
        string(p, "sort", "Order: recent (default), playtime, joins or name.");
        integer(p, "limit", "How many to return, 1-100. Default 30.");
        return new Tool("list_players",
            "Everyone the server has a record of, with first seen, last seen, session count "
            + "and total playtime, and whether they are online right now.",
            schema(p));
    }

    private static Tool playerSummarySpec() {
        JsonObject p = props();
        string(p, "player", "Exact name, case-insensitive.");
        required(p, "player");
        return new Tool("player_summary",
            "One player in detail: first and last seen, playtime, sessions, whether they are "
            + "online, and a breakdown of what they have been recorded doing.",
            schema(p));
    }

    private static Tool serverStatusSpec() {
        return new Tool("server_status",
            "The server right now: whether it is running, who is online, the Minecraft version, "
            + "uptime, tick rate, memory and the world's size on disk.",
            schema(props()));
    }

    private static Tool listModsSpec() {
        JsonObject p = props();
        string(p, "player", "Optional: one player's client mods instead of the server's.");
        return new Tool("list_mods",
            "The mods installed. With no player, the server's own list; with a player, the mods "
            + "their client reported, marking any that are on the restricted list.",
            schema(p));
    }

    private static Tool settingsSpec() {
        JsonObject p = props();
        string(p, "find", "Optional text to match against setting names.");
        return new Tool("settings_summary",
            "Almin's current settings and what each one means. Passwords, API keys and "
            + "anything else secret are never included.",
            schema(p));
    }

    // ---------- running one ----------

    /**
     * Executes one call, or explains why not.
     *
     * <p>A refusal comes back as an ordinary result rather than an exception on
     * purpose. The model needs to read "you cannot see that" and pass it on to
     * the person, where a thrown error would end the whole conversation over
     * one wrong guess about what it was allowed to look at.
     */
    static Result run(Accounts.Account me, String name, JsonObject args) {
        JsonObject a = args == null ? new JsonObject() : args;
        try {
            // Re-checked here and not only in forAccount. The two are reached
            // by different paths — a conversation carries the tools it was
            // started with, and an account can lose a menu in between — and
            // this is the check that runs immediately before the data is read.
            String needs = menuFor(name);
            if (needs != null && !may(me, needs)) {
                return refuse("This account cannot read the " + menuName(needs) + " menu.");
            }
            if ((name.equals("player_positions") || name.equals("list_places"))
                && coordsHidden(me)) {
                return refuse("This account is not shown coordinates.");
            }
            return switch (name) {
                case "search_activity"   -> searchActivity(me, a);
                case "count_activity"    -> countActivity(me, a);
                case "activity_overview" -> activityOverview(me);
                case "player_positions"  -> playerPositions(me, a);
                case "list_places"       -> listPlaces(me, a);
                case "list_players"      -> listPlayers(a);
                case "player_summary"    -> playerSummary(me, a);
                case "server_status"     -> serverStatus();
                case "list_mods"         -> listMods(a);
                case "settings_summary"  -> settingsSummary(a);
                default -> refuse("There is no tool called " + name + ".");
            };
        } catch (Exception e) {
            String why = e.getMessage() == null ? e.toString() : e.getMessage();
            AlminLog.warn("[almin] tool {} failed: {}", name, why);
            return refuse("That lookup failed: " + why);
        }
    }

    /** Which menu a tool belongs to, so one table settles both questions. */
    private static String menuFor(String tool) {
        return switch (tool) {
            case "search_activity", "count_activity", "activity_overview",
                 "player_positions", "list_places" -> "activity";
            case "list_players", "player_summary" -> "players";
            case "server_status" -> "dash";
            case "list_mods" -> "mods";
            case "settings_summary" -> "settings";
            default -> null;
        };
    }

    // ---------- the activity log ----------

    private static Result searchActivity(Accounts.Account me, JsonObject a) {
        int limit = clamp(num(a, "limit", 25), 1, MAX_ROWS);
        int offset = Math.max(0, num(a, "offset", 0));
        Window w = window(a);
        String find = text(a, "find").toLowerCase(Locale.ROOT);
        String player = text(a, "player");
        String action = text(a, "action");
        String dim = text(a, "dimension");
        String only = WebUi.onlyPlayer(me);

        ActivityLog.Page page = ActivityLog.page(offset, limit, e ->
            WebUi.visible(only, e.player())
                && w.holds(e.at())
                && (player.isEmpty() || player.equalsIgnoreCase(e.player()))
                && (action.isEmpty() || action.equalsIgnoreCase(e.action()))
                && (dim.isEmpty() || dim.equalsIgnoreCase(e.dim()))
                && (find.isEmpty() || haystack(me, e).contains(find)));

        JsonObject out = new JsonObject();
        out.addProperty("matched", page.matched());
        out.addProperty("returned", page.rows().size());
        out.addProperty("offset", offset);
        if (page.more()) {
            out.addProperty("more", "Call again with offset " + (offset + page.rows().size())
                + " for the next " + limit + ".");
        }
        JsonArray rows = new JsonArray();
        for (ActivityEntry e : page.rows()) rows.add(row(me, e));
        out.add("rows", rows);
        return done(out, page.matched() + " match" + (page.matched() == 1 ? "" : "es")
            + ", read " + page.rows().size() + describe(find, player, action, dim, w));
    }

    private static Result countActivity(Accounts.Account me, JsonObject a) {
        String asked = text(a, "group_by").toLowerCase(Locale.ROOT);
        final String by = asked.isEmpty() ? "player" : asked;
        Window w = window(a);
        String find = text(a, "find").toLowerCase(Locale.ROOT);
        String player = text(a, "player");
        String action = text(a, "action");
        String only = WebUi.onlyPlayer(me);

        // Counting is the whole job, so nothing is kept: the predicate tallies
        // and the page asks for no rows at all. That is what makes this cheap
        // enough to point at the entire log.
        Map<String, Integer> tally = new LinkedHashMap<>();
        int[] total = {0};
        ActivityLog.page(0, 0, e -> {
            if (!WebUi.visible(only, e.player())) return false;
            if (!w.holds(e.at())) return false;
            if (!player.isEmpty() && !player.equalsIgnoreCase(e.player())) return false;
            if (!action.isEmpty() && !action.equalsIgnoreCase(e.action())) return false;
            if (!find.isEmpty() && !haystack(me, e).contains(find)) return false;
            total[0]++;
            tally.merge(bucket(by, e), 1, Integer::sum);
            return false;
        });

        List<Map.Entry<String, Integer>> ranked = new ArrayList<>(tally.entrySet());
        // Days and hours read as a timeline; everything else reads as a ranking.
        boolean chronological = by.equals("day") || by.equals("hour");
        ranked.sort(chronological
            ? Map.Entry.comparingByKey()
            : (x, y) -> y.getValue() - x.getValue());

        JsonObject out = new JsonObject();
        out.addProperty("grouped_by", by);
        out.addProperty("total", total[0]);
        out.addProperty("groups", ranked.size());
        JsonArray arr = new JsonArray();
        int shown = 0;
        for (Map.Entry<String, Integer> e : ranked) {
            if (shown++ >= 40) break;
            JsonObject o = new JsonObject();
            o.addProperty(by, e.getKey());
            o.addProperty("count", e.getValue());
            arr.add(o);
        }
        if (ranked.size() > shown) {
            out.addProperty("note", "Showing the top " + shown + " of " + ranked.size() + ".");
        }
        out.add("counts", arr);
        return done(out, "counted " + total[0] + " row" + (total[0] == 1 ? "" : "s")
            + " by " + by);
    }

    private static Result activityOverview(Accounts.Account me) {
        String only = WebUi.onlyPlayer(me);
        Map<String, Integer> players = new LinkedHashMap<>();
        Map<String, Integer> actions = new LinkedHashMap<>();
        long[] oldest = {Long.MAX_VALUE};
        long[] newest = {0};
        int[] seen = {0};
        ActivityLog.page(0, 0, e -> {
            if (!WebUi.visible(only, e.player())) return false;
            seen[0]++;
            oldest[0] = Math.min(oldest[0], e.at());
            newest[0] = Math.max(newest[0], e.at());
            players.merge(e.player(), 1, Integer::sum);
            actions.merge(e.action(), 1, Integer::sum);
            return false;
        });

        AlminConfig cfg = AlminConfig.get();
        JsonObject out = new JsonObject();
        out.addProperty("rows_kept", seen[0]);
        out.addProperty("now", stamp(System.currentTimeMillis()));
        if (seen[0] > 0) {
            out.addProperty("oldest_row", stamp(oldest[0]));
            out.addProperty("oldest_row_ago", ago(oldest[0]));
            out.addProperty("newest_row", stamp(newest[0]));
            out.addProperty("newest_row_ago", ago(newest[0]));
        }
        out.addProperty("recording", cfg.activityLog ? "on" : "off");
        out.addProperty("kept_for", humanMinutes(cfg.activityRetentionMinutes));
        out.addProperty("row_limit", cfg.activityMaxEntries);
        out.addProperty("history_note", "The log is written to disk, so it survives restarts. "
            + "It reaches back until rows fall out of the retention window or past the row "
            + "limit, whichever comes first — nothing before that exists to be asked about.");
        if (!cfg.activityBlocks) {
            out.addProperty("excluded", "Block placing and breaking are not being recorded.");
        }
        if (!ActivityLog.includeAdmins()) {
            out.addProperty("admins_excluded",
                "Operators and trusted accounts are deliberately never recorded, so their "
                + "actions are absent from the log rather than merely hidden.");
        }
        out.add("players", topOf(players, 40));
        out.add("actions", topOf(actions, 40));
        return done(out, seen[0] + " row" + (seen[0] == 1 ? "" : "s") + " kept, "
            + players.size() + " player" + (players.size() == 1 ? "" : "s"));
    }

    /**
     * The places, as the map draws them.
     *
     * <p>Through the same reader the panel uses, so a question about where
     * somebody lives cannot see further than the map can. Places are built out
     * of rows this account is allowed to read and no others, which is settled
     * before the pass rather than by filtering the answer afterwards.
     */
    private static Result listPlaces(Accounts.Account me, JsonObject a) {
        String only = WebUi.onlyPlayer(me);
        String wantKind = text(a, "kind").toLowerCase(java.util.Locale.ROOT);
        String wantWho = text(a, "player");
        int limit = clamp(num(a, "limit", 20), 1, 40);

        List<ActivityEntry> rows = new ArrayList<>();
        for (ActivityEntry e : ActivityLog.recent(WebUi.placeRows())) {
            if (WebUi.visible(only, e.player())) rows.add(e);
        }

        JsonArray arr = new JsonArray();
        int found = 0;
        for (Places.Place p : Places.of(rows)) {
            if (!wantKind.isEmpty() && !p.kind().equals(wantKind)) continue;
            if (!wantWho.isEmpty() && !p.player().equalsIgnoreCase(wantWho)) continue;
            found++;
            if (arr.size() >= limit) continue;
            JsonObject o = new JsonObject();
            o.addProperty("kind", p.kind());
            o.addProperty("what", p.headline());
            o.addProperty("mostly", p.player().isEmpty() ? "shared" : p.player());
            o.addProperty("people", p.people());
            o.addProperty("dimension", p.dim());
            o.addProperty("where", p.x() + "," + p.y() + "," + p.z());
            o.addProperty("radius", p.radius());
            o.addProperty("visits", p.visits());
            o.addProperty("days", p.days());
            o.addProperty("last_used", stamp(p.to()));
            o.addProperty("last_used_ago", ago(p.to()));
            arr.add(o);
        }

        JsonObject out = new JsonObject();
        out.add("places", arr);
        out.addProperty("matched", found);
        if (found == 0) {
            out.addProperty("note", "Nothing in the log recurs enough to be a place yet. A "
                + "place needs somebody to come back to it on several separate occasions, so a "
                + "young server, or one whose log does not reach back far, has none.");
        }
        return done(out, found + " place" + (found == 1 ? "" : "s"));
    }

    private static Result playerPositions(Accounts.Account me, JsonObject a) {
        String name = text(a, "player");
        if (name.isEmpty()) return refuse("Which player? Pass a name.");
        String only = WebUi.onlyPlayer(me);
        if (!WebUi.visible(only, name)) {
            return refuse("This account may only look at its own activity.");
        }
        int limit = clamp(num(a, "limit", 40), 1, MAX_POINTS);
        Window w = window(a);

        List<PlayerTrackPoint> all = PlayerTracks.of(name);
        List<PlayerTrackPoint> kept = new ArrayList<>();
        for (PlayerTrackPoint p : all) if (w.holds(p.at())) kept.add(p);
        if (kept.isEmpty()) {
            JsonObject empty = new JsonObject();
            empty.addProperty("player", name);
            empty.addProperty("positions", 0);
            empty.addProperty("note", all.isEmpty()
                ? "No positions have been recorded for that name. Check the spelling with "
                  + "list_players."
                : "Positions exist for that player but none inside the window asked for.");
            return done(empty, "no path for " + name);
        }

        // Thinned across the whole window rather than truncated at the front.
        // The first forty samples of a two-hour walk are forty samples of the
        // first ten minutes, which answers a different question than the one
        // that was asked.
        JsonArray arr = new JsonArray();
        double step = kept.size() <= limit ? 1 : (double) kept.size() / limit;
        for (double i = 0; i < kept.size() && arr.size() < limit; i += step) {
            PlayerTrackPoint p = kept.get((int) i);
            JsonObject o = new JsonObject();
            o.addProperty("when", stamp(p.at()));
            o.addProperty("ago", ago(p.at()));
            o.addProperty("dim", p.dim());
            o.addProperty("x", p.x());
            o.addProperty("y", p.y());
            o.addProperty("z", p.z());
            arr.add(o);
        }
        JsonObject out = new JsonObject();
        out.addProperty("player", name);
        out.addProperty("samples_in_window", kept.size());
        out.addProperty("returned", arr.size());
        if (kept.size() > arr.size()) {
            out.addProperty("note", "Thinned evenly across the window, so this is the shape of "
                + "the path rather than every step of it.");
        }
        out.add("positions", arr);
        return done(out, arr.size() + " position" + (arr.size() == 1 ? "" : "s")
            + " for " + name);
    }

    // ---------- players ----------

    private static Result listPlayers(JsonObject a) {
        net.minecraft.server.MinecraftServer server = WebUi.bound();
        if (server == null) return refuse("The Minecraft server is not running.");
        int limit = clamp(num(a, "limit", 30), 1, 100);
        String sort = text(a, "sort").toLowerCase(Locale.ROOT);

        Map<java.util.UUID, PlayerHistory.Entry> known = WebUi.onServerThread(
            () -> PlayerHistory.get(server).snapshot(), Map.of());
        java.util.Set<String> online = WebUi.onServerThread(
            () -> {
                java.util.Set<String> names = new java.util.HashSet<>();
                for (net.minecraft.server.level.ServerPlayer p
                        : server.getPlayerList().getPlayers()) {
                    names.add(p.getGameProfile().name());
                }
                return names;
            }, java.util.Set.of());

        List<PlayerHistory.Entry> rows = new ArrayList<>(known.values());
        rows.sort(switch (sort) {
            case "playtime" -> (x, y) -> Long.compare(y.playtimeMillis(), x.playtimeMillis());
            case "joins" -> (x, y) -> Integer.compare(y.joins(), x.joins());
            case "name" -> (x, y) -> x.name().compareToIgnoreCase(y.name());
            default -> (x, y) -> Long.compare(y.lastSeen(), x.lastSeen());
        });

        JsonArray arr = new JsonArray();
        for (PlayerHistory.Entry e : rows) {
            if (arr.size() >= limit) break;
            arr.add(playerJson(e, online.contains(e.name())));
        }
        JsonObject out = new JsonObject();
        out.addProperty("known", rows.size());
        out.addProperty("online_now", online.size());
        out.addProperty("returned", arr.size());
        out.add("players", arr);
        return done(out, rows.size() + " known, " + online.size() + " online");
    }

    private static Result playerSummary(Accounts.Account me, JsonObject a) {
        net.minecraft.server.MinecraftServer server = WebUi.bound();
        if (server == null) return refuse("The Minecraft server is not running.");
        String name = text(a, "player");
        if (name.isEmpty()) return refuse("Which player? Pass a name.");

        PlayerHistory.Entry found = WebUi.onServerThread(() -> {
            for (PlayerHistory.Entry e : PlayerHistory.get(server).snapshot().values()) {
                if (name.equalsIgnoreCase(e.name())) return e;
            }
            return null;
        }, null);
        if (found == null) {
            return refuse("No player called " + name + " has been seen. list_players has the "
                + "names that exist.");
        }
        boolean online = Boolean.TRUE.equals(WebUi.onServerThread(() -> {
            for (net.minecraft.server.level.ServerPlayer p
                    : server.getPlayerList().getPlayers()) {
                if (found.name().equalsIgnoreCase(p.getGameProfile().name())) return true;
            }
            return false;
        }, false));

        JsonObject out = playerJson(found, online);
        // The log half only if this account may read the log at all, which is
        // a different permission from the one that got them this far.
        if (may(me, "activity")) {
            String only = WebUi.onlyPlayer(me);
            Map<String, Integer> actions = new LinkedHashMap<>();
            int[] seen = {0};
            ActivityLog.page(0, 0, e -> {
                if (!found.name().equalsIgnoreCase(e.player())) return false;
                if (!WebUi.visible(only, e.player())) return false;
                seen[0]++;
                actions.merge(e.action(), 1, Integer::sum);
                return false;
            });
            out.addProperty("recorded_rows", seen[0]);
            out.add("recorded_actions", topOf(actions, 25));
        }
        return done(out, name + ": " + (online ? "online" : "last seen " + ago(found.lastSeen())));
    }

    private static JsonObject playerJson(PlayerHistory.Entry e, boolean online) {
        JsonObject o = new JsonObject();
        o.addProperty("name", e.name());
        o.addProperty("online", online);
        if (e.firstSeen() > 0) {
            o.addProperty("first_seen", stamp(e.firstSeen()));
            o.addProperty("first_seen_ago", ago(e.firstSeen()));
        }
        if (e.lastSeen() > 0) {
            o.addProperty("last_seen", stamp(e.lastSeen()));
            o.addProperty("last_seen_ago", ago(e.lastSeen()));
        }
        o.addProperty("sessions", e.joins());
        o.addProperty("playtime", humanMillis(e.playtimeMillis()));
        return o;
    }

    // ---------- the server, its mods and its settings ----------

    private static Result serverStatus() {
        net.minecraft.server.MinecraftServer server = WebUi.bound();
        if (server == null) {
            JsonObject o = new JsonObject();
            o.addProperty("running", false);
            o.addProperty("note", "The Minecraft server is not running, so nothing about it "
                + "can be measured. The activity log is still readable.");
            return done(o, "server stopped");
        }
        Dashboard.Metrics m = WebUi.onServerThread(() -> Dashboard.metrics(server), null);
        if (m == null) return refuse("The server did not answer in time.");
        List<String> names = WebUi.onServerThread(() -> {
            List<String> who = new ArrayList<>();
            for (net.minecraft.server.level.ServerPlayer p
                    : server.getPlayerList().getPlayers()) {
                who.add(p.getGameProfile().name());
            }
            return who;
        }, List.of());

        JsonObject o = new JsonObject();
        o.addProperty("running", true);
        o.addProperty("minecraft_version", server.getServerVersion());
        o.addProperty("uptime", humanMillis(m.uptimeMillis()));
        o.addProperty("tps", Math.round(m.tps() * 10) / 10.0);
        o.addProperty("tps_target", m.tpsTarget());
        o.addProperty("ms_per_tick", Math.round(m.mspt() * 10) / 10.0);
        o.addProperty("players_online", m.players());
        o.addProperty("player_slots", m.maxPlayers());
        o.addProperty("memory_used_percent", m.memPct());
        o.addProperty("loaded_chunks", m.chunks());
        o.addProperty("entities", m.entities());
        o.addProperty("dimensions", m.dimensions());
        JsonArray who = new JsonArray();
        for (String n : names) who.add(n);
        o.add("online", who);
        return done(o, m.players() + " online, " + Math.round(m.tps() * 10) / 10.0 + " tps");
    }

    private static Result listMods(JsonObject a) {
        String who = text(a, "player");
        if (who.isEmpty()) {
            net.minecraft.server.MinecraftServer server = WebUi.bound();
            if (server == null) return refuse("The Minecraft server is not running.");
            List<ServerMods.Installed> installed = ServerMods.list(server);
            JsonArray arr = new JsonArray();
            for (ServerMods.Installed i : installed) {
                if (arr.size() >= 120) break;
                JsonObject o = new JsonObject();
                o.addProperty("id", i.modId());
                o.addProperty("name", i.name());
                o.addProperty("version", i.version());
                o.addProperty("file", i.file());
                o.addProperty("enabled", i.enabled());
                o.addProperty("loaded", i.loaded());
                arr.add(o);
            }
            JsonObject out = new JsonObject();
            out.addProperty("side", "server");
            out.addProperty("count", installed.size());
            out.add("mods", arr);
            return done(out, installed.size() + " server mod"
                + (installed.size() == 1 ? "" : "s"));
        }

        ClientProfiles.Profile profile = null;
        for (ClientProfiles.Profile p : ClientProfiles.all()) {
            if (who.equalsIgnoreCase(p.name())) { profile = p; break; }
        }
        if (profile == null) {
            return refuse("No client mod list has been reported for " + who + ". Only players "
                + "running the Almin client mod report one.");
        }
        java.util.Set<String> restricted = new java.util.HashSet<>(
            ClientProfiles.restricted(profile));
        JsonArray arr = new JsonArray();
        for (ClientProfiles.Mod m : profile.mods()) {
            if (arr.size() >= 200) break;
            JsonObject o = new JsonObject();
            o.addProperty("id", m.id());
            o.addProperty("version", m.version());
            if (m.removedAt() > 0) o.addProperty("removed", ago(m.removedAt()));
            if (restricted.contains(m.id())) o.addProperty("restricted", true);
            arr.add(o);
        }
        JsonObject out = new JsonObject();
        out.addProperty("side", "client");
        out.addProperty("player", profile.name());
        out.addProperty("reported", stamp(profile.at()));
        out.addProperty("count", profile.mods().size());
        out.addProperty("restricted_count", restricted.size());
        out.add("mods", arr);
        return done(out, profile.mods().size() + " client mod"
            + (profile.mods().size() == 1 ? "" : "s") + " for " + profile.name()
            + (restricted.isEmpty() ? "" : ", " + restricted.size() + " restricted"));
    }

    private static Result settingsSummary(JsonObject a) {
        String find = text(a, "find").toLowerCase(Locale.ROOT);
        JsonArray arr = new JsonArray();
        int hidden = 0;
        AlminConfig cfg = AlminConfig.get();
        for (AlminConfig.Key k : AlminConfig.KEYS) {
            // The one value the panel itself refuses to ship. It is a password
            // equivalent offline, and no question about the server needs it.
            if (k.name.equals("web-admin-password-hash")) { hidden++; continue; }
            if (!find.isEmpty() && !k.name.toLowerCase(Locale.ROOT).contains(find)) continue;
            JsonObject o = new JsonObject();
            o.addProperty("name", k.name);
            o.addProperty("value", k.display(cfg));
            o.addProperty("means", k.description);
            arr.add(o);
        }
        JsonObject out = new JsonObject();
        out.addProperty("shown", arr.size());
        if (hidden > 0) {
            out.addProperty("withheld", hidden + " secret setting"
                + (hidden == 1 ? "" : "s") + " withheld. The model API key is not a setting at "
                + "all and is not readable from here either.");
        }
        out.add("settings", arr);
        return done(out, arr.size() + " setting" + (arr.size() == 1 ? "" : "s"));
    }

    // ---------- shared readers ----------

    /**
     * One row as the model sees it. The detail goes through the panel's own
     * {@code detailFor}, which is what keeps an account barred from reading
     * chat from reading it here.
     */
    private static JsonObject row(Accounts.Account me, ActivityEntry e) {
        JsonObject o = new JsonObject();
        o.addProperty("when", stamp(e.at()));
        o.addProperty("ago", ago(e.at()));
        o.addProperty("player", e.player());
        o.addProperty("action", e.action());
        String detail = WebUi.detailFor(me, e);
        if (!detail.isEmpty()) o.addProperty("detail", detail);
        if (e.count() > 1) o.addProperty("times", e.count());
        if (!coordsHidden(me) && !e.where().isEmpty()) o.addProperty("where", e.where());
        else if (e.dim() != null && !e.dim().isEmpty()) o.addProperty("dim", e.dim());
        return o;
    }

    /**
     * What free text is matched against. Built from what this account may
     * actually see, so the filter cannot be used as an oracle for a detail the
     * account is not allowed to read: searching for a word from a hidden chat
     * line finds nothing, exactly as if the line said nothing.
     */
    private static String haystack(Accounts.Account me, ActivityEntry e) {
        return (e.player() + " " + e.action() + " " + WebUi.detailFor(me, e) + " " + e.where())
            .toLowerCase(Locale.ROOT);
    }

    private static String bucket(String by, ActivityEntry e) {
        return switch (by) {
            case "action" -> e.action();
            case "dimension", "dim" -> e.dim() == null || e.dim().isEmpty() ? "(none)" : e.dim();
            case "day" -> stamp(e.at()).substring(0, 10);
            case "hour" -> stamp(e.at()).substring(0, 13) + ":00";
            default -> e.player();
        };
    }

    private static JsonArray topOf(Map<String, Integer> counts, int limit) {
        List<Map.Entry<String, Integer>> ranked = new ArrayList<>(counts.entrySet());
        ranked.sort((x, y) -> y.getValue() - x.getValue());
        JsonArray arr = new JsonArray();
        for (Map.Entry<String, Integer> e : ranked) {
            if (arr.size() >= limit) break;
            JsonObject o = new JsonObject();
            o.addProperty("name", e.getKey());
            o.addProperty("count", e.getValue());
            arr.add(o);
        }
        return arr;
    }

    /** A half-open span of time, from the two "minutes ago" arguments. */
    private record Window(long from, long to) {
        boolean holds(long at) { return at >= from && at <= to; }
        boolean whole() { return from == Long.MIN_VALUE && to == Long.MAX_VALUE; }
    }

    private static Window window(JsonObject a) {
        long now = System.currentTimeMillis();
        int since = num(a, "since_minutes", 0);
        int before = num(a, "before_minutes", 0);
        long from = since > 0 ? now - since * 60_000L : Long.MIN_VALUE;
        long to = before > 0 ? now - before * 60_000L : Long.MAX_VALUE;
        return new Window(from, to);
    }

    private static String describe(String find, String player, String action,
                                   String dim, Window w) {
        List<String> bits = new ArrayList<>();
        if (!find.isEmpty()) bits.add("matching “" + find + "”");
        if (!player.isEmpty()) bits.add("by " + player);
        if (!action.isEmpty()) bits.add("of kind " + action);
        if (!dim.isEmpty()) bits.add("in " + dim);
        if (!w.whole()) bits.add("in a time window");
        return bits.isEmpty() ? "" : " — " + String.join(", ", bits);
    }

    // ---------- results ----------

    private static Result done(JsonObject body, String note) {
        String json = body.toString();
        if (json.length() > MAX_RESULT) {
            // Trimming JSON by the character would hand the model something it
            // cannot parse, so the reply is replaced by one it can: an object
            // that says the answer was too big and what to do instead.
            JsonObject small = new JsonObject();
            small.addProperty("error", "That answer was too large to return ("
                + json.length() + " characters, limit " + MAX_RESULT + "). Ask for fewer rows, "
                + "narrow it with a player, action or time window, or use count_activity if a "
                + "total is what you need.");
            json = small.toString();
            note = note + " (too large, not returned)";
        }
        return new Result(json, note);
    }

    private static Result refuse(String why) {
        JsonObject o = new JsonObject();
        o.addProperty("error", why);
        return new Result(o.toString(), why);
    }

    // ---------- arguments ----------

    private static String text(JsonObject a, String name) {
        if (a == null || !a.has(name) || a.get(name).isJsonNull()) return "";
        try { return a.get(name).getAsString().trim(); }
        catch (RuntimeException e) { return ""; }
    }

    private static int num(JsonObject a, String name, int fallback) {
        if (a == null || !a.has(name) || a.get(name).isJsonNull()) return fallback;
        try { return a.get(name).getAsInt(); }
        catch (RuntimeException e) {
            // Models sometimes send "30" or "thirty". The first is worth saving.
            try { return Integer.parseInt(text(a, name)); }
            catch (NumberFormatException ignored) { return fallback; }
        }
    }

    private static int clamp(int v, int low, int high) {
        return Math.max(low, Math.min(high, v));
    }

    // ---------- schemas ----------

    private static JsonObject props() { return new JsonObject(); }

    private static void string(JsonObject props, String name, String description) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "string");
        o.addProperty("description", description);
        props.add(name, o);
    }

    private static void integer(JsonObject props, String name, String description) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "integer");
        o.addProperty("description", description);
        props.add(name, o);
    }

    private static void required(JsonObject props, String name) {
        JsonObject o = props.getAsJsonObject(name);
        if (o != null) o.addProperty("almin_required", true);
    }

    /**
     * The JSON Schema every provider is handed. The required list is rebuilt
     * from the marks {@link #required} left, so a tool declares its arguments
     * in one place rather than in a list that drifts away from them.
     */
    private static JsonObject schema(JsonObject props) {
        JsonArray must = new JsonArray();
        for (String name : new ArrayList<>(props.keySet())) {
            JsonObject o = props.getAsJsonObject(name);
            if (o.has("almin_required")) { o.remove("almin_required"); must.add(name); }
        }
        JsonObject out = new JsonObject();
        out.addProperty("type", "object");
        out.add("properties", props);
        out.add("required", must);
        return out;
    }

    // ---------- time, said the way people say it ----------

    private static final java.time.format.DateTimeFormatter STAMP =
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    static String stamp(long at) {
        return java.time.Instant.ofEpochMilli(at)
            .atZone(java.time.ZoneId.systemDefault()).format(STAMP);
    }

    static String ago(long at) {
        long ms = System.currentTimeMillis() - at;
        if (ms < 0) return "just now";
        return humanMillis(ms) + " ago";
    }

    static String humanMillis(long ms) {
        long secs = ms / 1000;
        if (secs < 60) return secs + "s";
        long mins = secs / 60;
        if (mins < 60) return mins + "m";
        long hours = mins / 60;
        if (hours < 48) return hours + "h " + (mins % 60) + "m";
        long days = hours / 24;
        return days + "d " + (hours % 24) + "h";
    }

    static String humanMinutes(int minutes) {
        return humanMillis(minutes * 60_000L);
    }

    private static String menuName(String menu) {
        return switch (menu) {
            case "activity" -> "Activity";
            case "players" -> "Players";
            case "dash" -> "Overview";
            case "mods" -> "Mods";
            case "settings" -> "Settings";
            default -> menu;
        };
    }
}
