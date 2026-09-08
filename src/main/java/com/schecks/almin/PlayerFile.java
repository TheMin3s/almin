package com.schecks.almin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * What the world itself remembers about one player.
 *
 * <h3>Why this exists</h3>
 * Almin's own record is a log of what it saw happen. Minecraft keeps two more
 * that are nothing to do with it: a statistics file per player, counting
 * everything they have ever done, and a save file holding what they are
 * carrying. Both are already on the disk, both answer questions an admin
 * actually asks — "how long have they played", "what did they take from that
 * chest" — and neither was reachable from the panel except by opening a
 * binary in the file browser.
 *
 * <h3>Online and offline are the same question</h3>
 * A player who logged off an hour ago is exactly the player an admin wants to
 * look up, so nothing here requires them to be connected. Statistics come from
 * the JSON file either way, flushed first when the player is online so the
 * numbers are current. What they are carrying comes from the live inventory
 * when there is one and from {@code playerdata/<uuid>.dat} when there is not.
 *
 * <h3>What it does not do</h3>
 * It does not write. Nothing here can change a statistic, move an item or
 * touch a save file, and there is deliberately no method that could: reading
 * somebody's things is intrusive enough on its own, and an admin who wants to
 * take an item has a game they can do it in, where the player can see them.
 */
public final class PlayerFile {

    /** Big enough for a player save, small enough that a corrupt one cannot eat the heap. */
    private static final long NBT_QUOTA = 16L * 1024 * 1024;

    /** A statistics file past this size is not one. */
    private static final long MAX_STATS_BYTES = 8L * 1024 * 1024;

    private PlayerFile() {}

    // ---------- what comes back ----------

    /** One stack, wherever it was found. */
    public record Item(int slot, String where, String id, String name, int count) {}

    /** What somebody is carrying. */
    public record Gear(boolean live, long at, List<Item> items) {
        public static Gear none() { return new Gear(false, 0, List.of()); }
        public boolean any() { return !items.isEmpty(); }
    }

    /** One counted thing, in the game's own words where they are known. */
    public record Count(String id, String name, long value) {}

    /**
     * A player's statistics, in the shape the panel draws.
     *
     * @param headline the handful worth a tile: time played, deaths, kills
     * @param mined    the blocks they have broken most of
     * @param used     the items they have used most
     * @param crafted  what they have made most of
     * @param killed   what they have killed most of
     */
    public record Stats(boolean found, long at, List<Count> headline, List<Count> mined,
                        List<Count> used, List<Count> crafted, List<Count> killed) {
        public static Stats none() {
            return new Stats(false, 0, List.of(), List.of(), List.of(), List.of(), List.of());
        }
    }

    // ---------- where ----------

    /** The world folder, or null when there is no server to ask. */
    public static Path worldDir(MinecraftServer server) {
        if (server == null) return null;
        try {
            return server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static Path statsFile(MinecraftServer server, UUID id) {
        Path world = worldDir(server);
        return world == null || id == null ? null : world.resolve("stats")
            .resolve(id + ".json");
    }

    public static Path dataFile(MinecraftServer server, UUID id) {
        Path world = worldDir(server);
        return world == null || id == null ? null : world.resolve("playerdata")
            .resolve(id + ".dat");
    }

    // ---------- statistics ----------

    /**
     * One player's statistics.
     *
     * <p>Server thread, because an online player's counters live in memory and
     * are only written out now and then; asking them to save first is the
     * difference between "two hours" and "two hours as of whenever the server
     * last got round to it".
     */
    public static Stats stats(MinecraftServer server, UUID id) {
        if (server == null || id == null) return Stats.none();
        ServerPlayer live = server.getPlayerList().getPlayer(id);
        if (live != null) {
            try { live.getStats().save(); }
            catch (RuntimeException e) {
                AlminLog.warn("[almin] could not flush stats for {}: {}", id, e.getMessage());
            }
        }
        Path f = statsFile(server, id);
        if (f == null || !Files.isRegularFile(f)) return Stats.none();
        try {
            if (Files.size(f) > MAX_STATS_BYTES) return Stats.none();
            long at = Files.getLastModifiedTime(f).toMillis();
            JsonObject root = JsonParser.parseString(
                Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            return read(root, at);
        } catch (IOException | RuntimeException e) {
            AlminLog.warn("[almin] could not read stats for {}: {}", id, e.getMessage());
            return Stats.none();
        }
    }

    /** The same, against a parsed file, so this can be tested without a world. */
    public static Stats read(JsonObject root, long at) {
        JsonObject all = obj(root, "stats");
        JsonObject custom = obj(all, "minecraft:custom");

        List<Count> headline = new ArrayList<>();
        headline.add(new Count("minecraft:play_time", "Time played", num(custom, "minecraft:play_time")));
        headline.add(new Count("minecraft:deaths", "Deaths", num(custom, "minecraft:deaths")));
        headline.add(new Count("minecraft:mob_kills", "Mobs killed", num(custom, "minecraft:mob_kills")));
        headline.add(new Count("minecraft:player_kills", "Players killed",
            num(custom, "minecraft:player_kills")));
        headline.add(new Count("minecraft:walk_one_cm", "Distance walked",
            num(custom, "minecraft:walk_one_cm")));
        headline.add(new Count("minecraft:jump", "Jumps", num(custom, "minecraft:jump")));
        headline.add(new Count("minecraft:damage_dealt", "Damage dealt",
            num(custom, "minecraft:damage_dealt")));
        headline.add(new Count("minecraft:damage_taken", "Damage taken",
            num(custom, "minecraft:damage_taken")));

        return new Stats(true, at, headline,
            top(obj(all, "minecraft:mined")), top(obj(all, "minecraft:used")),
            top(obj(all, "minecraft:crafted")), top(obj(all, "minecraft:killed")));
    }

    /** How many of each kind of "what they did most of" is worth showing. */
    private static final int TOP = 8;

    private static List<Count> top(JsonObject o) {
        List<Count> out = new ArrayList<>();
        if (o == null) return out;
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            long v = value(e.getValue());
            if (v > 0) out.add(new Count(e.getKey(), pretty(e.getKey()), v));
        }
        out.sort(Comparator.comparingLong(Count::value).reversed()
            .thenComparing(Count::id));
        return out.size() <= TOP ? out : new ArrayList<>(out.subList(0, TOP));
    }

    private static JsonObject obj(JsonObject o, String k) {
        if (o == null || !o.has(k) || !o.get(k).isJsonObject()) return new JsonObject();
        return o.getAsJsonObject(k);
    }

    private static long num(JsonObject o, String k) {
        return o != null && o.has(k) ? value(o.get(k)) : 0L;
    }

    private static long value(JsonElement e) {
        try {
            return e != null && e.isJsonPrimitive() ? e.getAsLong() : 0L;
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    /** "minecraft:polished_blackstone_bricks" is a thing with a name. */
    public static String pretty(String id) {
        if (id == null || id.isEmpty()) return "";
        String s = id.substring(id.indexOf(':') + 1).replace('_', ' ').trim();
        if (s.isEmpty()) return id;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ---------- what they are carrying ----------

    /**
     * What one player has on them.
     *
     * <p>Server thread. An online player is read from the live inventory,
     * because their save file is as old as their last autosave and an admin
     * looking now wants to know what is in the hotbar now. Everybody else is
     * read from that save file, which is the whole point: the question is
     * usually asked about somebody who has just logged off.
     */
    public static Gear inventory(MinecraftServer server, UUID id) {
        if (server == null || id == null) return Gear.none();
        ServerPlayer live = server.getPlayerList().getPlayer(id);
        if (live != null) return liveGear(live);
        return savedGear(dataFile(server, id));
    }

    private static Gear liveGear(ServerPlayer p) {
        List<Item> items = new ArrayList<>();
        try {
            Inventory inv = p.getInventory();
            // The grid only. Whether armour and the off hand also live at the
            // end of this container has changed between versions, and reading
            // them twice would draw somebody's helmet in two places at once.
            int grid = Math.min(36, inv.getContainerSize());
            for (int i = 0; i < grid; i++) add(items, i, where(i), inv.getItem(i));
            for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                    EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND}) {
                add(items, -1, slot.getName().toLowerCase(Locale.ROOT), p.getItemBySlot(slot));
            }
            var ender = p.getEnderChestInventory();
            for (int i = 0; i < ender.getContainerSize(); i++) {
                add(items, i, "ender chest", ender.getItem(i));
            }
        } catch (RuntimeException e) {
            AlminLog.warn("[almin] could not read a live inventory: {}", e.getMessage());
        }
        return new Gear(true, System.currentTimeMillis(), items);
    }

    private static void add(List<Item> into, int slot, String where, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        String id = "";
        try {
            id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        } catch (RuntimeException ignored) {
            // A modded item whose registry entry has gone; the name still works.
        }
        String name;
        try { name = stack.getHoverName().getString(); }
        catch (RuntimeException e) { name = pretty(id); }
        into.add(new Item(slot, where, id, name, stack.getCount()));
    }

    /**
     * Which part of the inventory screen a slot number belongs to.
     *
     * <p>The numbering is the game's own and is not contiguous: the grid is
     * 0-35, armour is 100-103 counting up from the feet, and the off hand is
     * -106. A save file written by an older server uses exactly these, so they
     * are read rather than guessed at; anything else is "carried", which is
     * vague on purpose — a wrong specific answer would put somebody's sword in
     * the wrong square.
     */
    public static String where(int slot) {
        if (slot == OFFHAND_SLOT) return "offhand";
        if (slot >= 0 && slot < 9) return "hotbar";
        if (slot >= 9 && slot < 36) return "pack";
        if (slot >= 100 && slot <= 103) return ARMOUR[slot - 100];
        return "carried";
    }

    /** Feet first, the way the save file counts them. */
    private static final String[] ARMOUR = {"feet", "legs", "chest", "head"};
    private static final int OFFHAND_SLOT = -106;

    public static Gear savedGear(Path dat) {
        if (dat == null || !Files.isRegularFile(dat)) return Gear.none();
        List<Item> items = new ArrayList<>();
        long at;
        try {
            at = Files.getLastModifiedTime(dat).toMillis();
            CompoundTag root = NbtIo.readCompressed(dat, NbtAccounter.create(NBT_QUOTA));
            readList(items, root, "Inventory", null);
            readList(items, root, "EnderItems", "ender chest");
            readEquipment(items, root);
        } catch (IOException | RuntimeException e) {
            AlminLog.warn("[almin] could not read {}: {}", dat.getFileName(), e.getMessage());
            return Gear.none();
        }
        return new Gear(false, at, items);
    }

    /**
     * Armour and the off hand, where a modern save file keeps them.
     *
     * <p>They used to be slots 100-103 and -106 inside {@code Inventory} and
     * on newer servers they are a compound of their own. Both are read: a
     * world is not always as new as the server that opened it, and an empty
     * compound costs nothing.
     */
    private static void readEquipment(List<Item> into, CompoundTag root) {
        CompoundTag worn = root.getCompound("equipment").orElse(null);
        if (worn == null) return;
        for (String slot : new String[]{"head", "chest", "legs", "feet", "offhand"}) {
            CompoundTag t = worn.getCompound(slot).orElse(null);
            if (t == null) continue;
            String id = t.getStringOr("id", "");
            if (id.isEmpty()) continue;
            int count = t.getIntOr("count", t.getIntOr("Count", 1));
            into.add(new Item(-1, slot, id, pretty(id), count));
        }
    }

    private static void readList(List<Item> into, CompoundTag root, String key, String where) {
        ListTag list = root.getList(key).orElse(null);
        if (list == null) return;
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompoundOrEmpty(i);
            String id = t.getStringOr("id", "");
            if (id.isEmpty()) continue;
            // 1.20.5 renamed both of these; the old spellings are still what a
            // world written by an older server has in it.
            int count = t.getIntOr("count", t.getIntOr("Count", 1));
            int slot = t.getIntOr("Slot", (int) t.getByteOr("Slot", (byte) -1));
            into.add(new Item(slot, where != null ? where : where(slot), id, pretty(id), count));
        }
    }
}
