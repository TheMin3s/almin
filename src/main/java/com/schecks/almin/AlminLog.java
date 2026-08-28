package com.schecks.almin;

import org.slf4j.helpers.MessageFormatter;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Plain-text log file dedicated to Almin events at
 *   {server-root}/config/almin/almin.log
 *
 * Self-contained — never propagates to the main server console / latest.log.
 * If the log file can't be opened or written, messages are silently dropped
 * (the alternative would route them through Log4j2 and pollute the console,
 * which the mod explicitly avoids).
 *
 * Auto-rotation: the file is truncated every {@link #AUTO_CLEAR_HOURS} hours
 * by a daemon scheduled thread. Manual {@code /almin op clearlog} works the
 * same way, both routed through {@link #clearInternal(String)}.
 *
 * Threading: every public mutator is synchronized on the class. Safe to call
 * from the server tick thread, async fetch callbacks, or the auto-clear thread.
 */
public final class AlminLog {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final long AUTO_CLEAR_HOURS = 6;

    private static volatile PrintWriter writer;
    private static volatile Path logPath;
    private static ScheduledExecutorService scheduler;

    private AlminLog() {}

    public static synchronized void init(Path serverDir) {
        if (writer != null) return;
        try {
            Path dir = serverDir.resolve("config").resolve("almin");
            Files.createDirectories(dir);
            Path file = dir.resolve("almin.log");
            writer = new PrintWriter(
                Files.newBufferedWriter(
                    file,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE
                ),
                true /* autoFlush */
            );
            logPath = file;
            writeLine("INFO", "--- Almin log opened ---");
            startAutoClear();
        } catch (IOException e) {
            // Intentionally silent: routing this error to the main logger would
            // surface it on the server console, which is exactly what this
            // class exists to avoid.
            writer = null;
        }
    }

    public static synchronized void close() {
        stopAutoClear();
        if (writer == null) return;
        writeLine("INFO", "--- Almin log closed ---");
        try {
            writer.flush();
            writer.close();
        } catch (Exception ignored) {}
        writer = null;
    }

    public static Path logPath() {
        return logPath;
    }

    public static void info(String pattern, Object... args)  { write("INFO",  pattern, args); }
    public static void warn(String pattern, Object... args)  { write("WARN",  pattern, args); }
    public static void error(String pattern, Object... args) { write("ERROR", pattern, args); }

    private static synchronized void write(String level, String pattern, Object... args) {
        String msg = MessageFormatter.arrayFormat(pattern, args).getMessage();
        writeLine(level, msg);
    }

    private static void writeLine(String level, String msg) {
        // No fallback — if the writer isn't open, the message is dropped.
        // Routing failures to the main logger would defeat the whole point.
        if (writer == null) return;
        writer.println("[" + LocalDateTime.now().format(TS) + "] [" + level + "] " + msg);
    }

    /**
     * Manual clear via /almin op clearlog. Truncates to zero bytes and writes
     * a marker line so a tail-following reader can see something happened.
     */
    public static synchronized boolean clear() {
        return clearInternal("--- log cleared ---");
    }

    /**
     * Shared truncate-and-resume logic. Returns true on success.
     */
    private static synchronized boolean clearInternal(String marker) {
        if (logPath == null) return false;
        try {
            if (writer != null) {
                writer.flush();
                writer.close();
                writer = null;
            }
            writer = new PrintWriter(
                Files.newBufferedWriter(
                    logPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
                ),
                true
            );
            writeLine("INFO", marker);
            return true;
        } catch (IOException e) {
            // Silent — same rationale as init() / writeLine().
            return false;
        }
    }

    private static synchronized void startAutoClear() {
        if (scheduler != null) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "almin-log-rotate");
            t.setDaemon(true);
            return t;
        });
        // Wrapped: a repeating task that throws is cancelled for good, so a
        // single bad rotation would stop the log rotating for the whole run.
        scheduler.scheduleAtFixedRate(
            () -> {
                try {
                    clearInternal("--- log auto-cleared (every " + AUTO_CLEAR_HOURS + "h) ---");
                } catch (Throwable ignored) {
                    // Nowhere to report it: this class is the reporting channel.
                }
            },
            AUTO_CLEAR_HOURS, AUTO_CLEAR_HOURS, TimeUnit.HOURS
        );
    }

    private static synchronized void stopAutoClear() {
        if (scheduler == null) return;
        scheduler.shutdownNow();
        scheduler = null;
    }
}
