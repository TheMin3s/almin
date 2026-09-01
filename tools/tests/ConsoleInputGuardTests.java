import com.schecks.almin.ConsoleInputGuard;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class ConsoleInputGuardTests {
    static int fail;

    public static void main(String[] args) throws Exception {
        byte[] text = "say hello\n".getBytes(StandardCharsets.UTF_8);
        InputStream healthy = ConsoleInputGuard.wrap(new ByteArrayInputStream(text));
        ck("healthy console input is unchanged",
            Arrays.equals(text, healthy.readAllBytes()), "bytes changed");

        Broken broken = new Broken();
        InputStream guarded = ConsoleInputGuard.wrap(broken);
        ck("a broken console pipe becomes EOF", guarded.read() == -1, "not EOF");
        ck("later reads remain EOF without touching the dead pipe",
            guarded.read(new byte[8]) == -1 && broken.reads == 1,
            "reads=" + broken.reads);
        ck("a zero-length read still follows InputStream's contract",
            guarded.read(new byte[0]) == 0, "not zero");
        ck("availability is empty after disconnection", guarded.available() == 0,
            "available was not zero");
        ck("wrapping twice does not stack guards",
            ConsoleInputGuard.wrap(guarded) == guarded, "wrapped twice");

        System.out.println(fail == 0 ? "\nCONSOLE INPUT GUARD TESTS PASSED"
            : "\n" + fail + " FAILED");
        System.exit(fail == 0 ? 0 : 1);
    }

    static final class Broken extends InputStream {
        int reads;

        @Override
        public int read() throws IOException {
            reads++;
            throw new IOException("Input/output error");
        }
    }

    static void ck(String name, boolean ok, String why) {
        System.out.println("  " + (ok ? "PASS  " : "FAIL  ") + name
            + (ok || why.isEmpty() ? "" : "  -> " + why));
        if (!ok) fail++;
    }
}
