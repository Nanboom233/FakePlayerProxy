package com.fakeplayerproxy.world.data;

import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;

/** Immutable Minecraft 26.2 data for one block state. */
public record Block(
        String stateKey,
        int collisionShapeId,
        int outlineShapeId,
        int blockId,
        float friction,
        float speedFactor,
        float bounciness,
        boolean air,
        boolean water,
        boolean lava,
        int fluidAmount,
        boolean fluidFalling,
        Behavior behavior,
        int behaviorParameter,
        int fluidFaceMask,
        float destroySpeed,
        boolean requiresCorrectToolForDrops) {

    public boolean hasFluidFace(Direction direction) {
        return (fluidFaceMask & 1 << direction.ordinal()) != 0;
    }

    public enum Behavior {
        NONE,
        BUBBLE_COLUMN,
        COBWEB,
        BERRY_BUSH,
        POWDER_SNOW,
        HONEY,
        SLIME,
        BED,
        SOUL_SAND,
        CLIMBABLE,
        SCAFFOLDING
    }
}
