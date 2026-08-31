import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Static contract for the two client entrypoints and their independent packet
 * registries. Fabric's live registry needs a game context, but all of the
 * startup-crash conditions can be proven directly from the sources.
 */
public class PayloadTypes {
    static int fail = 0;
    static void ck(String what, boolean ok, String detail) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what
            + (ok ? "" : "  -> " + detail));
        if (!ok) fail++;
    }

    static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    public static void main(String[] args) throws Exception {
        String baseRegistry = read("src/main/java/com/schecks/almin/AlminPayloads.java");
        String adminRegistry = read("src/main/java/com/schecks/almin/AdminPayloads.java");
        String baseClient = read("src/main/java/com/schecks/almin/client/AlminClient.java");
        String adminClient = read("src/main/java/com/schecks/almin/client/AlminAdminClient.java");
        String installer = read("src/main/java/com/schecks/almin/client/ClientModInstaller.java");
        String profile = read("src/main/java/com/schecks/almin/client/ClientProfileReport.java");

        String[] baseReceived = {
            "FileTransferPayload", "DashboardPayload", "ModOfferPayload",
            "ModFilePayload", "ServerVersionPayload", "AdminInstallPayload"
        };
        String[] adminReceived = {
            "NanoOpenPayload", "DirListingPayload", "ConsoleOpenPayload",
            "ConsoleLinesPayload", "WebAdminPayload", "ActivityPayload",
            "PanelPayload", "AdminVersionPayload"
        };
        for (String type : baseReceived) {
            ck(type + " is declared by the base registry",
                baseClient.contains("registerGlobalReceiver(" + type + ".TYPE")
                    && baseRegistry.contains(type + ".TYPE"),
                "receiver and registry disagree");
            ck(type + " is absent from the admin entrypoint",
                !adminClient.contains("registerGlobalReceiver(" + type + ".TYPE"), type);
        }
        for (String type : adminReceived) {
            ck(type + " is declared by the admin registry",
                adminClient.contains("registerGlobalReceiver(" + type + ".TYPE")
                    && adminRegistry.contains(type + ".TYPE"),
                "receiver and registry disagree");
            ck(type + " is absent from the base entrypoint",
                !baseClient.contains("registerGlobalReceiver(" + type + ".TYPE"), type);
        }

        for (String type : new String[]{
                "ModResponsePayload", "ModFileRequestPayload", "ClientProfilePayload"}) {
            ck(type + " sent by the base client is declared",
                baseRegistry.contains(type + ".TYPE"), "missing from AlminPayloads");
        }
        for (String type : new String[]{
                "DirRequestPayload", "FileUploadPayload", "NanoSavePayload",
                "ConsoleSubscribePayload", "WebAdminRequestPayload",
                "WebPasswordPayload", "WebControlPayload", "ActivityRequestPayload"}) {
            ck(type + " sent by the admin extension is declared",
                adminRegistry.contains(type + ".TYPE"), "missing from AdminPayloads");
        }
        ck("the mod installer sends its declared request",
            installer.contains("new ModFileRequestPayload"), "");
        ck("profile reporting sends its declared payload",
            profile.contains("new ClientProfilePayload"), "");

        int baseTypes = baseClient.indexOf("AlminPayloads.registerTypes()");
        int baseReceiver = baseClient.indexOf("registerGlobalReceiver");
        ck("base types are declared before base receivers",
            baseTypes >= 0 && baseTypes < baseReceiver,
            "types=" + baseTypes + " receiver=" + baseReceiver);
        int adminTypes = adminClient.indexOf("AdminPayloads.registerTypes()");
        int adminReceiver = adminClient.indexOf("registerGlobalReceiver");
        ck("admin types are declared before admin receivers",
            adminTypes >= 0 && adminTypes < adminReceiver,
            "types=" + adminTypes + " receiver=" + adminReceiver);

        for (String registry : new String[]{baseRegistry, adminRegistry}) {
            ck("registry is idempotent", registry.contains("if (registered) return;"), "");
            ck("registry registration is synchronized",
                registry.contains("synchronized void registerTypes"), "");
        }

        // No subsystem should hide a packet registration behind a server-only
        // initializer again.
        for (String file : new String[]{"ConsoleNet", "DirNet", "NanoNet", "UploadNet",
                "ModNet", "FileShare", "Dashboard", "UpdateChecker"}) {
            String body = read("src/main/java/com/schecks/almin/" + file + ".java");
            ck(file + " does not register payload types itself",
                !body.contains("PayloadTypeRegistry"), "stray registration");
        }

        System.out.println(fail == 0 ? "\nPAYLOAD-TYPE TESTS PASSED"
            : "\n" + fail + " FAILED");
        System.exit(fail == 0 ? 0 : 1);
    }
}
