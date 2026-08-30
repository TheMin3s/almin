import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.tree.CommandNode;
import com.schecks.almin.commands.AlminCommand;
import net.minecraft.commands.CommandSourceStack;

import java.lang.reflect.Field;
import java.util.function.Predicate;

/**
 * Every command a panel button can build must actually parse.
 *
 * <p>The client sends a string; the server parses it. When a value carries a
 * space and the grammar behind it is a single-word argument, the two disagree
 * and the button reports "Unknown or incomplete command" with nothing saying
 * which word was the problem. This registers the real command tree and parses
 * the real strings.
 */
public class CommandGrammarTests {
    static int fail = 0;
    static void ck(String w, boolean ok, String d) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + w + (ok ? "" : "  -> " + d));
        if (!ok) fail++;
    }

    static CommandDispatcher<CommandSourceStack> dispatcher;

    @SuppressWarnings("unchecked")
    public static void main(String[] a) throws Exception {
        // Commands touch the permission registry, which needs the game's
        // static registries to exist. This is the whole of what that costs.
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        dispatcher = new CommandDispatcher<>();
        AlminCommand.register(dispatcher);
        // Permission predicates want a real source; the grammar does not.
        strip(dispatcher.getRoot());

        System.out.println("plain commands the panels send");
        for (String c : new String[]{
                "almin", "almin op activity", "almin op web", "almin op dir",
                "almin op console", "almin mods", "almin mods list", "almin config",
                "almin mods required sodium true", "almin mods remove sodium",
                "almin mods reload", "almin config reload", "almin update version"}) {
            ck("'" + c + "' parses", parses(c), why(c));
        }

        System.out.println();
        System.out.println("values with spaces, which is where this went wrong");
        for (String c : new String[]{
                "almin config dir-writable-roots mods, config, shared",
                "almin config web-start-command java -Xmx4G -jar server.jar nogui",
                "almin config update-repo TheMin3s/almin",
                "almin mask set TheMines The Mines",
                "almin mask clear TheMines",
                "almin op delete resourcepacks/Faithful 32x.zip",
                "almin op get world/My Backup/level.dat",
                "almin op nano config/some folder/a.json",
                "almin op rename mods/Old Name.jar new.jar",
                "almin op cmd say hello there",
                "almin mods addfile sodium Sodium 0.6.13.jar"}) {
            ck("'" + c + "' parses", parses(c), why(c));
        }

        System.out.println();
        System.out.println("the fetch forms, whose arguments were quotable strings");
        for (String c : new String[]{
                "almin op fetch mod https://example.com/a.jar",
                "almin op fetch mod https://example.com/a.jar restart",
                "almin op fetch mods/ https://example.com/a.jar",
                "almin op fetch mods/ https://example.com/a.jar restart",
                "almin op fetch datapack https://example.com/p.zip",
                "almin op fetch config/sub https://example.com/c.json"}) {
            ck("'" + c + "' parses", parses(c), why(c));
        }

        panels();
        navBar();
        renaming();

        System.out.println();
        System.out.println(fail == 0 ? "COMMAND-GRAMMAR TESTS PASSED" : fail + " FAILED");
        System.exit(fail == 0 ? 0 : 1);
    }

    /**
     * Every command the in-game panels put on a button, taken from the panels
     * themselves rather than retyped here — so a new button is covered the day
     * it is added.
     */
    static void panels() throws Exception {
        System.out.println();
        System.out.println("every command the in-game panels build");
        java.lang.reflect.Constructor<com.schecks.almin.AlminConfig> cc =
            com.schecks.almin.AlminConfig.class.getDeclaredConstructor();
        cc.setAccessible(true);
        java.lang.reflect.Field inst =
            com.schecks.almin.AlminConfig.class.getDeclaredField("instance");
        inst.setAccessible(true);
        inst.set(null, cc.newInstance());

        int checked = 0;
        for (String which : new String[]{"config", "mods", "update"}) {
            java.lang.reflect.Method m =
                com.schecks.almin.AdminPanels.class.getMethod(which);
            com.schecks.almin.PanelPayload panel =
                (com.schecks.almin.PanelPayload) m.invoke(null);
            ck("the " + which + " panel's own refresh command parses",
                parses(panel.refresh()), why(panel.refresh()));
            checked++;
            for (com.schecks.almin.PanelPayload.Row r : panel.rows()) {
                for (String cmd : new String[]{r.cmd1(), r.cmd2()}) {
                    if (cmd.isEmpty()) continue;
                    // A row with a prefill is an edit box: the button's command
                    // is a prefix and the typed value is appended to it.
                    if (cmd.equals(r.cmd1()) && !r.input().isEmpty()) continue;
                    ck("  '" + cmd + "'", parses(cmd), why(cmd));
                    checked++;
                }
                if (r.input().isEmpty() || r.cmd1().isEmpty()) continue;
                // The value that is actually in the box, and a value with
                // spaces in it, which is what broke.
                for (String value : new String[]{r.input(), "a b c", "mods, config, shared"}) {
                    if (value.isEmpty()) continue;
                    String full = r.cmd1() + " " + value;
                    ck("  '" + full + "'", parses(full), why(full));
                    checked++;
                }
            }
        }
        ck("that was a real sweep, not an empty one", checked > 40, checked + " commands");
    }

    /** The tab strip and the dashboard, which every screen carries. */
    static void navBar() throws Exception {
        System.out.println();
        System.out.println("the tab strip");
        java.lang.reflect.Field tabs =
            com.schecks.almin.client.AlminNav.class.getDeclaredField("TABS");
        tabs.setAccessible(true);
        for (String[] tab : (String[][]) tabs.get(null)) {
            ck("  '" + tab[1] + "'", parses(tab[1]), why(tab[1]));
        }
    }

    /**
     * Renaming has two halves that may both contain spaces, so one separator
     * cannot express it. The file browser quotes the new name; typed use falls
     * back to the last space.
     */
    static void renaming() throws Exception {
        System.out.println();
        System.out.println("renaming, where both halves can hold a space");
        java.lang.reflect.Method split =
            com.schecks.almin.commands.AlminCommand.class.getDeclaredMethod(
                "splitRename", String.class);
        split.setAccessible(true);

        java.util.LinkedHashMap<String, String[]> cases = new java.util.LinkedHashMap<>();
        cases.put("mods/a.jar b.jar", new String[]{"mods/a.jar", "b.jar"});
        cases.put("mods/Old Name.jar b.jar", new String[]{"mods/Old Name.jar", "b.jar"});
        // What the file browser sends: the new name quoted.
        cases.put("mods/a.jar \"My New Pack.zip\"",
            new String[]{"mods/a.jar", "My New Pack.zip"});
        cases.put("mods/Old Name.jar \"New Name.jar\"",
            new String[]{"mods/Old Name.jar", "New Name.jar"});
        cases.put("config/a.json \"b.json\"", new String[]{"config/a.json", "b.json"});
        for (var e : cases.entrySet()) {
            String[] got = (String[]) split.invoke(null, e.getKey());
            boolean ok = got != null && got[0].equals(e.getValue()[0])
                && got[1].equals(e.getValue()[1]);
            ck("'" + e.getKey() + "' splits right", ok,
                got == null ? "refused" : "[" + got[0] + "] [" + got[1] + "]");
        }
        for (String bad : new String[]{"", "   ", "onlyonepart", "a ", " b"}) {
            ck("'" + bad + "' is refused rather than guessed",
                split.invoke(null, bad) == null, "it guessed");
        }
        // A quote inside a filename must not be read as the separator.
        String[] odd = (String[]) split.invoke(null, "mods/say\"hi.txt out.txt");
        ck("a quote in the middle of a path is just a character",
            odd != null && odd[0].equals("mods/say\"hi.txt") && odd[1].equals("out.txt"),
            odd == null ? "refused" : "[" + odd[0] + "] [" + odd[1] + "]");

        // And the command itself still parses in both forms.
        for (String c : new String[]{
                "almin op rename mods/a.jar b.jar",
                "almin op rename mods/Old Name.jar \"New Name.jar\""}) {
            ck("'" + c + "' parses", parses(c), why(c));
        }

        // The file browser must actually be sending the quoted form.
        String screen = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/java/com/schecks/almin/client/RenameFileScreen.java"));
        ck("the rename screen quotes the new name",
            screen.contains("almin op rename \" + oldPath + \" \\\"\""),
            "it sends the name bare");
    }

    static boolean parses(String command) {
        ParseResults<CommandSourceStack> r = dispatcher.parse(command, null);
        return r.getExceptions().isEmpty()
            && !r.getReader().canRead()
            && r.getContext().getCommand() != null;
    }

    static String why(String command) {
        ParseResults<CommandSourceStack> r = dispatcher.parse(command, null);
        if (!r.getExceptions().isEmpty()) {
            return r.getExceptions().values().iterator().next().getMessage();
        }
        if (r.getReader().canRead()) {
            return "stopped at: ..." + r.getReader().getRemaining();
        }
        return "parsed but runs nothing";
    }

    /** Replaces every permission predicate with "yes", so this tests grammar. */
    static void strip(CommandNode<CommandSourceStack> node) throws Exception {
        Field f = CommandNode.class.getDeclaredField("requirement");
        f.setAccessible(true);
        f.set(node, (Predicate<CommandSourceStack>) s -> true);
        for (CommandNode<CommandSourceStack> child : node.getChildren()) strip(child);
    }
}
