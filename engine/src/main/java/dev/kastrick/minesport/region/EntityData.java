package dev.kastrick.minesport.region;

import dev.kastrick.minesport.nbt.NbtCompound;

/** Raw entity NBT paired with its world-space position. */
public record EntityData(double x, double y, double z, NbtCompound nbt) {}
