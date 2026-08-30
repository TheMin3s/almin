import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;

/**
 * What the new snapshot shading actually looks like, and what it costs.
 *
 * Builds a plausible world — hills, a lake, a beach, a plank floor someone
 * laid — runs it through WorldSnapshots.shadeColumn, and writes two PNGs: the
 * old flat three-brightness look and the new one. Then reports the bytes,
 * because grain is noise and noise is the one thing PNG cannot compress.
 */
public class TerrainLook {
    public static final int N = 384;      // map-radius 192, one block per pixel

    public static int[] height = new int[N * N];
    public static int[] base = new int[N * N];
    public static int[] fam = new int[N * N];
    public static int[] depth = new int[N * N];

    // map colours, from MapColor: GRASS, SAND, WATER, STONE, WOOD, PLANT, DIRT
    public static final int GRASS = 0x7FB238, SAND = 0xF7E9A3, WATER = 0x4040FF,
                     STONE = 0x707070, WOOD = 0x9A814D, DIRT = 0x976D4D, SNOW = 0xFFFFFF;

    public static void main(String[] a) throws Exception {
        Path out = Paths.get(a[0]);
        build();
        Class<?> ws = Class.forName("com.schecks.almin.WorldSnapshots");
        Method shade = ws.getDeclaredMethod("shadeColumn",
            int.class, int.class, int.class, int.class, int.class, int.class, int.class, int.class);
        shade.setAccessible(true);

        int[] neu = new int[N * N], old = new int[N * N];
        for (int z = 0; z < N; z++) {
            for (int x = 0; x < N; x++) {
                int i = z * N + x;
                boolean wet = depth[i] > 0;
                int here = height[i];
                int north = z > 0 ? ground(x, z - 1) : Integer.MIN_VALUE;
                int west = x > 0 ? ground(x - 1, z) : Integer.MIN_VALUE;
                neu[i] = (Integer) shade.invoke(null, base[i], x, z, here, north, west, depth[i], fam[i]);
                old[i] = vanilla(base[i], here, north);
            }
        }
        Class<?> png = Class.forName("com.schecks.almin.Png");
        Method enc = png.getDeclaredMethod("encode", int[].class, int.class, int.class);
        enc.setAccessible(true);
        byte[] nb = (byte[]) enc.invoke(null, neu, N, N);
        byte[] ob = (byte[]) enc.invoke(null, old, N, N);
        Files.createDirectories(out);
        Files.write(out.resolve("terrain-new.png"), nb);
        Files.write(out.resolve("terrain-old.png"), ob);
        System.out.printf("old (flat, 3 brightnesses): %,d bytes%n", ob.length);
        System.out.printf("new (relief + grain):       %,d bytes%n", nb.length);
        System.out.printf("ratio: %.2fx   ·  40 shots: %,d KB   120 shots: %,d KB%n",
            nb.length / (double) ob.length, nb.length * 40L / 1024, nb.length * 120L / 1024);

        // Distinct colours, as a proxy for "does it read as terrain or blotches".
        Set<Integer> so = new HashSet<>(), sn = new HashSet<>();
        for (int v : old) so.add(v);
        for (int v : neu) sn.add(v);
        System.out.printf("distinct colours: old %,d  new %,d%n", so.size(), sn.size());
    }

    static int ground(int x, int z) { return height[z * N + x]; }

    static int vanilla(int col, int here, int north) {
        int mod = north == Integer.MIN_VALUE ? 220 : here > north ? 255 : here < north ? 180 : 220;
        int r = ((col >> 16 & 0xFF) * mod) / 255, g = ((col >> 8 & 0xFF) * mod) / 255,
            b = ((col & 0xFF) * mod) / 255;
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    public static void build() {
        Random rnd = new Random(7);
        double[] h = new double[N * N];
        // Cheap fractal: a few octaves of smoothed noise.
        for (int oct = 0; oct < 5; oct++) {
            int step = 1 << (6 - oct);
            double amp = 22.0 / (1 << oct);
            double[] g = new double[(N / step + 2) * (N / step + 2)];
            for (int i = 0; i < g.length; i++) g[i] = rnd.nextDouble();
            int w = N / step + 2;
            for (int z = 0; z < N; z++) for (int x = 0; x < N; x++) {
                int gx = x / step, gz = z / step;
                double fx = (x % step) / (double) step, fz = (z % step) / (double) step;
                double v00 = g[gz * w + gx], v10 = g[gz * w + gx + 1];
                double v01 = g[(gz + 1) * w + gx], v11 = g[(gz + 1) * w + gx + 1];
                double v = v00 * (1 - fx) * (1 - fz) + v10 * fx * (1 - fz)
                         + v01 * (1 - fx) * fz + v11 * fx * fz;
                h[z * N + x] += v * amp;
            }
        }
        int sea = 62;
        for (int z = 0; z < N; z++) for (int x = 0; x < N; x++) {
            int i = z * N + x;
            int y = (int) Math.round(50 + h[i]);
            height[i] = y;
            if (y < sea) { depth[i] = sea - y; base[i] = WATER; fam[i] = 0; }
            else if (y < sea + 2) { base[i] = SAND; fam[i] = 1; }
            else if (y > 82) { base[i] = SNOW; fam[i] = 1; }
            else if (y > 74) { base[i] = STONE; fam[i] = 4; }
            else { base[i] = GRASS; fam[i] = 3; }
        }
        // Somebody's plank floor, and a dirt path off it.
        for (int z = 150; z < 186; z++) for (int x = 150; x < 194; x++) {
            int i = z * N + x;
            if (depth[i] > 0) continue;
            base[i] = WOOD; fam[i] = 2; height[i] = 70; depth[i] = 0;
        }
        for (int t = 0; t < 160; t++) {
            int x = 194 + t / 2, z = 168 + (int) (12 * Math.sin(t / 22.0));
            if (x < 1 || x >= N - 1 || z < 1 || z >= N - 1) continue;
            for (int d = -1; d <= 1; d++) {
                int i = z * N + x + d * N;
                if (i < 0 || i >= N * N || depth[i] > 0) continue;
                base[i] = DIRT; fam[i] = 4;
            }
        }
    }
}
