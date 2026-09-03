package com.schecks.almin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * People who may sign in to the panel, and what each of them may reach.
 *
 * <h3>The owner is not in here</h3>
 * There is exactly one account this class does not store: the owner's. That
 * one is the existing {@code web-admin-password} in the config, and it keeps
 * working exactly as it did — a panel that has never created a second account
 * behaves the way it always has, and an upgrade cannot lock anybody out of
 * their own server.
 *
 * <p>The owner is {@link #owner()}, synthesised on demand, always holding
 * {@link #WRITE} on every menu. Nothing here can lower that, delete it, or
 * list it to somebody else. That is the point: the accounts below are made by
 * the owner and can be taken away by the owner, and an account that could
 * demote the owner would be an account that could take the server.
 *
 * <h3>Where it is kept</h3>
 * {@code config/almin/accounts.json}, beside the AI key and for the same
 * reason — it holds password hashes, so {@link WebFiles#secret} refuses to
 * open it in the file browser. Hashes are PBKDF2 through {@link Passwords},
 * the same as the owner's.
 *
 * <h3>Access levels</h3>
 * Every menu is {@link #NONE}, {@link #READ} or {@link #WRITE} for every
 * account. Absent means none: a new account can see nothing until somebody
 * says otherwise, which is the only default that fails safe.
 */
public final class Accounts {

    /** Cannot open the menu at all; it is not drawn. */
    public static final String NONE = "none";
    /** May look; every control that changes something is refused. */
    public static final String READ = "read";
    /** May look and may change. */
    public static final String WRITE = "write";

    /**
     * The menus an account is granted separately.
     *
     * <p>These are the panel's own tab names, so that "what you may reach" and
     * "what you can see" cannot drift apart into two different vocabularies.
     */
    public static final List<String> MENUS =
        List.of("dash", "term", "activity", "files", "players", "mods", "settings");

    /** What each menu is called where a person reads it. */
    public static String menuName(String menu) {
        return switch (menu) {
            case "dash" -> "Dashboard";
            case "term" -> "Console";
            case "activity" -> "Activity";
            case "files" -> "Files";
            case "players" -> "Players";
            case "mods" -> "Mods";
            case "settings" -> "Settings";
            default -> menu;
        };
    }

    /**
     * One person who may sign in.
     *
     * @param id          stable random handle; a rename must not orphan grants
     * @param username    what they type to sign in, unique case-insensitively
     * @param hash        PBKDF2 from {@link Passwords}, never the password
     * @param mcName      the Minecraft account they are, or "" if not linked
     * @param mcUuid      that account's UUID, or ""
     * @param access      menu to {@link #NONE}/{@link #READ}/{@link #WRITE}
     * @param auditActivity whether their use of the Activity menu is recorded
     * @param startCommand  whether they may change what a restart runs, which
     *                      is a command on the host OS and so is granted on its
     *                      own rather than with the rest of Settings
     * @param hideCoords    whether the Activity menu keeps the numbers to
     *                      itself for them: they see what happened and who,
     *                      and not where on the map it is
     * @param owner       true only for the synthesised owner, never on disk
     */
    public record Account(String id, String username, String hash, String mcName, String mcUuid,
                          Map<String, String> access, Map<String, String> folders,
                          boolean auditActivity, boolean startCommand, boolean hideCoords,
                          int rank, long created, long lastLogin, boolean owner) {

        /**
         * Whether coordinates are kept from this account.
         *
         * <p>Somebody moderating chat, or reading who logged in when, does not
         * need everyone's base locations to do it, and the Activity menu hands
         * those over by the thousand. The map still draws — the shape of a
         * night's walking is the thing that answers questions — it simply
         * stops being a list of grid references anybody can copy out.
         *
         * <p>Never the owner: this narrows what a delegate is shown, and an
         * owner who wanted less could simply not look.
         */
        public boolean coordsHidden() { return !owner && hideCoords; }

        /**
         * Whether this account may set {@code web-start-command}.
         *
         * <p>Its own permission, and not part of Settings, because everything
         * else in Settings is a value Almin reads: this one is a line handed
         * to {@code /bin/sh} on the machine the server runs on. An account
         * that holds it can run anything the server user can, which is a
         * different question from whether they may change the AFK timeout.
         */
        public boolean canStartCommand() { return owner || startCommand; }

        /** What this account may do with one menu. The owner may do everything. */
        public String level(String menu) {
            if (owner) return WRITE;
            String v = access == null ? null : access.get(menu);
            return v == null ? NONE : v;
        }

        public boolean canRead(String menu) { return !level(menu).equals(NONE); }
        public boolean canWrite(String menu) { return level(menu).equals(WRITE); }

        /**
         * What this account may do with one top-level folder.
         *
         * <p>An empty map means the folder list was never narrowed, and the
         * Files menu behaves as it always did — everything the panel shows,
         * writable wherever {@code dir-writable-roots} allows. The moment one
         * folder is named, the list is the whole of what they may reach and
         * anything unnamed is invisible: a narrowing that left the rest open
         * would be a narrowing that did nothing.
         */
        public String folderLevel(String folder) {
            if (owner) return WRITE;
            if (folders == null || folders.isEmpty()) return WRITE;
            String v = folders.get(folder);
            return v == null ? NONE : v;
        }

        /** The top-level folder a panel-relative path is in, or "" for the root. */
        public static String topOf(String rel) {
            if (rel == null) return "";
            String r = rel.replace('\\', '/');
            while (r.startsWith("/")) r = r.substring(1);
            if (r.isEmpty()) return "";
            int slash = r.indexOf('/');
            return slash < 0 ? r : r.substring(0, slash);
        }

        /** Whether this path is inside something they may look at. */
        public boolean canSeePath(String rel) {
            String top = topOf(rel);
            // The root itself is always listable; what is in it is filtered.
            if (top.isEmpty()) return true;
            return !folderLevel(top).equals(NONE);
        }

        /** Whether this path is inside something they may change. */
        public boolean canWritePath(String rel) {
            String top = topOf(rel);
            if (top.isEmpty()) return false;
            return folderLevel(top).equals(WRITE);
        }

        /** Whether the folder list has been narrowed at all. */
        public boolean folderLimited() {
            return !owner && folders != null && !folders.isEmpty();
        }

        /** Where this account sits. 0 is the owner; larger is further down. */
        public int level() { return owner ? OWNER_RANK : Math.max(FIRST_RANK, rank); }

        /**
         * Whether this account may act on {@code other}.
         *
         * <p>Strictly above, never equal. Two accounts at the same rank are
         * peers and neither may touch the other — which is also what stops an
         * account acting on itself through this route, since its own rank is
         * never strictly greater than its own.
         */
        public boolean outranks(Account other) {
            return other != null && !other.owner() && level() < other.level();
        }
    }

    /** What one attempt to change the list did. */
    public record Result(boolean ok, String message, Account account) {
        public static Result fail(String why) { return new Result(false, why, null); }
        public static Result done(String what) { return new Result(true, what, null); }
        public static Result done(String what, Account a) { return new Result(true, what, a); }
    }

    /** The filename, so {@link WebFiles#secret} can refuse it by name. */
    public static String fileName() { return "accounts.json"; }

    /** The owner's rank. Nothing else may hold it. */
    public static final int OWNER_RANK = 0;
    /** The best rank an ordinary account can be given. */
    public static final int FIRST_RANK = 1;
    /** The worst, and the floor a new account lands on when its maker is deep. */
    public static final int LAST_RANK = 999;

    private static final SecureRandom RNG = new SecureRandom();
    private static final int MAX_ACCOUNTS = 64;
    private static final int MIN_PASSWORD = 8;

    private static volatile Path file;
    /** Username in lower case to account. Ordered, so the panel lists them stably. */
    private static final Map<String, Account> byName = new LinkedHashMap<>();

    private Accounts() {}

    // ---------- lifecycle ----------

    /** Points at {@code config/almin/} and reads whatever is there. */
    public static synchronized void init(Path serverDir) {
        file = serverDir.resolve("config").resolve("almin").resolve(fileName());
        load();
    }

    private static void load() {
        byName.clear();
        Path f = file;
        if (f == null || !Files.isRegularFile(f)) return;
        try {
            JsonElement root = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) return;
            JsonArray list = root.getAsJsonObject().getAsJsonArray("accounts");
            if (list == null) return;
            for (JsonElement e : list) {
                if (!e.isJsonObject()) continue;
                Account a = read(e.getAsJsonObject());
                if (a != null) byName.put(a.username().toLowerCase(Locale.ROOT), a);
            }
            AlminLog.info("[almin] {} panel account(s) loaded", byName.size());
        } catch (Exception e) {
            // A file we cannot read is not a reason to refuse every login; the
            // owner's password does not live here and still works.
            AlminLog.warn("[almin] could not read {}: {}", fileName(), e.getMessage());
        }
    }

    private static Account read(JsonObject o) {
        String username = str(o, "username");
        if (username.isBlank()) return null;
        Map<String, String> access = new LinkedHashMap<>();
        if (o.has("access") && o.get("access").isJsonObject()) {
            JsonObject g = o.getAsJsonObject("access");
            for (String menu : MENUS) {
                String v = str(g, menu);
                if (v.equals(READ) || v.equals(WRITE)) access.put(menu, v);
            }
        }
        Map<String, String> folders = new LinkedHashMap<>();
        if (o.has("folders") && o.get("folders").isJsonObject()) {
            JsonObject g = o.getAsJsonObject("folders");
            for (String key : g.keySet()) {
                String v = str(g, key);
                if (v.equals(READ) || v.equals(WRITE)) folders.put(key, v);
            }
        }
        return new Account(
            str(o, "id").isBlank() ? newId() : str(o, "id"),
            username, str(o, "hash"), str(o, "mcName"), str(o, "mcUuid"),
            access, folders, o.has("auditActivity") && o.get("auditActivity").getAsBoolean(),
            o.has("startCommand") && o.get("startCommand").getAsBoolean(),
            o.has("hideCoords") && o.get("hideCoords").getAsBoolean(),
            rankOf((int) num(o, "rank")),
            num(o, "created"), num(o, "lastLogin"), false);
    }

    private static synchronized void save() {
        Path f = file;
        if (f == null) return;
        try {
            Files.createDirectories(f.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            JsonArray list = new JsonArray();
            for (Account a : byName.values()) list.add(write(a));
            root.add("accounts", list);
            Files.writeString(f, root.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            AlminLog.warn("[almin] could not write {}: {}", fileName(), e.getMessage());
        }
    }

    private static JsonObject write(Account a) {
        JsonObject o = new JsonObject();
        o.addProperty("id", a.id());
        o.addProperty("username", a.username());
        o.addProperty("hash", a.hash());
        o.addProperty("mcName", a.mcName());
        o.addProperty("mcUuid", a.mcUuid());
        o.addProperty("auditActivity", a.auditActivity());
        o.addProperty("startCommand", a.startCommand());
        o.addProperty("hideCoords", a.hideCoords());
        o.addProperty("rank", a.rank());
        o.addProperty("created", a.created());
        o.addProperty("lastLogin", a.lastLogin());
        JsonObject g = new JsonObject();
        for (Map.Entry<String, String> e : a.access().entrySet()) g.addProperty(e.getKey(), e.getValue());
        o.add("access", g);
        JsonObject f = new JsonObject();
        for (Map.Entry<String, String> e : a.folders().entrySet()) f.addProperty(e.getKey(), e.getValue());
        o.add("folders", f);
        return o;
    }

    // ---------- reading ----------

    /**
     * The owner, who is the {@code web-admin-password} and holds everything.
     *
     * <p>Synthesised rather than stored, so there is no row anybody could edit
     * and no file whose loss would take the server's only full account with
     * it.
     */
    public static Account owner() {
        AlminConfig cfg = AlminConfig.get();
        String name = cfg.webAdminUsername == null || cfg.webAdminUsername.isBlank()
            ? "admin" : cfg.webAdminUsername.trim();
        return new Account("owner", name, cfg.webAdminPasswordHash == null ? "" : cfg.webAdminPasswordHash,
            "", "", Map.of(), Map.of(), false, true, false, OWNER_RANK, 0, 0, true);
    }

    /** Every account except the owner, in the order they were made. */
    public static synchronized List<Account> all() {
        return new ArrayList<>(byName.values());
    }

    public static synchronized Account byId(String id) {
        if (id == null) return null;
        if (id.equals("owner")) return owner();
        for (Account a : byName.values()) if (a.id().equals(id)) return a;
        return null;
    }

    /** Looks a name up for login. The owner's name wins if they collide. */
    public static synchronized Account byUsername(String username) {
        if (username == null) return null;
        String key = username.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) return null;
        Account own = owner();
        if (own.username().toLowerCase(Locale.ROOT).equals(key)) return own;
        return byName.get(key);
    }

    /** Whether anybody besides the owner exists, which is what turns the UI on. */
    public static synchronized boolean any() { return !byName.isEmpty(); }

    // ---------- changing ----------

    /**
     * Adds an account with no access to anything.
     *
     * <p>A new account being able to see nothing is deliberate. The person
     * making it knows what it is for and can say so in the next click; a
     * default of "everything" would mean every mistake is a full-access
     * account, and a default of "the last one's grants" would mean a mistake
     * nobody can see.
     */
    public static synchronized Result create(String username, String password) {
        return create(username, password, FIRST_RANK);
    }

    /** As above, at a given rank. */
    public static synchronized Result create(String username, String password, int rank) {
        String name = username == null ? "" : username.trim();
        String bad = nameProblem(name);
        if (!bad.isEmpty()) return Result.fail(bad);
        if (byUsername(name) != null) return Result.fail("There is already an account called " + name + ".");
        if (byName.size() >= MAX_ACCOUNTS) return Result.fail("That is as many accounts as Almin keeps.");
        String pw = passwordProblem(password);
        if (!pw.isEmpty()) return Result.fail(pw);
        Account a = new Account(newId(), name, Passwords.hash(password), "", "",
            new LinkedHashMap<>(), new LinkedHashMap<>(), false, false, false, rankOf(rank),
            System.currentTimeMillis(), 0, false);
        byName.put(name.toLowerCase(Locale.ROOT), a);
        save();
        AlminLog.info("[almin] panel account created: {}", name);
        return Result.done(name + " can sign in, but cannot see anything yet.", a);
    }

    /** Replaces somebody's password. The owner's lives in the config, not here. */
    public static synchronized Result setPassword(String id, String password) {
        Account a = stored(id);
        if (a == null) return Result.fail("No such account.");
        String bad = passwordProblem(password);
        if (!bad.isEmpty()) return Result.fail(bad);
        put(a.username(), new Account(a.id(), a.username(), Passwords.hash(password), a.mcName(), a.mcUuid(),
            a.access(), a.folders(), a.auditActivity(), a.startCommand(), a.hideCoords(), a.rank(), a.created(), a.lastLogin(), false));
        save();
        return Result.done(a.username() + "'s password is changed.");
    }

    /** Sets one menu's level. Anything but read or write means none. */
    public static synchronized Result setAccess(String id, String menu, String level) {
        Account a = stored(id);
        if (a == null) return Result.fail("No such account.");
        if (!MENUS.contains(menu)) return Result.fail("There is no " + menu + " menu.");
        Map<String, String> access = new LinkedHashMap<>(a.access());
        if (level.equals(READ) || level.equals(WRITE)) access.put(menu, level);
        else access.remove(menu);
        put(a.username(), new Account(a.id(), a.username(), a.hash(), a.mcName(), a.mcUuid(),
            access, a.folders(), a.auditActivity(), a.startCommand(), a.hideCoords(), a.rank(), a.created(), a.lastLogin(), false));
        save();
        return Result.done(a.username() + " " + describe(level) + " " + menuName(menu) + ".");
    }

    private static String describe(String level) {
        return switch (level) {
            case WRITE -> "can change";
            case READ -> "can read";
            default -> "cannot see";
        };
    }

    /**
     * Says which top-level folders an account may see, and which it may change.
     *
     * <p>Passing nothing clears the narrowing and hands back the whole tree,
     * which is what an account has until somebody says otherwise.
     */
    public static synchronized Result setFolder(String id, String folder, String level) {
        Account a = stored(id);
        if (a == null) return Result.fail("No such account.");
        String f = folder == null ? "" : folder.trim();
        if (f.isEmpty() || f.contains("/") || f.contains("\\") || f.contains("..")) {
            return Result.fail("That is not a folder name.");
        }
        Map<String, String> folders = new LinkedHashMap<>(a.folders());
        if (level.equals(READ) || level.equals(WRITE)) folders.put(f, level);
        else folders.remove(f);
        put(a.username(), new Account(a.id(), a.username(), a.hash(), a.mcName(), a.mcUuid(),
            a.access(), folders, a.auditActivity(), a.startCommand(), a.hideCoords(), a.rank(), a.created(), a.lastLogin(), false));
        save();
        return Result.done(a.username() + " " + describe(level) + " " + f + ".");
    }

    /** Hands the whole tree back, as it is for a new account. */
    public static synchronized Result clearFolders(String id) {
        Account a = stored(id);
        if (a == null) return Result.fail("No such account.");
        put(a.username(), new Account(a.id(), a.username(), a.hash(), a.mcName(), a.mcUuid(),
            a.access(), new LinkedHashMap<>(), a.auditActivity(), a.startCommand(), a.hideCoords(), a.rank(), a.created(), a.lastLogin(), false));
        save();
        return Result.done(a.username() + " can reach every folder the Files menu shows.");
    }

    /**
     * Moves an account up or down the order.
     *
     * <p>Bounds only; who may do it is {@link #outranks} at the call site.
     * The owner's rank is not a stored thing and cannot be set at all.
     */
    public static synchronized Result setRank(String id, int rank) {
        Account a = stored(id);
        if (a == null) return Result.fail("No such account.");
        int want = rankOf(rank);
        put(a.username(), new Account(a.id(), a.username(), a.hash(), a.mcName(), a.mcUuid(),
            a.access(), a.folders(), a.auditActivity(), a.startCommand(), a.hideCoords(), want, a.created(), a.lastLogin(), false));
        save();
        return Result.done(a.username() + " is now level " + want + ".");
    }

    /** Clamps a rank into what an ordinary account may hold. */
    public static int rankOf(int rank) {
        if (rank < FIRST_RANK) return FIRST_RANK;
        return Math.min(rank, LAST_RANK);
    }

    /** Ties an account to the Minecraft player it belongs to. Blank name unlinks. */
    public static synchronized Result link(String id, String mcName, String mcUuid) {
        Account a = stored(id);
        if (a == null) return Result.fail("No such account.");
        String n = mcName == null ? "" : mcName.trim();
        String u = mcUuid == null ? "" : mcUuid.trim();
        if (!n.isEmpty() && !n.matches("[A-Za-z0-9_]{1,16}")) {
            return Result.fail("That is not a Minecraft account name.");
        }
        put(a.username(), new Account(a.id(), a.username(), a.hash(), n, n.isEmpty() ? "" : u,
            a.access(), a.folders(), a.auditActivity(), a.startCommand(), a.hideCoords(), a.rank(), a.created(), a.lastLogin(), false));
        save();
        return Result.done(n.isEmpty()
            ? a.username() + " is no longer linked to a player."
            : a.username() + " is " + n + ".");
    }

    /**
     * Grants or withdraws the right to change what a restart runs.
     *
     * <p>Separate from every menu level because it is not a menu: it is the
     * one setting in Almin that becomes a command line on the host, and an
     * account holding it can do anything the server's user can.
     */
    public static synchronized Result setStartCommand(String id, boolean on) {
        Account a = stored(id);
        if (a == null) return Result.fail("No such account.");
        put(a.username(), new Account(a.id(), a.username(), a.hash(), a.mcName(), a.mcUuid(),
            a.access(), a.folders(), a.auditActivity(), on, a.hideCoords(), a.rank(), a.created(),
            a.lastLogin(), false));
        save();
        return Result.done(on
            ? a.username() + " can change what a restart runs on this machine."
            : a.username() + " can no longer change what a restart runs.");
    }

    /** Keeps coordinates out of the Activity menu for one account, or puts them back. */
    public static synchronized Result setHideCoords(String id, boolean on) {
        Account a = stored(id);
        if (a == null) return Result.fail("No such account.");
        put(a.username(), new Account(a.id(), a.username(), a.hash(), a.mcName(), a.mcUuid(),
            a.access(), a.folders(), a.auditActivity(), a.startCommand(), on, a.rank(),
            a.created(), a.lastLogin(), false));
        save();
        return Result.done(on
            ? a.username() + " is shown the Activity menu without coordinates."
            : a.username() + " is shown coordinates in the Activity menu again.");
    }

    /** Turns Activity-menu recording on or off for one account. */
    public static synchronized Result setAudit(String id, boolean on) {
        Account a = stored(id);
        if (a == null) return Result.fail("No such account.");
        put(a.username(), new Account(a.id(), a.username(), a.hash(), a.mcName(), a.mcUuid(),
            a.access(), a.folders(), on, a.startCommand(), a.hideCoords(), a.rank(), a.created(),
            a.lastLogin(), false));
        save();
        return Result.done(on
            ? a.username() + "'s use of the Activity menu is recorded, and they are told so."
            : a.username() + "'s use of the Activity menu is no longer recorded.");
    }

    public static synchronized Result rename(String id, String username) {
        Account a = stored(id);
        if (a == null) return Result.fail("No such account.");
        String name = username == null ? "" : username.trim();
        String bad = nameProblem(name);
        if (!bad.isEmpty()) return Result.fail(bad);
        Account clash = byUsername(name);
        if (clash != null && !clash.id().equals(a.id())) {
            return Result.fail("There is already an account called " + name + ".");
        }
        byName.remove(a.username().toLowerCase(Locale.ROOT));
        byName.put(name.toLowerCase(Locale.ROOT), new Account(a.id(), name, a.hash(), a.mcName(), a.mcUuid(),
            a.access(), a.folders(), a.auditActivity(), a.startCommand(), a.hideCoords(), a.rank(), a.created(), a.lastLogin(), false));
        save();
        return Result.done("Now called " + name + ".");
    }

    public static synchronized Result delete(String id) {
        Account a = stored(id);
        if (a == null) return Result.fail("No such account.");
        byName.remove(a.username().toLowerCase(Locale.ROOT));
        save();
        AlminLog.info("[almin] panel account deleted: {}", a.username());
        return Result.done(a.username() + " can no longer sign in.");
    }

    /** Records a successful login, for the "last seen" column. */
    public static synchronized void noteLogin(String id) {
        Account a = stored(id);
        if (a == null) return;
        put(a.username(), new Account(a.id(), a.username(), a.hash(), a.mcName(), a.mcUuid(),
            a.access(), a.folders(), a.auditActivity(), a.startCommand(), a.hideCoords(), a.rank(), a.created(), System.currentTimeMillis(), false));
        save();
    }

    // ---------- rules ----------

    /** Why this username will not do, or "". */
    public static String nameProblem(String name) {
        if (name == null || name.isBlank()) return "A username is needed.";
        if (name.length() > 24) return "That username is too long.";
        if (!name.matches("[A-Za-z0-9_.-]+")) {
            return "Usernames are letters, digits, dot, dash and underscore.";
        }
        return "";
    }

    /** Why this password will not do, or "". */
    public static String passwordProblem(String password) {
        if (password == null || password.length() < MIN_PASSWORD) {
            return "A password of at least " + MIN_PASSWORD + " characters is needed.";
        }
        if (password.length() > 200) return "That password is too long.";
        return "";
    }

    // ---------- plumbing ----------

    /** The stored account with this id — never the owner, who is not editable here. */
    private static Account stored(String id) {
        if (id == null || id.equals("owner")) return null;
        for (Account a : byName.values()) if (a.id().equals(id)) return a;
        return null;
    }

    private static void put(String username, Account a) {
        byName.put(username.toLowerCase(Locale.ROOT), a);
    }

    private static String newId() {
        byte[] raw = new byte[9];
        RNG.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static String str(JsonObject o, String field) {
        try {
            return o.has(field) && o.get(field).isJsonPrimitive() ? o.get(field).getAsString() : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static long num(JsonObject o, String field) {
        try {
            return o.has(field) && o.get(field).isJsonPrimitive() ? o.get(field).getAsLong() : 0L;
        } catch (RuntimeException e) {
            return 0L;
        }
    }
}
