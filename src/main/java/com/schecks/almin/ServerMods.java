package com.schecks.almin;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The jars in this server's own {@code mods/} folder.
 *
 * <h3>Not the same thing as the offer list</h3>
 * {@link ModOffers} is about <em>other people's</em> computers: a list of
 * suggestions sent to joining players, which they may accept or decline. This
 * is about this machine — the mods the server itself loads at boot. The two
 * lived in one tab under one heading for a long time and that was the wrong
 * shape, because "add a mod" meant two completely different acts depending on
 * which list you were looking at.
 *
 * <h3>Nothing is hot-loaded</h3>
 * Fabric reads {@code mods/} once, while the game is starting. Putting a jar
 * there changes nothing until the server restarts, and taking one away leaves
 * the running server exactly as it was. Every method here says so rather than
 * pretending otherwise: an install reports "on next start", and the panel
 * shows which entries the running process actually has loaded.
 *
 * <h3>Disabling rather than deleting</h3>
 * A jar is turned off by renaming it to {@code .jar.disabled}, which is the
 * convention every launcher already uses and which Fabric ignores because it
 * only reads {@code .jar}. That keeps "I think this mod is the problem" a
 * reversible act — deleting to test a theory is how people lose a mod they
 * cannot find again.
 *
 * <h3>What this can and cannot reach</h3>
 * Only {@code mods/}, only files whose name is a plain {@code .jar} (or the
 * disabled form of one), and only files that are really Fabric mod jars. A
 * path cannot be smuggled through a filename, and the folder is resolved and
 * normalised before anything is written.
 */
public final class ServerMods {

    /** One jar on this server, and what the running process makes of it. */
    public record Installed(String file, String modId, String name, String version,
                            long bytes, long modified, boolean loaded, boolean enabled,
                            boolean ours) {}

    /** The suffix a disabled jar carries. Fabric only ever reads ".jar". */
    public static final String OFF = ".disabled";

    /** Largest jar this will accept, matching what an offer may be. */
    public static final long MAX_BYTES = ModOffers.MAX_FILE_BYTES;

    private ServerMods() {}

    // ---------- where ----------

    /** The server's {@code mods/} folder, or null if there isn't one. */
    public static Path dir(MinecraftServer server) {
        if (server == null) return null;
        try {
            Path p = server.getServerDirectory().resolve("mods").toAbsolutePath().normalize();
            return Files.isDirectory(p) ? p : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * A path inside {@code mods/} for a name a browser sent, or null.
     *
     * <p>The same shape as {@link ModOffers#resolveModFile}: a bare filename,
     * ending in {@code .jar} or {@code .jar.disabled}, resolving to somewhere
     * that is still inside the folder after normalising.
     */
    public static Path resolve(MinecraftServer server, String name) {
        return resolve(dir(server), name);
    }

    /**
     * The same rule, against a folder rather than a server.
     *
     * <p>Every method here has this pair. None of the work needs a running
     * game — it is a directory, some filenames and a rename — and a version
     * that insists on a {@code MinecraftServer} cannot be tested at all,
     * which is a poor trade for the one line it saves.
     */
    static Path resolve(Path dir, String name) {
        if (dir == null || name == null) return null;
        String n = name.trim();
        if (n.isEmpty() || n.contains("/") || n.contains("\\") || n.contains("..")) return null;
        String lower = n.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".jar") && !lower.endsWith(".jar" + OFF)) return null;
        Path p = dir.resolve(n).toAbsolutePath().normalize();
        return p.startsWith(dir) ? p : null;
    }

    // ---------- reading ----------

    /**
     * Every jar in {@code mods/}, enabled ones first, then by name.
     *
     * <p>{@code loaded} is asked of the running loader rather than guessed
     * from the file: a jar dropped in ten minutes ago is present and not
     * loaded, and that difference is the single most useful thing this list
     * can say.
     */
    public static List<Installed> list(MinecraftServer server) {
        return list(dir(server));
    }

    static List<Installed> list(Path dir) {
        List<Installed> out = new ArrayList<>();
        if (dir == null) return out;
        try (var s = Files.list(dir)) {
            for (Path f : s.filter(Files::isRegularFile).toList()) {
                String name = f.getFileName().toString();
                String lower = name.toLowerCase(Locale.ROOT);
                boolean enabled = lower.endsWith(".jar");
                if (!enabled && !lower.endsWith(".jar" + OFF)) continue;
                ModJars.Meta meta = ModJars.read(f);
                long bytes = 0, modified = 0;
                try {
                    bytes = Files.size(f);
                    modified = Files.getLastModifiedTime(f).toMillis();
                } catch (IOException ignored) {
                    // A file that vanished mid-listing; the row is still worth having.
                }
                String id = meta.ok() ? meta.modId() : "";
                out.add(new Installed(name, id,
                    meta.ok() && !meta.name().isBlank() ? meta.name() : stem(name),
                    meta.version(), bytes, modified,
                    enabled && !id.isEmpty() && loaded(id), enabled,
                    Almin.MOD_ID.equals(id)));
            }
        } catch (IOException e) {
            AlminLog.warn("[almin] could not read mods/: {}", e.getMessage());
        }
        out.sort(Comparator.comparing((Installed m) -> !m.enabled())
            .thenComparing(m -> m.name().toLowerCase(Locale.ROOT)));
        return out;
    }

    /** Whether the running server actually has this mod id loaded. */
    private static boolean loaded(String modId) {
        try {
            return FabricLoader.getInstance().getModContainer(modId).isPresent();
        } catch (Throwable t) {
            return false;
        }
    }

    private static String stem(String file) {
        String n = file;
        if (n.toLowerCase(Locale.ROOT).endsWith(OFF)) n = n.substring(0, n.length() - OFF.length());
        if (n.toLowerCase(Locale.ROOT).endsWith(".jar")) n = n.substring(0, n.length() - 4);
        return n;
    }

    // ---------- changing ----------

    /** What one attempt to change the folder did. */
    public record Result(boolean ok, String message) {
        public static Result fail(String why) { return new Result(false, why); }
        public static Result done(String what) { return new Result(true, what); }
    }

    /**
     * Turns a jar on or off by renaming it.
     *
     * <p>Almin's own jar is refused: disabling the mod that is running the
     * panel would leave the next start with no panel and no way to put it
     * back except by hand on the machine.
     */
    public static Result setEnabled(MinecraftServer server, String file, boolean on) {
        return setEnabled(dir(server), file, on);
    }

    static Result setEnabled(Path dir, String file, boolean on) {
        Path p = resolve(dir, file);
        if (p == null || !Files.isRegularFile(p)) return Result.fail("No such jar in mods/.");
        String name = p.getFileName().toString();
        boolean isOn = name.toLowerCase(Locale.ROOT).endsWith(".jar");
        if (isOn == on) return Result.done(name + " was already " + (on ? "on" : "off") + ".");
        if (!on && ours(p)) {
            return Result.fail("That is Almin's own jar — turning it off would take the panel "
                + "with it at the next start.");
        }
        String next = on ? name.substring(0, name.length() - OFF.length()) : name + OFF;
        Path target = resolve(dir, next);
        if (target == null) return Result.fail("Could not work out the new name.");
        if (Files.exists(target)) return Result.fail(next + " is already there.");
        try {
            Files.move(p, target);
            AlminLog.info("[almin] mods/{} {}", name, on ? "enabled" : "disabled");
            return Result.done(stem(name) + " is " + (on ? "on" : "off")
                + " from the next start.");
        } catch (IOException e) {
            return Result.fail("Rename failed: " + e.getMessage());
        }
    }

    /** Deletes a jar outright. The running server is unaffected until it restarts. */
    public static Result delete(MinecraftServer server, String file) {
        return delete(dir(server), file);
    }

    static Result delete(Path dir, String file) {
        Path p = resolve(dir, file);
        if (p == null || !Files.isRegularFile(p)) return Result.fail("No such jar in mods/.");
        if (ours(p)) {
            return Result.fail("That is Almin's own jar. Use the updater, or replace it by hand.");
        }
        try {
            String name = p.getFileName().toString();
            Files.delete(p);
            AlminLog.info("[almin] deleted mods/{}", name);
            return Result.done(stem(name) + " is gone from the next start.");
        } catch (IOException e) {
            return Result.fail("Delete failed: " + e.getMessage());
        }
    }

    /**
     * Moves a finished download or upload into {@code mods/}.
     *
     * <p>The file has already been written somewhere else and checked; this is
     * only the last step, kept separate so nothing half-written is ever
     * visible in the folder Fabric reads.
     *
     * @param preferred the filename to use, which must survive {@link #resolve}
     */
    public static Result install(MinecraftServer server, Path staged, String preferred,
                                 boolean replace) {
        return install(dir(server), staged, preferred, replace);
    }

    static Result install(Path dir, Path staged, String preferred, boolean replace) {
        Path target = resolve(dir, preferred);
        if (target == null) {
            return Result.fail("Filename must be a plain .jar name, e.g. sodium-0.5.11.jar");
        }
        if (!replace && Files.exists(target)) {
            return Result.fail(preferred + " is already in mods/.");
        }
        try {
            Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
            AlminLog.info("[almin] installed mods/{}", preferred);
            ModJars.Meta meta = ModJars.read(target);
            String what = meta.ok() && !meta.name().isBlank() ? meta.name() : preferred;
            return Result.done(what + " is installed. It loads at the next server start.");
        } catch (IOException e) {
            return Result.fail("Could not put it in mods/: " + e.getMessage());
        }
    }

    /** Whether a jar is the one this very process is running from. */
    private static boolean ours(Path jar) {
        ModJars.Meta meta = ModJars.read(jar);
        return meta.ok() && Almin.MOD_ID.equals(meta.modId());
    }
}
