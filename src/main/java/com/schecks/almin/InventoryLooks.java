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
 * Who looked in whose inventory, and when.
 *
 * <h3>Why this is its own record</h3>
 * Looking at what somebody is carrying is the most intrusive thing the panel
 * can do. It is not activity — the player did nothing — so it does not belong
 * in the activity log, which is about the world and is thrown away on the
 * activity retention clock. And it is not covered by {@link PanelAudit}, which
 * records watched accounts and deliberately never records the owner: an owner
 * who can look at everything unrecorded is exactly the hole this closes.
 *
 * <h3>Everybody, always</h3>
 * There is no account this is switched off for and no setting that turns it
 * off, including for the owner. That is the trade the feature is offered on:
 * the panel will show you what somebody is carrying, and it will say that you
 * looked. The record is shown at the top of the same sheet the looking happens
 * in, so the next person to open it sees who has been there — which is the
 * point, because a record nobody reads is a record that protects nobody.
 *
 * <h3>What it keeps</h3>
 * When, which account, and whose inventory. Never what was in it: a list of
 * somebody's things copied into a second file, kept forever, would be a worse
 * privacy problem than the one this is here to solve.
 */
public final class InventoryLooks {

    /** How many to keep. Old enough to be forgotten is old enough not to matter. */
    private static final int MAX = 600;

    private static final Object LOCK = new Object();
    private static final List<Look> looks = new ArrayList<>();
    private static Path file;
    private static boolean loaded;

    private InventoryLooks() {}

    /**
     * One look.
     *
     * @param at     when
     * @param who    the panel account that looked
     * @param uuid   whose inventory it was
     * @param player the name that account was shown, for a sheet to print
     */
    public record Look(long at, String who, String uuid, String player) {}

    /** The file this lives in. Public so the tests can point it somewhere. */
    public static void init(Path serverDir) {
        synchronized (LOCK) {
            file = serverDir == null ? null
                : serverDir.resolve("config").resolve("almin").resolve("inventory-looks.json");
            looks.clear();
            loaded = false;
            load();
        }
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        if (file == null || !Files.isRegularFile(file)) return;
        try {
            JsonElement root = JsonParser.parseString(
                Files.readString(file, StandardCharsets.UTF_8));
            if (!root.isJsonArray()) return;
            for (JsonElement e : root.getAsJsonArray()) {
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                looks.add(new Look(o.get("at").getAsLong(), str(o, "who"),
                    str(o, "uuid"), str(o, "player")));
            }
        } catch (IOException | RuntimeException e) {
            AlminLog.warn("[almin] could not read the inventory record: {}", e.getMessage());
        }
    }

    private static String str(JsonObject o, String k) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * Writes one down.
     *
     * <p>Called before the inventory is read rather than after. If the read
     * then fails there is a record of an attempt, which is the right way round:
     * the alternative loses the record of anything that went wrong halfway.
     */
    public static void record(String who, String uuid, String player) {
        Look look = new Look(System.currentTimeMillis(),
            who == null ? "" : who, uuid == null ? "" : uuid, player == null ? "" : player);
        synchronized (LOCK) {
            load();
            looks.add(look);
            while (looks.size() > MAX) looks.remove(0);
            save();
        }
        AlminLog.info("[almin] {} looked in {}'s inventory", look.who(), look.player());
    }

    private static void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            JsonArray arr = new JsonArray();
            for (Look l : looks) {
                JsonObject o = new JsonObject();
                o.addProperty("at", l.at());
                o.addProperty("who", l.who());
                o.addProperty("uuid", l.uuid());
                o.addProperty("player", l.player());
                arr.add(o);
            }
            Files.writeString(file, arr.toString(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            AlminLog.warn("[almin] could not write the inventory record: {}", e.getMessage());
        }
    }

    /** Every look at one player, newest first. */
    public static List<Look> forPlayer(String uuid) {
        synchronized (LOCK) {
            load();
            List<Look> out = new ArrayList<>();
            for (int i = looks.size() - 1; i >= 0; i--) {
                Look l = looks.get(i);
                if (uuid == null || uuid.isEmpty() || l.uuid().equalsIgnoreCase(uuid)) out.add(l);
            }
            return out;
        }
    }

    /** Everything, newest first. For the tests, and for a record anybody may read. */
    public static List<Look> all() {
        return forPlayer(null);
    }

    /** For the tests, and for a world reset: the record goes with the world it is about. */
    public static void forget() {
        synchronized (LOCK) {
            looks.clear();
            loaded = true;
            if (file == null) return;
            try { Files.deleteIfExists(file); }
            catch (IOException e) {
                AlminLog.warn("[almin] could not clear the inventory record: {}", e.getMessage());
            }
        }
    }
}
