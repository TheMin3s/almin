package com.schecks.almin.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The handful of settings that belong to a player's own copy of Almin.
 *
 * <p>Separate from {@code AlminConfig}, which is the server's: this file lives
 * in the player's game directory and is never sent anywhere. It exists mostly
 * for one switch — Almin keeps itself up to date by writing a jar into the
 * player's {@code mods} folder, and anyone who would rather it did not should
 * be able to say so without uninstalling the mod.
 */
public final class ClientConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Download a newer Almin in the background and apply it on next launch. */
    public boolean autoUpdate = true;

    /** Hours between checks while the game is open. 0 checks only at startup. */
    public int checkHours = 3;

    private static volatile ClientConfig instance;

    /** Never null: an unreadable or missing file means defaults. */
    public static ClientConfig get() {
        ClientConfig c = instance;
        if (c != null) return c;
        synchronized (ClientConfig.class) {
            if (instance == null) instance = load();
            return instance;
        }
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("almin-client.json");
    }

    private static ClientConfig load() {
        ClientConfig cfg = new ClientConfig();
        Path file = path();
        try {
            if (Files.isRegularFile(file)) {
                JsonObject o = JsonParser.parseString(
                    Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                if (o.has("auto-update")) cfg.autoUpdate = o.get("auto-update").getAsBoolean();
                if (o.has("check-hours")) {
                    cfg.checkHours = Math.max(0, Math.min(168, o.get("check-hours").getAsInt()));
                }
            }
            save(cfg, file);   // rewrite so a new key appears in the file
        } catch (Exception e) {
            // A player's config being unreadable is not worth a crash on a
            // screen they cannot see yet; defaults are the safe answer.
        }
        return cfg;
    }

    private static void save(ClientConfig cfg, Path file) {
        try {
            Files.createDirectories(file.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("auto-update", cfg.autoUpdate);
            o.addProperty("check-hours", cfg.checkHours);
            Files.writeString(file, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // Read-only config dir. Nothing here is worth failing over.
        }
    }
}
