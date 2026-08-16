package com.fakeplayerproxy.world.phys;

import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;

/** Stateless swept-volume and penetration formulas for piston movement. */
public final class PistonPhysics {
    private PistonPhysics() {
    }

    public static AABB movementArea(AABB box, Direction direction, double amount) {
        double signed = switch (direction) {
            case DOWN, NORTH, WEST -> -amount;
            case UP, SOUTH, EAST -> amount;
        };
        double min = Math.min(signed, 0.0);
        double max = Math.max(signed, 0.0);
        return switch (direction) {
            case WEST -> new AABB(box.minX() + min, box.minY(), box.minZ(),
                    box.minX() + max, box.maxY(), box.maxZ());
            case EAST -> new AABB(box.maxX() + min, box.minY(), box.minZ(),
                    box.maxX() + max, box.maxY(), box.maxZ());
            case DOWN -> new AABB(box.minX(), box.minY() + min, box.minZ(),
                    box.maxX(), box.minY() + max, box.maxZ());
            case UP -> new AABB(box.minX(), box.maxY() + min, box.minZ(),
                    box.maxX(), box.maxY() + max, box.maxZ());
            case NORTH -> new AABB(box.minX(), box.minY(), box.minZ() + min,
                    box.maxX(), box.maxY(), box.minZ() + max);
            case SOUTH -> new AABB(box.minX(), box.minY(), box.maxZ() + min,
                    box.maxX(), box.maxY(), box.maxZ() + max);
        };
    }

    public static double penetration(AABB moving, Direction direction, AABB entity) {
        return switch (direction) {
            case EAST -> moving.maxX() - entity.minX();
            case WEST -> entity.maxX() - moving.minX();
            case UP -> moving.maxY() - entity.minY();
            case DOWN -> entity.maxY() - moving.minY();
            case SOUTH -> moving.maxZ() - entity.minZ();
            case NORTH -> entity.maxZ() - moving.minZ();
        };
    }
}
