package com.fakeplayerproxy.world.data;

import java.util.List;

import org.cloudburstmc.math.vector.Vector3d;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.Pose;

/** Immutable Minecraft 26.2 geometry and movement defaults for one entity type. */
public record EntityTypeData(
        List<PoseData> poses,
        MovementKind movementKind,
        VehicleData vehicle,
        boolean living,
        boolean pushable,
        boolean movementCollision,
        boolean pickable,
        float pickRadius,
        boolean affectedByPiston,
        byte defaultSharedFlags,
        boolean defaultNoGravity,
        Pose defaultPose,
        int sharedFlagsMetadataId,
        int noGravityMetadataId,
        int poseMetadataId,
        int healthMetadataId,
        int livingFlagsMetadataId,
        float defaultHealth,
        double gravity,
        double scale,
        double stepHeight,
        double movementSpeed,
        double movementEfficiency,
        double waterMovementEfficiency,
        double bounciness) {

    public EntityTypeData {
        poses = List.copyOf(poses);
        if ((movementKind == MovementKind.SERVER || movementKind == MovementKind.MINECART)
                != (vehicle == null)) {
            throw new IllegalArgumentException(
                    "Only client-controlled movement kinds may have vehicle data");
        }
    }

    public record VehicleData(
            int horseFlagsMetadataId,
            int steeringBoostMetadataId,
            int striderSuffocatingMetadataId,
            int camelLastPoseChangeTickMetadataId,
            int happyGhastStaysStillMetadataId) {
    }

    public record PoseData(
            double width,
            double height,
            double eyeHeight,
            List<Vector3d> passengerAttachments,
            Vector3d vehicleAttachment) {

        public PoseData {
            passengerAttachments = List.copyOf(passengerAttachments);
        }
    }

    public enum MovementKind {
        SERVER,
        MINECART,
        BOAT,
        HORSE,
        CAMEL,
        PIG,
        STRIDER,
        HAPPY_GHAST,
        NAUTILUS
    }

}
