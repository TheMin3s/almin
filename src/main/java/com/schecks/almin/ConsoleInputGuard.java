package com.schecks.almin;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Turns a console pipe that its host has torn down into an ordinary EOF.
 *
 * <p>NAS panels, SSH sessions and process wrappers sometimes leave Java with
 * an open stdin descriptor whose next read fails with {@code EIO}. Minecraft
 * catches that exception, prints a frightening stack trace from its console
 * handler, and loses console input. There is nothing useful to recover from a
 * dead pipe, so returning EOF is the honest representation of its state and
 * lets the rest of the dedicated server continue quietly.
 */
public final class ConsoleInputGuard extends FilterInputStream {
    private static final org.slf4j.Logger LOGGER =
        org.slf4j.LoggerFactory.getLogger("almin");

    private volatile boolean disconnected;

    private ConsoleInputGuard(InputStream input) {
        super(input);
    }

    /** Installs the guard before Minecraft creates its console-reader thread. */
    public static synchronized void install() {
        if (System.in instanceof ConsoleInputGuard) return;
        System.setIn(wrap(System.in));
    }

    /** Exposed so the failure behavior can be tested without touching stdin. */
    public static InputStream wrap(InputStream input) {
        if (input instanceof ConsoleInputGuard) return input;
        return new ConsoleInputGuard(input);
    }

    @Override
    public int read() throws IOException {
        if (disconnected) return -1;
        try {
            return super.read();
        } catch (IOException e) {
            disconnect(e);
            return -1;
        }
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (disconnected) return -1;
        try {
            return super.read(bytes, offset, length);
        } catch (IOException e) {
            disconnect(e);
            return -1;
        }
    }

    @Override
    public int available() throws IOException {
        if (disconnected) return 0;
        try {
            return super.available();
        } catch (IOException e) {
            disconnect(e);
            return 0;
        }
    }

    private synchronized void disconnect(IOException e) {
        if (disconnected) return;
        disconnected = true;
        LOGGER.warn("[almin] Console input disconnected ({}); continuing without terminal input.",
            e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }
}
