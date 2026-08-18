package com.fakeplayerproxy.world.phys;

import com.fakeplayerproxy.world.entity.Entity;
import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;

/** Packet-specific alternatives returned by the shared interaction raycast. */
public sealed interface InteractionHit permits InteractionHit.BlockHit, InteractionHit.EntityHit {
    record BlockHit(
            Vector3i position,
            Direction face,
            Vector3d hitPoint,
            double distance,
            boolean insideBlock,
            boolean insideWorldBorder) implements InteractionHit {
    }

    record EntityHit(Entity entity, Vector3d hitPoint, double distance) implements InteractionHit {
    }
}
