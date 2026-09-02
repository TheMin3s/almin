package com.schecks.almin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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
 * <h3>Coalescing</h3>
 * A panel open on the Activity tab polls. Writing a line per request would
 * bury the one interesting entry under a thousand identical ones, so the same
 * account doing the same thing inside {@link #TOGETHER_MS} becomes one entry
 * with a count and a moving end time. That is the same trick the activity log
 * itself uses, for the same reason.
 */
public final class PanelAudit {

    /**
     * One thing somebody did.
     *
     * @param at       when it started
     * @param until    when the last of them happened; equal to {@code at} for one
     * @param username whose it was
     * @param what     the short phrase shown in the list
     * @param detail   what it was about — a player's name, a question, a place
     * @param count    how many were folded together
     */
    public record Entry(long at, long until, String username, String what,
                        String detail, int count) {}

    /** Repeats of the same thing inside this window become one entry. */
    static final long TOGETHER_MS = 120_000L;

    /** The most entries kept. Older ones fall off the end. */
    private static final int MAX_ENTRIES = 4000;

    private static volatile Path file;
    private static final Deque<Entry> entries = new ArrayDeque<>();
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
                entries.addLast(new Entry(num(o, "at"), num(o, "until"), str(o, "username"),
                    str(o, "what"), str(o, "detail"), (int) Math.max(1, num(o, "count"))));
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

    private static synchronized void add(String username, String what, String detail) {
        long now = System.currentTimeMillis();
        Entry last = entries.peekLast();
        if (last != null && last.username().equals(username) && last.what().equals(what)
            && last.detail().equals(detail) && now - last.until() < TOGETHER_MS) {
            entries.removeLast();
            entries.addLast(new Entry(last.at(), now, username, what, detail, last.count() + 1));
        } else {
            entries.addLast(new Entry(now, now, username, what, detail, 1));
        }
        prune();
        // Written promptly, because the reason to keep it is the case where
        // somebody is about to be asked about it. Folding keeps this rare.
        if (now - lastSave > 2000) { lastSave = now; save(); }
    }

    private static void prune() {
        int keepDays = AlminConfig.get().panelAuditDays;
        long cutoff = keepDays <= 0 ? 0 : System.currentTimeMillis() - keepDays * 86_400_000L;
        while (!entries.isEmpty() && entries.peekFirst().until() < cutoff) entries.removeFirst();
        while (entries.size() > MAX_ENTRIES) entries.removeFirst();
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
            root.addProperty("version", 1);
            JsonArray list = new JsonArray();
            for (Entry e : entries) {
                JsonObject o = new JsonObject();
                o.addProperty("at", e.at());
                o.addProperty("until", e.until());
                o.addProperty("username", e.username());
                o.addProperty("what", e.what());
                o.addProperty("detail", e.detail());
                o.addProperty("count", e.count());
                list.add(o);
            }
            root.add("entries", list);
            Files.writeString(f, root.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            AlminLog.warn("[almin] could not write {}: {}", fileName(), e.getMessage());
        }
    }

    /**
     * What to call one of the Activity routes, where a person reads it.
     *
     * <p>Deliberately in the vocabulary of what somebody was doing rather than
     * which endpoint answered: "read the activity log" is a thing a person
     * did, and "/api/activity" is not.
     */
    public static String describe(String route) {
        return switch (route) {
            case "/api/activity" -> "read the activity log";
            case "/api/track" -> "followed players' paths";
            case "/api/map", "/api/bluemap", "/bluemap" -> "looked at the map";
            case "/api/scene/context", "/api/blocks", "/api/block", "/api/item" ->
                "opened a build or fight in 3D";
            case "/api/insights" -> "asked the model to summarise";
            case "/api/insights/find" -> "asked the model to find something";
            case "/api/reset" -> "cleared activity records";
            default -> "used the activity menu";
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
