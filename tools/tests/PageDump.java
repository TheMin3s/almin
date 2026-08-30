import java.lang.reflect.Field;
import java.nio.file.*;

/** Writes the panel page out so the browser code in it can be checked. */
public class PageDump {
    public static void main(String[] a) throws Exception {
        Class<?> c = Class.forName("com.schecks.almin.WebPage");
        Field f = c.getDeclaredField("HTML");
        f.setAccessible(true);
        String html = (String) f.get(null);
        Files.writeString(Path.of(a[0]), html);

        int s = html.indexOf("<script>"), e = html.lastIndexOf("</script>");
        if (s < 0 || e < 0) { System.out.println("NO SCRIPT BLOCK"); System.exit(1); }
        Files.writeString(Path.of(a[1]), html.substring(s + "<script>".length(), e));
        System.out.println("html " + html.length() + " chars, script " + (e - s) + " chars");
    }
}
