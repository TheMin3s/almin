package com.schecks.almin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The server's history, one line a day.
 *
 * <h3>What this is, and what it is not</h3>
 * {@link AiInsights} summarises a <em>period</em> — whatever the map is
 * currently showing — and rewrites that paragraph whenever the period changes.
 * It answers "what has been going on". This answers a different question: "what
 * has happened here, since the beginning". A fortnight of that is not a
 * paragraph, it is a list, and each entry has to be short enough that fourteen
 * of them can be read at a glance.
 *
 * <p>So each day gets one sentence and no more. Not a report of the day — a
 * line in a history, of the kind somebody would write in a book afterwards.
 *
 * <h3>Why it is written a day at a time</h3>
 * Three reasons, and they all point the same way.
 *
 * <p>A fortnight of episodes does not fit in a small prompt, and the whole
 * design of the AI features here is that they work on a 3B model on the same
 * machine. One day of episodes does fit.
 *
 * <p>A day that is over never changes. Summarising it twice is spending money
 * to be told the same thing, so a written day is kept — on disk, so a restart
 * does not re-bill the whole history — and only today's line is ever rewritten.
 *
 * <p>And a history that fills in a few days at a time can be watched. Fourteen
 * requests fired at a paid service the moment somebody opens a tab is a bad
 * surprise; {@link #STEP} days per press is a decision somebody keeps making.
 *
 * <h3>Where it goes</h3>
 * Through {@link AiTransport}, like everything else here, to whatever
 * {@code ai-provider} says. Off by default, and the same consent applies: a
 * remote provider receives player names and places, and unless
 * {@code ai-send-chat} is off, what people said.
 */
public final class AiStory {

    /** Days written per press. Small on purpose: a bill somebody can watch. */
    private static final int STEP = 4;

    /** Below this a day is a handful of rows, and there is nothing to say. */
    private static final int MIN_EVENTS = 12;

    /** Episodes handed to the model for one day. Enough to see the shape. */
    private static final int MAX_EPISODES = 40;

    /** Days kept. A history longer than the log it came from is a fiction. */
    private static final int MAX_DAYS = 60;

    /** What the model is for here, said once. */
    private static final String SYSTEM =
        "You write one sentence of history per day for a Minecraft server. You are given "
        + "what happened on one day, already worked out from the server's records. Reply "
        + "with a single plain sentence, at most 22 words, naming the players who mattered "
        + "and what they did. Write it the way a chronicle entry is written: past tense, no "
        + "preamble, no bullet points, no quotation marks, no date at the front. If nothing "
        + "of consequence happened, say so in a few words.";

    private AiStory() {}

    /**
     * One day, in a line.
     *
     * @param whole whether the log still holds the whole of this day. A day
     *              the log has begun forgetting reads as a quiet one \u2014 the
     *              hours that expired look exactly like hours nobody played
     *              \u2014 so a sentence written from it would be confidently
     *              wrong, and none is written.
     */
    public record Day(long at, String line, int events, int players, boolean written,
                      boolean whole) {}

    /**
     * The history as it stands, and how much of it is not written yet.
     *
     * @param days     newest first, both written and unwritten
     * @param missing  how many days have activity but no line
     * @param problem  why nothing can be written, or empty
     */
    public record Story(long generated, List<Day> days, int missing, String problem,
                        String error) {

        public boolean ok() { return error == null || error.isEmpty(); }
    }

    // ---------- what has been written ----------

    /** day-start millis -> the line, and the row count it was written from. */
    private record Written(String line, int events) {}

    private static final Map<Long, Written> lines = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile Path file;
    private static final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Points this at where the history is kept, and reads back what is there.
     *
     * <p>Beside the key rather than in {@code config.json}: it is a cache, not
     * a setting, and nobody should have to scroll past a fortnight of prose to
     * find the port number.
     */
    public static synchronized void init(Path serverDir) {
        file = serverDir.resolve("config").resolve("almin").resolve("ai-story.json");
        lines.clear();
        try {
            if (!Files.exists(file)) return;
            JsonObject root = JsonParser.parseString(
                Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            for (String key : root.keySet()) {
                JsonObject o = root.getAsJsonObject(key);
                lines.put(Long.parseLong(key), new Written(
                    o.get("line").getAsString(), o.get("events").getAsInt()));
            }
        } catch (RuntimeException | IOException e) {
            // A cache that cannot be read is a cache that gets written again.
            AlminLog.warn("[almin] could not read the story: {}", e.toString());
            lines.clear();
        }
    }

    /** Forgets everything written, on disk as well. For a wipe, and the tests. */
    public static synchronized void forget() {
        lines.clear();
        Path f = file;
        if (f == null) return;
        try {
            Files.deleteIfExists(f);
        } catch (IOException e) {
            AlminLog.warn("[almin] could not delete the story: {}", e.toString());
        }
    }

    private static synchronized void save() {
        Path f = file;
        if (f == null) return;
        try {
            JsonObject root = new JsonObject();
            for (Map.Entry<Long, Written> e : new TreeMap<>(lines).entrySet()) {
                JsonObject o = new JsonObject();
                o.addProperty("line", e.getValue().line());
                o.addProperty("events", e.getValue().events());
                root.add(String.valueOf(e.getKey()), o);
            }
            Files.createDirectories(f.getParent());
            Files.writeString(f, root.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            AlminLog.warn("[almin] could not save the story: {}", e.toString());
        }
    }

    // ---------- reading ----------

    /**
     * What days there are, and what has been said about each. No model.
     *
     * @param oldest when the log's earliest surviving row is, from
     *               {@link ActivityLog#span()}. It is what decides whether a
     *               day is whole: a day that began before the log's own
     *               memory does is a day the log has already lost part of.
     *               Zero means "not known", and then nothing is treated as
     *               partial \u2014 the old behaviour, for callers that cannot
     *               say.
     */
    public static Story of(List<ActivityEntry> rows, long oldest) {
        return of(rows, oldest, false, 0);
    }

    /**
     * The history, optionally writing the days that have none.
     *
     * <p>Blocking when {@code write} is set, and called from a web thread —
     * never from the server thread.
     */
    public static Story write(List<ActivityEntry> rows, long oldest) {
        return of(rows, oldest, true, STEP);
    }

    /**
     * Whether the log still holds every hour of one day.
     *
     * <p>The log forgets from the back, so the only day this can be false for
     * is the earliest one it still has anything from \u2014 and that is
     * exactly the day whose history would otherwise be written from whatever
     * fragment survived. Today counts as whole when the log reaches back past
     * midnight: it is unfinished rather than incomplete, which is a different
     * thing and one everybody already understands about today.
     *
     * <p>Zero \u2014 "not known" \u2014 needs no special case and does not get
     * one: it is before every real day, so a caller that cannot say where the
     * log begins gets every day treated as whole, which is the behaviour there
     * was before any of this.
     */
    static boolean whole(long day, long oldest) {
        return oldest <= day;
    }

    private static Story of(List<ActivityEntry> rows, long oldest, boolean write, int budget) {
        Map<Long, List<ActivityEntry>> byDay = new TreeMap<>(Collections.reverseOrder());
        for (ActivityEntry e : rows) {
            byDay.computeIfAbsent(dayOf(e.at()), k -> new ArrayList<>()).add(e);
        }

        String problem = AiInsights.problem();
        List<Day> out = new ArrayList<>();
        int missing = 0;
        int wrote = 0;
        String error = "";

        for (Map.Entry<Long, List<ActivityEntry>> e : byDay.entrySet()) {
            if (out.size() >= MAX_DAYS) break;
            long day = e.getKey();
            List<ActivityEntry> mine = e.getValue();
            int people = people(mine);
            boolean whole = whole(day, oldest);
            if (mine.size() < MIN_EVENTS) {
                // A day with a handful of rows in it has no history in it. It
                // is still shown, because a gap in a timeline is information.
                out.add(new Day(day, "", mine.size(), people, false, whole));
                continue;
            }

            Written had = lines.get(day);
            // Today is the only day that can still change. A finished day
            // whose row count has moved has had rows expire off the back of
            // the log, which is not new history and not worth re-writing.
            boolean stale = had != null && isToday(day) && had.events() != mine.size();
            if (had != null && !stale) {
                out.add(new Day(day, had.line(), mine.size(), people, true, whole));
                continue;
            }

            // A past day the log has begun forgetting can never be written
            // truthfully, so it is shown and not counted as missing. Counting
            // it would leave the button offering to write a day it will refuse
            // to write, for as long as the day survives at all.
            if (!whole) {
                out.add(new Day(day, had == null ? "" : had.line(), mine.size(), people,
                    had != null, false));
                continue;
            }

            missing++;
            if (!write || !problem.isEmpty() || wrote >= budget) {
                out.add(new Day(day, had == null ? "" : had.line(), mine.size(), people,
                    had != null, whole));
                continue;
            }
            if (!running.compareAndSet(false, true)) {
                error = "Already writing — try again in a moment.";
                out.add(new Day(day, "", mine.size(), people, false, whole));
                continue;
            }
            try {
                String line = writeDay(day, mine);
                wrote++;
                if (line.isEmpty()) {
                    out.add(new Day(day, "", mine.size(), people, false, whole));
                } else {
                    lines.put(day, new Written(line, mine.size()));
                    missing--;
                    out.add(new Day(day, line, mine.size(), people, true, whole));
                }
            } catch (IOException ex) {
                error = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                out.add(new Day(day, "", mine.size(), people, false, whole));
            } finally {
                running.set(false);
            }
        }

        if (wrote > 0) {
            trim();
            save();
        }
        return new Story(System.currentTimeMillis(), out, missing, problem, error);
    }

    /** One day, put to the model. */
    private static String writeDay(long day, List<ActivityEntry> rows) throws IOException {
        AlminConfig cfg = AlminConfig.get();
        List<Episodes.Episode> episodes = Episodes.of(rows);
        episodes.sort((a, b) -> Integer.compare(b.weight(), a.weight()));

        StringBuilder p = new StringBuilder();
        p.append("Date: ").append(stamp(day)).append('\n');
        p.append("Players: ").append(String.join(", ", names(rows))).append('\n');
        p.append("Recorded events: ").append(rows.size()).append('\n');
        p.append("What happened, worked out from the records:\n");
        int n = 0;
        for (Episodes.Episode ep : episodes) {
            if (n++ >= MAX_EPISODES) break;
            p.append("- ").append(ep.player()).append(": ").append(ep.headline()).append('\n');
        }
        if (n == 0) p.append("- nothing that formed into a recognisable piece of work\n");
        if (cfg.aiSendChat) {
            List<String> said = new ArrayList<>();
            for (ActivityEntry e : rows) {
                if (!"chat".equals(e.action())) continue;
                said.add(e.player() + ": " + e.detail());
                if (said.size() >= 12) break;
            }
            if (!said.isEmpty()) {
                p.append("Some of what was said:\n");
                for (String s : said) p.append("- ").append(s).append('\n');
            }
        }
        p.append("\nWrite the one sentence for this day.");

        String said = AiTransport.ask(cfg, AiInsights.provider(cfg), SYSTEM, p.toString());
        return tidy(said);
    }

    /**
     * The one sentence, out of whatever the model actually sent.
     *
     * <p>Small models answer a request for one sentence with a paragraph, a
     * bulleted list, or the sentence wrapped in an explanation of what they
     * were about to write. All of those are one sentence with something
     * around it, so the something is taken off rather than the answer thrown
     * away.
     */
    public static String tidy(String raw) {
        if (raw == null) return "";
        String s = raw.strip();
        if (s.isEmpty()) return "";
        // A model that returned a list is answering with its first item.
        for (String line : s.split("\n")) {
            String t = line.strip();
            if (t.isEmpty()) continue;
            t = t.replaceFirst("^[-*•]\\s*", "");
            t = t.replaceFirst("^\\d+[.)]\\s*", "");
            // "Here is the sentence:" and its many spellings.
            if (t.endsWith(":") && t.length() < 60) continue;
            s = t;
            break;
        }
        if (s.length() > 1 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1).strip();
        }
        // One sentence: keep up to the first full stop that ends one, so a
        // model that kept going is trimmed rather than quoted at length.
        int cut = -1;
        for (int i = 0; i < s.length() - 1; i++) {
            char c = s.charAt(i);
            if ((c == '.' || c == '!' || c == '?') && s.charAt(i + 1) == ' ') { cut = i + 1; break; }
        }
        if (cut > 0) s = s.substring(0, cut).strip();
        if (s.length() > 240) s = s.substring(0, 237).strip() + "…";
        return s;
    }

    /** The oldest lines go when there are more than a history's worth. */
    private static void trim() {
        if (lines.size() <= MAX_DAYS) return;
        List<Long> days = new ArrayList<>(lines.keySet());
        Collections.sort(days);
        for (int i = 0; i < days.size() - MAX_DAYS; i++) lines.remove(days.get(i));
    }

    // ---------- small things ----------

    /** Midnight before this moment, on the clock the server keeps. */
    public static long dayOf(long at) {
        ZonedDateTime z = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault());
        return z.toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static boolean isToday(long day) {
        return day == dayOf(System.currentTimeMillis());
    }

    private static String stamp(long day) {
        return Instant.ofEpochMilli(day).atZone(ZoneId.systemDefault()).toLocalDate().toString();
    }

    private static int people(List<ActivityEntry> rows) {
        return names(rows).size();
    }

    private static List<String> names(List<ActivityEntry> rows) {
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (ActivityEntry e : rows) {
            if (e.player() != null && !e.player().isEmpty()) seen.put(e.player(), true);
        }
        return new ArrayList<>(seen.keySet());
    }
}
