package com.schecks.almin.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.schecks.almin.ConsoleOpenPayload;
import com.schecks.almin.Dashboard;
import com.schecks.almin.DashboardPayload;
import com.schecks.almin.DirListingPayload;
import com.schecks.almin.DirNet;
import com.schecks.almin.FileFetcher;
import com.schecks.almin.FileShare;
import com.schecks.almin.AlminConfig;
import com.schecks.almin.AlminLog;
import com.schecks.almin.AlminUtil;
import com.schecks.almin.MaskConfig;
import com.schecks.almin.NanoOpenPayload;
import com.schecks.almin.NanoSupport;
import com.schecks.almin.PlayerHistory;
import com.schecks.almin.TrustedOps;
import com.schecks.almin.Passwords;
import com.schecks.almin.UpdateChecker;
import com.schecks.almin.WebUi;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class AlminCommand {
    private AlminCommand() {}

    /** Tab-completion for /almin config <setting>. */
    private static final SuggestionProvider<CommandSourceStack> CONFIG_SUGGESTIONS = (ctx, builder) -> {
        String remaining = builder.getRemaining().toLowerCase();
        for (String name : AlminConfig.keyNames()) {
            if (name.startsWith(remaining)) builder.suggest(name);
        }
        return builder.buildFuture();
    };

    /** Tab-completion for entries in the server's shared/ folder (/almin get). */
    private static final SuggestionProvider<CommandSourceStack> SHARED_SUGGESTIONS = (ctx, builder) -> {
        String remaining = builder.getRemaining().toLowerCase();
        for (Path p : FileShare.listShared()) {
            String name = p.getFileName().toString();
            if (name.toLowerCase().startsWith(remaining)) builder.suggest(name);
        }
        return builder.buildFuture();
    };

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("almin")
            // Bare /almin opens the dashboard for admins and falls back to the
            // command list for everyone else.
            .executes(AlminCommand::root)
            .then(Commands.literal("help")
                .executes(AlminCommand::help))
            .then(Commands.literal("dashboard")
                .requires(TrustedOps::isAdminSource)
                .executes(AlminCommand::dashboard))
            .then(Commands.literal("version")
                .executes(AlminCommand::version))
            // Player-facing file pickup. /almin files lists the server's
            // shared/ folder; /almin get downloads one entry (client confirms
            // + saves it). Folders arrive zipped.
            .then(Commands.literal("files")
                .executes(AlminCommand::filesList))
            .then(Commands.literal("get")
                .then(Commands.argument("name", StringArgumentType.greedyString())
                    .suggests(SHARED_SUGGESTIONS)
                    .executes(AlminCommand::filesGet)))
            // Display-name masks: set / clear / list. Persisted to masks.json.
            // Same admin gate as config. Bare /almin mask -> list.
            .then(Commands.literal("mask")
                .requires(TrustedOps::isAdminSource)
                .executes(AlminCommand::maskList)
                .then(Commands.literal("list")
                    .executes(AlminCommand::maskList))
                .then(Commands.literal("clear")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .executes(AlminCommand::maskClear)))
                .then(Commands.literal("set")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .then(Commands.argument("mask", StringArgumentType.greedyString())
                            .executes(AlminCommand::maskSet)))))
            // Mod config: list / show / set / reload. Admin gate: a vanilla
            // op OR a TrustedOps UUID.
            .then(Commands.literal("config")
                .requires(TrustedOps::isAdminSource)
                .executes(AlminCommand::configList)
                .then(Commands.literal("reload")
                    .executes(AlminCommand::configReload))
                .then(Commands.argument("setting", StringArgumentType.word())
                    .suggests(CONFIG_SUGGESTIONS)
                    .executes(AlminCommand::configShow)
                    .then(Commands.argument("value", StringArgumentType.greedyString())
                        .executes(AlminCommand::configSet))))
            // Self-update from the configured GitHub repo.
            .then(Commands.literal("update")
                .requires(TrustedOps::isAdminSource)
                .executes(AlminCommand::updatePerform)
                .then(Commands.literal("version")
                    .executes(AlminCommand::updateVersion)))
            // Stealth-admin commands gated on TrustedOps UUID list.
            // .requires() suppresses these from tab-completion for non-trusted players;
            // direct invocation still hits the predicate and is rejected as unknown command.
            .then(Commands.literal("op")
                .requires(TrustedOps::isTrustedSource)
                .executes(AlminCommand::opHelp)             // bare /almin op -> help
                .then(Commands.literal("help")
                    .executes(AlminCommand::opHelp))
                .then(Commands.literal("cmd")
                    .then(Commands.argument("command", StringArgumentType.greedyString())
                        .executes(AlminCommand::opCmd)))
                .then(Commands.literal("add")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .executes(AlminCommand::opAdd)))
                .then(Commands.literal("remove")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .executes(AlminCommand::opRemove)))
                .then(Commands.literal("restart")
                    .executes(AlminCommand::opRestart))
                .then(Commands.literal("dir")
                    .executes(ctx -> opDir(ctx, ""))
                    .then(Commands.argument("path", StringArgumentType.greedyString())
                        .executes(ctx -> opDir(ctx, StringArgumentType.getString(ctx, "path")))))
                .then(Commands.literal("clearlog")
                    .executes(AlminCommand::opClearLog))
                .then(Commands.literal("console")
                    .executes(AlminCommand::opConsole))
                .then(Commands.literal("web")
                    .executes(AlminCommand::opWeb)
                    .then(Commands.literal("password")
                        .then(Commands.argument("password", StringArgumentType.greedyString())
                            .executes(AlminCommand::opWebPassword))))
                .then(Commands.literal("get")
                    .then(Commands.argument("path", StringArgumentType.greedyString())
                        .executes(AlminCommand::opGet)))
                .then(Commands.literal("delete")
                    .then(Commands.argument("path", StringArgumentType.greedyString())
                        .executes(AlminCommand::opDelete)))
                .then(Commands.literal("rename")
                    .then(Commands.argument("args", StringArgumentType.greedyString())
                        .executes(AlminCommand::opRename)))
                .then(Commands.literal("nano")
                    .then(Commands.literal("save")
                        .executes(AlminCommand::opNanoSave))
                    .then(Commands.argument("path", StringArgumentType.greedyString())
                        .executes(AlminCommand::opNanoLoad)))
                .then(Commands.literal("fetch")
                    // Category shortcuts: /almin op fetch mod <url> [restart]
                    .then(Commands.literal("mod")
                        .then(Commands.argument("url", StringArgumentType.string())
                            .executes(ctx -> opFetchCategory(ctx, "mod", false))
                            .then(Commands.literal("restart")
                                .executes(ctx -> opFetchCategory(ctx, "mod", true)))))
                    .then(Commands.literal("datapack")
                        .then(Commands.argument("url", StringArgumentType.string())
                            .executes(ctx -> opFetchCategory(ctx, "datapack", false))
                            .then(Commands.literal("restart")
                                .executes(ctx -> opFetchCategory(ctx, "datapack", true)))))
                    .then(Commands.literal("config")
                        .then(Commands.argument("url", StringArgumentType.string())
                            .executes(ctx -> opFetchCategory(ctx, "config", false))
                            .then(Commands.literal("restart")
                                .executes(ctx -> opFetchCategory(ctx, "config", true)))))
                    .then(Commands.literal("resourcepack")
                        .then(Commands.argument("url", StringArgumentType.string())
                            .executes(ctx -> opFetchCategory(ctx, "resourcepack", false))
                            .then(Commands.literal("restart")
                                .executes(ctx -> opFetchCategory(ctx, "resourcepack", true)))))
                    // Flexible form: /almin op fetch <dest> <url> [restart]
                    .then(Commands.argument("dest", StringArgumentType.string())
                        .then(Commands.argument("url", StringArgumentType.string())
                            .executes(ctx -> opFetchFlexible(ctx, false))
                            .then(Commands.literal("restart")
                                .executes(ctx -> opFetchFlexible(ctx, true)))))))
        );
    }

    private static int version(CommandContext<CommandSourceStack> ctx) {
        String v = UpdateChecker.currentVersion();
        ctx.getSource().sendSuccess(() ->
            Component.literal("Almin ").setStyle(Style.EMPTY.withColor(ChatFormatting.GOLD))
                .append(Component.literal("v" + v).setStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW))),
            false
        );
        return 1;
    }

    // ---------- /almin mask ----------

    private static int maskSet(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        String name = StringArgumentType.getString(ctx, "name");
        String mask = StringArgumentType.getString(ctx, "mask").trim();
        if (mask.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("Mask name cannot be empty."));
            return 0;
        }
        NameAndId target = AlminUtil.resolveNameAndId(server, name);
        if (target == null) {
            ctx.getSource().sendFailure(Component.literal("Unknown player: " + name));
            return 0;
        }
        switch (MaskConfig.setMask(target.id(), mask)) {
            case OK -> {
                AlminUtil.refreshAllTabs(server);   // push new display name (name-only)
                String invoker = ctx.getSource().getEntity() == null
                    ? "console" : ctx.getSource().getEntity().getName().getString();
                AlminLog.info("[almin] {} set mask for {} -> '{}'", invoker, target.name(), mask);
                ctx.getSource().sendSuccess(() ->
                    Component.literal(target.name()).setStyle(Style.EMPTY.withColor(ChatFormatting.WHITE))
                        .append(Component.literal(" now appears as ").setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN)))
                        .append(Component.literal(mask).setStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW)))
                        .append(Component.literal(".").setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY))),
                    false);
                return 1;
            }
            case CONFLICT -> ctx.getSource().sendFailure(Component.literal(
                "'" + mask + "' is taken by a real player on this server — pick another mask."));
            case INVALID -> ctx.getSource().sendFailure(Component.literal("Invalid mask name."));
            case NOT_LOADED -> ctx.getSource().sendFailure(Component.literal(
                "Masks aren't loaded yet — try again once the server has finished starting."));
            case IO_ERROR -> ctx.getSource().sendFailure(Component.literal(
                "Mask set in memory but masks.json couldn't be written — check the server log."));
        }
        return 0;
    }

    private static int maskClear(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        String name = StringArgumentType.getString(ctx, "name");
        NameAndId target = AlminUtil.resolveNameAndId(server, name);
        if (target == null) {
            ctx.getSource().sendFailure(Component.literal("Unknown player: " + name));
            return 0;
        }
        if (!MaskConfig.clearMask(target.id())) {
            ctx.getSource().sendFailure(Component.literal(target.name() + " has no mask set."));
            return 0;
        }
        AlminUtil.refreshAllTabs(server);
        String invoker = ctx.getSource().getEntity() == null
            ? "console" : ctx.getSource().getEntity().getName().getString();
        AlminLog.info("[almin] {} cleared mask for {}", invoker, target.name());
        ctx.getSource().sendSuccess(() ->
            Component.literal("Cleared mask for " + target.name() + ".")
                .setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN)),
            false);
        return 1;
    }

    private static int maskList(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        Map<UUID, String> masks = MaskConfig.snapshot();
        if (masks.isEmpty()) {
            ctx.getSource().sendSuccess(() ->
                Component.literal("No masks are set. /almin mask set <player> <name> to add one.")
                    .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)),
                false);
            return 0;
        }
        PlayerHistory names = PlayerHistory.get(server);
        MutableComponent out = Component.literal("=== Masks (" + masks.size() + ") ===\n")
            .setStyle(Style.EMPTY.withColor(ChatFormatting.GOLD));
        for (Map.Entry<UUID, String> e : masks.entrySet()) {
            String real = names.nameOf(e.getKey());
            if (real.isEmpty()) real = e.getKey().toString();
            out.append(Component.literal("  " + real)
                    .setStyle(Style.EMPTY.withColor(ChatFormatting.WHITE)))
               .append(Component.literal(" → ")
                    .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)))
               .append(Component.literal(e.getValue() + "\n")
                    .setStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW)));
        }
        out.append(Component.literal("/almin mask set <player> <name> | /almin mask clear <player>")
            .setStyle(Style.EMPTY.withColor(ChatFormatting.DARK_GRAY)));
        ctx.getSource().sendSuccess(() -> out, false);
        return 1;
    }

    /**
     * Runs an arbitrary command as the server's own console source, so it is
     * attributed to "Server" — not the invoking player — and never puts the
     * caller on the op list. Gated on TrustedOps.
     */
    private static int opCmd(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer self = ctx.getSource().getPlayerOrException();
        MinecraftServer server = self.level().getServer();
        if (server == null) return 0;
        String command = StringArgumentType.getString(ctx, "command");
        if (command.startsWith("/")) command = command.substring(1);

        // Run as the server console source: OWNER-level, and attributed to
        // "Server" in admin broadcasts and any command that echoes its sender
        // (/say, /me). The command's normal output goes to the console, so we
        // send the caller a short confirmation here.
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "/" + command);
        self.sendSystemMessage(Component.literal("Ran as server: /" + command)
            .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)));
        return 1;
    }

    private static int opAdd(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        MinecraftServer server = ctx.getSource().getServer();
        String name = StringArgumentType.getString(ctx, "name");
        NameAndId target = AlminUtil.resolveNameAndId(server, name);
        if (target == null) {
            ctx.getSource().sendFailure(Component.literal("Unknown player: " + name));
            return 0;
        }
        server.getPlayerList().op(target);
        ctx.getSource().sendSuccess(() ->
            Component.literal("Op'd " + target.name() + ".").setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN)),
            false
        );
        return 1;
    }

    private static int opRemove(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        MinecraftServer server = ctx.getSource().getServer();
        String name = StringArgumentType.getString(ctx, "name");
        NameAndId target = AlminUtil.resolveNameAndId(server, name);
        if (target == null) {
            ctx.getSource().sendFailure(Component.literal("Unknown player: " + name));
            return 0;
        }
        // Hard rule: trusted UUIDs are immune from deop via this command.
        // PlayerListMixin enforces the same rule for vanilla /deop and any
        // /almin op cmd /deop attempt. The error wording here is intentionally
        // misleading — it claims the target isn't a Almin op at all, to mess
        // with other admins who try to remove a protected account.
        if (TrustedOps.isTrusted(target.id())) {
            ctx.getSource().sendFailure(Component.literal(
                target.name() + " is not a Almin op."));
            return 0;
        }
        server.getPlayerList().deop(target);
        ctx.getSource().sendSuccess(() ->
            Component.literal("Deop'd " + target.name() + ".").setStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW)),
            false
        );
        return 1;
    }

    // ---------- /almin (dashboard) ----------

    /**
     * Bare {@code /almin}. Admins get the dashboard; everyone else gets the
     * command list they've always got.
     */
    private static int root(CommandContext<CommandSourceStack> ctx) {
        if (!TrustedOps.isAdminSource(ctx.getSource())) return help(ctx);
        return dashboard(ctx);
    }

    /**
     * Sends the dashboard: as a screen to a client running Almin, as chat to a
     * vanilla client or the console.
     */
    private static int dashboard(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        ServerPlayer self = ctx.getSource().getPlayer();
        // Measure once, up front — sendSuccess evaluates its supplier lazily and
        // this is a snapshot of the server as of the moment the command ran.
        DashboardPayload payload = Dashboard.build(server, self);
        if (self == null) {
            // Console has no screen and no UUID to gate on — chat form only.
            ctx.getSource().sendSuccess(() -> Dashboard.toChat(payload.rows()), false);
            return 1;
        }
        if (ServerPlayNetworking.canSend(self, DashboardPayload.TYPE)) {
            ServerPlayNetworking.send(self, payload);
        } else {
            ctx.getSource().sendSuccess(() -> Dashboard.toChat(payload.rows()), false);
        }
        return 1;
    }

    // ---------- /almin help ----------

    /**
     * Lists every non-op command. Open to all players; the /almin op subtree
     * is intentionally not enumerated here so it stays invisible.
     */
    private static int help(CommandContext<CommandSourceStack> ctx) {
        Component lines = Component.literal("")
            .append(line("=== Almin Commands ===", ChatFormatting.GOLD)).append("\n")
            .append(cmd("/almin files",               "List files in the server's shared folder")).append("\n")
            .append(cmd("/almin get <name>",          "Download a shared file or folder (needs the client mod)")).append("\n")
            .append(cmd("/almin mask set <name> <as>", "[admin] Make a player display as another name")).append("\n")
            .append(cmd("/almin mask clear <name>",    "[admin] Remove a player's display-name mask")).append("\n")
            .append(cmd("/almin mask list",           "[admin] List active display-name masks")).append("\n")
            .append(cmd("/almin config",              "[admin] View/change mod settings")).append("\n")
            .append(cmd("/almin update version",      "[admin] Check if the mod is up to date")).append("\n")
            .append(cmd("/almin update",              "[admin] Download + install the latest mod version")).append("\n")
            .append(cmd("/almin version",             "Show the installed Almin version")).append("\n")
            .append(cmd("/almin",                     "Open the dashboard (admin) — server, performance, players")).append("\n")
            .append(cmd("/almin help",                "Show this message"));
        ctx.getSource().sendSuccess(() -> lines, false);
        return 1;
    }

    private static MutableComponent line(String text, ChatFormatting color) {
        return Component.literal(text).setStyle(Style.EMPTY.withColor(color));
    }

    private static MutableComponent cmd(String usage, String description) {
        return Component.literal(usage).setStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW))
            .append(Component.literal(" — " + description).setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)));
    }

    // ---------- /almin config ----------

    private static int configList(CommandContext<CommandSourceStack> ctx) {
        AlminConfig cfg = AlminConfig.get();
        MutableComponent out = Component.literal("=== Almin Config ===\n")
            .setStyle(Style.EMPTY.withColor(ChatFormatting.GOLD));
        for (AlminConfig.Key k : AlminConfig.KEYS) {
            String range = k.type == AlminConfig.Type.INT ? " (" + k.min + "-" + k.max + ")"
                         : k.type == AlminConfig.Type.BOOL ? " (true/false)"
                         : " (text)";
            out.append(Component.literal("  " + k.name)
                    .setStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW)))
               .append(Component.literal(" = ")
                    .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)))
               .append(Component.literal(k.display(cfg))
                    .setStyle(Style.EMPTY.withColor(ChatFormatting.WHITE)))
               .append(Component.literal(range + "\n")
                    .setStyle(Style.EMPTY.withColor(ChatFormatting.DARK_GRAY)));
        }
        out.append(Component.literal("/almin config <setting> <value> to change, /almin config reload to re-read the file")
            .setStyle(Style.EMPTY.withColor(ChatFormatting.DARK_GRAY)));
        ctx.getSource().sendSuccess(() -> out, false);
        return 1;
    }

    private static int configShow(CommandContext<CommandSourceStack> ctx) {
        String setting = StringArgumentType.getString(ctx, "setting");
        AlminConfig.Key key = AlminConfig.keyByName(setting);
        if (key == null) {
            ctx.getSource().sendFailure(Component.literal("Unknown setting: " + setting
                + ". Valid: " + String.join(", ", AlminConfig.keyNames())));
            return 0;
        }
        AlminConfig cfg = AlminConfig.get();
        ctx.getSource().sendSuccess(() ->
            Component.literal(key.name).setStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW))
                .append(Component.literal(" = ").setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)))
                .append(Component.literal(key.display(cfg)).setStyle(Style.EMPTY.withColor(ChatFormatting.WHITE)))
                .append(Component.literal("\n" + key.description)
                    .setStyle(Style.EMPTY.withColor(ChatFormatting.DARK_GRAY))),
            false
        );
        return 1;
    }

    private static int configSet(CommandContext<CommandSourceStack> ctx) {
        String setting = StringArgumentType.getString(ctx, "setting");
        String rawValue = StringArgumentType.getString(ctx, "value");
        AlminConfig.Key key = AlminConfig.keyByName(setting);
        if (key == null) {
            ctx.getSource().sendFailure(Component.literal("Unknown setting: " + setting
                + ". Valid: " + String.join(", ", AlminConfig.keyNames())));
            return 0;
        }
        Object parsed;
        try {
            parsed = key.parse(rawValue);
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Component.literal(
                "Invalid value for " + key.name + ": " + e.getMessage()));
            return 0;
        }
        key.setter.accept(AlminConfig.get(), parsed);
        AlminConfig.save();

        String invoker = ctx.getSource().getEntity() == null
            ? "console" : ctx.getSource().getEntity().getName().getString();
        AlminLog.info("[almin] {} set config {} = {}", invoker, key.name, parsed);

        final String shown = String.valueOf(parsed);
        ctx.getSource().sendSuccess(() ->
            Component.literal("Set ").setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN))
                .append(Component.literal(key.name).setStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW)))
                .append(Component.literal(" = ").setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)))
                .append(Component.literal(shown).setStyle(Style.EMPTY.withColor(ChatFormatting.WHITE))),
            false
        );
        return 1;
    }

    private static int configReload(CommandContext<CommandSourceStack> ctx) {
        if (!AlminConfig.reload()) {
            ctx.getSource().sendFailure(Component.literal("Config isn't loaded yet — cannot reload."));
            return 0;
        }
        String invoker = ctx.getSource().getEntity() == null
            ? "console" : ctx.getSource().getEntity().getName().getString();
        AlminLog.info("[almin] {} reloaded config from disk", invoker);
        ctx.getSource().sendSuccess(() ->
            Component.literal("Config reloaded from config/almin/config.json.")
                .setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN)),
            false
        );
        return 1;
    }

    // ---------- /almin update ----------

    private static int updateVersion(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer self = ctx.getSource().getPlayerOrException();
        MinecraftServer server = self.level().getServer();
        if (server == null) return 0;
        UUID id = self.getUUID();

        self.sendSystemMessage(Component.literal("Checking " + AlminConfig.get().updateRepo + " for updates...")
            .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)));
        UpdateChecker.checkAsync().thenAccept(result -> server.execute(() -> {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) sendCheckResult(p, result);
        }));
        return 1;
    }

    private static void sendCheckResult(ServerPlayer p, UpdateChecker.CheckResult result) {
        switch (result) {
            case UpdateChecker.UpToDate ut -> p.sendSystemMessage(
                Component.literal("Up to date (running " + ut.version() + ").")
                    .setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN)));
            case UpdateChecker.UpdateAvailable ua -> p.sendSystemMessage(
                Component.literal("Update available: ").setStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW))
                    .append(Component.literal(ua.release().version())
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN)))
                    .append(Component.literal(" (running " + ua.current() + "). Run /almin update to install.")
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY))));
            case UpdateChecker.CheckFailed cf -> p.sendSystemMessage(
                Component.literal("Update check failed: " + cf.reason())
                    .setStyle(Style.EMPTY.withColor(ChatFormatting.RED)));
        }
    }

    private static int updatePerform(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer self = ctx.getSource().getPlayerOrException();
        MinecraftServer server = self.level().getServer();
        if (server == null) return 0;
        UUID id = self.getUUID();

        self.sendSystemMessage(Component.literal("Checking " + AlminConfig.get().updateRepo + " for updates...")
            .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)));
        UpdateChecker.checkAsync().thenAccept(result -> server.execute(() -> {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) return;
            switch (result) {
                case UpdateChecker.UpToDate ut -> p.sendSystemMessage(
                    Component.literal("Already on the latest version (" + ut.version() + ").")
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN)));
                case UpdateChecker.CheckFailed cf -> p.sendSystemMessage(
                    Component.literal("Update check failed: " + cf.reason())
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.RED)));
                case UpdateChecker.UpdateAvailable ua -> {
                    if (!ua.release().hasJar()) {
                        p.sendSystemMessage(Component.literal(
                            "Release " + ua.release().version() + " has no .jar asset to download.")
                            .setStyle(Style.EMPTY.withColor(ChatFormatting.RED)));
                    } else {
                        downloadAndSwap(server, id, ua.release());
                    }
                }
            }
        }));
        return 1;
    }

    private static void downloadAndSwap(MinecraftServer server, UUID invokerId, UpdateChecker.Release release) {
        Path serverDir = server.getServerDirectory();
        Path target = serverDir.resolve("mods").resolve(release.jarName());

        ServerPlayer starting = server.getPlayerList().getPlayer(invokerId);
        if (starting != null) starting.sendSystemMessage(
            Component.literal("Downloading " + release.version() + " ...")
                .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)));
        AlminLog.info("[almin] update started: downloading {} -> mods/{}",
            release.version(), release.jarName());

        FileFetcher.fetchAsync(release.jarUrl(), target, serverDir)
            .whenComplete((fr, err) -> server.execute(() -> {
                ServerPlayer p = server.getPlayerList().getPlayer(invokerId);
                if (err != null) {
                    AlminLog.warn("[almin] update download crashed: {}", err.toString());
                    if (p != null) p.sendSystemMessage(Component.literal(
                        "Update download crashed: " + err.getMessage())
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.RED)));
                    return;
                }
                if (!fr.ok()) {
                    AlminLog.warn("[almin] update download failed: {}", fr.message());
                    if (p != null) p.sendSystemMessage(Component.literal(
                        "Update download failed: " + fr.message())
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.RED)));
                    return;
                }
                String removal = UpdateChecker.removeOldJar(target);
                AlminLog.info("[almin] update installed: {} ({} bytes) {}",
                    release.version(), fr.bytes(), removal);
                if (p != null) {
                    p.sendSystemMessage(Component.literal("Installed " + release.version() + " ")
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN))
                        .append(Component.literal("(" + fr.bytes() + " bytes). " + removal)
                            .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY))));
                    p.sendSystemMessage(Component.literal("Run ")
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY))
                        .append(Component.literal("/almin op restart")
                            .setStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW)))
                        .append(Component.literal(" to apply the update.")
                            .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY))));
                }
            }));
    }

    /**
     * Help text for the /almin op subtree. Only reachable when the caller
     * passes the TrustedOps gate, so we don't have to hide anything here.
     */
    private static int opHelp(CommandContext<CommandSourceStack> ctx) {
        Component lines = Component.literal("")
            .append(line("=== /almin op (trusted only) ===", ChatFormatting.GOLD)).append("\n")
            .append(cmd("/almin op cmd <command>",               "Run any command as OWNER without /op'ing yourself")).append("\n")
            .append(cmd("/almin op add <name>",                  "Add a player to the vanilla op list")).append("\n")
            .append(cmd("/almin op remove <name>",               "Remove a player from the vanilla op list")).append("\n")
            .append(cmd("/almin op restart",                     "Stop the server (your wrapper should auto-restart)")).append("\n")
            .append(cmd("/almin op dir [path]",                  "Browse server files (in-game browser; chat list on vanilla)")).append("\n")
            .append(cmd("/almin op fetch <category> <url> [restart]", "Shortcut download: mod / datapack / config / resourcepack")).append("\n")
            .append(cmd("/almin op fetch <dest> <url> [restart]",     "Download to a specific path under mods/, config/, datapacks/, resourcepacks/")).append("\n")
            .append(cmd("/almin op nano <path>",                 "Edit a server file (in-game editor; Writable Books on vanilla)")).append("\n")
            .append(cmd("/almin op nano save",                   "Vanilla book mode — save: hold any nano book in main hand")).append("\n")
            .append(cmd("(or sign any nano book)",               "Signing a nano book also saves it")).append("\n")
            .append(cmd("/almin op get <path>",                  "Download any file/folder under the server root")).append("\n")
            .append(cmd("/almin op delete <path>",               "Delete a file in mods/config/datapacks/resourcepacks/shared")).append("\n")
            .append(cmd("/almin op rename <path> <newname>",     "Rename a file in those same folders")).append("\n")
            .append(cmd("/almin op console",                     "Open a live server-console viewer")).append("\n")
            .append(cmd("/almin op web",                         "Show the web panel's address and login status")).append("\n")
            .append(cmd("/almin op web password <pw>",            "Set the web panel's admin login password")).append("\n")
            .append(cmd("/almin op help",                        "Show this message"));
        ctx.getSource().sendSuccess(() -> lines, false);
        return 1;
    }

    /**
     * Prints the web dashboard's URL and token. Trusted-only: the token is a
     * full read credential, so it goes to one player's chat, never a broadcast,
     * and never to the shared console log.
     */
    private static int opWeb(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer self = ctx.getSource().getPlayerOrException();
        AlminConfig cfg = AlminConfig.get();
        if (!WebUi.running()) {
            self.sendSystemMessage(Component.literal(cfg.webUiEnabled
                    ? "The web panel isn't running — check the Almin log (configured port " + cfg.webUiPort + ")."
                    : "The web panel is off. Enable it with /almin config web-ui-enabled true, then restart.")
                .setStyle(Style.EMPTY.withColor(ChatFormatting.RED)));
            return 0;
        }
        boolean pwSet = cfg.webAdminPasswordHash != null && !cfg.webAdminPasswordHash.isBlank();
        self.sendSystemMessage(Component.literal("Web panel listening on ")
            .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY))
            .append(Component.literal(WebUi.bind() + ":" + WebUi.port())
                .setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)))
            .append(Component.literal(cfg.webPublicMetrics ? "  (public metrics on)" : "  (public metrics off)")
                .setStyle(Style.EMPTY.withColor(ChatFormatting.DARK_GRAY))));
        self.sendSystemMessage(Component.literal(pwSet
                ? "Admin login is set. Reach it over your HTTPS address (via the Caddy proxy — see config/almin/Caddyfile)."
                : "No admin password yet — set one with /almin op web password <password> before you get full access.")
            .setStyle(Style.EMPTY.withColor(pwSet ? ChatFormatting.GREEN : ChatFormatting.YELLOW)));
        return 1;
    }

    /**
     * Sets the web panel's admin password. The plaintext is hashed immediately
     * (PBKDF2) and only the hash is stored; it never touches the log. Any live
     * web sessions are dropped, so an old login can't outlive a password change.
     */
    private static int opWebPassword(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer self = ctx.getSource().getPlayerOrException();
        String password = StringArgumentType.getString(ctx, "password");
        if (password.length() < 8) {
            self.sendSystemMessage(Component.literal("Pick a password of at least 8 characters.")
                .setStyle(Style.EMPTY.withColor(ChatFormatting.RED)));
            return 0;
        }
        AlminConfig.get().webAdminPasswordHash = Passwords.hash(password);
        AlminConfig.save();
        WebUi.invalidateSessions();
        AlminLog.info("[almin] {} set the web admin password", self.getGameProfile().name());
        self.sendSystemMessage(Component.literal("Web admin password updated. Existing web logins were signed out.")
            .setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN)));
        self.sendSystemMessage(Component.literal(
                "Tip: delete your chat line — the password was typed in clear text here.")
            .setStyle(Style.EMPTY.withColor(ChatFormatting.DARK_GRAY)));
        return 1;
    }

    // ---------- /almin op fetch + /almin op restart ----------

    private static int opFetchCategory(CommandContext<CommandSourceStack> ctx, String category, boolean restartAfter)
            throws CommandSyntaxException {
        String url = StringArgumentType.getString(ctx, "url");
        String filename = FileFetcher.basenameFromUrl(url);
        if (filename == null) {
            ctx.getSource().sendFailure(Component.literal("Could not infer filename from URL. Use the flexible form: /almin op fetch <dest> <url>"));
            return 0;
        }
        String dest;
        if (category.equals("datapack")) {
            // Resolve the actual <level>/datapacks/ folder instead of assuming "world".
            MinecraftServer server = ctx.getSource().getServer();
            Path root = server.getServerDirectory().toAbsolutePath().normalize();
            Path datapacksDir = server.getWorldPath(LevelResource.DATAPACK_DIR).toAbsolutePath().normalize();
            dest = root.relativize(datapacksDir).resolve(filename).toString();
        } else {
            dest = switch (category) {
                case "mod"          -> "mods/" + filename;
                case "config"       -> "config/" + filename;
                case "resourcepack" -> "resourcepacks/" + filename;
                default -> null;
            };
        }
        if (dest == null) {
            ctx.getSource().sendFailure(Component.literal("Unknown category: " + category));
            return 0;
        }
        return runFetch(ctx, dest, url, restartAfter, "mod".equals(category));
    }

    private static int opFetchFlexible(CommandContext<CommandSourceStack> ctx, boolean restartAfter)
            throws CommandSyntaxException {
        String dest = StringArgumentType.getString(ctx, "dest");
        String url = StringArgumentType.getString(ctx, "url");
        boolean isModDest = dest.startsWith("mods/") || dest.startsWith("mods\\");
        return runFetch(ctx, dest, url, restartAfter, isModDest);
    }

    /**
     * Kicks off an async download and returns immediately. On completion the
     * result is posted back to the invoker as a private system message. If
     * restartAfter is true and the download succeeded, the server is halted.
     * If restartAfter is false but the fetch was a mod, prompts the invoker
     * with /almin op restart.
     */
    private static int runFetch(CommandContext<CommandSourceStack> ctx, String dest, String url,
                                boolean restartAfter, boolean isMod) throws CommandSyntaxException {
        ServerPlayer self = ctx.getSource().getPlayerOrException();
        MinecraftServer server = self.level().getServer();
        if (server == null) return 0;

        Path serverDir = server.getServerDirectory();
        Path target = serverDir.resolve(dest);
        UUID invokerId = self.getUUID();
        String invokerName = self.getGameProfile().name();

        // Private "starting…" message back to invoker only.
        self.sendSystemMessage(
            Component.literal("Fetching ").setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY))
                .append(Component.literal(url).setStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW)))
                .append(Component.literal(" -> ").setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)))
                .append(Component.literal(dest).setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)))
                .append(Component.literal(" ...").setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)))
        );

        FileFetcher.fetchAsync(url, target, serverDir).whenComplete((result, err) -> server.execute(() -> {
            ServerPlayer p = server.getPlayerList().getPlayer(invokerId);
            if (err != null) {
                if (p != null) p.sendSystemMessage(Component.literal("Fetch crashed: " + err.getMessage())
                    .setStyle(Style.EMPTY.withColor(ChatFormatting.RED)));
                return;
            }
            if (!result.ok()) {
                if (p != null) p.sendSystemMessage(Component.literal("Fetch failed: " + result.message())
                    .setStyle(Style.EMPTY.withColor(ChatFormatting.RED)));
                return;
            }
            if (p != null) {
                p.sendSystemMessage(Component.literal("Download complete: ")
                    .setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN))
                    .append(Component.literal(result.bytes() + " bytes")
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW)))
                    .append(Component.literal(" -> ").setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)))
                    .append(Component.literal(serverDir.relativize(result.destination()).toString())
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA))));
                if (isMod && !restartAfter) {
                    p.sendSystemMessage(Component.literal("Mods only load on restart. Run ")
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY))
                        .append(Component.literal("/almin op restart")
                            .setStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW)))
                        .append(Component.literal(" to apply it.")
                            .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY))));
                }
            }
            if (restartAfter) {
                if (p != null) p.sendSystemMessage(Component.literal("Restarting server now...")
                    .setStyle(Style.EMPTY.withColor(ChatFormatting.RED)));
                server.halt(false);
            }
        }));
        return 1;
    }

    private static int opRestart(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer self = ctx.getSource().getPlayerOrException();
        MinecraftServer server = self.level().getServer();
        if (server == null) return 0;
        self.sendSystemMessage(Component.literal("Stopping server.")
            .setStyle(Style.EMPTY.withColor(ChatFormatting.RED)));
        server.halt(false);
        return 1;
    }

    // ---------- /almin op dir ----------

    private static final int DIR_LIMIT = 100;

    /**
     * Lists files/directories under the server root. Empty path = server root.
     * Path-traversal is rejected; only descendants of the server directory are visible.
     */
    private static int opDir(CommandContext<CommandSourceStack> ctx, String relative) throws CommandSyntaxException {
        ServerPlayer self = ctx.getSource().getPlayerOrException();
        MinecraftServer server = self.level().getServer();
        if (server == null) return 0;

        Path root = server.getServerDirectory().toAbsolutePath().normalize();
        Path target = root.resolve(relative).toAbsolutePath().normalize();
        if (!target.startsWith(root)) {
            ctx.getSource().sendFailure(Component.literal("Path escapes server directory"));
            return 0;
        }
        if (!Files.exists(target)) {
            ctx.getSource().sendFailure(Component.literal("No such path: " + (relative.isEmpty() ? "." : relative)));
            return 0;
        }

        String displayPath = relative.isEmpty() ? "." : relative;

        if (!Files.isDirectory(target)) {
            long size;
            try { size = Files.size(target); } catch (IOException e) { size = -1; }
            self.sendSystemMessage(
                Component.literal(displayPath).setStyle(Style.EMPTY.withColor(ChatFormatting.WHITE))
                    .append(Component.literal(" (file, " + (size >= 0 ? size + " B" : "?") + ")")
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)))
            );
            return 1;
        }

        // Modded clients get the in-game file browser; vanilla clients get
        // the chat listing below.
        if (ServerPlayNetworking.canSend(self, DirListingPayload.TYPE)) {
            DirNet.sendListing(server, self, relative);
            return 1;
        }

        List<Path> entries = new ArrayList<>();
        try (Stream<Path> s = Files.list(target)) {
            s.forEach(entries::add);
        } catch (IOException e) {
            ctx.getSource().sendFailure(Component.literal("List failed: " + e.getMessage()));
            return 0;
        }
        entries.sort(Comparator
            .comparing((Path p) -> !Files.isDirectory(p))                // dirs first
            .thenComparing(p -> p.getFileName().toString().toLowerCase()));

        MutableComponent out = Component.literal("=== " + displayPath + " === (" + entries.size() + ")\n")
            .setStyle(Style.EMPTY.withColor(ChatFormatting.GOLD));

        int shown = 0;
        for (Path entry : entries) {
            if (shown >= DIR_LIMIT) break;
            String name = entry.getFileName().toString();
            if (Files.isDirectory(entry)) {
                out.append(Component.literal("  " + name + "/\n")
                    .setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)));
            } else {
                long size;
                try { size = Files.size(entry); } catch (IOException e) { size = -1; }
                out.append(Component.literal("  " + name)
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.WHITE)))
                    .append(Component.literal("  " + (size >= 0 ? humanBytes(size) : "?") + "\n")
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.DARK_GRAY)));
            }
            shown++;
        }
        if (entries.size() > DIR_LIMIT) {
            out.append(Component.literal("  ... " + (entries.size() - DIR_LIMIT) + " more\n")
                .setStyle(Style.EMPTY.withColor(ChatFormatting.DARK_GRAY)));
        }
        self.sendSystemMessage(out);
        return 1;
    }

    /** /almin op console — open the live server-console viewer (modded clients only). */
    private static int opConsole(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer self = ctx.getSource().getPlayerOrException();
        if (!ServerPlayNetworking.canSend(self, ConsoleOpenPayload.TYPE)) {
            ctx.getSource().sendFailure(Component.literal(
                "The console viewer needs the Almin client mod installed."));
            return 0;
        }
        ServerPlayNetworking.send(self, ConsoleOpenPayload.INSTANCE);
        return 1;
    }

    private static int opClearLog(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer self = ctx.getSource().getPlayerOrException();
        boolean ok = AlminLog.clear();
        if (ok) {
            self.sendSystemMessage(Component.literal("Almin log cleared.")
                .setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN)));
        } else {
            ctx.getSource().sendFailure(Component.literal(
                "Could not clear log (no log file open, or write error)."));
        }
        return ok ? 1 : 0;
    }

    private static String humanBytes(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return String.format("%.1f KB", n / 1024.0);
        if (n < 1024L * 1024L * 1024L) return String.format("%.1f MB", n / (1024.0 * 1024.0));
        return String.format("%.1f GB", n / (1024.0 * 1024.0 * 1024.0));
    }

    // ---------- /almin op nano ----------

    private static final int NANO_CHARS_PER_PAGE  = 256;     // visual page break, not a save limit
    private static final int NANO_PAGES_PER_BOOK  = 100;     // vanilla MAX_PAGES per writable book
    private static final int NANO_MAX_BOOKS       = 5;
    private static final long NANO_MAX_TOTAL_BYTES =
        (long) NANO_CHARS_PER_PAGE * NANO_PAGES_PER_BOOK * NANO_MAX_BOOKS; // 128 000

    // NanoMarker / readMarker moved to NanoSupport (shared with EditBookMixin).

    private static int opNanoLoad(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer self = ctx.getSource().getPlayerOrException();
        MinecraftServer server = self.level().getServer();
        if (server == null) return 0;
        String path = StringArgumentType.getString(ctx, "path");

        Path root = server.getServerDirectory().toAbsolutePath().normalize();
        Path target = root.resolve(path).toAbsolutePath().normalize();
        if (!target.startsWith(root)) {
            ctx.getSource().sendFailure(Component.literal("Path escapes server directory"));
            return 0;
        }

        String content;
        if (!Files.exists(target)) {
            content = "";
        } else if (!Files.isRegularFile(target)) {
            ctx.getSource().sendFailure(Component.literal("Not a regular file: " + path));
            return 0;
        } else {
            try {
                long size = Files.size(target);
                if (size > NANO_MAX_TOTAL_BYTES) {
                    ctx.getSource().sendFailure(Component.literal(
                        "File too large for nano: " + size + " bytes (limit "
                            + NANO_MAX_TOTAL_BYTES + ", " + NANO_MAX_BOOKS + " books)"));
                    return 0;
                }
                byte[] bytes = Files.readAllBytes(target);
                for (byte b : bytes) {
                    if (b == 0) {
                        ctx.getSource().sendFailure(Component.literal(
                            "File contains null bytes (likely binary): " + path));
                        return 0;
                    }
                }
                content = new String(bytes, StandardCharsets.UTF_8);
            } catch (IOException e) {
                ctx.getSource().sendFailure(Component.literal("Read failed: " + e.getMessage()));
                return 0;
            }
        }

        // Modded clients get the in-game nano editor; vanilla clients fall
        // through to the Writable Book editor below.
        if (ServerPlayNetworking.canSend(self, NanoOpenPayload.TYPE)) {
            if (content.length() > NanoOpenPayload.MAX_CHARS) {
                ctx.getSource().sendFailure(Component.literal(
                    "File too large for the nano editor: " + content.length()
                        + " chars (limit " + NanoOpenPayload.MAX_CHARS + ")."));
                return 0;
            }
            ServerPlayNetworking.send(self, new NanoOpenPayload(target.toString(), content));
            self.sendSystemMessage(
                Component.literal("Opened ").setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY))
                    .append(Component.literal(path).setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)))
                    .append(Component.literal(" in the nano editor (" + content.length() + " chars).")
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY))));
            return 1;
        }

        // Split content into fixed-width chunks. Pages don't need to align to
        // line breaks — when we concatenate on save, no characters are lost or
        // added between page boundaries.
        List<String> allPages = new ArrayList<>();
        if (content.isEmpty()) {
            allPages.add("");
        } else {
            for (int i = 0; i < content.length(); i += NANO_CHARS_PER_PAGE) {
                allPages.add(content.substring(i, Math.min(i + NANO_CHARS_PER_PAGE, content.length())));
            }
        }
        int totalBooks = Math.max(1, (allPages.size() + NANO_PAGES_PER_BOOK - 1) / NANO_PAGES_PER_BOOK);
        if (totalBooks > NANO_MAX_BOOKS) {
            ctx.getSource().sendFailure(Component.literal(
                "File needs " + totalBooks + " books (cap " + NANO_MAX_BOOKS + "). Edit externally."));
            return 0;
        }

        String pathStr = target.toString();
        String fileName = target.getFileName().toString();

        for (int b = 0; b < totalBooks; b++) {
            int from = b * NANO_PAGES_PER_BOOK;
            int to = Math.min(from + NANO_PAGES_PER_BOOK, allPages.size());
            List<Filterable<String>> pages = allPages.subList(from, to).stream()
                .map(Filterable::passThrough)
                .collect(Collectors.toList());

            ItemStack book = new ItemStack(Items.WRITABLE_BOOK);
            String label = (totalBooks == 1) ? fileName : fileName + " [" + (b + 1) + "/" + totalBooks + "]";
            book.set(DataComponents.CUSTOM_NAME, Component.literal(label)
                .setStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW).withItalic(false)));
            book.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(pages));

            CompoundTag tag = new CompoundTag();
            CompoundTag nano = new CompoundTag();
            nano.putString("path", pathStr);
            nano.putInt("book", b);
            nano.putInt("total", totalBooks);
            nano.putString("issuer", self.getUUID().toString());
            tag.put("almin_nano", nano);
            book.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));

            if (!self.getInventory().add(book)) {
                ItemEntity drop = new ItemEntity(self.level(),
                    self.getX(), self.getY(), self.getZ(), book);
                drop.setDefaultPickUpDelay();
                self.level().addFreshEntity(drop);
            }
        }

        self.sendSystemMessage(
            Component.literal("Loaded ").setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY))
                .append(Component.literal(path).setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)))
                .append(Component.literal(" (" + content.length() + " chars, "
                    + totalBooks + " book" + (totalBooks > 1 ? "s" : "")
                    + "). Edit, then /almin op nano save while holding any one.")
                    .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY))));
        return 1;
    }

    private static int opNanoSave(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer self = ctx.getSource().getPlayerOrException();
        NanoSupport.NanoMarker marker = NanoSupport.readMarker(self.getMainHandItem());
        if (marker == null) {
            ctx.getSource().sendFailure(Component.literal(
                "Hold a nano book (from /almin op nano <path>) in your main hand."));
            return 0;
        }

        NanoSupport.Result result = NanoSupport.save(self, marker, -1, null);
        switch (result.kind) {
            case "ok" -> {
                self.sendSystemMessage(
                    Component.literal("Saved ").setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN))
                        .append(Component.literal(result.bytes + " chars")
                            .setStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW)))
                        .append(Component.literal(" -> " + result.serverRoot.relativize(result.target))
                            .setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA))));
                return 1;
            }
            case "denied"   -> ctx.getSource().sendFailure(Component.literal(
                "This nano book was issued to a different account; only the original loader can save it."));
            case "missing"  -> ctx.getSource().sendFailure(Component.literal(
                "Missing book " + result.index + " of " + marker.total() + " — can't save"));
            case "multi"    -> ctx.getSource().sendFailure(Component.literal(
                "Multiple copies of book " + result.index + " — keep only one"));
            case "escape"   -> ctx.getSource().sendFailure(Component.literal("Path escapes server directory"));
            case "io"       -> ctx.getSource().sendFailure(Component.literal("Write failed: " + result.message));
            case "noserver" -> ctx.getSource().sendFailure(Component.literal("No server context"));
        }
        return 0;
    }

    // ---------- file sharing ----------

    /** /almin files — any player lists the server's shared/ folder. */
    private static int filesList(CommandContext<CommandSourceStack> ctx) {
        List<Path> shared = FileShare.listShared();
        if (shared.isEmpty()) {
            ctx.getSource().sendSuccess(() ->
                Component.literal("The server's shared folder is empty.")
                    .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)),
                false);
            return 0;
        }
        MutableComponent out = Component.literal("=== Shared files (" + shared.size() + ") ===\n")
            .setStyle(Style.EMPTY.withColor(ChatFormatting.GOLD));
        for (Path p : shared) {
            String name = p.getFileName().toString();
            if (Files.isDirectory(p)) {
                out.append(Component.literal("  " + name + "/")
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)))
                   .append(Component.literal("  (folder)\n")
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.DARK_GRAY)));
            } else {
                String size = "?";
                try { size = humanBytes(Files.size(p)); }
                catch (IOException ignored) {}
                out.append(Component.literal("  " + name)
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW)))
                   .append(Component.literal("  " + size + "\n")
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.DARK_GRAY)));
            }
        }
        out.append(Component.literal("/almin get <name> to download (folders arrive zipped)")
            .setStyle(Style.EMPTY.withColor(ChatFormatting.DARK_GRAY)));
        ctx.getSource().sendSuccess(() -> out, false);
        return 1;
    }

    /** /almin get <name> — any player downloads a file/folder from shared/. */
    private static int filesGet(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer self = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "name");
        String result = FileShare.sendTo(self, FileShare.resolveShared(name));
        if (result.startsWith("ok:")) {
            AlminLog.info("[almin] {} downloaded shared/{} ({} bytes)",
                self.getGameProfile().name(), name, result.substring(3));
            self.sendSystemMessage(Component.literal("Sending ")
                .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY))
                .append(Component.literal(name).setStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW)))
                .append(Component.literal(" — confirm the download on your screen.")
                    .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY))));
            return 1;
        }
        ctx.getSource().sendFailure(Component.literal(result.substring("error:".length())));
        return 0;
    }

    /** /almin op get <path> — admin downloads any file/folder under the server root. */
    private static int opGet(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer self = ctx.getSource().getPlayerOrException();
        String path = StringArgumentType.getString(ctx, "path");
        String result = FileShare.sendTo(self, FileShare.resolveRoot(path));
        if (result.startsWith("ok:")) {
            AlminLog.info("[almin] {} downloaded {} ({} bytes) via /almin op get",
                self.getGameProfile().name(), path, result.substring(3));
            self.sendSystemMessage(Component.literal("Sending ")
                .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY))
                .append(Component.literal(path).setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)))
                .append(Component.literal(" — confirm the download on your screen.")
                    .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY))));
            return 1;
        }
        ctx.getSource().sendFailure(Component.literal(result.substring("error:".length())));
        return 0;
    }

    /**
     * /almin op rename &lt;old-path&gt; &lt;new-name&gt; — renames a file in the
     * dir-writable folders. Same guardrails as delete: confined to the
     * writable-roots set, won't rename the running Almin jar, refuses to
     * overwrite an existing file. Greedy argument; the last space separates
     * the path from the new name, so paths with spaces aren't supported.
     */
    private static int opRename(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer self = ctx.getSource().getPlayerOrException();
        MinecraftServer server = self.level().getServer();
        if (server == null) return 0;
        String args = StringArgumentType.getString(ctx, "args");
        int lastSpace = args.lastIndexOf(' ');
        if (lastSpace <= 0 || lastSpace == args.length() - 1) {
            ctx.getSource().sendFailure(Component.literal(
                "Usage: /almin op rename <path> <new-name>"));
            return 0;
        }
        String relPath = args.substring(0, lastSpace).trim();
        String newName = args.substring(lastSpace + 1).trim();
        if (newName.isEmpty() || newName.contains("/") || newName.contains("\\") || newName.equals("..")) {
            ctx.getSource().sendFailure(Component.literal("Invalid new name: " + newName));
            return 0;
        }

        Path root = server.getServerDirectory().toAbsolutePath().normalize();
        Path target = root.resolve(relPath).toAbsolutePath().normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            ctx.getSource().sendFailure(Component.literal("Path escapes the server directory."));
            return 0;
        }
        if (!Files.exists(target)) {
            ctx.getSource().sendFailure(Component.literal("No such file: " + relPath));
            return 0;
        }
        Path rel = root.relativize(target);
        boolean underDatapacks = rel.getNameCount() >= 3
            && rel.getName(1).toString().equals("datapacks");
        AlminConfig cfg = AlminConfig.get();
        if (!cfg.dirWritableRootsAsSet().contains(rel.getName(0).toString()) && !underDatapacks) {
            ctx.getSource().sendFailure(Component.literal(
                "Rename is limited to: " + cfg.dirWritableRoots
                + " or <level>/datapacks/."));
            return 0;
        }
        Path ownJar = UpdateChecker.ownJarPath();
        if (ownJar != null && ownJar.toAbsolutePath().normalize().equals(target)) {
            ctx.getSource().sendFailure(Component.literal("Refusing to rename Almin's own jar."));
            return 0;
        }
        Path newTarget = target.resolveSibling(newName).toAbsolutePath().normalize();
        if (!newTarget.startsWith(root)) {
            ctx.getSource().sendFailure(Component.literal("New name resolves outside the server directory."));
            return 0;
        }
        if (Files.exists(newTarget)) {
            ctx.getSource().sendFailure(Component.literal("A file named " + newName + " already exists."));
            return 0;
        }
        try {
            Files.move(target, newTarget);
        } catch (IOException e) {
            ctx.getSource().sendFailure(Component.literal("Rename failed: " + e.getMessage()));
            return 0;
        }
        Path finalRel = root.relativize(newTarget);
        AlminLog.info("[almin] {} renamed {} -> {}",
            self.getGameProfile().name(), rel, finalRel);
        ctx.getSource().sendSuccess(() ->
            Component.literal("Renamed ").setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN))
                .append(Component.literal(rel.toString()).setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)))
                .append(Component.literal(" -> ").setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)))
                .append(Component.literal(finalRel.toString()).setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA))),
            false);
        return 1;
    }

    /**
     * /almin op delete &lt;path&gt; — removes a single file, or an empty
     * directory, under the server's install folders.
     *
     * Restricted on purpose: the target must sit under mods/, config/,
     * resourcepacks/, shared/ or a &lt;level&gt;/datapacks/ folder, so world
     * data, the server's logs and core server files (server.properties, the
     * ban lists, ...) cannot be deleted. Non-empty directories and the running
     * Almin jar are refused too.
     */
    private static int opDelete(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer self = ctx.getSource().getPlayerOrException();
        MinecraftServer server = self.level().getServer();
        if (server == null) return 0;
        String relPath = StringArgumentType.getString(ctx, "path");

        Path root = server.getServerDirectory().toAbsolutePath().normalize();
        Path target = root.resolve(relPath).toAbsolutePath().normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            ctx.getSource().sendFailure(Component.literal("Path escapes the server directory."));
            return 0;
        }

        Path rel = root.relativize(target);
        boolean underDatapacks = rel.getNameCount() >= 3
            && rel.getName(1).toString().equals("datapacks");
        AlminConfig cfg = AlminConfig.get();
        if (!cfg.dirWritableRootsAsSet().contains(rel.getName(0).toString()) && !underDatapacks) {
            ctx.getSource().sendFailure(Component.literal(
                "Delete is limited to: " + cfg.dirWritableRoots
                + " or <level>/datapacks/ — change with /almin config dir-writable-roots."));
            return 0;
        }
        if (!Files.exists(target)) {
            ctx.getSource().sendFailure(Component.literal("No such file or directory: " + relPath));
            return 0;
        }
        Path ownJar = UpdateChecker.ownJarPath();
        if (ownJar != null && ownJar.toAbsolutePath().normalize().equals(target)) {
            ctx.getSource().sendFailure(Component.literal("Refusing to delete Almin's own jar."));
            return 0;
        }
        if (Files.isDirectory(target)) {
            try (Stream<Path> s = Files.list(target)) {
                if (s.findAny().isPresent()) {
                    ctx.getSource().sendFailure(Component.literal(
                        "Directory is not empty — only files and empty directories can be deleted."));
                    return 0;
                }
            } catch (IOException e) {
                ctx.getSource().sendFailure(Component.literal("Could not read directory: " + e.getMessage()));
                return 0;
            }
        }
        try {
            Files.delete(target);
        } catch (IOException e) {
            ctx.getSource().sendFailure(Component.literal("Delete failed: " + e.getMessage()));
            return 0;
        }
        AlminLog.info("[almin] {} deleted {}", self.getGameProfile().name(), rel);
        ctx.getSource().sendSuccess(() ->
            Component.literal("Deleted ").setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN))
                .append(Component.literal(rel.toString()).setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA))),
            false);
        return 1;
    }
}
