package com.schecks.almin;

import com.schecks.almin.commands.AlminCommand;
import com.schecks.almin.events.ActivityHooks;
import com.schecks.almin.events.JoinHandler;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class Almin implements ModInitializer {
    public static final String MOD_ID = "almin";

    @Override
    public void onInitialize() {
        // Packet types first: both entrypoints need them declared before any
        // handler or receiver can be attached.
        AlminPayloads.registerTypes();
        ModNet.register();
        WebAdminNet.register();
        ActivityNet.register();
        NanoNet.register();
        DirNet.register();
        UploadNet.register();
        ConsoleNet.register();
        JoinHandler.register();
        ActivityHooks.register();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            AlminCommand.register(dispatcher));
        // Dedicated event log goes to config/almin/almin.log; opens at server
        // start, closes at server stop. Auto-rotates every 6h. Never writes
        // to the main server console — no boot message either. The tunable
        // config.json is loaded alongside it.
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            adoptLegacyConfigDir(server.getServerDirectory());
            AlminLog.init(server.getServerDirectory());
            AlminConfig.init(server.getServerDirectory());
            MaskConfig.init(server);
            ModOffers.init(server);
            FileShare.init(server);
            ActivityLog.init(server);
            WorldSnapshots.init(server);
        });
        // Boot-time update check — runs after config is loaded. With auto-update
        // enabled (the default) it downloads, installs and restarts into a newer
        // version on its own; otherwise it just logs a single "update available"
        // warning to the console.
        // Position sampling for the activity map. Its own schedule lives in
        // PlayerTracks; this only has to offer it the tick.
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK
            .register(PlayerTracks::sample);
        // Who has stopped moving. Its own schedule lives in Afk; this only has
        // to offer the tick.
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK
            .register(Afk::tick);
        // Pictures of the ground, so the map has a world under it. Its own
        // schedule lives in WorldSnapshots; this only has to offer the tick.
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK
            .register(WorldSnapshots::tick);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            Dashboard.markStarted();
            ConsoleTap.start(server);
            WebUi.start(server);
            UpdateChecker.checkOnBoot(server);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            // In supervisor mode the panel deliberately outlives the server, so
            // it can start it again; otherwise this shuts it down with the rest.
            WebUi.onServerStopped();
            ConsoleTap.stop();
            ActivityLog.close();
            WorldSnapshots.close();
            // Last, and deliberately so: when the stop was a restart, this
            // starts the server again and then ends this process. Anything
            // that still has a file to close has to come above it, because
            // halt() does not run shutdown hooks. It closes the Almin log
            // itself on the way out, so this can still write to it.
            WebUi.handOver();
            // A panel still up here is one deliberately outliving the server —
            // supervisor mode, or a restart that failed and is saying so. It
            // has more to write.
            if (!AlminConfig.get().webSupervisor && !WebUi.running()) AlminLog.close();
        });
    }

    /**
     * Moves a pre-rename {@code config/lifesmp/} folder to {@code config/almin/},
     * so a server upgrading from LifeSMP keeps its settings and masks instead of
     * silently starting from defaults. Runs before anything reads the folder,
     * and does nothing once the new folder exists.
     */
    private static void adoptLegacyConfigDir(Path serverDir) {
        Path legacy = serverDir.resolve("config").resolve("lifesmp");
        Path current = serverDir.resolve("config").resolve("almin");
        if (Files.exists(current) || !Files.isDirectory(legacy)) return;
        try {
            Files.move(legacy, current);
            // The log file is named after the mod too.
            Path oldLog = current.resolve("lifesmp.log");
            if (Files.isRegularFile(oldLog)) {
                Files.move(oldLog, current.resolve("almin.log"));
            }
        } catch (IOException e) {
            // Not fatal: the config classes just create a fresh folder instead.
            // Nothing is logged here — AlminLog isn't open yet.
        }
    }
}
