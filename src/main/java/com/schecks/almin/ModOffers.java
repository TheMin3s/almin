package com.schecks.almin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The set of mods this server advertises to joining players, stored in
 * {@code config/almin/mods.json} and managed with {@code /almin mods} or the
 * web panel's Mods tab.
 *
 * <h3>What this is, and what it is not</h3>
 * An offer is a <em>suggestion with a download link</em>. The server never
 * pushes a file; it sends a list, the player's client shows it, and nothing is
 * downloaded unless that player clicks Approve. Installed jars land in
 * {@code mods/} and take effect on the client's next launch — nothing is
 * hot-loaded into a running game.
 *
 * <p>An offer names its jar one of two ways:
 * <ul>
 *   <li><b>Server-hosted</b> (preferred) — the jar sits in
 *       {@code config/almin/modfiles/} and is streamed to the player over the
 *       game connection they already trust. No third-party host is involved and
 *       nothing has to be reachable from the public internet.</li>
 *   <li><b>By URL</b> — an {@code https://} link the client fetches itself.</li>
 * </ul>
 *
 * <p>This is still the most dangerous surface in the mod, because approving
 * means running someone else's code. The guards live here, at the point where
 * an offer is created:
 * <ul>
 *   <li>URLs must be {@code https://} — checked when an offer is added and
 *       again by the client before it downloads.</li>
 *   <li>An optional SHA-256 pins the exact file; the client refuses a download
 *       whose digest doesn't match.</li>
 * </ul>
 * The client also shows the host it is about to download from, so the person
 * clicking Approve can see who they're trusting.
 */
public final class ModOffers {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** Keeps one offer list from becoming an unbounded packet. */
    public static final int MAX_OFFERS = 64;

    /** Largest jar this server will host and stream to a client. */
    public static final long MAX_FILE_BYTES = 32L * 1024 * 1024;

    private static final List<AdvertisedMod> OFFERS = new ArrayList<>();
    private static volatile Path path;
    private static volatile Path modFilesDir;

    private ModOffers() {}

    /**
     * One advertised mod. {@code modId} is the Fabric mod id, used by the client
     * to skip anything already installed. {@code sha256} may be empty.
     */
    public record AdvertisedMod(String modId, String name, String version,
                                String url, String sha256, boolean required,
                                String file) {
        /** True when the jar is hosted by this server rather than fetched from a URL. */
        public boolean serverHosted() { return file != null && !file.isBlank(); }
    }

    /** Load (or create) mods.json. Call once at server start. */
    public static synchronized void init(MinecraftServer server) {
        Path dir = server.getServerDirectory().resolve("config").resolve("almin");
        path = dir.resolve("mods.json");
        modFilesDir = dir.resolve("modfiles");
        try {
            Files.createDirectories(modFilesDir);
        } catch (IOException e) {
            AlminLog.warn("[almin] could not create modfiles/ folder: {}", e.getMessage());
        }
        if (!Files.exists(path)) writeStub();
        reload();
    }

    /** Where admins drop jars for the server to host. */
    public static Path modFilesDir() {
        return modFilesDir;
    }

    /**
     * Resolves a bare filename inside modfiles/. Returns null for anything with
     * a path separator, a parent reference, or a non-jar extension — the name
     * comes from config and, on the request path, from a client.
     */
    public static Path resolveModFile(String name) {
        if (modFilesDir == null || name == null) return null;
        String n = name.trim();
        if (n.isEmpty() || n.contains("/") || n.contains("\\") || n.contains("..")) return null;
        if (!n.toLowerCase().endsWith(".jar")) return null;
        Path p = modFilesDir.resolve(n).toAbsolutePath().normalize();
        if (!p.startsWith(modFilesDir.toAbsolutePath().normalize())) return null;
        return p;
    }

    /** Jar filenames currently sitting in modfiles/, sorted. */
    public static List<String> availableFiles() {
        List<String> out = new ArrayList<>();
        if (modFilesDir == null || !Files.isDirectory(modFilesDir)) return out;
        try (var s = Files.list(modFilesDir)) {
            s.filter(Files::isRegularFile)
             .map(f -> f.getFileName().toString())
             .filter(n -> n.toLowerCase().endsWith(".jar"))
             .sorted()
             .forEach(out::add);
        } catch (IOException ignored) {}
        return out;
    }

    /** Re-read the file. Returns true if loading succeeded. */
    public static synchronized boolean reload() {
        if (path == null) return false;
        OFFERS.clear();
        if (!Files.exists(path)) return true;
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseString(json);
            JsonArray arr = root.isJsonObject() && root.getAsJsonObject().has("mods")
                ? root.getAsJsonObject().getAsJsonArray("mods")
                : root.isJsonArray() ? root.getAsJsonArray() : new JsonArray();
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                String modId = str(o, "id");
                String url = str(o, "url");
                String file = str(o, "file");
                if (modId.isBlank()) continue;
                if (!file.isBlank()) {
                    // Server-hosted: the file must actually be there, or the
                    // offer would fail only once a player pressed Approve.
                    Path resolved = resolveModFile(file);
                    if (resolved == null || !Files.isRegularFile(resolved)) {
                        AlminLog.warn("[almin] mod offer '{}' skipped — modfiles/{} is missing", modId, file);
                        continue;
                    }
                    url = "";
                } else if (url.isBlank() || !isHttps(url)) {
                    AlminLog.warn("[almin] mod offer '{}' skipped — needs a modfiles/ entry or an https url", modId);
                    continue;
                }
                if (OFFERS.size() >= MAX_OFFERS) {
                    AlminLog.warn("[almin] mods.json holds more than {} entries; the rest are ignored", MAX_OFFERS);
                    break;
                }
                String name = str(o, "name");
                OFFERS.add(new AdvertisedMod(
                    modId,
                    name.isBlank() ? modId : name,
                    str(o, "version"),
                    url,
                    str(o, "sha256"),
                    o.has("required") && o.get("required").getAsBoolean(),
                    file));
            }
            return true;
        } catch (Exception e) {
            AlminLog.warn("[almin] mods.json unreadable ({}), advertising nothing", e.getMessage());
            return false;
        }
    }

    private static String str(JsonObject o, String key) {
        try {
            return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString().trim() : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** True for a syntactically valid https URL with a host. */
    public static boolean isHttps(String url) {
        try {
            URI u = new URI(url);
            return "https".equalsIgnoreCase(u.getScheme()) && u.getHost() != null && !u.getHost().isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    /** A copy of the current offers, in file order. */
    public static synchronized List<AdvertisedMod> list() {
        return new ArrayList<>(OFFERS);
    }

    public static synchronized int count() {
        return OFFERS.size();
    }

    /** True if any advertised mod is marked required. */
    public static synchronized boolean anyRequired() {
        return OFFERS.stream().anyMatch(AdvertisedMod::required);
    }

    /** Outcome of {@link #add}. */
    public enum AddResult { OK, BAD_URL, BAD_FILE, MISSING_FILE, DUPLICATE, FULL, NOT_LOADED, IO_ERROR }

    /** Adds (or replaces) an offer and writes mods.json. */
    public static synchronized AddResult add(AdvertisedMod mod) {
        if (path == null) return AddResult.NOT_LOADED;
        if (mod.modId() == null || mod.modId().isBlank()) return AddResult.BAD_URL;
        if (mod.serverHosted()) {
            Path resolved = resolveModFile(mod.file());
            if (resolved == null) return AddResult.BAD_FILE;
            if (!Files.isRegularFile(resolved)) return AddResult.MISSING_FILE;
        } else if (!isHttps(mod.url())) {
            return AddResult.BAD_URL;
        }
        boolean replacing = OFFERS.removeIf(m -> m.modId().equalsIgnoreCase(mod.modId()));
        if (!replacing && OFFERS.size() >= MAX_OFFERS) return AddResult.FULL;
        OFFERS.add(mod);
        return persist() ? AddResult.OK : AddResult.IO_ERROR;
    }

    /** Removes an offer by mod id. Returns true if one was removed. */
    public static synchronized boolean remove(String modId) {
        if (path == null || modId == null) return false;
        if (!OFFERS.removeIf(m -> m.modId().equalsIgnoreCase(modId))) return false;
        persist();
        return true;
    }

    /** Flips an offer's required flag. Returns false if no such offer. */
    public static synchronized boolean setRequired(String modId, boolean required) {
        if (path == null || modId == null) return false;
        for (int i = 0; i < OFFERS.size(); i++) {
            AdvertisedMod m = OFFERS.get(i);
            if (m.modId().equalsIgnoreCase(modId)) {
                OFFERS.set(i, new AdvertisedMod(m.modId(), m.name(), m.version(),
                    m.url(), m.sha256(), required, m.file()));
                persist();
                return true;
            }
        }
        return false;
    }

    private static boolean persist() {
        if (path == null) return false;
        try {
            Files.createDirectories(path.getParent());
            JsonArray arr = new JsonArray();
            for (AdvertisedMod m : OFFERS) {
                JsonObject o = new JsonObject();
                o.addProperty("id", m.modId());
                o.addProperty("name", m.name());
                o.addProperty("version", m.version());
                o.addProperty("url", m.url());
                o.addProperty("file", m.file() == null ? "" : m.file());
                o.addProperty("sha256", m.sha256());
                o.addProperty("required", m.required());
                arr.add(o);
            }
            JsonObject root = new JsonObject();
            root.add("mods", arr);
            Path tmp = Files.createTempFile(path.getParent(), ".mods-", ".tmp");
            Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            AlminLog.warn("[almin] failed to write mods.json: {}", e.getMessage());
            return false;
        }
    }

    private static void writeStub() {
        try {
            Files.createDirectories(path.getParent());
            // Ships empty on purpose — an example entry in "mods" would be a real
            // offer, and every player would be asked to download it.
            Files.writeString(path,
                "{\n"
              + "  \"_comment\": \"Mods this server offers to joining players. Nothing downloads until a player approves it. Prefer 'file': drop the jar in config/almin/modfiles/ and the server streams it over the game connection. 'url' is the alternative and must be https.\",\n"
              + "  \"_example\": {\n"
              + "    \"id\": \"sodium\",\n"
              + "    \"name\": \"Sodium\",\n"
              + "    \"version\": \"0.5.11\",\n"
              + "    \"file\": \"sodium-0.5.11.jar\",\n"
              + "    \"url\": \"\",\n"
              + "    \"sha256\": \"\",\n"
              + "    \"required\": false\n"
              + "  },\n"
              + "  \"mods\": []\n"
              + "}\n",
                StandardCharsets.UTF_8);
        } catch (IOException e) {
            AlminLog.warn("[almin] failed to write mods.json stub: {}", e.getMessage());
        }
    }
}
