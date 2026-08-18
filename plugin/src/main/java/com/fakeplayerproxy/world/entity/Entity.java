package com.fakeplayerproxy.world.entity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

import com.fakeplayerproxy.world.data.Block;
import com.fakeplayerproxy.world.world.World;
import com.fakeplayerproxy.world.data.EntityTypeData;
import com.fakeplayerproxy.world.phys.AABB;
import com.fakeplayerproxy.world.phys.CollisionPhysics;
import com.fakeplayerproxy.world.player.Player;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.cloudburstmc.math.vector.Vector3d;
import org.geysermc.mcprotocollib.protocol.data.game.entity.MinecartStep;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.Pose;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement;
import org.jetbrains.annotations.NotNull;

/** Base client entity state and collision/relationship behavior. */
@Accessors(fluent = true)
public class Entity {
    private static final double COLLISION_EPSILON = 1.0E-7;
    private static final double VELOCITY_EPSILON = 0.003;

    @Getter
    @Setter
    private int id;
    @Getter(AccessLevel.PROTECTED)
    private final EntityTypeData definition;
    private final List<Entity> passengers = new ArrayList<>();
    private final Deque<MinecartStep> minecartSteps = new ArrayDeque<>();
    @Getter
    private Vector3d position;
    private Vector3d codecPosition;
    @Getter
    private Vector3d velocity;
    @Getter
    private float yaw;
    @Getter
    private float pitch;
    @Getter
    private Pose pose;
    @Getter
    private boolean onGround;
    @Getter
    private boolean horizontalCollision;
    private double waterHeight;
    @Getter
    private double lavaHeight;
    @Getter
    private boolean fallFlying;
    @Getter
    private boolean swimming;
    @Getter
    private boolean descending;
    @Getter
    @Setter
    private boolean noGravity;
    @Getter
    private double fallDistance;
    @Getter
    private Entity vehicle;
    @Getter
    private final Vehicle clientVehicle;
    private Vector3d interpolationTarget;
    private float interpolationYaw;
    private float interpolationPitch;
    private int interpolationTicks;
    private Vector3d stuckSpeedMultiplier = Vector3d.ZERO;
    private Vector3d pistonMovement = Vector3d.ZERO;

    public Entity(
            int id,
            @NotNull EntityTypeData definition,
            @NotNull Vector3d position,
            @NotNull Vector3d velocity,
            float yaw,
            float pitch) {
        this.id = id;
        this.definition = definition;
        this.position = position;
        this.codecPosition = position;
        this.velocity = velocity;
        this.yaw = yaw;
        this.pitch = pitch;
        this.pose = definition.defaultPose();
        this.clientVehicle = definition.vehicle() == null ? null : new Vehicle(this, definition.vehicle());
        setSharedFlags(definition.defaultSharedFlags());
        this.noGravity = definition.defaultNoGravity();
    }

    public boolean inWater() {
        return waterHeight > 0.0;
    }

    public boolean inLava() {
        return lavaHeight > 0.0;
    }

    public List<Entity> passengers() {
        return Collections.unmodifiableList(passengers);
    }

    public AABB boundingBox() {
        return boundingBox(pose);
    }

    public AABB boundingBox(Pose targetPose) {
        double scale = effectiveScale();
        EntityTypeData.PoseData dimensions = definition.poses().get(targetPose.ordinal());
        double width = dimensions.width() * scale;
        double height = dimensions.height() * scale;
        double radius = width / 2.0;
        return new AABB(
                position.getX() - radius, position.getY(), position.getZ() - radius,
                position.getX() + radius, position.getY() + height, position.getZ() + radius);
    }

    public double effectiveScale() {
        return definition.scale();
    }

    public double defaultStepHeight() {
        return definition.stepHeight();
    }

    public boolean hasMovementCollision() {
        return definition.movementCollision();
    }

    public boolean isPushable() {
        return definition.pushable();
    }

    public boolean isPickable() {
        return definition.pickable();
    }

    public float pickRadius() {
        return definition.pickRadius();
    }

    public boolean ignoresPistonMovement() {
        return !definition.affectedByPiston();
    }

    public boolean isMinecart() {
        return definition.movementKind() == EntityTypeData.MovementKind.MINECART;
    }

    public boolean isBoat() {
        return clientVehicle != null
                && clientVehicle.movementKind() == EntityTypeData.MovementKind.BOAT;
    }

    public boolean isCarpetRideable() {
        return switch (definition.movementKind()) {
            case MINECART, BOAT, HORSE, CAMEL -> true;
            default -> false;
        };
    }

    final EntityTypeData.MovementKind movementKind() {
        return definition.movementKind();
    }

    public void resetMetadataDefaults() {
        setSharedFlags(definition.defaultSharedFlags());
        noGravity(definition.defaultNoGravity());
        setPose(definition.defaultPose());
    }

    public double eyeHeight() {
        return poseDefinition().eyeHeight() * effectiveScale();
    }

    public EntityTypeData.PoseData poseDefinition() {
        return definition.poses().get(pose.ordinal());
    }

    public void setPosition(@NotNull Vector3d position) {
        this.position = position;
    }

    public void setVelocity(@NotNull Vector3d velocity) {
        this.velocity = velocity;
    }

    public void addVelocity(Vector3d delta) {
        velocity = velocity.add(delta);
    }

    public void setRotation(float yaw, float pitch) {
        this.yaw = yaw;
        this.pitch = pitch;
    }

    public void setPose(@NotNull Pose pose) {
        this.pose = pose;
    }

    public void setSharedFlags(byte flags) {
        descending = (flags & 1 << 1) != 0;
        swimming = (flags & 1 << 4) != 0;
        fallFlying = (flags & 1 << 7) != 0;
    }

    public void applyMetadata(int metadataId, Object value) {
        if (metadataId == definition.sharedFlagsMetadataId() && value instanceof Byte flags) {
            setSharedFlags(flags);
        } else if (metadataId == definition.noGravityMetadataId() && value instanceof Boolean flag) {
            noGravity(flag);
        } else if (metadataId == definition.poseMetadataId() && value instanceof Pose nextPose) {
            setPose(nextPose);
        } else if (metadataId == definition.healthMetadataId() && value instanceof Float nextHealth
                && this instanceof LivingEntity livingEntity) {
            livingEntity.setHealth(nextHealth);
        } else if (clientVehicle != null) {
            clientVehicle.applyMetadata(metadataId, value);
        }
    }

    public void setCollisionFlags(boolean onGround, boolean horizontalCollision) {
        this.onGround = onGround;
        this.horizontalCollision = horizontalCollision;
    }

    public void sync(Vector3d position, Vector3d velocity, float yaw, float pitch) {
        setPosition(position);
        codecPosition = position;
        setVelocity(velocity);
        setRotation(yaw, pitch);
        interpolationTicks = 0;
    }

    public void interpolate(
            Vector3d movement,
            boolean positionChanged,
            float targetYaw,
            float targetPitch,
            boolean rotationChanged,
            boolean localAuthoritative) {
        if (positionChanged) {
            codecPosition = codecPosition.add(movement);
        }
        if (localAuthoritative) {
            return;
        }
        boolean pending = interpolationTicks > 0 && interpolationTarget != null;
        interpolationTarget = positionChanged ? codecPosition : pending ? interpolationTarget : position;
        interpolationYaw = rotationChanged ? targetYaw : pending ? interpolationYaw : yaw;
        interpolationPitch = rotationChanged ? targetPitch : pending ? interpolationPitch : pitch;
        interpolationTicks = 3;
    }

    public void positionSync(Vector3d target, float targetYaw, float targetPitch,
                             boolean targetOnGround, boolean localAuthoritative) {
        codecPosition = target;
        if (localAuthoritative) {
            return;
        }
        setPosition(target);
        setRotation(targetYaw, targetPitch);
        setCollisionFlags(targetOnGround, horizontalCollision);
        interpolationTarget = null;
        interpolationTicks = 0;
    }

    public void teleport(
            Vector3d packetPosition,
            Vector3d packetMovement,
            float packetYaw,
            float packetPitch,
            List<PositionElement> relatives,
            boolean targetOnGround) {
        Vector3d oldPosition = position();
        Vector3d oldVelocity = velocity();
        float oldYaw = yaw();
        float oldPitch = pitch();
        Vector3d targetPosition = Vector3d.from(
                relatives.contains(PositionElement.X)
                        ? oldPosition.getX() + packetPosition.getX() : packetPosition.getX(),
                relatives.contains(PositionElement.Y)
                        ? oldPosition.getY() + packetPosition.getY() : packetPosition.getY(),
                relatives.contains(PositionElement.Z)
                        ? oldPosition.getZ() + packetPosition.getZ() : packetPosition.getZ());
        float targetYaw = relatives.contains(PositionElement.Y_ROT) ? oldYaw + packetYaw : packetYaw;
        float targetPitch = relatives.contains(PositionElement.X_ROT) ? oldPitch + packetPitch : packetPitch;
        Vector3d relativeVelocity = relatives.contains(PositionElement.ROTATE_DELTA)
                ? CollisionPhysics.rotateDelta(
                oldVelocity, oldYaw, oldPitch, targetYaw, targetPitch)
                : oldVelocity;
        Vector3d targetVelocity = Vector3d.from(
                relatives.contains(PositionElement.DELTA_X)
                        ? relativeVelocity.getX() + packetMovement.getX() : packetMovement.getX(),
                relatives.contains(PositionElement.DELTA_Y)
                        ? relativeVelocity.getY() + packetMovement.getY() : packetMovement.getY(),
                relatives.contains(PositionElement.DELTA_Z)
                        ? relativeVelocity.getZ() + packetMovement.getZ() : packetMovement.getZ());
        sync(targetPosition, targetVelocity, targetYaw, targetPitch);
        setCollisionFlags(targetOnGround, horizontalCollision);
    }

    public void tickInterpolation() {
        if (definition.movementKind() == EntityTypeData.MovementKind.MINECART && !minecartSteps.isEmpty()) {
            MinecartStep step = minecartSteps.removeFirst();
            sync(step.position(), step.movement(), step.yRot(), step.xRot());
            return;
        }
        if (interpolationTicks <= 0 || interpolationTarget == null) {
            return;
        }
        double factor = 1.0 / interpolationTicks;
        position = Vector3d.from(
                position.getX() + (interpolationTarget.getX() - position.getX()) * factor,
                position.getY() + (interpolationTarget.getY() - position.getY()) * factor,
                position.getZ() + (interpolationTarget.getZ() - position.getZ()) * factor);
        yaw += (float) ((interpolationYaw - yaw) * factor);
        pitch += (float) ((interpolationPitch - pitch) * factor);
        interpolationTicks--;
    }

    public void queueMinecartSteps(List<MinecartStep> steps) {
        minecartSteps.addAll(steps);
    }

    public void baseTick(World world) {
        World.FluidSample fluid = world.fluid(this, boundingBox());
        waterHeight = fluid.waterHeight();
        lavaHeight = fluid.lavaHeight();
        if (isPushedByFluid()) {
            addVelocity(fluid.flow());
        }
    }

    public void move(World world, Vector3d requested, double stepHeight) {
        if (noPhysics()) {
            position = position.add(requested);
            onGround = false;
            horizontalCollision = false;
            return;
        }
        boolean stuck = stuckSpeedMultiplier.lengthSquared() > COLLISION_EPSILON;
        if (stuck) {
            requested = requested.mul(stuckSpeedMultiplier);
            stuckSpeedMultiplier = Vector3d.ZERO;
        }
        Vector3d movement = Vector3d.from(
                threshold(requested.getX()), threshold(requested.getY()), threshold(requested.getZ()));
        AABB box = boundingBox();
        List<AABB> obstacles = world.collisions(this,
                box.expand(movement.getX(), movement.getY(), movement.getZ())
                        .expand(0.0, stepHeight, 0.0)
                        .inflate(COLLISION_EPSILON));
        Vector3d base = CollisionPhysics.collide(box, movement, obstacles);
        boolean blockedHorizontally = movement.getX() != base.getX() || movement.getZ() != base.getZ();
        boolean canStep = blockedHorizontally
                && (onGround || movement.getY() < 0.0 && movement.getY() != base.getY());
        Vector3d applied = base;
        if (canStep && stepHeight > 0.0) {
            Vector3d upward = CollisionPhysics.collide(
                    box, Vector3d.from(movement.getX(), stepHeight, movement.getZ()), obstacles);
            AABB steppedBox = box.move(upward.getX(), upward.getY(), upward.getZ());
            double downward = CollisionPhysics.collideY(
                    steppedBox, movement.getY() - upward.getY(), obstacles);
            Vector3d stepped = Vector3d.from(upward.getX(), upward.getY() + downward, upward.getZ());
            if (horizontalDistanceSquared(stepped) > horizontalDistanceSquared(base)) {
                applied = stepped;
            }
        }

        position = position.add(applied);
        horizontalCollision = movement.getX() != applied.getX() || movement.getZ() != applied.getZ();
        boolean verticalCollision = movement.getY() != applied.getY();
        onGround = verticalCollision && movement.getY() < 0.0;
        if (inWater()) {
            fallDistance = 0.0;
        } else if (movement.getY() < 0.0) {
            fallDistance -= movement.getY();
        }
        if (onGround) {
            fallDistance = 0.0;
        }
        velocity = stuck ? Vector3d.ZERO : Vector3d.from(
                movement.getX() == applied.getX() ? movement.getX() : 0.0,
                movement.getY() == applied.getY() ? movement.getY() : 0.0,
                movement.getZ() == applied.getZ() ? movement.getZ() : 0.0);
        applyInsideBlockEffects(world);
        applyStepOn(world);
        float speedFactor = world.speedFactor(this);
        velocity = Vector3d.from(velocity.getX() * speedFactor, velocity.getY(),
                velocity.getZ() * speedFactor);
        if (verticalCollision && movement.getY() < 0.0) {
            double restitution = Math.max(entityBounciness(), world.blockBelow(this).bounciness());
            if (-movement.getY() >= effectiveGravity() && restitution > 0.0) {
                velocity = Vector3d.from(velocity.getX(), -movement.getY() * restitution,
                        velocity.getZ());
            }
        }
    }

    public void resetPistonMovement() {
        pistonMovement = Vector3d.ZERO;
    }

    public void moveByPiston(World world, Vector3d requested) {
        Vector3d limited = Vector3d.from(
                limitPistonAxis(requested.getX(), pistonMovement.getX()),
                limitPistonAxis(requested.getY(), pistonMovement.getY()),
                limitPistonAxis(requested.getZ(), pistonMovement.getZ()));
        pistonMovement = pistonMovement.add(limited);
        if (limited.lengthSquared() <= COLLISION_EPSILON) {
            return;
        }
        Vector3d retainedVelocity = velocity;
        move(world, limited, 0.0);
        velocity = retainedVelocity;
    }

    private static double limitPistonAxis(double requested, double accumulated) {
        if (requested == 0.0) {
            return 0.0;
        }
        double total = Math.clamp(requested + accumulated, -0.51, 0.51);
        double applied = total - accumulated;
        return Math.abs(applied) <= 1.0E-5 ? 0.0 : applied;
    }

    public double entityBounciness() {
        return 0.0;
    }

    public double effectiveGravity() {
        return 0.0;
    }

    public boolean canWalkOnPowderSnow() {
        return false;
    }

    public boolean noPhysics() {
        return false;
    }

    public boolean isPushedByFluid() {
        return true;
    }

    public boolean sharesVehicleRoot(Entity other) {
        Entity thisRoot = this;
        while (thisRoot.vehicle != null) {
            thisRoot = thisRoot.vehicle;
        }
        Entity otherRoot = other;
        while (otherRoot.vehicle != null) {
            otherRoot = otherRoot.vehicle;
        }
        return thisRoot == otherRoot && (thisRoot != this || otherRoot != other);
    }

    public boolean isControlledBy(Player player) {
        return clientVehicle != null && clientVehicle.isControlledBy(player);
    }

    public void tickVehicle(World world) {
        if (clientVehicle != null) {
            clientVehicle.tick(world);
        }
    }

    public void releaseLocalControl() {
        if (clientVehicle != null) {
            clientVehicle.releaseLocalControl();
        }
    }

    public void attachPassenger(Entity passenger) {
        if (!passengers.contains(passenger)) {
            passengers.add(passenger);
        }
        passenger.vehicle = this;
    }

    public void detachPassenger(Entity passenger) {
        if (passengers.remove(passenger) && passenger.vehicle == this) {
            passenger.vehicle = null;
            releaseLocalControl();
        }
    }

    public void placePassengerTree() {
        List<Vector3d> attachments = poseDefinition().passengerAttachments();
        for (int index = 0; index < passengers.size(); index++) {
            Entity passenger = passengers.get(index);
            Vector3d vehiclePoint = clientVehicle == null
                    ? (attachments.isEmpty() ? Vector3d.ZERO
                        : attachments.get(Math.min(index, attachments.size() - 1)))
                        .mul(effectiveScale())
                    : clientVehicle.passengerAttachment(index, passengers.size());
            Vector3d passengerPoint = passenger.poseDefinition().vehicleAttachment()
                    .mul(passenger.effectiveScale());
            Vector3d offset = rotateY(vehiclePoint, -yaw)
                    .sub(rotateY(passengerPoint, -passenger.yaw));
            passenger.position = position.add(offset);
            passenger.onGround = onGround;
            passenger.horizontalCollision = horizontalCollision;
            passenger.placePassengerTree();
        }
    }

    private static Vector3d rotateY(Vector3d vector, float yaw) {
        double radians = Math.toRadians(yaw);
        double sin = Math.sin(radians);
        double cos = Math.cos(radians);
        return Vector3d.from(
                vector.getX() * cos - vector.getZ() * sin,
                vector.getY(),
                vector.getZ() * cos + vector.getX() * sin);
    }

    private void applyInsideBlockEffects(World world) {
        boolean bubbleApplied = false;
        for (World.BlockSample sample : world.blocksInside(this)) {
            switch (sample.block().behavior()) {
                case BUBBLE_COLUMN -> {
                    if (!bubbleApplied) {
                        applyBubbleColumn(world, sample);
                        bubbleApplied = true;
                    }
                }
                case COBWEB -> makeStuck(cobwebStuckMultiplier());
                case BERRY_BUSH -> {
                    if (this instanceof LivingEntity) {
                        makeStuck(Vector3d.from(0.8, 0.75, 0.8));
                    }
                }
                case POWDER_SNOW -> {
                    if (!canWalkOnPowderSnow() && (!(this instanceof LivingEntity)
                            || floor(position.getX()) == sample.x()
                            && floor(position.getY()) == sample.y()
                            && floor(position.getZ()) == sample.z())) {
                        makeStuck(Vector3d.from(0.9, 1.5, 0.9));
                    }
                }
                case HONEY -> applyHoneySlide(sample);
                case NONE, SLIME, BED, SOUL_SAND, CLIMBABLE, SCAFFOLDING -> {
                    // These kinds are handled by collision, step-on, speed, or travel.
                }
            }
        }
    }

    private void applyStepOn(World world) {
        if (!onGround || world.blockBelow(this).behavior() != Block.Behavior.SLIME
                || pose == Pose.SNEAKING) {
            return;
        }
        double absoluteY = Math.abs(velocity.getY());
        if (absoluteY < 0.1) {
            double scale = 0.4 + absoluteY * 0.2;
            velocity = Vector3d.from(velocity.getX() * scale, velocity.getY(),
                    velocity.getZ() * scale);
        }
    }

    private void applyBubbleColumn(World world, World.BlockSample sample) {
        double y = velocity.getY();
        boolean dragDown = sample.block().behaviorParameter() != 0;
        if (world.bubbleColumnHasOpenSurface(sample)) {
            y = dragDown ? Math.max(-0.9, y - 0.03) : Math.min(1.8, y + 0.1);
        } else {
            y = dragDown ? Math.max(-0.3, y - 0.03) : Math.min(0.7, y + 0.06);
        }
        velocity = Vector3d.from(velocity.getX(), y, velocity.getZ());
    }

    private void applyHoneySlide(World.BlockSample sample) {
        if (onGround || position.getY() > sample.y() + 0.9375 - COLLISION_EPSILON) {
            return;
        }
        double oldY = velocity.getY() / 0.98f + 0.08;
        if (oldY >= -0.08) {
            return;
        }
        double width = boundingBox().maxX() - boundingBox().minX();
        double overlap = 0.4375 + width / 2.0;
        if (Math.abs(sample.x() + 0.5 - position.getX()) + COLLISION_EPSILON <= overlap
                && Math.abs(sample.z() + 0.5 - position.getZ()) + COLLISION_EPSILON <= overlap) {
            return;
        }
        double factor = oldY < -0.13 ? -0.05 / oldY : 1.0;
        velocity = Vector3d.from(
                velocity.getX() * factor, (-0.05 - 0.08) * 0.98f,
                velocity.getZ() * factor);
    }

    private void makeStuck(Vector3d multiplier) {
        fallDistance = 0.0;
        stuckSpeedMultiplier = stuckSpeedMultiplier == Vector3d.ZERO
                ? multiplier : Vector3d.from(
                Math.min(stuckSpeedMultiplier.getX(), multiplier.getX()),
                Math.min(stuckSpeedMultiplier.getY(), multiplier.getY()),
                Math.min(stuckSpeedMultiplier.getZ(), multiplier.getZ()));
    }

    public Vector3d cobwebStuckMultiplier() {
        return Vector3d.from(0.25, 0.05, 0.25);
    }

    private static double threshold(double value) {
        return Math.abs(value) < VELOCITY_EPSILON ? 0.0 : value;
    }

    private static double horizontalDistanceSquared(Vector3d vector) {
        return vector.getX() * vector.getX() + vector.getZ() * vector.getZ();
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

}
