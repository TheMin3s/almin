package com.schecks.almin;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The server-only half of automatic updates.
 *
 * <p>The release check is asynchronous, so a server can be empty when it
 * starts and have players by the time GitHub answers. A detected update is
 * therefore queued while anyone is online, then taken on the first empty
 * tick. Downloads are staged with a {@code .part} suffix and the player count
 * is checked again before the live jar is touched, so somebody joining during
 * a slow download does not get kicked and an interrupted stage cannot become
 * a second loadable Fabric mod.
 */
public final class ServerAutoUpdater {
    private static final Logger CONSOLE = LoggerFactory.getLogger("almin");
    private static final AtomicBoolean WORKING = new AtomicBoolean();

    /**
     * @param asked     somebody asked for this one, so the auto-update setting
     *                  is not what decides whether it happens
     * @param whenEmpty for an asked one, whether it still waits for the server
     *                  to be empty; automatic ones follow the config instead
     */
    private record Pending(UpdateChecker.Release release, Path staged, long bytes,
                           boolean asked, boolean whenEmpty) {}

    private static volatile Pending pending;

    private ServerAutoUpdater() {}

    /** Starts one boot check. Its result is handed back to the server thread. */
    public static void checkOnBoot(MinecraftServer server) {
        if (!AlminConfig.get().updateCheckOnBoot) return;
        UpdateChecker.checkAsync().thenAccept(result -> server.execute(() -> {
            switch (result) {
                case UpdateChecker.UpdateAvailable ua -> found(server, ua);
                case UpdateChecker.UpToDate ut ->
                    AlminLog.info("[almin] up to date ({})", ut.version());
                case UpdateChecker.CheckFailed cf ->
                    AlminLog.warn("[almin] update check failed: {}", cf.reason());
            }
        }));
    }

    private static void found(MinecraftServer server, UpdateChecker.UpdateAvailable found) {
        UpdateChecker.Release release = found.release();
        AlminLog.info("[almin] update available: {} (current {})",
            release.version(), found.current());
        if (!AlminConfig.get().autoUpdate || !release.hasJar()) {
            CONSOLE.warn("[Almin] A new version is available: {} (currently running {}). "
                + "Run /almin update to install it.", release.version(), found.current());
            return;
        }

        if (shouldWaitForPlayers(server.getPlayerCount())) {
            queue(new Pending(release, null, 0, false, false));
            CONSOLE.warn("[Almin] Update {} is ready and will install after the last player "
                + "leaves.", release.version());
            AlminLog.info("[almin] auto-update {} queued until the server is empty",
                release.version());
            return;
        }
        download(server, release, false, false);
    }

    /**
     * Installs a release because somebody asked for it at this moment — the
     * scheduled-update path.
     *
     * <p>Told apart from the automatic one all the way through: it does not
     * consult {@code auto-update}, which is a question about what the server
     * does on its own and not about what an admin just asked for, and it waits
     * for an empty server only if that is what they asked for.
     */
    public static void installAsked(MinecraftServer server, UpdateChecker.Release release,
                                    boolean whenEmpty) {
        download(server, release, true, whenEmpty);
    }

    /** Called every server tick; it is a nearly-free null check until queued. */
    public static void tick(MinecraftServer server) {
        Pending next = pending;
        if (next == null || WORKING.get()) return;
        if (!next.asked() && !AlminConfig.get().autoUpdate) {
            pending = null;
            deleteQuietly(next.staged());
            AlminLog.info("[almin] cancelled queued auto-update because auto-update is off");
            return;
        }
        if (shouldWait(next, server.getPlayerCount())) return;

        pending = null;
        if (next.staged() == null) download(server, next.release(), next.asked(), next.whenEmpty());
        else finish(server, next);
    }

    /** Package-visible so the offline suite can prove the waiting policy. */
    static boolean shouldWaitForPlayers(int players) {
        return AlminConfig.get().autoUpdateWhenEmpty && players > 0;
    }

    /**
     * An update somebody asked for carries its own answer to this. Only the
     * automatic ones read it out of the config.
     */
    private static boolean shouldWait(Pending p, int players) {
        return p.asked() ? p.whenEmpty() && players > 0 : shouldWaitForPlayers(players);
    }

    /** Version waiting for an empty server, or an empty string when none is queued. */
    public static String pendingVersion() {
        Pending p = pending;
        return p == null ? "" : p.release().version();
    }

    /** Clears process-local state at the beginning and end of one server run. */
    public static void reset() {
        Pending old = pending;
        pending = null;
        WORKING.set(false);
        if (old != null) deleteQuietly(old.staged());
    }

    private static void queue(Pending candidate) {
        Pending have = pending;
        if (have == null || UpdateChecker.compareVersions(
                candidate.release().version(), have.release().version()) >= 0) {
            if (have != null && have.staged() != null
                    && !have.staged().equals(candidate.staged())) deleteQuietly(have.staged());
            pending = candidate;
        } else {
            deleteQuietly(candidate.staged());
        }
    }

    private static void download(MinecraftServer server, UpdateChecker.Release release,
                                 boolean asked, boolean whenEmpty) {
        if (!WORKING.compareAndSet(false, true)) {
            queue(new Pending(release, null, 0, asked, whenEmpty));
            return;
        }

        Path serverDir = server.getServerDirectory();
        Path staged;
        try {
            staged = ServerJarUpdate.stage(serverDir, release.jarName());
        } catch (RuntimeException e) {
            WORKING.set(false);
            CONSOLE.warn("[Almin] Auto-update aborted: release {} has an invalid jar name.",
                release.version());
            return;
        }
        deleteQuietly(staged);

        CONSOLE.warn("[Almin] {}: downloading {} ...",
            asked ? "Scheduled update" : "Auto-update", release.version());
        AlminLog.info("[almin] {}: staging {} -> mods/{}",
            asked ? "scheduled update" : "auto-update", release.version(), staged.getFileName());
        FileFetcher.fetchAsync(release.jarUrl(), staged, serverDir).whenComplete((fr, error) ->
            server.execute(() -> downloaded(server, release, staged, fr, error, asked, whenEmpty)));
    }

    private static void downloaded(MinecraftServer server, UpdateChecker.Release release,
                                   Path staged, FileFetcher.FetchResult fetched,
                                   Throwable error, boolean asked, boolean whenEmpty) {
        if (error != null || fetched == null || !fetched.ok()) {
            String why = error != null ? error.toString()
                : fetched == null ? "no result" : fetched.message();
            CONSOLE.warn("[Almin] Auto-update download failed: {} — keeping current version.",
                why);
            AlminLog.warn("[almin] auto-update download failed: {}", why);
            deleteQuietly(staged);
            WORKING.set(false);
            return;
        }
        if (!UpdateChecker.looksLikeValidMod(staged)) {
            CONSOLE.warn("[Almin] Auto-update aborted: the download is not a valid mod jar "
                + "— keeping current version.");
            AlminLog.warn("[almin] auto-update aborted: staged {} is not a valid mod jar",
                release.jarName());
            deleteQuietly(staged);
            WORKING.set(false);
            return;
        }
        if (!asked && !AlminConfig.get().autoUpdate) {
            deleteQuietly(staged);
            WORKING.set(false);
            return;
        }
        Pending ready = new Pending(release, staged, fetched.bytes(), asked, whenEmpty);
        // Somebody can join while a download is running, and the whole point
        // of waiting for an empty server is not kicking them out of it.
        if (shouldWait(ready, server.getPlayerCount())) {
            queue(ready);
            WORKING.set(false);
            CONSOLE.warn("[Almin] Update {} finished downloading, but somebody joined; it "
                + "will install after the server is empty again.", release.version());
            return;
        }
        finish(server, ready);
    }

    /** Runs on the server thread, after the final empty-server check. */
    private static void finish(MinecraftServer server, Pending ready) {
        WORKING.set(true);
        UpdateChecker.Release release = ready.release();
        ServerJarUpdate.Install installed = ServerJarUpdate.install(
            server.getServerDirectory(), ready.staged(), release.jarName());
        if (!installed.ok()) {
            CONSOLE.warn("[Almin] Auto-update could not move the staged jar into place: {}",
                installed.message());
            AlminLog.warn("[almin] auto-update install failed: {}", installed.message());
            deleteQuietly(ready.staged());
            WORKING.set(false);
            return;
        }

        String removal = installed.message();
        CONSOLE.warn("[Almin] Auto-update installed {} {} — restarting to apply.",
            release.version(), removal);
        AlminLog.info("[almin] auto-update installed {} ({} bytes) {}; restarting",
            release.version(), ready.bytes(), removal);
        boolean relaunch = ServerRelaunch.arm("an auto-update to " + release.version());
        server.getPlayerList().broadcastSystemMessage(Component.literal(
            "[Almin] Updating to " + release.version() + " — the server is restarting."), false);
        if (!relaunch) AlminExit.arm("an auto-update to " + release.version());
        server.halt(false);
    }

    private static void deleteQuietly(Path file) {
        if (file == null) return;
        try { Files.deleteIfExists(file); }
        catch (IOException ignored) {}
    }
}
