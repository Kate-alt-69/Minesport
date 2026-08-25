package dev.kastrick.minesport.region;

/** Resolved Minecraft scheduled tick with a world-space position and relative delay. */
public record ScheduledTickData(
    int x, int y, int z,
    String id,
    int delay,
    int priority
) {}
