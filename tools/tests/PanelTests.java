import com.schecks.almin.AlminConfig;
import com.schecks.almin.PanelPayload;
import com.schecks.almin.client.PanelScreen;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;

import java.lang.reflect.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The panel that gave the chat-only commands a screen: the wire format, the
 * settings rows it builds, and the rule that every button is just a command.
 */
public class PanelTests {
    static int fail = 0;
    static void ck(String w, boolean ok, String d) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + w + (ok ? "" : "  -> " + d));
        if (!ok) fail++;
    }

    static AlminConfig cfg;

    public static void main(String[] a) throws Exception {
        Constructor<AlminConfig> cc = AlminConfig.class.getDeclaredConstructor();
        cc.setAccessible(true);
        cfg = cc.newInstance();
        Field inst = AlminConfig.class.getDeclaredField("instance");
        inst.setAccessible(true); inst.set(null, cfg);

        codec();
        configPanel();
        commandsOnly();
        filtering();
        tabs();

        System.out.println(fail == 0 ? "\nPANEL TESTS PASSED" : "\n" + fail + " FAILED");
        System.exit(fail == 0 ? 0 : 1);
    }

    /** Nine fields per row, hand-written codec — exactly where order goes wrong. */
    static void codec() throws Exception {
        List<PanelPayload.Row> rows = List.of(
            PanelPayload.Row.header("Settings"),
            new PanelPayload.Row(PanelPayload.ENTRY, "web-ui-port", "8246", 0xFFFFFFFF,
                "Edit", "almin config web-ui-port", "", "", "8246"),
            new PanelPayload.Row(PanelPayload.ENTRY, "auto-update", "on", 0xFF57C957,
                "Turn off", "almin config auto-update false", "Remove", "almin mods remove x", ""),
            PanelPayload.Row.note("a note"));
        PanelPayload sent = new PanelPayload("Title", "Note", "almin config", rows);

        Method w = PanelPayload.class.getDeclaredMethod("write",
            RegistryFriendlyByteBuf.class, PanelPayload.class);
        Method r = PanelPayload.class.getDeclaredMethod("read", RegistryFriendlyByteBuf.class);
        w.setAccessible(true); r.setAccessible(true);

        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), null);
        w.invoke(null, buf, sent);
        PanelPayload back = (PanelPayload) r.invoke(null, buf);
        ck("the panel round-trips every field", sent.equals(back), back.toString());
        ck("the buffer is fully consumed", buf.readableBytes() == 0,
            buf.readableBytes() + " left");

        // Over-long content must clip, not blow the declared cap.
        List<PanelPayload.Row> many = new ArrayList<>();
        for (int i = 0; i < PanelPayload.MAX_ROWS + 120; i++) {
            many.add(new PanelPayload.Row(PanelPayload.ENTRY, "x".repeat(500), "y".repeat(500),
                0, "b".repeat(60), "c".repeat(400), "", "", "z".repeat(500)));
        }
        RegistryFriendlyByteBuf b2 = new RegistryFriendlyByteBuf(Unpooled.buffer(), null);
        w.invoke(null, b2, new PanelPayload("t", "n", "almin config", many));
        int size = b2.readableBytes();
        PanelPayload trimmed = (PanelPayload) r.invoke(null, b2);
        ck("too many rows are trimmed", trimmed.rows().size() == PanelPayload.MAX_ROWS,
            String.valueOf(trimmed.rows().size()));
        ck("...and it stays inside the declared cap", size <= PanelPayload.MAX_BYTES,
            size + " > " + PanelPayload.MAX_BYTES);

        RegistryFriendlyByteBuf b3 = new RegistryFriendlyByteBuf(Unpooled.buffer(), null);
        w.invoke(null, b3, new PanelPayload(null, null, null,
            List.of(new PanelPayload.Row(1, null, null, 0, null, null, null, null, null))));
        PanelPayload nulls = (PanelPayload) r.invoke(null, b3);
        ck("nulls become empty, not a dropped packet",
            "".equals(nulls.title()) && "".equals(nulls.rows().get(0).label()), nulls.toString());
    }

    /** Every setting must be reachable, and typed the way the screen expects. */
    static void configPanel() throws Exception {
        Class<?> panels = Class.forName("com.schecks.almin.AdminPanels");
        Method m = panels.getDeclaredMethod("config");
        m.setAccessible(true);
        PanelPayload p = (PanelPayload) m.invoke(null);

        for (AlminConfig.Key k : AlminConfig.KEYS) {
            boolean present = p.rows().stream().anyMatch(r -> r.label().equals(k.name));
            ck("config panel lists " + k.name, present, "missing");
        }

        // A bool is one click; anything else opens a box prefilled with the value.
        PanelPayload.Row bool = find(p, "auto-update");
        ck("a bool toggles in one click",
            bool != null && bool.input().isEmpty() && bool.cmd1().endsWith(" false"),
            bool == null ? "missing" : bool.toString());
        PanelPayload.Row port = find(p, "web-ui-port");
        ck("an int opens a prefilled box",
            port != null && !port.input().isEmpty() && port.cmd1().equals("almin config web-ui-port"),
            port == null ? "missing" : port.toString());

        // The password hash must never be shipped to a screen.
        Field hf = AlminConfig.class.getDeclaredField("webAdminPasswordHash");
        hf.setAccessible(true);
        hf.set(cfg, "$pbkdf2$secret-hash-value");
        PanelPayload p2 = (PanelPayload) m.invoke(null);
        ck("the password hash value is never sent",
            p2.rows().stream().noneMatch(r -> r.value().contains("secret-hash-value")
                || r.input().contains("secret-hash-value")), "hash leaked to the panel");
        PanelPayload.Row hash = find(p2, "web-admin-password-hash");
        ck("...only whether one is set", hash != null && hash.value().equals("set"),
            hash == null ? "missing" : hash.value());
        ck("...and it has no edit button", hash != null && hash.cmd1().isEmpty(),
            hash == null ? "missing" : hash.cmd1());
    }

    static PanelPayload.Row find(PanelPayload p, String label) {
        return p.rows().stream().filter(r -> r.label().equals(label)).findFirst().orElse(null);
    }

    /**
     * The whole security argument for one generic screen: a button carries an
     * ordinary command, which the server re-checks. Nothing may carry anything
     * that is not one.
     */
    static void commandsOnly() throws Exception {
        Class<?> panels = Class.forName("com.schecks.almin.AdminPanels");
        List<PanelPayload> all = new ArrayList<>();
        Method cfgM = panels.getDeclaredMethod("config"); cfgM.setAccessible(true);
        Method modsM = panels.getDeclaredMethod("mods"); modsM.setAccessible(true);
        Method updM = panels.getDeclaredMethod("update"); updM.setAccessible(true);
        Method shM = panels.getDeclaredMethod("shared"); shM.setAccessible(true);
        all.add((PanelPayload) cfgM.invoke(null));
        all.add((PanelPayload) modsM.invoke(null));
        all.add((PanelPayload) updM.invoke(null));
        all.add((PanelPayload) shM.invoke(null));

        boolean allCommands = true;
        String bad = "";
        for (PanelPayload p : all) {
            if (!p.refresh().startsWith("almin ")) { allCommands = false; bad = p.refresh(); }
            for (PanelPayload.Row r : p.rows()) {
                for (String c : new String[]{r.cmd1(), r.cmd2()}) {
                    if (!c.isEmpty() && !c.startsWith("almin ")) { allCommands = false; bad = c; }
                }
            }
        }
        ck("every button is an /almin command", allCommands, bad);

        // A button with no command must have no label, or it would do nothing.
        boolean coherent = true;
        for (PanelPayload p : all) {
            for (PanelPayload.Row r : p.rows()) {
                if (!r.btn1().isEmpty() && r.cmd1().isEmpty()) coherent = false;
                if (!r.btn2().isEmpty() && r.cmd2().isEmpty()) coherent = false;
            }
        }
        ck("no button is drawn without something to run", coherent, "");

        // Nothing may smuggle a second command past the parser.
        boolean singleLine = all.stream().allMatch(p -> p.rows().stream()
            .noneMatch(r -> r.cmd1().contains("\n") || r.cmd2().contains("\n")));
        ck("no command contains a newline", singleLine, "");
    }

    /** Filtering keeps the list's shape; only entries are narrowed. */
    static void filtering() throws Exception {
        Method m = PanelScreen.class.getDeclaredMethod("matches", PanelPayload.Row.class, String.class);
        m.setAccessible(true);
        PanelPayload.Row header = PanelPayload.Row.header("Settings");
        PanelPayload.Row note = PanelPayload.Row.note("a note");
        PanelPayload.Row entry = PanelPayload.Row.of("auto-update", "on", 0);

        ck("an empty filter keeps everything", (Boolean) m.invoke(null, entry, ""), "");
        ck("headers survive a filter", (Boolean) m.invoke(null, header, "zzz"), "");
        ck("notes survive a filter", (Boolean) m.invoke(null, note, "zzz"), "");
        ck("a matching label is kept", (Boolean) m.invoke(null, entry, "auto"), "");
        ck("a matching value is kept", (Boolean) m.invoke(null, entry, "on"), "");
        ck("a non-match is dropped", !(Boolean) m.invoke(null, entry, "zzz"), "");
    }

    /** The strip must show the panel you are on as the current tab. */
    static void tabs() throws Exception {
        Method m = PanelScreen.class.getDeclaredMethod("tabFor", String.class);
        m.setAccessible(true);
        String[][] cases = {
            {"almin config", "Config"}, {"almin mods list", "Mods"},
            {"almin mask list", "Masks"}, {"almin files", "Shared"},
            {"almin update version", "Updates"}, {"almin something", ""}};
        for (String[] c : cases) {
            ck("'" + c[0] + "' is the " + (c[1].isEmpty() ? "(none)" : c[1]) + " tab",
                c[1].equals(m.invoke(null, c[0])), String.valueOf(m.invoke(null, c[0])));
        }

        // Every tab the strip offers must be one the panel can claim back,
        // or the current tab is never shown as current.
        String nav = Files.readString(Path.of("src/main/java/com/schecks/almin/client/AlminNav.java"));
        for (String label : new String[]{"Masks", "Config", "Updates", "Shared", "Mods"}) {
            ck(label + " is in the nav strip", nav.contains("\"" + label + "\""), "missing");
        }
    }
}
