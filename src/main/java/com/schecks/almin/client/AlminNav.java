package com.schecks.almin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Shared navigation for the admin screens: a strip of tabs along the bottom so
 * moving between the console, the file browser and the rest doesn't mean typing
 * {@code /almin} again, plus a Back entry when the screen was opened from the
 * dashboard.
 *
 * <p>Every destination is reached by re-issuing the ordinary command, exactly
 * as the dashboard's own buttons do. Nothing here grants access: the server
 * re-checks permission on each command, and {@link #trusted()} only decides
 * which tabs are worth drawing — it is a copy of the flag the server already
 * sent with the dashboard.
 */
public final class AlminNav {
    /** Tab labels paired with the command that opens them. */
    private static final String[][] TABS = {
        {"Console", "almin op console", "trusted"},
        {"Files",   "almin op dir",     "trusted"},
        {"Web",     "almin op web",     "trusted"},
        {"Activity","almin op activity", "trusted"},
        {"Shared",  "almin files",      ""},
        {"Mods",    "almin mods list",  ""},
        {"Config",  "almin config",     ""},
    };

    /**
     * Whether the player got here from the dashboard, so a Back entry makes
     * sense. Set when the dashboard launches something; cleared when a screen
     * is closed back to the game.
     */
    private static volatile boolean fromDashboard = false;

    /** Mirror of the dashboard payload's trusted flag, for which tabs to draw. */
    private static volatile boolean trusted = false;

    /** Narrower than this and the label is unreadable, so drop a tab instead. */
    private static final int MIN_TAB_WIDTH = 44;
    private static final int MAX_TAB_WIDTH = 84;

    private AlminNav() {}

    /** Button width if {@code count} tabs share the available run. */
    private static int fit(int available, int gap, int count) {
        return (available - gap * (count - 1)) / count;
    }

    public static void launchedFromDashboard() { fromDashboard = true; }
    public static void leftAdminUi()           { fromDashboard = false; }
    public static boolean cameFromDashboard()  { return fromDashboard; }

    public static void setTrusted(boolean value) { trusted = value; }
    public static boolean trusted()              { return trusted; }

    /** Re-issues a command as if typed. */
    public static void send(String command) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return;
        mc.getConnection().sendCommand(command);
    }

    /**
     * Builds the bottom strip. The caller adds the returned buttons as widgets
     * and supplies {@code navigate}, which should do any screen-specific
     * cleanup (unsubscribing, say) before the command goes out.
     *
     * @param current label of the tab representing this screen, so it can be
     *                shown as the active one; "" if none matches
     */
    public static List<Button> bar(int screenWidth, int y, String current, Consumer<String> navigate) {
        List<String[]> entries = new ArrayList<>();
        if (fromDashboard) entries.add(new String[]{"< Dashboard", "almin", ""});
        for (String[] tab : TABS) {
            if ("trusted".equals(tab[2]) && !trusted) continue;
            entries.add(tab);
        }
        if (entries.isEmpty()) return List.of();

        int gap = 3;
        int available = screenWidth - 8;
        // Shed the lowest-priority tabs rather than shrink everything into
        // illegibility — or, worse, run the strip off the edge of the screen.
        while (entries.size() > 1 && fit(available, gap, entries.size()) < MIN_TAB_WIDTH) {
            entries.remove(entries.size() - 1);
        }
        int widest = Math.min(MAX_TAB_WIDTH, fit(available, gap, entries.size()));
        int total = widest * entries.size() + gap * (entries.size() - 1);
        int x = Math.max(4, (screenWidth - total) / 2);

        List<Button> out = new ArrayList<>(entries.size());
        for (String[] e : entries) {
            String label = e[0];
            String command = e[1];
            boolean active = !label.equals(current);
            Button b = Button.builder(Component.literal(label),
                    btn -> { if (active) navigate.accept(command); })
                .bounds(x, y, widest, 20)
                .build();
            // The tab you're already on stays visible but unclickable, so the
            // strip reads the same on every screen.
            b.active = active;
            out.add(b);
            x += widest + gap;
        }
        return out;
    }
}
