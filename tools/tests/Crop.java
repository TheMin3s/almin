import java.lang.reflect.Method;
import java.nio.file.*;

/** A 3x nearest-neighbour crop of a snapshot, for looking at closely. */
public class Crop {
    public static void main(String[] a) throws Exception {
        Class<?> png = Class.forName("com.schecks.almin.Png");
        Method dec = png.getDeclaredMethod("decode", byte[].class);
        Method enc = png.getDeclaredMethod("encode", int[].class, int.class, int.class);
        dec.setAccessible(true); enc.setAccessible(true);
        Object img = dec.invoke(null, (Object) Files.readAllBytes(Paths.get(a[0])));
        int w = (int) img.getClass().getMethod("width").invoke(img);
        int[] px = (int[]) img.getClass().getMethod("argb").invoke(img);
        int x0 = Integer.parseInt(a[2]), z0 = Integer.parseInt(a[3]),
            n = Integer.parseInt(a[4]), k = Integer.parseInt(a[5]);
        int[] out = new int[n * k * n * k];
        for (int z = 0; z < n * k; z++)
            for (int x = 0; x < n * k; x++)
                out[z * n * k + x] = px[(z0 + z / k) * w + (x0 + x / k)];
        Files.write(Paths.get(a[1]), (byte[]) enc.invoke(null, out, n * k, n * k));
        System.out.println("wrote " + a[1]);
    }
}
