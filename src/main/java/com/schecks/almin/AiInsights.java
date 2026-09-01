package com.schecks.almin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A paragraph about what happened, written by a language model.
 *
 * <h3>What it is given</h3>
 * Not the log. {@link Episodes} has already turned four thousand rows into
 * forty sentences — "dug a shaft from y 64 down to y 11", "built something 14
 * across and 6 high" — and that is what goes in the prompt. The model's job is
 * the part it is actually good at: reading forty facts about a server and
 * saying which three mattered. The arithmetic is done before it is asked.
 *
 * <p>That matters for more than tidiness. A prompt of forty sentences fits in
 * a 3B model running on the same machine, which is the difference between this
 * being a feature every server can use and a feature with a bill attached.
 *
 * <h3>Where it goes</h3>
 * Wherever {@code ai-provider} says: {@code anthropic}, {@code openai}, or
 * {@code local} — anything speaking the OpenAI chat API at {@code ai-base-url},
 * which is how Ollama, llama.cpp's server and LM Studio all present
 * themselves. {@code local} is the default, and the only one where nothing
 * leaves the machine.
 *
 * <h3>Raw HTTP, not an SDK</h3>
 * Deliberate. Almin ships with no third-party dependencies at all — it encodes
 * its own PNGs rather than pull in {@code java.desktop} — and a server mod
 * bundling a vendor SDK for an optional feature would be a poor trade. It also
 * has to speak to a local Ollama, so one small request builder covering three
 * shapes is less code than two clients.
 *
 * <h3>Consent</h3>
 * Off by default, and the panel says in words what would be sent before the
 * switch will turn on. Turning it on is a decision about other people's data:
 * a remote provider receives player names, places and — unless
 * {@code ai-send-chat} is off — what they said. Nothing is sent until someone
 * asks for a summary, or sets {@code ai-auto-minutes}.
 */
public final class AiInsights {

    /** One moment the model thought was worth pointing at. */
    public record Moment(long at, String label, String why, String player,
                         String dim, int x, int y, int z, int weight) {}

    /**
     * What the model was asked to look at.
     *
     * <p>The same machinery answers three questions — what happened on the
     * server, what one player has been doing, and what is going on in this
     * corner of the map — and they are the same question with a different
     * subject. Keeping the subject in one small object means the prompt, the
     * cache and the panel all agree on which of the three they are holding:
     * before this there was one cached report, and asking about a player
     * quietly replaced the summary of everything.
     */
    public record Scope(String kind, String player, String dim,
                        int x, int z, int radius) {

        public static Scope all() { return new Scope("all", "", "", 0, 0, 0); }

        public static Scope of(String player) {
            return new Scope("player", player == null ? "" : player, "", 0, 0, 0);
        }

        public static Scope area(String dim, int x, int z, int radius) {
            return new Scope("area", "", dim == null ? "" : dim, x, z, Math.max(16, radius));
        }

        /** The cache key. Areas round off, so nudging the map is not a new question. */
        public String key() {
            return switch (kind) {
                case "player" -> "player:" + player.toLowerCase(Locale.ROOT);
                case "area" -> "area:" + dim + ":" + (x >> 7) + ":" + (z >> 7) + ":"
                    + Integer.highestOneBit(Math.max(1, radius));
                default -> "all";
            };
        }

        /** How the prompt introduces itself. */
        public String said() {
            return switch (kind) {
                case "player" -> "Everything below is one player: " + player + ".";
                case "area" -> "Everything below is one corner of the map: within "
                    + radius + " blocks of " + x + "," + z
                    + (dim.isEmpty() ? "" : " in the " + dim) + ".";
                default -> "This is the whole server.";
            };
        }

        /** Whether one episode belongs to this scope. */
        public boolean holds(Episodes.Episode e) {
            if (e == null) return false;
            return switch (kind) {
                case "player" -> player.equalsIgnoreCase(e.player());
                case "area" -> (dim.isEmpty() || dim.equals(e.dim()))
                    && Math.abs(e.x() - x) <= radius && Math.abs(e.z() - z) <= radius;
                default -> true;
            };
        }

        /** Whether one row belongs to this scope. */
        public boolean holds(ActivityEntry e) {
            if (e == null) return false;
            return switch (kind) {
                case "player" -> player.equalsIgnoreCase(e.player());
                case "area" -> (dim.isEmpty() || dim.equals(e.dim()))
                    && Math.abs(e.x() - x) <= radius && Math.abs(e.z() - z) <= radius;
                default -> true;
            };
        }
    }

    /**
     * A stretch the rules did not find.
     *
     * <p>{@link Episodes} knows about a fixed list of shapes: a shaft, a tree,
     * a fight. It is deterministic, free, and it can only ever find what
     * somebody thought to write down. This is the other half — a pattern the
     * model noticed across the timeline that no rule describes: a nightly trip
     * to the same place, three people converging, a twenty-minute loop run
     * over and over.
     *
     * <p>Kept apart from an {@link Episodes.Episode} everywhere, and labelled
     * as the model's own in the panel, because it is a claim rather than a
     * count. Its times are clamped to the window that was actually sent, so a
     * confident model cannot invent an afternoon.
     */
    public record Found(long from, long to, String player, String label, String why) {}

    /**
     * What one stretch of work was probably for.
     *
     * <p>Separate from a {@link Moment}, which points at something worth
     * looking at. This is the other half of the question: not "what should I
     * look at" but "why was that person doing that" — the thing an episode's
     * own sentence cannot say, because it can only see the episode.
     */
    public record Meaning(long at, String player, String means) {}

    /** The last thing the model said, and when. */
    public record Report(long generated, long from, long to, String summary,
                         List<Moment> moments, List<Meaning> meanings, List<Found> found,
                         String scope, String model, String provider, String error) {

        public boolean ok() { return error == null || error.isEmpty(); }

        public static Report failed(String why) {
            return new Report(System.currentTimeMillis(), 0, 0, "", List.of(), List.of(),
                List.of(), "all", "", "", why);
        }
    }

    /**
     * An answer to "what am I looking for", as a filter rather than a list.
     *
     * <p>The tempting design is to have the model return the rows that match
     * and show those. This returns the <em>filter</em> instead — which players,
     * which actions, which things, which stretches — and the panel sets its own
     * controls to it. That difference is the whole point: the person can see
     * what was decided on their behalf, widen it, narrow it, or throw it away,
     * and the map keeps working the way it did a second earlier.
     */
    public record Lens(long generated, String question, String reply,
                       List<Long> episodes, List<String> players, List<String> actions,
                       List<String> items, List<String> kinds, String error) {

        public boolean ok() { return error == null || error.isEmpty(); }

        public static Lens failed(String question, String why) {
            return new Lens(System.currentTimeMillis(), question, "", List.of(), List.of(),
                List.of(), List.of(), List.of(), why);
        }
    }

    /** What the model made of one client's mod list. */
    public record ModFlag(String id, String level, String why) {}

    /** A whole review of one client, flags and all. */
    public record ModReview(long generated, String player, String summary,
                            List<ModFlag> flags, String error) {

        public boolean ok() { return error == null || error.isEmpty(); }

        public static ModReview failed(String player, String why) {
            return new ModReview(System.currentTimeMillis(), player, "", List.of(), why);
        }
    }

    /** How long to wait for the model. Configurable; see ai-timeout-seconds. */
    static Duration timeout() {
        int s = AlminConfig.get().aiTimeoutSeconds;
        return Duration.ofSeconds(s < 5 ? 5 : Math.min(s, 3600));
    }
    /** Episodes handed to the model. Past this the prompt stops being small. */
    private static final int MAX_EPISODES = 60;

    /** Chat lines handed to the model, newest kept. */
    private static final int MAX_CHAT = 40;

    /** Nothing is asked twice inside this window; the panel gets the cache. */
    private static final long COOLDOWN_MS = 20_000;

    /**
     * The last answer for each scope.
     *
     * <p>One field was enough while there was one question. Now that the panel
     * can ask about a player and about a corner of the map, a single slot
     * meant opening a player replaced the summary of the server with a
     * paragraph about one person — and nothing said so.
     */
    private static final Map<String, Report> cache = new java.util.concurrent.ConcurrentHashMap<>();

    /** Ceiling on remembered scopes, so a busy panel cannot grow this. */
    private static final int MAX_CACHED = 24;

    private static volatile long lastAsked;
    /** One request at a time: the panel has a button and people press buttons. */
    private static final AtomicBoolean running = new AtomicBoolean(false);

    private static volatile Path keyFile;

    private AiInsights() {}

    // ---------- the key ----------

    /**
     * Where the API key lives: its own file, not {@code config.json}.
     *
     * <p>config.json is served by the panel's file browser and rewritten
     * whenever a setting changes. A credential in it would be readable by
     * anything that can read a config file and would end up in any copy of it
     * anyone ever pasted into a bug report. {@link WebFiles} refuses to serve
     * this file by name.
     */
    public static synchronized void init(java.nio.file.Path serverDir) {
        keyFile = serverDir.resolve("config").resolve("almin").resolve("ai-key");
        if (timer != null) return;
        timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Almin-ai");
            t.setDaemon(true);
            return t;
        });
        // Checked every minute; whether it does anything is ai-auto-minutes.
        timer.scheduleWithFixedDelay(AiInsights::maybeAuto, 60, 60,
            java.util.concurrent.TimeUnit.SECONDS);
    }

    private static java.util.concurrent.ScheduledExecutorService timer;

    public static synchronized void close() {
        if (timer != null) { timer.shutdownNow(); timer = null; }
    }

    /**
     * The unattended summary, if one was asked for.
     *
     * <p>Off unless {@code ai-auto-minutes} is set, because this is the one
     * path that talks to a paid service without anyone pressing anything, and
     * a feature that quietly runs up a bill is a bad feature. It also skips
     * when nothing has been recorded since the last one — re-summarising an
     * unchanged log is spending money to be told the same thing.
     */
    private static void maybeAuto() {
        try {
            AlminConfig cfg = AlminConfig.get();
            int every = cfg.aiAutoMinutes;
            if (!cfg.aiEnabled || every <= 0) return;
            long now = System.currentTimeMillis();
            if (now - lastAsked < every * 60_000L) return;
            if (!problem().isEmpty()) return;

            List<ActivityEntry> rows = ActivityLog.recent(2500);
            if (rows.isEmpty()) return;
            long newest = 0, oldest = Long.MAX_VALUE;
            for (ActivityEntry e : rows) {
                newest = Math.max(newest, e.at());
                oldest = Math.min(oldest, e.at());
            }
            Report have = cache.get("all");
            if (have != null && have.ok() && newest <= have.generated() - COOLDOWN_MS) return;

            List<Episodes.Episode> episodes = Episodes.of(rows);
            summarise(Scope.all(), episodes, rows, oldest, newest, 0, true);
        } catch (Throwable t) {
            AlminLog.warn("[almin] unattended summary failed: {}", t.toString());
        }
    }

    /** The file the key is kept in, for anything that needs to refuse to serve it. */
    public static String keyFileName() { return "ai-key"; }

    public static synchronized boolean hasKey() {
        return !key().isEmpty();
    }

    static synchronized String key() {
        Path f = keyFile;
        if (f == null || !Files.isRegularFile(f)) return "";
        try {
            return Files.readString(f, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    /** Stores (or, given blank, forgets) the API key. */
    public static synchronized boolean setKey(String value) {
        Path f = keyFile;
        if (f == null) return false;
        try {
            Files.createDirectories(f.getParent());
            if (value == null || value.isBlank()) {
                Files.deleteIfExists(f);
                return true;
            }
            Files.writeString(f, value.trim(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
            // Best effort: on a POSIX host, only the account running the server.
            try {
                Files.setPosixFilePermissions(f,
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException | IOException ignoredPerms) {
                // Windows, or a filesystem without them. The file is still not served.
            }
            return true;
        } catch (IOException e) {
            AlminLog.warn("[almin] could not save the AI key: {}", e.getMessage());
            return false;
        }
    }

    // ---------- what the panel asks for ----------

    /** The last report for one scope, or null if nothing has been asked. */
    public static Report cached(Scope scope) {
        return cache.get(scope == null ? "all" : scope.key());
    }

    /** The last report about the server as a whole. */
    public static Report cached() { return cache.get("all"); }

    public static void forget() { cache.clear(); }

    /**
     * Whether a provider is configured well enough to be worth asking.
     *
     * @return the reason it is not, or an empty string if it is
     */
    public static String problem() {
        AlminConfig cfg = AlminConfig.get();
        if (!cfg.aiEnabled) return "Summaries are off (ai-enabled).";
        String provider = provider(cfg);
        if (cfg.aiModel == null || cfg.aiModel.isBlank()) return "No model set (ai-model).";
        if (addressed(provider)) {
            if (cfg.aiBaseUrl == null || cfg.aiBaseUrl.isBlank()) {
                return provider.equals("local")
                    ? "No address for the local model (ai-base-url)."
                    : "No address for the service (ai-base-url).";
            }
            String bad = endpointProblem(cfg.aiBaseUrl);
            if (!bad.isEmpty()) return bad;
            // A key is optional here on purpose: a model on this machine
            // usually wants none, and a service that does will say 401 in
            // words rather than being guessed at from here.
            return "";
        }
        if (!hasKey()) return "No API key set for " + provider + ".";
        return "";
    }

    static String provider(AlminConfig cfg) {
        String p = cfg.aiProvider == null ? "" : cfg.aiProvider.trim().toLowerCase(Locale.ROOT);
        return switch (p) {
            case "anthropic", "openai", "local", "custom", "google" -> p;
            default -> "local";
        };
    }

    /**
     * Whether this provider is somewhere else, reachable at an address the
     * admin gives.
     *
     * <p>{@code local} and {@code custom} are the same request to the same
     * shape of API; they differ only in what the panel says about them and in
     * whether a key is expected. Keeping them apart matters because "no key
     * needed" is true of a model on this machine and false of almost every
     * service that is not.
     */
    private static boolean addressed(String provider) {
        return provider.equals("local") || provider.equals("custom");
    }

    /** A base URL has to be one, and has to be http. Anything else is a typo. */
    private static String endpointProblem(String base) {
        try {
            URI u = URI.create(base.trim());
            String scheme = u.getScheme();
            if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
                return "ai-base-url must start with http:// or https://";
            }
            if (u.getHost() == null || u.getHost().isBlank()) return "ai-base-url has no host.";
            if (u.getUserInfo() != null) return "Put the key in the key file, not in the URL.";
            return "";
        } catch (IllegalArgumentException e) {
            // Almost always a missing scheme — "127.0.0.1:11434" does not
            // parse, and the reason is not obvious from "not a URL".
            return "ai-base-url must start with http:// — e.g. http://127.0.0.1:11434/v1";
        }
    }

    /**
     * Asks the model about one scope, or hands back what it said a moment ago.
     *
     * <p>Blocking, and called from a web thread — never from the server
     * thread. The rows are gathered by the caller, which already holds them;
     * everything the prompt needs — the episodes in scope, the chat, the
     * timeline — is cut out of them here, so a caller cannot forget one.
     */
    public static Report summarise(Scope scope, List<Episodes.Episode> episodes,
                                   List<ActivityEntry> rows,
                                   long from, long to, int online, boolean force) {
        Scope where = scope == null ? Scope.all() : scope;
        String why = problem();
        if (!why.isEmpty()) return Report.failed(why);

        Report have = cache.get(where.key());
        long now = System.currentTimeMillis();
        if (!force && have != null && now - have.generated() < COOLDOWN_MS) return have;
        if (!running.compareAndSet(false, true)) {
            return have != null ? have : Report.failed("Already thinking — try again in a moment.");
        }
        try {
            lastAsked = now;
            AlminConfig cfg = AlminConfig.get();
            List<Episodes.Episode> mine = new ArrayList<>();
            for (Episodes.Episode e : episodes) if (where.holds(e)) mine.add(e);
            List<ActivityEntry> inScope = new ArrayList<>();
            for (ActivityEntry e : rows) if (where.holds(e)) inScope.add(e);
            List<ActivityEntry> chat = new ArrayList<>();
            for (ActivityEntry e : inScope) if ("chat".equals(e.action())) chat.add(e);

            String prompt = prompt(where, mine, inScope, chat, from, to, online, cfg.aiSendChat);
            byte[] sceneImage = cfg.aiSendSceneImages ? AiSceneImage.render(mine, inScope) : null;
            if (sceneImage != null) prompt += """

                A block-layout diagram is attached. It contains up to six bordered panels,
                in the same order as the first spatial episodes above. In each panel the
                large upper view is X/Z from above and the lower strip is elevation. Gold
                marks are placements and red marks are breaks. Disconnected dots are
                scattered edits, not evidence of a building merely because their bounding
                box is wide or tall.
                """;
            String text = ask(cfg, prompt, sceneImage);
            Report report = parse(text, from, to, cfg, mine, where);
            remember(where.key(), report);
            return report;
        } catch (Exception e) {
            Report bad = Report.failed(e.getMessage() == null ? e.toString() : e.getMessage());
            remember(where.key(), bad);
            return bad;
        } finally {
            running.set(false);
        }
    }

    /** Keeps one answer, dropping the oldest once there are too many. */
    private static void remember(String key, Report report) {
        cache.put(key, report);
        if (cache.size() <= MAX_CACHED) return;
        String oldest = null;
        long when = Long.MAX_VALUE;
        for (Map.Entry<String, Report> e : cache.entrySet()) {
            if (e.getKey().equals("all")) continue;      // the one everybody comes back to
            if (e.getValue().generated() < when) { when = e.getValue().generated(); oldest = e.getKey(); }
        }
        if (oldest != null) cache.remove(oldest);
    }

    // ---------- the prompt ----------

    static final String SYSTEM = """
        You are reading a summary of what happened on a Minecraft server, \
        prepared for the server's admin. You are given episodes: stretches of \
        one player doing one thing, already worked out from the block-by-block \
        log. Coordinates are Minecraft x,y,z; y is height, so low y means \
        underground.

        Write three things.

        First, a short paragraph — two or three sentences — saying what \
        happened, in plain language, as if telling the admin over their \
        shoulder. Name players. Say what people were doing, not what the log \
        recorded. Do not list every episode; say what the session was about.

        Then pick at most five moments worth looking at: things that are \
        unusual, destructive, dangerous, or simply the most interesting thing \
        anyone did. A quiet server has few or none, and saying so is a correct \
        answer.

        Then, for up to twelve of the episodes, say what it was probably FOR. \
        The episode line already says what happened — "dug a shaft from y 64 \
        down to y 11" — so do not repeat it. Say what it was in aid of, read \
        from what came before and after it and from where it was: getting to \
        the ore layer, clearing ground for the build next to it, stocking up \
        after dying there, walling in the farm they made an hour ago. One \
        Use a plain phrase of at most twelve words each. Where a stretch plainly \
        stands alone and means \
        nothing beyond itself, leave it out rather than inventing a reason for \
        it.

        Be conservative about construction. A count, a material name, or a wide \
        bounding box does not make a village, base, expansion, building, or project. \
        Use those words only when connected geometry or multiple corroborating \
        episodes actually show one. If edits are isolated or splotched around, call \
        them scattered placements or breaks and do not invent a purpose. The attached \
        diagram, when present, is stronger evidence of shape than the headline.

        Last, look for patterns the episodes do not name. The episodes were \
        worked out by fixed rules that only know a short list of shapes — a \
        shaft, a tree, a fight — so anything spread across players, across \
        places or across hours is invisible to them. That is what you are for \
        here: a trip made to the same spot every evening, three people \
        converging somewhere at once, a twenty-minute round that repeats, a \
        stretch of one player shadowing another, work that stops the moment \
        somebody logs in. Use the quarter-hour table for this, not the \
        episode list. At most four, each with the times it covers. If nothing \
        stands out, send an empty list — a quiet server is a real answer and a \
        made-up pattern is worse than none.

        Judge only from what you are given. Do not guess at motive in the sense \
        of accusing anyone — no cheating, no griefing, no bad faith — and do \
        not invent numbers, coordinates or events that are not listed. \
        "Probably" and "looks like" are honest; certainty is not.

        Answer as JSON and nothing else:
        {"summary": "...", \
        "moments": [{"at": <timestamp from an episode>, "label": "short title", \
        "why": "one sentence", "player": "name", "weight": 0-100}], \
        "sequences": [{"at": <timestamp from an episode>, "means": "one sentence"}], \
        "patterns": [{"from": <timestamp>, "to": <timestamp>, "player": "name or empty \
        for several", "label": "short title", "why": "one sentence"}]}""";

    /** Everything the model is told, and nothing else. */
    static String prompt(Scope scope, List<Episodes.Episode> episodes,
                         List<ActivityEntry> rows, List<ActivityEntry> chat,
                         long from, long to, int online, boolean sendChat) {
        StringBuilder b = new StringBuilder(8192);
        b.append(scope.said()).append('\n');
        b.append("Period: ").append(span(to - from)).append(", ending now.\n");
        b.append("Nothing happened outside ").append(from).append(" to ").append(to)
         .append(" — do not refer to any other time.\n");
        b.append("Players connected right now: ").append(online).append(".\n\n");

        b.append("Episodes (most notable first):\n");
        int n = 0;
        for (Episodes.Episode e : episodes) {
            if (n++ >= MAX_EPISODES) break;
            b.append("- at ").append(e.to())
             .append(" | ").append(e.player())
             .append(" | ").append(e.kind())
             .append(" | ").append(e.dim())
             .append(' ').append(e.x()).append(',').append(e.y()).append(',').append(e.z())
             .append(" | ").append(span(e.durationMs()))
             .append(" | ").append(e.headline())
             .append('\n');
        }
        if (n == 0) b.append("- nothing recorded\n");

        timeline(b, rows, from, to);

        if (sendChat && !chat.isEmpty()) {
            b.append("\nWhat people said:\n");
            int c = 0;
            for (int i = Math.max(0, chat.size() - MAX_CHAT); i < chat.size(); i++) {
                ActivityEntry e = chat.get(i);
                b.append("- ").append(e.player()).append(": ").append(e.detail()).append('\n');
                if (++c >= MAX_CHAT) break;
            }
        }
        return b.toString();
    }

    /** How wide a bucket in the timeline table is. */
    private static final long BUCKET_MS = 900_000;      // a quarter of an hour

    /** Ceiling on rows of that table, oldest dropped. */
    private static final int MAX_BUCKETS = 60;

    /**
     * The log seen from a long way off: who did roughly what, quarter-hour by
     * quarter-hour.
     *
     * <p>{@link Episodes} cuts the log by <em>place</em> — one player, one
     * spot, no long pause — which is exactly right for "what is this heap of
     * broken blocks" and exactly wrong for anything spread over an evening. A
     * player who visits the same spot four nights running produces four
     * unremarkable episodes and no fifth one saying they did it four times.
     *
     * <p>So the model also gets the log cut by <em>time</em>. It is small —
     * one line per player per quarter-hour, counts only, no coordinates beyond
     * a rough centre — and it is the only view in the prompt from which a
     * rhythm is visible at all.
     */
    static void timeline(StringBuilder b, List<ActivityEntry> rows, long from, long to) {
        if (rows == null || rows.isEmpty()) return;
        // player -> bucket -> counts
        Map<String, Map<Long, Map<String, Integer>>> grid = new java.util.TreeMap<>();
        Map<String, Map<Long, long[]>> where = new java.util.HashMap<>();
        for (ActivityEntry e : rows) {
            long bucket = e.at() / BUCKET_MS;
            grid.computeIfAbsent(e.player(), k -> new java.util.TreeMap<>())
                .computeIfAbsent(bucket, k -> new java.util.LinkedHashMap<>())
                .merge(e.action(), Math.max(1, e.count()), Integer::sum);
            long[] sum = where.computeIfAbsent(e.player(), k -> new java.util.HashMap<>())
                .computeIfAbsent(bucket, k -> new long[3]);
            sum[0] += e.x(); sum[1] += e.z(); sum[2]++;
        }
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Map<Long, Map<String, Integer>>> who : grid.entrySet()) {
            for (Map.Entry<Long, Map<String, Integer>> slot : who.getValue().entrySet()) {
                long[] sum = where.get(who.getKey()).get(slot.getKey());
                StringBuilder line = new StringBuilder();
                line.append("- ").append(slot.getKey() * BUCKET_MS)
                    .append(" | ").append(who.getKey())
                    .append(" | near ").append(sum[2] == 0 ? 0 : sum[0] / sum[2])
                    .append(',').append(sum[2] == 0 ? 0 : sum[1] / sum[2])
                    .append(" | ");
                boolean first = true;
                for (Map.Entry<String, Integer> c : slot.getValue().entrySet()) {
                    if (!first) line.append(", ");
                    line.append(c.getValue()).append(' ').append(c.getKey());
                    first = false;
                }
                lines.add(line.toString());
            }
        }
        if (lines.isEmpty()) return;
        b.append("\nActivity by the quarter-hour (timestamp | player | rough place | counts):\n");
        for (int i = Math.max(0, lines.size() - MAX_BUCKETS); i < lines.size(); i++) {
            b.append(lines.get(i)).append('\n');
        }
    }

    private static String span(long ms) {
        long s = Math.max(0, ms) / 1000;
        if (s < 90) return s + " seconds";
        if (s < 5400) return (s / 60) + " minutes";
        if (s < 172800) return (s / 3600) + " hours";
        return (s / 86400) + " days";
    }

    // ---------- talking to a provider ----------

    private static String ask(AlminConfig cfg, String prompt) throws IOException {
        return ask(cfg, SYSTEM, prompt);
    }

    private static String ask(AlminConfig cfg, String prompt, byte[] sceneImage)
            throws IOException {
        return AiTransport.ask(cfg, provider(cfg), SYSTEM, prompt, sceneImage);
    }

    private static String ask(AlminConfig cfg, String system, String prompt) throws IOException {
        return AiTransport.ask(cfg, provider(cfg), system, prompt);
    }
    // ---------- reading the answer ----------

    /**
     * Pulls a report out of whatever the model actually sent.
     *
     * <p>Models wrap JSON in prose and in code fences however they feel, and a
     * 3B model does it more than most. Rather than insist, the first balanced
     * object in the reply is taken; if there is not one, the whole reply
     * becomes the summary, which is a worse answer but still an answer.
     */
    static Report parse(String text, long from, long to, AlminConfig cfg,
                        List<Episodes.Episode> episodes, Scope scope) {
        String provider = provider(cfg);
        String model = cfg.aiModel == null ? "" : cfg.aiModel.trim();
        String where = scope == null ? "all" : scope.key();
        String json = firstObject(text);
        if (json.isEmpty()) {
            return new Report(System.currentTimeMillis(), from, to, text.trim(),
                List.of(), List.of(), List.of(), where, model, provider, "");
        }
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            String summary = str(o, "summary");
            List<Moment> moments = new ArrayList<>();
            if (o.has("moments") && o.get("moments").isJsonArray()) {
                for (JsonElement el : o.getAsJsonArray("moments")) {
                    if (!el.isJsonObject()) continue;
                    Moment m = moment(el.getAsJsonObject(), episodes);
                    if (m != null) moments.add(m);
                    if (moments.size() >= 8) break;
                }
            }
            List<Meaning> meanings = new ArrayList<>();
            if (o.has("sequences") && o.get("sequences").isJsonArray()) {
                for (JsonElement el : o.getAsJsonArray("sequences")) {
                    if (!el.isJsonObject()) continue;
                    Meaning m = meaning(el.getAsJsonObject(), episodes);
                    if (m != null) meanings.add(m);
                    if (meanings.size() >= 16) break;
                }
            }
            List<Found> found = new ArrayList<>();
            if (o.has("patterns") && o.get("patterns").isJsonArray()) {
                for (JsonElement el : o.getAsJsonArray("patterns")) {
                    if (!el.isJsonObject()) continue;
                    Found f = found(el.getAsJsonObject(), from, to);
                    if (f != null) found.add(f);
                    if (found.size() >= 6) break;
                }
            }
            if (summary.isEmpty() && moments.isEmpty() && meanings.isEmpty() && found.isEmpty()) {
                return new Report(System.currentTimeMillis(), from, to, text.trim(),
                    List.of(), List.of(), List.of(), where, model, provider, "");
            }
            return new Report(System.currentTimeMillis(), from, to, summary, moments,
                meanings, found, where, model, provider, "");
        } catch (Exception e) {
            return new Report(System.currentTimeMillis(), from, to, text.trim(),
                List.of(), List.of(), List.of(), where, model, provider, "");
        }
    }

    /**
     * One pattern the model claims to have seen, clamped to real time.
     *
     * <p>Unlike a moment or a meaning, this has no episode to anchor to —
     * that is the point of it. What it does have is a window, and a window
     * outside the period that was sent is the signature of a model filling in
     * a plausible-looking number. So the times are clamped to what was
     * actually given, and anything landing entirely outside is dropped.
     */
    private static Found found(JsonObject o, long windowFrom, long windowTo) {
        String label = cut(str(o, "label"), 90);
        if (label.isEmpty()) return null;
        long a = num(o, "from"), b = num(o, "to");
        if (a <= 0 && b <= 0) return null;
        if (a <= 0) a = b;
        if (b <= 0) b = a;
        if (b < a) { long swap = a; a = b; b = swap; }
        if (b < windowFrom || a > windowTo) return null;
        a = Math.max(windowFrom, Math.min(windowTo, a));
        b = Math.max(windowFrom, Math.min(windowTo, b));
        return new Found(a, b, cut(str(o, "player"), 32), label, cut(str(o, "why"), 200));
    }

    private static long num(JsonObject o, String k) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsLong() : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /**
     * One moment, anchored back onto an episode.
     *
     * <p>The model is asked for a timestamp it was given, and the place comes
     * from the episode with that timestamp rather than from the model — asking
     * it to copy coordinates back is asking it to make them up. A moment that
     * matches nothing is still shown, without a place to jump to.
     */
    private static Moment moment(JsonObject o, List<Episodes.Episode> episodes) {
        String label = str(o, "label");
        if (label.isEmpty()) return null;
        long at = 0;
        try { at = o.has("at") ? o.get("at").getAsLong() : 0; }
        catch (Exception ignored) { /* the model wrote a date; the label still stands */ }

        Episodes.Episode nearest = null;
        long best = Long.MAX_VALUE;
        for (Episodes.Episode e : episodes) {
            long d = Math.abs(e.to() - at);
            if (d < best) { best = d; nearest = e; }
        }
        // Only trust the match if it is actually one of the timestamps given.
        boolean anchored = nearest != null && best <= 1000;
        int weight = 50;
        try { weight = o.has("weight") ? Math.max(0, Math.min(100, o.get("weight").getAsInt())) : 50; }
        catch (Exception ignored) { /* keep the default */ }

        return new Moment(anchored ? nearest.to() : at, cut(label, 90), cut(str(o, "why"), 200),
            anchored ? nearest.player() : cut(str(o, "player"), 32),
            anchored ? nearest.dim() : "",
            anchored ? nearest.x() : 0, anchored ? nearest.y() : 0, anchored ? nearest.z() : 0,
            weight);
    }

    /**
     * One episode's meaning, anchored the same way a moment is.
     *
     * <p>Refused unless the timestamp is one that was actually given: a
     * sentence attached to nothing is a sentence about nothing, and this is
     * the field most likely to attract an invented one.
     */
    private static Meaning meaning(JsonObject o, List<Episodes.Episode> episodes) {
        String means = cut(str(o, "means"), 200);
        if (means.isEmpty()) return null;
        long at;
        try { at = o.has("at") ? o.get("at").getAsLong() : 0; }
        catch (Exception e) { return null; }
        // Anchored to the player as well as the moment: two people can finish
        // something in the same second, and a reading attached to the wrong
        // one of them is worse than none.
        for (Episodes.Episode e : episodes) {
            if (Math.abs(e.to() - at) <= 1000) return new Meaning(e.to(), e.player(), means);
        }
        return null;
    }

    /** The first balanced {...} in a string, ignoring braces inside strings. */
    static String firstObject(String text) {
        int start = text.indexOf('{');
        if (start < 0) return "";
        int depth = 0;
        boolean inString = false, escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (inString) {
                if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') inString = true;
            else if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return text.substring(start, i + 1);
        }
        return "";
    }

    private static String cut(String s, int max) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').trim();
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }

    private static String str(JsonObject o, String k) {
        return o != null && o.has(k) && !o.get(k).isJsonNull() && o.get(k).isJsonPrimitive()
            ? o.get(k).getAsString() : "";
    }

    // ---------- questions and "what am I looking for" ----------

    static final String LENS_SYSTEM = """
        You are answering a Minecraft server admin's question about the \
        Activity screen in front of them. You are given only the evidence in \
        that screen's selected player or map area and timeline window: compact \
        activity counts and episodes already worked out from the log.

        First answer the question directly, in one to four short sentences. \
        Use only the supplied evidence. If it cannot answer the question, say \
        that plainly. Do not invent motives, structures, or events, and do not \
        turn scattered block edits into a grand construction project.

        Then choose the Activity filters that show the evidence behind the \
        answer. Pick only the players, action types, specific things, and \
        episode kinds that could help. The admin may press Ask for the answer \
        alone or Find on map to apply those filters.

        Be generous rather than strict. A filter that hides the answer is worse \
        than one that leaves some noise in, because they can see what you \
        chose and narrow it themselves — and they cannot see what you threw \
        away. If the request names something not in the data at all, say so in \
        the reply and return empty lists rather than guessing.

        Use only action names and item names spelled exactly as they appear in \
        the lists you were given, and only timestamps that are on an episode.

        Answer as JSON and nothing else:
        {"reply": "a concise direct answer grounded only in the evidence", \
        "players": ["exact names"], "actions": ["exact action names"], \
        "items": ["exact detail strings"], "kinds": ["episode kinds"], \
        "episodes": [<timestamps of matching episodes>]}""";

    /**
     * Answers a question and returns filters for its supporting evidence.
     *
     * <p>Deliberately not a search: nothing is fetched, nothing is re-read,
     * and the model never sees a row the panel was not already showing. It is
     * given the same list the person is looking at and asked which parts of it
     * they meant.
     */
    public static Lens look(String question, List<Episodes.Episode> episodes,
                            List<ActivityEntry> rows, long from, long to) {
        return look(question, Scope.all(), episodes, rows, from, to);
    }

    /** Same question, limited to the Activity screen's selected subject. */
    public static Lens look(String question, Scope scope, List<Episodes.Episode> episodes,
                            List<ActivityEntry> rows, long from, long to) {
        String q = question == null ? "" : question.trim();
        if (q.isEmpty()) return Lens.failed(q, "Ask a question about the activity first.");
        if (q.length() > 400) q = q.substring(0, 400);
        String why = problem();
        if (!why.isEmpty()) return Lens.failed(q, why);
        if (!running.compareAndSet(false, true)) {
            return Lens.failed(q, "Already thinking — try again in a moment.");
        }
        try {
            AlminConfig cfg = AlminConfig.get();
            lastAsked = System.currentTimeMillis();
            Scope where = scope == null ? Scope.all() : scope;
            String text = ask(cfg, LENS_SYSTEM,
                lensPrompt(q, where, episodes, rows, from, to, cfg.aiSendChat));
            return lens(q, text, episodes);
        } catch (Exception e) {
            return Lens.failed(q, e.getMessage() == null ? e.toString() : e.getMessage());
        } finally {
            running.set(false);
        }
    }

    /** The episodes, the vocabulary of the log, and the question. */
    static String lensPrompt(String question, Scope scope, List<Episodes.Episode> episodes,
                             List<ActivityEntry> rows, long from, long to,
                             boolean sendChat) {
        StringBuilder b = new StringBuilder(8192);
        b.append("Question: ").append(question).append("\n");
        b.append(scope.said()).append('\n');
        b.append("Period: ").append(span(to - from)).append(", ending now.\n\n");

        java.util.TreeSet<String> players = new java.util.TreeSet<>();
        java.util.TreeSet<String> actions = new java.util.TreeSet<>();
        Map<String, Integer> things = new java.util.HashMap<>();
        Map<String, long[]> totals = new java.util.HashMap<>();
        for (ActivityEntry e : rows) {
            players.add(e.player());
            actions.add(e.action());
            boolean hiddenChat = "chat".equals(e.action()) && !sendChat;
            if (!hiddenChat && e.detail() != null && !e.detail().isBlank()
                    && e.detail().length() <= 80) {
                things.merge(e.detail(), Math.max(1, e.count()), Integer::sum);
            }
            String detail = hiddenChat ? "(content withheld)" : cut(e.detail(), 80);
            String key = e.player() + " | " + e.action()
                + (detail.isEmpty() ? "" : " | " + detail);
            long[] stat = totals.computeIfAbsent(key,
                ignored -> new long[]{0, Long.MAX_VALUE, Long.MIN_VALUE});
            stat[0] += Math.max(1, e.count());
            stat[1] = Math.min(stat[1], e.at());
            stat[2] = Math.max(stat[2], e.at());
        }
        b.append("Players in the log: ").append(String.join(", ", players)).append('\n');
        b.append("Action names in the log: ").append(String.join(", ", actions)).append('\n');
        b.append("Chat text: ").append(sendChat ? "included when present" : "withheld").append('\n');

        List<Map.Entry<String, Integer>> top = new ArrayList<>(things.entrySet());
        top.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        b.append("Things named in the log (commonest first): ");
        for (int i = 0; i < Math.min(80, top.size()); i++) {
            if (i > 0) b.append(", ");
            b.append(top.get(i).getKey());
        }
        b.append("\n\nActivity counts (player | action | detail | count | first/last):\n");
        List<Map.Entry<String, long[]>> counted = new ArrayList<>(totals.entrySet());
        counted.sort((a, c) -> Long.compare(c.getValue()[0], a.getValue()[0]));
        for (int i = 0; i < Math.min(100, counted.size()); i++) {
            Map.Entry<String, long[]> e = counted.get(i);
            long[] stat = e.getValue();
            b.append("- ").append(e.getKey()).append(" | ").append(stat[0])
             .append(" | first ").append(span(Math.max(0, to - stat[1])))
             .append(" before end, last ").append(span(Math.max(0, to - stat[2])))
             .append(" before end\n");
        }
        if (counted.isEmpty()) b.append("- nothing recorded\n");

        b.append("\nEpisodes:\n");
        int n = 0;
        for (Episodes.Episode e : episodes) {
            if (n++ >= MAX_EPISODES) break;
            b.append("- at ").append(e.to()).append(" | ").append(e.player())
             .append(" | ").append(e.kind())
             .append(" | ").append(e.dim()).append(' ')
             .append(e.x()).append(',').append(e.y()).append(',').append(e.z())
             .append(" | ").append(e.headline()).append('\n');
        }
        if (n == 0) b.append("- nothing recorded\n");
        return b.toString();
    }

    /** Reads a filter back, keeping only names that were really in the data. */
    static Lens lens(String question, String text, List<Episodes.Episode> episodes) {
        String json = firstObject(text);
        if (json.isEmpty()) {
            return new Lens(System.currentTimeMillis(), question, text.trim(),
                List.of(), List.of(), List.of(), List.of(), List.of(), "");
        }
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            java.util.Set<Long> known = new java.util.HashSet<>();
            java.util.Set<String> names = new java.util.HashSet<>();
            java.util.Set<String> kinds = new java.util.HashSet<>();
            for (Episodes.Episode e : episodes) {
                known.add(e.to());
                names.add(e.player().toLowerCase(Locale.ROOT));
                kinds.add(e.kind());
            }
            List<Long> hits = new ArrayList<>();
            if (o.has("episodes") && o.get("episodes").isJsonArray()) {
                for (JsonElement el : o.getAsJsonArray("episodes")) {
                    try {
                        long at = el.getAsLong();
                        // Only a timestamp it was actually given. A near miss
                        // is not close enough to point somebody at.
                        if (known.contains(at)) hits.add(at);
                    } catch (RuntimeException ignored) {
                        // The model wrote a date. There is nothing to point at.
                    }
                }
            }
            return new Lens(System.currentTimeMillis(), question, cut(str(o, "reply"), 1200),
                hits, strings(o, "players", 24), strings(o, "actions", 24),
                strings(o, "items", 60), strings(o, "kinds", 24), "");
        } catch (Exception e) {
            return new Lens(System.currentTimeMillis(), question, text.trim(),
                List.of(), List.of(), List.of(), List.of(), List.of(), "");
        }
    }

    private static List<String> strings(JsonObject o, String key, int max) {
        List<String> out = new ArrayList<>();
        if (!o.has(key) || !o.get(key).isJsonArray()) return out;
        for (JsonElement el : o.getAsJsonArray(key)) {
            if (!el.isJsonPrimitive()) continue;
            String v = cut(el.getAsString(), 60);
            if (!v.isEmpty() && !out.contains(v)) out.add(v);
            if (out.size() >= max) break;
        }
        return out;
    }

    // ---------- a second opinion on a client's mod list ----------

    static final String MODS_SYSTEM = """
        You are reading the list of mods installed on one Minecraft player's \
        client, reported by that client to the server it joined. The server's \
        admin wants to know whether anything on it is worth a second look.

        Say what each notable mod is for, and flag the ones that change what a \
        player can see or do in a way another player cannot: x-ray and ore \
        finders, radars that show players or mobs through walls, reach and \
        aim helpers, auto-clickers, flight and movement hacks, and clients \
        built around those. Mods that were removed recently matter too — \
        somebody uninstalling one an hour before joining is worth knowing \
        about.

        Be accurate about what is ordinary. Most of a long list is graphics, \
        performance, maps, inventory helpers and libraries, and calling those \
        suspicious wastes the admin's attention and is unfair to the player. \
        A minimap is normal on most servers; a minimap with entity radar is a \
        different conversation. If a mod id means nothing to you, say that \
        rather than guessing at it — "I do not recognise this" is useful and \
        an invented description is not.

        This is self-reported by the client and can be faked, so nothing here \
        is proof. Do not accuse anyone. Say what the mod does and let the \
        admin decide.

        Use these levels: "fine" (ordinary), "watch" (depends on the server's \
        rules), "concern" (an advantage other players do not have), \
        "unknown" (you do not recognise it).

        Answer as JSON and nothing else, and include only mods worth a line:
        {"summary": "two sentences on the list as a whole", \
        "flags": [{"id": "exact mod id", "level": "fine|watch|concern|unknown", \
        "why": "one sentence"}]}""";

    /**
     * Asks the model to read one client's mod list.
     *
     * <p>The one place in Almin where a model is pointed at a person rather
     * than at a log, so the prompt spends most of its length on restraint:
     * most of a mod list is ordinary, an unrecognised id is an unrecognised
     * id, and none of it is evidence. The panel repeats that above the answer.
     */
    public static ModReview review(String player, List<ClientProfiles.Mod> present,
                                   List<ClientProfiles.Mod> removed) {
        String who = player == null ? "" : player;
        String why = problem();
        if (!why.isEmpty()) return ModReview.failed(who, why);
        if (present == null || present.isEmpty()) {
            return ModReview.failed(who, "That client has not reported a mod list.");
        }
        if (!running.compareAndSet(false, true)) {
            return ModReview.failed(who, "Already thinking — try again in a moment.");
        }
        try {
            AlminConfig cfg = AlminConfig.get();
            lastAsked = System.currentTimeMillis();
            String text = ask(cfg, MODS_SYSTEM, modsPrompt(who, present, removed));
            return modReview(who, text);
        } catch (Exception e) {
            return ModReview.failed(who, e.getMessage() == null ? e.toString() : e.getMessage());
        } finally {
            running.set(false);
        }
    }

    static String modsPrompt(String player, List<ClientProfiles.Mod> present,
                             List<ClientProfiles.Mod> removed) {
        StringBuilder b = new StringBuilder(4096);
        long now = System.currentTimeMillis();
        b.append("Client: ").append(player).append("\n\nInstalled now:\n");
        int n = 0;
        for (ClientProfiles.Mod m : present) {
            if (n++ >= 300) break;
            b.append("- ").append(m.id());
            if (!m.version().isBlank()) b.append(' ').append(m.version());
            if (m.firstSeen() > 0) {
                b.append("  (first seen ").append(span(now - m.firstSeen())).append(" ago)");
            }
            b.append('\n');
        }
        if (removed != null && !removed.isEmpty()) {
            b.append("\nRemoved recently:\n");
            int r = 0;
            for (ClientProfiles.Mod m : removed) {
                if (r++ >= 60) break;
                b.append("- ").append(m.id());
                if (!m.version().isBlank()) b.append(' ').append(m.version());
                b.append("  (gone ").append(span(now - m.removedAt())).append(" ago)\n");
            }
        }
        return b.toString();
    }

    static ModReview modReview(String player, String text) {
        String json = firstObject(text);
        if (json.isEmpty()) {
            return new ModReview(System.currentTimeMillis(), player, text.trim(), List.of(), "");
        }
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            List<ModFlag> flags = new ArrayList<>();
            if (o.has("flags") && o.get("flags").isJsonArray()) {
                for (JsonElement el : o.getAsJsonArray("flags")) {
                    if (!el.isJsonObject()) continue;
                    JsonObject f = el.getAsJsonObject();
                    String id = cut(str(f, "id"), 64);
                    if (id.isEmpty()) continue;
                    String level = str(f, "level").toLowerCase(Locale.ROOT);
                    if (!level.equals("watch") && !level.equals("concern")
                        && !level.equals("unknown")) level = "fine";
                    flags.add(new ModFlag(id, level, cut(str(f, "why"), 200)));
                    if (flags.size() >= 60) break;
                }
            }
            return new ModReview(System.currentTimeMillis(), player,
                cut(str(o, "summary"), 400), flags, "");
        } catch (Exception e) {
            return new ModReview(System.currentTimeMillis(), player, text.trim(), List.of(), "");
        }
    }

    /** For the panel: what would be sent, without sending it. */
    public static Map<String, Object> shape(AlminConfig cfg) {
        return Map.of(
            "provider", provider(cfg),
            "model", cfg.aiModel == null ? "" : cfg.aiModel,
            "local", provider(cfg).equals("local"),
            "sendsChat", cfg.aiSendChat);
    }
}
