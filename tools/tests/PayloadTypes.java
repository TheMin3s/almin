import com.schecks.almin.*;
import java.lang.reflect.*;
import java.util.*;

/**
 * Regression for the client-jar startup crash: the client entrypoint registered
 * receivers for payload types that only the main entrypoint declared, so a
 * client-only jar threw "no payload type has been registered".
 *
 * Fabric's registry needs a game context, so instead of calling it we assert the
 * contract statically: every payload type the client touches must be declared in
 * AlminPayloads, and registerTypes() must be safe to call twice.
 */
public class PayloadTypes {
    static int fail = 0;
    static void ck(String w, boolean ok, String d) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + w + (ok ? "" : "  -> " + d));
        if (!ok) fail++;
    }

    public static void main(String[] a) throws Exception {
        String src = new String(java.nio.file.Files.readAllBytes(
            java.nio.file.Path.of("src/main/java/com/schecks/almin/AlminPayloads.java")));
        String client = new String(java.nio.file.Files.readAllBytes(
            java.nio.file.Path.of("src/main/java/com/schecks/almin/client/AlminClient.java")));
        String installer = new String(java.nio.file.Files.readAllBytes(
            java.nio.file.Path.of("src/main/java/com/schecks/almin/client/ClientModInstaller.java")));

        // Everything the client receives must be declared.
        for (String t : new String[]{"FileTransferPayload","DashboardPayload","NanoOpenPayload",
                "DirListingPayload","ModOfferPayload","ModFilePayload","ServerVersionPayload",
                "ConsoleOpenPayload","ConsoleLinesPayload","WebAdminPayload","ActivityPayload","PanelPayload"}) {
            boolean received = client.contains("registerGlobalReceiver(" + t + ".TYPE");
            ck(t + " received by client is declared", !received || src.contains(t + ".TYPE"),
                "client receives it but AlminPayloads does not declare it");
        }
        // Everything the client sends must be declared too.
        for (String t : new String[]{"DirRequestPayload","FileUploadPayload","NanoSavePayload",
                "ConsoleSubscribePayload","ModResponsePayload","ModFileRequestPayload",
                "WebAdminRequestPayload","WebPasswordPayload","WebControlPayload","ActivityRequestPayload"}) {
            ck(t + " sent by client is declared", src.contains(t + ".TYPE"), "missing from AlminPayloads");
        }
        ck("ModFileRequestPayload actually sent by installer",
            installer.contains("new ModFileRequestPayload"), "");

        // The client entrypoint must declare types before attaching receivers.
        int decl = client.indexOf("AlminPayloads.registerTypes()");
        int first = client.indexOf("registerGlobalReceiver");
        ck("client declares types before registering receivers", decl >= 0 && decl < first,
            "declare=" + decl + " firstReceiver=" + first);

        // Idempotency: a universal jar runs both entrypoints.
        ck("registerTypes is guarded against double registration",
            src.contains("if (registered) return;"), "no guard — universal jar would throw");
        ck("registerTypes is synchronized", src.contains("synchronized void registerTypes"), "");

        // No stragglers left behind the main entrypoint.
        for (String f : new String[]{"ConsoleNet","DirNet","NanoNet","UploadNet","ModNet",
                                     "FileShare","Dashboard","UpdateChecker"}) {
            String body = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Path.of("src/main/java/com/schecks/almin/" + f + ".java")));
            ck(f + " no longer registers types itself", !body.contains("PayloadTypeRegistry"),
                "still registers a type behind the main entrypoint");
        }

        System.out.println(fail == 0 ? "\nPAYLOAD-TYPE TESTS PASSED" : "\n" + fail + " FAILED");
        System.exit(fail == 0 ? 0 : 1);
    }
}
