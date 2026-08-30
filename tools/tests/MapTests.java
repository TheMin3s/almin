import com.schecks.almin.AlminConfig;
import com.schecks.almin.Png;
import com.schecks.almin.WorldSnapshots;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.List;
import java.util.zip.CRC32;

/**
 * The world under the map: the PNG written by hand, and the snapshots kept
 * beside the activity log.
 *
 * The encoder is written out longhand rather than handed to ImageIO, because
 * java.desktop is a module a trimmed server JRE may not ship. That makes it
 * the thing most worth checking — so it is checked by decoding what it writes
 * with a real decoder, not by reading it back with itself.
 */
public class MapTests {
    static int fail = 0;
    static void ck(String w, boolean ok, String d) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + w + (ok ? "" : "  -> " + d));
        if (!ok) fail++;
    }

    static Path dir;

    public static void main(String[] a) throws Exception {
        Constructor<AlminConfig> cc = AlminConfig.class.getDeclaredConstructor();
        cc.setAccessible(true);
        Field inst = AlminConfig.class.getDeclaredField("instance");
        inst.setAccessible(true);
        inst.set(null, cc.newInstance());

        dir = Files.createTempDirectory("alminmap");

        png();
        naming();
        choosing();
        keeping();
        safety();
        settings();
        wiring();

        System.out.println(fail == 0 ? "\nMAP TESTS PASSED" : "\n" + fail + " FAILED");
        System.exit(fail == 0 ? 0 : 1);
    }

    /** Written by hand, so decoded by someone else. */
    static void png() throws Exception {
        int w = 37, h = 23;                       // odd sizes, not a tidy power of two
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                px[y * w + x] = (x == 0 && y == 0) ? 0x00000000            // transparent
                    : 0xFF000000 | (x * 7 % 256) << 16 | (y * 11 % 256) << 8 | ((x + y) % 256);
            }
        }
        byte[] out = Png.encode(px, w, h);

        ck("it starts with the PNG signature",
            (out[0] & 0xFF) == 0x89 && out[1] == 'P' && out[2] == 'N' && out[3] == 'G',
            "bad magic");
        ck("every chunk's CRC checks out", crcsValid(out), "a chunk is corrupt");
        ck("it ends with IEND", new String(out, out.length - 8, 4,
            java.nio.charset.StandardCharsets.US_ASCII).equals("IEND"), "no IEND");

        BufferedImage img = ImageIO.read(new ByteArrayInputStream(out));
        ck("a real decoder reads it back", img != null, "ImageIO returned null");
        ck("...at the right size", img.getWidth() == w && img.getHeight() == h,
            img.getWidth() + "x" + img.getHeight());

        boolean same = true;
        for (int y = 0; y < h && same; y++) {
            for (int x = 0; x < w; x++) {
                if (img.getRGB(x, y) != px[y * w + x]) { same = false; break; }
            }
        }
        ck("...pixel for pixel, alpha included", same, "pixels differ");
        ck("an unloaded corner stays transparent",
            (img.getRGB(0, 0) >>> 24) == 0, "alpha " + (img.getRGB(0, 0) >>> 24));

        // A large flat image is the common case; it must compress, not expand.
        int[] flat = new int[512 * 512];
        java.util.Arrays.fill(flat, 0xFF3A7A2E);
        byte[] big = Png.encode(flat, 512, 512);
        ck("a flat image compresses rather than growing",
            big.length < 512 * 512 * 4 / 10, big.length + " bytes");

        boolean refused = false;
        try { Png.encode(new int[4], 0, 4); } catch (Exception e) { refused = true; }
        ck("an empty image is refused, not written", refused, "it encoded");
        refused = false;
        try { Png.encode(new int[4], 40, 40); } catch (Exception e) { refused = true; }
        ck("too few pixels for the size is refused", refused, "it encoded");
    }

    static boolean crcsValid(byte[] png) {
        int at = 8;
        while (at + 12 <= png.length) {
            int len = ((png[at] & 0xFF) << 24) | ((png[at + 1] & 0xFF) << 16)
                    | ((png[at + 2] & 0xFF) << 8) | (png[at + 3] & 0xFF);
            if (len < 0 || at + 12 + len > png.length) return false;
            CRC32 crc = new CRC32();
            crc.update(png, at + 4, 4 + len);
            int want = ((png[at + 8 + len] & 0xFF) << 24) | ((png[at + 9 + len] & 0xFF) << 16)
                     | ((png[at + 10 + len] & 0xFF) << 8) | (png[at + 11 + len] & 0xFF);
            if ((int) crc.getValue() != want) return false;
            at += 12 + len;
        }
        return at == png.length;
    }

    /** The filename is the index: it has to survive a restart on its own. */
    static void naming() throws Exception {
        Method name = m("name", WorldSnapshots.Shot.class);
        Method parse = m("parse", String.class);
        WorldSnapshots.Shot s = new WorldSnapshots.Shot(
            1712345678901L, "the_nether", -1408, 2176, 384, 2, 0L, "");
        String file = (String) name.invoke(null, s);
        WorldSnapshots.Shot back = (WorldSnapshots.Shot) parse.invoke(null, file);
        ck("a snapshot's name round-trips", back != null
            && back.at() == s.at() && back.dim().equals(s.dim())
            && back.minX() == s.minX() && back.minZ() == s.minZ()
            && back.blocks() == s.blocks() && back.scale() == s.scale(),
            String.valueOf(back));
        ck("...negative coordinates included", file.contains("-1408"), file);

        ck("...and a dimension whose own name has underscores in it",
            back != null && back.dim().equals("the_nether"),
            back == null ? "unreadable" : back.dim());

        for (String junk : new String[]{"notes.txt", "a@b.png", "x@1@2@3@4@5@6@7@8.png",
                                        "overworld@x@1@2@3@4.png", "x@1@2@3@4@5@six.png", ""}) {
            ck("refuses '" + junk + "'", parse.invoke(null, junk) == null, junk);
        }

        // Six fields is what every snapshot written before differences looked
        // like, and those files are still on disk on servers that update.
        Object old6 = parse.invoke(null, "overworld@1712345678901@0@0@384@2.png");
        ck("a pre-difference filename still parses", old6 != null, "unreadable");
        ck("...and counts as a whole picture",
            old6 != null && (Boolean) WorldSnapshots.Shot.class.getMethod("whole").invoke(old6),
            "it was taken for a difference");
    }

    /** Which picture the timeline is pointing at. */
    static void choosing() throws Exception {
        shots().clear();
        shots().add(new WorldSnapshots.Shot(1000, "overworld", 0, 0, 384, 2, 0L, "a.png"));
        shots().add(new WorldSnapshots.Shot(2000, "overworld", 0, 0, 384, 2, 0L, "b.png"));
        shots().add(new WorldSnapshots.Shot(3000, "the_nether", 0, 0, 384, 2, 0L, "c.png"));

        ck("the newest picture at or before the cursor",
            "b.png".equals(WorldSnapshots.at("overworld", 2500).file()), "");
        ck("...exactly on one counts as at it",
            "b.png".equals(WorldSnapshots.at("overworld", 2000).file()), "");
        ck("...and not one from later",
            "a.png".equals(WorldSnapshots.at("overworld", 1999).file()), "");
        ck("dimensions never borrow each other's ground",
            "c.png".equals(WorldSnapshots.at("the_nether", 9999).file()), "");
        // Tracks outlive the pictures, so a cursor before them all is normal.
        ck("before them all, the oldest beats an empty grid",
            "a.png".equals(WorldSnapshots.at("overworld", 1).file()), "");
        ck("a dimension with no pictures has none",
            WorldSnapshots.at("the_end", 9999) == null, "");
    }

    /** They are pictures of where people were; they are not allowed to pile up. */
    static void keeping() throws Exception {
        shots().clear();
        Files.createDirectories(dir);
        Field dirF = WorldSnapshots.class.getDeclaredField("dir");
        dirF.setAccessible(true);
        dirF.set(null, dir);

        long now = System.currentTimeMillis();
        AlminConfig.get().mapSnapshotKeep = 5;
        AlminConfig.get().activityRetentionMinutes = 60;
        for (int i = 0; i < 12; i++) {
            String f = "overworld@" + (now - i * 1000L) + "@0@0@384@2.png";
            Files.write(dir.resolve(f), new byte[]{1});
            shots().add(new WorldSnapshots.Shot(now - i * 1000L, "overworld", 0, 0, 384, 2, 0L, f));
        }
        WorldSnapshots.prune();
        ck("too many are trimmed to the cap", shots().size() == 5,
            String.valueOf(shots().size()));
        ck("...oldest first, so the recent ones survive",
            WorldSnapshots.all().get(0).at() >= now - 4000,
            String.valueOf(now - WorldSnapshots.all().get(0).at()));
        ck("...and the files go with them",
            Files.list(dir).count() == 5, String.valueOf(Files.list(dir).count()));

        // Expiry beats the count cap: old is old even if there is room. Ground
        // pictures have their own window now, which is measured in days.
        shots().clear();
        for (var f : Files.list(dir).toList()) Files.delete(f);
        long past = now - (AlminConfig.get().mapSnapshotDays + 2) * 86_400_000L;
        String stale = "overworld@" + past + "@0@0@384@2.png";
        Files.write(dir.resolve(stale), new byte[]{1});
        shots().add(new WorldSnapshots.Shot(past, "overworld", 0, 0, 384, 2, 0L, stale));
        WorldSnapshots.prune();
        ck("a picture past the retention window is deleted",
            shots().isEmpty() && !Files.exists(dir.resolve(stale)), "it survived");

        shots().clear();
        String keeper = "overworld@" + now + "@0@0@384@2.png";
        Files.write(dir.resolve(keeper), new byte[]{1});
        shots().add(new WorldSnapshots.Shot(now, "overworld", 0, 0, 384, 2, 0L, keeper));
        WorldSnapshots.clear();
        ck("clearing the activity log takes the ground with it",
            shots().isEmpty() && !Files.exists(dir.resolve(keeper)), "it survived");
    }

    /** The filename comes from our own index, but resolve() would still obey it. */
    static void safety() throws Exception {
        Field dirF = WorldSnapshots.class.getDeclaredField("dir");
        dirF.setAccessible(true);
        dirF.set(null, dir);
        Path secret = dir.getParent().resolve("secret.txt");
        Files.writeString(secret, "not yours");
        byte[] got = WorldSnapshots.read(
            new WorldSnapshots.Shot(1, "overworld", 0, 0, 384, 2, 0L, "../secret.txt"));
        ck("a traversing filename reads nothing", got == null, "it read the file");
        ck("neither does an empty one",
            WorldSnapshots.read(new WorldSnapshots.Shot(1, "o", 0, 0, 1, 1, 0L, "")) == null, "");
        ck("nor a null shot", WorldSnapshots.read(null) == null, "");
    }

    static void settings() throws Exception {
        Constructor<AlminConfig> cc = AlminConfig.class.getDeclaredConstructor();
        cc.setAccessible(true);
        AlminConfig fresh = cc.newInstance();

        AlminConfig.Key every = AlminConfig.keyByName("map-snapshot-seconds");
        ck("the cadence is a setting", every != null, "missing");
        ck("...and can be turned off with 0", every != null && every.min == 0,
            every == null ? "-" : String.valueOf(every.min));
        ck("...defaulting to about half a minute", "30".equals(every.display(fresh)),
            every.display(fresh));

        AlminConfig.Key scale = AlminConfig.keyByName("map-blocks-per-pixel");
        ck("detail is bounded so a snapshot cannot cost the world",
            scale != null && scale.min == 1 && scale.max == 8,
            scale == null ? "-" : scale.min + ".." + scale.max);
        AlminConfig.Key radius = AlminConfig.keyByName("map-radius");
        ck("so is the area", radius != null && radius.max == 512,
            radius == null ? "-" : String.valueOf(radius.max));
        AlminConfig.Key keep = AlminConfig.keyByName("map-snapshot-keep");
        ck("and how many are kept", keep != null && keep.min == 2,
            keep == null ? "-" : String.valueOf(keep.min));
    }

    /** Where the cost lands is the whole design; assert it stayed there. */
    static void wiring() throws Exception {
        String src = Files.readString(Path.of(
            "src/main/java/com/schecks/almin/WorldSnapshots.java"));
        ck("terrain is never generated just to photograph it",
            src.contains("getChunkNow(") && !src.contains("ChunkStatus"), "it may generate");
        ck("an unloaded chunk is skipped rather than waited on",
            src.contains("if (chunk == null) continue;"), "no skip");
        int write = src.indexOf("private static void write(");
        ck("encoding happens off the server thread",
            write > 0 && src.indexOf("pool.execute(", write) > write
                && src.indexOf("Png.encode(", write) > src.indexOf("pool.execute(", write),
            "encoding is inline");
        ck("...on a daemon thread, so it cannot hold the JVM open",
            src.contains("t.setDaemon(true)"), "not a daemon");
        ck("one snapshot at a time, so a slow disk cannot queue them up",
            src.contains("if (busy) return;"), "unbounded");

        String almin = Files.readString(Path.of("src/main/java/com/schecks/almin/Almin.java"));
        ck("it is offered the tick", almin.contains("WorldSnapshots::tick"), "never ticks");
        ck("and closed with everything else", almin.contains("WorldSnapshots.close()"), "leaks");

        String log = Files.readString(Path.of("src/main/java/com/schecks/almin/ActivityLog.java"));
        ck("clearing the log clears the ground too",
            log.contains("WorldSnapshots.clear()"), "left behind");

        // The in-game map draws every pixel of a line as its own quad, so a
        // long path is a real per-frame cost rather than a theoretical one.
        String screen = Files.readString(Path.of(
            "src/main/java/com/schecks/almin/client/ActivityScreen.java"));
        ck("the in-game map spends a bounded number of pixels a frame",
            screen.contains("int budget = PIXEL_BUDGET") && screen.contains("budget -= line("),
            "unbounded");
        ck("...and skips samples landing on the same pixel",
            screen.contains("if (px == sx && py == sy) continue;"), "no dedupe");
        ck("a jump across the map is not drawn as a walk",
            screen.contains("steps > MAX_SEGMENT"), "no cap");
        ck("placing and breaking are told apart by shape, not only colour",
            screen.contains("case \"place\"") && screen.contains("case \"break\"")
                && screen.contains("g.outline("), "same shape");

        String web = Files.readString(Path.of("src/main/java/com/schecks/almin/WebUi.java"));
        ck("the pictures need a login like everything else",
            web.contains("/api/map") && web.substring(web.indexOf("private void handleMap"),
                web.indexOf("private void handleMap") + 300).contains("requireAuth"),
            "unauthenticated");
    }

    @SuppressWarnings("unchecked")
    static List<WorldSnapshots.Shot> shots() throws Exception {
        Field f = WorldSnapshots.class.getDeclaredField("shots");
        f.setAccessible(true);
        return (List<WorldSnapshots.Shot>) f.get(null);
    }

    static Method m(String name, Class<?>... args) throws Exception {
        Method x = WorldSnapshots.class.getDeclaredMethod(name, args);
        x.setAccessible(true);
        return x;
    }
}
