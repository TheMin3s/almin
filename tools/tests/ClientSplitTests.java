import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Proves the release build is three loadable jars, not one jar copied three times. */
public class ClientSplitTests {
    static int fail = 0;
    static void ck(String what, boolean ok, String detail) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what
            + (ok ? "" : "  -> " + detail));
        if (!ok) fail++;
    }

    public static void main(String[] args) throws Exception {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(Path.of("gradle.properties"))) {
            props.load(in);
        }
        Path server = Path.of("build/libs/almin-" + props.getProperty("mod_version") + "-server.jar");
        Path base = Path.of("build/libs/almin-" + props.getProperty("client_version") + "-client.jar");
        Path admin = Path.of("build/libs/almin-" + props.getProperty("admin_version") + "-admin.jar");

        ck("server jar exists", Files.isRegularFile(server), server.toString());
        ck("base client jar exists", Files.isRegularFile(base), base.toString());
        ck("admin extension jar exists", Files.isRegularFile(admin), admin.toString());

        Set<String> serverEntries = entries(server);
        Set<String> baseEntries = entries(base);
        Set<String> adminEntries = entries(admin);
        String serverJson = entry(server, "fabric.mod.json");
        String baseJson = entry(base, "fabric.mod.json");
        String adminJson = entry(admin, "fabric.mod.json");

        ck("base jar has the player entrypoint",
            baseEntries.contains("com/schecks/almin/client/AlminClient.class"), "");
        ck("base jar retains mod download/reporting UI",
            baseEntries.contains("com/schecks/almin/client/ModOfferScreen.class")
                && baseEntries.contains("com/schecks/almin/client/ClientProfileReport.class")
                && baseEntries.contains("com/schecks/almin/AdminInstallPayload.class"), "");
        ck("base jar has no admin entrypoint or screens",
            !baseEntries.contains("com/schecks/almin/client/AlminAdminClient.class")
                && !baseEntries.contains("com/schecks/almin/client/ConsoleScreen.class")
                && !baseEntries.contains("com/schecks/almin/client/ActivityScreen.class"), "");

        ck("admin jar has the extension entrypoint and tools",
            adminEntries.contains("com/schecks/almin/client/AlminAdminClient.class")
                && adminEntries.contains("com/schecks/almin/client/ConsoleScreen.class")
                && adminEntries.contains("com/schecks/almin/client/ActivityScreen.class"), "");
        ck("admin jar does not duplicate base implementation",
            !adminEntries.contains("com/schecks/almin/client/AlminClient.class")
                && !adminEntries.contains("com/schecks/almin/client/ModOfferScreen.class")
                && !adminEntries.contains("com/schecks/almin/client/ClientUpdater.class"), "");

        Set<String> duplicateClasses = new HashSet<>(baseEntries);
        duplicateClasses.retainAll(adminEntries);
        duplicateClasses.removeIf(name -> !name.endsWith(".class"));
        ck("base and admin jars contain no duplicate classes",
            duplicateClasses.isEmpty(), duplicateClasses.toString());

        ck("base manifest keeps the almin mod id",
            baseJson.contains("\"id\": \"almin\"")
                && baseJson.contains("com.schecks.almin.client.AlminClient"), baseJson);
        ck("admin manifest uses its own mod id",
            adminJson.contains("\"id\": \"almin_admin\"")
                && adminJson.contains("com.schecks.almin.client.AlminAdminClient"), adminJson);
        ck("admin manifest requires the base client",
            adminJson.contains("\"almin\": \">="
                + props.getProperty("admin_base_version") + "\""), adminJson);
        ck("server contains both packet registries",
            serverEntries.contains("com/schecks/almin/AlminPayloads.class")
                && serverEntries.contains("com/schecks/almin/AdminPayloads.class"), "");
        ck("server jar excludes every client entrypoint",
            !serverEntries.contains("com/schecks/almin/client/AlminClient.class")
                && !serverEntries.contains("com/schecks/almin/client/AlminAdminClient.class"),
            serverJson);

        String checker = Files.readString(
            Path.of("src/main/java/com/schecks/almin/UpdateChecker.java"));
        ck("base update utilities do not load the server entrypoint",
            !checker.contains("Almin.MOD_ID"), "hidden server-class dependency");

        System.out.println(fail == 0 ? "\nCLIENT-SPLIT TESTS PASSED"
            : "\n" + fail + " FAILED");
        System.exit(fail == 0 ? 0 : 1);
    }

    static Set<String> entries(Path jar) throws Exception {
        Set<String> names = new HashSet<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            zip.stream().map(ZipEntry::getName).forEach(names::add);
        }
        return names;
    }

    static String entry(Path jar, String name) throws Exception {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(name);
            if (entry == null) return "";
            try (InputStream in = zip.getInputStream(entry)) {
                return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
    }
}
