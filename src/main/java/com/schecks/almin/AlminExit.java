package com.schecks.almin;

/**
 * Makes sure an Almin-initiated stop actually ends the process.
 *
 * <h3>Why this exists</h3>
 * Three Almin features work by stopping the Minecraft server and letting
 * something else start it again: auto-update, {@code /almin op restart}, and
 * the web panel's Stop and Restart. Every one of them depends on the JVM
 * exiting afterwards — that exit is the signal a wrapper script, a systemd
 * unit or a host panel watches for.
 *
 * <p>The JVM only exits once no non-daemon thread is left. Almin's own web
 * panel used to leave one (the JDK's HTTP-Dispatcher, which inherits its daemon
 * flag from whoever calls {@code HttpServer.start()}), so the game stopped, the
 * process lived on holding its port, and nothing restarted it. That particular
 * cause is fixed at the source; this is the guarantee that the feature works
 * anyway, whatever else is holding the door.
 *
 * <p>It arms only on a stop Almin asked for. An ordinary shutdown is not its
 * business, and neither is supervisor mode, where outliving the server is the
 * entire point.
 */
public final class AlminExit {
    /** Long enough for any honest shutdown, short enough to still be a restart. */
    private static final long GRACE_MS = 60_000;

    private static final org.slf4j.Logger CONSOLE = org.slf4j.LoggerFactory.getLogger("almin");

    private static volatile boolean armed = false;

    private AlminExit() {}

    /**
     * Starts the watchdog, unless supervisor mode means the JVM is meant to
     * stay. The thread is a daemon, so it cannot itself be the reason the
     * process lingers — if the JVM exits on its own, this never runs.
     */
    public static synchronized void arm(String why) {
        if (armed) return;
        if (AlminConfig.get().webSupervisor) return;
        armed = true;
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(GRACE_MS);
            } catch (InterruptedException e) {
                return;
            }
            AlminLog.warn("[almin] JVM still alive {}s after {} — forcing exit", GRACE_MS / 1000, why);
            CONSOLE.warn("[almin] The server stopped ({}) but this process is still running "
                + "{}s later — something is holding it open. Exiting so it can be restarted.",
                why, GRACE_MS / 1000);
            Runtime.getRuntime().halt(0);
        }, "Almin-exit-watchdog");
        t.setDaemon(true);
        t.start();
    }

    /** For tests: whether the watchdog has been started this run. */
    public static boolean armed() {
        return armed;
    }
}
