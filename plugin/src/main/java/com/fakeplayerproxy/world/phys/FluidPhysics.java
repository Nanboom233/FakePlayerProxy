package com.fakeplayerproxy.world.phys;

import org.cloudburstmc.math.vector.Vector3d;

/** Stateless fluid-current accumulation and minimum-impulse rules. */
public final class FluidPhysics {
    private FluidPhysics() {
    }

    public static Vector3d current(
            Vector3d accumulatedFlow,
            int currentCount,
            boolean averageFlow,
            double scale,
            Vector3d oldVelocity) {
        if (currentCount == 0 || accumulatedFlow.lengthSquared() < 1.0E-5) {
            return Vector3d.ZERO;
        }
        Vector3d impulse = averageFlow
                ? accumulatedFlow.mul(1.0 / currentCount)
                : accumulatedFlow.normalize();
        impulse = impulse.mul(scale);
        if (Math.abs(oldVelocity.getX()) < 0.003 && Math.abs(oldVelocity.getZ()) < 0.003
                && impulse.length() < 0.0045) {
            impulse = impulse.normalize().mul(0.0045);
        }
        return impulse;
    }
}
