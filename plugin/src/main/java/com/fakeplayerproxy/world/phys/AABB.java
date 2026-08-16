package com.fakeplayerproxy.world.phys;

/** Immutable axis-aligned box in world coordinates. */
public record AABB(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    public AABB move(double x, double y, double z) {
        return new AABB(minX + x, minY + y, minZ + z, maxX + x, maxY + y, maxZ + z);
    }

    public AABB expand(double x, double y, double z) {
        return new AABB(
                x < 0.0 ? minX + x : minX,
                y < 0.0 ? minY + y : minY,
                z < 0.0 ? minZ + z : minZ,
                x > 0.0 ? maxX + x : maxX,
                y > 0.0 ? maxY + y : maxY,
                z > 0.0 ? maxZ + z : maxZ);
    }

    public AABB inflate(double value) {
        return new AABB(
                minX - value,
                minY - value,
                minZ - value,
                maxX + value,
                maxY + value,
                maxZ + value);
    }

    public boolean intersects(AABB other) {
        return maxX > other.minX && minX < other.maxX
                && maxY > other.minY && minY < other.maxY
                && maxZ > other.minZ && minZ < other.maxZ;
    }
}
