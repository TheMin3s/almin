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
    public String updateRepo = "TheMin3s/almin";
    public boolean updateCheckOnBoot = true;
    public boolean autoUpdate = true;
    public String dirWritableRoots = "mods,config,resourcepacks,shared";
    public int spawnImmunitySeconds = 3;
    public boolean webUiEnabled = true;
    /** 0 until the first startup picks one; see {@link #ensureFirstRunDefaults}. */
    public int webUiPort = 0;
    /**
     * Address the panel listens on. All interfaces by default so it simply
     * works; set it to 127.0.0.1 if you are putting a TLS proxy in front.
     */
    public String webUiBind = "0.0.0.0";
    /** Serve the unauthenticated basic-metrics view to anyone who can reach it. */
    public boolean webPublicMetrics = true;
    /** PBKDF2 hash of the admin password. Empty = login disabled (no full access). */
    public String webAdminPasswordHash = "";
    /** How long a web login stays valid. */
    public int webSessionMinutes = 120;
    /**
     * Only allow admin login over a connection Almin can tell is protected —
     * loopback, or a proxy reporting HTTPS. Off by default, because switching
     * it on without a proxy in front makes the panel impossible to log into
     * from another machine. Turn it on once TLS is actually in place.
     */
    public boolean webRequireSecure = false;
    /**
     * Keep the web panel (and this JVM) alive after the Minecraft server stops,
     * so the panel can start it again.
     *
     * <p>Off by default because it changes what happens at shutdown. With it
     * off, stopping the server lets the JVM exit — which is what an external
     * wrapper watches for in order to restart it, and how {@code /almin op
     * restart} has always worked. Turning it on makes this JVM outlive the
     * server, so that wrapper would never see the exit.
     */
    public boolean webSupervisor = false;
    /** Command the panel's Start button runs. Required for supervisor mode. */
    public String webStartCommand = "";
    /** Offer the mods in mods.json to joining players. */
    public boolean modsAdvertise = true;
    /**
     * Disconnect a player who declines when at least one offered mod is marked
     * required. Self-reported by the client, so this is a house rule rather
     * than an enforcement mechanism — see {@link ModResponsePayload}.
     */
    public boolean modsDenyKicks = false;
    /**
     * Require the Almin client mod to play. Vanilla clients are disconnected at
     * join. Without this, a player can simply not install Almin and never be
     * shown the mod offer at all.
     */
    public boolean requireClientMod = false;
    /** Record what ordinary players do. Never records ops or trusted UUIDs. */
    public boolean activityLog = true;
    /**
     * How long an activity row is kept, in minutes. A day by default — this is
     * data about named people, so it expires rather than accumulating.
     */
    public int activityRetentionMinutes = 1440;
    /** Ceiling on the log, oldest dropped first, so a busy server can't grow it forever. */
    public int activityMaxEntries = 20000;
    /**
     * Include block edits. On by default, folded into counted rows; turn it off
     * if you only care about chat, commands, containers and deaths.
     */
    public boolean activityBlocks = true;
    /** Include combat: damage taken, hits landed, deaths. */
    public boolean activityCombat = true;
    /** Include item use, entity interaction and containers. */
    public boolean activityItems = true;
    /**
     * Seconds between position samples for the movement map. 0 turns the map
     * off. A player standing still is never sampled twice.
     */
    public int activityTrackSeconds = 5;

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
        textKey("web-ui-bind", "Address the panel binds to (0.0.0.0 = reachable; 127.0.0.1 = this machine only)",
            c -> c.webUiBind, (c, v) -> c.webUiBind = (String) v),
        boolKey("web-require-secure", "Refuse admin login unless the connection is loopback or HTTPS via a proxy (needs TLS set up first)",
            c -> c.webRequireSecure, (c, v) -> c.webRequireSecure = (Boolean) v),
        boolKey("web-public-metrics", "Serve the unauthenticated basic-metrics view (login is always required for the rest)",
            c -> c.webPublicMetrics, (c, v) -> c.webPublicMetrics = (Boolean) v),
        // Not settable to a raw value here — /almin op web password hashes it.
        // Listed so it shows in /almin config and can be cleared to "".
        textKey("web-admin-password-hash", "PBKDF2 hash of the web admin password (set via /almin op web password <pw>)",
            c -> c.webAdminPasswordHash, (c, v) -> c.webAdminPasswordHash = (String) v),
        intKey("web-session-minutes", "How long a web login stays valid, in minutes", 5, 10080,
            c -> c.webSessionMinutes, (c, v) -> c.webSessionMinutes = (Integer) v),
        boolKey("web-supervisor", "Keep the panel alive after the server stops so it can start it again (read the README first)",
            c -> c.webSupervisor, (c, v) -> c.webSupervisor = (Boolean) v),
        textKey("web-start-command", "Command the Start button runs in supervisor mode, e.g. ./start.sh",
            c -> c.webStartCommand, (c, v) -> c.webStartCommand = (String) v),
        boolKey("mods-advertise", "Offer the mods listed in mods.json to joining players",
            c -> c.modsAdvertise, (c, v) -> c.modsAdvertise = (Boolean) v),
        boolKey("mods-deny-kicks", "Disconnect players who decline when a required mod is offered",
            c -> c.modsDenyKicks, (c, v) -> c.modsDenyKicks = (Boolean) v),
        boolKey("require-client-mod", "Require the Almin client mod to play (vanilla clients are disconnected)",
            c -> c.requireClientMod, (c, v) -> c.requireClientMod = (Boolean) v),
        boolKey("activity-log", "Record what ordinary players do (never ops or trusted UUIDs)",
            c -> c.activityLog, (c, v) -> c.activityLog = (Boolean) v),
        intKey("activity-retention-minutes", "How long an activity row is kept before it is deleted", 5, 10080,
            c -> c.activityRetentionMinutes, (c, v) -> c.activityRetentionMinutes = (Integer) v),
        intKey("activity-max-entries", "Ceiling on the activity log; oldest rows drop first", 500, 50000,
            c -> c.activityMaxEntries, (c, v) -> c.activityMaxEntries = (Integer) v),
        boolKey("activity-blocks", "Include block breaks and uses in the activity log",
            c -> c.activityBlocks, (c, v) -> c.activityBlocks = (Boolean) v),
        boolKey("activity-combat", "Include damage, hits and deaths in the activity log",
            c -> c.activityCombat, (c, v) -> c.activityCombat = (Boolean) v),
        boolKey("activity-items", "Include item use, entity interaction and containers",
            c -> c.activityItems, (c, v) -> c.activityItems = (Boolean) v),
        intKey("activity-track-seconds", "Seconds between position samples for the map (0 = no map)", 0, 300,
            c -> c.activityTrackSeconds, (c, v) -> c.activityTrackSeconds = (Integer) v)
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
     * Picks the web dashboard's port the first time the server starts (or if it
     * was cleared to 0) and persists it, so it doesn't collide with a
     * neighbour's guess and bookmarks stay valid. The admin password is not
     * generated — there's deliberately no default login; an admin sets one with
     * {@code /almin op web password <pw>} before full access is possible.
     */
    private static void ensureFirstRunDefaults(AlminConfig cfg) {
        if (cfg.webUiPort <= 0) {
            // A quiet stretch of the registered range — high enough to avoid
            // the usual suspects, fixed once so bookmarks keep working.
            cfg.webUiPort = 8100 + new SecureRandom().nextInt(900);
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
