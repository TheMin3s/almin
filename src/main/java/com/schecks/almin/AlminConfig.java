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
    /**
     * Top-level folders that may be deleted recursively. Separate from writes:
     * allowing the live world to be removed must not also allow arbitrary
     * uploads, edits, or renames inside it.
     */
    public String dirDeletableRoots = "mods,config,resourcepacks,shared,world";
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
    /**
     * Start the server again from here when Almin stops it for a restart.
     *
     * <p>Off by default because hosted servers, containers, and NAS packages
     * usually already have a supervisor. If Almin and that supervisor both
     * relaunch, two JVMs race over the same world and can swamp the host while
     * one repeatedly loses Minecraft's session lock.
     *
     * <p>Turn it on only for a directly launched server with nothing outside
     * watching the process. Almin then reuses this JVM's command line.
     *
     * @see ServerRelaunch
     */
    public boolean webRestartRelaunch = false;
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
     * Record admins too — trusted UUIDs and anyone with moderator permission.
     *
     * <p>Off by default, and that default is the point rather than an
     * oversight: the log is read by the people it would otherwise be about, so
     * leaving them out is what stops it becoming a tool for watching
     * colleagues. Turn it on when you want a complete record — an audit, or a
     * server where the staff have agreed to it.
     *
     * <p>{@code /almin op activity admins temp on} does the same thing until
     * the next restart, for when the reason is a single afternoon.
     */
    public boolean activityIncludeAdmins = false;
    /**
     * How long an activity row is kept, in minutes. Five days by default —
     * long enough that "what happened over the weekend" is still answerable,
     * short enough that this stays a working record rather than an archive.
     * It is data about named people, so it expires rather than accumulating.
     */
    public int activityRetentionMinutes = 7200;
    /** Ceiling on the log, oldest dropped first, so a busy server can't grow it forever. */
    public int activityMaxEntries = 120000;
    /**
     * Include block edits. On by default, folded into counted rows; turn it off
     * if you only care about chat, commands, containers and deaths.
     */
    public boolean activityBlocks = true;
    /** Include combat: damage taken, hits landed, deaths. */
    public boolean activityCombat = true;
    /** Include item use, entity interaction, containers, crafting and trades. */
    public boolean activityItems = true;
    /**
     * Include the milestones: advancements, sleeping, enchanting, and going
     * through a portal.
     *
     * <p>Cheap — nobody earns an advancement four hundred times a minute — and
     * out of proportion to its cost when reading a session back. "Entered the
     * Nether, 19:12" explains the two hours of tunnelling under it far better
     * than the tunnelling explains itself.
     */
    public boolean activityProgress = true;
    /**
     * Seconds between position samples for the movement map. 0 turns the map
     * off. A player standing still is never sampled twice.
     */
    public int activityTrackSeconds = 5;
    /**
     * Seconds of not moving before a player counts as away. 0 turns it off.
     *
     * <p>Standing still is the only signal a server can be sure of: a client
     * that has stopped sending movement is indistinguishable from a player who
     * has stopped moving, which is exactly what AFK means.
     */
    public int activityAfkSeconds = 20;
    /**
     * Seconds between pictures of the ground for the activity map. 0 turns
     * them off, leaving the map a grid with paths on it.
     *
     * <p>Each one is a real cost: sampling has to happen on the server thread,
     * because block states belong to it. The defaults are chosen so that cost
     * lands roughly once every half minute and is bounded by
     * {@link #mapRadius} and {@link #mapBlocksPerPixel}.
     */
    public int mapSnapshotSeconds = 30;
    /**
     * How many days of ground pictures to keep, thinned with age. 0 follows
     * the activity log instead.
     *
     * <p>Longer than the log on purpose, and defensible: a thinned month-old
     * snapshot is a picture of the world — what was built, what was cleared —
     * rather than a record of who was standing in it. The paths and the rows
     * that say who did it still expire on the log's clock.
     */
    public int mapSnapshotDays = 30;
    /**
     * Thin old pictures instead of deleting them outright.
     *
     * <p>Keeping every picture for a month is impossible and keeping none of
     * them past a day loses the thing the map is for. So the further back a
     * picture is, the fewer of its neighbours are kept: everything for the
     * last half hour, one a minute for the last two, one every four hours by
     * the time it is a month old. What survives is a record that gets coarser
     * with age rather than one that stops.
     */
    public boolean mapSnapshotThin = true;
    /**
     * How many pictures are kept before the oldest are deleted.
     *
     * <p>A hard ceiling rather than the usual rule: {@link #mapSnapshotThin}
     * decides what is worth keeping and this only stops a pathological case
     * from filling a disk. Roomy, because most pictures are stored as the
     * difference from another and cost a few kilobytes.
     */
    public int mapSnapshotKeep = 1500;
    /**
     * Blocks per pixel. 1 is a pixel per block, and the default: the map is
     * something people zoom into, and a picture that goes soft the moment you
     * do is not worth the saving. 2 is four times cheaper if a server needs
     * it back.
     */
    public int mapBlocksPerPixel = 1;
    /** Blocks either side of the players the picture covers. */
    public int mapRadius = 192;
    /**
     * Let the Almin client mod report what it is running.
     *
     * <p>On by default, because it is the reason to install a client mod on an
     * administered server: it is what turns "it crashed" into a mod list and a
     * Java version. It is also the one thing Almin collects about a player's
     * computer rather than about their play, so it is a switch and it is
     * documented as one.
     *
     * <p>Self-reported, always. A modified client can put anything it likes in
     * that packet, so this is a support tool and a house rule, never proof.
     */
    public boolean clientReport = true;
    /** How long a removed mod stays listed as recently removed. */
    public int clientModHistoryDays = 7;
    /**
     * Mod ids players are asked not to run, comma-separated.
     *
     * <p>Only meaningful alongside {@link #requireClientMod}: without the
     * client mod there is no mod list to check, so the restriction would apply
     * to whoever was honest enough to be visible. The panel hides the whole
     * section until the requirement is on, unless
     * {@link #modsShowRestricted} says otherwise.
     */
    public String modsRestricted = "";
    /** Show the restricted-mods section even without require-client-mod. */
    public boolean modsShowRestricted = false;
    /** Disconnect a player found running a restricted mod, rather than only logging it. */
    public boolean modsRestrictedKick = false;
    /**
     * Read the activity log with a language model, so the map comes with a
     * paragraph saying what happened rather than four thousand rows.
     *
     * <p>Off by default and deliberately so: turning it on sends what players
     * did — names, places, and optionally what they said — to whichever
     * service {@link #aiProvider} names. That is a decision about other
     * people's data, so it is one an admin has to make on purpose.
     *
     * <p>The pattern-finding underneath it ({@link Episodes}) is local, always
     * runs, and needs none of this. The model writes prose over the top of it.
     */
    public boolean aiEnabled = false;
    /**
     * Which service: {@code anthropic}, {@code openai}, or {@code local} for
     * anything speaking the OpenAI chat API on an address you give — Ollama,
     * llama.cpp's server, LM Studio. {@code local} is how you run a small
     * model on the same machine and have nothing leave it.
     */
    public String aiProvider = "local";
    /** Model name, as that service spells it. */
    public String aiModel = "qwen2.5:3b";
    /** Base URL for {@code local} (and for an OpenAI-compatible gateway). */
    public String aiBaseUrl = "http://127.0.0.1:11434/v1";
    /**
     * Include what people said in what is sent to the model.
     *
     * <p>Separate from the rest because it is different in kind: coordinates
     * are a record of a game, and chat is a record of a conversation.
     */
    public boolean aiSendChat = true;
    /**
     * Attach a small locally-rendered diagram of block-edit geometry when the
     * selected model accepts image input. The transport retries with text only
     * when an OpenAI-compatible or local model explicitly rejects vision.
     */
    public boolean aiSendSceneImages = true;
    /**
     * Minutes between unattended summaries. 0 = only when someone asks.
     *
     * <p>Half an hour by default, which only ever runs once {@link #aiEnabled}
     * is on — and that is the switch somebody has to reach for deliberately.
     * A summary you have to ask for is one nobody reads: the value of the
     * thing is walking up to the panel and finding out what happened.
     */
    public int aiAutoMinutes = 30;

    /**
     * How long to wait for the model, in seconds.
     *
     * <p>Deliberately under a minute. A reverse proxy in front of the panel
     * has its own limit — 60 seconds is the usual default — and whichever
     * limit is shorter is the one that decides what the browser sees. When
     * Almin waited longer than the proxy, the proxy answered 504 with no
     * message and Almin's account of what went wrong never arrived. Raise it
     * if the model is genuinely slow, and raise the proxy's to match.
     */
    public int aiTimeoutSeconds = 45;
    /**
     * Show player faces in the panel's player and activity lists.
     *
     * <p>On by default. A face for someone who is connected costs nothing —
     * the skin is already in their profile. A face for someone who has left,
     * or anyone at all on an offline-mode server, means asking Mojang, so
     * this is also the switch for "this server does not talk to Mojang".
     */
    public boolean webPlayerHeads = true;

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
        textKey("dir-writable-roots", "Comma-separated top-level folders the dir UI may upload, edit and rename in",
            c -> c.dirWritableRoots, (c, v) -> c.dirWritableRoots = (String) v),
        textKey("dir-deletable-roots", "Comma-separated top-level folders the dir UI may delete recursively (world is included by default)",
            c -> c.dirDeletableRoots, (c, v) -> c.dirDeletableRoots = (String) v),
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
        boolKey("web-supervisor", "Keep the panel up while the server is stopped, so it can be started from the browser",
            c -> c.webSupervisor, (c, v) -> c.webSupervisor = (Boolean) v),
        textKey("web-start-command", "Command used to start the server again (blank = re-run this server's own command line)",
            c -> c.webStartCommand, (c, v) -> c.webStartCommand = (String) v),
        boolKey("web-restart-relaunch", "Start the server again from here after an Almin restart or update (only enable when no wrapper, container or service restarts it)",
            c -> c.webRestartRelaunch, (c, v) -> c.webRestartRelaunch = (Boolean) v),
        boolKey("mods-advertise", "Offer the mods listed in mods.json to joining players",
            c -> c.modsAdvertise, (c, v) -> c.modsAdvertise = (Boolean) v),
        boolKey("mods-deny-kicks", "Disconnect players who decline when a required mod is offered",
            c -> c.modsDenyKicks, (c, v) -> c.modsDenyKicks = (Boolean) v),
        boolKey("require-client-mod", "Require the Almin client mod to play (vanilla clients are disconnected)",
            c -> c.requireClientMod, (c, v) -> c.requireClientMod = (Boolean) v),
        boolKey("activity-log", "Record what ordinary players do (never ops or trusted UUIDs)",
            c -> c.activityLog, (c, v) -> c.activityLog = (Boolean) v),
        boolKey("activity-include-admins", "Record admins and trusted UUIDs as well as ordinary players",
            c -> c.activityIncludeAdmins, (c, v) -> c.activityIncludeAdmins = (Boolean) v),
        intKey("activity-retention-minutes", "How long an activity row is kept before it is deleted (5 days by default)", 5, 43200,
            c -> c.activityRetentionMinutes, (c, v) -> c.activityRetentionMinutes = (Integer) v),
        intKey("activity-max-entries", "Ceiling on the activity log; oldest rows drop first", 500, 400000,
            c -> c.activityMaxEntries, (c, v) -> c.activityMaxEntries = (Integer) v),
        boolKey("activity-blocks", "Include block breaks and uses in the activity log",
            c -> c.activityBlocks, (c, v) -> c.activityBlocks = (Boolean) v),
        boolKey("activity-combat", "Include damage, hits and deaths in the activity log",
            c -> c.activityCombat, (c, v) -> c.activityCombat = (Boolean) v),
        boolKey("activity-items", "Include item use, entity interaction, containers, crafting and trades",
            c -> c.activityItems, (c, v) -> c.activityItems = (Boolean) v),
        boolKey("activity-progress", "Include advancements, sleeping, enchanting and portal trips",
            c -> c.activityProgress, (c, v) -> c.activityProgress = (Boolean) v),
        intKey("activity-track-seconds", "Seconds between position samples for the map (0 = no map)", 0, 300,
            c -> c.activityTrackSeconds, (c, v) -> c.activityTrackSeconds = (Integer) v),
        intKey("activity-afk-seconds", "Seconds of not moving before a player counts as away (0 = never)", 0, 600,
            c -> c.activityAfkSeconds, (c, v) -> c.activityAfkSeconds = (Integer) v),
        intKey("map-snapshot-seconds", "Seconds between pictures of the ground for the map (0 = no world under it)", 0, 600,
            c -> c.mapSnapshotSeconds, (c, v) -> c.mapSnapshotSeconds = (Integer) v),
        intKey("map-snapshot-keep", "Hard ceiling on how many pictures of the ground are kept", 2, 4000,
            c -> c.mapSnapshotKeep, (c, v) -> c.mapSnapshotKeep = (Integer) v),
        intKey("map-snapshot-days", "How many days of ground pictures to keep (0 = the activity log's window)", 0, 365,
            c -> c.mapSnapshotDays, (c, v) -> c.mapSnapshotDays = (Integer) v),
        boolKey("map-snapshot-thin", "Keep fewer pictures the older they get, rather than deleting them outright",
            c -> c.mapSnapshotThin, (c, v) -> c.mapSnapshotThin = (Boolean) v),
        intKey("map-blocks-per-pixel", "Detail of those pictures; 1 is a pixel per block and costs the most", 1, 8,
            c -> c.mapBlocksPerPixel, (c, v) -> c.mapBlocksPerPixel = (Integer) v),
        intKey("map-radius", "Blocks either side of the players each picture covers", 32, 512,
            c -> c.mapRadius, (c, v) -> c.mapRadius = (Integer) v),
        boolKey("web-player-heads", "Show player faces in the panel (looks skins up from Mojang for players who are not online)",
            c -> c.webPlayerHeads, (c, v) -> c.webPlayerHeads = (Boolean) v),
        boolKey("client-report", "Let the Almin client mod report its mod list and machine details",
            c -> c.clientReport, (c, v) -> c.clientReport = (Boolean) v),
        intKey("client-mod-history-days", "How long a removed client mod stays listed as recently removed", 1, 90,
            c -> c.clientModHistoryDays, (c, v) -> c.clientModHistoryDays = (Integer) v),
        textKey("mods-restricted", "Comma-separated mod ids players are asked not to run",
            c -> c.modsRestricted, (c, v) -> c.modsRestricted = (String) v),
        boolKey("mods-show-restricted", "Show the restricted-mods section even without require-client-mod",
            c -> c.modsShowRestricted, (c, v) -> c.modsShowRestricted = (Boolean) v),
        boolKey("mods-restricted-kick", "Disconnect a player found running a restricted mod",
            c -> c.modsRestrictedKick, (c, v) -> c.modsRestrictedKick = (Boolean) v),
        boolKey("ai-enabled", "Let a language model summarise the activity log (sends player activity to the chosen service)",
            c -> c.aiEnabled, (c, v) -> c.aiEnabled = (Boolean) v),
        textKey("ai-provider", "anthropic, openai, google, local, or custom (any OpenAI-compatible address in ai-base-url)",
            c -> c.aiProvider, (c, v) -> c.aiProvider = (String) v),
        textKey("ai-model", "Model name as that service spells it",
            c -> c.aiModel, (c, v) -> c.aiModel = (String) v),
        textKey("ai-base-url", "Base URL for the local/compatible provider, e.g. http://127.0.0.1:11434/v1",
            c -> c.aiBaseUrl, (c, v) -> c.aiBaseUrl = (String) v),
        boolKey("ai-send-chat", "Include what players said in what is sent to the model",
            c -> c.aiSendChat, (c, v) -> c.aiSendChat = (Boolean) v),
        boolKey("ai-send-scene-images", "Send a compact block-layout diagram when the model supports image input",
            c -> c.aiSendSceneImages, (c, v) -> c.aiSendSceneImages = (Boolean) v),
        intKey("ai-auto-minutes", "Minutes between unattended summaries (0 = only when asked)", 0, 1440,
            c -> c.aiAutoMinutes, (c, v) -> c.aiAutoMinutes = (Integer) v),
        intKey("ai-timeout-seconds", "How long to wait for the model (keep it under any reverse proxy's own timeout)", 5, 600,
            c -> c.aiTimeoutSeconds, (c, v) -> c.aiTimeoutSeconds = (Integer) v)
    );

    /**
     * Bumped when a default changes in a way that should reach servers that
     * are already running. Stored so the change happens once rather than every
     * time the file is read, and so a value someone chose on purpose is only
     * ever overwritten if it is still sitting on the old default.
     */
    private static final int CONFIG_VERSION = 3;
    /** Version of the defaults this file was last written against. */
    public int configVersion = 0;

    /** Parses {@link #dirWritableRoots} into a Set, ignoring empties/whitespace. */
    public Set<String> dirWritableRootsAsSet() {
        return rootSet(dirWritableRoots);
    }

    /** Parses {@link #dirDeletableRoots}; {@code world} is in the default. */
    public Set<String> dirDeletableRootsAsSet() {
        return rootSet(dirDeletableRoots);
    }

    private static Set<String> rootSet(String roots) {
        Set<String> out = new HashSet<>();
        if (roots == null) return out;
        for (String s : roots.split(",")) {
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
        migrate(cfg);
    }

    /**
     * Carries a changed default onto a server that already has a config file.
     *
     * <p>Every key is written to disk on first run, so a new default would
     * otherwise only ever reach a brand-new install — the setting on every
     * existing server is already pinned to whatever the default was the day it
     * started. A value is only moved if it is still exactly the old default,
     * so anything anyone chose deliberately is left alone.
     */
    private static void migrate(AlminConfig cfg) {
        if (cfg.configVersion >= CONFIG_VERSION) {
            cfg.configVersion = CONFIG_VERSION;
            return;
        }
        // v1: the activity log now keeps five days rather than one.
        if (cfg.configVersion < 1 && cfg.activityRetentionMinutes == 1440) {
            cfg.activityRetentionMinutes = 7200;
        }
        // v2: block edits and fights are no longer folded into counted rows,
        // so the same afternoon is many more rows than it used to be. A
        // ceiling chosen for the folded log would now throw away most of it.
        if (cfg.configVersion < 2 && cfg.activityMaxEntries == 20000) {
            cfg.activityMaxEntries = 120000;
        }
        // v3: self-relaunch used to be on by default. That is unsafe on the
        // hosted/container/NAS setups most servers actually use: their own
        // supervisor starts a second copy while Almin starts one too. Migrate
        // the old default off once; anyone can explicitly turn it back on
        // afterwards for a directly launched, unsupervised server.
        if (cfg.configVersion < 3 && cfg.webRestartRelaunch) {
            cfg.webRestartRelaunch = false;
        }
        cfg.configVersion = CONFIG_VERSION;
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
            // Not a setting, so not in KEYS: it records which set of defaults
            // this file was written against. Absent means "before there were
            // versions", which is what {@link #migrate} expects.
            if (obj.has("config-version")) {
                try { cfg.configVersion = obj.get("config-version").getAsInt(); }
                catch (Exception ignoredVersion) { /* treat as unversioned */ }
            }
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
            obj.addProperty("config-version", cfg.configVersion);
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
