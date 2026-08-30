package com.schecks.almin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
                         List<Moment> moments, List<Meaning> meanings,
                         String model, String provider, String error) {

        public boolean ok() { return error == null || error.isEmpty(); }

        public static Report failed(String why) {
            return new Report(System.currentTimeMillis(), 0, 0, "", List.of(), List.of(),
                "", "", why);
        }
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(90);
    private static final String AGENT = "Almin/" + Almin.MOD_ID;

    /** Ceiling on a reply, so a runaway model cannot fill memory. */
    private static final int MAX_RESPONSE = 512 * 1024;

    /** Episodes handed to the model. Past this the prompt stops being small. */
    private static final int MAX_EPISODES = 60;

    /** Chat lines handed to the model, newest kept. */
    private static final int MAX_CHAT = 40;

    /** Nothing is asked twice inside this window; the panel gets the cache. */
    private static final long COOLDOWN_MS = 20_000;

    private static volatile Report last;
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

            List<ActivityLog.Entry> rows = ActivityLog.recent(2500);
            if (rows.isEmpty()) return;
            long newest = 0, oldest = Long.MAX_VALUE;
            for (ActivityLog.Entry e : rows) {
                newest = Math.max(newest, e.at());
                oldest = Math.min(oldest, e.at());
            }
            Report have = last;
            if (have != null && have.ok() && newest <= have.generated() - COOLDOWN_MS) return;

            List<Episodes.Episode> episodes = Episodes.of(rows);
            List<ActivityLog.Entry> chat = new ArrayList<>();
            for (int i = rows.size() - 1; i >= 0; i--) {
                if ("chat".equals(rows.get(i).action())) chat.add(rows.get(i));
            }
            summarise(episodes, chat, oldest, newest, 0, true);
        } catch (Throwable t) {
            AlminLog.warn("[almin] unattended summary failed: {}", t.toString());
        }
    }

    /** The file the key is kept in, for anything that needs to refuse to serve it. */
    public static String keyFileName() { return "ai-key"; }

    public static synchronized boolean hasKey() {
        return !key().isEmpty();
    }

    private static synchronized String key() {
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

    /** The last report, or null if nothing has been asked yet. */
    public static Report cached() { return last; }

    public static void forget() { last = null; }

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
        if (provider.equals("local")) {
            if (cfg.aiBaseUrl == null || cfg.aiBaseUrl.isBlank()) {
                return "No address for the local model (ai-base-url).";
            }
            return endpointProblem(cfg.aiBaseUrl);
        }
        if (!hasKey()) return "No API key set for " + provider + ".";
        return "";
    }

    private static String provider(AlminConfig cfg) {
        String p = cfg.aiProvider == null ? "" : cfg.aiProvider.trim().toLowerCase(Locale.ROOT);
        return switch (p) {
            case "anthropic", "openai", "local" -> p;
            default -> "local";
        };
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
     * Asks the model, or hands back what it said a moment ago.
     *
     * <p>Blocking, and called from a web thread — never from the server
     * thread. The episodes and chat lines are gathered by the caller, which
     * already holds them.
     */
    public static Report summarise(List<Episodes.Episode> episodes, List<ActivityLog.Entry> chat,
                                   long from, long to, int online, boolean force) {
        String why = problem();
        if (!why.isEmpty()) return Report.failed(why);

        Report have = last;
        long now = System.currentTimeMillis();
        if (!force && have != null && now - have.generated() < COOLDOWN_MS) return have;
        if (!running.compareAndSet(false, true)) {
            return have != null ? have : Report.failed("Already thinking — try again in a moment.");
        }
        try {
            lastAsked = now;
            AlminConfig cfg = AlminConfig.get();
            String prompt = prompt(episodes, chat, from, to, online, cfg.aiSendChat);
            String text = ask(cfg, prompt);
            Report report = parse(text, from, to, cfg, episodes);
            last = report;
            return report;
        } catch (Exception e) {
            Report bad = Report.failed(e.getMessage() == null ? e.toString() : e.getMessage());
            last = bad;
            return bad;
        } finally {
            running.set(false);
        }
    }

    // ---------- the prompt ----------

    static final String SYSTEM = """
        You are reading a summary of what happened on a Minecraft server, \
        prepared for the server's admin. You are given episodes: stretches of \
        one player doing one thing, already worked out from the block-by-block \
        log. Coordinates are Minecraft x,y,z; y is height, so low y means \
        underground.

        Write three things.

        First, a short paragraph — three or four sentences — saying what \
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
        short sentence each. Where a stretch plainly stands alone and means \
        nothing beyond itself, leave it out rather than inventing a reason for \
        it.

        Judge only from what you are given. Do not guess at motive in the sense \
        of accusing anyone — no cheating, no griefing, no bad faith — and do \
        not invent numbers, coordinates or events that are not listed. \
        "Probably" and "looks like" are honest; certainty is not.

        Answer as JSON and nothing else:
        {"summary": "...", \
        "moments": [{"at": <timestamp from an episode>, "label": "short title", \
        "why": "one sentence", "player": "name", "weight": 0-100}], \
        "sequences": [{"at": <timestamp from an episode>, "means": "one sentence"}]}""";

    /** Everything the model is told, and nothing else. */
    static String prompt(List<Episodes.Episode> episodes, List<ActivityLog.Entry> chat,
                         long from, long to, int online, boolean sendChat) {
        StringBuilder b = new StringBuilder(4096);
        b.append("Period: ").append(span(to - from)).append(", ending now.\n");
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

        if (sendChat && !chat.isEmpty()) {
            b.append("\nWhat people said:\n");
            int c = 0;
            for (int i = Math.max(0, chat.size() - MAX_CHAT); i < chat.size(); i++) {
                ActivityLog.Entry e = chat.get(i);
                b.append("- ").append(e.player()).append(": ").append(e.detail()).append('\n');
                if (++c >= MAX_CHAT) break;
            }
        }
        return b.toString();
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
        String provider = provider(cfg);
        return switch (provider) {
            case "anthropic" -> anthropic(cfg, prompt);
            default -> openaiShaped(cfg, prompt, provider.equals("openai"));
        };
    }

    /**
     * The Messages API.
     *
     * <p>{@code x-api-key} rather than a bearer token, and
     * {@code anthropic-version} is required — a request without it is
     * rejected, which is the first thing that goes wrong when this is written
     * from memory.
     */
    private static String anthropic(AlminConfig cfg, String prompt) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("model", cfg.aiModel.trim());
        body.addProperty("max_tokens", 2000);
        body.addProperty("system", SYSTEM);
        JsonArray messages = new JsonArray();
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", prompt);
        messages.add(user);
        body.add("messages", messages);

        JsonObject reply = post("https://api.anthropic.com/v1/messages", body, req -> req
            .header("x-api-key", key())
            .header("anthropic-version", "2023-06-01"));

        // A safety classifier can decline, which arrives as a 200 with no text.
        String stop = str(reply, "stop_reason");
        if (stop.equals("refusal")) {
            throw new IOException("The model declined to answer this one.");
        }
        StringBuilder text = new StringBuilder();
        if (reply.has("content") && reply.get("content").isJsonArray()) {
            for (JsonElement el : reply.getAsJsonArray("content")) {
                if (!el.isJsonObject()) continue;
                JsonObject block = el.getAsJsonObject();
                if ("text".equals(str(block, "type"))) text.append(str(block, "text"));
            }
        }
        if (text.length() == 0) throw new IOException("The model sent nothing back.");
        return text.toString();
    }

    /**
     * The OpenAI chat shape, which is also what every local runner speaks.
     *
     * <p>{@code max_tokens} rather than the newer name, because the local
     * servers this has to work with have not all followed.
     */
    private static String openaiShaped(AlminConfig cfg, String prompt, boolean hosted)
            throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("model", cfg.aiModel.trim());
        body.addProperty("max_tokens", 2000);
        body.addProperty("stream", false);
        JsonArray messages = new JsonArray();
        messages.add(message("system", SYSTEM));
        messages.add(message("user", prompt));
        body.add("messages", messages);

        String base = hosted ? "https://api.openai.com/v1" : cfg.aiBaseUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String url = base + "/chat/completions";
        String apiKey = key();

        JsonObject reply = post(url, body, req -> {
            // A local runner usually wants no key at all, and sending an empty
            // bearer header upsets some of them.
            if (!apiKey.isEmpty()) req.header("Authorization", "Bearer " + apiKey);
            return req;
        });

        if (!reply.has("choices") || !reply.get("choices").isJsonArray()
            || reply.getAsJsonArray("choices").isEmpty()) {
            throw new IOException("The model sent nothing back.");
        }
        JsonObject first = reply.getAsJsonArray("choices").get(0).getAsJsonObject();
        JsonObject msg = first.has("message") && first.get("message").isJsonObject()
            ? first.getAsJsonObject("message") : new JsonObject();
        String text = str(msg, "content");
        if (text.isEmpty()) throw new IOException("The model sent nothing back.");
        return text;
    }

    private static JsonObject message(String role, String content) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", content);
        return o;
    }

    /** Adds whichever headers a provider needs. */
    private interface Headers {
        HttpRequest.Builder apply(HttpRequest.Builder b);
    }

    private static JsonObject post(String url, JsonObject body, Headers headers)
            throws IOException {
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", AGENT)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .timeout(TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
        HttpRequest request = headers.apply(builder).build();

        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()) {
            HttpResponse<byte[]> response =
                client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            byte[] bytes = response.body();
            if (bytes != null && bytes.length > MAX_RESPONSE) {
                throw new IOException("The model's answer was too large.");
            }
            String text = bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
            if (response.statusCode() / 100 != 2) {
                throw new IOException(explain(response.statusCode(), text));
            }
            JsonElement parsed = JsonParser.parseString(text);
            if (!parsed.isJsonObject()) throw new IOException("The service sent back something odd.");
            return parsed.getAsJsonObject();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        } catch (com.google.gson.JsonParseException e) {
            throw new IOException("The service sent back something that was not JSON.");
        } catch (java.net.ConnectException e) {
            // The most common failure by far, and "java.net.ConnectException"
            // tells an admin nothing about what to do next.
            throw new IOException("Nothing answered at " + hostOf(url)
                + ". Is the model running, and is the address right?");
        } catch (java.net.http.HttpTimeoutException e) {
            throw new IOException("The model took longer than "
                + TIMEOUT.toSeconds() + " seconds to answer.");
        } catch (java.net.UnknownHostException e) {
            throw new IOException("No such host: " + hostOf(url));
        }
    }

    /**
     * Turns a status code into something an admin can act on.
     *
     * <p>The provider's own message is worth showing — it is usually the
     * actual reason — but it is not worth showing three kilobytes of it.
     */
    /** Just the host and port, for a message somebody has to act on. */
    private static String hostOf(String url) {
        try {
            URI u = URI.create(url);
            return u.getPort() > 0 ? u.getHost() + ":" + u.getPort() : u.getHost();
        } catch (RuntimeException e) {
            return url;
        }
    }

    private static String explain(int status, String body) {
        String detail = "";
        try {
            JsonElement el = JsonParser.parseString(body);
            if (el.isJsonObject() && el.getAsJsonObject().has("error")) {
                JsonElement err = el.getAsJsonObject().get("error");
                detail = err.isJsonObject() ? str(err.getAsJsonObject(), "message")
                                            : err.getAsString();
            }
        } catch (Exception ignored) {
            // Not JSON. The status code alone will have to do.
        }
        if (detail.length() > 200) detail = detail.substring(0, 199) + "…";
        String head = switch (status) {
            case 401, 403 -> "The service rejected the API key";
            case 404 -> "No such model, or the wrong address";
            case 429 -> "Rate limited — too many requests";
            case 500, 502, 503, 504 -> "The service is having trouble";
            default -> "The service said " + status;
        };
        return detail.isEmpty() ? head : head + " — " + detail;
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
                        List<Episodes.Episode> episodes) {
        String provider = provider(cfg);
        String model = cfg.aiModel == null ? "" : cfg.aiModel.trim();
        String json = firstObject(text);
        if (json.isEmpty()) {
            return new Report(System.currentTimeMillis(), from, to, text.trim(),
                List.of(), List.of(), model, provider, "");
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
            if (summary.isEmpty() && moments.isEmpty() && meanings.isEmpty()) {
                return new Report(System.currentTimeMillis(), from, to, text.trim(),
                    List.of(), List.of(), model, provider, "");
            }
            return new Report(System.currentTimeMillis(), from, to, summary, moments,
                meanings, model, provider, "");
        } catch (Exception e) {
            return new Report(System.currentTimeMillis(), from, to, text.trim(),
                List.of(), List.of(), model, provider, "");
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

    /** For the panel: what would be sent, without sending it. */
    public static Map<String, Object> shape(AlminConfig cfg) {
        return Map.of(
            "provider", provider(cfg),
            "model", cfg.aiModel == null ? "" : cfg.aiModel,
            "local", provider(cfg).equals("local"),
            "sendsChat", cfg.aiSendChat);
    }
}
