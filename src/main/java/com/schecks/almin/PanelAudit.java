package com.schecks.almin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * What an account looked at in the Activity menu, when that account is watched.
 *
 * <h3>Why this exists at all</h3>
 * The Activity menu is a record of what everybody on the server did, where
 * they walked, and what they said. Handing somebody that is handing them a
 * surveillance tool, and the honest way to lend it out is to keep a record of
 * its use and to say so to the person using it. Both halves matter: a record
 * kept without telling them is worse than no record, and telling them without
 * keeping one is a bluff.
 *
 * <h3>Only the watched are recorded</h3>
 * Nothing is written for an account the owner has not switched this on for,
 * and nothing is ever written for the owner. There is no hidden mode: an
 * account whose use is recorded is told so, in a warning it has to dismiss,
 * every time it opens the menu.
 *
 * <h3>Visits, not requests</h3>
 * A panel open on the Activity tab polls, several times a second between them
 * all. The first version of this wrote a line per request and the result was
 * unreadable: a thousand copies of "read the activity log" with the one entry
 * worth reading somewhere inside them.
 *
 * <p>So the record is shaped like the thing it describes. A stretch of time
 * with the menu open is one entry — a <em>visit</em>, with a start and a
 * moving end — and the things somebody chose to do while they were in there
 * hang under it: who they picked out, what they searched for, what they asked
 * the model. The polling itself writes nothing at all; it only keeps the
 * visit's end time moving. Come back after {@link #VISIT_IDLE_MS} of silence
 * and that is a new visit.
 *
 * <p>Repeats of one act inside {@link #TOGETHER_MS} still fold into a single
 * entry with a count, which is the same trick the activity log itself uses,
 * for the same reason.
 */
public final class PanelAudit {

    /**
     * One thing somebody did, or one stretch of having the menu open.
     *
     * @param at       when it started
     * @param until    when the last of them happened; equal to {@code at} for one
     * @param username whose it was
     * @param what     the short phrase shown in the list
     * @param detail   what it was about — a player's name, a question, a place
     * @param count    how many were folded together
     * @param visit    true for the stretch itself, false for something done in it
     */
    public record Entry(long at, long until, String username, String what,
                        String detail, int count, boolean visit) {}

    /** Repeats of the same thing inside this window become one entry. */
    static final long TOGETHER_MS = 120_000L;

    /**
     * Silence for this long ends a visit.
     *
     * <p>Comfortably longer than the panel's own minute heartbeat, so a person
     * reading one screen for ten minutes is one visit rather than ten. Short
     * enough that closing the tab and coming back after lunch is two.
     */
    static final long VISIT_IDLE_MS = 240_000L;

    /** What a stretch with the menu open is called where a person reads it. */
    private static final String VISIT_WHAT = "opened the Activity menu";

    /** The most entries kept. Older ones fall off the end. */
    private static final int MAX_ENTRIES = 4000;

    private static volatile Path file;
    // A list rather than a deque: a visit's end time moves while it is open,
    // which means replacing an entry that is no longer at either end.
    private static final List<Entry> entries = new ArrayList<>();
    private static volatile long lastSave;

    private PanelAudit() {}

    /** The filename, so the file browser can be told to leave it alone. */
    public static String fileName() { return "panel-audit.json"; }

    public static synchronized void init(Path serverDir) {
        file = serverDir.resolve("config").resolve("almin").resolve(fileName());
        entries.clear();
        Path f = file;
        if (f == null || !Files.isRegularFile(f)) return;
        try {
            JsonElement root = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) return;
            JsonArray list = root.getAsJsonObject().getAsJsonArray("entries");
            if (list == null) return;
            for (JsonElement e : list) {
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                entries.add(new Entry(num(o, "at"), num(o, "until"), str(o, "username"),
                    str(o, "what"), str(o, "detail"), (int) Math.max(1, num(o, "count")),
                    o.has("visit") && o.get("visit").getAsBoolean()));
            }
            prune();
        } catch (Exception e) {
            AlminLog.warn("[almin] could not read {}: {}", fileName(), e.getMessage());
        }
    }

    /**
     * Records one thing, if this account is watched.
     *
     * <p>Takes the account rather than a name so the decision of whether to
     * write anything is made here, once, instead of at each call site where
     * it could be forgotten in the direction that fails quietly.
     */
    public static void note(Accounts.Account who, String what, String detail) {
        if (who == null || who.owner() || !who.auditActivity()) return;
        if (what == null || what.isBlank()) return;
        String clean = detail == null ? "" : detail.trim();
        if (clean.length() > 160) clean = clean.substring(0, 160);
        add(who.username(), what, clean);
    }

    /**
     * Notes that this account has the Activity menu open right now.
     *
     * <p>Called for every request the menu makes, and writes nothing for
     * almost all of them: it starts a visit if there is not one running, and
     * otherwise only moves the running one's end time. That is what turns a
     * poll every second into one line saying how long somebody was in there.
     */
    public static synchronized void visiting(Accounts.Account who) {
        if (who == null || who.owner() || !who.auditActivity()) return;
        long now = System.currentTimeMillis();
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry e = entries.get(i);
            if (!e.visit() || !e.username().equalsIgnoreCase(who.username())) continue;
            if (now - e.until() >= VISIT_IDLE_MS) break;   // they had gone; this is a new one
            entries.set(i, new Entry(e.at(), now, e.username(), e.what(), e.detail(),
                                     e.count(), true));
            saveSoon(now);
            return;
        }
        entries.add(new Entry(now, now, who.username(), VISIT_WHAT, "", 1, true));
        prune();
        saveSoon(now);
    }

    private static synchronized void add(String username, String what, String detail) {
        long now = System.currentTimeMillis();
        Entry last = entries.isEmpty() ? null : entries.get(entries.size() - 1);
        if (last != null && !last.visit() && last.username().equals(username)
            && last.what().equals(what) && last.detail().equals(detail)
            && now - last.until() < TOGETHER_MS) {
            entries.set(entries.size() - 1,
                new Entry(last.at(), now, username, what, detail, last.count() + 1, false));
        } else {
            entries.add(new Entry(now, now, username, what, detail, 1, false));
        }
        prune();
        saveSoon(now);
    }

    /**
     * Written promptly, because the reason to keep it is the case where
     * somebody is about to be asked about it. Folding keeps this rare.
     */
    private static void saveSoon(long now) {
        if (now - lastSave > 2000) { lastSave = now; save(); }
    }

    private static void prune() {
        int keepDays = AlminConfig.get().panelAuditDays;
        long cutoff = keepDays <= 0 ? 0 : System.currentTimeMillis() - keepDays * 86_400_000L;
        int drop = 0;
        while (drop < entries.size() && entries.get(drop).until() < cutoff) drop++;
        if (entries.size() - drop > MAX_ENTRIES) drop = entries.size() - MAX_ENTRIES;
        if (drop > 0) entries.subList(0, drop).clear();
    }

    /** Everything recorded for one account, newest first. */
    public static synchronized List<Entry> forUser(String username) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) if (e.username().equalsIgnoreCase(username)) out.add(e);
        java.util.Collections.reverse(out);
        return out;
    }

    /** Everything recorded, newest first. */
    public static synchronized List<Entry> all() {
        List<Entry> out = new ArrayList<>(entries);
        java.util.Collections.reverse(out);
        return out;
    }

    /** Throws away one account's record — used when the account is removed. */
    public static synchronized void forget(String username) {
        entries.removeIf(e -> e.username().equalsIgnoreCase(username));
        save();
    }

    /** Writes it out now, whatever the debounce says. */
    public static synchronized void flush() { save(); }

    private static void save() {
        Path f = file;
        if (f == null) return;
        try {
            Files.createDirectories(f.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("version", 2);
            JsonArray list = new JsonArray();
            for (Entry e : entries) {
                JsonObject o = new JsonObject();
                o.addProperty("at", e.at());
                o.addProperty("until", e.until());
                o.addProperty("username", e.username());
                o.addProperty("what", e.what());
                o.addProperty("detail", e.detail());
                o.addProperty("count", e.count());
                if (e.visit()) o.addProperty("visit", true);
                list.add(o);
            }
            root.add("entries", list);
            Files.writeString(f, root.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            AlminLog.warn("[almin] could not write {}: {}", fileName(), e.getMessage());
        }
    }

    /**
     * What to call one of the Activity routes, where a person reads it, or
     * {@code null} for a request that is not worth a line of its own.
     *
     * <p>Deliberately in the vocabulary of what somebody was doing rather than
     * which endpoint answered: "cleared activity records" is a thing a person
     * did, and "/api/reset" is not.
     *
     * <p>Most of the menu's traffic returns null. Reading the log, the paths,
     * the map, the pictures of the ground and the blocks behind a 3D view are
     * all the panel drawing itself, several times a second, and a line each is
     * a record nobody can read. Those keep the visit alive and nothing more;
     * what somebody actually chose to look at is reported by the panel, which
     * is the only place that knows. What stays here is the handful of requests
     * that are a decision on their own — and anything that changes something,
     * whether or not it was named, so a route added later is not silent.
     */
    public static String describe(String route, String method) {
        boolean run = "POST".equals(method);
        return switch (route) {
            // GET is the episode list Almin works out for itself, polled on a
            // timer. POST is the one that spends somebody's money.
            case "/api/insights" -> run ? "asked the model to summarise" : null;
            case "/api/insights/find" -> "asked the model to find something";
            case "/api/reset" -> "cleared activity records";
            default -> run || "DELETE".equals(method) || "PUT".equals(method)
                ? "used the activity menu" : null;
        };
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
