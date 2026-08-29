package com.schecks.almin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pictures of the ground, taken every so often, so the activity map has a
 * world under it instead of a grid.
 *
 * <h3>What it is</h3>
 * A top-down raster of the loaded area around whoever is playing — the same
 * idea as a vanilla map, but shaded the way the web world maps are: for each
 * column, the top block's map colour, lit by the slope of the ground under it,
 * darkened by the depth of any water over it, and given a fixed grain so a
 * material reads as itself. Taken on a timer and kept with a timestamp, so the
 * map can show the world as it was at the moment the timeline points at, and a
 * build appears as you scrub forward.
 *
 * <h3>What it is not</h3>
 * Not a world renderer and not a complete map. Only chunks the server already
 * has loaded are drawn; anything else stays transparent, because generating
 * terrain to photograph it would be an enormous cost for a picture nobody
 * asked for. So the picture is of where people are, which is the part the
 * activity log is about anyway.
 *
 * <h3>Cost</h3>
 * Two halves. Sampling has to happen on the server thread — block states
 * belong to it — so it is bounded by {@code map-radius} and
 * {@code map-blocks-per-pixel} and skips unloaded chunks without touching
 * them. Encoding and writing the PNG then happen on a daemon thread, because
 * neither needs the world and neither should cost a tick.
 *
 * <h3>Lifetime</h3>
 * Snapshots expire on the same clock as the activity log, and are capped by
 * count as well. They are pictures of where people were, so they are not
 * allowed to accumulate any more than the log is.
 */
public final class WorldSnapshots {
    private static final org.slf4j.Logger CONSOLE = org.slf4j.LoggerFactory.getLogger("almin");

    /**
     * One picture: when, where, and how to place it back on a map.
     *
     * @param base the timestamp of the keyframe this one is a difference
     *             against, or 0 if it is a whole picture in its own right
     */
    public record Shot(long at, String dim, int minX, int minZ, int blocks, int scale,
                       long base, String file) {
        /** Width of the covered area in blocks. Square, so this is both sides. */
        public int span() { return blocks; }
        /** Whether this file holds the whole picture rather than a difference. */
        public boolean whole() { return base == 0; }
    }

    private static final List<Shot> shots = new CopyOnWriteArrayList<>();

    private static volatile Path dir;
    private static ExecutorService writer;
    private static int tickCounter;
    private static volatile boolean busy;

    private WorldSnapshots() {}

    // ---------- lifecycle ----------

    public static synchronized void init(MinecraftServer server) {
        if (writer != null) return;
        dir = server.getServerDirectory().resolve("config").resolve("almin").resolve("map");
        writer = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Almin-map");
            t.setDaemon(true);
            return t;
        });
        adopt();
    }

    public static synchronized void close() {
        if (writer != null) {
            writer.shutdown();
            writer = null;
        }
    }

    /**
     * Picks up whatever last run left on disk, so the map is not blank for the
     * first half-minute after a restart. Filenames carry everything needed to
     * place a picture, which is why they look the way they do.
     */
    private static void adopt() {
        Path d = dir;
        if (d == null || !Files.isDirectory(d)) return;
        try (var files = Files.list(d)) {
            files.forEach(f -> {
                Shot s = parse(f.getFileName().toString());
                if (s != null) shots.add(s);
            });
        } catch (IOException e) {
            AlminLog.warn("[almin] could not read old map snapshots: {}", e.toString());
        }
        shots.sort(Comparator.comparingLong(Shot::at));
        prune();
        AlminLog.info("[almin] adopted {} map snapshot(s)", shots.size());
    }

    /**
     * {@code <dim>@<at>@<minX>@<minZ>@<blocks>@<scale>@<base>.png}
     *
     * <p>Separated by {@code @} rather than an underscore, because dimension
     * names contain underscores — {@code the_nether} split into two fields and
     * every Nether snapshot was unreadable after a restart.
     *
     * <p>The last field is the difference base, which older files do not have;
     * six fields still parse, as a whole picture.
     */
    private static String name(Shot s) {
        return s.dim() + "@" + s.at() + "@" + s.minX() + "@" + s.minZ()
            + "@" + s.blocks() + "@" + s.scale() + "@" + s.base() + ".png";
    }

    private static Shot parse(String file) {
        if (!file.endsWith(".png")) return null;
        String[] p = file.substring(0, file.length() - 4).split("@");
        if (p.length != 6 && p.length != 7) return null;
        try {
            long base = p.length == 7 ? Long.parseLong(p[6]) : 0L;
            return new Shot(Long.parseLong(p[1]), p[0], Integer.parseInt(p[2]),
                Integer.parseInt(p[3]), Integer.parseInt(p[4]), Integer.parseInt(p[5]),
                base, file);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---------- taking one ----------

    /**
     * Called every tick; takes a picture on its own schedule.
     *
     * <p>Skips while one is still being written, so a slow disk delays the
     * next picture rather than queueing an unbounded number of them.
     */
    public static void tick(MinecraftServer server) {
        AlminConfig cfg = AlminConfig.get();
        int seconds = cfg.mapSnapshotSeconds;
        if (!cfg.activityLog || seconds <= 0 || writer == null) return;
        if (++tickCounter < seconds * 20) return;
        tickCounter = 0;
        if (busy) return;
        try {
            capture(server, cfg);
        } catch (Throwable t) {
            AlminLog.warn("[almin] map snapshot failed: {}", t.toString());
        }
    }

    /**
     * Samples the ground around whoever is playing.
     *
     * <p>Centred on the players actually being recorded, in whichever
     * dimension has the most of them — the map is about what they are doing,
     * so the picture follows them rather than the world origin.
     */
    private static void capture(MinecraftServer server, AlminConfig cfg) {
        ServerLevel level = busiestLevel(server);
        if (level == null) return;

        List<ServerPlayer> here = new ArrayList<>();
        for (ServerPlayer p : level.players()) {
            if (ActivityLog.watched(p)) here.add(p);
        }
        if (here.isEmpty()) return;

        long sumX = 0, sumZ = 0;
        for (ServerPlayer p : here) { sumX += p.getBlockX(); sumZ += p.getBlockZ(); }
        int cx = (int) (sumX / here.size());
        int cz = (int) (sumZ / here.size());

        int scale = Math.max(1, cfg.mapBlocksPerPixel);
        int blocks = Math.max(scale * 16, cfg.mapRadius * 2);
        int size = blocks / scale;
        // Snapped to a grid rather than centred exactly on the players. Two
        // reasons, and the second is the one that matters: the ground stops
        // sliding a few blocks sideways every half minute as people wander,
        // and — because consecutive pictures now cover exactly the same
        // squares — the next one can be stored as the difference from this
        // one instead of as another copy of the world.
        int minX = Math.floorDiv(cx - blocks / 2, GRID) * GRID;
        int minZ = Math.floorDiv(cz - blocks / 2, GRID) * GRID;

        int[] pixels = new int[size * size];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        LevelChunk chunk = null;
        int chunkX = Integer.MIN_VALUE, chunkZ = Integer.MIN_VALUE;
        boolean anything = false;

        for (int py = 0; py < size; py++) {
            int wz = minZ + py * scale;
            for (int px = 0; px < size; px++) {
                int wx = minX + px * scale;
                int cxi = wx >> 4, czi = wz >> 4;
                if (cxi != chunkX || czi != chunkZ) {
                    chunk = level.getChunkSource().getChunkNow(cxi, czi);
                    chunkX = cxi;
                    chunkZ = czi;
                }
                if (chunk == null) continue;                 // not loaded: stays transparent
                int color = column(level, chunk, pos, wx, wz, scale);
                if (color != 0) {
                    pixels[py * size + px] = color;
                    anything = true;
                }
            }
        }
        if (!anything) return;

        String dim = level.dimension().identifier().getPath();
        store(dim, System.currentTimeMillis(), minX, minZ, blocks, scale, pixels, size);
    }

    // ---------- keeping only what changed ----------

    /**
     * The last whole picture written for a dimension, kept so the next one can
     * be stored as the difference from it.
     *
     * <p>In memory only. After a restart there is nothing to compare against,
     * so the next picture is a whole one — which is also the only thing that
     * would be correct, since a difference is meaningless without the frame it
     * differs from.
     */
    private record Frame(int minX, int minZ, int blocks, int scale, long at, int[] pixels) {
        boolean covers(int x, int z, int b, int sc) {
            return minX == x && minZ == z && blocks == b && scale == sc;
        }
    }

    private static final Map<String, Frame> keyframes = new ConcurrentHashMap<>();

    /**
     * Capture windows are aligned to this, in blocks, so that a player
     * wandering inside one produces pictures of exactly the same square.
     */
    private static final int GRID = 64;

    /**
     * Past this fraction of pixels changed, a difference is no longer worth
     * having: it is nearly a whole picture, and it costs a second file and a
     * composition step to read.
     */
    private static final double REDRAW_AT = 0.45;

    /** And past this long, a fresh whole picture regardless, so a chain cannot outlive its use. */
    private static final long KEYFRAME_MS = 30 * 60_000L;

    /**
     * Files a difference, or a whole picture when a difference would not pay.
     *
     * <p>Differences are always against the keyframe rather than against the
     * picture before them, so reading any snapshot costs two files and never a
     * chain of forty. On a server where the world is mostly standing still
     * that difference is a few hundred changed pixels out of a hundred and
     * fifty thousand, which is the whole point: the ground barely changes, so
     * storing it again every half minute is storing the same picture over and
     * over.
     *
     * <p>One thing a difference cannot say is "this column went back to being
     * unknown", because transparent means "unchanged" here. A pixel that was
     * seen once keeps its last known colour, which is the better answer anyway
     * — the map showing what it saw beats it forgetting.
     */
    private static void store(String dim, long at, int minX, int minZ, int blocks, int scale,
                              int[] pixels, int size) {
        Frame frame = keyframes.get(dim);
        boolean sameGround = frame != null && frame.covers(minX, minZ, blocks, scale)
            && frame.pixels().length == pixels.length
            && at - frame.at() < KEYFRAME_MS;

        if (sameGround) {
            int[] delta = new int[pixels.length];
            int changed = 0;
            for (int i = 0; i < pixels.length; i++) {
                if (pixels[i] != 0 && pixels[i] != frame.pixels()[i]) {
                    delta[i] = pixels[i];
                    changed++;
                }
            }
            if (changed == 0) return;                 // nothing moved; nothing to keep
            if (changed < pixels.length * REDRAW_AT) {
                write(new Shot(at, dim, minX, minZ, blocks, scale, frame.at(), ""), delta, size);
                return;
            }
        }

        keyframes.put(dim, new Frame(minX, minZ, blocks, scale, at, pixels.clone()));
        write(new Shot(at, dim, minX, minZ, blocks, scale, 0L, ""), pixels, size);
    }

    /**
     * One column's colour.
     *
     * <p>Vanilla's map gives every block one flat colour out of a small
     * palette and picks between three brightnesses, which is why a vanilla map
     * reads as blotches: a beach and a desert are the same yellow, and a
     * hillside only shows at all if it happens to face north.
     *
     * <p>This does what the web world maps do instead — relief from the slope
     * in both directions rather than one, water darkened by how deep it is,
     * and a fixed per-block grain over the top so sand looks grainy and planks
     * look like planks. All of it is a function of the position and the block,
     * so the same column is the same pixel in every snapshot and the texture
     * does not crawl between them.
     *
     * <p>This half is the part that needs the world. The arithmetic is in
     * {@link #shadeColumn}, which does not, and can therefore be looked at
     * without a running server.
     */
    private static int column(ServerLevel level, LevelChunk chunk,
                              BlockPos.MutableBlockPos pos, int wx, int wz, int scale) {
        int surface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, wx & 15, wz & 15);

        // Down past anything with no colour of its own. A glass roof, a
        // barrier, a light block or a piece of scaffolding is the top of the
        // heightmap and answers NONE, and taking that as the answer punched
        // holes in the map wherever anyone had built with glass — the ground
        // was there, it just was not the block being asked.
        BlockState state = null;
        MapColor color = null;
        int top = surface;
        for (int drop = 0; drop < SEE_THROUGH && top > level.getMinY(); drop++, top--) {
            pos.set(wx, top - 1, wz);
            state = chunk.getBlockState(pos);
            color = state.getMapColor(level, pos);
            if (color != null && color != MapColor.NONE) break;
            color = null;
        }
        if (color == null) return 0;

        // Anything sitting on top of solid ground — water, mostly. The depth
        // is what turns a flat blue sheet into a coastline.
        int floor = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR, wx & 15, wz & 15);
        int depth = Math.max(0, surface - floor);

        // Relief is taken from the ground, not from the surface of the sea:
        // shading the water by its own (flat) top would erase the seabed and
        // the coast with it.
        boolean wet = depth > 0;
        int here = wet ? floor : surface;
        int north = groundAt(level, chunk, wx, wz - scale, wet);
        int west = groundAt(level, chunk, wx - scale, wz, wet);

        int base = BlockTextures.colourOf(state, color.col, wx, wz);
        return shadeColumn(base, wx, wz, here, north, west, depth,
            BlockTextures.textured() ? PLAIN : family(color));
    }

    /**
     * How far down to look for a block that has a colour.
     *
     * <p>Enough to see through a glass roof and the room under it, not enough
     * to turn a deep shaft into a floor sample.
     */
    private static final int SEE_THROUGH = 24;

    // ---------- the arithmetic, with no world in it ----------

    /** What a block is made of, as far as how it should be textured goes. */
    static final int PLAIN = 0, GRAINY = 1, PLANKED = 2, LEAFY = 3, ROCKY = 4;

    /**
     * Colour for one column: base colour, relief, water depth, grain.
     *
     * @param base   the block's flat map colour, 0xRRGGBB
     * @param here   ground height at this column
     * @param north  ground height one sample north, or {@code MIN_VALUE}
     * @param west   ground height one sample west, or {@code MIN_VALUE}
     * @param depth  blocks of fluid standing on the ground here, 0 on land
     * @param family one of {@link #PLAIN}…{@link #ROCKY}
     */
    static int shadeColumn(int base, int wx, int wz, int here, int north, int west,
                           int depth, int family) {
        // A slope, not three steps. The light comes from the north-west, which
        // is the convention every map people already read uses.
        float lum = 1f;
        if (north != Integer.MIN_VALUE) lum += 0.085f * clamp(here - north, -4, 4);
        if (west != Integer.MIN_VALUE)  lum += 0.050f * clamp(here - west, -4, 4);
        lum = Math.max(0.52f, Math.min(1.34f, lum));

        int r = (base >> 16) & 0xFF, g = (base >> 8) & 0xFF, b = base & 0xFF;

        if (depth > 0) {
            // Deep water goes dark and blue rather than merely dark, so the
            // drop-off at a shelf is visible.
            float d = Math.min(1f, depth / 22f);
            lum *= 1f - 0.42f * d;
            b = Math.round(b + (150 - b) * 0.35f * d);
        }

        lum *= 1f + grain(wx, wz, family, depth > 0);
        return 0xFF000000 | (shade(r, lum) << 16) | (shade(g, lum) << 8) | shade(b, lum);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    private static int shade(int channel, float lum) {
        int v = Math.round(channel * lum);
        return v < 0 ? 0 : Math.min(v, 255);
    }

    /**
     * The per-block variation that makes a material look like itself.
     *
     * <p>Two octaves: fine speckle, plus a coarser patchiness at four blocks,
     * because pure per-pixel static reads as a broken screen rather than as
     * ground. How much of each, and whether it runs in stripes, depends on
     * what the block is — planks get a grain along one axis, sand gets an even
     * speckle, grass gets patches.
     *
     * <p>Kept small on purpose. It has to be enough to see and not enough to
     * change what colour something is — a builder chose that wool, and it
     * should still be that wool — and every bit of noise added here is a bit
     * PNG cannot compress, paid for on every snapshot.
     */
    static float grain(int x, int z, int family, boolean fluid) {
        if (fluid) {
            // Water: almost nothing, and what there is runs in bands, so it
            // reads as a surface rather than as gravel.
            return 0.020f * hash(x >> 1, z >> 3, 3);
        }
        float fine = hash(x, z, 1);
        float patch = hash(x >> 2, z >> 2, 2);
        return switch (family) {
            // A plank runs one way. The stripe is the grain; the noise stops
            // every plank from being identical.
            case PLANKED -> (((z & 3) == 0) ? -0.055f : ((z & 3) == 2 ? 0.030f : 0f))
                            + 0.030f * fine;
            case GRAINY  -> 0.038f * fine + 0.020f * patch;
            // Grass is patchy at a few blocks, not at one.
            case LEAFY   -> 0.028f * fine + 0.055f * patch;
            case ROCKY   -> 0.034f * fine + 0.038f * patch;
            default      -> 0.014f * fine + 0.010f * patch;
        };
    }

    /** Deterministic −0.5…0.5 from a position. Same block, same speckle, always. */
    static float hash(int x, int z, int salt) {
        int h = x * 374761393 + z * 668265263 + salt * 1274126177;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return ((h >>> 16) & 0xFFFF) / 65535.0f - 0.5f;
    }

    /** Which grain a map colour gets. Colour is all a top-down map has to go on. */
    private static int family(MapColor color) {
        if (color == MapColor.WOOD || color == MapColor.PODZOL
            || color == MapColor.COLOR_BROWN) return PLANKED;
        if (color == MapColor.SAND || color == MapColor.QUARTZ || color == MapColor.SNOW
            || color == MapColor.CLAY || color == MapColor.TERRACOTTA_WHITE) return GRAINY;
        if (color == MapColor.GRASS || color == MapColor.PLANT
            || color == MapColor.COLOR_GREEN) return LEAFY;
        if (color == MapColor.STONE || color == MapColor.METAL || color == MapColor.DIRT
            || color == MapColor.COLOR_GRAY || color == MapColor.COLOR_LIGHT_GRAY) return ROCKY;
        // Wool, terracotta, ore blocks — things people built with, left alone.
        return PLAIN;
    }

    /**
     * The height used for relief at a neighbouring column: the seabed under
     * water, the surface on land. {@code MIN_VALUE} if that chunk isn't loaded.
     */
    private static int groundAt(ServerLevel level, LevelChunk near, int wx, int wz, boolean fluid) {
        LevelChunk chunk = near.getPos().getMinBlockX() >> 4 == (wx >> 4)
                && near.getPos().getMinBlockZ() >> 4 == (wz >> 4)
            ? near : level.getChunkSource().getChunkNow(wx >> 4, wz >> 4);
        if (chunk == null) return Integer.MIN_VALUE;
        Heightmap.Types type = fluid ? Heightmap.Types.OCEAN_FLOOR : Heightmap.Types.WORLD_SURFACE;
        return chunk.getHeight(type, wx & 15, wz & 15);
    }

    /** The dimension with the most recorded players in it. */
    private static ServerLevel busiestLevel(MinecraftServer server) {
        ServerLevel best = null;
        int most = 0;
        for (ServerLevel level : server.getAllLevels()) {
            int n = 0;
            for (ServerPlayer p : level.players()) if (ActivityLog.watched(p)) n++;
            if (n > most) { most = n; best = level; }
        }
        return best;
    }

    // ---------- writing and keeping ----------

    private static void write(Shot shot, int[] pixels, int size) {
        ExecutorService pool = writer;
        Path d = dir;
        if (pool == null || d == null) return;
        busy = true;
        pool.execute(() -> {
            try {
                Files.createDirectories(d);
                String file = name(shot);
                Files.write(d.resolve(file), Png.encode(pixels, size, size));
                shots.add(new Shot(shot.at(), shot.dim(), shot.minX(), shot.minZ(),
                    shot.blocks(), shot.scale(), shot.base(), file));
                prune();
            } catch (Throwable t) {
                AlminLog.warn("[almin] could not save a map snapshot: {}", t.toString());
                CONSOLE.warn("[almin] map snapshot could not be saved: {}", t.toString());
            } finally {
                busy = false;
            }
        });
    }

    /**
     * Drops snapshots that are too old or too many.
     *
     * <p>They are pictures of where people were, so they expire on the same
     * clock as the activity log; the count cap is what stops a long retention
     * window from filling a disk.
     */
    public static synchronized void prune() {
        long cutoff = System.currentTimeMillis() - ActivityLog.retentionMillis();
        int keep = Math.max(2, AlminConfig.get().mapSnapshotKeep);
        List<Shot> ordered = new ArrayList<>(shots);
        ordered.sort(Comparator.comparingLong(Shot::at));

        List<Shot> doomed = new ArrayList<>();
        for (Shot s : ordered) if (s.at() < cutoff) doomed.add(s);
        int over = (ordered.size() - doomed.size()) - keep;
        for (int i = 0; i < ordered.size() && over > 0; i++) {
            Shot s = ordered.get(i);
            if (doomed.contains(s)) continue;
            doomed.add(s);
            over--;
        }

        // A whole picture that something else is a difference against has to
        // outlive it, or the survivor becomes unreadable. It goes on the next
        // pass, once the last thing depending on it has gone.
        List<Shot> keeping = new ArrayList<>(ordered);
        keeping.removeAll(doomed);
        doomed.removeIf(s -> s.whole() && dependedOn(s, keeping));

        for (Shot s : doomed) {
            shots.remove(s);
            delete(s);
            // The next capture must not be filed as a difference against a
            // picture that has just been deleted.
            Frame held = keyframes.get(s.dim());
            if (held != null && held.at() == s.at()) keyframes.remove(s.dim());
            COMPOSED.remove(s.file());
        }
    }

    /** Whether any surviving snapshot is stored as a difference against this one. */
    private static boolean dependedOn(Shot base, List<Shot> surviving) {
        for (Shot s : surviving) {
            if (s.base() == base.at() && s.dim().equals(base.dim())) return true;
        }
        return false;
    }

    private static void delete(Shot s) {
        Path d = dir;
        if (d == null || s.file().isEmpty()) return;
        try {
            Files.deleteIfExists(d.resolve(s.file()));
        } catch (IOException ignored) {
            // A file we could not delete is only clutter.
        }
    }

    /** Everything currently held, oldest first. */
    public static List<Shot> all() {
        List<Shot> out = new ArrayList<>(shots);
        out.sort(Comparator.comparingLong(Shot::at));
        return out;
    }

    /**
     * The newest picture of {@code dim} taken at or before {@code at}, or the
     * oldest one there is when the cursor sits before them all — a map with an
     * approximately-right world under it beats an empty grid.
     */
    public static Shot at(String dim, long at) {
        Shot best = null, earliest = null;
        for (Shot s : shots) {
            if (dim != null && !dim.isEmpty() && !s.dim().equals(dim)) continue;
            if (earliest == null || s.at() < earliest.at()) earliest = s;
            if (s.at() <= at && (best == null || s.at() > best.at())) best = s;
        }
        return best != null ? best : earliest;
    }

    /**
     * The bytes of one snapshot as a whole picture, or null if it has since
     * been pruned.
     *
     * <p>A snapshot stored as a difference is put back together here — its
     * keyframe, with the changed pixels painted over it — so that what leaves
     * this class is always an ordinary PNG and nothing downstream has to know
     * that differences exist.
     */
    public static byte[] read(Shot shot) {
        if (shot == null) return null;
        if (shot.whole()) return bytes(shot);

        byte[] cached = COMPOSED.get(shot.file());
        if (cached != null) return cached;

        Shot base = baseOf(shot);
        byte[] baseBytes = base == null ? null : bytes(base);
        byte[] deltaBytes = bytes(shot);
        if (baseBytes == null || deltaBytes == null) return null;
        try {
            Png.Image whole = Png.decode(baseBytes);
            Png.Image delta = Png.decode(deltaBytes);
            if (whole.width() != delta.width() || whole.height() != delta.height()) return null;
            int[] px = whole.argb().clone();
            int[] over = delta.argb();
            for (int i = 0; i < px.length; i++) {
                // Transparent means "unchanged" — see the note on store().
                if ((over[i] >>> 24) != 0) px[i] = over[i];
            }
            byte[] out = Png.encode(px, whole.width(), whole.height());
            synchronized (COMPOSED) {
                if (COMPOSED.size() >= COMPOSED_MAX) {
                    var it = COMPOSED.keySet().iterator();
                    it.next();
                    it.remove();
                }
                COMPOSED.put(shot.file(), out);
            }
            return out;
        } catch (IOException e) {
            AlminLog.warn("[almin] could not rebuild map snapshot {}: {}", shot.file(), e.toString());
            return null;
        }
    }

    /**
     * A few rebuilt pictures, so scrubbing back and forth over the same moment
     * does not decode and re-encode it every time. Small on purpose: the
     * browser is told to cache these for an hour and does most of the work.
     */
    private static final int COMPOSED_MAX = 6;
    private static final Map<String, byte[]> COMPOSED =
        java.util.Collections.synchronizedMap(new LinkedHashMap<>());

    /** The whole picture a difference is against, or null if it is gone. */
    private static Shot baseOf(Shot shot) {
        for (Shot s : shots) {
            if (s.at() == shot.base() && s.dim().equals(shot.dim()) && s.whole()) return s;
        }
        return null;
    }

    private static byte[] bytes(Shot shot) {
        Path d = dir;
        if (d == null || shot == null || shot.file().isEmpty()) return null;
        try {
            Path f = d.resolve(shot.file()).normalize();
            // The filename comes from our own index, but resolve() would still
            // honour a traversal if one ever got into it.
            if (!f.startsWith(d.normalize())) return null;
            return Files.isRegularFile(f) ? Files.readAllBytes(f) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Deletes every snapshot — called when the activity log is cleared. */
    public static synchronized void clear() {
        for (Shot s : new ArrayList<>(shots)) delete(s);
        shots.clear();
        keyframes.clear();
        COMPOSED.clear();
    }
}
