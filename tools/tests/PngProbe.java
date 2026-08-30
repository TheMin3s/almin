import java.lang.reflect.*;
import java.util.zip.*;
public class PngProbe {
    public static void main(String[] a) throws Exception {
        ZipFile zip = new ZipFile(a[0]);
        Class<?> P = Class.forName("com.schecks.almin.Png");
        Method dec = P.getDeclaredMethod("decode", byte[].class); dec.setAccessible(true);
        for (String name : new String[]{"sand","short_grass","stone","oak_planks","grass_block_top","glass","water_still","snow","dirt"}) {
            ZipEntry e = zip.getEntry("assets/minecraft/textures/block/" + name + ".png");
            if (e == null) { System.out.println(name + ": missing"); continue; }
            byte[] b; try (var in = zip.getInputStream(e)) { b = in.readAllBytes(); }
            // IHDR: width, height, depth, colour type
            int w = ((b[16]&255)<<24)|((b[17]&255)<<16)|((b[18]&255)<<8)|(b[19]&255);
            int h = ((b[20]&255)<<24)|((b[21]&255)<<16)|((b[22]&255)<<8)|(b[23]&255);
            int depth = b[24]&255, type = b[25]&255, interlace = b[28]&255;
            String res;
            try {
                Object img = dec.invoke(null, (Object) b);
                int[] px = (int[]) img.getClass().getMethod("argb").invoke(img);
                int opaque = 0; for (int v : px) if ((v>>>24) >= 128) opaque++;
                res = "decoded, opaque " + opaque + "/" + px.length;
            } catch (Exception ex) {
                res = "FAILED " + (ex.getCause()==null?ex:ex.getCause());
            }
            System.out.printf("%-18s %dx%d depth=%d type=%d interlace=%d  %s%n",
                name, w, h, depth, type, interlace, res);
        }
        zip.close();
    }
}
