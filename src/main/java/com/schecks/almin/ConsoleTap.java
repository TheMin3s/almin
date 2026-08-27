package com.schecks.almin;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Live console tap: tails {@code logs/latest.log} on a background thread,
 * keeps a small ring buffer of recent lines, and broadcasts new lines to
 * subscribed clients on each server tick.
 *
 * Tailing the file (rather than installing a Log4j2 appender) keeps this
 * simple and side-effect free — no log-framework integration, no
 * re-entrancy risk if the broadcast itself logs something.
 */
public final class ConsoleTap {
    private static final int RING_SIZE = 500;
    private static final long POLL_INTERVAL_MS = 250;
    private static final int READ_CHUNK = 64 * 1024;
    private static final int PARTIAL_CAP = 16 * 1024;

    private static volatile ConsoleTap instance;

    private final Path logFile;
    private final Deque<String> ring = new ArrayDeque<>(RING_SIZE);
    private final Set<UUID> subscribers = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<String> pending = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService executor;
    private final ByteArrayOutputStream partial = new ByteArrayOutputStream();
    private long position = 0;

    private ConsoleTap(MinecraftServer server) {
        this.logFile = server.getServerDirectory().resolve("logs").resolve("latest.log")
            .toAbsolutePath().normalize();
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Almin-console-tap");
            t.setDaemon(true);
            return t;
        });
    }

    /** Starts tailing. Call once at server start. */
    public static synchronized void start(MinecraftServer server) {
        if (instance != null) return;
        ConsoleTap tap = new ConsoleTap(server);
        instance = tap;
        tap.begin();
        ServerTickEvents.END_SERVER_TICK.register(tap::onTick);
    }

    /** Stops the tailer. Call at server stop. */
    public static synchronized void stop() {
        if (instance == null) return;
        instance.executor.shutdownNow();
        instance.subscribers.clear();
        instance = null;
    }

    public static ConsoleTap get() {
        return instance;
    }

    private void begin() {
        // Seek to current end of file so we only stream lines that arrive
        // from now on; the ring buffer fills up as the session progresses.
        try {
            if (Files.exists(logFile)) {
                position = Files.size(logFile);
            }
        } catch (IOException ignored) {
            // File may not exist yet; poll() will pick it up.
        }
        executor.scheduleWithFixedDelay(this::pollSafely, POLL_INTERVAL_MS,
            POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /** Adds a player to the subscriber set and sends them the current history. */
    public void subscribe(ServerPlayer player) {
        if (!subscribers.add(player.getUUID())) return;
        List<String> history;
        synchronized (ring) {
            history = new ArrayList<>(ring);
        }
        if (history.isEmpty()) return;
        for (int i = 0; i < history.size(); i += ConsoleLinesPayload.MAX_LINES_PER_BATCH) {
            int end = Math.min(i + ConsoleLinesPayload.MAX_LINES_PER_BATCH, history.size());
            ServerPlayNetworking.send(player,
                new ConsoleLinesPayload(new ArrayList<>(history.subList(i, end))));
        }
    }

    public void unsubscribe(UUID id) {
        subscribers.remove(id);
    }

    /**
     * The most recent {@code max} buffered lines, oldest first. Safe to call
     * from any thread — the web dashboard reads this from an HTTP worker.
     */
    public List<String> recentLines(int max) {
        List<String> all;
        synchronized (ring) {
            all = new ArrayList<>(ring);
        }
        int from = Math.max(0, all.size() - max);
        return new ArrayList<>(all.subList(from, all.size()));
    }

    private void pollSafely() {
        try {
            poll();
        } catch (Throwable t) {
            // scheduleWithFixedDelay stops scheduling on an unhandled throwable,
            // so any failure here must be swallowed for the tailer to survive.
        }
    }

    private void poll() throws IOException {
        if (!Files.exists(logFile)) return;
        long size = Files.size(logFile);
        if (size < position) {
            // Log rotated (file shrank) — start over from the new beginning.
            position = 0;
            partial.reset();
        }
        if (size <= position) return;

        try (SeekableByteChannel ch = Files.newByteChannel(logFile, StandardOpenOption.READ)) {
            ch.position(position);
            ByteBuffer buf = ByteBuffer.allocate(READ_CHUNK);
            int n;
            while ((n = ch.read(buf)) > 0) {
                buf.flip();
                while (buf.hasRemaining()) {
                    byte b = buf.get();
                    if (b == '\n') {
                        String line = partial.toString(StandardCharsets.UTF_8);
                        partial.reset();
                        if (!line.isEmpty() && line.charAt(line.length() - 1) == '\r') {
                            line = line.substring(0, line.length() - 1);
                        }
                        emit(line);
                    } else {
                        if (partial.size() < PARTIAL_CAP) {
                            partial.write(b);
                        }
                        // Past the cap: drop extra bytes until the next newline.
                        // Pathological case only (single >16 KB line).
                    }
                }
                buf.clear();
            }
            position = ch.position();
        }
    }

    private void emit(String line) {
        if (line.length() > ConsoleLinesPayload.MAX_LINE_CHARS) {
            line = line.substring(0, ConsoleLinesPayload.MAX_LINE_CHARS - 1) + "…";
        }
        synchronized (ring) {
            ring.addLast(line);
            while (ring.size() > RING_SIZE) ring.pollFirst();
        }
        if (!subscribers.isEmpty()) {
            pending.add(line);
        }
    }

    private void onTick(MinecraftServer server) {
        if (subscribers.isEmpty()) {
            // Drop anything queued — it's already in the ring buffer for the
            // next subscriber, no need to keep it in memory.
            pending.clear();
            return;
        }
        if (pending.isEmpty()) return;

        List<String> batch = new ArrayList<>();
        String s;
        while ((s = pending.poll()) != null) {
            batch.add(s);
            if (batch.size() >= ConsoleLinesPayload.MAX_LINES_PER_BATCH) break;
        }
        if (batch.isEmpty()) return;

        ConsoleLinesPayload payload = new ConsoleLinesPayload(batch);
        for (UUID id : subscribers) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) continue;
            if (ServerPlayNetworking.canSend(p, ConsoleLinesPayload.TYPE)) {
                ServerPlayNetworking.send(p, payload);
            }
        }
    }
}
