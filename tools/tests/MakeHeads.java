import java.lang.reflect.*;
import java.nio.file.*;

/**
 * Builds skin PNGs, runs them through the real Heads.crop, and writes the
 * resulting faces out — so what the browser is shown is what the server
 * would actually have produced.
 */
public class MakeHeads {
    public static void main(String[] a) throws Exception {
        Path out = Path.of(a[0]);
        Files.createDirectories(out);
        Class<?> heads = Class.forName("com.schecks.almin.Heads");
        Class<?> image = Class.forName("com.schecks.almin.Png$Image");
        Method crop = heads.getDeclaredMethod("crop", image);
        crop.setAccessible(true);
        Constructor<?> ctor = image.getConstructor(int.class, int.class, int[].class);

        String[] names = {"steve", "alex", "mika"};
        int[][] palettes = {
            {0xFFB59578, 0xFF3B2C22, 0xFF6C4A2E},   // skin, eye, hair
            {0xFFF0C8A0, 0xFF2E4A6B, 0xFFCA6B2A},
            {0xFF7A5A46, 0xFF1E1E28, 0xFF20242C},
        };
        for (int n = 0; n < names.length; n++) {
            int[] skin = new int[64 * 64];
            int face = palettes[n][0], eye = palettes[n][1], hair = palettes[n][2];
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    int c = face;
                    if (y == 0 || (y == 1 && (x == 0 || x == 7))) c = hair;
                    if (y == 3 && (x == 2 || x == 5)) c = eye;
                    if (y == 5 && x >= 3 && x <= 4) c = 0xFF8A5F48;
                    skin[(8 + y) * 64 + (8 + x)] = c;
                }
            }
            // A hat layer on one of them, so compositing is visible.
            if (n == 1) {
                for (int y = 0; y < 3; y++)
                    for (int x = 0; x < 8; x++)
                        skin[(8 + y) * 64 + (40 + x)] = 0xFF2C7A4B;
            }
            byte[] png = (byte[]) crop.invoke(null, ctor.newInstance(64, 64, skin));
            Files.write(out.resolve(names[n] + ".png"), png);
        }

        // Two mod icons, through the real encoder.
        Method encode = Class.forName("com.schecks.almin.Png")
            .getMethod("encode", int[].class, int.class, int.class);
        int size = 96;
        String[] icons = {"sodium", "modmenu"};
        int[][] cols = {{0xFF1E6FD9, 0xFF7FC3FF}, {0xFF8B4FBF, 0xFFE0C4FF}};
        for (int i = 0; i < icons.length; i++) {
            int[] px = new int[size * size];
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    double d = Math.hypot(x - size / 2.0, y - size / 2.0) / (size / 2.0);
                    px[y * size + x] = d < 0.92 ? (d < 0.55 ? cols[i][1] : cols[i][0]) : 0;
                }
            }
            Files.write(out.resolve("icon-" + icons[i] + ".png"),
                (byte[]) encode.invoke(null, px, size, size));
        }
        System.out.println("wrote heads and icons to " + out);
    }
}
