package com.schecks.almin.events;

import com.schecks.almin.ActivityLog;
import com.schecks.almin.AlminConfig;
import com.schecks.almin.AlminLog;
import net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
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
 * <p>Several actions are not events at all and arrive from mixins instead:
 * commands, opening a container, crafting, trading, dropping, enchanting,
 * writing on a sign, and earning an advancement. Each of those has its own
 * mixin next to this file, for the same reason — the game has the moment and
 * Fabric has no event for it.
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

        // Right-clicking a block. Held back rather than recorded, because at
        // this point in the interaction nobody knows yet what it was — see
        // the note on PendingUse.
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!level.isClientSide() && player instanceof ServerPlayer p) {
                safely("use", () -> {
                    // The callback runs once per hand. When the main hand
                    // placed a block, the off-hand pass arrives afterwards
                    // with the placement already written and no pending row
                    // left to replace — so it used to be filed as a use, and
                    // every placement produced two rows: "place Oak Planks"
                    // and "use Oak Planks with Oak Planks" beside it.
                    if (placedThisTick.contains(p.getUUID())) return;
                    ItemStack held = p.getItemInHand(hand);
                    BlockPos pos = hit.getBlockPos();
                    String what = blockName(level.getBlockState(pos));
                    if (!held.isEmpty()) what = what + " with " + itemName(held);
                    pending.put(p.getUUID(), new PendingUse(p, what, pos));
                });
            }
            return InteractionResult.PASS;
        });

        // Anything still held back once the tick is over was a use after all.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!placedThisTick.isEmpty()) placedThisTick.clear();
            if (pending.isEmpty()) return;
            safely("use-flush", ActivityHooks::flushPending);
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

        // What a player killed. Separate from 'attack', which is every swing:
        // forty swings and one kill is a fight, and forty kills is a farm. It
        // is also the row that answers "who killed my horse".
        ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register((level, killer, killed, src) -> {
            if (killer instanceof ServerPlayer p && combat() && killed != null) {
                safely("kill", () -> ActivityLog.recordFolded(p, "kill", nameOf(killed)));
            }
        });

        // Sleeping. Small, and the clearest marker in the log of where
        // somebody actually lives: a bed is a place people come back to.
        EntitySleepEvents.START_SLEEPING.register((entity, pos) -> {
            if (entity instanceof ServerPlayer p && progress()) {
                safely("sleep", () -> ActivityLog.recordBlock(p, "sleep", "went to bed", pos));
            }
        });

        // Going through a portal, or dying out of the End. Recorded in the
        // dimension arrived in, which is where the next thing they do will be.
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((p, from, to) -> {
            if (progress()) {
                safely("portal", () -> ActivityLog.record(p, "portal",
                    "from " + dimName(from) + " to " + dimName(to)));
            }
        });
    }

    /** The short name of a level, as the activity map spells dimensions. */
    private static String dimName(net.minecraft.server.level.ServerLevel level) {
        try {
            return level.dimension().identifier().getPath();
        } catch (Throwable t) {
            return "?";
        }
    }

    // ---------- placing a block ----------

    /**
     * A right-click on a block, waiting to find out what it turned into.
     *
     * <p>Placing a block and interacting with one arrive at the same event:
     * {@code UseBlockCallback} fires before the interaction resolves, so at
     * that moment putting dirt down and opening a chest are indistinguishable.
     * Recording there made every placement read as "used Dirt on Dirt", which
     * is both wrong and the opposite of what someone reading a grief report
     * needs — placements are the rows that matter most.
     *
     * <p>So the row waits. If the placement goes through, {@code BlockItem}
     * reaches {@link #placed} in the same tick and the row becomes a placement
     * instead. If nothing places, the end of the tick writes it out as the use
     * it always was.
     */
    private record PendingUse(ServerPlayer player, String detail, BlockPos pos) {}

    private static final java.util.Map<java.util.UUID, PendingUse> pending =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Who has already placed a block this tick.
     *
     * <p>Cleared at the end of every tick, alongside the pending uses it
     * exists to suppress. A player can place more than one block in a tick,
     * and each of those is still its own row — this only stops the second
     * <em>hand</em> of one interaction being counted as a separate act.
     */
    private static final java.util.Set<java.util.UUID> placedThisTick =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Called from {@code BlockPlaceActivityMixin} when a block actually goes
     * down. Replaces the held-back use, so one right-click is one row.
     */
    public static void placed(ServerPlayer player, BlockState state, BlockPos pos) {
        safely("place", () -> {
            pending.remove(player.getUUID());
            placedThisTick.add(player.getUUID());
            ActivityLog.recordBlock(player, "place", blockName(state), pos);
        });
    }

    /**
     * Writes out every right-click that did not turn into a placement.
     *
     * <p>Drains rather than iterates: a use recorded during the drain would
     * belong to the next tick anyway.
     */
    private static void flushPending() {
        for (java.util.UUID id : java.util.List.copyOf(pending.keySet())) {
            PendingUse u = pending.remove(id);
            if (u == null) continue;
            ActivityLog.recordBlock(u.player(), "use", u.detail(), u.pos());
        }
    }

    private static boolean combat() {
        return AlminConfig.get().activityCombat;
    }

    private static boolean items() {
        return AlminConfig.get().activityItems;
    }

    private static boolean progress() {
        return AlminConfig.get().activityProgress;
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
