package com.schecks.almin;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory web sessions plus per-client login throttling.
 *
 * <p>A session is a 256-bit random id with an absolute expiry; there is no
 * server-side state beyond that, and everything is dropped when the server
 * stops, so a restart logs everyone out. Login attempts are counted per client
 * key (its address) and locked out after {@link #MAX_FAILURES} misses for
 * {@link #LOCKOUT_MS}, which is what keeps the single admin password from being
 * brute-forced over HTTP.
 */
public final class WebSessions {
    private static final int MAX_FAILURES = 5;
    private static final long LOCKOUT_MS = 15 * 60_000L;
    private static final SecureRandom RNG = new SecureRandom();

    /**
     * @param accountId whose session this is — {@code "owner"} for the
     *                  config's own admin password, or an id from
     *                  {@link Accounts}. Held here rather than in a cookie so
     *                  that changing what somebody may reach takes effect on
     *                  their next request rather than their next login.
     */
    private record Session(long expiresAt, String accountId) {}
    private record Attempts(int count, long since) {}

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Attempts> failures = new ConcurrentHashMap<>();

    /** Creates a session valid for {@code minutes} and returns its id. */
    public String open(int minutes) {
        return open(minutes, "owner");
    }

    /** Creates a session belonging to {@code accountId} and returns its id. */
    public String open(int minutes, String accountId) {
        byte[] raw = new byte[32];
        RNG.nextBytes(raw);
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        sessions.put(id, new Session(System.currentTimeMillis() + minutes * 60_000L,
            accountId == null ? "owner" : accountId));
        return id;
    }

    /** Whose session this is, or null if it is not a live one. */
    public String accountOf(String id) {
        if (id == null) return null;
        Session s = sessions.get(id);
        if (s == null) return null;
        if (System.currentTimeMillis() >= s.expiresAt()) {
            sessions.remove(id);
            return null;
        }
        return s.accountId();
    }

    /** Ends every session belonging to one account — used when it is deleted. */
    public void closeAccount(String accountId) {
        if (accountId == null) return;
        sessions.entrySet().removeIf(e -> accountId.equals(e.getValue().accountId()));
    }

    /** True if {@code id} names a live session; expired ones are dropped here. */
    public boolean valid(String id) {
        if (id == null) return false;
        Session s = sessions.get(id);
        if (s == null) return false;
        if (System.currentTimeMillis() >= s.expiresAt()) {
            sessions.remove(id);
            return false;
        }
        return true;
    }

    /** Ends a session (logout). No-op if it wasn't live. */
    public void close(String id) {
        if (id != null) sessions.remove(id);
    }

    /** Drops every session — e.g. when the password changes. */
    public void closeAll() {
        sessions.clear();
    }

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
