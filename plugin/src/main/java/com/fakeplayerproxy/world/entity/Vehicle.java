package com.fakeplayerproxy.world.entity;

import com.fakeplayerproxy.world.data.EntityTypeData;
import com.fakeplayerproxy.world.phys.VehiclePhysics;
import com.fakeplayerproxy.world.player.Player;
import com.fakeplayerproxy.world.world.World;

import org.cloudburstmc.math.vector.Vector3d;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.Pose;

/** Runtime metadata, control, movement, and seat state for one client-simulated vehicle. */
public final class Vehicle {
    private final Entity owner;
    private final EntityTypeData.VehicleData data;
    private byte horseFlags;
    private int steeringBoostDuration;
    private int steeringBoostRevision;
    private boolean striderSuffocating;
    private long camelLastPoseChangeTick;
    private boolean happyGhastStaysStill;
    private int boostRevision;
    private int boostDuration;
    private int boostElapsed;
    private boolean boosting;
    private BoatStatus boatStatus = BoatStatus.IN_AIR;
    private BoatStatus oldBoatStatus = BoatStatus.IN_AIR;
    private double waterLevel = -Double.MAX_VALUE;
    private float landFriction;

    Vehicle(Entity owner, EntityTypeData.VehicleData data) {
        this.owner = owner;
        this.data = data;
    }

    public EntityTypeData.MovementKind movementKind() {
        return owner.movementKind();
    }

    public void applyMetadata(int metadataId, Object value) {
        switch (value) {
            case Byte flags when metadataId == data.horseFlagsMetadataId() ->
                    horseFlags = flags;
            case Integer duration when metadataId == data.steeringBoostMetadataId() -> {
                steeringBoostDuration = Math.max(0, duration);
                steeringBoostRevision++;
            }
            case Boolean flag when metadataId == data.striderSuffocatingMetadataId() ->
                    striderSuffocating = flag;
            case Long tick when metadataId == data.camelLastPoseChangeTickMetadataId() ->
                    camelLastPoseChangeTick = tick;
            case Boolean flag when metadataId == data.happyGhastStaysStillMetadataId() ->
                    happyGhastStaysStill = flag;
            default -> {
                // This metadata field is not read by client vehicle movement.
            }
        }
    }

    public boolean isControlledBy(Player player) {
        return !owner.passengers().isEmpty()
                && owner.passengers().getFirst() == player
                && !happyGhastStaysStill
                && (!(owner instanceof LivingEntity livingEntity)
                || livingEntity.canControl(movementKind(), player));
    }

    public void tick(World world) {
        acceptBoost();
        if (movementKind() == EntityTypeData.MovementKind.BOAT) {
            World.BoatEnvironment environment = world.boatEnvironment(owner);
            oldBoatStatus = boatStatus;
            boatStatus = environment.status();
            waterLevel = environment.waterLevel();
            if (boatStatus == BoatStatus.ON_LAND) {
                landFriction = environment.landFriction();
            }
        }
        owner.baseTick(world);
        Player controller = !owner.passengers().isEmpty()
                && owner.passengers().getFirst() instanceof Player player ? player : null;
        switch (movementKind()) {
            case BOAT -> {
                if (controller != null) {
                    floatBoat(world);
                    owner.move(world, owner.velocity(), 0.0);
                } else {
                    owner.setVelocity(Vector3d.ZERO);
                }
            }
            case HORSE, CAMEL, PIG, STRIDER -> tickGroundVehicle(world, controller);
            case HAPPY_GHAST, NAUTILUS -> tickFlyingVehicle(world, controller);
            case SERVER, MINECART -> throw new IllegalStateException(
                    "A runtime Vehicle cannot use server movement");
        }
    }

    private void tickGroundVehicle(World world, Player controller) {
        if (controller != null) {
            owner.setRotation(controller.yaw(), controller.pitch() * 0.5f);
        }
        double stepHeight = owner instanceof LivingEntity livingEntity
                ? livingEntity.effectiveStepHeight() : owner.defaultStepHeight();
        double gravity = owner.effectiveGravity();
        if ((movementKind() == EntityTypeData.MovementKind.HORSE
                || movementKind() == EntityTypeData.MovementKind.CAMEL)
                && refusesRiddenMovement() && owner.onGround()) {
            owner.setVelocity(Vector3d.from(0.0, owner.velocity().getY(), 0.0));
        } else if ((movementKind() == EntityTypeData.MovementKind.PIG
                || movementKind() == EntityTypeData.MovementKind.STRIDER)
                && owner instanceof LivingEntity livingEntity) {
            tickBoost();
            double multiplier = movementKind() == EntityTypeData.MovementKind.PIG
                    ? 0.225 : striderSuffocating ? 0.35 : 0.55;
            double speed = livingEntity.effectiveMovementSpeed() * multiplier * boostFactor();
            double radians = Math.toRadians(owner.yaw());
            owner.addVelocity(Vector3d.from(
                    -Math.sin(radians) * speed, 0.0, Math.cos(radians) * speed));
        }
        owner.move(world, owner.velocity(), stepHeight);
        owner.setVelocity(Vector3d.from(
                owner.velocity().getX() * 0.91,
                (owner.velocity().getY() - gravity) * 0.98,
                owner.velocity().getZ() * 0.91));
    }

    private void tickFlyingVehicle(World world, Player controller) {
        if (controller != null) {
            double turn = movementKind() == EntityTypeData.MovementKind.HAPPY_GHAST ? 0.08 : 0.5;
            owner.setRotation((float) (owner.yaw()
                    + wrapDegrees(controller.yaw() - owner.yaw()) * turn), controller.pitch() * 0.5f);
        }
        double stepHeight = owner instanceof LivingEntity livingEntity
                ? livingEntity.effectiveStepHeight() : owner.defaultStepHeight();
        owner.move(world, owner.velocity(), stepHeight);
        owner.setVelocity(owner.velocity().mul(0.9));
    }

    private void floatBoat(World world) {
        double verticalSpeed = -0.04;
        double buoyancy = 0.0;
        float friction = 0.05f;
        if (oldBoatStatus == BoatStatus.IN_AIR
                && boatStatus != BoatStatus.IN_AIR && boatStatus != BoatStatus.ON_LAND) {
            waterLevel = owner.boundingBox().maxY();
            double targetY = world.waterLevelAbove(owner) - owner.boundingBox().maxY()
                    + owner.position().getY() + 0.101;
            Vector3d adjustment = Vector3d.from(0.0, targetY - owner.position().getY(), 0.0);
            if (world.canMoveWithoutCollision(owner, adjustment)) {
                owner.setPosition(Vector3d.from(
                        owner.position().getX(), targetY, owner.position().getZ()));
                owner.setVelocity(Vector3d.from(owner.velocity().getX(), 0.0, owner.velocity().getZ()));
            }
            boatStatus = BoatStatus.IN_WATER;
            return;
        }
        switch (boatStatus) {
            case IN_WATER -> {
                buoyancy = (waterLevel - owner.position().getY()) / Math.max(
                        1.0E-7, owner.boundingBox().maxY() - owner.boundingBox().minY());
                friction = 0.9f;
            }
            case UNDER_FLOWING_WATER -> {
                verticalSpeed = -0.0007;
                friction = 0.9f;
            }
            case UNDER_WATER -> {
                buoyancy = 0.01;
                friction = 0.45f;
            }
            case IN_AIR -> friction = 0.9f;
            case ON_LAND -> {
                friction = landFriction;
                landFriction /= 2.0f;
            }
        }
        owner.setVelocity(VehiclePhysics.boatVelocity(
                owner.velocity(), verticalSpeed, buoyancy, friction));
    }

    public Vector3d passengerAttachment(int index, int passengerCount) {
        if (movementKind() == EntityTypeData.MovementKind.BOAT) {
            double seat = passengerCount > 1 ? (index == 0 ? 0.2 : -0.6) : 0.0;
            return Vector3d.from(
                    0.0, owner.poseDefinition().height() * owner.effectiveScale() / 3.0, seat);
        }
        var attachments = owner.poseDefinition().passengerAttachments();
        return (attachments.isEmpty() ? Vector3d.ZERO
                : attachments.get(Math.min(index, attachments.size() - 1)))
                .mul(owner.effectiveScale());
    }

    public boolean clipsPassengerFluid() {
        return movementKind() == EntityTypeData.MovementKind.BOAT
                && boatStatus != BoatStatus.UNDER_WATER
                && boatStatus != BoatStatus.UNDER_FLOWING_WATER;
    }

    public void releaseLocalControl() {
        boosting = false;
        boostElapsed = 0;
    }

    private void acceptBoost() {
        if (steeringBoostRevision <= boostRevision) {
            return;
        }
        boostRevision = steeringBoostRevision;
        boostDuration = steeringBoostDuration;
        boostElapsed = 0;
        boosting = boostDuration > 0;
    }

    private void tickBoost() {
        if (boosting && boostElapsed++ > boostDuration) {
            boosting = false;
        }
    }

    private double boostFactor() {
        return boosting
                ? 1.0 + 1.15 * Math.sin((double) boostElapsed / boostDuration * Math.PI)
                : 1.0;
    }

    private boolean refusesRiddenMovement() {
        boolean horseImmobile = (horseFlags & (16 | 32)) != 0;
        return horseImmobile || movementKind() == EntityTypeData.MovementKind.CAMEL
                && (camelLastPoseChangeTick < 0 || owner.pose() == Pose.SITTING);
    }

    private static double wrapDegrees(double degrees) {
        double wrapped = degrees % 360.0;
        if (wrapped >= 180.0) {
            wrapped -= 360.0;
        } else if (wrapped < -180.0) {
            wrapped += 360.0;
        }
        return wrapped;
    }

    public enum BoatStatus {
        IN_WATER,
        UNDER_WATER,
        UNDER_FLOWING_WATER,
        ON_LAND,
        IN_AIR
    }
}
