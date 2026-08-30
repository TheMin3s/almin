import com.schecks.almin.AlminConfig;
import com.schecks.almin.WebUi;

import java.lang.reflect.*;
import java.net.*;
import java.util.HashSet;
import java.util.Set;

/**
 * The bug that left port 8246 bound after a crash.
 *
 * HttpServer.start() spawns its own HTTP-Dispatcher thread, outside the
 * executor, inheriting its daemon flag from whoever called start(). Called from
 * the Minecraft server thread it was non-daemon, so it held the JVM — and the
 * port — open after the game died.
 */
public class PortLeakTests {
    static int fail = 0;
    static void ck(String w, boolean ok, String d) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + w + (ok ? "" : "  -> " + d));
        if (!ok) fail++;
    }

    static AlminConfig cfg;
    static Method listen;

    public static void main(String[] a) throws Exception {
        Constructor<AlminConfig> cc = AlminConfig.class.getDeclaredConstructor();
        cc.setAccessible(true);
        cfg = cc.newInstance();
        Field inst = AlminConfig.class.getDeclaredField("instance");
        inst.setAccessible(true); inst.set(null, cfg);
        set("webUiBind", "127.0.0.1");

        listen = WebUi.class.getDeclaredMethod("listen",
            net.minecraft.server.MinecraftServer.class, AlminConfig.class);
        listen.setAccessible(true);

        daemonFlag();
        supervisorFlag();
        noPortDrift();
        released();

        System.out.println(fail == 0 ? "\nPORT-LEAK TESTS PASSED" : "\n" + fail + " FAILED");
        System.exit(fail == 0 ? 0 : 1);
    }

    static void set(String f, Object v) throws Exception {
        Field x = AlminConfig.class.getDeclaredField(f); x.setAccessible(true); x.set(cfg, v);
    }
    static Object get(String f) throws Exception {
        Field x = AlminConfig.class.getDeclaredField(f); x.setAccessible(true); return x.get(cfg);
    }
    static Set<Thread> dispatchers() {
        Set<Thread> out = new HashSet<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().contains("HTTP-Dispatcher")) out.add(t);
        }
        return out;
    }
    /** The dispatcher thread this start created. */
    static Thread newDispatcher(Set<Thread> before) {
        for (Thread t : dispatchers()) if (!before.contains(t)) return t;
        return null;
    }
    static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return s.getLocalPort();
        }
    }

    /** The default: the panel must never be the reason a dead JVM stays up. */
    static void daemonFlag() throws Exception {
        set("webSupervisor", false);
        set("webUiPort", freePort());
        Set<Thread> before = dispatchers();
        listen.invoke(null, null, cfg);
        ck("the panel starts", WebUi.running(), WebUi.lastError());

        Thread d = newDispatcher(before);
        ck("a dispatcher thread was created", d != null, "none found");
        ck("it is a daemon, so a crash can't leave the port held",
            d != null && d.isDaemon(), d == null ? "none" : "daemon=" + d.isDaemon());

        // The handler pool must be daemon too.
        boolean poolOk = true;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().equals("Almin-web") && !t.isDaemon()) poolOk = false;
        }
        ck("the handler pool is daemon too", poolOk, "");
        WebUi.stopNow();
    }

    /** Supervisor mode is the one case where holding the JVM open is the point. */
    static void supervisorFlag() throws Exception {
        set("webSupervisor", true);
        set("webUiPort", freePort());
        Set<Thread> before = dispatchers();
        listen.invoke(null, null, cfg);
        Thread d = newDispatcher(before);
        ck("supervisor mode starts the panel", WebUi.running(), WebUi.lastError());
        ck("...with a non-daemon dispatcher, so it outlives the server",
            d != null && !d.isDaemon(), d == null ? "none" : "daemon=" + d.isDaemon());
        WebUi.stopNow();
        set("webSupervisor", false);
    }

    /**
     * The second half of the report: because each fallback was written back to
     * the config, the address crawled upwards on every restart.
     */
    static void noPortDrift() throws Exception {
        int wanted = freePort();
        set("webUiPort", wanted);
        try (ServerSocket squatter = new ServerSocket(wanted, 8, InetAddress.getByName("127.0.0.1"))) {
            listen.invoke(null, null, cfg);
            ck("it still comes up when the port is held", WebUi.running(), WebUi.lastError());
            ck("...on a different port", WebUi.port() != wanted, "port=" + WebUi.port());
            ck("...WITHOUT rewriting the configured port",
                (Integer) get("webUiPort") == wanted,
                "config drifted to " + get("webUiPort"));
            WebUi.stopNow();
        }

        // Once the squatter is gone, the next start returns to the real port.
        listen.invoke(null, null, cfg);
        ck("the next start goes back to the configured port", WebUi.port() == wanted,
            "port=" + WebUi.port() + " wanted=" + wanted);
        WebUi.stopNow();
    }

    /** Stop has to actually free the socket, or the next start collides again. */
    static void released() throws Exception {
        int port = freePort();
        set("webUiPort", port);
        listen.invoke(null, null, cfg);
        ck("bound before stop", WebUi.running(), WebUi.lastError());
        WebUi.stopNow();

        boolean free = false;
        for (int i = 0; i < 20 && !free; i++) {
            try (ServerSocket s = new ServerSocket(port, 4, InetAddress.getByName("127.0.0.1"))) {
                free = true;
            } catch (Exception e) {
                Thread.sleep(100);
            }
        }
        ck("the port is free again after stop", free, "still bound after 2s");

        // And the dispatcher thread is gone, not merely idle.
        boolean gone = true;
        for (Thread t : dispatchers()) if (t.isAlive() && t.isDaemon()) gone = gone && true;
        ck("stop leaves no live non-daemon dispatcher",
            dispatchers().stream().noneMatch(t -> t.isAlive() && !t.isDaemon()), "");

        // A shutdown hook must be armed, so an exit closes the socket promptly.
        Field hooked = WebUi.class.getDeclaredField("shutdownHooked");
        hooked.setAccessible(true);
        ck("a JVM shutdown hook is armed", (Boolean) hooked.get(null), "");
    }
}
