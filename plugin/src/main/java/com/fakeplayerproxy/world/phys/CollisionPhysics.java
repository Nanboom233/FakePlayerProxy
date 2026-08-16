package com.fakeplayerproxy.world.phys;

import java.util.List;

import org.cloudburstmc.math.vector.Vector3d;

/** Stateless axis clipping used by entity movement and step candidates. */
public final class CollisionPhysics {
    private CollisionPhysics() {
    }

    public static Vector3d collide(AABB box, Vector3d requested, List<AABB> obstacles) {
        double y = collideY(box, requested.getY(), obstacles);
        if (y != 0.0) {
            box = box.move(0.0, y, 0.0);
        }
        double x = requested.getX();
        double z = requested.getZ();
        if (Math.abs(x) < Math.abs(z)) {
            z = collideZ(box, z, obstacles);
            if (z != 0.0) {
                box = box.move(0.0, 0.0, z);
            }
            x = collideX(box, x, obstacles);
        } else {
            x = collideX(box, x, obstacles);
            if (x != 0.0) {
                box = box.move(x, 0.0, 0.0);
            }
            z = collideZ(box, z, obstacles);
        }
        return Vector3d.from(x, y, z);
    }

    public static double collideY(AABB box, double movement, List<AABB> obstacles) {
        for (AABB obstacle : obstacles) {
            if (box.maxX() > obstacle.minX() && box.minX() < obstacle.maxX()
                    && box.maxZ() > obstacle.minZ() && box.minZ() < obstacle.maxZ()) {
                if (movement > 0.0 && box.maxY() <= obstacle.minY()) {
                    movement = Math.min(movement, obstacle.minY() - box.maxY());
                } else if (movement < 0.0 && box.minY() >= obstacle.maxY()) {
                    movement = Math.max(movement, obstacle.maxY() - box.minY());
                }
            }
        }
        return movement;
    }

    public static Vector3d rotateDelta(
            Vector3d movement, float oldYaw, float oldPitch, float newYaw, float newPitch) {
        double pitch = Math.toRadians(oldPitch - newPitch);
        double pitchCos = Math.cos(pitch);
        double pitchSin = Math.sin(pitch);
        double x = movement.getX();
        double y = movement.getY() * pitchCos + movement.getZ() * pitchSin;
        double z = movement.getZ() * pitchCos - movement.getY() * pitchSin;
        double yaw = Math.toRadians(oldYaw - newYaw);
        double yawCos = Math.cos(yaw);
        double yawSin = Math.sin(yaw);
        return Vector3d.from(x * yawCos + z * yawSin, y, z * yawCos - x * yawSin);
    }

    private static double collideX(AABB box, double movement, List<AABB> obstacles) {
        for (AABB obstacle : obstacles) {
            if (box.maxY() > obstacle.minY() && box.minY() < obstacle.maxY()
                    && box.maxZ() > obstacle.minZ() && box.minZ() < obstacle.maxZ()) {
                if (movement > 0.0 && box.maxX() <= obstacle.minX()) {
                    movement = Math.min(movement, obstacle.minX() - box.maxX());
                } else if (movement < 0.0 && box.minX() >= obstacle.maxX()) {
                    movement = Math.max(movement, obstacle.maxX() - box.minX());
                }
            }
        }
        return movement;
    }

    private static double collideZ(AABB box, double movement, List<AABB> obstacles) {
        for (AABB obstacle : obstacles) {
            if (box.maxX() > obstacle.minX() && box.minX() < obstacle.maxX()
                    && box.maxY() > obstacle.minY() && box.minY() < obstacle.maxY()) {
                if (movement > 0.0 && box.maxZ() <= obstacle.minZ()) {
                    movement = Math.min(movement, obstacle.minZ() - box.maxZ());
                } else if (movement < 0.0 && box.minZ() >= obstacle.maxZ()) {
                    movement = Math.max(movement, obstacle.maxZ() - box.minZ());
                }
            }
        }
        return movement;
    }
}
