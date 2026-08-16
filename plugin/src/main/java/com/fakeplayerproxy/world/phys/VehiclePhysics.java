package com.fakeplayerproxy.world.phys;

import org.cloudburstmc.math.vector.Vector3d;

/** Stateless zero-input vehicle velocity formulas. */
public final class VehiclePhysics {
    private VehiclePhysics() {
    }

    public static Vector3d boatVelocity(
            Vector3d velocity, double verticalSpeed, double buoyancy, float friction) {
        Vector3d result = Vector3d.from(
                velocity.getX() * friction,
                velocity.getY() + verticalSpeed,
                velocity.getZ() * friction);
        if (buoyancy <= 0.0) {
            return result;
        }
        return Vector3d.from(
                result.getX(),
                (result.getY() + buoyancy * (0.04 / 0.65)) * 0.75,
                result.getZ());
    }
}
