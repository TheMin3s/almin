package com.schecks.almin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * What each player is running, and what it used to be.
 *
 * <p>The interesting question is rarely "what mods does this player have"; it
 * is "what changed". A client that worked yesterday and crashes today changed
 * something, and a list that only ever shows the present cannot say what. So
 * this keeps the current list and, for a while, what was added and what was
 * taken away.
 *
 * <h3>How change is recorded</h3>
 * Not as a log of every join — a player who logs in forty times a day would
 * write forty identical entries. Each mod carries the moment it was first seen
 * and, once it stops appearing, the moment it went. A mod that comes back
 * clears its removal, so alt-tabbing between two profiles does not fill the
 * list with ghosts.
 *
 * <h3>Lifetime</h3>
 * Removals are shown for {@code client-mod-history-days} and then forgotten.
 * The present list stays as long as the player is known, because it is the
 * answer to "what is on that client", which does not expire.
 *
 * <h3>Threading</h3>
 * Written from the network thread on join and read from HTTP threads.
 * Everything touching the map is synchronized on the class; the file is
 * written on the same thread that changed it, which happens once per join.
 */
public final class ClientProfiles {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** One mod on one client, and what has happened to it. */
    public record Mod(String id, String version, long firstSeen, long removedAt) {
        public boolean gone() { return removedAt > 0; }
    }

    /** One client, as of its last join. */
    public record Profile(UUID uuid, String name, long at,
                          String minecraft, String loader, String launcher,
                          String os, String osVersion, String arch, String java,
                          int cores, int memoryMb, List<Mod> mods) {

        /** Mods that are on the client now, alphabetically. */
        public List<Mod> present() {
            List<Mod> out = new ArrayList<>();
            for (Mod m : mods) if (!m.gone()) out.add(m);
            out.sort(Comparator.comparing(m -> m.id().toLowerCase(Locale.ROOT)));
            return out;
        }

        /** Mods that have gone, most recently first. */
        public List<Mod> removed() {
            List<Mod> out = new ArrayList<>();
            for (Mod m : mods) if (m.gone()) out.add(m);
            out.sort(Comparator.comparingLong(Mod::removedAt).reversed());
            return out;
        }
    }

    private static final Map<UUID, Profile> profiles = new LinkedHashMap<>();
    private static volatile Path file;

    /** Ceiling on how many clients are remembered, oldest dropped first. */
    private static final int MAX_PROFILES = 500;

    private ClientProfiles() {}

    public static synchronized void init(MinecraftServer server) {
        file = server.getServerDirectory().resolve("config").resolve("almin")
            .resolve("clients.json");
        load();
    }

    // ---------- recording one ----------

    /**
     * Files what a client just reported, against what it reported last time.
     *
     * @return the mods that are new since the last join, for anything that
     *         wants to say so out loud
     */
    public static synchronized List<String> record(UUID id, String name,
                                                   ClientProfilePayload said) {
        long now = System.currentTimeMillis();
        Profile before = profiles.get(id);
        Map<String, Mod> was = new LinkedHashMap<>();
        if (before != null) for (Mod m : before.mods()) was.put(m.id(), m);

        Map<String, String> nowHas = new LinkedHashMap<>();
        for (String entry : said.mods()) {
            int at = entry.lastIndexOf('@');
            String modId = at > 0 ? entry.substring(0, at) : entry;
            String version = at > 0 ? entry.substring(at + 1) : "";
            if (modId.isBlank()) continue;
            nowHas.put(modId, version);
        }

        List<Mod> merged = new ArrayList<>();
        List<String> added = new ArrayList<>();
        for (Map.Entry<String, String> e : nowHas.entrySet()) {
            Mod old = was.get(e.getKey());
            if (old == null) {
                merged.add(new Mod(e.getKey(), e.getValue(), now, 0));
                // Only new if this client has been seen before; the first
                // sighting is not "they installed forty mods".
                if (before != null) added.add(e.getKey());
            } else {
                // Coming back clears the removal: a mod toggled off and on is
                // not a mod that left.
                merged.add(new Mod(e.getKey(), e.getValue(), old.firstSeen(), 0));
            }
        }
        for (Mod old : was.values()) {
            if (nowHas.containsKey(old.id())) continue;
            merged.add(old.gone() ? old
                : new Mod(old.id(), old.version(), old.firstSeen(), now));
        }

        profiles.put(id, new Profile(id, name, now,
            said.minecraft(), said.loader(), said.launcher(),
            said.os(), said.osVersion(), said.arch(), said.java(),
            said.cores(), said.memoryMb(), List.copyOf(merged)));
        forgetOldRemovals();
        while (profiles.size() > MAX_PROFILES) {
            profiles.remove(profiles.keySet().iterator().next());
        }
        save();
        return added;
    }

    /** Drops removals nobody is going to look at again. */
    private static void forgetOldRemovals() {
        long cutoff = System.currentTimeMillis() - historyMillis();
        for (Map.Entry<UUID, Profile> e : new ArrayList<>(profiles.entrySet())) {
            Profile p = e.getValue();
            List<Mod> keep = new ArrayList<>(p.mods().size());
            boolean changed = false;
            for (Mod m : p.mods()) {
                if (m.gone() && m.removedAt() < cutoff) { changed = true; continue; }
                keep.add(m);
            }
            if (changed) {
                e.setValue(new Profile(p.uuid(), p.name(), p.at(), p.minecraft(), p.loader(),
                    p.launcher(), p.os(), p.osVersion(), p.arch(), p.java(), p.cores(),
                    p.memoryMb(), List.copyOf(keep)));
            }
        }
    }

    public static long historyMillis() {
        return Math.max(1, AlminConfig.get().clientModHistoryDays) * 86_400_000L;
    }

    // ---------- reading ----------

    public static synchronized Profile of(UUID id) {
        forgetOldRemovals();
        return profiles.get(id);
    }

    public static synchronized List<Profile> all() {
        forgetOldRemovals();
        List<Profile> out = new ArrayList<>(profiles.values());
        out.sort(Comparator.comparingLong(Profile::at).reversed());
        return out;
    }

    /**
     * Which of a client's mods are on the restricted list.
     *
     * <p>Matched on the mod id, lower-cased, because that is the only thing a
     * server and a client reliably agree on — display names are translated and
     * versions change.
     */
    public static List<String> restricted(Profile profile) {
        if (profile == null) return List.of();
        TreeSet<String> banned = restrictedSet();
        if (banned.isEmpty()) return List.of();
        List<String> hits = new ArrayList<>();
        for (Mod m : profile.present()) {
            if (banned.contains(m.id().toLowerCase(Locale.ROOT))) hits.add(m.id());
        }
        return hits;
    }

    /** The configured list, parsed. */
    public static TreeSet<String> restrictedSet() {
        TreeSet<String> out = new TreeSet<>();
        String raw = AlminConfig.get().modsRestricted;
        if (raw == null) return out;
        for (String s : raw.split(",")) {
            String t = s.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    public static synchronized void clear() {
        profiles.clear();
        save();
    }

    // ---------- disk ----------

    private static void load() {
        Path f = file;
        if (f == null || !Files.exists(f)) return;
        try {
            JsonObject root = JsonParser.parseString(
                Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            for (String key : root.keySet()) {
                UUID id;
                try { id = UUID.fromString(key); } catch (IllegalArgumentException bad) { continue; }
                JsonObject o = root.getAsJsonObject(key);
                List<Mod> mods = new ArrayList<>();
                if (o.has("mods") && o.get("mods").isJsonArray()) {
                    for (var el : o.getAsJsonArray("mods")) {
                        JsonObject m = el.getAsJsonObject();
                        mods.add(new Mod(str(m, "id"), str(m, "version"),
                            num(m, "firstSeen"), num(m, "removedAt")));
                    }
                }
                profiles.put(id, new Profile(id, str(o, "name"), num(o, "at"),
                    str(o, "minecraft"), str(o, "loader"), str(o, "launcher"),
                    str(o, "os"), str(o, "osVersion"), str(o, "arch"), str(o, "java"),
                    (int) num(o, "cores"), (int) num(o, "memoryMb"), List.copyOf(mods)));
            }
        } catch (Exception e) {
            AlminLog.warn("[almin] clients.json unreadable ({}), starting fresh", e.getMessage());
        }
    }

    private static void save() {
        Path f = file;
        if (f == null) return;
        try {
            Files.createDirectories(f.getParent());
            JsonObject root = new JsonObject();
            for (Profile p : profiles.values()) {
                JsonObject o = new JsonObject();
                o.addProperty("name", p.name());
                o.addProperty("at", p.at());
                o.addProperty("minecraft", p.minecraft());
                o.addProperty("loader", p.loader());
                o.addProperty("launcher", p.launcher());
                o.addProperty("os", p.os());
                o.addProperty("osVersion", p.osVersion());
                o.addProperty("arch", p.arch());
                o.addProperty("java", p.java());
                o.addProperty("cores", p.cores());
                o.addProperty("memoryMb", p.memoryMb());
                com.google.gson.JsonArray mods = new com.google.gson.JsonArray();
                for (Mod m : p.mods()) {
                    JsonObject j = new JsonObject();
                    j.addProperty("id", m.id());
                    j.addProperty("version", m.version());
                    j.addProperty("firstSeen", m.firstSeen());
                    j.addProperty("removedAt", m.removedAt());
                    mods.add(j);
                }
                o.add("mods", mods);
                root.add(p.uuid().toString(), o);
            }
            Path tmp = Files.createTempFile(f.getParent(), ".clients-", ".tmp");
            Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            AlminLog.warn("[almin] could not write clients.json: {}", e.getMessage());
        }
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    private static long num(JsonObject o, String k) {
        try {
            return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsLong() : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
