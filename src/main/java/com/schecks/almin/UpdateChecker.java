package com.schecks.almin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipFile;

/**
 * Checks the configured GitHub repo's latest release against the running mod
 * version. Used by /almin update and the boot-time notice.
 *
 * The {@link #LOGGER} here is the intentional path to the main server console
 * — used for the boot-time update notice and any auto-update progress. Routine
 * results go to {@link AlminLog} (the dedicated file) instead.
 */
public final class UpdateChecker {
    private static final Logger LOGGER = LoggerFactory.getLogger("almin");

    public record Release(String version, String jarUrl, String jarName) {
        public boolean hasJar() { return jarUrl != null && jarName != null; }
    }

    /** Outcome of a version check. */
    public sealed interface CheckResult {}
    public record UpToDate(String version) implements CheckResult {}
    public record UpdateAvailable(String current, Release release) implements CheckResult {}
    public record CheckFailed(String reason) implements CheckResult {}

    private UpdateChecker() {}

    /**
     * Registers the S2C version payload used by the client self-updater.
     * Call once from the main entrypoint.
     */
    public static void registerPayload() {
        PayloadTypeRegistry.clientboundPlay().register(
            ServerVersionPayload.TYPE, ServerVersionPayload.CODEC);
    }

    /** The running mod version (Loom-expanded mod_version), e.g. "1.0.0". */
    public static String currentVersion() {
        return FabricLoader.getInstance()
            .getModContainer(Almin.MOD_ID)
            .map(c -> c.getMetadata().getVersion().getFriendlyString())
            .orElse("0.0.0");
    }

    public static CompletableFuture<CheckResult> checkAsync() {
        return CompletableFuture.supplyAsync(UpdateChecker::checkBlocking);
    }

    private static CheckResult checkBlocking() {
        String repo = AlminConfig.get().updateRepo == null ? "" : AlminConfig.get().updateRepo.trim();
        if (repo.isEmpty()) return new CheckFailed("update-repo is not configured");
        Release latest;
        try {
            latest = fetchLatestRelease(repo);
        } catch (Exception e) {
            return new CheckFailed(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        if (latest == null) return new CheckFailed("no releases found for " + repo);

        String current = currentVersion();
        if (compareVersions(latest.version(), current) > 0) {
            return new UpdateAvailable(current, latest);
        }
        return new UpToDate(current);
    }

    /**
     * Marks the jar this side of the mod wants out of a release. A release
     * carries a server jar and a client jar, so "take the first .jar" would be
     * a coin flip — each side names the one it needs.
     */
    public static final String SERVER_JAR = "server";
    public static final String CLIENT_JAR = "client";

    private static Release fetchLatestRelease(String repo) throws IOException, InterruptedException {
        return fetchReleaseFrom(URI.create(
            "https://api.github.com/repos/" + repo + "/releases/latest"), SERVER_JAR);
    }

    /**
     * Fetches a specific tagged release (vX.Y.Z) from {@code repo}, or null if
     * that tag has no release. Used by the client to sync to a server's exact
     * version; {@code want} selects which jar of the release to take.
     */
    public static Release fetchReleaseByTag(String repo, String version, String want)
            throws IOException, InterruptedException {
        String tag = (version.startsWith("v") || version.startsWith("V")) ? version : "v" + version;
        return fetchReleaseFrom(URI.create(
            "https://api.github.com/repos/" + repo + "/releases/tags/" + tag), want);
    }

    private static Release fetchReleaseFrom(URI uri, String want) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();
        HttpRequest req = HttpRequest.newBuilder()
            .uri(uri)
            .timeout(Duration.ofSeconds(20))
            .header("User-Agent", "almin-mod")          // GitHub 403s requests without a UA
            .header("Accept", "application/vnd.github+json")
            .GET()
            .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 404) return null;        // repo has no releases yet
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("GitHub API HTTP " + resp.statusCode());
        }
        JsonObject obj = JsonParser.parseString(resp.body()).getAsJsonObject();
        if (!obj.has("tag_name")) return null;
        String tag = obj.get("tag_name").getAsString();
        String version = (tag.startsWith("v") || tag.startsWith("V")) ? tag.substring(1) : tag;

        // Prefer the jar for this side; fall back to any jar, so the single-jar
        // releases published before the server/client split still resolve.
        String jarUrl = null, jarName = null;
        String fallbackUrl = null, fallbackName = null;
        if (obj.has("assets") && obj.get("assets").isJsonArray()) {
            JsonArray assets = obj.getAsJsonArray("assets");
            for (var el : assets) {
                JsonObject a = el.getAsJsonObject();
                String name = a.has("name") ? a.get("name").getAsString() : "";
                if (!name.toLowerCase().endsWith(".jar") || !a.has("browser_download_url")) continue;
                String url = a.get("browser_download_url").getAsString();
                if (want != null && name.toLowerCase().contains(want)) {
                    jarName = name;
                    jarUrl = url;
                    break;
                }
                if (fallbackName == null) {
                    fallbackName = name;
                    fallbackUrl = url;
                }
            }
        }
        if (jarName == null) {
            jarName = fallbackName;
            jarUrl = fallbackUrl;
        }
        return new Release(version, jarUrl, jarName);
    }

    /**
     * Returns &gt;0 if a is newer than b, &lt;0 if older, 0 if equal.
     * Tolerant of "v" prefixes, differing segment counts, and non-numeric tails.
     */
    public static int compareVersions(String a, String b) {
        String[] pa = a.replaceAll("^[vV]", "").split("[.+\\-_]");
        String[] pb = b.replaceAll("^[vV]", "").split("[.+\\-_]");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int x = i < pa.length ? parseIntSafe(pa[i]) : 0;
            int y = i < pb.length ? parseIntSafe(pb[i]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    /** Path of this mod's own jar on disk, or null if it can't be resolved (e.g. dev env). */
    public static Path ownJarPath() {
        Optional<ModContainer> mc = FabricLoader.getInstance().getModContainer(Almin.MOD_ID);
        if (mc.isEmpty()) return null;
        ModOrigin origin = mc.get().getOrigin();
        if (origin.getKind() != ModOrigin.Kind.PATH) return null;
        List<Path> paths = origin.getPaths();
        if (paths == null || paths.isEmpty()) return null;
        Path p = paths.get(0);
        return Files.isRegularFile(p) ? p : null;
    }

    /**
     * Removes the currently-running jar after a new one has been downloaded.
     * Returns a human-readable status string describing what happened.
     */
    public static String removeOldJar(Path newJarPath) {
        Path old = ownJarPath();
        if (old == null) {
            return "(could not locate the old jar — if a duplicate appears, remove it manually before restart)";
        }
        Path oldAbs = old.toAbsolutePath().normalize();
        Path newAbs = newJarPath.toAbsolutePath().normalize();
        if (oldAbs.equals(newAbs)) {
            return "(replaced the jar in place)";
        }
        try {
            Files.delete(oldAbs);
            return "(removed old jar " + oldAbs.getFileName() + ")";
        } catch (IOException deleteFailed) {
            // Likely a Windows file lock — rename so Fabric ignores it (not a .jar anymore).
            try {
                Path disabled = oldAbs.resolveSibling(oldAbs.getFileName() + ".disabled");
                Files.move(oldAbs, disabled, StandardCopyOption.REPLACE_EXISTING);
                return "(old jar locked — renamed to " + disabled.getFileName() + ")";
            } catch (IOException renameFailed) {
                return "(WARNING: could not remove old jar " + oldAbs.getFileName()
                    + " — delete it manually before restarting or the server will fail to start"
                    + " with a duplicate mod id)";
            }
        }
    }

    /**
     * Boot-time check. Async. When auto-update is enabled in the config and a
     * newer release with a jar asset is found, it is downloaded, validated,
     * swapped in, and the server restarts to apply it. Otherwise the only
     * thing written to the console is a single "update available" WARN line.
     */
    public static void checkOnBoot(MinecraftServer server) {
        if (!AlminConfig.get().updateCheckOnBoot) return;
        checkAsync().thenAccept(result -> {
            switch (result) {
                case UpdateAvailable ua -> {
                    AlminLog.info("[almin] update available: {} (current {})",
                        ua.release().version(), ua.current());
                    if (AlminConfig.get().autoUpdate && ua.release().hasJar()) {
                        autoInstall(server, ua.release());
                    } else {
                        LOGGER.warn("[Almin] A new version is available: {} (currently running {}). "
                            + "Run /almin update to install it.", ua.release().version(), ua.current());
                    }
                }
                case UpToDate ut ->
                    AlminLog.info("[almin] up to date ({})", ut.version());
                case CheckFailed cf ->
                    AlminLog.warn("[almin] update check failed: {}", cf.reason());
            }
        });
    }

    /**
     * Downloads a release's jar into mods/, verifies it really is a mod jar,
     * removes the running jar, and restarts the server to load the new
     * version. If the download fails or looks invalid, the current version is
     * left untouched. Runs off the main thread except the final halt.
     */
    private static void autoInstall(MinecraftServer server, Release release) {
        Path serverDir = server.getServerDirectory();
        Path target = serverDir.resolve("mods").resolve(release.jarName());
        LOGGER.warn("[Almin] Auto-update: downloading {} ...", release.version());
        AlminLog.info("[almin] auto-update: downloading {} -> mods/{}",
            release.version(), release.jarName());

        FileFetcher.fetchAsync(release.jarUrl(), target, serverDir).thenAccept(fr -> {
            if (!fr.ok()) {
                LOGGER.warn("[Almin] Auto-update download failed: {} — keeping current version.",
                    fr.message());
                AlminLog.warn("[almin] auto-update download failed: {}", fr.message());
                return;
            }
            if (!looksLikeValidMod(target)) {
                LOGGER.warn("[Almin] Auto-update aborted: the download is not a valid mod jar"
                    + " — keeping current version.");
                AlminLog.warn("[almin] auto-update aborted: {} is not a valid mod jar",
                    release.jarName());
                try { Files.deleteIfExists(target); } catch (IOException ignored) {}
                return;
            }
            String removal = removeOldJar(target);
            LOGGER.warn("[Almin] Auto-update installed {} {} — restarting to apply.",
                release.version(), removal);
            AlminLog.info("[almin] auto-update installed {} ({} bytes) {}; restarting",
                release.version(), fr.bytes(), removal);
            server.execute(() -> server.halt(false));
        });
    }

    /** True if {@code jar} is a readable zip that contains a fabric.mod.json. */
    public static boolean looksLikeValidMod(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            return zip.getEntry("fabric.mod.json") != null;
        } catch (IOException e) {
            return false;
        }
    }
}
