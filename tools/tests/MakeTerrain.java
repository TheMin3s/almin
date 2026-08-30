import com.schecks.almin.Png;
import java.nio.file.*;

/** A plausible terrain raster, written with the real encoder, for a visual check. */
public class MakeTerrain {
    public static void main(String[] a) throws Exception {
        int n = 192;                       // 384 blocks at 2 blocks/pixel
        int[] px = new int[n * n];
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                double h = noise(x * 0.045, y * 0.045) * 0.6
                         + noise(x * 0.11, y * 0.11) * 0.3
                         + noise(x * 0.3, y * 0.3) * 0.1;
                int c;
                if (h < 0.36) c = 0xFF3A5FA8;                       // water
                else if (h < 0.40) c = 0xFFC2B280;                  // sand
                else if (h < 0.62) c = shade(0xFF4C8B36, h);        // grass
                else if (h < 0.74) c = shade(0xFF6E6E6E, h);        // stone
                else c = 0xFFEEEEEE;                                // snow
                // A patch nobody has loaded, so the transparent case shows.
                if (x > 150 && y < 40) c = 0;
                px[y * n + x] = c;
            }
        }
        Files.write(Path.of(a[0]), Png.encode(px, n, n));
        System.out.println("wrote " + a[0]);
    }
    static int shade(int rgb, double h) {
        double k = 0.75 + (h % 0.08) * 3;
        int r = (int) Math.min(255, ((rgb >> 16) & 255) * k);
        int g = (int) Math.min(255, ((rgb >> 8) & 255) * k);
        int b = (int) Math.min(255, (rgb & 255) * k);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
    static double noise(double x, double y) {
        double v = Math.sin(x * 1.7 + Math.cos(y * 1.3)) * Math.cos(y * 1.1 + Math.sin(x * 0.9));
        return (v + 1) / 2;
    }
}
