import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Png.decode, Heads.crop, ModIcons sniffing and path guards. */
public class ImageTests {
    static int pass = 0, fail = 0;
    static void ok(String what, boolean cond) {
        if (cond) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Png.encode -> Png.decode round trip");
        Class<?> png = Class.forName("com.schecks.almin.Png");
        Method encode = png.getMethod("encode", int[].class, int.class, int.class);
        Method decode = png.getMethod("decode", byte[].class);

        int w = 37, h = 23;   // deliberately not a round number
        int[] src = new int[w * h];
        Random rnd = new Random(7);
        for (int i = 0; i < src.length; i++) src[i] = rnd.nextInt();
        byte[] bytes = (byte[]) encode.invoke(null, src, w, h);
        Object img = decode.invoke(null, (Object) bytes);
        int[] back = (int[]) img.getClass().getMethod("argb").invoke(img);
        ok("width survives", (int) img.getClass().getMethod("width").invoke(img) == w);
        ok("height survives", (int) img.getClass().getMethod("height").invoke(img) == h);
        boolean same = true;
        for (int i = 0; i < src.length; i++) if (src[i] != back[i]) { same = false; break; }
        ok("every pixel survives", same);

        System.out.println("decoding refuses what it cannot read");
        ok("not a PNG", throwsIo(decode, new byte[]{1,2,3,4,5,6,7,8,9,10}));
        ok("truncated", throwsIo(decode, Arrays.copyOf(bytes, bytes.length / 2)));
        ok("empty", throwsIo(decode, new byte[0]));
        byte[] corrupt = bytes.clone();
        corrupt[25] = (byte) 0xEE;   // inside IHDR: colour type
        ok("unknown colour type", throwsIo(decode, corrupt));

        System.out.println("real PNGs written by another encoder");
        // Written by a hand-rolled Python writer using row filters this
        // encoder never emits and colour types it never writes, so the reading
        // half is checked against something Almin did not produce.
        Path fixtures = Path.of(System.getProperty("fixtures"));
        int[] reference = null;
        for (String name : new String[]{"filt-sub.png","filt-up.png","filt-avg.png","filt-paeth.png"}) {
            Object d = decode.invoke(null, (Object) Files.readAllBytes(fixtures.resolve(name)));
            int[] pix = (int[]) d.getClass().getMethod("argb").invoke(d);
            ok(name + " is 16x16", (int) d.getClass().getMethod("width").invoke(d) == 16
                && pix.length == 256);
            // Every filter encodes the same gradient, so all four must
            // reconstruct byte-for-byte identical pixels.
            if (reference == null) reference = pix;
            else ok(name + " matches the other filters", Arrays.equals(reference, pix));
            ok(name + " gradient corner", pix[0] == 0xFF000000 && (pix[255] & 0xFFFFFF) == 0xF0F0F0);
        }
        Object greyImg = decode.invoke(null, (Object) Files.readAllBytes(fixtures.resolve("grey.png")));
        int[] greyPix = (int[]) greyImg.getClass().getMethod("argb").invoke(greyImg);
        ok("greyscale decodes opaque", (greyPix[0] >>> 24) == 255);
        ok("greyscale is grey", ((greyPix[255] >> 16) & 0xFF) == (greyPix[255] & 0xFF));

        Object rgbImg = decode.invoke(null, (Object) Files.readAllBytes(fixtures.resolve("rgb.png")));
        int[] rgbPix = (int[]) rgbImg.getClass().getMethod("argb").invoke(rgbImg);
        ok("truecolour matches the rgba fixtures", (rgbPix[128] & 0xFFFFFF) == (reference[128] & 0xFFFFFF));
        ok("truecolour is opaque", (rgbPix[0] >>> 24) == 255);

        Object palImg = decode.invoke(null, (Object) Files.readAllBytes(fixtures.resolve("palette.png")));
        int[] palPix = (int[]) palImg.getClass().getMethod("argb").invoke(palImg);
        ok("palette decodes", palPix.length == 256);
        ok("palette entry 0 is blue", (palPix[0] & 0xFFFFFF) == 0x0000FF);
        ok("palette entry 15 is red", (palPix[15] & 0xFFFFFF) == 0xF0000F);
        // tRNS is [0,128,255,255] — shorter than the palette on purpose.
        ok("tRNS makes entry 0 transparent", (palPix[0] >>> 24) == 0);
        ok("tRNS half-alpha survives", (palPix[1] >>> 24) == 128);
        ok("entries past tRNS are opaque", (palPix[15] >>> 24) == 255);

        System.out.println("Heads.crop");
        Class<?> heads = Class.forName("com.schecks.almin.Heads");
        Method crop = heads.getDeclaredMethod("crop", Class.forName("com.schecks.almin.Png$Image"));
        crop.setAccessible(true);
        Constructor<?> imageCtor = Class.forName("com.schecks.almin.Png$Image")
            .getConstructor(int.class, int.class, int[].class);

        // A 64x64 skin: face solid blue, hat solid red at full alpha.
        int[] skin = new int[64 * 64];
        for (int y = 8; y < 16; y++) for (int x = 8; x < 16; x++) skin[y*64+x] = 0xFF0000FF;
        for (int y = 8; y < 16; y++) for (int x = 40; x < 48; x++) skin[y*64+x] = 0xFFFF0000;
        byte[] headPng = (byte[]) crop.invoke(null, imageCtor.newInstance(64, 64, skin));
        Object head = decode.invoke(null, (Object) headPng);
        int[] hp = (int[]) head.getClass().getMethod("argb").invoke(head);
        int hw = (int) head.getClass().getMethod("width").invoke(head);
        ok("head is 64x64", hw == 64 && hp.length == 64 * 64);
        ok("opaque hat wins over the face", hp[0] == 0xFFFF0000);

        // Same skin with a transparent hat: the face must show through.
        int[] bare = new int[64 * 64];
        for (int y = 8; y < 16; y++) for (int x = 8; x < 16; x++) bare[y*64+x] = 0xFF0000FF;
        byte[] barePng = (byte[]) crop.invoke(null, imageCtor.newInstance(64, 64, bare));
        Object bareHead = decode.invoke(null, (Object) barePng);
        int[] bp = (int[]) bareHead.getClass().getMethod("argb").invoke(bareHead);
        ok("transparent hat leaves the face", bp[0] == 0xFF0000FF);

        // Legacy 64x32 skins: same face and hat coordinates, shorter image.
        int[] legacy = new int[64 * 32];
        for (int y = 8; y < 16; y++) for (int x = 8; x < 16; x++) legacy[y*64+x] = 0xFF00FF00;
        byte[] legacyPng = (byte[]) crop.invoke(null, imageCtor.newInstance(64, 32, legacy));
        Object lh = decode.invoke(null, (Object) legacyPng);
        int[] lp = (int[]) lh.getClass().getMethod("argb").invoke(lh);
        ok("64x32 skins still crop", lp[0] == 0xFF00FF00);

        // Too small to hold a face at all.
        boolean threw = false;
        try { crop.invoke(null, imageCtor.newInstance(8, 8, new int[64])); }
        catch (InvocationTargetException e) { threw = e.getCause() instanceof java.io.IOException; }
        ok("a skin with no face is refused", threw);

        System.out.println("Heads.parseUuid");
        Method parse = heads.getMethod("parseUuid", String.class);
        UUID known = UUID.fromString("516e51d9-4e6b-4a2f-a282-e0f51f5a20e7");
        ok("dashed", known.equals(parse.invoke(null, known.toString())));
        ok("plain", known.equals(parse.invoke(null, known.toString().replace("-",""))));
        ok("uppercase plain", known.equals(parse.invoke(null,
            known.toString().replace("-","").toUpperCase(Locale.ROOT))));
        ok("nonsense is null", parse.invoke(null, "../../etc/passwd") == null);
        ok("null is null", parse.invoke(null, (Object) null) == null);

        System.out.println("ModIcons format sniffing");
        Class<?> icons = Class.forName("com.schecks.almin.ModIcons");
        Method sniff = icons.getDeclaredMethod("sniff", byte[].class);
        sniff.setAccessible(true);
        ok("png", "image/png".equals(sniff.invoke(null, (Object) bytes)));
        byte[] jpeg = new byte[16]; jpeg[0] = (byte)0xFF; jpeg[1] = (byte)0xD8;
        ok("jpeg", "image/jpeg".equals(sniff.invoke(null, (Object) jpeg)));
        byte[] webp = "RIFF____WEBPVP8 ".getBytes("US-ASCII");
        ok("webp", "image/webp".equals(sniff.invoke(null, (Object) webp)));
        byte[] svg = "<svg xmlns='...'>onload=alert(1)".getBytes("US-ASCII");
        ok("svg refused (a browser would execute it)", sniff.invoke(null, (Object) svg) == null);
        byte[] html = "<!doctype html><script>".getBytes("US-ASCII");
        ok("html refused", sniff.invoke(null, (Object) html) == null);
        ok("short input refused", sniff.invoke(null, (Object) new byte[]{1,2}) == null);

        System.out.println("ModIcons cache paths cannot leave the folder");
        Method fileFor = icons.getDeclaredMethod("fileFor", String.class);
        fileFor.setAccessible(true);
        Field dir = icons.getDeclaredField("dir");
        dir.setAccessible(true);
        Path tmp = Files.createTempDirectory("almin-icons");
        icons.getMethod("init", Path.class).invoke(null, tmp);
        ok("a plain id resolves", fileFor.invoke(null, "sodium") != null);
        ok("id with a slash refused", fileFor.invoke(null, "a/b") == null);
        ok("traversal refused", fileFor.invoke(null, "../../evil") == null);
        ok("dotfile refused", fileFor.invoke(null, ".bashrc") == null);
        ok("empty refused", fileFor.invoke(null, "  ") == null);
        ok("absurdly long refused", fileFor.invoke(null, "x".repeat(200)) == null);
        ok("uppercase folds", fileFor.invoke(null, "Sodium").toString()
            .equals(fileFor.invoke(null, "sodium").toString()));

        // ---- packed bit depths ----
        // Minecraft's own block textures are 1-, 2- and 4-bit palettes as
        // often as they are 8-bit ones: sand and dirt are 4-bit, snow is
        // 2-bit. The fixtures are written by the Python above, not by our own
        // encoder, so this is a format test rather than a round trip.
        Object p4 = decode.invoke(null, (Object) Files.readAllBytes(fixtures.resolve("pal4.png")));
        int[] p4px = (int[]) p4.getClass().getMethod("argb").invoke(p4);
        ok("a 4-bit palette decodes to the right size", p4px.length == 18);
        // Palette entry i is (17i, 255-17i, 37i mod 256); the first row is 0..5.
        boolean rowOk = true;
        for (int i = 0; i < 6; i++) {
            int want = 0xFF000000 | (i * 17) << 16 | (255 - i * 17) << 8 | ((i * 37) % 256);
            if (p4px[i] != want) rowOk = false;
        }
        ok("4-bit palette indices unpack in the right order", rowOk);
        ok("...including the second row", p4px[6] ==
            (0xFF000000 | (15 * 17) << 16 | (255 - 15 * 17) << 8 | ((15 * 37) % 256)));

        Object p2 = decode.invoke(null, (Object) Files.readAllBytes(fixtures.resolve("pal2.png")));
        int[] p2px = (int[]) p2.getClass().getMethod("argb").invoke(p2);
        ok("a 2-bit palette decodes", p2px.length == 10);
        ok("2-bit indices unpack", p2px[0] == 0xFFFF0000 && p2px[1] == 0xFF00FF00
            && p2px[2] == 0xFF0000FF);
        // Odd width: the fifth pixel of a 5-wide 2-bit row is alone in its byte,
        // and the second row must start on the next byte rather than continue.
        ok("an odd width does not bleed into the next row",
            p2px[4] == 0xFFFF0000 && p2px[5] == (0xFF000000 | (250 << 16) | (240 << 8) | 230));

        Object g1 = decode.invoke(null, (Object) Files.readAllBytes(fixtures.resolve("grey1.png")));
        int[] g1px = (int[]) g1.getClass().getMethod("argb").invoke(g1);
        ok("1-bit grey scales to black and white",
            g1px[0] == 0xFF000000 && g1px[1] == 0xFFFFFFFF);
        ok("...and a 9-wide row still starts the next one cleanly", g1px[9] == 0xFFFFFFFF);

        Object g4 = decode.invoke(null, (Object) Files.readAllBytes(fixtures.resolve("grey4.png")));
        int[] g4px = (int[]) g4.getClass().getMethod("argb").invoke(g4);
        ok("4-bit grey scales 0..15 across the full range",
            g4px[0] == 0xFF000000 && g4px[3] == 0xFFFFFFFF);
        ok("...and the middle lands where it should",
            ((g4px[2] >> 16) & 0xFF) == 10 * 255 / 15);

        Object p4t = decode.invoke(null, (Object) Files.readAllBytes(fixtures.resolve("pal4t.png")));
        int[] p4tpx = (int[]) p4t.getClass().getMethod("argb").invoke(p4t);
        ok("tRNS still applies at 4 bits",
            (p4tpx[0] >>> 24) == 255 && (p4tpx[1] >>> 24) == 0 && (p4tpx[2] >>> 24) == 255);

        // 16-bit is legal PNG and used by nothing here; it must be refused
        // rather than silently misread.
        byte[] sixteen = Files.readAllBytes(fixtures.resolve("grey.png")).clone();
        sixteen[24] = 16;
        ok("16-bit is refused", throwsIo(decode, sixteen));

        // ---- real block textures, if this run has any ----
        Class<?> textures = Class.forName("com.schecks.almin.BlockTextures");
        Method greyish = textures.getDeclaredMethod("greyish", int.class);
        greyish.setAccessible(true);
        ok("a grey is grey", (Boolean) greyish.invoke(null, 0x808080));
        ok("an off-white is grey enough", (Boolean) greyish.invoke(null, 0xF0EEF2));
        ok("grass green is not", !(Boolean) greyish.invoke(null, 0x7FB238));
        Method blend = textures.getDeclaredMethod("blend", int.class, int.class, float.class);
        blend.setAccessible(true);
        ok("blend at 0 keeps the base", ((Integer) blend.invoke(null, 0x102030, 0xFFFFFF, 0f))
            == 0x102030);
        ok("blend at 1 takes the other", ((Integer) blend.invoke(null, 0x102030, 0x405060, 1f))
            == 0x405060);
        ok("blend halfway is halfway",
            ((Integer) blend.invoke(null, 0x000000, 0xFFFFFF, 0.5f)) == 0x808080);

        System.out.println();
        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    static boolean throwsIo(Method decode, byte[] input) {
        try { decode.invoke(null, (Object) input); return false; }
        catch (InvocationTargetException e) { return e.getCause() instanceof java.io.IOException; }
        catch (Exception e) { return false; }
    }
}
