import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * The map drawn in the game's own textures, next to the map drawn in the map
 * palette. Same synthetic world as TerrainLook, so the two are comparable.
 *
 * Exercises the real BlockTextures.reduce / blend / scale on the real texture
 * files out of the merged Minecraft jar, then runs the result through the real
 * shading and the real PNG encoder.
 */
public class TextureLook {
    static final int N = TerrainLook.N;
    static Class<?> BT;
    static Method reduce, blend, scale, luminance, greyish;
    static int failures = 0;

    static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failures++;
    }

    public static void main(String[] a) throws Exception {
        Path jar = Paths.get(a[0]);
        Path out = Paths.get(a[1]);
        BT = Class.forName("com.schecks.almin.BlockTextures");
        reduce = BT.getDeclaredMethod("reduce", byte[].class, int.class);
        blend = BT.getDeclaredMethod("blend", int.class, int.class, float.class);
        scale = BT.getDeclaredMethod("scale", int.class, float.class);
        luminance = BT.getDeclaredMethod("luminance", int.class);
        greyish = BT.getDeclaredMethod("greyish", int.class);
        for (Method m : new Method[]{reduce, blend, scale, luminance, greyish}) m.setAccessible(true);

        ZipFile zip = new ZipFile(jar.toFile());

        // ---- what reduce() makes of real files ----
        Object sand = skin(zip, "sand", TerrainLook.SAND);
        check("sand.png reduces to a surface", sand != null);
        Object grass = skin(zip, "grass_block_top", TerrainLook.GRASS);
        check("grass_block_top.png reduces to a surface", grass != null);
        check("  and is recognised as a tint mask", grass != null && tinted(grass));
        check("sand is not a tint mask", sand != null && !tinted(sand));
        Object planks = skin(zip, "oak_planks", 0x9A814D);
        check("oak_planks.png reduces to a surface", planks != null);
        check("  and its average is a plausible oak", planks != null && plausible(avg(planks)));

        // A cross-shaped plant is a shape, not a surface, and must be refused.
        check("short_grass.png is refused as a surface", skin(zip, "short_grass", 0) == null);
        check("glass.png is refused as a surface", skin(zip, "glass", 0) == null);

        // Texel variation is what makes the grain; a flat texture would have none.
        check("sand has real per-texel variation", sand != null && spread(sand) > 6);
        check("stone has real per-texel variation",
            spread(skin(zip, "stone", TerrainLook.STONE)) > 6);

        // ---- render the same world both ways ----
        TerrainLook.build();
        Map<Integer, Object> skins = new HashMap<>();
        skins.put(TerrainLook.GRASS, grass);
        skins.put(TerrainLook.SAND, sand);
        skins.put(TerrainLook.STONE, skin(zip, "stone", TerrainLook.STONE));
        skins.put(TerrainLook.WOOD, planks);
        skins.put(TerrainLook.DIRT, skin(zip, "dirt", TerrainLook.DIRT));
        skins.put(TerrainLook.SNOW, skin(zip, "snow", TerrainLook.SNOW));
        skins.put(TerrainLook.WATER, skin(zip, "water_still", TerrainLook.WATER));

        Class<?> ws = Class.forName("com.schecks.almin.WorldSnapshots");
        Method shade = ws.getDeclaredMethod("shadeColumn", int.class, int.class, int.class,
            int.class, int.class, int.class, int.class, int.class);
        shade.setAccessible(true);

        int[] px = new int[N * N];
        for (int z = 0; z < N; z++) {
            for (int x = 0; x < N; x++) {
                int i = z * N + x;
                int fallback = TerrainLook.base[i];
                Object sk = skins.get(fallback);
                int colour = sk == null ? fallback : colourOf(sk, fallback, x, z);
                int north = z > 0 ? TerrainLook.height[i - N] : Integer.MIN_VALUE;
                int west = x > 0 ? TerrainLook.height[i - 1] : Integer.MIN_VALUE;
                // family PLAIN: with a texture there is no need for invented grain.
                px[i] = (Integer) shade.invoke(null, colour, x, z, TerrainLook.height[i],
                    north, west, TerrainLook.depth[i], 0);
            }
        }
        Class<?> png = Class.forName("com.schecks.almin.Png");
        Method enc = png.getDeclaredMethod("encode", int[].class, int.class, int.class);
        enc.setAccessible(true);
        byte[] bytes = (byte[]) enc.invoke(null, px, N, N);
        Files.write(out.resolve("terrain-textured.png"), bytes);
        System.out.printf("textured: %,d bytes%n", bytes.length);
        Set<Integer> distinct = new HashSet<>();
        for (int v : px) distinct.add(v);
        System.out.printf("distinct colours: %,d%n", distinct.size());

        zip.close();
        System.out.println(failures == 0 ? "TEXTURES OK" : "TEXTURE FAILURES: " + failures);
        if (failures > 0) System.exit(1);
    }

    /** The same arithmetic colourOf() does, with the skin already in hand. */
    static int colourOf(Object sk, int fallback, int wx, int wz) throws Exception {
        int[] texel = (int[]) get(sk, "texel");
        int average = (Integer) get(sk, "average");
        int p = texel[((wz & 15) << 4) | (wx & 15)];
        if (tinted(sk)) {
            int lt = (Integer) luminance.invoke(null, p);
            int la = Math.max(1, (Integer) luminance.invoke(null, average));
            return (Integer) scale.invoke(null, fallback, 1f + 0.55f * ((lt - la) / (float) la));
        }
        return (Integer) blend.invoke(null, average, p, 0.55f);
    }

    static Object skin(ZipFile zip, String name, int fallback) throws Exception {
        ZipEntry e = zip.getEntry("assets/minecraft/textures/block/" + name + ".png");
        if (e == null) throw new IllegalStateException("no such texture: " + name);
        byte[] b;
        try (var in = zip.getInputStream(e)) { b = in.readAllBytes(); }
        return reduce.invoke(null, b, fallback);
    }

    static Object get(Object rec, String field) throws Exception {
        Method m = rec.getClass().getDeclaredMethod(field);
        m.setAccessible(true);
        return m.invoke(rec);
    }
    static boolean tinted(Object sk) throws Exception { return (Boolean) get(sk, "tinted"); }
    static int avg(Object sk) throws Exception { return (Integer) get(sk, "average"); }

    /** Largest luminance gap inside one texture — the grain the map will show. */
    static int spread(Object sk) throws Exception {
        int[] t = (int[]) get(sk, "texel");
        int lo = 999, hi = -1;
        for (int v : t) {
            int l = (Integer) luminance.invoke(null, v);
            lo = Math.min(lo, l); hi = Math.max(hi, l);
        }
        return hi - lo;
    }

    static boolean plausible(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        return r > g && g > b && r > 120 && b < 140;
    }
}
