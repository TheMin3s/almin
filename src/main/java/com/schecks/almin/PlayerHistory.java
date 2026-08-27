package com.schecks.almin;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the server remembers about every account that has joined it: the name
 * it was last seen under, when it first and last appeared, how many times it
 * has joined, and how long it has played.
 *
 * <p>The name half is load-bearing — the admin tools resolve names to UUIDs for
 * players who aren't online right now ({@code /almin op add|remove},
 * {@code /almin mask set|clear}), and the mask conflict check needs to know
 * which names belong to real accounts. The rest feeds the player-history panel
 * of {@code /almin}.
 *
 * <p>Under the mod's old name this map lived in {@code lifesmp:lives}, mixed in
 * with each player's life count. {@link #get} falls back to that file once, so
 * a world upgrading from LifeSMP keeps its name history instead of forgetting
 * every offline player; the lives fields in those old records are ignored, and
 * the names are re-saved under the current id.
 */
public class PlayerHistory extends SavedData {

    /**
     * One account's record. Timestamps are epoch millis; 0 means "never" — the
     * case for entries carried over from LifeSMP, which only knew the name.
     */
    public record Entry(String name, long firstSeen, long lastSeen, int joins, long playtimeMillis) {
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("name").orElse("").forGetter(Entry::name),
            Codec.LONG.fieldOf("first_seen").orElse(0L).forGetter(Entry::firstSeen),
            Codec.LONG.fieldOf("last_seen").orElse(0L).forGetter(Entry::lastSeen),
            Codec.INT.fieldOf("joins").orElse(0).forGetter(Entry::joins),
            Codec.LONG.fieldOf("playtime_ms").orElse(0L).forGetter(Entry::playtimeMillis)
        ).apply(i, Entry::new));

        static Entry empty() { return new Entry("", 0L, 0L, 0, 0L); }
    }

    public static final Codec<PlayerHistory> CODEC = Codec.unboundedMap(
        UUIDUtil.STRING_CODEC, Entry.CODEC
    ).xmap(PlayerHistory::new, d -> d.entries);

    public static final SavedDataType<PlayerHistory> TYPE = new SavedDataType<>(
        Identifier.parse("almin:player_history"),
        PlayerHistory::new,
        CODEC,
        null
    );

    /** Pre-rename location of this data — read once, on first use. */
    private static final SavedDataType<PlayerHistory> LEGACY_TYPE = new SavedDataType<>(
        Identifier.parse("lifesmp:lives"),
        PlayerHistory::new,
        CODEC,
        null
    );

    /**
     * When each online player's current session began (epoch millis). In-memory
     * only: a session that ends in a crash contributes no playtime rather than
     * a bogus one.
     */
    private static final Map<UUID, Long> SESSION_START = new ConcurrentHashMap<>();

    private final Map<UUID, Entry> entries;

    public PlayerHistory() {
        this.entries = new HashMap<>();
    }

    public PlayerHistory(Map<UUID, Entry> entries) {
        this.entries = new HashMap<>(entries);
    }

    public static PlayerHistory get(MinecraftServer server) {
        PlayerHistory current = server.overworld().getDataStorage().computeIfAbsent(TYPE);
        if (current.entries.isEmpty()) {
            // Either a brand-new world or one that still has its names under the
            // pre-rename id. Adopt whatever the old file holds; if there's no old
            // file this reads an empty map and costs one lookup, once.
            PlayerHistory legacy = server.overworld().getDataStorage().computeIfAbsent(LEGACY_TYPE);
            if (!legacy.entries.isEmpty()) {
                current.entries.putAll(legacy.entries);
                current.setDirty();
                AlminLog.info("[almin] carried {} player record(s) over from the LifeSMP save data",
                    legacy.entries.size());
            }
        }
        return current;
    }

    /** Records a join: refreshes the name, bumps the counter, starts the session clock. */
    public void recordJoin(UUID id, String name) {
        if (name == null || name.isEmpty()) return;
        long now = System.currentTimeMillis();
        Entry old = entries.getOrDefault(id, Entry.empty());
        entries.put(id, new Entry(
            name,
            old.firstSeen() == 0L ? now : old.firstSeen(),
            now,
            old.joins() + 1,
            old.playtimeMillis()
        ));
        SESSION_START.put(id, now);
        setDirty();
    }

    /** Records a disconnect: banks the session's playtime and stamps last-seen. */
    public void recordLeave(UUID id) {
        Long started = SESSION_START.remove(id);
        Entry old = entries.get(id);
        if (old == null) return;
        long now = System.currentTimeMillis();
        long session = started == null ? 0L : Math.max(0L, now - started);
        entries.put(id, new Entry(
            old.name(), old.firstSeen(), now, old.joins(), old.playtimeMillis() + session
        ));
        setDirty();
    }

    /** Millis the given player has been online this session, or 0 if not online. */
    public static long sessionLength(UUID id) {
        Long started = SESSION_START.get(id);
        return started == null ? 0L : Math.max(0L, System.currentTimeMillis() - started);
    }

    /** The name {@code id} was last seen under, or "" if never seen. */
    public String nameOf(UUID id) {
        Entry e = entries.get(id);
        return e == null ? "" : e.name();
    }

    /** The UUID last seen under {@code name} (case-insensitive), or null. */
    public UUID findByName(String name) {
        for (Map.Entry<UUID, Entry> e : entries.entrySet()) {
            if (e.getValue().name().equalsIgnoreCase(name)) {
                return e.getKey();
            }
        }
        return null;
    }

    /** How many accounts this server has ever seen. */
    public int knownCount() {
        return entries.size();
    }

    /** A read-only copy of every record. */
    public Map<UUID, Entry> snapshot() {
        return new LinkedHashMap<>(entries);
    }
}
