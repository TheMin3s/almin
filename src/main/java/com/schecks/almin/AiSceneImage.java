package com.schecks.almin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** A compact, font-free picture of the block geometry handed to a model. */
final class AiSceneImage {
    static final int WIDTH = 768;
    static final int HEIGHT = 512;
    private static final int COLS = 3;
    private static final int ROWS = 2;
    private static final int MAX_PANELS = COLS * ROWS;

    private AiSceneImage() {}

    /**
     * Up to six episode panels. The upper part is an X/Z plan and the lower
     * strip is elevation along the wider horizontal axis. Gold is placed and
     * red is broken. No font or client asset is needed, so this works on a
     * trimmed dedicated-server JRE.
     */
    static byte[] render(List<Episodes.Episode> episodes, List<ActivityLog.Entry> rows) {
        if (episodes == null || rows == null) return null;
        List<List<ActivityLog.Entry>> panels = new ArrayList<>();
        for (Episodes.Episode episode : episodes) {
            if (!spatial(episode.kind())) continue;
            List<ActivityLog.Entry> matching = new ArrayList<>();
            for (ActivityLog.Entry row : rows) {
                if (!episode.player().equals(row.player()) || !episode.dim().equals(row.dim())) continue;
                if (row.at() < episode.from() - 1000 || row.at() > episode.to() + 1000) continue;
                if ("place".equals(row.action()) || "break".equals(row.action())) matching.add(row);
            }
            List<ActivityLog.Entry> work = Episodes.mainWork(matching);
            if (work.size() >= 2) panels.add(work);
            if (panels.size() >= MAX_PANELS) break;
        }
        if (panels.isEmpty()) return null;

        int[] pixels = new int[WIDTH * HEIGHT];
        fill(pixels, 0, 0, WIDTH, HEIGHT, 0xFF0B0D11);
        for (int i = 0; i < panels.size(); i++) {
            int col = i % COLS, row = i / COLS;
            drawPanel(pixels, col * (WIDTH / COLS), row * (HEIGHT / ROWS),
                WIDTH / COLS, HEIGHT / ROWS, panels.get(i));
        }
        try { return Png.encode(pixels, WIDTH, HEIGHT); }
        catch (IOException e) { return null; }
    }

    private static boolean spatial(String kind) {
        return switch (kind) {
            case "build", "bridge", "tower", "redstone", "dig", "clear", "mine",
                 "shaft", "tunnel", "tree", "farm" -> true;
            default -> false;
        };
    }

    private static void drawPanel(int[] px, int ox, int oy, int w, int h,
                                  List<ActivityLog.Entry> rows) {
        fill(px, ox + 4, oy + 4, w - 8, h - 8, 0xFF121720);
        frame(px, ox + 4, oy + 4, w - 8, h - 8, 0xFF394454);
        int split = oy + (h * 3) / 4;
        fill(px, ox + 8, split, w - 16, 1, 0xFF394454);

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (ActivityLog.Entry e : rows) {
            minX = Math.min(minX, e.x()); maxX = Math.max(maxX, e.x());
            minY = Math.min(minY, e.y()); maxY = Math.max(maxY, e.y());
            minZ = Math.min(minZ, e.z()); maxZ = Math.max(maxZ, e.z());
        }

        int planX = ox + 12, planY = oy + 12, planW = w - 24, planH = split - planY - 8;
        double planScale = Math.min(planW / (double) Math.max(1, maxX - minX + 1),
                                    planH / (double) Math.max(1, maxZ - minZ + 1));
        int dot = clamp((int) Math.floor(planScale * .82), 3, 12);
        double usedW = (maxX - minX + 1) * planScale;
        double usedH = (maxZ - minZ + 1) * planScale;
        double left = planX + (planW - usedW) / 2;
        double top = planY + (planH - usedH) / 2;

        boolean xAxis = maxX - minX >= maxZ - minZ;
        int axisMin = xAxis ? minX : minZ, axisMax = xAxis ? maxX : maxZ;
        int elevX = ox + 12, elevY = split + 7, elevW = w - 24, elevH = oy + h - elevY - 12;
        double elevScaleX = elevW / (double) Math.max(1, axisMax - axisMin + 1);
        double elevScaleY = elevH / (double) Math.max(1, maxY - minY + 1);
        int elevDot = clamp((int) Math.floor(Math.min(elevScaleX, elevScaleY) * .8), 2, 8);

        int shown = 0;
        for (ActivityLog.Entry e : rows) {
            if (shown++ >= 2400) break;
            int colour = colour(e, minY, maxY);
            int x = (int) Math.round(left + (e.x() - minX + .5) * planScale - dot / 2.0);
            int y = (int) Math.round(top + (e.z() - minZ + .5) * planScale - dot / 2.0);
            fill(px, x, y, dot, dot, colour);

            int axis = xAxis ? e.x() : e.z();
            int ex = (int) Math.round(elevX + (axis - axisMin + .5) * elevScaleX
                - elevDot / 2.0);
            int ey = (int) Math.round(elevY + elevH - (e.y() - minY + .5) * elevScaleY
                - elevDot / 2.0);
            fill(px, ex, ey, elevDot, elevDot, colour);
        }
    }

    private static int colour(ActivityLog.Entry e, int minY, int maxY) {
        int base = "place".equals(e.action()) ? 0xFFD8A62E : 0xFFE64B55;
        double height = (e.y() - minY) / (double) Math.max(1, maxY - minY);
        double k = .72 + height * .38;
        int r = clamp((int) (((base >> 16) & 255) * k), 0, 255);
        int g = clamp((int) (((base >> 8) & 255) * k), 0, 255);
        int b = clamp((int) ((base & 255) * k), 0, 255);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static void frame(int[] px, int x, int y, int w, int h, int colour) {
        fill(px, x, y, w, 1, colour); fill(px, x, y + h - 1, w, 1, colour);
        fill(px, x, y, 1, h, colour); fill(px, x + w - 1, y, 1, h, colour);
    }

    private static void fill(int[] px, int x, int y, int w, int h, int colour) {
        int x0 = clamp(x, 0, WIDTH), y0 = clamp(y, 0, HEIGHT);
        int x1 = clamp(x + Math.max(0, w), 0, WIDTH);
        int y1 = clamp(y + Math.max(0, h), 0, HEIGHT);
        for (int py = y0; py < y1; py++) {
            java.util.Arrays.fill(px, py * WIDTH + x0, py * WIDTH + x1, colour);
        }
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }
}
