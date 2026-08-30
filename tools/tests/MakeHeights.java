import java.lang.reflect.Method;
import java.nio.file.*;

/** The heights of the synthetic world, encoded the way the server encodes them. */
public class MakeHeights {
    public static void main(String[] a) throws Exception {
        TerrainLook.build();
        Class<?> ws = Class.forName("com.schecks.almin.WorldSnapshots");
        Method hp = ws.getDeclaredMethod("heightPixels", int[].class, int[].class);
        hp.setAccessible(true);
        int n = TerrainLook.N;
        int[] colours = new int[n * n];
        for (int i = 0; i < colours.length; i++) colours[i] = 0xFF000000;   // all known
        int[] px = (int[]) hp.invoke(null, TerrainLook.height, colours);
        Method enc = Class.forName("com.schecks.almin.Png")
            .getDeclaredMethod("encode", int[].class, int.class, int.class);
        enc.setAccessible(true);
        Files.write(Paths.get(a[0]), (byte[]) enc.invoke(null, px, n, n));
        int lo = 9999, hi = -9999;
        for (int h : TerrainLook.height) { lo = Math.min(lo, h); hi = Math.max(hi, h); }
        System.out.println("heights " + lo + ".." + hi + " -> " + a[0]);
    }
}
