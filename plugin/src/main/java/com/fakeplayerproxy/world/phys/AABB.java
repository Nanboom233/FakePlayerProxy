package com.fakeplayerproxy.world.phys;

import org.cloudburstmc.math.vector.Vector3d;
import java.util.Optional;
import it.unimi.dsi.fastutil.Pair;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;

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

    public AABB inflate(double x, double y, double z) {
        return new AABB(minX - x, minY - y, minZ - z, maxX + x, maxY + y, maxZ + z);
    }

    public Vector3d closestPoint(Vector3d point) {
        return Vector3d.from(
                Math.clamp(point.getX(), minX, maxX),
                Math.clamp(point.getY(), minY, maxY),
                Math.clamp(point.getZ(), minZ, maxZ));
    }

    public Vector3d center() {
        return Vector3d.from(
                (minX + maxX) * 0.5,
                (minY + maxY) * 0.5,
                (minZ + maxZ) * 0.5);
    }

    public boolean contains(Vector3d point) {
        return point.getX() >= minX && point.getX() <= maxX
                && point.getY() >= minY && point.getY() <= maxY
                && point.getZ() >= minZ && point.getZ() <= maxZ;
    }

    public Optional<Pair<Vector3d, Direction>> clip(Vector3d start, Vector3d end) {
        double[] minimum = {minX, minY, minZ};
        double[] maximum = {maxX, maxY, maxZ};
        double[] origin = {start.getX(), start.getY(), start.getZ()};
        double[] delta = {
                end.getX() - start.getX(), end.getY() - start.getY(), end.getZ() - start.getZ()
        };
        Direction[][] faces = {
                {Direction.WEST, Direction.EAST},
                {Direction.DOWN, Direction.UP},
                {Direction.NORTH, Direction.SOUTH}
        };
        double near = 0.0;
        double far = 1.0;
        Direction face = null;
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(delta[axis]) < 1.0E-12) {
                if (origin[axis] < minimum[axis] || origin[axis] > maximum[axis]) {
                    return Optional.empty();
                }
                continue;
            }
            double first = (minimum[axis] - origin[axis]) / delta[axis];
            double second = (maximum[axis] - origin[axis]) / delta[axis];
            Direction firstFace = faces[axis][0];
            if (first > second) {
                double swap = first;
                first = second;
                second = swap;
                firstFace = faces[axis][1];
            }
            if (first > near) {
                near = first;
                face = firstFace;
            }
            far = Math.min(far, second);
            if (near > far) {
                return Optional.empty();
            }
        }
        if (near > 1.0) {
            return Optional.empty();
        }
        if (face == null) {
            face = dominantOpposite(delta);
        }
        return Optional.of(Pair.of(start.add(end.sub(start).mul(near)), face));
    }

    private static Direction dominantOpposite(double[] delta) {
        double x = Math.abs(delta[0]);
        double y = Math.abs(delta[1]);
        double z = Math.abs(delta[2]);
        if (x >= y && x >= z) {
            return delta[0] > 0.0 ? Direction.WEST : Direction.EAST;
        }
        if (y >= z) {
            return delta[1] > 0.0 ? Direction.DOWN : Direction.UP;
        }
        return delta[2] > 0.0 ? Direction.NORTH : Direction.SOUTH;
    }

    public boolean intersects(AABB other) {
        return maxX > other.minX && minX < other.maxX
                && maxY > other.minY && minY < other.maxY
                && maxZ > other.minZ && minZ < other.maxZ;
    }
}
