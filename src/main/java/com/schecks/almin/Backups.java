package com.schecks.almin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Copies of the world, made on purpose and made on a clock.
 *
 * <h3>What a backup is here</h3>
 * One zip holding the world folder and the handful of server files that decide
 * who may join it — {@code server.properties}, the op, whitelist and ban
 * lists. Not the mods, not the configs, not Almin's own folder: those are the
 * things you can put back by reinstalling, and the world is the thing you
 * cannot. A backup that took an hour and filled a disk because it also copied
 * every jar is a backup nobody keeps enough of.
 *
 * <h3>Taking one safely</h3>
 * The same three steps an admin does by hand. Autosave is turned off and every
 * level is flushed to disk first, so what is copied is a world that has
 * finished being written; the copy happens off the server thread, so the game
 * keeps running while it does; autosave goes back on afterwards whether the
 * copy worked or not. A backup taken without that is a copy of a world
 * mid-write, which is the kind that restores into a hole where somebody's base
 * was.
 *
 * <h3>Deleting them again</h3>
 * Three limits, all applied after each new backup: how many to keep, how old
 * is too old, and how much disk the whole folder may use. The newest is never
 * deleted by any of them — a rule that can throw away the only copy is not a
 * retention policy, it is a bug waiting for the day the disk fills.
 *
 * <h3>What this deliberately does not do</h3>
 * Restore. Putting a world back means stopping the server, moving the live
 * world out of the way and unpacking over it, and a button that does that from
 * a browser is one misclick from deleting exactly what these files exist to
 * protect. The zips are ordinary zips: download one, stop the server, unpack
 * it yourself.
 */
public final class Backups {

    /** Server files worth carrying with the world, because they decide who gets into it. */
    private static final String[] EXTRAS = {
        "server.properties", "ops.json", "whitelist.json",
        "banned-players.json", "banned-ips.json"
    };

    /** What a backup file is called. Sorts chronologically as text, which the listing relies on. */
    private static final DateTimeFormatter STAMP =
        DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneId.systemDefault());

    private static final String PREFIX = "world-";
    private static final String SUFFIX = ".zip";

    /** Refuse to start one that would obviously not fit. */
    private static final long HEADROOM_BYTES = 256L * 1024 * 1024;

    private Backups() {}

    // ---------- what the panel is shown ----------

    /**
     * One backup on the disk.
     *
     * @param auto whether it was made by the clock rather than by somebody
     */
    public record Entry(String name, long bytes, long at, boolean auto) {}

    /**
     * What is happening right now.
     *
     * @param step   which of the three parts it is in, in words
     * @param done   files copied so far
     * @param total  files to copy, or 0 before that is known
     */
    public record State(boolean running, String step, int done, int total,
                        long startedAt, String message, boolean failed) {
        static State idle(String message, boolean failed) {
            return new State(false, "", 0, 0, 0, message, failed);
        }
    }

    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static volatile State state = State.idle("", false);
    private static volatile long lastRun;
    private static volatile long lastActivity;
    private static ScheduledExecutorService timer;
    private static MinecraftServer server;

    /** Starts the clock. Called once, when the server is up. */
    public static synchronized void init(MinecraftServer mc) {
        server = mc;
        close();
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Almin-backup");
            t.setDaemon(true);
            return t;
        });
        // A minute is fine: the interval is in hours, and checking often only
        // means the first one after a config change happens soon rather than
        // in an hour.
        timer.scheduleWithFixedDelay(Backups::maybeAuto, 60, 60, TimeUnit.SECONDS);
    }

    public static synchronized void close() {
        if (timer != null) { timer.shutdownNow(); timer = null; }
    }

    public static State state() { return state; }

    public static long lastRun() { return lastRun; }

    /** Something happened in the world, so the next automatic backup is worth taking. */
    public static void noteActivity() {
        lastActivity = System.currentTimeMillis();
    }

    // ---------- where they live ----------

    /**
     * The backup folder, made if it is not there.
     *
     * <p>Relative names land beside the server; an absolute one is honoured as
     * given, because "put them on the other disk" is the single most useful
     * thing this setting can say.
     */
    public static Path folder(MinecraftServer mc) {
        return mc == null ? null : folderIn(mc.getServerDirectory());
    }

    /**
     * The same, from a plain path.
     *
     * <p>Everything below this line is about a folder of zips rather than
     * about a running server, and takes a path for that reason: the retention
     * rules are the part most worth testing and the part a test can least
     * easily stand a Minecraft server up for.
     */
    public static Path folderIn(Path serverDir) {
        if (serverDir == null) return null;
        String set = AlminConfig.get().backupFolder;
        String name = set == null || set.isBlank() ? "backups" : set.trim();
        Path base = serverDir.toAbsolutePath().normalize();
        Path p = Path.of(name);
        return (p.isAbsolute() ? p : base.resolve(p)).normalize();
    }

    /** Every backup on the disk, newest first. */
    public static List<Entry> list(MinecraftServer mc) {
        return listIn(folder(mc));
    }

    /** Every backup in one folder, newest first. */
    public static List<Entry> listIn(Path dir) {
        List<Entry> out = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) return out;
        try (var files = Files.list(dir)) {
            for (Path f : files.toList()) {
                String n = f.getFileName().toString();
                if (!n.startsWith(PREFIX) || !n.endsWith(SUFFIX)) continue;
                if (!Files.isRegularFile(f)) continue;
                out.add(new Entry(n, Files.size(f),
                    Files.getLastModifiedTime(f).toMillis(), n.contains("-auto")));
            }
        } catch (IOException e) {
            AlminLog.warn("[almin] could not list backups: {}", e.getMessage());
        }
        out.sort(Comparator.comparingLong(Entry::at).reversed());
        return out;
    }

    /**
     * One backup by name.
     *
     * <p>The name is looked for in the listing rather than resolved against
     * the folder, so nothing a request sends can name a file that is not one
     * of these — including by climbing out of the folder with dots.
     */
    public static Path fileOf(MinecraftServer mc, String name) {
        return fileIn(folder(mc), name);
    }

    /** The same, in one folder. */
    public static Path fileIn(Path dir, String name) {
        if (dir == null || name == null || name.isEmpty()) return null;
        for (Entry e : listIn(dir)) {
            if (e.name().equals(name)) return dir.resolve(e.name());
        }
        return null;
    }

    /** How much disk the whole folder is using. */
    public static long totalBytes(MinecraftServer mc) {
        long sum = 0;
        for (Entry e : list(mc)) sum += e.bytes();
        return sum;
    }

    // ---------- making one ----------

    /** What a request to make or delete one came back with. */
    public record Result(boolean ok, String message) {}

    /**
     * Starts a backup and returns straight away.
     *
     * <p>Never two at once, and never on top of a world that is still being
     * written by the last one.
     */
    public static Result start(boolean auto, String who) {
        MinecraftServer mc = server;
        if (mc == null) return new Result(false, "There is no server to back up.");
        if (!running.compareAndSet(false, true)) {
            return new Result(false, "A backup is already running.");
        }
        state = new State(true, "getting ready", 0, 0, System.currentTimeMillis(), "", false);
        Thread t = new Thread(() -> run(mc, auto, who), "Almin-backup-run");
        t.setDaemon(true);
        t.start();
        return new Result(true, "Backup started.");
    }

    private static void run(MinecraftServer mc, boolean auto, String who) {
        long began = System.currentTimeMillis();
        Path target = null;
        try {
            Path world = mc.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            Path dir = folder(mc);
            Files.createDirectories(dir);

            long need = sizeOf(world);
            long free = Files.getFileStore(dir).getUsableSpace();
            // A zip of a world is smaller than the world, but not reliably so:
            // region files are already compressed. Refusing on the raw size is
            // the cautious answer and the one that never fills a disk.
            if (free < need + HEADROOM_BYTES) {
                finish(false, "Not enough room: the world is " + human(need)
                    + " and there is " + human(free) + " free.");
                return;
            }

            String name = PREFIX + STAMP.format(Instant.ofEpochMilli(began))
                + (auto ? "-auto" : "") + SUFFIX;
            target = dir.resolve(name);

            step("flushing the world", 0, 0);
            boolean held = hold(mc, true);
            try {
                List<Path> files = collect(world, mc);
                step("copying", 0, files.size());
                zip(world, files, target, mc);
            } finally {
                if (held) hold(mc, false);
            }

            long bytes = Files.size(target);
            lastRun = began;
            AlminLog.info("[almin] backup {} written by {} ({})", name, who, human(bytes));
            step("tidying up", 0, 0);
            String swept = sweep(mc);
            finish(true, "Backed up " + human(bytes) + " to " + name
                + (swept.isEmpty() ? "" : " · " + swept));
        } catch (Throwable t) {
            AlminLog.warn("[almin] backup failed: {}", t.toString());
            // A half-written zip is worse than none: it looks like a backup.
            if (target != null) {
                try { Files.deleteIfExists(target); } catch (IOException ignored) { }
            }
            finish(false, "Backup failed: " + t.getMessage());
        } finally {
            running.set(false);
        }
    }

    private static void step(String what, int done, int total) {
        State s = state;
        state = new State(true, what, done, total, s.startedAt(), "", false);
    }

    private static void finish(boolean ok, String message) {
        state = State.idle(message, !ok);
    }

    /**
     * Autosave off, everything flushed; and back on again.
     *
     * <p>On the server thread, because both of those are the server's own
     * bookkeeping. The copy itself is not — it takes as long as the world is
     * big, and doing that on the tick loop would be a freeze rather than a
     * backup.
     */
    private static boolean hold(MinecraftServer mc, boolean on) {
        try {
            return mc.submit(() -> {
                if (on) {
                    mc.setAutoSave(false);
                    for (ServerLevel level : mc.getAllLevels()) level.noSave = true;
                    mc.saveEverything(true, true, true);
                } else {
                    for (ServerLevel level : mc.getAllLevels()) level.noSave = false;
                    mc.setAutoSave(true);
                }
                return true;
            }).get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            AlminLog.warn("[almin] could not {} saving for the backup: {}",
                on ? "pause" : "resume", e.toString());
            return false;
        }
    }

    private static List<Path> collect(Path world, MinecraftServer mc) throws IOException {
        List<Path> out = new ArrayList<>();
        Path backups = folder(mc);
        Files.walkFileTree(world, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                // Somebody pointing the backup folder inside the world would
                // otherwise back up every previous backup, every time.
                return dir.equals(backups) ? FileVisitResult.SKIP_SUBTREE
                                           : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                // The lock the running server holds. Copying it is harmless and
                // unpacking it over a live world is not, so it is left out.
                if (!f.getFileName().toString().equals("session.lock")) out.add(f);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path f, IOException e) {
                AlminLog.warn("[almin] backup skipped {}: {}", f.getFileName(), e.getMessage());
                return FileVisitResult.CONTINUE;
            }
        });
        return out;
    }

    private static void zip(Path world, List<Path> files, Path target, MinecraftServer mc)
            throws IOException {
        Path base = world.getParent();
        String worldName = world.getFileName().toString();
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        try (OutputStream raw = Files.newOutputStream(tmp);
             ZipOutputStream zip = new ZipOutputStream(raw)) {
            int done = 0;
            for (Path f : files) {
                String rel = worldName + "/" + world.relativize(f).toString().replace('\\', '/');
                try {
                    put(zip, rel, f);
                } catch (IOException e) {
                    // A file that vanished between listing and copying is
                    // normal on a live world, and is not a failed backup.
                    AlminLog.warn("[almin] backup skipped {}: {}", rel, e.getMessage());
                }
                if (++done % 200 == 0) step("copying", done, files.size());
            }
            step("copying", files.size(), files.size());
            for (String extra : EXTRAS) {
                Path f = base == null ? null : base.resolve(extra);
                if (f == null || !Files.isRegularFile(f)) continue;
                try { put(zip, extra, f); }
                catch (IOException e) {
                    AlminLog.warn("[almin] backup skipped {}: {}", extra, e.getMessage());
                }
            }
        }
        Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static void put(ZipOutputStream zip, String name, Path f) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(Files.getLastModifiedTime(f).toMillis());
        zip.putNextEntry(entry);
        Files.copy(f, zip);
        zip.closeEntry();
    }

    private static long sizeOf(Path dir) {
        final long[] sum = {0};
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                    sum[0] += a.size();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path f, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // A partial answer is still a useful one for a headroom check.
        }
        return sum[0];
    }

    // ---------- deleting them again ----------

    /**
     * Applies the three limits, newest first.
     *
     * <p>The newest is never a candidate, whatever the limits say. A policy
     * that can delete the only copy is not a policy.
     */
    static String sweep(MinecraftServer mc) {
        return sweepIn(folder(mc));
    }

    /** The same, over one folder. */
    public static String sweepIn(Path dir) {
        AlminConfig cfg = AlminConfig.get();
        List<Entry> all = listIn(dir);
        if (all.size() <= 1) return "";
        long now = System.currentTimeMillis();
        long maxBytes = cfg.backupMaxGb <= 0 ? Long.MAX_VALUE : cfg.backupMaxGb * 1024L * 1024L * 1024L;
        long ageLimit = cfg.backupKeepDays <= 0 ? Long.MAX_VALUE : cfg.backupKeepDays * 86_400_000L;
        int keep = Math.max(1, cfg.backupKeep);

        long running = 0;
        List<Entry> doomed = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            Entry e = all.get(i);
            running += e.bytes();
            if (i == 0) continue;                       // the newest, always
            if (i >= keep) { doomed.add(e); continue; }
            if (now - e.at() > ageLimit) { doomed.add(e); continue; }
            if (running > maxBytes) doomed.add(e);
        }
        if (doomed.isEmpty()) return "";
        int gone = 0;
        long freed = 0;
        for (Entry e : doomed) {
            try {
                Files.deleteIfExists(dir.resolve(e.name()));
                gone++;
                freed += e.bytes();
                AlminLog.info("[almin] backup {} deleted by the retention rules", e.name());
            } catch (IOException ex) {
                AlminLog.warn("[almin] could not delete {}: {}", e.name(), ex.getMessage());
            }
        }
        return gone == 0 ? "" : "deleted " + gone + " old backup" + (gone == 1 ? "" : "s")
            + " (" + human(freed) + ")";
    }

    /** Deletes one, by name, because somebody asked. */
    public static Result delete(MinecraftServer mc, String name) {
        return deleteIn(folder(mc), name);
    }

    /** The same, in one folder. */
    public static Result deleteIn(Path dir, String name) {
        Path f = fileIn(dir, name);
        if (f == null) return new Result(false, "There is no backup by that name.");
        try {
            Files.delete(f);
            return new Result(true, "Deleted " + name + ".");
        } catch (IOException e) {
            return new Result(false, "Could not delete it: " + e.getMessage());
        }
    }

    // ---------- the clock ----------

    /**
     * The automatic one, if it is switched on and due.
     *
     * <p>Off unless somebody turns it on. A mod that starts writing gigabytes
     * to a disk it was not asked about is a mod that gets uninstalled, so this
     * is the one setting that has to be a decision rather than a default.
     */
    private static void maybeAuto() {
        try {
            AlminConfig cfg = AlminConfig.get();
            if (!cfg.backupEnabled || server == null) return;
            int hours = Math.max(1, cfg.backupIntervalHours);
            long now = System.currentTimeMillis();
            if (lastRun == 0) {
                // Nothing this run has taken. Count from the newest on disk, so
                // restarting the server is not a way to get a backup every time.
                List<Entry> have = list(server);
                lastRun = have.isEmpty() ? 0 : have.get(0).at();
                if (lastRun == 0) lastRun = now - hours * 3_600_000L;
            }
            if (now - lastRun < hours * 3_600_000L) return;
            // Nothing has happened since the last one, so it would be a second
            // copy of the same world. The clock still moves, or a quiet server
            // would try again every minute.
            if (cfg.backupSkipUnchanged && lastActivity <= lastRun) {
                lastRun = now;
                return;
            }
            start(true, "the clock");
        } catch (Throwable t) {
            AlminLog.warn("[almin] automatic backup check failed: {}", t.toString());
        }
    }

    // ---------- words ----------

    public static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] u = {"KB", "MB", "GB", "TB"};
        double v = bytes / 1024.0;
        int i = 0;
        while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
        return String.format(Locale.ROOT, v >= 100 ? "%.0f %s" : v >= 10 ? "%.1f %s" : "%.2f %s",
            v, u[i]);
    }
}
