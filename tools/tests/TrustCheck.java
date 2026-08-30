import com.schecks.almin.TrustedOps;
import java.util.UUID;
public class TrustCheck {
    public static void main(String[] a) {
        UUID mines  = UUID.fromString("516e51d9-4e6b-4a2f-a282-e0f51f5a20e7");
        UUID golani = UUID.fromString("cccda823-cfc9-4b9a-b7e9-633e02d0b3ba");
        int fail = 0;
        fail += chk("TheMines is trusted", TrustedOps.isTrusted(mines));
        fail += chk("removed account is NOT trusted", !TrustedOps.isTrusted(golani));
        fail += chk("random uuid is NOT trusted", !TrustedOps.isTrusted(UUID.randomUUID()));
        fail += chk("null is NOT trusted", !TrustedOps.isTrusted(null));
        fail += chk("count is exactly 1", TrustedOps.count() == 1);
        System.out.println(fail == 0 ? "\nTRUST LIST OK" : "\n" + fail + " FAILED");
        System.exit(fail == 0 ? 0 : 1);
    }
    static int chk(String w, boolean ok) { System.out.println((ok?"  PASS  ":"  FAIL  ")+w); return ok?0:1; }
}
