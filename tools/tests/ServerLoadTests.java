import com.schecks.almin.ServerLoad;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The parts of the load sampler that do not need a world.
 *
 * <p>Most of {@link ServerLoad} is a walk over entities and chunks, which
 * needs a running server and is therefore checked by looking at it rather than
 * by running it. Two things are not: the ring buffer of tick times, where
 * getting the order wrong draws a spike that never happened, and the promise
 * that a server nobody is watching is not measured at all.
 */
public class ServerLoadTests {
    static int fail = 0;

    static void ck(String what, boolean ok, String saw) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what + (ok ? "" : "  -> " + saw));
        if (!ok) fail++;
    }

    static String show(double[] d) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < d.length; i++) {
            if (i > 0) b.append(' ');
            b.append(Math.round(d[i]));
        }
        return b.append(']').toString();
    }

    /** Nanoseconds, so the answers come out as whole milliseconds. */
    static long ms(long millis) { return millis * 1_000_000L; }

    public static void main(String[] args) throws Exception {
        // ---- the ring ----
        {
            // The server increments its counter and then writes that slot, so
            // after tick 4 the newest sample is at index 4 and the oldest is
            // at 5. Put in array order this reads 30 40 10 20 — a chart with a
            // cliff in the middle of it that nothing caused.
            long[] ring = { ms(10), ms(20), ms(30), ms(40), ms(50) };
            double[] out = ServerLoad.unroll(ring, 4);
            ck("the tick history comes out oldest first, not in array order",
                show(out).equals("[10 20 30 40 50]"), show(out));
        }
        {
            // Wrapped: after tick 7 of a five-slot ring the newest is index 2.
            long[] ring = { ms(60), ms(70), ms(80), ms(40), ms(50) };
            double[] out = ServerLoad.unroll(ring, 7);
            ck("...however many times the ring has wrapped",
                show(out).equals("[40 50 60 70 80]"), show(out));
        }
        {
            // A server five seconds old has not filled the ring. Those slots
            // hold nothing, and a tick that took no time at all would be a
            // lie about the one thing this menu exists to report.
            long[] ring = { ms(10), ms(20), ms(30), 0, 0 };
            double[] out = ServerLoad.unroll(ring, 2);
            ck("a ring nothing has been written to yet reports only what has",
                show(out).equals("[10 20 30]"), show(out));
        }
        {
            ck("no history at all is no history, not a crash",
                ServerLoad.unroll(new long[0], 12).length == 0
                    && ServerLoad.unroll(null, 0).length == 0, "");
        }
        {
            // A tick counter is an int that has been adding up for as long as
            // the server has been on; a long-lived one can go negative.
            long[] ring = { ms(10), ms(20), ms(30), ms(40), ms(50) };
            double[] out = ServerLoad.unroll(ring, Integer.MIN_VALUE);
            ck("a tick counter that has gone round the int does not throw",
                out.length == 5, show(out));
        }

        // ---- the promise about doing nothing ----
        {
            ServerLoad.reset();
            ck("nothing has been measured before anybody asks",
                ServerLoad.latest() == null, "there was a sample");

            // tick() must return before it touches the server when nobody is
            // looking. Passing null proves it: a version that sampled anyway
            // would dereference it.
            ServerLoad.tick(null);
            ck("...and a tick on a server nobody is watching does not measure one",
                ServerLoad.latest() == null, "it sampled unasked");
        }

        // ---- what cannot be run here, read instead ----
        String src = Files.readString(
            Path.of("src/main/java/com/schecks/almin/ServerLoad.java"));
        ck("the pass runs on the server thread, through the tick",
            src.contains("public static void tick(MinecraftServer server)")
                && src.contains("Server thread only"),
            "the sampler no longer says where it runs");
        ck("interest expires, so the sampler stops when the menu is closed",
            src.contains("now - wantedAt > INTEREST_MILLIS") && src.contains("return;"),
            "nothing switches the sampler back off");
        ck("a sample that fails does not take the tick down with it",
            src.contains("catch (Throwable t)") && src.contains("load sample failed"),
            "a throwing sample would escape into the tick loop");
        ck("only ticking chunks are counted as ticking blocks",
            src.contains("forEachBlockTickingChunk"),
            "a loaded-but-idle chunk would be reported as load");

        String almin = Files.readString(Path.of("src/main/java/com/schecks/almin/Almin.java"));
        ck("the sampler is offered the tick, and dropped on a stop",
            almin.contains("register(ServerLoad::tick)") && almin.contains("ServerLoad.reset()"),
            "the sampler is never given a tick, or outlives the server");

        String web = Files.readString(Path.of("src/main/java/com/schecks/almin/WebUi.java"));
        ck("the route is behind the Load menu",
            web.contains("java.util.Map.entry(\"/api/load\", \"load\")"),
            "the load route is not menu-guarded");
        ck("asking is what keeps the sampler awake",
            web.contains("ServerLoad.want()"), "nothing tells the sampler anybody is looking");
        // Positions are the only part of a load sample that is about somebody
        // rather than about the server, so they are the only part that goes.
        ck("an account not shown coordinates still gets the counts",
            web.contains("loadJson(s, hidden(ex))") && web.contains("if (noCoords) continue;"),
            "coordinates are either not withheld or the whole answer is");

        String tools = Files.readString(Path.of("src/main/java/com/schecks/almin/AiTools.java"));
        ck("the model's version is behind the same menu",
            tools.contains("case \"server_load\" -> \"load\"")
                && tools.contains("may(me, \"load\")"),
            "server_load is reachable from a menu it does not belong to");

        // The reflection is only here to keep the check honest: the record is
        // public, and a field quietly renamed would slip past a source grep.
        Method latest = ServerLoad.class.getDeclaredMethod("latest");
        ck("a sample is readable without the server thread",
            latest.getReturnType().equals(ServerLoad.Sample.class), "");

        System.out.println(fail == 0 ? "\nSERVER LOAD TESTS PASSED" : "\n" + fail + " FAILED");
        System.exit(fail == 0 ? 0 : 1);
    }
}
