import com.schecks.almin.AlminConfig;
import com.schecks.almin.WebFiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Recursive folder deletion, including its two no-partial-delete guardrails. */
public class DeleteFolderTests {
    static int fail = 0;
    static void ck(String what, boolean ok, String detail) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what
            + (ok ? "" : "  -> " + detail));
        if (!ok) fail++;
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("almin-delete");

        var configConstructor = AlminConfig.class.getDeclaredConstructor();
        configConstructor.setAccessible(true);
        AlminConfig defaults = configConstructor.newInstance();
        ck("world is deletable by default",
            defaults.dirDeletableRootsAsSet().contains("world"),
            defaults.dirDeletableRoots);
        ck("world is not made writable by delete permission",
            !defaults.dirWritableRootsAsSet().contains("world"),
            defaults.dirWritableRoots);
        ck("the deletable-folder list is exposed as a config key",
            AlminConfig.keyByName("dir-deletable-roots") != null,
            "missing dir-deletable-roots");

        Path datapacks = root.resolve("world/datapacks");
        ck("the configured world folder passes the delete policy",
            WebFiles.isDeletable(root, defaults.dirDeletableRootsAsSet(), datapacks,
                root.resolve("world")), "world was refused");
        ck("an unlisted server folder is still protected",
            !WebFiles.isDeletable(root, defaults.dirDeletableRootsAsSet(), datapacks,
                root.resolve("logs")), "logs was allowed");
        ck("the server root itself can never be deleted",
            !WebFiles.isDeletable(root, defaults.dirDeletableRootsAsSet(), datapacks, root),
            "server root was allowed");
        ck("a path outside the server can never be deleted",
            !WebFiles.isDeletable(root, defaults.dirDeletableRootsAsSet(), datapacks,
                root.getParent().resolve("outside")), "outside path was allowed");

        Path tree = root.resolve("config/pack");
        Files.createDirectories(tree.resolve("nested/deeper"));
        Files.writeString(tree.resolve("one.txt"), "one");
        Files.writeString(tree.resolve("nested/two.txt"), "two");
        Files.writeString(tree.resolve("nested/deeper/three.txt"), "three");
        WebFiles.Result removed = WebFiles.deleteTree(tree, List.of());
        ck("a non-empty folder tree is deleted", removed.ok(), removed.message());
        ck("the selected folder itself is gone", !Files.exists(tree), tree.toString());

        Path file = root.resolve("mods/example.jar");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "jar");
        removed = WebFiles.deleteTree(file, List.of());
        ck("ordinary file deletion still works", removed.ok() && !Files.exists(file),
            removed.message());

        Path guarded = root.resolve("config/almin");
        Path protectedFile = guarded.resolve("ai.key");
        Files.createDirectories(guarded.resolve("child"));
        Files.writeString(guarded.resolve("child/config.json"), "{}");
        Files.writeString(protectedFile, "secret");
        WebFiles.Result refused = WebFiles.deleteTree(guarded, List.of(protectedFile));
        ck("a tree containing a protected file is refused", !refused.ok(), refused.message());
        ck("protection happens before any sibling is deleted",
            Files.exists(guarded.resolve("child/config.json"))
                && Files.exists(protectedFile), "tree was partially deleted");

        Path outside = root.resolve("outside");
        Path linkedTree = root.resolve("shared/links");
        Files.createDirectories(outside);
        Files.createDirectories(linkedTree);
        Files.writeString(outside.resolve("keep.txt"), "keep");
        boolean symlinkMade = false;
        try {
            Files.createSymbolicLink(linkedTree.resolve("outside-link"), outside);
            symlinkMade = true;
        } catch (UnsupportedOperationException | java.io.IOException ignored) {
            // Some CI filesystems disallow symlinks; the implementation still
            // uses a non-following file-tree walk.
        }
        removed = WebFiles.deleteTree(linkedTree, List.of());
        ck("a folder containing a symlink can be deleted", removed.ok(), removed.message());
        ck("recursive deletion never follows a symlink outside the folder",
            !symlinkMade || Files.exists(outside.resolve("keep.txt")), "outside file was deleted");

        String command = Files.readString(Path.of(
            "src/main/java/com/schecks/almin/commands/AlminCommand.java"));
        ck("the in-game command uses the same guarded implementation",
            command.contains("WebFiles.delete(server, relPath)"), "duplicate delete path");
        String page = Files.readString(Path.of(
            "src/main/java/com/schecks/almin/WebPage.java"));
        ck("the web confirmation warns that folder contents are included",
            page.contains("everything inside it"), "old empty-folder warning remains");
        ck("the web panel gates delete independently from write",
            page.contains("disabled:!e.deletable")
                && page.contains("disabled:!e.writable"),
            "delete still follows writable");

        System.out.println(fail == 0 ? "\nDELETE-FOLDER TESTS PASSED"
            : "\n" + fail + " FAILED");
        System.exit(fail == 0 ? 0 : 1);
    }
}
