package com.schecks.almin.client;

import com.schecks.almin.UpdateChecker;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Keeps the client mod in sync with the server's Almin version.
 *
 * When the server reports its version (ServerVersionPayload) and the client is
 * behind, the matching release is downloaded from the FIXED official repo —
 * never a URL chosen by the server — verified to be a real mod jar, and
 * swapped into mods/. It takes effect the next time Minecraft is launched, so
 * dismissing the notice just means it applies on your next start.
 */
public final class ClientUpdater {
    /** Hardcoded on purpose: the server only ever supplies a version number. */
    private static final String REPO = "TheMin3s/almin";
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final Logger LOG = LoggerFactory.getLogger("almin");

    private static volatile boolean handled = false;

    private ClientUpdater() {}

    /** Called on the client thread when the server reports its Almin version. */
    public static void onServerVersion(String serverVersion) {
        if (handled || serverVersion == null || serverVersion.isBlank()) return;
        String clientVersion = UpdateChecker.currentVersion();
        int cmp = UpdateChecker.compareVersions(serverVersion, clientVersion);
        if (cmp == 0) return;                  // versions match — nothing to do
        handled = true;
        if (cmp > 0) {
            // Server newer than client — auto-update to match.
            LOG.info("[Almin] client {} is behind server {} — fetching update",
                clientVersion, serverVersion);
            CompletableFuture.runAsync(() -> downloadAndSwap(serverVersion, clientVersion));
        } else {
            // Server older than client — warn the player to nag the owner.
            LOG.info("[Almin] server {} is older than client {} — showing warning",
                serverVersion, clientVersion);
            Minecraft.getInstance().execute(() ->
                Minecraft.getInstance().setScreenAndShow(
                    new ServerOutdatedScreen(serverVersion, clientVersion)));
        }
    }

    /** Forget that we already prompted — so each new server is evaluated fresh. */
    public static void reset() {
        handled = false;
    }

    private static void downloadAndSwap(String serverVersion, String clientVersion) {
        Path tmp = null;
        try {
            UpdateChecker.Release release = UpdateChecker.fetchReleaseByTag(
                REPO, serverVersion, UpdateChecker.CLIENT_JAR);
            if (release == null || !release.hasJar()) {
                LOG.warn("[Almin] client update: no jar asset for release {}", serverVersion);
                return;
            }
            Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
            Files.createDirectories(modsDir);
            tmp = Files.createTempFile(modsDir, ".almin-update-", ".part");
            download(release.jarUrl(), tmp);
            if (!UpdateChecker.looksLikeValidMod(tmp)) {
                LOG.warn("[Almin] client update: download is not a valid mod jar — aborting");
                Files.deleteIfExists(tmp);
                return;
            }
            Path target = modsDir.resolve(release.jarName());
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            tmp = null;
            String removal = UpdateChecker.removeOldJar(target);
            LOG.info("[Almin] client updated to {} {}", release.version(), removal);
            Minecraft.getInstance().execute(() -> announce(clientVersion, release.version()));
        } catch (Exception e) {
            LOG.warn("[Almin] client update failed: {}", e.toString());
        } finally {
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            }
        }
    }

    private static void announce(String from, String to) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.setScreenAndShow(new UpdateAppliedScreen(from, to));
    }

    private static void download(String url, Path dest) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofMinutes(5))
            .header("User-Agent", "almin-mod")
            .GET()
            .build();
        HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("HTTP " + resp.statusCode());
        }
        long total = 0;
        try (InputStream in = resp.body(); OutputStream out = Files.newOutputStream(dest)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BYTES) throw new IOException("update jar exceeds size cap");
                out.write(buf, 0, n);
            }
        }
    }
}
