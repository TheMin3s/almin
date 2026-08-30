package com.schecks.almin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Noticing that the world is not the one this history is about.
 *
 * <h3>The problem</h3>
 * Almin's records do not live in the world folder. The activity log, the
 * position samples and the pictures of the ground are in {@code config/almin/}
 * so that they survive a server that will not start — which is exactly when
 * somebody most wants to read them. The cost of that choice is that deleting
 * the world does not delete them, and the panel then draws a map of a world
 * that no longer exists, with paths across terrain nobody can visit and a
 * timeline of things that happened somewhere else.
 *
 * <h3>How a new world is recognised</h3>
 * By the overworld's seed and the level's name, written to
 * {@code config/almin/world.json} the first time a world is seen. A world
 * regenerated from a new seed, or a different save loaded into the same
 * server folder, does not match and the records are cleared.
 *
 * <p>Regenerating with the <em>same</em> seed and the same name is
 * indistinguishable from restarting, and nothing here pretends otherwise —
 * the panel has buttons for that case, which is the honest answer to a
 * question no signal can settle.
 *
 * <h3>What is not touched</h3>
 * Mod offers, masks, trusted ops, client profiles, the config and the log
 * file. None of those are about the world; a new world is not a reason to
 * forget which mods this server hands out.
 */
public final class WorldReset {

    /** What was cleared, for whoever asked. */
    public record Cleared(boolean actions, boolean paths, boolean pictures, String message) {}

    private static volatile Path file;

    private WorldReset() {}

    /**
     * Compares this world against the one the records are about.
     *
     * <p>Called once the levels are loaded — the overworld does not exist
     * while the server is still starting.
     */
    public static synchronized void check(MinecraftServer server) {
        if (server == null) return;
        file = server.getServerDirectory().resolve("config").resolve("almin")
            .resolve("world.json");
        String now = identify(server);
        if (now.isEmpty()) return;                 // could not tell; never guess
        String was = remembered();
        if (was.equals(now)) return;
        if (!was.isEmpty()) {
            AlminLog.warn("[almin] this is a different world from the one on record "
                + "— clearing the activity log, the paths and the map pictures");
            Cleared done = wipe(true, true, true);
            AlminLog.info("[almin] {}", done.message());
        }
        remember(now);
    }

    /** The seed and name of the world now loaded, or "" if it cannot be read. */
    private static String identify(MinecraftServer server) {
        try {
            long seed = server.overworld().getSeed();
            String name = server.getWorldData().getLevelName();
            return seed + "/" + (name == null ? "" : name);
        } catch (Throwable t) {
            // A world that will not answer is not a world that has changed.
            return "";
        }
    }

    private static String remembered() {
        Path f = file;
        if (f == null || !Files.isRegularFile(f)) return "";
        try {
            JsonObject o = JsonParser.parseString(
                Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            return o.has("world") ? o.get("world").getAsString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static void remember(String id) {
        Path f = file;
        if (f == null) return;
        try {
            Files.createDirectories(f.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("world", id);
            o.addProperty("seen", System.currentTimeMillis());
            Files.writeString(f, o.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            AlminLog.warn("[almin] could not write world.json: {}", e.getMessage());
        }
    }

    /**
     * Throws away whichever records were asked for.
     *
     * <p>Also used by the panel's own buttons, because "this is a new world"
     * and "I want to start again" want exactly the same thing to happen.
     */
    public static synchronized Cleared wipe(boolean actions, boolean paths, boolean pictures) {
        StringBuilder said = new StringBuilder();
        boolean didActions = false, didPaths = false, didPictures = false;
        if (actions) {
            ActivityLog.wipe();
            didActions = true;
            said.append("cleared the activity log");
        }
        if (paths) {
            PlayerTracks.clear();
            didPaths = true;
            if (said.length() > 0) said.append(", ");
            said.append("cleared the paths");
        }
        if (pictures) {
            WorldSnapshots.clear();
            didPictures = true;
            if (said.length() > 0) said.append(", ");
            said.append("cleared the map pictures");
        }
        // A summary of a log that has gone is a summary of nothing.
        if (actions) AiInsights.forget();
        if (said.length() == 0) said.append("nothing to clear");
        return new Cleared(didActions, didPaths, didPictures, said.toString());
    }
}
