package com.schecks.almin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Player faces, cropped out of skins, for the panel's player and activity
 * lists.
 *
 * <h3>What it does</h3>
 * A Minecraft skin is one PNG with the head's front face at pixels (8,8) and
 * the hat layer that sits over it at (40,8). A "head" is those two squares,
 * composited and scaled up. That is the whole job — the interesting part is
 * where the skin comes from and how hard this tries to get one.
 *
 * <h3>Where a skin comes from</h3>
 * <ol>
 *   <li><b>The connected player's own profile.</b> On an online-mode server
 *       the login handshake already put the texture there, so a player who is
 *       online costs no network call at all.</li>
 *   <li><b>Mojang's session server</b>, by UUID, for anyone who has left.</li>
 *   <li><b>Mojang's name lookup, then the session server.</b> An offline-mode
 *       server invents its own UUIDs, so the second step finds nothing; the
 *       name is the only handle that means anything, and looking it up is what
 *       every head-render service does. Off by default? No — but
 *       {@code web-player-heads} turns the whole thing off, and turning it off
 *       is the switch for "this server does not talk to Mojang".</li>
 * </ol>
 *
 * <h3>Care taken</h3>
 * Every fetch is capped and timed out, the texture host is pinned so a
 * mangled profile cannot redirect this at something else, and both hits and
 * misses are cached — a miss especially, because the common case for a
 * cracked server is that <em>every</em> lookup fails and nothing would be
 * gained by asking Mojang again on every page refresh.
 *
 * <p>Nothing here may run on the server thread: it blocks on the network.
 * {@link #textureFromProfile} is the one exception and is the reason the
 * caller passes a URL in rather than a server.
 */
public final class Heads {
    /** The face square in a skin, and the hat square laid over it. */
    private static final int FACE_X = 8, FACE_Y = 8, FACE = 8;
    private static final int HAT_X = 40, HAT_Y = 8;

    /** Scale factor. 8 gives a 64px image: crisp on a high-density display. */
    private static final int SCALE = 8;

    private static final Duration TIMEOUT = Duration.ofSeconds(6);
    private static final long MAX_SKIN_BYTES = 512 * 1024;

    /** A face is worth re-checking occasionally; people do change skins. */
    private static final long HIT_TTL_MS = 6 * 60 * 60 * 1000L;

    /**
     * A miss is cached for much less time than a hit, but still long enough
     * that a list of two hundred names cannot become two hundred requests to
     * Mojang every time someone reloads the page.
     */
    private static final long MISS_TTL_MS = 20 * 60 * 1000L;

    /** Enough for a big server's history without becoming a leak. */
    private static final int MAX_CACHE = 600;

    private static final String TEXTURE_HOST = "textures.minecraft.net";

    private record Cached(byte[] png, long at) {}

    private static final Map<UUID, Cached> CACHE = new ConcurrentHashMap<>();

    /**
     * Lookups run here, not on the thread that asked.
     *
     * <p>The panel serves every request from a four-thread pool. A history
     * list is a hundred rows, each asking for a face, each of which may mean
     * two calls to Mojang and a download — so answering them inline would let
     * one player list block the console, the file browser and the metrics
     * behind it. Instead the request waits {@link #WAIT_MS} for an answer and
     * otherwise says "no face"; the work carries on, and the next time that
     * row is drawn the cache has it.
     */
    private static final ThreadPoolExecutor LOOKUPS = new ThreadPoolExecutor(
        0, 2, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(64),
        r -> {
            Thread t = new Thread(r, "almin-heads");
            t.setDaemon(true);
            return t;
        });

    /** In flight, so a page full of one player's rows is still one lookup. */
    private static final Map<UUID, Future<byte[]>> PENDING = new ConcurrentHashMap<>();

    /**
     * How long a request will wait before drawing an initial instead. Short
     * on purpose: a face is worth a moment, never worth a stalled panel.
     */
    private static final long WAIT_MS = 1500;

    private Heads() {}

    /** Drops everything, so a skin change can be picked up without a restart. */
    public static void clear() {
        CACHE.clear();
        synchronized (NAMES) { NAMES.clear(); }
    }

    /** Name to account, remembered so a list of masks is not a list of lookups. */
    private static final Map<String, Object> NAMES = new java.util.LinkedHashMap<>();
    private static final Object NOBODY = new Object();

    /**
     * The head for a bare name, with no UUID to go on.
     *
     * <p>Which is the case a mask puts you in: a mask is a display name
     * somebody typed, and it may be another player's account, an account
     * nobody on this server has, or not an account at all. Answering "no face"
     * for the last two is the correct answer and is cached like any other.
     */
    public static byte[] byName(String name) {
        if (name == null) return null;
        String clean = name.trim();
        if (clean.isEmpty() || clean.length() > 16 || !clean.matches("[A-Za-z0-9_]+")) return null;
        if (!AlminConfig.get().webPlayerHeads) return null;

        Object known;
        synchronized (NAMES) { known = NAMES.get(clean.toLowerCase(java.util.Locale.ROOT)); }
        if (known == NOBODY) return null;
        UUID id = known instanceof UUID u ? u : null;
        if (id == null) {
            id = lookupByName(clean);
            synchronized (NAMES) {
                if (NAMES.size() > 400) NAMES.clear();
                NAMES.put(clean.toLowerCase(java.util.Locale.ROOT), id == null ? NOBODY : id);
            }
            if (id == null) return null;
        }
        return head(id, clean, "");
    }

    /**
     * The head for {@code id} as a PNG, or null if there isn't one to be had.
     *
     * <p>{@code textureUrl} is the skin URL already known from a connected
     * player's profile, or "" to go and look. {@code name} is used only for
     * the offline-mode fallback and may be "".
     */
    public static byte[] head(UUID id, String name, String textureUrl) {
        if (id == null) return null;
        if (!AlminConfig.get().webPlayerHeads) return null;

        Cached hit = CACHE.get(id);
        if (hit != null && !stale(hit)) return hit.png();

        Future<byte[]> work;
        try {
            work = PENDING.computeIfAbsent(id, key -> LOOKUPS.submit(() -> {
                try {
                    Cached again = CACHE.get(key);
                    if (again != null && !stale(again)) return again.png();
                    byte[] png = build(key, name, textureUrl);
                    remember(key, png);
                    return png;
                } finally {
                    PENDING.remove(key);
                }
            }));
        } catch (RejectedExecutionException e) {
            // More faces queued than are worth chasing. An initial will do.
            return null;
        }
        try {
            return work.get(WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // Still going. Whoever asks next gets it from the cache.
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean stale(Cached c) {
        long age = System.currentTimeMillis() - c.at();
        return age > (c.png() == null ? MISS_TTL_MS : HIT_TTL_MS);
    }

    private static void remember(UUID id, byte[] png) {
        // A plain cap with a clear-out: this is a picture cache, and an exact
        // LRU would be more machinery than the thing being cached is worth.
        if (CACHE.size() >= MAX_CACHE) CACHE.clear();
        CACHE.put(id, new Cached(png, System.currentTimeMillis()));
    }

    private static byte[] build(UUID id, String name, String textureUrl) {
        String url = textureUrl == null ? "" : textureUrl.trim();
        if (url.isEmpty()) url = textureFor(id);
        if (url.isEmpty() && name != null && !name.isBlank()) {
            UUID real = lookupByName(name);
            if (real != null && !real.equals(id)) url = textureFor(real);
        }
        if (url.isEmpty()) return null;
        byte[] skin = download(url);
        if (skin == null) return null;
        try {
            return crop(Png.decode(skin));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The skin URL in a connected player's profile, or "".
     *
     * <p>Reads the player list, so it must run on the server thread. This is
     * the only method here that may.
     */
    public static String textureFromProfile(MinecraftServer server, UUID id) {
        if (server == null || id == null) return "";
        try {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null) return "";
            Iterator<com.mojang.authlib.properties.Property> it =
                player.getGameProfile().properties().get("textures").iterator();
            if (!it.hasNext()) return "";
            return skinUrlFromTextures(it.next().value());
        } catch (Exception e) {
            return "";
        }
    }

    /** Asks the session server for a profile and digs the skin URL out of it. */
    private static String textureFor(UUID id) {
        String plain = id.toString().replace("-", "");
        JsonObject profile = getJson(
            "https://sessionserver.mojang.com/session/minecraft/profile/" + plain + "?unsigned=true");
        if (profile == null || !profile.has("properties")) return "";
        try {
            for (var el : profile.getAsJsonArray("properties")) {
                JsonObject p = el.getAsJsonObject();
                if (!"textures".equals(str(p, "name"))) continue;
                return skinUrlFromTextures(str(p, "value"));
            }
        } catch (RuntimeException e) {
            return "";
        }
        return "";
    }

    /** The real account UUID behind a name, for offline-mode servers. */
    private static UUID lookupByName(String name) {
        String clean = name.trim();
        if (clean.isEmpty() || clean.length() > 16 || !clean.matches("[A-Za-z0-9_]+")) return null;
        JsonObject o = getJson("https://api.mojang.com/users/profiles/minecraft/" + clean);
        if (o == null) return null;
        String id = str(o, "id");
        if (id.length() != 32) return null;
        try {
            return UUID.fromString(id.replaceFirst(
                "(.{8})(.{4})(.{4})(.{4})(.{12})", "$1-$2-$3-$4-$5"));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The base64 "textures" property, decoded, down to the SKIN url. */
    private static String skinUrlFromTextures(String base64) {
        try {
            byte[] raw = Base64.getDecoder().decode(base64);
            if (raw.length > 8 * 1024) return "";
            JsonObject o = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8))
                .getAsJsonObject();
            if (!o.has("textures")) return "";
            JsonObject textures = o.getAsJsonObject("textures");
            if (!textures.has("SKIN")) return "";
            return str(textures.getAsJsonObject("SKIN"), "url");
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Fetches a skin.
     *
     * <p>The host is pinned. The URL comes from Mojang, but it arrives as data
     * over the network and this is a server making an outbound request with
     * it — pinning is what stops a rewritten profile from choosing the target.
     * Mojang still hands out {@code http://} texture links; those are upgraded
     * rather than followed, since the same host serves them over TLS.
     */
    private static byte[] download(String url) {
        try {
            URI uri = URI.create(url.trim());
            if (!TEXTURE_HOST.equalsIgnoreCase(uri.getHost())) return null;
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                if (!"http".equalsIgnoreCase(uri.getScheme())) return null;
                uri = URI.create("https://" + TEXTURE_HOST + uri.getRawPath());
            }
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET().build();
            try (HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build()) {
                HttpResponse<byte[]> response =
                    client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() / 100 != 2) return null;
                byte[] body = response.body();
                return (body == null || body.length == 0 || body.length > MAX_SKIN_BYTES)
                    ? null : body;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The face square with the hat composited over it, scaled up.
     *
     * <p>Legacy 64x32 skins need no special case: the face and the hat sit at
     * the same coordinates in both layouts, and only the parts below row 32
     * differ.
     */
    static byte[] crop(Png.Image skin) throws java.io.IOException {
        if (skin.width() < FACE_X + FACE || skin.height() < FACE_Y + FACE) {
            throw new java.io.IOException("skin too small");
        }
        boolean hasHat = skin.width() >= HAT_X + FACE && skin.height() >= HAT_Y + FACE;
        int size = FACE * SCALE;
        int[] out = new int[size * size];
        for (int y = 0; y < size; y++) {
            int sy = y / SCALE;
            for (int x = 0; x < size; x++) {
                int sx = x / SCALE;
                int base = skin.at(FACE_X + sx, FACE_Y + sy) | 0xFF000000;
                if (hasHat) base = over(skin.at(HAT_X + sx, HAT_Y + sy), base);
                out[y * size + x] = base;
            }
        }
        return Png.encode(out, size, size);
    }

    /** Straight source-over compositing of {@code top} onto opaque {@code bottom}. */
    private static int over(int top, int bottom) {
        int a = top >>> 24;
        if (a == 0) return bottom;
        if (a == 255) return top;
        int r = (((top >> 16) & 0xFF) * a + ((bottom >> 16) & 0xFF) * (255 - a)) / 255;
        int g = (((top >> 8) & 0xFF) * a + ((bottom >> 8) & 0xFF) * (255 - a)) / 255;
        int b = ((top & 0xFF) * a + (bottom & 0xFF) * (255 - a)) / 255;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static JsonObject getJson(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .header("User-Agent", "TheMin3s/almin (Minecraft server admin mod)")
                .timeout(TIMEOUT)
                .GET()
                .build();
            try (HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build()) {
                HttpResponse<byte[]> response =
                    client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                // 204 is Mojang's "no such player", and is the ordinary answer
                // on an offline-mode server. Not a fault, not worth logging.
                if (response.statusCode() / 100 != 2) return null;
                byte[] body = response.body();
                if (body == null || body.length == 0 || body.length > 64 * 1024) return null;
                var parsed = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
                return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(JsonObject o, String k) {
        try {
            return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** Parses a UUID leniently: dashed or plain, or null. */
    public static UUID parseUuid(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.length() == 32 && s.matches("[0-9a-f]+")) {
            s = s.replaceFirst("(.{8})(.{4})(.{4})(.{4})(.{12})", "$1-$2-$3-$4-$5");
        }
        try {
            return UUID.fromString(s);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
