package com.schecks.almin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A small, read-only piece of the live world for an activity scene.
 *
 * <p>This deliberately reads only chunks that are already loaded. Opening the
 * admin panel must never generate terrain or keep a distant chunk alive. It
 * also returns only exposed blocks: a solid volume says no more than its
 * outside faces, and sending every buried stone block would make both the
 * server tick and the browser pay for data nobody can see.
 */
final class SceneContext {
    private static final int MIN_RADIUS = 4;
    private static final int MAX_RADIUS = 32;
    private static final int MAX_VERTICAL = 96;
    private static final int MAX_BLOCKS = 8000;

    private SceneContext() {}

    /** Called on the Minecraft server thread. */
    static JsonObject capture(MinecraftServer server, String dim, int centreX, int centreZ,
                              int wantedMinY, int wantedMaxY, int wantedRadius) {
        ServerLevel level = level(server, dim);
        if (level == null) return null;

        int radius = clamp(wantedRadius, MIN_RADIUS, MAX_RADIUS);
        int low = Math.max(level.getMinY(), Math.min(wantedMinY, wantedMaxY));
        int high = Math.min(level.getMaxY() - 1, Math.max(wantedMinY, wantedMaxY));
        if (high < low) {
            int y = clamp((wantedMinY + wantedMaxY) / 2,
                level.getMinY(), level.getMaxY() - 1);
            low = y;
            high = y;
        }
        if (high - low + 1 > MAX_VERTICAL) {
            int middle = low + (high - low) / 2;
            low = Math.max(level.getMinY(), middle - MAX_VERTICAL / 2);
            high = Math.min(level.getMaxY() - 1, low + MAX_VERTICAL - 1);
            low = Math.max(level.getMinY(), high - MAX_VERTICAL + 1);
        }

        int side = radius * 2 + 1;
        int height = high - low + 1;
        BlockState[] states = new BlockState[side * side * height];
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        // One world read per cell. Exposure is worked out from this bounded
        // array afterwards, instead of asking the level for six neighbours of
        // every solid block (which made a large scene need millions of reads).
        for (int dz = 0; dz < side; dz++) {
            int z = centreZ - radius + dz;
            for (int dx = 0; dx < side; dx++) {
                int x = centreX - radius + dx;
                cursor.set(x, low, z);
                if (!level.hasChunkAt(cursor)) continue;
                for (int dy = 0; dy < height; dy++) {
                    cursor.set(x, low + dy, z);
                    states[index(dx, dy, dz, side)] = level.getBlockState(cursor);
                }
            }
        }

        JsonArray blocks = new JsonArray();
        boolean truncated = false;
        outer:
        // Top down keeps the surface when an unusually intricate cave system
        // reaches the cap. Ordinary scenes stay well below it.
        for (int dy = height - 1; dy >= 0; dy--) {
            int y = low + dy;
            for (int dz = 0; dz < side; dz++) {
                int z = centreZ - radius + dz;
                for (int dx = 0; dx < side; dx++) {
                    int x = centreX - radius + dx;
                    BlockState state = states[index(dx, dy, dz, side)];
                    if (state == null || state.isAir()
                        || !exposed(states, dx, dy, dz, side, height)) continue;
                    if (blocks.size() >= MAX_BLOCKS) {
                        truncated = true;
                        break outer;
                    }
                    JsonObject block = new JsonObject();
                    block.addProperty("x", x);
                    block.addProperty("y", y);
                    block.addProperty("z", z);
                    block.addProperty("what", state.getBlock().getName().getString());
                    blocks.add(block);
                }
            }
        }

        JsonObject out = new JsonObject();
        out.addProperty("dim", level.dimension().identifier().getPath());
        out.addProperty("x", centreX);
        out.addProperty("z", centreZ);
        out.addProperty("radius", radius);
        out.addProperty("minY", low);
        out.addProperty("maxY", high);
        out.addProperty("truncated", truncated);
        out.add("blocks", blocks);
        return out;
    }

    private static boolean exposed(BlockState[] states, int x, int y, int z,
                                   int side, int height) {
        return open(states, x - 1, y, z, side, height)
            || open(states, x + 1, y, z, side, height)
            || open(states, x, y - 1, z, side, height)
            || open(states, x, y + 1, z, side, height)
            || open(states, x, y, z - 1, side, height)
            || open(states, x, y, z + 1, side, height);
    }

    private static boolean open(BlockState[] states, int x, int y, int z,
                                int side, int height) {
        if (x < 0 || x >= side || y < 0 || y >= height || z < 0 || z >= side) return true;
        BlockState beside = states[index(x, y, z, side)];
        // Null means the neighbouring chunk was not loaded. It is the edge of
        // the truthful slice and is never queried merely to fill this view.
        return beside == null || beside.isAir();
    }

    private static int index(int x, int y, int z, int side) {
        return (y * side + z) * side + x;
    }

    private static ServerLevel level(MinecraftServer server, String wanted) {
        if (server == null) return null;
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().identifier().getPath().equals(wanted)) return level;
        }
        return null;
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }
}
