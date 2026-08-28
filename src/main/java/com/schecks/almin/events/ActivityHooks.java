package com.schecks.almin.events;

import com.schecks.almin.ActivityLog;
import com.schecks.almin.AlminConfig;
import com.schecks.almin.AlminLog;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Wires the game's events into {@link ActivityLog}.
 *
 * <p>Every listener is a pure observer: it records and returns whatever means
 * "carry on". Nothing here can cancel an interaction, so a bug in this file
 * cannot stop a player doing something — it can only fail to write it down.
 *
 * <p>Two actions are not events at all and arrive from mixins instead:
 * commands ({@code CommandActivityMixin}) and opening a container
 * ({@code OpenMenuActivityMixin}).
 */
public final class ActivityHooks {
    private ActivityHooks() {}

    /**
     * How many observer failures are written down before the rest are counted
     * silently. A hook that fails once will fail on every interaction, and
     * filling the log with the same stack trace helps nobody.
     */
    private static final int MAX_REPORTED = 5;
    private static final java.util.concurrent.atomic.AtomicInteger failures =
        new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Runs an observer without letting it reach the game.
     *
     * <p>Fabric propagates whatever a listener throws, and these listeners sit
     * on the join, chat, break and death paths — the busiest in the server. A
     * bug in the activity log must cost a missing row, never a crash, so
     * everything here is caught, including {@code Error}.
     */
    private static void safely(String what, Runnable job) {
        try {
            job.run();
        } catch (Throwable t) {
            int n = failures.incrementAndGet();
            if (n <= MAX_REPORTED) {
                AlminLog.warn("[almin] activity hook '{}' failed ({}): {}", what, n, t.toString());
            }
        }
    }

    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
            safely("join", () -> ActivityLog.record(handler.getPlayer(), "join", "")));

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
            safely("leave", () -> ActivityLog.record(handler.getPlayer(), "leave", "")));

        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) ->
            safely("chat", () -> ActivityLog.record(sender, "chat", message.signedContent())));

        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, entity) -> {
            if (player instanceof ServerPlayer p) {
                safely("break", () -> ActivityLog.recordBlock(p, "break", blockName(state), pos));
            }
        });

        // Right-clicking a block is as close as the API gets to "placed":
        // placement itself is resolved deep inside item use. The item in hand
        // is what makes the row worth reading either way.
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!level.isClientSide() && player instanceof ServerPlayer p) {
                safely("use", () -> {
                    ItemStack held = p.getItemInHand(hand);
                    BlockPos pos = hit.getBlockPos();
                    String what = held.isEmpty()
                        ? blockName(level.getBlockState(pos))
                        : itemName(held) + " on " + blockName(level.getBlockState(pos));
                    ActivityLog.recordBlock(p, "use", what, pos);
                });
            }
            return InteractionResult.PASS;
        });

        // Every swing at anything, not only at people: hitting a mob is the
        // difference between a fight and a grief report. Folded, because a
        // player fighting produces one of these per swing.
        AttackEntityCallback.EVENT.register((player, level, hand, target, hit) -> {
            if (!level.isClientSide() && player instanceof ServerPlayer p && combat()) {
                safely("attack", () ->
                    ActivityLog.recordFolded(p, "attack", nameOf(target)));
            }
            return InteractionResult.PASS;
        });

        // Damage taken, which is where "who hit whom" actually lives: an arrow,
        // a potion or a mob never goes through the attack callback at all.
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, blocked, taken, byShield) -> {
            if (entity instanceof ServerPlayer p && combat()) {
                safely("hurt", () -> {
                    String from = source.getEntity() != null
                        ? nameOf(source.getEntity())
                        : source.type().msgId();
                    String detail = from + "  " + Math.round(taken) + " damage"
                        + (byShield ? " (blocked)" : "");
                    ActivityLog.recordFolded(p, "hurt", detail);
                });
            }
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer p) {
                safely("death", () ->
                    ActivityLog.record(p, "death", source.getLocalizedDeathMessage(p).getString()));
            }
        });

        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) ->
            safely("respawn", () -> ActivityLog.record(newPlayer, "respawn",
                alive ? "returned from the End" : "")));

        // Eating, drinking, throwing, firing a bow. Folded — eating one meal is
        // several of these.
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (!level.isClientSide() && player instanceof ServerPlayer p && items()) {
                ItemStack held = p.getItemInHand(hand);
                if (!held.isEmpty()) {
                    safely("item", () -> ActivityLog.recordFolded(p, "item", itemName(held)));
                }
            }
            return InteractionResult.PASS;
        });

        // Right-clicking an entity: trading with a villager, naming, leashing,
        // shearing, mounting.
        UseEntityCallback.EVENT.register((player, level, hand, target, hit) -> {
            if (!level.isClientSide() && player instanceof ServerPlayer p && items()) {
                safely("interact", () -> ActivityLog.recordFolded(p, "interact", nameOf(target)));
            }
            return InteractionResult.PASS;
        });
    }

    private static boolean combat() {
        return AlminConfig.get().activityCombat;
    }

    private static boolean items() {
        return AlminConfig.get().activityItems;
    }

    /** A player's account name, or an entity's type name. */
    private static String nameOf(Entity e) {
        if (e == null) return "?";
        if (e instanceof ServerPlayer p) return p.getGameProfile().name();
        return e.getType().getDescription().getString();
    }

    private static String blockName(BlockState state) {
        return state == null ? "?" : state.getBlock().getName().getString();
    }

    private static String itemName(ItemStack stack) {
        return stack.getHoverName().getString();
    }
}
