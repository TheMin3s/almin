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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pictures of the ground, taken every so often, so the activity map has a
 * world under it instead of a grid.
 *
 * <h3>What it is</h3>
 * A top-down raster of the loaded area around whoever is playing — the same
 * idea as a vanilla map: for each column, the top block's map colour, shaded
 * by whether the ground rises or falls going north. Taken on a timer and kept
 * with a timestamp, so the map can show the world as it was at the moment the
 * timeline points at, and a build appears as you scrub forward.
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

    /** One picture: when, where, and how to place it back on a map. */
    public record Shot(long at, String dim, int minX, int minZ, int blocks, int scale,
                       String file) {
        /** Width of the covered area in blocks. Square, so this is both sides. */
        public int span() { return blocks; }
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
     * {@code <dim>@<at>@<minX>@<minZ>@<blocks>@<scale>.png}
     *
     * <p>Separated by {@code @} rather than an underscore, because dimension
     * names contain underscores — {@code the_nether} split into two fields and
     * every Nether snapshot was unreadable after a restart.
     */
    private static String name(Shot s) {
        return s.dim() + "@" + s.at() + "@" + s.minX() + "@" + s.minZ()
            + "@" + s.blocks() + "@" + s.scale() + ".png";
    }

    private static Shot parse(String file) {
        if (!file.endsWith(".png")) return null;
        String[] p = file.substring(0, file.length() - 4).split("@");
        if (p.length != 6) return null;
        try {
            return new Shot(Long.parseLong(p[1]), p[0], Integer.parseInt(p[2]),
                Integer.parseInt(p[3]), Integer.parseInt(p[4]), Integer.parseInt(p[5]), file);
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
        int minX = cx - blocks / 2;
        int minZ = cz - blocks / 2;

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

        Shot shot = new Shot(System.currentTimeMillis(),
            level.dimension().identifier().getPath(), minX, minZ, blocks, scale, "");
        write(shot, pixels, size);
    }

    /**
     * One column's colour: the top block's map colour, shaded by the step up
     * or down to the column behind it.
     *
     * <p>The same trick vanilla maps use, and the reason terrain reads as
     * terrain rather than as flat blotches — a hillside is only visible
     * because its north face is darker.
     */
    private static int column(ServerLevel level, LevelChunk chunk,
                              BlockPos.MutableBlockPos pos, int wx, int wz, int scale) {
        int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, wx & 15, wz & 15);
        pos.set(wx, y - 1, wz);
        BlockState state = chunk.getBlockState(pos);
        MapColor color = state.getMapColor(level, pos);
        if (color == null || color == MapColor.NONE) return 0;

        int north = heightAt(level, chunk, wx, wz - scale);
        MapColor.Brightness brightness = north == Integer.MIN_VALUE ? MapColor.Brightness.NORMAL
            : y > north ? MapColor.Brightness.HIGH
            : y < north ? MapColor.Brightness.LOW
            : MapColor.Brightness.NORMAL;
        return color.calculateARGBColor(brightness);
    }

    /** Surface height at a column, or {@code MIN_VALUE} if it isn't loaded. */
    private static int heightAt(ServerLevel level, LevelChunk near, int wx, int wz) {
        LevelChunk chunk = near.getPos().getMinBlockX() >> 4 == (wx >> 4)
                && near.getPos().getMinBlockZ() >> 4 == (wz >> 4)
            ? near : level.getChunkSource().getChunkNow(wx >> 4, wz >> 4);
        if (chunk == null) return Integer.MIN_VALUE;
        return chunk.getHeight(Heightmap.Types.WORLD_SURFACE, wx & 15, wz & 15);
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
                Shot named = new Shot(shot.at(), shot.dim(), shot.minX(), shot.minZ(),
                    shot.blocks(), shot.scale(), "");
                String file = name(named);
                Files.write(d.resolve(file), Png.encode(pixels, size, size));
                shots.add(new Shot(shot.at(), shot.dim(), shot.minX(), shot.minZ(),
                    shot.blocks(), shot.scale(), file));
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
        for (Shot s : doomed) {
            shots.remove(s);
            delete(s);
        }
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

    /** The bytes of one snapshot, or null if it has since been pruned. */
    public static byte[] read(Shot shot) {
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
    }
}
