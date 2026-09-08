package com.schecks.almin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the server is spending its time on.
 *
 * <h3>The question this answers</h3>
 * The dashboard already says <em>how</em> the server is doing: ticks are
 * taking 71ms, memory is at 84%. Neither number tells anybody what to do
 * about it. The reason a Minecraft server is slow is almost never mysterious
 * — it is nine thousand items on a hopper floor, or a villager hall somebody
 * left running, or a chunk loader in the Nether that has been ticking a mob
 * farm for a week with nobody near it — but finding out which of those it is
 * means running commands and guessing at coordinates.
 *
 * <p>So this samples the running server and reports the things a person can
 * actually go and change: what there is a lot of, and <em>where</em>. Every
 * hotspot comes out with coordinates on it, because "4,812 items" is a fact
 * and "4,812 items at -211, 64, 908 in the overworld" is a decision.
 *
 * <h3>Why it is sampled rather than read</h3>
 * Entities and chunks belong to the server thread. Walking them from an HTTP
 * handler is a data race with the tick, so a sample is taken on the tick
 * itself and the handler reads whatever the last one produced.
 *
 * <p>Sampling is not free — it is one pass over every entity and every ticking
 * chunk — so it does not happen unless somebody is looking. {@link #want()}
 * says somebody is; the tick honours it at most once every
 * {@link #MIN_GAP_MILLIS} and then goes quiet again on its own once the panel
 * stops asking. A server nobody is watching does no work here at all.
 *
 * <h3>What it deliberately does not do</h3>
 * There is no per-entity timing. Attributing milliseconds to individual
 * entities means instrumenting the tick loop, which costs something on every
 * tick forever in exchange for a number that is only ever read for a minute at
 * a time. Counts and positions answer the same question — an admin who is
 * shown where the entities are does not also need to be told that entities
 * cost time.
 */
public final class ServerLoad {

    /** Shortest time between two samples, however often the panel asks. */
    private static final long MIN_GAP_MILLIS = 4_000L;

    /** How long a request keeps the sampler awake after the last one. */
    private static final long INTEREST_MILLIS = 45_000L;

    /** Chunks across one hotspot bucket: 64 blocks, about a farm. */
    private static final int SPOT_CHUNKS = 4;

    /** How many of each list survives into the answer. */
    private static final int TOP = 12;

    /** Below this a hotspot is just where somebody happens to be standing. */
    private static final int SPOT_FLOOR = 40;

    /** Blocks around a player counted as theirs, for the per-player list. */
    private static final int NEAR_BLOCKS = 128;

    private ServerLoad() {}

    // ---------- what a sample says ----------

    /** One kind of thing, and how much of it there is. */
    public record Kind(String id, String name, int count, String dim,
                       int x, int y, int z) {}

    /** Somewhere with a lot packed into it. */
    public record Spot(String dim, int x, int z, int entities, int blockEntities,
                       String mostly, int chunks) {}

    /** One world's share. */
    public record Dim(String id, int chunks, int forced, int entities,
                      int blockEntities) {}

    /** What is loaded around one player, which is what they cost. */
    public record Near(String player, String uuid, String dim,
                       int entities, int blockEntities) {}

    /**
     * One pass over the running server.
     *
     * @param at      when it was taken
     * @param took    how long the pass itself cost, in milliseconds
     * @param mspt    the server's own average tick time
     * @param worst   the slowest of the last hundred ticks
     * @param recent  those hundred ticks, in milliseconds, oldest first
     */
    public record Sample(long at, long took,
                         double mspt, double tps, double target, double worst,
                         double[] recent,
                         long heapUsed, long heapMax, long gcCount, long gcMillis,
                         int players, int entities, int blockEntities,
                         int chunks, int forced,
                         List<Kind> kinds, List<Kind> blockKinds,
                         List<Spot> spots, List<Dim> dims, List<Near> near) {}

    private static volatile Sample latest;
    private static volatile long wantedAt;
    private static volatile long lastAt;

    /** Says somebody is looking, so the next tick takes a sample. */
    public static void want() {
        wantedAt = System.currentTimeMillis();
    }

    /** The last sample, or {@code null} when none has been taken yet. */
    public static Sample latest() {
        return latest;
    }

    /** Forgets what was measured. For a server stop, and for the tests. */
    public static void reset() {
        latest = null;
        wantedAt = 0L;
        lastAt = 0L;
    }

    /**
     * Takes a sample when one is wanted and one is due.
     *
     * <p>Registered on the end of the tick, like everything else that needs
     * the server thread. Costs one field read per tick while nobody is
     * looking.
     */
    public static void tick(MinecraftServer server) {
        long now = System.currentTimeMillis();
        if (now - wantedAt > INTEREST_MILLIS) return;
        if (now - lastAt < MIN_GAP_MILLIS) return;
        lastAt = now;
        try {
            latest = sample(server);
        } catch (Throwable t) {
            // A sample is a convenience; failing one must not take the tick
            // down with it. The panel goes on showing the previous one and
            // says how old it is.
            AlminLog.warn("[almin] load sample failed: {}", t.toString());
        }
    }

    // ---------- the pass ----------

    /** One walk over the worlds. Server thread only. */
    static Sample sample(MinecraftServer server) {
        long began = System.nanoTime();
        long at = System.currentTimeMillis();

        Map<String, int[]> byKind = new HashMap<>();        // id -> {count}
        Map<String, String> kindName = new HashMap<>();
        Map<String, int[]> byBlockKind = new HashMap<>();
        Map<String, String> blockKindName = new HashMap<>();
        Map<String, Kind> kindWhere = new HashMap<>();      // biggest cluster seen
        Map<String, Bucket> buckets = new HashMap<>();
        List<Dim> dims = new ArrayList<>();

        int entities = 0, blockEntities = 0, chunks = 0, forced = 0;

        for (ServerLevel level : server.getAllLevels()) {
            String dim = level.dimension().identifier().getPath();
            int dimEntities = 0, dimBlocks = 0;

            for (Entity e : level.getAllEntities()) {
                if (e.isRemoved()) continue;
                dimEntities++;
                EntityType<?> type = e.getType();
                String id = BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath();
                byKind.computeIfAbsent(id, k -> new int[1])[0]++;
                kindName.computeIfAbsent(id, k -> type.getDescription().getString());
                bucketOf(buckets, dim, e.chunkPosition()).add(id, e.blockPosition());
            }

            // Ticking chunks are the ones that cost something. A chunk that is
            // loaded but not ticking has a block entity in it that is not
            // running, and reporting it as load would send somebody to pull up
            // a hopper that was already asleep.
            List<LevelChunk> ticking = new ArrayList<>();
            level.getChunkSource().chunkMap.forEachBlockTickingChunk(ticking::add);
            for (LevelChunk chunk : ticking) {
                for (Map.Entry<BlockPos, BlockEntity> be : chunk.getBlockEntities().entrySet()) {
                    dimBlocks++;
                    BlockEntity block = be.getValue();
                    var key = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(block.getType());
                    String id = key == null ? "unknown" : key.getPath();
                    byBlockKind.computeIfAbsent(id, k -> new int[1])[0]++;
                    blockKindName.computeIfAbsent(id,
                        k -> block.getBlockState().getBlock().getName().getString());
                    bucketOf(buckets, dim, chunk.getPos()).addBlock(be.getKey());
                }
            }

            int loaded = level.getChunkSource().getLoadedChunksCount();
            int forcedHere = level.getForceLoadedChunks().size();
            dims.add(new Dim(dim, loaded, forcedHere, dimEntities, dimBlocks));
            entities += dimEntities;
            blockEntities += dimBlocks;
            chunks += loaded;
            forced += forcedHere;
        }

        // Where each kind is thickest, so a row in the list is somewhere to go
        // rather than only a number.
        for (Bucket b : buckets.values()) {
            for (Map.Entry<String, int[]> e : b.kinds.entrySet()) {
                Kind had = kindWhere.get(e.getKey());
                if (had != null && had.count() >= e.getValue()[0]) continue;
                kindWhere.put(e.getKey(), new Kind(e.getKey(), "", e.getValue()[0],
                    b.dim, b.midX(), b.midY(), b.midZ()));
            }
        }

        List<Kind> kinds = rank(byKind, kindName, kindWhere);
        List<Kind> blockKinds = rank(byBlockKind, blockKindName, Map.of());

        List<Spot> spots = new ArrayList<>();
        for (Bucket b : buckets.values()) {
            int total = b.entities + b.blocks;
            if (total < SPOT_FLOOR) continue;
            spots.add(new Spot(b.dim, b.midX(), b.midZ(),
                b.entities, b.blocks, kindName.getOrDefault(b.mostly(), b.mostly()),
                b.chunks.size()));
        }
        spots.sort(Comparator.comparingInt((Spot s) -> -(s.entities() + s.blockEntities()))
            .thenComparing(Spot::dim).thenComparingInt(Spot::x).thenComparingInt(Spot::z));
        if (spots.size() > TOP) spots = new ArrayList<>(spots.subList(0, TOP));

        dims.sort(Comparator.comparingInt((Dim d) -> -(d.entities() + d.blockEntities())));

        List<Near> near = near(server);

        double[] recent = unroll(server.getTickTimesNanos(), server.getTickCount());
        double worst = 0;
        for (double d : recent) worst = Math.max(worst, d);
        double mspt = server.getAverageTickTimeNanos() / 1_000_000.0;
        double target = server.tickRateManager().tickrate();
        double tps = mspt <= 0 ? target : Math.min(target, 1000.0 / mspt);

        Runtime rt = Runtime.getRuntime();
        long heapUsed = rt.totalMemory() - rt.freeMemory();
        long gcCount = 0, gcMillis = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (gc.getCollectionCount() > 0) gcCount += gc.getCollectionCount();
            if (gc.getCollectionTime() > 0) gcMillis += gc.getCollectionTime();
        }

        long took = (System.nanoTime() - began) / 1_000_000L;
        return new Sample(at, took, mspt, tps, target, worst, recent,
            heapUsed, rt.maxMemory(), gcCount, gcMillis,
            server.getPlayerList().getPlayerCount(), entities, blockEntities, chunks, forced,
            kinds, blockKinds, spots, dims, near);
    }

    /**
     * The last hundred ticks, in milliseconds, oldest first.
     *
     * <p>The array the server hands over is a ring it writes into, so the end
     * of it is not the end of the history — put in order the wrong way, a
     * chart of it shows a spike wherever the ring happens to have wrapped,
     * which is a spike that never happened.
     *
     * <p>The server increments its tick counter and then writes that slot, so
     * the newest sample sits at {@code tickCount % length} and the oldest is
     * the next one round.
     */
    public static double[] unroll(long[] nanos, int tickCount) {
        if (nanos == null || nanos.length == 0) return new double[0];
        int size = nanos.length;
        int newest = Math.floorMod(tickCount, size);
        double[] all = new double[size];
        int kept = 0;
        for (int i = 0; i < size; i++) {
            long v = nanos[Math.floorMod(newest + 1 + i, size)];
            // A tick cannot take no time at all, so a zero is a slot nothing
            // has been written to yet — a server less than five seconds old.
            // They are all at the front, because that is where the ring has
            // not reached.
            if (v > 0) all[kept++] = v / 1_000_000.0;
        }
        return kept == size ? all : java.util.Arrays.copyOf(all, kept);
    }

    /** What is loaded around each player: the closest thing to a per-person bill. */
    private static List<Near> near(MinecraftServer server) {
        List<Near> out = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            ServerLevel level = p.level() instanceof ServerLevel s ? s : null;
            if (level == null) continue;
            BlockPos at = p.blockPosition();
            int entities = 0;
            for (Entity e : level.getAllEntities()) {
                if (e.isRemoved()) continue;
                if (Math.abs(e.blockPosition().getX() - at.getX()) > NEAR_BLOCKS) continue;
                if (Math.abs(e.blockPosition().getZ() - at.getZ()) > NEAR_BLOCKS) continue;
                entities++;
            }
            int blocks = 0;
            int radius = NEAR_BLOCKS / 16;
            ChunkPos here = p.chunkPosition();
            int hx = chunkX(here), hz = chunkZ(here);
            for (int cx = hx - radius; cx <= hx + radius; cx++) {
                for (int cz = hz - radius; cz <= hz + radius; cz++) {
                    LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                    if (chunk != null) blocks += chunk.getBlockEntities().size();
                }
            }
            out.add(new Near(p.getGameProfile().name(), p.getUUID().toString(),
                level.dimension().identifier().getPath(), entities, blocks));
        }
        out.sort(Comparator.comparingInt((Near n) -> -(n.entities() + n.blockEntities()))
            .thenComparing(Near::player));
        return out;
    }

    /** Biggest first, trimmed, with the name and the thickest cluster attached. */
    private static List<Kind> rank(Map<String, int[]> counts, Map<String, String> names,
                                   Map<String, Kind> where) {
        List<Kind> out = new ArrayList<>();
        for (Map.Entry<String, int[]> e : counts.entrySet()) {
            Kind spot = where.get(e.getKey());
            out.add(new Kind(e.getKey(), names.getOrDefault(e.getKey(), e.getKey()),
                e.getValue()[0],
                spot == null ? "" : spot.dim(),
                spot == null ? 0 : spot.x(), spot == null ? 0 : spot.y(),
                spot == null ? 0 : spot.z()));
        }
        out.sort(Comparator.comparingInt((Kind k) -> -k.count()).thenComparing(Kind::id));
        return out.size() > TOP ? new ArrayList<>(out.subList(0, TOP)) : out;
    }

    // ---------- buckets ----------

    private static Bucket bucketOf(Map<String, Bucket> all, String dim, ChunkPos pos) {
        int bx = Math.floorDiv(chunkX(pos), SPOT_CHUNKS);
        int bz = Math.floorDiv(chunkZ(pos), SPOT_CHUNKS);
        return all.computeIfAbsent(dim + " " + bx + " " + bz, k -> new Bucket(dim));
    }

    // ChunkPos keeps its own coordinates to itself in this mapping; the block
    // corner is public and is sixteen times the chunk.
    private static int chunkX(ChunkPos pos) { return pos.getMinBlockX() >> 4; }

    private static int chunkZ(ChunkPos pos) { return pos.getMinBlockZ() >> 4; }

    /**
     * One patch of ground, and everything that landed in it.
     *
     * <p>The centre is the mean of what was counted rather than the middle of
     * the bucket: a farm that straddles a boundary should send somebody to the
     * farm, not to the corner of an arbitrary grid square.
     */
    private static final class Bucket {
        final String dim;
        final Map<String, int[]> kinds = new HashMap<>();
        final java.util.Set<Long> chunks = new java.util.HashSet<>();
        int entities, blocks, n;
        long sumX, sumY, sumZ;

        Bucket(String dim) { this.dim = dim; }

        void add(String id, BlockPos at) {
            entities++;
            kinds.computeIfAbsent(id, k -> new int[1])[0]++;
            note(at);
        }

        void addBlock(BlockPos at) {
            blocks++;
            note(at);
        }

        private void note(BlockPos at) {
            n++;
            sumX += at.getX();
            sumY += at.getY();
            sumZ += at.getZ();
            chunks.add(ChunkPos.pack(at.getX() >> 4, at.getZ() >> 4));
        }

        int midX() { return (int) (sumX / Math.max(1, n)); }

        int midY() { return (int) (sumY / Math.max(1, n)); }

        int midZ() { return (int) (sumZ / Math.max(1, n)); }

        /** Whichever kind there is most of here, for the one-line description. */
        String mostly() {
            String best = "";
            int most = 0;
            for (Map.Entry<String, int[]> e : kinds.entrySet()) {
                if (e.getValue()[0] > most || (e.getValue()[0] == most
                    && e.getKey().compareTo(best) < 0)) {
                    most = e.getValue()[0];
                    best = e.getKey();
                }
            }
            return best;
        }
    }
}
