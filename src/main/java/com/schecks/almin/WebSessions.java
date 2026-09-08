package com.schecks.almin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Web sessions that outlive the process, plus per-client login throttling.
 *
 * <h3>Staying logged in</h3>
 * A session used to be a number in memory with a fixed expiry, so every
 * restart signed everybody out and every closed browser lost the cookie. Both
 * were accidents of how it was stored rather than decisions about who should
 * be logged in. Now the sessions are on the disk, the cookie has a lifetime,
 * and the expiry slides: using the panel pushes it out, so a login lasts until
 * it is genuinely unused for {@code web-session-minutes}.
 *
 * <h3>What still ends a session</h3>
 * Changing the password behind it. Each session carries a stamp taken from the
 * account's stored hash, and the stamp is checked on every request — so a
 * changed password ends that account's logins whether it was changed from the
 * panel, from a command, or by editing the file, and whether the server was
 * running at the time or not. Deleting the account does the same. So does
 * logging out, which is the one that people actually reach for.
 *
 * <h3>What is on the disk</h3>
 * Not the session ids. The file holds a SHA-256 of each one, the way a
 * password file holds a hash: somebody who can read it cannot put its contents
 * in a cookie and be signed in. The id itself exists in exactly one place,
 * which is the browser that was given it.
 */
public final class WebSessions {
    private static final int MAX_FAILURES = 5;
    private static final long LOCKOUT_MS = 15 * 60_000L;
    private static final SecureRandom RNG = new SecureRandom();

    /** Sessions past this many are dropped oldest-first, so the file cannot grow forever. */
    private static final int MAX_SESSIONS = 400;

    /** How much the expiry has to move before it is worth writing the file again. */
    private static final long SAVE_EVERY_MS = 60_000L;

    /**
     * @param accountId whose session this is — {@code "owner"} for the
     *                  config's own admin password, or an id from
     *                  {@link Accounts}. Held here rather than in a cookie so
     *                  that changing what somebody may reach takes effect on
     *                  their next request rather than their next login.
     * @param stamp     what their password looked like when they logged in
     * @param created   when they logged in, which a restart must not forget
     * @param seen      the last request this session made
     */
    private record Session(long expiresAt, String accountId, String stamp,
                           long created, long seen) {}
    private record Attempts(int count, long since) {}

    /** Keyed by the hash of the id, never by the id: the same thing the file holds. */
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Attempts> failures = new ConcurrentHashMap<>();

    private final Object diskLock = new Object();
    private volatile Path file;
    private volatile long lastSave;

    /**
     * Points this at a file and reads whatever is in it.
     *
     * <p>Called once, when the panel starts. Without it everything still works
     * and is simply forgotten on the way down, which is what a test wants and
     * what the panel had before.
     */
    public void init(Path serverDir) {
        synchronized (diskLock) {
            file = serverDir == null ? null
                : serverDir.resolve("config").resolve("almin").resolve("sessions.json");
            sessions.clear();
            load();
        }
    }

    private void load() {
        Path f = file;
        if (f == null || !Files.isRegularFile(f)) return;
        long now = System.currentTimeMillis();
        int kept = 0, dropped = 0;
        try {
            JsonElement root = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8));
            if (!root.isJsonArray()) return;
            for (JsonElement e : root.getAsJsonArray()) {
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                String key = str(o, "id");
                long expires = num(o, "expires");
                if (key.isEmpty() || expires <= now) { dropped++; continue; }
                sessions.put(key, new Session(expires, str(o, "account"), str(o, "stamp"),
                    num(o, "created"), num(o, "seen")));
                kept++;
            }
        } catch (IOException | RuntimeException e) {
            AlminLog.warn("[almin] could not read the saved logins: {}", e.getMessage());
            return;
        }
        if (kept > 0) {
            AlminLog.info("[almin] {} panel login(s) carried across the restart", kept);
        }
        if (dropped > 0) save();
    }

    private static String str(JsonObject o, String k) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static long num(JsonObject o, String k) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsLong() : 0L;
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private void save() {
        Path f = file;
        if (f == null) return;
        synchronized (diskLock) {
            lastSave = System.currentTimeMillis();
            try {
                Files.createDirectories(f.getParent());
                JsonArray arr = new JsonArray();
                for (Map.Entry<String, Session> e : sessions.entrySet()) {
                    JsonObject o = new JsonObject();
                    o.addProperty("id", e.getKey());
                    o.addProperty("account", e.getValue().accountId());
                    o.addProperty("stamp", e.getValue().stamp());
                    o.addProperty("expires", e.getValue().expiresAt());
                    o.addProperty("created", e.getValue().created());
                    o.addProperty("seen", e.getValue().seen());
                    arr.add(o);
                }
                Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
                Files.writeString(tmp, arr.toString(), StandardCharsets.UTF_8);
                Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                // Nobody else's business. Best-effort: a filesystem without
                // POSIX permissions is not a reason to refuse to log in.
                try {
                    Files.setPosixFilePermissions(f,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                } catch (UnsupportedOperationException | IOException ignored) {
                    // Windows, or a share. The folder is the protection there.
                }
            } catch (IOException | RuntimeException e) {
                AlminLog.warn("[almin] could not save the panel logins: {}", e.getMessage());
            }
        }
    }

    // ---------- ids ----------

    /** The id as the file knows it. Never reversible, which is the point. */
    public static String keyOf(String id) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(id.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }

    /**
     * What an account's password looks like, short.
     *
     * <p>A hash of the stored hash, so the file that remembers logins does not
     * also carry a second copy of everybody's password hash. Empty when there
     * is no such account any more, which reads as "no session of theirs is
     * valid" — the right answer for a deleted account.
     */
    static String stampFor(String accountId) {
        String hash;
        if (accountId == null || accountId.isEmpty() || "owner".equals(accountId)) {
            hash = AlminConfig.get().webAdminPasswordHash;
        } else {
            Accounts.Account a = Accounts.byId(accountId);
            hash = a == null ? null : a.hash();
        }
        if (hash == null || hash.isEmpty()) return "";
        return keyOf(hash).substring(0, 32);
    }

    // ---------- the sessions themselves ----------

    /** Creates a session valid for {@code minutes} and returns its id. */
    public String open(int minutes) {
        return open(minutes, "owner");
    }

    /** Creates a session belonging to {@code accountId} and returns its id. */
    public String open(int minutes, String accountId) {
        byte[] raw = new byte[32];
        RNG.nextBytes(raw);
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String who = accountId == null ? "owner" : accountId;
        long now = System.currentTimeMillis();
        sessions.put(keyOf(id), new Session(now + minutes * 60_000L, who,
            stampFor(who), now, now));
        prune(now);
        save();
        return id;
    }

    /**
     * Looks a session up, and pushes its expiry out.
     *
     * <p>The one place a session is read, so it is the one place the sliding
     * window has to be applied. Everything else asks this.
     */
    private Session live(String id) {
        if (id == null || id.isEmpty()) return null;
        String key = keyOf(id);
        Session s = sessions.get(key);
        if (s == null) return null;
        long now = System.currentTimeMillis();
        if (now >= s.expiresAt()) {
            sessions.remove(key);
            save();
            return null;
        }
        // The password behind it changed, or the account went away. Checked
        // here rather than only where passwords are set, so a change made in
        // any of the several ways it can be made ends the session. An empty
        // stamp is never a match, including against another empty one: it
        // means there is no account and no password to be a session for.
        String now2 = stampFor(s.accountId());
        if (now2.isEmpty() || !now2.equals(s.stamp())) {
            sessions.remove(key);
            save();
            return null;
        }
        long minutes = Math.max(5, AlminConfig.get().webSessionMinutes);
        long fresh = now + minutes * 60_000L;
        if (fresh > s.expiresAt()) {
            Session moved = new Session(fresh, s.accountId(), s.stamp(), s.created(), now);
            sessions.put(key, moved);
            // Written at most once a minute. The expiry moves on every
            // request, and the panel polls every three seconds.
            if (now - lastSave >= SAVE_EVERY_MS) save();
            return moved;
        }
        return s;
    }

    /** Drops the oldest sessions once there are absurdly many of them. */
    private void prune(long now) {
        sessions.entrySet().removeIf(e -> now >= e.getValue().expiresAt());
        while (sessions.size() > MAX_SESSIONS) {
            String oldest = null;
            long at = Long.MAX_VALUE;
            for (Map.Entry<String, Session> e : sessions.entrySet()) {
                if (e.getValue().created() < at) { at = e.getValue().created(); oldest = e.getKey(); }
            }
            if (oldest == null) return;
            sessions.remove(oldest);
        }
    }

    /** Whose session this is, or null if it is not a live one. */
    public String accountOf(String id) {
        Session s = live(id);
        return s == null ? null : s.accountId();
    }

    /** Ends every session belonging to one account — used when it is deleted. */
    public void closeAccount(String accountId) {
        if (accountId == null) return;
        if (sessions.entrySet().removeIf(e -> accountId.equals(e.getValue().accountId()))) save();
    }

    /** True if {@code id} names a live session; expired ones are dropped here. */
    public boolean valid(String id) {
        return live(id) != null;
    }

    /** Ends a session (logout). No-op if it wasn't live. */
    public void close(String id) {
        if (id == null || id.isEmpty()) return;
        if (sessions.remove(keyOf(id)) != null) save();
    }

    /** Drops every session — e.g. when the password changes. */
    public void closeAll() {
        sessions.clear();
        save();
    }

    /** How many logins are currently being remembered, for the panel to say. */
    public int count() {
        long now = System.currentTimeMillis();
        return (int) sessions.values().stream().filter(s -> now < s.expiresAt()).count();
    }

    // ---------- login throttling ----------

    /** True while {@code clientKey} is locked out from further login attempts. */
    public boolean lockedOut(String clientKey) {
        Attempts a = failures.get(clientKey);
        if (a == null) return false;
        if (System.currentTimeMillis() - a.since() >= LOCKOUT_MS) {
            failures.remove(clientKey);
            return false;
        }
        return a.count() >= MAX_FAILURES;
    }

    /** Records a failed login for {@code clientKey}; returns attempts remaining. */
    public int recordFailure(String clientKey) {
        Attempts updated = failures.compute(clientKey, (k, a) -> {
            long now = System.currentTimeMillis();
            if (a == null || now - a.since() >= LOCKOUT_MS) return new Attempts(1, now);
            return new Attempts(a.count() + 1, a.since());
        });
        return Math.max(0, MAX_FAILURES - updated.count());
    }

    /** Clears the failure counter for {@code clientKey} after a good login. */
    public void recordSuccess(String clientKey) {
        failures.remove(clientKey);
    }

    public int maxFailures() {
        return MAX_FAILURES;
    }

    public long lockoutMinutes() {
        return LOCKOUT_MS / 60_000L;
    }
}
