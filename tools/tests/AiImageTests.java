import com.schecks.almin.ActivityLog;
import com.schecks.almin.ActivityEntry;
import com.schecks.almin.Episodes;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** The model's optional geometry diagram is real, bounded, and informative. */
public class AiImageTests {
    static int failures;

    static void check(String label, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + label);
        if (!ok) failures++;
    }

    public static void main(String[] args) throws Exception {
        long now = System.currentTimeMillis();
        List<ActivityEntry> rows = new ArrayList<>();
        for (int x = 0; x < 8; x++) {
            rows.add(new ActivityEntry(now + x, "Alex", "u", "place", "Oak Planks",
                "overworld", 100 + x, 64 + (x % 2), 200, 1));
        }
        rows.add(new ActivityEntry(now + 20, "Alex", "u", "break", "Stone",
            "overworld", 103, 64, 201, 1));
        rows.add(new ActivityEntry(now + 21, "Alex", "u", "break", "Stone",
            "overworld", 104, 64, 201, 1));
        Episodes.Episode episode = new Episodes.Episode("build", "Built something", "Alex", "u",
            "overworld", now - 1, now + 100, 103, 64, 200, 7, 1, 10, 60, "hammer");

        Class<?> diagrams = Class.forName("com.schecks.almin.AiSceneImage");
        Method render = diagrams.getDeclaredMethod("render", List.class, List.class);
        render.setAccessible(true);
        byte[] png = (byte[]) render.invoke(null, List.of(episode), rows);
        check("a spatial episode produces an image", png != null && png.length > 100);

        Object image = Class.forName("com.schecks.almin.Png").getMethod("decode", byte[].class)
            .invoke(null, (Object) png);
        int width = (int) image.getClass().getMethod("width").invoke(image);
        int height = (int) image.getClass().getMethod("height").invoke(image);
        int[] pixels = (int[]) image.getClass().getMethod("argb").invoke(image);
        check("the image stays at its fixed low-detail size", width == 768 && height == 512);
        long gold = java.util.Arrays.stream(pixels).filter(p -> ((p >> 16) & 255) > 140
            && ((p >> 8) & 255) > 70 && (p & 255) < 100).count();
        long red = java.util.Arrays.stream(pixels).filter(p -> ((p >> 16) & 255) > 140
            && ((p >> 8) & 255) < 110).count();
        check("placements and breaks are both visible", gold > 0 && red > 0);

        var system = Class.forName("com.schecks.almin.AiInsights").getDeclaredField("SYSTEM");
        system.setAccessible(true);
        String prompt = (String) system.get(null);
        check("the model is warned not to turn sparse bounds into a project",
            prompt.contains("bounding box") && prompt.contains("scattered placements")
                && prompt.contains("at most twelve words"));

        System.out.println(failures == 0 ? "AI IMAGE OK" : failures + " AI IMAGE FAILURES");
        if (failures > 0) System.exit(1);
    }
}
