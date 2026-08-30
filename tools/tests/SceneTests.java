import java.nio.file.Files;
import java.nio.file.Path;

/** Durable wiring and safety checks for the activity-map 3D scenes. */
public class SceneTests {
    static int failures;

    static void check(String label, boolean ok, String detail) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + label
            + (ok ? "" : " -> " + detail));
        if (!ok) failures++;
    }

    public static void main(String[] args) throws Exception {
        String context = Files.readString(Path.of(
            "src/main/java/com/schecks/almin/SceneContext.java"));
        String web = Files.readString(Path.of(
            "src/main/java/com/schecks/almin/WebUi.java"));
        String page = Files.readString(Path.of(
            "src/main/java/com/schecks/almin/WebPage.java"));

        check("world context skips chunks that are not already loaded",
            context.contains("if (!level.hasChunkAt(cursor)) continue;")
                && context.contains("beside == null || beside.isAir()"),
            "a scene may load or generate terrain");
        check("world context sends exposed blocks rather than a solid volume",
            context.contains("|| !exposed(states, dx, dy, dz, side, height)")
                && context.contains("MAX_BLOCKS"),
            "the response is unbounded or full of buried blocks");
        check("the live-world route is authenticated",
            web.contains("/api/scene/context")
                && section(web, "private void handleSceneContext", 500).contains("requireAuth(ex)"),
            "the route is missing or public");
        check("the browser asks for the live context only for a scene",
            page.contains("async function loadSceneContext")
                && page.contains("jget(q)")
                && page.contains("/api/scene/context?dim="),
            "the scene never loads its surroundings");
        check("the scene has a numbered 3D grid and inspectable blocks",
            page.contains("function sceneGridSvg")
                && page.contains("'Y '+y")
                && page.contains("function wireSceneInspect")
                && page.contains("data-sc-what"),
            "the coordinate or inspection layer is missing");
        check("recorded players keep Y as well as X and Z",
            page.contains("players.push({player:who,x:p.x-e.x,y:p.y,z:p.z-e.z")
                && page.contains("function scenePlayer")
                && page.contains("p.wx+','+p.y+','+p.wz"),
            "player altitude is not carried through the scene");
        check("build marks can be expanded without disabling the 3D badge",
            page.contains("sceneEvents:false")
                && page.contains("id=\"t-scene-events\"")
                && page.contains("mapOpts.sceneEvents=!mapOpts.sceneEvents"),
            "the collapse has no visible escape hatch");

        System.out.println(failures == 0 ? "\nSCENE TESTS PASSED" : "\n" + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    static String section(String text, String start, int length) {
        int at = text.indexOf(start);
        return at < 0 ? "" : text.substring(at, Math.min(text.length(), at + length));
    }
}
