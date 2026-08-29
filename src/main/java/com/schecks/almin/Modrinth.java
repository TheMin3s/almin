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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Looks mods up on Modrinth so an advertisement can be built from a name or a
 * link instead of six fields typed by hand.
 *
 * <h3>Why this exists</h3>
 * The advertised-mods list needs a mod id, a version, a download URL and a
 * hash. Typed by hand, the id is the one that goes wrong — it is the Fabric
 * mod id, not the name on the page, and when it is wrong a client cannot tell
 * that the player already has the mod, so they are offered it on every join.
 * Resolving from Modrinth gets the URL and hash right; downloading the jar and
 * reading its own {@code fabric.mod.json} gets the id right (see
 * {@link ModJars}).
 *
 * <h3>Versions</h3>
 * Everything is filtered by the server's own Minecraft version and the Fabric
 * loader, so a link to a project resolves to the file that actually fits this
 * server rather than to whatever is newest.
 *
 * <h3>Care taken</h3>
 * Calls go out with a timeout and a small response cap, never on the server
 * thread. Nothing here downloads a jar — that stays with {@link FileFetcher},
 * which already has the size limits and the redirect rules.
 */
public final class Modrinth {
    private static final String API = "https://api.modrinth.com/v2";

    /** Modrinth asks projects to identify themselves; being anonymous is rude. */
    private static final String AGENT = "TheMin3s/almin (Minecraft server admin mod)";

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** A search answer is JSON; anything this large is not one we asked for. */
    private static final int MAX_RESPONSE = 2 * 1024 * 1024;

    private static final int MAX_RESULTS = 20;

    private Modrinth() {}

    /** One search hit, trimmed to what a person choosing needs to see. */
    public record Hit(String slug, String title, String description, int downloads,
                      String iconUrl) {}

    /** A file that fits this server: what to advertise and where to get it. */
    public record Resolved(String slug, String title, String version, String url,
                           String filename, String sha512, String iconUrl, String problem) {
        public boolean ok() { return problem.isEmpty(); }
        static Resolved fail(String why) {
            return new Resolved("", "", "", "", "", "", "", why);
        }
        /** The project page, for a link out of the panel. */
        public String page() { return slug.isEmpty() ? "" : "https://modrinth.com/mod/" + slug; }
    }

    /** A project's display name and icon, both optional. */
    private record About(String title, String iconUrl) {}

    /**
     * The project slug in a Modrinth link, or "" if it is not one.
     *
     * <p>Accepts what a person would actually paste: the project page, with or
     * without a trailing path, and with or without the scheme.
     */
    public static String slugFrom(String link) {
        if (link == null) return "";
        String s = link.trim();
        if (s.isEmpty()) return "";
        s = s.replaceFirst("^https?://", "");
        if (!s.toLowerCase(Locale.ROOT).startsWith("modrinth.com")) {
            // A bare slug is fine too — that is what the search results give.
            // Deliberately narrower than Modrinth's own charset: no dots and no
            // slashes, so a half-typed host cannot be mistaken for a project.
            return s.matches("[a-zA-Z0-9_-]{1,64}") ? s : "";
        }
        String[] parts = s.split("/");
        // modrinth.com / <type> / <slug> / ...
        if (parts.length < 3) return "";
        String slug = parts[2];
        int q = slug.indexOf('?');
        if (q >= 0) slug = slug.substring(0, q);
        return slug;
    }

    /** Searches Fabric mods for this Minecraft version. */
    public static List<Hit> search(String query, String gameVersion) throws IOException {
        String facets = "[[\"project_type:mod\"],[\"categories:fabric\"],[\"versions:"
            + gameVersion + "\"]]";
        String url = API + "/search?limit=" + MAX_RESULTS
            + "&query=" + enc(query)
            + "&facets=" + enc(facets);
        JsonObject body = getJson(url).getAsJsonObject();
        List<Hit> out = new ArrayList<>();
        for (JsonElement el : body.getAsJsonArray("hits")) {
            JsonObject o = el.getAsJsonObject();
            out.add(new Hit(
                str(o, "slug"),
                str(o, "title"),
                str(o, "description"),
                o.has("downloads") ? o.get("downloads").getAsInt() : 0,
                str(o, "icon_url")));
        }
        return out;
    }

    /**
     * The newest release of {@code slug} that fits this server.
     *
     * <p>Release before beta before alpha, newest first within that — the same
     * order Modrinth's own page uses, so what Almin picks is what a person
     * looking at the page would have picked.
     */
    public static Resolved resolve(String slugOrLink, String gameVersion) {
        String slug = slugFrom(slugOrLink);
        if (slug.isEmpty()) return Resolved.fail("That doesn't look like a Modrinth link or slug.");
        try {
            String url = API + "/project/" + enc(slug) + "/version"
                + "?loaders=" + enc("[\"fabric\"]")
                + "&game_versions=" + enc("[\"" + gameVersion + "\"]");
            JsonElement parsed = getJson(url);
            if (!parsed.isJsonArray()) return Resolved.fail("Modrinth returned something unexpected.");
            JsonArray versions = parsed.getAsJsonArray();
            if (versions.isEmpty()) {
                return Resolved.fail("No Fabric build of " + slug + " for Minecraft " + gameVersion + ".");
            }
            JsonObject best = pick(versions);
            if (best == null) return Resolved.fail("No downloadable file for " + slug + ".");

            JsonObject file = primaryFile(best);
            if (file == null) return Resolved.fail("That release has no file attached.");

            About about = aboutProject(slug);
            return new Resolved(slug, about.title(),
                str(best, "version_number"),
                str(file, "url"),
                str(file, "filename"),
                file.has("hashes") ? str(file.getAsJsonObject("hashes"), "sha512") : "",
                about.iconUrl(),
                "");
        } catch (IOException e) {
            return Resolved.fail("Could not reach Modrinth: " + e.getMessage());
        } catch (RuntimeException e) {
            return Resolved.fail("Modrinth answered with something unreadable.");
        }
    }

    /** Release beats beta beats alpha; the list already arrives newest first. */
    private static JsonObject pick(JsonArray versions) {
        JsonObject beta = null;
        JsonObject alpha = null;
        for (JsonElement el : versions) {
            JsonObject v = el.getAsJsonObject();
            if (primaryFile(v) == null) continue;
            String type = str(v, "version_type");
            if ("release".equals(type)) return v;
            if ("beta".equals(type) && beta == null) beta = v;
            if ("alpha".equals(type) && alpha == null) alpha = v;
        }
        return beta != null ? beta : alpha;
    }

    /** The file marked primary, or the first .jar. */
    private static JsonObject primaryFile(JsonObject version) {
        if (!version.has("files")) return null;
        JsonObject firstJar = null;
        for (JsonElement el : version.getAsJsonArray("files")) {
            JsonObject f = el.getAsJsonObject();
            String name = str(f, "filename");
            if (!name.toLowerCase(Locale.ROOT).endsWith(".jar")) continue;
            if (f.has("primary") && f.get("primary").getAsBoolean()) return f;
            if (firstJar == null) firstJar = f;
        }
        return firstJar;
    }

    /** The project's display name and icon, falling back to the slug. */
    private static About aboutProject(String slug) {
        try {
            JsonObject o = getJson(API + "/project/" + enc(slug)).getAsJsonObject();
            String title = str(o, "title");
            return new About(title.isEmpty() ? slug : title, str(o, "icon_url"));
        } catch (Exception e) {
            return new About(slug, "");
        }
    }

    private static JsonElement getJson(String url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", AGENT)
            .header("Accept", "application/json")
            .timeout(TIMEOUT)
            .GET()
            .build();
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()) {
            HttpResponse<byte[]> response =
                client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 404) throw new IOException("no such project");
            if (response.statusCode() / 100 != 2) {
                throw new IOException("Modrinth said " + response.statusCode());
            }
            byte[] bytes = response.body();
            if (bytes.length > MAX_RESPONSE) throw new IOException("response too large");
            return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }
}
