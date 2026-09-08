package com.schecks.almin;

import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which files in {@code config/} belong to which mod.
 *
 * <h3>Why this exists</h3>
 * Configuring a Fabric mod on a server means opening a file browser, knowing
 * that the mod calls itself {@code ftbchunks} rather than "FTB Chunks",
 * finding the right one of the ninety files in {@code config/}, and editing
 * it. Every step of that is knowledge the panel already has and the person
 * does not. The Mods menu lists the mod; it should be the thing that opens its
 * settings.
 *
 * <h3>How a file is matched to a mod</h3>
 * There is no standard. Fabric has nothing to say about where a mod keeps its
 * configuration, so mods pick their own, and in practice they pick one of a
 * small number of shapes: a folder named after the mod id, a file named after
 * it with one of a handful of extensions, or one of those with a suffix. All
 * of those are matched, in that order, plus the same shapes against the jar's
 * own filename for the mods whose id and jar disagree.
 *
 * <p>Almin's own {@code config/almin/} is the one folder deliberately not
 * matched: it holds the panel's own credentials, and the Settings menu is
 * where those belong.
 *
 * <p>It will miss some. A mod that writes {@code config/mycoolmod-v2/} under a
 * name nothing can be derived from is not found, and that is fine: the file
 * browser is still there, and a menu that finds most of them is worth having.
 * What it must never do is claim a file belongs to a mod when it does not,
 * because the panel offers to edit what it finds.
 *
 * <h3>What it will not reach</h3>
 * Only {@code config/}, only text-shaped files, only files small enough to put
 * in an editor, and only paths this class derived itself. A caller hands back
 * a path it was given; it is looked for in the list again rather than
 * resolved, so a path that was never offered cannot be read whatever it says.
 */
public final class ModConfigs {

    /** Extensions a mod's settings are actually written in. */
    private static final List<String> EXTENSIONS = List.of(
        "json", "json5", "jsonc", "toml", "conf", "hocon", "properties", "cfg",
        "yaml", "yml", "txt", "ini", "snbt", "xml");

    /** Files listed for one mod. Past this it is a data folder, not settings. */
    private static final int MAX_FILES = 40;

    /** How far into a mod's own folder to look. Deep trees are storage. */
    private static final int MAX_DEPTH = 3;

    /** Largest file the panel will offer to edit. */
    public static final long MAX_BYTES = 2L * 1024 * 1024;

    private ModConfigs() {}

    /** One settings file, as the panel needs it. */
    public record Entry(String path, String name, long bytes, long modified,
                        boolean editable) {}

    // ---------- where ----------

    /** The server's {@code config/} folder, or null if there isn't one. */
    public static Path dir(MinecraftServer server) {
        if (server == null) return null;
        try {
            Path p = server.getServerDirectory().resolve("config").toAbsolutePath().normalize();
            return Files.isDirectory(p) ? p : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---------- finding ----------

    /** What this mod's settings look like they are. */
    public static List<Entry> of(MinecraftServer server, String modId, String jarFile) {
        return of(dir(server), modId, jarFile);
    }

    /**
     * The same, against a folder.
     *
     * <p>None of this needs a running game — it is a directory and some
     * filenames — and a version that insisted on a {@link MinecraftServer}
     * could not be tested at all.
     */
    public static List<Entry> of(Path dir, String modId, String jarFile) {
        List<Entry> out = new ArrayList<>();
        if (dir == null) return out;

        Set<Path> found = new LinkedHashSet<>();
        for (String stem : stems(modId, jarFile)) {
            // A folder of its own is the tidiest shape and the commonest for
            // anything with more than one setting.
            Path folder = safe(dir, stem);
            if (folder != null && Files.isDirectory(folder)) walk(dir, folder, found);
            // config/<stem>.json, and the rest of the spellings.
            for (String ext : EXTENSIONS) {
                Path f = safe(dir, stem + "." + ext);
                if (f != null && Files.isRegularFile(f)) found.add(f);
            }
        }
        // config/<stem>-client.toml, config/<stem>_server.json: one mod, several
        // files, named after itself with something on the end.
        prefixed(dir, stems(modId, jarFile), found);

        Path ours = safe(dir, "almin");
        for (Path f : found) {
            if (out.size() >= MAX_FILES) break;
            // Almin's own folder is never offered here, whatever stem reached
            // it. It holds the panel's password, its accounts and its AI key,
            // and the Mods menu is a different door from the one those are
            // behind: an account allowed to configure mods is not thereby
            // allowed to read the settings that decide who may log in.
            if (ours != null && f.startsWith(ours)) continue;
            out.add(entry(dir, f));
        }
        out.sort(Comparator.comparing(Entry::path));
        return out;
    }

    /**
     * The path for one file this mod owns, or null.
     *
     * <p>Deliberately not a resolve: the request's path is compared against
     * what {@link #of} found rather than turned into a path of its own. A
     * caller cannot reach a file by naming it, only by naming one that was
     * already offered — which makes the containment question the same
     * question as the ownership one, and leaves one place for both to be
     * wrong rather than two.
     */
    public static Path file(MinecraftServer server, String modId, String jarFile, String rel) {
        return file(dir(server), modId, jarFile, rel);
    }

    public static Path file(Path dir, String modId, String jarFile, String rel) {
        if (dir == null || rel == null || rel.isEmpty()) return null;
        for (Entry e : of(dir, modId, jarFile)) {
            if (!e.path().equals(rel)) continue;
            Path p = dir.resolve(rel).toAbsolutePath().normalize();
            return p.startsWith(dir) && Files.isRegularFile(p) ? p : null;
        }
        return null;
    }

    // ---------- the shapes a name comes in ----------

    /**
     * The names a mod's settings might be filed under.
     *
     * <p>The id first, because it is what a mod usually uses; then the jar's
     * own stem with its version taken off, for the ones where they disagree;
     * then both with hyphens and underscores swapped, because a mod that calls
     * itself {@code some-mod} sometimes writes {@code some_mod.json}.
     */
    public static List<String> stems(String modId, String jarFile) {
        Set<String> out = new LinkedHashSet<>();
        add(out, modId);
        add(out, jarStem(jarFile));
        for (String s : new ArrayList<>(out)) {
            add(out, s.replace('-', '_'));
            add(out, s.replace('_', '-'));
        }
        return new ArrayList<>(out);
    }

    private static void add(Set<String> out, String s) {
        if (s == null) return;
        String t = s.trim().toLowerCase(Locale.ROOT);
        // A stem has to be a plain name. Anything with a separator in it is
        // either a path or a mod id that would not be a filename anyway.
        if (t.isEmpty() || t.length() > 64) return;
        if (t.contains("/") || t.contains("\\") || t.contains("..")) return;
        out.add(t);
    }

    /** "sodium-fabric-0.5.8.jar" is filed under "sodium-fabric", not the version. */
    public static String jarStem(String jarFile) {
        if (jarFile == null) return "";
        String n = jarFile;
        String lower = n.toLowerCase(Locale.ROOT);
        if (lower.endsWith(ServerMods.OFF)) n = n.substring(0, n.length() - ServerMods.OFF.length());
        lower = n.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jar")) n = n.substring(0, n.length() - 4);
        // Everything from the first version-looking part onwards is a version.
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("[-_+](?:v)?\\d").matcher(n);
        if (m.find()) n = n.substring(0, m.start());
        return n;
    }

    // ---------- the walk ----------

    private static void walk(Path root, Path folder, Set<Path> into) {
        try (var s = Files.walk(folder, MAX_DEPTH)) {
            for (Path f : s.filter(Files::isRegularFile).toList()) {
                if (into.size() >= MAX_FILES) return;
                Path p = f.toAbsolutePath().normalize();
                // Belt and braces: a symlink inside a mod's config folder
                // pointing at /etc would otherwise be walked into.
                if (!p.startsWith(root)) continue;
                if (!texty(p.getFileName().toString())) continue;
                into.add(p);
            }
        } catch (IOException e) {
            AlminLog.warn("[almin] could not read {}: {}", folder, e.getMessage());
        }
    }

    private static void prefixed(Path dir, List<String> stems, Set<Path> into) {
        try (var s = Files.list(dir)) {
            for (Path f : s.filter(Files::isRegularFile).toList()) {
                if (into.size() >= MAX_FILES) return;
                String name = f.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!texty(name)) continue;
                for (String stem : stems) {
                    if (name.length() <= stem.length()) continue;
                    if (!name.startsWith(stem)) continue;
                    // The next character has to be a separator, or "sodium"
                    // would claim "sodiumextra.json", which is another mod.
                    char next = name.charAt(stem.length());
                    if (next != '-' && next != '_' && next != '.') continue;
                    if (next == '.' && name.indexOf('.', stem.length() + 1) < 0) continue;
                    into.add(f.toAbsolutePath().normalize());
                    break;
                }
            }
        } catch (IOException e) {
            AlminLog.warn("[almin] could not read config/: {}", e.getMessage());
        }
    }

    private static Path safe(Path dir, String name) {
        try {
            Path p = dir.resolve(name).toAbsolutePath().normalize();
            return p.startsWith(dir) ? p : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Entry entry(Path dir, Path f) {
        long bytes = 0, modified = 0;
        try {
            bytes = Files.size(f);
            modified = Files.getLastModifiedTime(f).toMillis();
        } catch (IOException ignored) {
            // A file that vanished mid-listing; the row is still worth having.
        }
        String rel = dir.relativize(f).toString().replace('\\', '/');
        return new Entry(rel, f.getFileName().toString(), bytes, modified,
            bytes <= MAX_BYTES);
    }

    /** Whether this is a file somebody could sensibly be shown in an editor. */
    private static boolean texty(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        return EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}
