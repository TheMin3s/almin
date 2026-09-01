package com.schecks.almin;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the list-shaped admin screens the client shows for commands that used
 * to answer only in chat: masks, config, advertised mods, shared files and
 * updates.
 *
 * <p>Every button carries the ordinary command that performs it. Nothing here
 * grants anything — the client re-issues the command and the server re-checks
 * permission exactly as if it had been typed, so a crafted click is worth no
 * more than typing the same thing.
 *
 * <p>Values that come from players or the filesystem are truncated on the way
 * out ({@link PanelPayload} clips again), because a long world name or mask
 * should make a row look untidy, not push the buttons off the screen.
 */
public final class AdminPanels {
    private static final int GOLD  = 0xFFFFAA00;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GREEN = 0xFF57C957;
    private static final int WARN  = 0xFFFFCC55;
    private static final int DIM   = 0xFF9AA3AE;

    private AdminPanels() {}

    /** True when this player has a client that can show a panel. */
    public static boolean canShow(ServerPlayer player) {
        return player != null && ServerPlayNetworking.canSend(player, PanelPayload.TYPE);
    }

    public static void send(ServerPlayer player, PanelPayload panel) {
        if (!canShow(player)) return;
        ServerPlayNetworking.send(player, panel);
    }

    // ---------- masks ----------

    /**
     * Display-name masks, with everyone currently online listed so a mask can
     * be set without typing a name.
     */
    public static PanelPayload masks(MinecraftServer server, boolean trusted) {
        List<PanelPayload.Row> rows = new ArrayList<>();
        Map<UUID, String> masks = MaskConfig.snapshot();
        PlayerHistory history = PlayerHistory.get(server);

        rows.add(PanelPayload.Row.header("Online"));
        List<ServerPlayer> online = server.getPlayerList().getPlayers();
        if (online.isEmpty()) {
            rows.add(PanelPayload.Row.note("Nobody is connected."));
        }
        for (ServerPlayer p : online) {
            String name = p.getGameProfile().name();
            String mask = MaskConfig.maskFor(p.getUUID());
            boolean unmasked = mask == null || mask.isEmpty();
            rows.add(row(name, unmasked ? "no mask" : mask, unmasked ? DIM : GOLD,
                unmasked ? "Set mask" : "Change", "almin mask set " + name,
                unmasked ? "" : "Clear", unmasked ? "" : "almin mask clear " + name,
                mask == null ? "" : mask));
            // Op and de-op are trusted-only, so the buttons only exist for a
            // trusted viewer. The command re-checks regardless; this just
            // avoids offering something that would be refused.
            if (trusted) {
                boolean op = server.getPlayerList().isOp(p.nameAndId());
                rows.add(new PanelPayload.Row(PanelPayload.NOTE,
                    "  " + (op ? "server operator" : "not an operator"), "", 0,
                    op ? "De-op" : "Make op",
                    (op ? "almin op remove " : "almin op add ") + name,
                    "", "", ""));
            }
        }

        rows.add(PanelPayload.Row.header("Masks set (" + masks.size() + ")"));
        if (masks.isEmpty()) {
            rows.add(PanelPayload.Row.note("None. Pick a player above to give them one."));
        }
        for (Map.Entry<UUID, String> e : masks.entrySet()) {
            String real = history == null ? "" : history.nameOf(e.getKey());
            if (real == null || real.isEmpty()) real = e.getKey().toString();
            rows.add(row(real, e.getValue(), GOLD,
                "Change", "almin mask set " + real,
                "Clear", "almin mask clear " + real,
                e.getValue()));
        }
        return new PanelPayload("Almin — Display-name masks",
            "A mask is cosmetic. Commands and permissions still use the real name.",
            "almin mask list", rows);
    }

    // ---------- config ----------

    /** Every setting, typed: booleans toggle in one click, the rest open a box. */
    public static PanelPayload config() {
        AlminConfig cfg = AlminConfig.get();
        List<PanelPayload.Row> rows = new ArrayList<>();
        rows.add(PanelPayload.Row.header("Settings (" + AlminConfig.KEYS.size() + ")"));
        for (AlminConfig.Key k : AlminConfig.KEYS) {
            String shown = k.display(cfg);
            if (k.name.equals("web-admin-password-hash")) {
                // Never shown: it is a password equivalent offline, and the
                // screen has no reason to carry one.
                rows.add(row(k.name, shown.isBlank() ? "not set" : "set", DIM,
                    "", "", "", "", ""));
                rows.add(PanelPayload.Row.note("  " + k.description));
                continue;
            }
            if (k.type == AlminConfig.Type.BOOL) {
                boolean on = Boolean.parseBoolean(shown);
                rows.add(row(k.name, on ? "on" : "off", on ? GREEN : DIM,
                    on ? "Turn off" : "Turn on",
                    "almin config " + k.name + " " + (on ? "false" : "true"),
                    "", "", ""));
            } else {
                String range = k.type == AlminConfig.Type.INT ? "  (" + k.min + "–" + k.max + ")" : "";
                rows.add(row(k.name, shown.isEmpty() ? "—" : shown, WHITE,
                    "Edit", "almin config " + k.name, "", "", shown));
                if (!range.isEmpty()) rows.add(PanelPayload.Row.note("  " + k.description + range));
                else rows.add(PanelPayload.Row.note("  " + k.description));
                continue;
            }
            rows.add(PanelPayload.Row.note("  " + k.description));
        }
        rows.add(PanelPayload.Row.header("File"));
        rows.add(row("config/almin/config.json", "on disk", DIM,
            "Reload", "almin config reload", "", "", ""));
        return new PanelPayload("Almin — Settings",
            "Saved as you change them.", "almin config", rows);
    }

    // ---------- advertised mods ----------

    /** The mods offered to joining players, plus the three settings behind them. */
    public static PanelPayload mods() {
        AlminConfig cfg = AlminConfig.get();
        List<ModOffers.AdvertisedMod> mods = ModOffers.list();
        List<PanelPayload.Row> rows = new ArrayList<>();

        rows.add(PanelPayload.Row.header("Behaviour"));
        rows.add(toggle("Advertise on join", cfg.modsAdvertise, "mods-advertise"));
        rows.add(toggle("Declining disconnects", cfg.modsDenyKicks, "mods-deny-kicks"));
        rows.add(toggle("Almin required to play", cfg.requireClientMod, "require-client-mod"));

        rows.add(PanelPayload.Row.header("Advertised (" + mods.size() + ")"));
        if (mods.isEmpty()) {
            rows.add(PanelPayload.Row.note("Nothing advertised. Add mods from the web panel."));
        }
        for (ModOffers.AdvertisedMod m : mods) {
            String name = m.name().isBlank() ? m.modId() : m.name();
            String version = m.version().isBlank() ? "" : " " + m.version();
            rows.add(row(name + version, m.required() ? "required" : "optional",
                m.required() ? WARN : DIM,
                m.required() ? "Make optional" : "Make required",
                "almin mods required " + m.modId() + " " + (!m.required()),
                "Remove", "almin mods remove " + m.modId(), ""));
            rows.add(PanelPayload.Row.note("  "
                + (m.serverHosted() ? "served by this server · modfiles/" + m.file() : m.url())
                + (m.sha256().isBlank() ? "" : "  · pinned")));
        }
        rows.add(PanelPayload.Row.header("File"));
        rows.add(row("config/almin/mods.json", "on disk", DIM,
            "Reload", "almin mods reload", "", "", ""));
        return new PanelPayload("Almin — Advertised mods",
            "Nothing is pushed. Each player sees the list and chooses.",
            "almin mods list", rows);
    }

    // ---------- shared files ----------

    /** The server's shared/ folder, one download button per entry. */
    public static PanelPayload shared() {
        List<PanelPayload.Row> rows = new ArrayList<>();
        List<Path> files = FileShare.listShared();
        rows.add(PanelPayload.Row.header("Shared files (" + files.size() + ")"));
        if (files.isEmpty()) {
            rows.add(PanelPayload.Row.note("Nothing shared. Drop files in the server's shared/ folder."));
        }
        for (Path p : files) {
            String name = p.getFileName().toString();
            boolean dir = java.nio.file.Files.isDirectory(p);
            String size = dir ? "folder (sent zipped)" : Dashboard.bytes(sizeOf(p));
            rows.add(row(name, size, dir ? GOLD : WHITE,
                "Download", "almin get " + name, "", "", ""));
        }
        return new PanelPayload("Almin — Shared files",
            "Anyone on the server can list and download these.",
            "almin files", rows);
    }

    private static long sizeOf(Path p) {
        try {
            return java.nio.file.Files.size(p);
        } catch (Exception e) {
            return 0L;
        }
    }

    // ---------- updates ----------

    /**
     * What is running and what the update settings are.
     *
     * <p>No check is made here: reaching GitHub takes seconds and this is built
     * on the server thread. The Check button issues {@code /almin update
     * version}, which does it off-thread and answers in chat.
     */
    public static PanelPayload update() {
        AlminConfig cfg = AlminConfig.get();
        List<PanelPayload.Row> rows = new ArrayList<>();
        rows.add(PanelPayload.Row.header("Version"));
        rows.add(PanelPayload.Row.of("Running", "v" + UpdateChecker.currentVersion(), WHITE));
        rows.add(row("Repository", cfg.updateRepo, DIM,
            "Edit", "almin config update-repo", "", "", cfg.updateRepo));
        rows.add(PanelPayload.Row.header("Actions"));
        rows.add(row("Check GitHub", "answers in chat", DIM,
            "Check", "almin update version", "", "", ""));
        rows.add(row("Download and install", "restart needed after", DIM,
            "Update now", "almin update", "", "", ""));
        rows.add(PanelPayload.Row.header("Settings"));
        rows.add(toggle("Check on boot", cfg.updateCheckOnBoot, "update-check-on-boot"));
        rows.add(toggle("Install automatically", cfg.autoUpdate, "auto-update"));
        rows.add(toggle("Wait until empty", cfg.autoUpdateWhenEmpty, "auto-update-when-empty"));
        String queued = ServerAutoUpdater.pendingVersion();
        rows.add(PanelPayload.Row.note(queued.isEmpty()
            ? "Auto-update waits for the last player to leave before restarting."
            : "v" + queued + " is queued and will install after the last player leaves."));
        return new PanelPayload("Almin — Updates", "", "almin update version", rows);
    }

    // ---------- helpers ----------

    private static PanelPayload.Row toggle(String label, boolean on, String key) {
        return row(label, on ? "on" : "off", on ? GREEN : DIM,
            on ? "Turn off" : "Turn on",
            "almin config " + key + " " + (on ? "false" : "true"),
            "", "", "");
    }

    private static PanelPayload.Row row(String label, String value, int accent,
                                        String btn1, String cmd1, String btn2, String cmd2,
                                        String input) {
        return new PanelPayload.Row(PanelPayload.ENTRY, label, value, accent,
            btn1, cmd1, btn2, cmd2, input);
    }
}
