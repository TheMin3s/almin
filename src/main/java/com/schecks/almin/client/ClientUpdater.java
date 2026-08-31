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
 * When the server reports its required client version (ServerVersionPayload) and the client is
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

    /**
     * The newest version already sitting in {@code mods/} waiting for a
     * restart. Without it, every check would download the same jar again.
     */
    private static volatile String staged = "";

    private static java.util.concurrent.ScheduledExecutorService checker;

    private ClientUpdater() {}

    /**
     * Starts checking GitHub for a newer Almin.
     *
     * <h3>Why this exists</h3>
     * The client used to learn about versions one way only: by joining a
     * server and being told what that server ran. So a player whose server had
     * not updated never updated, and a player who had not joined anything
     * never checked at all. This asks GitHub directly — once shortly after
     * launch, then on a timer while the game stays open.
     *
     * <p>A jar cannot be swapped underneath a running game, so an update
     * downloaded now applies the next time Minecraft is started. That is the
     * whole design: nothing is interrupted, and the next launch is current.
     */
    public static synchronized void startBackgroundChecks() {
        if (checker != null) return;
        if (!ClientConfig.get().autoUpdate) {
            LOG.info("[Almin] automatic updates are off (auto-update in config/almin-client.json)");
            return;
        }
        checker = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Almin-client-update");
            // Daemon: a background check must never be the reason the game
            // will not close.
            t.setDaemon(true);
            return t;
        });
        int hours = ClientConfig.get().checkHours;
        // A short delay so the check does not compete with loading.
        checker.schedule(guarded(ClientUpdater::checkGitHub), 20, java.util.concurrent.TimeUnit.SECONDS);
        if (hours > 0) {
            checker.scheduleWithFixedDelay(guarded(ClientUpdater::checkGitHub),
                hours, hours, java.util.concurrent.TimeUnit.HOURS);
        }
    }

    /**
     * A repeating task that throws is cancelled for good and says nothing, so
     * one failed check would quietly end all of them.
     */
    private static Runnable guarded(Runnable job) {
        return () -> {
            try {
                job.run();
            } catch (Throwable t) {
                LOG.warn("[Almin] update check failed: {}", t.toString());
            }
        };
    }

    /** Asks GitHub what the newest release is, and takes it if it is newer. */
    private static void checkGitHub() {
        if (!ClientConfig.get().autoUpdate) return;
        String current = UpdateChecker.currentVersion();
        UpdateChecker.Release latest;
        try {
            latest = UpdateChecker.fetchLatestRelease(REPO, UpdateChecker.CLIENT_JAR);
        } catch (Exception e) {
            LOG.info("[Almin] could not reach GitHub for an update check: {}", e.toString());
            return;
        }
        if (latest == null || !latest.hasJar()) return;
        if (!worthInstalling(latest.version(), current, staged)) return;

        LOG.info("[Almin] {} is newer than {} — downloading for the next launch",
            latest.version(), current);
        install(latest, current);
    }

    /**
     * Whether {@code candidate} is worth downloading.
     *
     * <p>Two things stop it. It has to be newer than what is running, and it
     * has to be newer than whatever is already sitting in {@code mods/}
     * waiting for a restart — otherwise every check would fetch the same jar
     * again, because the running version does not change until the game is
     * restarted.
     */
    static boolean worthInstalling(String candidate, String current, String alreadyStaged) {
        if (candidate == null || candidate.isBlank()) return false;
        if (UpdateChecker.compareVersions(candidate, current) <= 0) return false;
        if (alreadyStaged == null || alreadyStaged.isBlank()) return true;
        return UpdateChecker.compareVersions(candidate, alreadyStaged) > 0;
    }

    /** Called on the client thread when the server reports its Almin version. */
    public static void onServerVersion(String requiredVersion) {
        if (handled || requiredVersion == null || requiredVersion.isBlank()) return;
        String clientVersion = UpdateChecker.currentVersion();
        int cmp = UpdateChecker.compareVersions(requiredVersion, clientVersion);
        if (cmp == 0) return;                  // versions match — nothing to do
        handled = true;
        if (cmp > 0) {
            // Server newer than client — auto-update to match.
            LOG.info("[Almin] client {} is behind server {} — fetching update",
                clientVersion, requiredVersion);
            CompletableFuture.runAsync(() -> downloadAndSwap(requiredVersion, clientVersion));
        } else {
            // Server older than client — warn the player to nag the owner.
            LOG.info("[Almin] server {} is older than client {} — showing warning",
                requiredVersion, clientVersion);
            Minecraft.getInstance().execute(() ->
                Minecraft.getInstance().setScreenAndShow(
                    new ServerOutdatedScreen(requiredVersion, clientVersion)));
        }
    }

    /** Forget that we already prompted — so each new server is evaluated fresh. */
    public static void reset() {
        handled = false;
    }

    private static void downloadAndSwap(String serverVersion, String clientVersion) {
        try {
            UpdateChecker.Release release = UpdateChecker.fetchReleaseByTag(
                REPO, serverVersion, UpdateChecker.CLIENT_JAR);
            if (release == null || !release.hasJar()) {
                LOG.warn("[Almin] client update: no jar asset for release {}", serverVersion);
                return;
            }
            install(release, clientVersion);
        } catch (Exception e) {
            LOG.warn("[Almin] client update failed: {}", e.toString());
        }
    }

    /**
     * Writes a release into {@code mods/} and removes the running jar.
     *
     * <p>Shared by both triggers — matching a server's version, and the
     * background check — because the risky half is identical either way: the
     * download is verified to be a real mod jar before anything is replaced,
     * and a failure leaves the current install untouched.
     */
    private static void install(UpdateChecker.Release release, String fromVersion) {
        Path tmp = null;
        try {
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
            staged = release.version();
            LOG.info("[Almin] Almin {} is installed and will be used from the next launch {}",
                release.version(), removal);
            Minecraft.getInstance().execute(() -> announce(fromVersion, release.version()));
        } catch (Exception e) {
            LOG.warn("[Almin] client update failed: {}", e.toString());
        } finally {
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            }
        }
    }

    /**
     * Tells the player, but only if they are in a world.
     *
     * <p>At the title screen there is nothing to interrupt and no reason to:
     * the update applies at the next launch either way, and throwing a screen
     * in front of someone who just opened the game would be an ambush.
     */
    private static void announce(String from, String to) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.setScreenAndShow(new UpdateAppliedScreen(from, to));
    }

    /** The version waiting for a restart, or "" when there is none. */
    public static String staged() {
        return staged;
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
