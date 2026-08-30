import com.schecks.almin.AlminConfig;
import com.schecks.almin.WebAdminNet;
import com.schecks.almin.WebAdminPayload;
import com.schecks.almin.WebUi;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;

import java.lang.reflect.*;
import java.net.*;

/**
 * The web panel's on/off controls, the port fallback that stops a taken port
 * from silently killing the panel, and the allowlist behind the in-game tab.
 */
public class WebControlTests {
    static int fail = 0;
    static void ck(String w, boolean ok, String d) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + w + (ok ? "" : "  -> " + d));
        if (!ok) fail++;
    }

    static AlminConfig cfg;
    static Field fEnabled, fPort, fBind, fMetrics, fStartCmd, fSession;
    static Method mListen, mApply;

    public static void main(String[] a) throws Exception {
        Constructor<AlminConfig> cc = AlminConfig.class.getDeclaredConstructor();
        cc.setAccessible(true);
        cfg = cc.newInstance();
        fEnabled = f("webUiEnabled"); fPort = f("webUiPort"); fBind = f("webUiBind");
        fMetrics = f("webPublicMetrics"); fStartCmd = f("webStartCommand"); fSession = f("webSessionMinutes");
        Field inst = AlminConfig.class.getDeclaredField("instance");
        inst.setAccessible(true); inst.set(null, cfg);

        ck("web-ui-enabled defaults ON", (boolean) fEnabled.get(cc.newInstance()), "default is off");

        mListen = WebUi.class.getDeclaredMethod("listen", net.minecraft.server.MinecraftServer.class, AlminConfig.class);
        mListen.setAccessible(true);
        mApply = WebAdminNet.class.getDeclaredMethod("apply", String.class, String.class, String.class);
        mApply.setAccessible(true);

        portFallback();
        startStop();
        allowlist();
        codec();

        System.out.println(fail == 0 ? "\nWEB-CONTROL TESTS PASSED" : "\n" + fail + " FAILED");
        System.exit(fail == 0 ? 0 : 1);
    }

    /**
     * A MinecraftServer reference that exists without a game behind it —
     * allocated, never constructed. Enough to satisfy the "is the server up
     * yet" guard so the real start path can be exercised offline.
     */
    static net.minecraft.server.MinecraftServer stubServer() throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field f = unsafe.getDeclaredField("theUnsafe"); f.setAccessible(true);
        Object u = f.get(null);
        Method alloc = unsafe.getMethod("allocateInstance", Class.class);
        return (net.minecraft.server.MinecraftServer)
            alloc.invoke(u, Class.forName("net.minecraft.server.dedicated.DedicatedServer"));
    }

    static Field f(String n) throws Exception {
        Field x = AlminConfig.class.getDeclaredField(n); x.setAccessible(true); return x;
    }

    /** A port already in use must not take the panel down with it. */
    static void portFallback() throws Exception {
        try (ServerSocket squatter = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))) {
            int taken = squatter.getLocalPort();
            fBind.set(cfg, "127.0.0.1");
            fPort.setInt(cfg, taken);
            mListen.invoke(null, null, cfg);

            ck("panel comes up even though its port was taken", WebUi.running(),
                "not running: " + WebUi.lastError());
            ck("it moved to a different port", WebUi.port() != taken && WebUi.port() > 0,
                "port=" + WebUi.port());
            // Deliberately NOT written back: persisting each fallback is what
            // made the address crawl upwards on every restart. The configured
            // port is the operator's intent; a fallback lasts one run.
            ck("the configured port is left alone", fPort.getInt(cfg) == taken,
                "config drifted to " + fPort.getInt(cfg));
            ck("no error is recorded on success", WebUi.lastError().isEmpty(), WebUi.lastError());

            // ...and it is really listening there.
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", WebUi.port()), 2000);
                ck("the new port actually accepts connections", true, "");
            } catch (Exception e) {
                ck("the new port actually accepts connections", false, e.toString());
            }
            WebUi.stopNow();
        }
    }

    static void startStop() throws Exception {
        fPort.setInt(cfg, 0);              // "pick one", exercised through startNow
        fEnabled.setBoolean(cfg, true);

        WebUi.Control r;

        // Give startNow a server to hand on. An uninitialised instance is
        // enough: the panel only reaches into it for snapshots, which already
        // fail closed, and nothing here exercises a route.
        Field bound = WebUi.class.getDeclaredField("boundServer");
        bound.setAccessible(true);
        bound.set(null, stubServer());

        fBind.set(cfg, "127.0.0.1");
        fPort.setInt(cfg, 0);
        r = WebUi.startNow();
        ck("start picks a port when none is configured", r.ok() && WebUi.running(), r.message());
        int chosen = WebUi.port();

        r = WebUi.startNow();
        ck("start while running is refused", !r.ok(), r.message());

        r = WebUi.restartNow();
        ck("restart brings it back up", r.ok() && WebUi.running(), r.message());
        chosen = WebUi.port();

        r = WebUi.stopNow();
        ck("stop reports success", r.ok(), r.message());
        ck("stop really stops it", !WebUi.running(), "still running");

        r = WebUi.stopNow();
        ck("stopping twice is refused, not an error", !r.ok(), r.message());

        // The port it settled on is still free again afterwards.
        try (ServerSocket s = new ServerSocket(chosen, 4, InetAddress.getByName("127.0.0.1"))) {
            ck("the port is released on stop", true, "");
        } catch (Exception e) {
            ck("the port is released on stop", false, e.toString());
        }

        fEnabled.setBoolean(cfg, false);
        r = WebUi.startNow();
        ck("start is refused while disabled", !r.ok(), r.message());
        fEnabled.setBoolean(cfg, true);

        bound.set(null, null);
        r = WebUi.startNow();
        ck("start before the server exists refuses cleanly", !r.ok(), r.message());
        bound.set(null, stubServer());
    }

    /** The in-game tab must not be a back door into arbitrary config. */
    static void allowlist() throws Exception {
        WebUi.Control r = (WebUi.Control) mApply.invoke(null, "tester", "web-start-command", "rm -rf /");
        ck("web-start-command is refused from the Web tab", !r.ok(), r.message());
        ck("...and is left untouched", "".equals(fStartCmd.get(cfg)), String.valueOf(fStartCmd.get(cfg)));

        r = (WebUi.Control) mApply.invoke(null, "tester", "web-admin-password-hash", "$pbkdf2$forged");
        ck("the password hash is refused from the Web tab", !r.ok(), r.message());

        r = (WebUi.Control) mApply.invoke(null, "tester", "auto-update", "false");
        ck("an unrelated setting is refused", !r.ok(), r.message());

        r = (WebUi.Control) mApply.invoke(null, "tester", "not-a-setting", "x");
        ck("an unknown setting is refused", !r.ok(), r.message());

        int before = fSession.getInt(cfg);
        r = (WebUi.Control) mApply.invoke(null, "tester", "web-session-minutes", "1");
        ck("an out-of-range value is refused", !r.ok(), r.message());
        ck("...and the old value survives", fSession.getInt(cfg) == before, "changed to " + fSession.getInt(cfg));

        r = (WebUi.Control) mApply.invoke(null, "tester", "web-session-minutes", "45");
        ck("a valid value is accepted", r.ok(), r.message());
        ck("...and is applied", fSession.getInt(cfg) == 45, String.valueOf(fSession.getInt(cfg)));

        fMetrics.setBoolean(cfg, true);
        r = (WebUi.Control) mApply.invoke(null, "tester", "web-public-metrics", "false");
        ck("a toggle is accepted", r.ok() && !fMetrics.getBoolean(cfg), r.message());

        // Turning the panel off through the tab must actually stop it.
        fBind.set(cfg, "127.0.0.1");
        WebUi.startNow();
        ck("panel is up before the off switch", WebUi.running(), WebUi.lastError());
        r = (WebUi.Control) mApply.invoke(null, "tester", "web-ui-enabled", "false");
        ck("switching Enabled off stops the panel now", r.ok() && !WebUi.running(), r.message());
        r = (WebUi.Control) mApply.invoke(null, "tester", "web-ui-enabled", "true");
        ck("switching Enabled on starts it again", r.ok() && WebUi.running(), r.message());
        WebUi.stopNow();
    }

    /**
     * Hand-written codecs get their field order wrong. Thirteen fields, all
     * distinct, round-tripped through a real buffer.
     */
    static void codec() throws Exception {
        WebAdminPayload sent = new WebAdminPayload(
            true, false, "10.0.0.7", 8123, 8100, true, false, true, false, true, 240,
            "http://10.0.0.7:8123/", "could not bind 0.0.0.0:8100 — Address already in use");

        Method w = WebAdminPayload.class.getDeclaredMethod("write", RegistryFriendlyByteBuf.class, WebAdminPayload.class);
        Method rd = WebAdminPayload.class.getDeclaredMethod("read", RegistryFriendlyByteBuf.class);
        w.setAccessible(true); rd.setAccessible(true);

        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), null);
        w.invoke(null, buf, sent);
        WebAdminPayload back = (WebAdminPayload) rd.invoke(null, buf);
        ck("the status packet round-trips every field", sent.equals(back), back.toString());
        ck("the buffer is fully consumed", buf.readableBytes() == 0, buf.readableBytes() + " bytes left");

        // A too-long error must truncate, not blow up the packet.
        String huge = "x".repeat(4000);
        RegistryFriendlyByteBuf b2 = new RegistryFriendlyByteBuf(Unpooled.buffer(), null);
        WebAdminPayload big = new WebAdminPayload(false, true, "0.0.0.0", 0, 0,
            false, false, false, false, false, 120, "", huge);
        try {
            w.invoke(null, b2, big);
            WebAdminPayload got = (WebAdminPayload) rd.invoke(null, b2);
            ck("an over-long error is clipped, not fatal", got.lastError().length() == 512,
                String.valueOf(got.lastError().length()));
        } catch (Exception e) {
            ck("an over-long error is clipped, not fatal", false, e.toString());
        }

        // Null strings must not reach the buffer as nulls.
        RegistryFriendlyByteBuf b3 = new RegistryFriendlyByteBuf(Unpooled.buffer(), null);
        WebAdminPayload nulls = new WebAdminPayload(false, true, null, 0, 0,
            false, false, false, false, false, 120, null, null);
        try {
            w.invoke(null, b3, nulls);
            WebAdminPayload got = (WebAdminPayload) rd.invoke(null, b3);
            ck("null strings become empty, not a dropped packet",
                "".equals(got.bind()) && "".equals(got.url()) && "".equals(got.lastError()), got.toString());
        } catch (Exception e) {
            ck("null strings become empty, not a dropped packet", false, e.toString());
        }
    }
}
