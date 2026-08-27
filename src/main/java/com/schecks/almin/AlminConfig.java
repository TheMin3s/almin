package com.schecks.almin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * All tunable Almin settings (everything outside the /almin op tree).
 *
 * Persisted as {server-root}/config/almin/config.json. Editable on disk or
 * live via /almin config. A single {@link #KEYS} registry drives both the
 * JSON file format and the command, so the two never drift.
 *
 * Access the live values through {@link #get()}. Before the server has
 * started (config not yet loaded) {@code get()} returns a fresh all-defaults
 * instance, so callers never NPE.
 */
public final class AlminConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static volatile AlminConfig instance;
    private static volatile Path path;

    // ---- tunable settings (field initialisers are the defaults) ----
    public String updateRepo = "TheMin3s/lifesmp";
    public boolean updateCheckOnBoot = true;
    public boolean autoUpdate = true;
    public String dirWritableRoots = "mods,config,resourcepacks,shared";
    public int spawnImmunitySeconds = 3;
    public boolean webUiEnabled = true;
    /** 0 until the first startup picks one; see {@link #ensureFirstRunDefaults}. */
    public int webUiPort = 0;
    public String webUiBind = "0.0.0.0";
    /** Empty until the first startup generates one. Grants full read access. */
    public String webUiToken = "";

    public enum Type { INT, BOOL, TEXT }

    /** One configurable setting: its name, type, bounds, and field accessors. */
    public static final class Key {
        public final String name;
        public final String description;
        public final Type type;
        public final int min, max;                       // INT only
        public final Function<AlminConfig, Object> getter;
        public final BiConsumer<AlminConfig, Object> setter;

        Key(String name, String description, Type type, int min, int max,
            Function<AlminConfig, Object> getter, BiConsumer<AlminConfig, Object> setter) {
            this.name = name;
            this.description = description;
            this.type = type;
            this.min = min;
            this.max = max;
            this.getter = getter;
            this.setter = setter;
        }

        /** Parse a raw command/file string into the right typed value, or throw. */
        public Object parse(String raw) {
            return switch (type) {
                case BOOL -> {
                    if (raw.equalsIgnoreCase("true"))  yield Boolean.TRUE;
                    if (raw.equalsIgnoreCase("false")) yield Boolean.FALSE;
                    throw new IllegalArgumentException("expected true or false");
                }
                case INT -> {
                    int v;
                    try { v = Integer.parseInt(raw.trim()); }
                    catch (NumberFormatException e) { throw new IllegalArgumentException("expected a whole number"); }
                    if (v < min || v > max) {
                        throw new IllegalArgumentException("must be between " + min + " and " + max);
                    }
                    yield v;
                }
                case TEXT -> raw;
            };
        }

        public String display(AlminConfig cfg) {
            return String.valueOf(getter.apply(cfg));
        }
    }

    private static Key intKey(String n, String d, int min, int max,
                              Function<AlminConfig, Object> g, BiConsumer<AlminConfig, Object> s) {
        return new Key(n, d, Type.INT, min, max, g, s);
    }
    private static Key boolKey(String n, String d,
                               Function<AlminConfig, Object> g, BiConsumer<AlminConfig, Object> s) {
        return new Key(n, d, Type.BOOL, 0, 0, g, s);
    }
    private static Key textKey(String n, String d,
                               Function<AlminConfig, Object> g, BiConsumer<AlminConfig, Object> s) {
        return new Key(n, d, Type.TEXT, 0, 0, g, s);
    }

    public static final List<Key> KEYS = List.of(
        textKey("update-repo", "GitHub owner/repo the mod checks for updates",
            c -> c.updateRepo, (c, v) -> c.updateRepo = (String) v),
        boolKey("update-check-on-boot", "Check GitHub for a newer mod version on server start",
            c -> c.updateCheckOnBoot, (c, v) -> c.updateCheckOnBoot = (Boolean) v),
        boolKey("auto-update", "On boot, automatically download, install and restart into a newer version",
            c -> c.autoUpdate, (c, v) -> c.autoUpdate = (Boolean) v),
        textKey("dir-writable-roots", "Comma-separated top-level folders the dir UI may upload/delete in",
            c -> c.dirWritableRoots, (c, v) -> c.dirWritableRoots = (String) v),
        intKey("spawn-immunity-seconds", "Damage-immunity seconds granted on (re)spawn (0 = off)", 0, 30,
            c -> c.spawnImmunitySeconds, (c, v) -> c.spawnImmunitySeconds = (Integer) v),
        boolKey("web-ui-enabled", "Serve the read-only web dashboard over HTTP",
            c -> c.webUiEnabled, (c, v) -> c.webUiEnabled = (Boolean) v),
        intKey("web-ui-port", "Port the web dashboard listens on (picked at first startup; 0 = pick a new one next start)", 0, 65535,
            c -> c.webUiPort, (c, v) -> c.webUiPort = (Integer) v),
        textKey("web-ui-bind", "Address the web dashboard binds to (127.0.0.1 = this machine only)",
            c -> c.webUiBind, (c, v) -> c.webUiBind = (String) v),
        textKey("web-ui-token", "Access token for the web dashboard — anyone with it can read everything",
            c -> c.webUiToken, (c, v) -> c.webUiToken = (String) v)
    );

    /** Parses {@link #dirWritableRoots} into a Set, ignoring empties/whitespace. */
    public Set<String> dirWritableRootsAsSet() {
        Set<String> out = new HashSet<>();
        if (dirWritableRoots == null) return out;
        for (String s : dirWritableRoots.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    public static Key keyByName(String name) {
        for (Key k : KEYS) {
            if (k.name.equalsIgnoreCase(name)) return k;
        }
        return null;
    }

    /** Live config. Never null — returns an all-defaults instance pre-load. */
    public static AlminConfig get() {
        AlminConfig i = instance;
        return i != null ? i : new AlminConfig();
    }

    /** Load (or create) the config file. Call once at server start. */
    public static synchronized void init(Path serverDir) {
        path = serverDir.resolve("config").resolve("almin").resolve("config.json");
        instance = readFromDisk();
        ensureFirstRunDefaults(instance);
        writeToDisk(instance); // rewrites the file so any newly added keys appear
    }

    /**
     * Fills in the settings that can't have a sensible fixed default: the web
     * dashboard's port and access token. Both are generated the first time the
     * server starts (or if they're cleared from the file) and then persisted,
     * so the port doesn't collide with a neighbour's guess and the token isn't
     * the same on every install of the mod.
     */
    private static void ensureFirstRunDefaults(AlminConfig cfg) {
        if (cfg.webUiPort <= 0) {
            // A quiet stretch of the registered range — high enough to avoid
            // the usual suspects, fixed once so bookmarks keep working.
            cfg.webUiPort = 8100 + new SecureRandom().nextInt(900);
        }
        if (cfg.webUiToken == null || cfg.webUiToken.isBlank()) {
            byte[] raw = new byte[16];
            new SecureRandom().nextBytes(raw);
            StringBuilder sb = new StringBuilder(raw.length * 2);
            for (byte b : raw) sb.append(String.format("%02x", b));
            cfg.webUiToken = sb.toString();
        }
    }

    /** Re-read the file from disk (for /almin config reload). Returns true on success. */
    public static synchronized boolean reload() {
        if (path == null) return false;
        instance = readFromDisk();
        MaskConfig.reload();
        return true;
    }

    /** Persist current values to disk. */
    public static synchronized void save() {
        if (instance != null) writeToDisk(instance);
    }

    private static AlminConfig readFromDisk() {
        AlminConfig cfg = new AlminConfig();
        if (path == null || !Files.exists(path)) return cfg;
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            for (Key k : KEYS) {
                if (!obj.has(k.name)) continue;
                try {
                    JsonElement el = obj.get(k.name);
                    Object v = switch (k.type) {
                        case BOOL -> el.getAsBoolean();
                        case INT  -> Math.max(k.min, Math.min(k.max, el.getAsInt()));
                        case TEXT -> el.getAsString();
                    };
                    k.setter.accept(cfg, v);
                } catch (Exception ignoredKey) {
                    // malformed value for this key — keep the default
                }
            }
        } catch (Exception e) {
            // unreadable file — fall back to all defaults
            AlminLog.warn("[almin] config.json unreadable ({}), using defaults", e.getMessage());
        }
        return cfg;
    }

    private static void writeToDisk(AlminConfig cfg) {
        if (path == null) return;
        try {
            Files.createDirectories(path.getParent());
            JsonObject obj = new JsonObject();
            for (Key k : KEYS) {
                Object v = k.getter.apply(cfg);
                switch (k.type) {
                    case BOOL -> obj.addProperty(k.name, (Boolean) v);
                    case INT  -> obj.addProperty(k.name, (Integer) v);
                    case TEXT -> obj.addProperty(k.name, (String) v);
                }
            }
            Path tmp = Files.createTempFile(path.getParent(), ".config-", ".tmp");
            Files.writeString(tmp, GSON.toJson(obj), StandardCharsets.UTF_8);
            Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            AlminLog.warn("[almin] failed to write config.json: {}", e.getMessage());
        }
    }

    public static List<String> keyNames() {
        List<String> names = new ArrayList<>(KEYS.size());
        for (Key k : KEYS) names.add(k.name);
        return names;
    }
}
