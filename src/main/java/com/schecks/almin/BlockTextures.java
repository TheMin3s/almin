package com.schecks.almin;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The map, drawn in the game's own textures rather than in sixty flat colours.
 *
 * <h3>Why it is worth it</h3>
 * Minecraft's map palette has about sixty entries, so sand, sandstone, birch
 * planks and bone blocks are one colour between them; a desert village reads as
 * one beige smear. A block's texture knows better, and at one pixel per block
 * there is exactly enough room to use it: the pixel takes the texture's own
 * colour, nudged by the texel that falls at that position, so a sand field
 * comes out grainy the way sand actually is and a floor of planks comes out
 * striped the way planks actually are — from the real files, not from a rule
 * somebody wrote about sand.
 *
 * <h3>Where the textures come from</h3>
 * Not from the server: a dedicated server jar ships no block textures, because
 * nothing on a server ever draws a block. So Almin looks, in order, at
 * {@code config/almin/textures.zip}, {@code config/almin/textures/}, any
 * {@code resourcepacks/*.zip}, and finally its own classpath — which has them
 * when the game is running from a jar that bundles client assets.
 *
 * <p>Any resource pack will do, including the vanilla one, and a pack that only
 * changes some blocks textures those and leaves the rest to the map palette.
 * Finding nothing is not an error: {@link #textured()} stays false and
 * {@link WorldSnapshots} draws what it drew before.
 *
 * <h3>Threading</h3>
 * {@link #colourOf} is called from the server thread, once per pixel, so it
 * never reads a file: a miss returns the fallback colour and queues the load
 * for a daemon thread. The first picture after a restart is the old flat one
 * and every picture after it is textured, which is the right way round — a
 * tick is not the place to open a zip.
 */
public final class BlockTextures {

    /** One block's texture, reduced to what a top-down map can use. */
    private record Skin(int average, int[] texel, boolean tinted) {
        /** The texel at a world position, tiling the texture across the world. */
        int at(int wx, int wz) {
            return texel[((wz & 15) << 4) | (wx & 15)];
        }
    }

    /** Sentinel for "looked, found nothing" so a miss is not retried forever. */
    private static final Skin NONE = new Skin(0, new int[0], false);

    private static final Map<Block, Skin> SKINS = new ConcurrentHashMap<>();

    /** Blocks whose load is already queued, so a busy map cannot queue it twice. */
    private static final Map<Block, Boolean> PENDING = new ConcurrentHashMap<>();

    /** Ceiling on the cache. A world has far fewer distinct blocks than this. */
    private static final int MAX_SKINS = 1200;

    /** Largest texture file worth reading. Block textures are a few hundred bytes. */
    private static final int MAX_TEXTURE_BYTES = 512 * 1024;

    /**
     * How much of the texel shows through.
     *
     * <p>All of it would be honest and unreadable — a top-down map made of raw
     * texels is a field of static, because a texture is meant to be seen at
     * sixteen pixels across and not at one. Most of the way to the block's own
     * colour, with the texel as a deviation from it, keeps the material
     * recognisable and still lets you see the grain.
     */
    private static final float TEXEL = 0.55f;

    /**
     * Below this fraction of opaque pixels a texture is a shape, not a surface.
     *
     * <p>High on purpose. A real surface — stone, planks, sand — is entirely
     * opaque; a cross-shaped plant is about half, which is close enough to a
     * half-empty tile that a laxer threshold let short grass through and
     * averaged a green cross on nothing into a colour of its own.
     */
    private static final float MIN_OPAQUE = 0.9f;

    private static volatile List<Object> sources = List.of();
    private static volatile String describe = "none";
    private static volatile boolean any;

    private static final ThreadPoolExecutor LOADS = new ThreadPoolExecutor(
        0, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(512),
        r -> {
            Thread t = new Thread(r, "Almin-textures");
            t.setDaemon(true);
            return t;
        },
        new ThreadPoolExecutor.DiscardPolicy());

    private BlockTextures() {}

    // ---------- finding a pack ----------

    /**
     * Picks up whatever textures this server happens to have.
     *
     * <p>Cheap: it opens zips to see that they are zips and does not read a
     * texture until something asks for one.
     */
    public static void init(MinecraftServer server) {
        List<Object> found = new ArrayList<>();
        try {
            Path dir = server.getServerDirectory().resolve("config").resolve("almin");
            addZip(found, dir.resolve("textures.zip"));
            addDir(found, dir.resolve("textures"));

            Path packs = server.getServerDirectory().resolve("resourcepacks");
            if (Files.isDirectory(packs)) {
                try (var list = Files.list(packs)) {
                    List<Path> zips = list.filter(p -> p.getFileName().toString()
                            .toLowerCase(Locale.ROOT).endsWith(".zip"))
                        .sorted().toList();
                    for (Path z : zips) addZip(found, z);
                }
                try (var list = Files.list(packs)) {
                    list.filter(Files::isDirectory).sorted().forEach(p -> addDir(found, p));
                }
            }
        } catch (Exception e) {
            AlminLog.warn("[almin] could not look for block textures: {}", e.toString());
        }
        // Last resort, and the one that works in a development run: the jar
        // this is running from, if it happens to carry client assets.
        if (classpathHas()) found.add(BlockTextures.class);

        sources = List.copyOf(found);
        any = !found.isEmpty();
        describe = found.isEmpty() ? "none" : describeSources(found);
        AlminLog.info("[almin] block textures: {}", describe);
    }

    private static String describeSources(List<Object> found) {
        StringBuilder b = new StringBuilder();
        for (Object o : found) {
            if (b.length() > 0) b.append(", ");
            if (o instanceof ZipFile z) b.append(Path.of(z.getName()).getFileName());
            else if (o instanceof Path p) b.append(p.getFileName()).append('/');
            else b.append("this jar");
        }
        return b.toString();
    }

    private static void addZip(List<Object> found, Path zip) {
        if (!Files.isRegularFile(zip)) return;
        try {
            found.add(new ZipFile(zip.toFile()));
        } catch (IOException e) {
            AlminLog.warn("[almin] {} is not a readable zip: {}", zip.getFileName(), e.getMessage());
        }
    }

    private static void addDir(List<Object> found, Path dir) {
        if (Files.isDirectory(dir)) found.add(dir.toAbsolutePath().normalize());
    }

    private static boolean classpathHas() {
        try (InputStream in = BlockTextures.class.getResourceAsStream(
                "/assets/minecraft/textures/block/sand.png")) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    public static void close() {
        for (Object o : sources) {
            if (o instanceof ZipFile z) {
                try { z.close(); } catch (IOException ignored) { /* going away anyway */ }
            }
        }
        sources = List.of();
        SKINS.clear();
        PENDING.clear();
        ITEMS.clear();
        palette = null;
        any = false;
    }

    /** Whether any textures were found. False means the map looks as it always did. */
    public static boolean textured() { return any; }

    /** What was found, for the panel to say. */
    public static String source() { return describe; }

    /** How many blocks have a texture loaded so far. */
    public static int loaded() { return SKINS.size(); }

    // ---------- what a block looks like, by the name the log wrote down ----------

    private static volatile Map<String, Integer> palette;

    /**
     * Every block's colour, keyed by the name the activity log records.
     *
     * <p>The log stores display names — "Oak Planks", not
     * {@code minecraft:oak_planks} — because that is what a person reading a
     * row wants. So the reverse lookup is over display names too, which also
     * means it comes out in whatever language the server runs in and matches
     * the rows either way.
     *
     * <p>Built once and kept: it is a thousand entries and it cannot change
     * while the server is up.
     */
    public static Map<String, Integer> palette() {
        Map<String, Integer> have = palette;
        if (have != null) return have;
        Map<String, Integer> built = new java.util.LinkedHashMap<>();
        try {
            for (Block block : BuiltInRegistries.BLOCK) {
                String name = block.getName().getString();
                if (name.isEmpty() || built.containsKey(name)) continue;
                built.put(name, colourFor(block));
            }
        } catch (Throwable t) {
            AlminLog.warn("[almin] could not read the block palette: {}", t.toString());
        }
        palette = Map.copyOf(built);
        return palette;
    }

    /**
     * One block's colour with no world to ask.
     *
     * <p>The texture's average where there is one, since that is the colour
     * the block actually is; the map palette otherwise, which is the colour
     * the game would draw it on a map. Never the tinted mask: grass and leaves
     * are grey files, and the palette already knows they are green.
     */
    private static int colourFor(Block block) {
        int fallback = 0x7a8595;
        try {
            var map = block.defaultMapColor();
            if (map != null && map != net.minecraft.world.level.material.MapColor.NONE) {
                fallback = map.col;
            }
        } catch (Throwable ignored) {
            // A block that will not say; the grey stands in.
        }
        if (!any) return fallback;
        Skin skin = SKINS.get(block);
        if (skin == null) {
            // Not loaded yet: queue it and answer with the palette this time.
            queue(block, fallback);
            return fallback;
        }
        if (skin == NONE || skin.tinted()) return fallback;
        return skin.average();
    }

    /**
     * An item's texture file, for the tool on a sequence badge.
     *
     * <p>Same sources as the block textures and the same answer when there are
     * none: nothing, and the panel draws its own.
     */
    public static byte[] item(String name) {
        if (!any || name == null) return null;
        if (!name.matches("[a-z0-9_]{1,48}")) return null;
        byte[] cached = ITEMS.get(name);
        if (cached != null) return cached.length == 0 ? null : cached;
        byte[] png = null;
        for (Object source : sources) {
            try {
                png = readFrom(source, "assets/minecraft/textures/item/" + name + ".png");
                if (png != null) break;
            } catch (IOException ignored) {
                // A bad pack is not worth a log line per icon.
            }
        }
        if (ITEMS.size() > 64) ITEMS.clear();
        ITEMS.put(name, png == null ? new byte[0] : png);
        return png;
    }

    private static final Map<String, byte[]> ITEMS = new ConcurrentHashMap<>();

    // ---------- the one thing the server thread calls ----------

    /**
     * The colour for one block at one place.
     *
     * @param fallback the block's flat map colour, used until (or unless) a
     *                 texture for it turns up
     * @return 0xRRGGBB, never with an alpha channel
     */
    public static int colourOf(BlockState state, int fallback, int wx, int wz) {
        if (!any || state == null) return fallback;
        Block block = state.getBlock();
        Skin skin = SKINS.get(block);
        if (skin == null) {
            queue(block, fallback);
            return fallback;
        }
        if (skin == NONE || skin.texel().length != 256) return fallback;

        int avg = skin.average();
        int px = skin.at(wx, wz);
        if (skin.tinted()) {
            // A greyscale texture is a mask the game tints by biome — grass,
            // leaves, water. Nothing here knows the biome, but the map palette
            // already does: it has the right green. So the texture supplies the
            // pattern and the palette supplies the colour.
            int lumTexel = luminance(px), lumAvg = Math.max(1, luminance(avg));
            float k = 1f + TEXEL * ((lumTexel - lumAvg) / (float) lumAvg);
            return scale(fallback, k);
        }
        return blend(avg, px, TEXEL);
    }

    private static void queue(Block block, int fallback) {
        if (SKINS.size() >= MAX_SKINS) return;
        if (PENDING.putIfAbsent(block, Boolean.TRUE) != null) return;
        Identifier id = BuiltInRegistries.BLOCK.getKey(block);
        LOADS.execute(() -> {
            try {
                SKINS.put(block, load(id, fallback));
            } catch (Throwable t) {
                SKINS.put(block, NONE);
            } finally {
                PENDING.remove(block);
            }
        });
    }

    // ---------- reading one ----------

    /**
     * The candidate texture names for a block, best first.
     *
     * <p>Vanilla names a block's texture after the block often enough that two
     * guesses cover most of the game: the top face where a block has one
     * ({@code grass_block_top}, {@code oak_log_top}, {@code furnace_top}) and
     * the plain name where it does not ({@code sand}, {@code oak_planks}).
     * Anything neither guess finds keeps its map colour, which is what it had
     * before, so a miss costs nothing.
     */
    static List<String> candidates(Identifier id) {
        String path = id.getPath();
        List<String> out = new ArrayList<>(4);
        String alias = ALIASES.get(path);
        if (alias != null) out.add(alias);
        out.add(path + "_top");
        out.add(path);
        // Doubles and slabs are cut from another block's texture.
        if (path.endsWith("_slab")) out.add(path.substring(0, path.length() - 5));
        if (path.endsWith("_stairs")) out.add(path.substring(0, path.length() - 7));
        return out;
    }

    /** The handful vanilla does not name after the block. */
    private static final Map<String, String> ALIASES = Map.ofEntries(
        Map.entry("water", "water_still"),
        Map.entry("lava", "lava_still"),
        Map.entry("grass_block", "grass_block_top"),
        Map.entry("dirt_path", "dirt_path_top"),
        Map.entry("farmland", "farmland"),
        Map.entry("snow_block", "snow"),
        Map.entry("powder_snow", "powder_snow"),
        Map.entry("short_grass", "grass_block_top"),
        Map.entry("tall_grass", "grass_block_top"),
        Map.entry("mangrove_roots", "mangrove_roots_top"),
        Map.entry("cut_copper", "cut_copper"),
        Map.entry("nether_portal", "nether_portal"),
        Map.entry("redstone_wire", "redstone_dust_dot"),
        Map.entry("cobweb", "cobweb"));

    private static Skin load(Identifier id, int fallback) {
        for (String name : candidates(id)) {
            byte[] png = read(id.getNamespace(), name);
            if (png == null) continue;
            Skin skin = reduce(png, fallback);
            if (skin != null) return skin;
        }
        return NONE;
    }

    /** The bytes of {@code assets/<ns>/textures/block/<name>.png}, or null. */
    private static byte[] read(String namespace, String name) {
        if (!name.matches("[a-z0-9_/.-]{1,96}") || name.contains("..")) return null;
        String rel = "assets/" + namespace + "/textures/block/" + name + ".png";
        for (Object source : sources) {
            try {
                byte[] bytes = readFrom(source, rel);
                if (bytes != null) return bytes;
            } catch (IOException ignored) {
                // A bad pack is not worth a log line per texture.
            }
        }
        return null;
    }

    private static byte[] readFrom(Object source, String rel) throws IOException {
        if (source instanceof ZipFile zip) {
            ZipEntry entry = zip.getEntry(rel);
            if (entry == null || entry.getSize() > MAX_TEXTURE_BYTES) return null;
            try (InputStream in = zip.getInputStream(entry)) {
                return in.readNBytes(MAX_TEXTURE_BYTES);
            }
        }
        if (source instanceof Path dir) {
            Path file = dir.resolve(rel).normalize();
            if (!file.startsWith(dir)) return null;               // a pack cannot escape itself
            if (!Files.isRegularFile(file)) return null;
            if (Files.size(file) > MAX_TEXTURE_BYTES) return null;
            return Files.readAllBytes(file);
        }
        try (InputStream in = BlockTextures.class.getResourceAsStream("/" + rel)) {
            return in == null ? null : in.readNBytes(MAX_TEXTURE_BYTES);
        }
    }

    /**
     * A texture file, reduced to an average and a 16×16 grid.
     *
     * <p>Animated textures ({@code water_still}) are a column of frames; only
     * the first is taken. Textures that are mostly transparent are shapes
     * rather than surfaces — a plant, a pane, a torch — and are refused, since
     * a cross of pixels averaged into one is not a colour anyone would
     * recognise.
     *
     * @return null if the file is not usable as a surface
     */
    static Skin reduce(byte[] png, int fallback) {
        Png.Image img;
        try {
            img = Png.decode(png);
        } catch (IOException e) {
            return null;
        }
        int w = img.width(), h = img.height();
        if (w < 2 || h < 2) return null;
        // One frame of an animation, and the frame is square.
        int frame = Math.min(w, h);

        long r = 0, g = 0, b = 0;
        int opaque = 0;
        int[] grid = new int[256];
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int sx = x * frame / 16, sy = y * frame / 16;
                int argb = img.at(sx, sy);
                grid[(y << 4) | x] = argb & 0xFFFFFF;
                if ((argb >>> 24) < 128) continue;
                opaque++;
                r += (argb >> 16) & 0xFF;
                g += (argb >> 8) & 0xFF;
                b += argb & 0xFF;
            }
        }
        if (opaque < 256 * MIN_OPAQUE) return null;
        int average = (int) ((r / opaque) << 16 | (g / opaque) << 8 | (b / opaque));

        // Any transparent texel takes the average, so a few holes in an
        // otherwise solid texture do not read as black specks.
        for (int i = 0; i < grid.length; i++) {
            if (((img.at((i & 15) * frame / 16, (i >> 4) * frame / 16)) >>> 24) < 128) {
                grid[i] = average;
            }
        }
        return new Skin(average, grid, greyish(average) && !greyish(fallback));
    }

    // ---------- colour arithmetic ----------

    /** Whether a colour has so little hue that it is a tint mask rather than a colour. */
    static boolean greyish(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        return max - min <= 24;
    }

    static int luminance(int rgb) {
        return (((rgb >> 16) & 0xFF) * 77 + ((rgb >> 8) & 0xFF) * 150 + (rgb & 0xFF) * 29) >> 8;
    }

    static int blend(int base, int over, float k) {
        int r = clamp(((base >> 16) & 0xFF) + Math.round((((over >> 16) & 0xFF)
            - ((base >> 16) & 0xFF)) * k));
        int g = clamp(((base >> 8) & 0xFF) + Math.round((((over >> 8) & 0xFF)
            - ((base >> 8) & 0xFF)) * k));
        int b = clamp((base & 0xFF) + Math.round(((over & 0xFF) - (base & 0xFF)) * k));
        return r << 16 | g << 8 | b;
    }

    static int scale(int rgb, float k) {
        return clamp(Math.round(((rgb >> 16) & 0xFF) * k)) << 16
             | clamp(Math.round(((rgb >> 8) & 0xFF) * k)) << 8
             | clamp(Math.round((rgb & 0xFF) * k));
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }
}
