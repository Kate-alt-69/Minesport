package dev.kastrick.minesport.region;

import dev.kastrick.minesport.nbt.NbtCompound;

/** Raw block-entity NBT paired with its world-space block position. */
public record BlockEntityData(int x, int y, int z, NbtCompound nbt) {}
