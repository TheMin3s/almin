package com.schecks.almin;

/** One sampled position in a player path sent to the activity viewer. */
public record PlayerTrackPoint(long at, String dim, int x, int y, int z) {}
