package com.schecks.almin;

/**
 * One recorded player action. This wire model is deliberately separate from
 * {@link ActivityLog}, so the optional admin client does not package or load
 * any of the server's activity-storage implementation.
 */
public record ActivityEntry(long at, String player, String uuid, String action,
                            String detail, String dim, int x, int y, int z, int count) {
    /** "overworld 1,2,3", for anywhere that shows a line rather than a map. */
    public String where() {
        if (dim == null || dim.isEmpty()) return "";
        return dim + " " + x + "," + y + "," + z;
    }
}
