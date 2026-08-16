package com.fakeplayerproxy.world.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fakeplayerproxy.world.data.Decoder;
import com.fakeplayerproxy.world.world.World;
import com.fakeplayerproxy.world.entity.Entity;
import com.fakeplayerproxy.world.entity.LivingEntity;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.List;
import java.util.UUID;

import net.kyori.adventure.key.Key;
import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftTypes;
import org.geysermc.mcprotocollib.protocol.data.game.RegistryEntry;
import org.geysermc.mcprotocollib.protocol.data.game.chunk.ChunkSection;
import org.geysermc.mcprotocollib.protocol.data.game.entity.Effect;
import org.geysermc.mcprotocollib.protocol.data.game.entity.EquipmentSlot;
import org.geysermc.mcprotocollib.protocol.data.game.entity.attribute.Attribute;
import org.geysermc.mcprotocollib.protocol.data.game.entity.attribute.AttributeType;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.Equipment;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerSpawnInfo;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockChangeEntry;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockEntityInfo;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundMoveVehiclePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundPaddleBoatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerRotPacket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;

final class PlayerTest {
    @Test
    void ordinaryKnockbackMovesAndThenDecays() {
        Player player = playerWithFloor();
        player.applyServerPosition(Vector3d.from(8.0, 1.0, 8.0),
                Vector3d.from(0.4, 0.0, 0.0), 0.0f, 0.0f, true);

        player.travel(player.world(), false);
        double firstX = player.position().getX();
        double firstVelocity = player.velocity().getX();
        player.travel(player.world(), false);

        assertEquals(8.4, firstX, 1.0E-9);
        assertTrue(player.position().getX() > firstX);
        assertTrue(player.velocity().getX() < firstVelocity);
    }

    @Test
    void blockCollisionClipsMovementButOrdinaryLivingEntityDoesNot() {
        Player blockPlayer = playerWithFloor();
        ChunkSection blockSection = chunk();
        fillFloor(blockSection);
        for (int y = 1; y <= 2; y++) {
            blockSection.setBlock(9, y, 8, 1);
        }
        installChunk(blockPlayer.world(), new ChunkSection[]{blockSection});
        blockPlayer.applyServerPosition(Vector3d.from(8.5, 1.0, 8.5),
                Vector3d.from(0.5, 0.0, 0.0), 0.0f, 0.0f, true);

        blockPlayer.travel(blockPlayer.world(), false);

        assertEquals(8.7, blockPlayer.position().getX(), 1.0E-7);
        assertTrue(blockPlayer.horizontalCollision());

        Player entityPlayer = playerWithFloor();
        entityPlayer.world().addEntity(9,
                org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType.ZOMBIE,
                Vector3d.from(9.2, 1.0, 8.5), Vector3d.ZERO, 0.0f, 0.0f);
        entityPlayer.applyServerPosition(Vector3d.from(8.5, 1.0, 8.5),
                Vector3d.from(0.5, 0.0, 0.0), 0.0f, 0.0f, true);

        entityPlayer.travel(entityPlayer.world(), false);

        assertEquals(9.0, entityPlayer.position().getX(), 1.0E-7);
        assertFalse(entityPlayer.horizontalCollision());
        assertTrue(entityPlayer.velocity().getX() > 0.0);
    }

    @Test
    void waterAppliesFluidDragAndFlow() {
        Player player = playerWithFloor();
        ChunkSection section = chunk();
        fillFloor(section);
        section.setBlock(8, 1, 8, 86);
        section.setBlock(7, 1, 8, 86);
        section.setBlock(9, 1, 8, 93);
        section.setBlock(8, 1, 7, 86);
        section.setBlock(8, 1, 9, 86);
        installChunk(player.world(), new ChunkSection[]{section});
        player.applyServerPosition(Vector3d.from(8.5, 1.0, 8.5),
                Vector3d.ZERO, 0.0f, 0.0f, false);

        player.travel(player.world(), false);

        assertTrue(player.position().getX() > 8.5);
        assertTrue(player.velocity().getX() > 0.0);
        assertTrue(player.inWater());
    }

    @Test
    void unknownChunkDoesNotPauseMovement() {
        Player player = player();
        configure(player.world());
        player.initializeGame(1, spawnInfo());
        player.applyServerPosition(Vector3d.from(8.0, 1.0, 8.0),
                Vector3d.ZERO, 0.0f, 0.0f, false);

        player.travel(player.world(), false);
        player.travel(player.world(), false);

        assertTrue(player.position().getY() < 1.0);
        assertTrue(player.velocity().getY() < 0.0);
    }

    @Test
    void gravityAttributeAndSlowFallingEffectChangeTravel() {
        Player normal = playerWithFloor();
        normal.applyServerPosition(Vector3d.from(8.0, 4.0, 8.0),
                Vector3d.ZERO, 0.0f, 0.0f, false);
        normal.travel(normal.world(), false);

        Player modified = playerWithFloor();
        modified.updateAttributes(List.of(new Attribute(AttributeType.Builtin.GRAVITY, 0.2)));
        modified.updateEffect(Effect.SLOW_FALLING, 0);
        modified.applyServerPosition(Vector3d.from(8.0, 4.0, 8.0),
                Vector3d.ZERO, 0.0f, 0.0f, false);
        modified.travel(modified.world(), false);

        assertTrue(normal.velocity().getY() < modified.velocity().getY());
        assertEquals(-0.0098, modified.velocity().getY(), 1.0E-9);
    }

    @Test
    void fixedFallingAdjustmentDrivesWaterTravel() {
        Player player = playerWithBlockAtBody(86);
        player.applyServerPosition(Vector3d.from(8.5, 1.0, 8.5),
                Vector3d.ZERO, 0.0f, 0.0f, false);

        player.travel(player.world(), false);

        assertEquals(-0.005, player.velocity().getY(), 1.0E-7);
    }

    @Test
    void gameModeAndAbilitiesDriveTheirRetainedPhysicsBranches() {
        Player spectator = playerWithFloor();
        spectator.world().addEntity(9, EntityType.ZOMBIE,
                Vector3d.from(9.2, 1.0, 8.5), Vector3d.ZERO, 0.0f, 0.0f);
        spectator.applyServerPosition(Vector3d.from(8.5, 1.0, 8.5),
                Vector3d.from(1.0, 0.0, 0.0), 0.0f, 0.0f, false);
        spectator.gameMode(GameMode.SPECTATOR);

        spectator.travel(spectator.world(), true);

        assertEquals(9.5, spectator.position().getX(), 1.0E-9);
        assertFalse(spectator.horizontalCollision());
        assertFalse(spectator.onGround());

        Player flying = playerWithFloor();
        flying.applyServerPosition(Vector3d.from(8.5, 4.0, 8.5),
                Vector3d.from(0.4, 0.2, 0.0), 0.0f, 0.0f, false);
        flying.abilities(true, true);

        flying.travel(flying.world(), true);

        assertEquals(8.9, flying.position().getX(), 1.0E-9);
        assertTrue(flying.velocity().getX() < 0.4);
        assertEquals(0.12, flying.velocity().getY(), 1.0E-9);
    }

    @Test
    void sharedMovementFlagsDriveClientPoseSelection() {
        Player player = playerWithFloor();
        player.applyServerPosition(Vector3d.from(8.5, 1.0, 8.5),
                Vector3d.ZERO, 0.0f, 0.0f, true);

        player.applyMetadata(Decoder.instance().entity(EntityType.PLAYER).sharedFlagsMetadataId(),
                (byte) (1 << 4));
        player.tick(mock(MinecraftConnection.class), true);
        assertEquals(org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.Pose.SWIMMING,
                player.pose());

        player.applyMetadata(Decoder.instance().entity(EntityType.PLAYER).sharedFlagsMetadataId(),
                (byte) (1 << 7));
        player.tick(mock(MinecraftConnection.class), true);
        assertEquals(org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.Pose.FALL_FLYING,
                player.pose());
    }

    @Test
    void equipmentAndNoGravityChangeOnlyTheirRetainedMovementBranches() {
        Player powderWalker = playerWithFloorState(27162);
        powderWalker.updateEquipment(new Equipment[]{
                new Equipment(EquipmentSlot.BOOTS,
                        new ItemStack(Decoder.instance().leatherBootsItemId()))
        });
        powderWalker.applyServerPosition(Vector3d.from(8.5, 1.2, 8.5),
                Vector3d.from(0.0, -0.4, 0.0), 0.0f, 0.0f, false);

        powderWalker.travel(powderWalker.world(), false);

        assertTrue(powderWalker.position().getY() >= 1.0);

        Player floating = playerWithFloor();
        floating.noGravity(true);
        floating.applyServerPosition(Vector3d.from(8.5, 4.0, 8.5),
                Vector3d.ZERO, 0.0f, 0.0f, false);
        floating.travel(floating.world(), false);
        assertEquals(0.0, floating.velocity().getY(), 1.0E-9);
    }

    @Test
    void scaleFitUsesPoseResourceWithoutClearingActionInput() {
        Player player = playerWithFloor();
        ChunkSection section = chunk();
        fillFloor(section);
        for (int x = 7; x <= 9; x++) {
            for (int z = 7; z <= 9; z++) {
                section.setBlock(x, 3, z, 1);
            }
        }
        installChunk(player.world(), new ChunkSection[]{section});
        player.applyServerPosition(Vector3d.from(8.5, 1.0, 8.5),
                Vector3d.ZERO, 0.0f, 0.0f, true);
        player.updateAttributes(List.of(new Attribute(AttributeType.Builtin.SCALE, 2.0)));
        Player.InputState actionInput = Player.InputState.CLEAR.withMovement("forward");
        player.setInputState(actionInput);

        player.tick(mock(MinecraftConnection.class), true);

        assertEquals(org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.Pose.SWIMMING,
                player.pose());
        assertEquals(actionInput, player.inputState());
    }

    @Test
    void locallyControlledRootIgnoresOrdinaryTransformAndGroundState() {
        Player player = playerWithFloor();
        Entity boat = player.world().addEntity(2, EntityType.OAK_BOAT,
                Vector3d.from(8.0, 1.0, 8.0), Vector3d.ZERO, 0.0f, 0.0f);
        player.world().setPassengers(2, new int[]{player.id()});

        player.applyVehiclePosition(Vector3d.from(12.0, 2.0, 13.0), 30.0f, 5.0f);
        boat.interpolate(Vector3d.from(0.5, 0.0, 0.0),
                true, 0.0f, 0.0f, false, true);
        player.world().tick();
        player.world().tick();
        player.world().tick();

        assertEquals(Vector3d.from(12.0, 2.0, 13.0), boat.position());
        assertFalse(boat.onGround());
    }

    @Test
    void releasedLocalRootUsesTheAdvancedRelativeCodecBaseline() {
        Player player = playerWithFloor();
        Entity boat = player.world().addEntity(2, EntityType.OAK_BOAT,
                Vector3d.from(8.0, 2.0, 8.0), Vector3d.ZERO, 0.0f, 0.0f);
        player.world().setPassengers(2, new int[]{player.id()});

        boat.interpolate(Vector3d.from(0.5, 0.0, 0.0),
                true, 0.0f, 0.0f, false, false);
        assertEquals(8.0, boat.position().getX(), 1.0E-9);

        player.world().setPassengers(2, new int[0]);
        boat.interpolate(Vector3d.from(0.5, 0.0, 0.0),
                true, 0.0f, 0.0f, false, false);
        player.world().tick();
        player.world().tick();
        player.world().tick();

        assertEquals(9.0, boat.position().getX(), 1.0E-9);
    }

    @Test
    void ordinaryLivingEntityStillProducesPushWithoutMovementCollision() {
        Player player = playerWithFloor();
        player.setPosition(Vector3d.from(8.5, 1.0, 8.5));
        player.setVelocity(Vector3d.ZERO);
        player.world().addEntity(9, EntityType.ZOMBIE,
                Vector3d.from(8.7, 1.0, 8.5), Vector3d.ZERO, 0.0f, 0.0f);

        player.world().pushEntities(player);

        assertTrue(player.velocity().getX() < 0.0);
    }

    @Test
    void scaffoldingCollisionUsesEntityHeightAndDescendingContext() {
        Player supported = playerWithBlockAtBody(20707);
        supported.applyServerPosition(Vector3d.from(8.5, 2.0, 8.5),
                Vector3d.from(0.0, -0.3, 0.0), 0.0f, 0.0f, false);
        supported.travel(supported.world(), false);

        Player descending = playerWithBlockAtBody(20707);
        descending.applyServerPosition(Vector3d.from(8.5, 2.0, 8.5),
                Vector3d.from(0.0, -0.3, 0.0), 0.0f, 0.0f, false);
        descending.setInputState(Player.InputState.CLEAR.withShift(true));
        descending.travel(descending.world(), false);

        assertEquals(2.0, supported.position().getY(), 1.0E-7);
        assertTrue(descending.position().getY() < supported.position().getY());
    }

    @Test
    void powderSnowFallingShapeUsesAccumulatedDistance() {
        Player player = playerWithFloorState(27162);
        player.applyServerPosition(Vector3d.from(8.5, 4.0, 8.5),
                Vector3d.ZERO, 0.0f, 0.0f, false);

        player.move(player.world(), Vector3d.from(0.0, -3.0, 0.0), 0.6);
        assertTrue(player.fallDistance() > 2.5);
        player.move(player.world(), Vector3d.from(0.0, -0.2, 0.0), 0.6);

        assertEquals(0.9, player.position().getY(), 1.0E-6);
    }

    @Test
    void weavingChangesTheRetainedCobwebMultiplier() {
        Player player = playerWithBlockAtBody(2247);
        player.updateEffect(Effect.WEAVING, 0);
        player.applyServerPosition(Vector3d.from(8.5, 1.0, 8.5),
                Vector3d.from(0.2, 0.0, 0.0), 0.0f, 0.0f, false);
        player.travel(player.world(), false);
        double before = player.position().getX();
        double velocityBefore = player.velocity().getX();

        player.travel(player.world(), false);

        assertEquals(velocityBefore * 0.5, player.position().getX() - before, 1.0E-7);
    }

    @Test
    void retainedVehicleMetadataControlsImmobilityAndSteeringBoost() {
        Player player = playerWithFloor();
        player.setRotation(0.0f, 0.0f);
        player.updateEquipment(new Equipment[]{
                new Equipment(EquipmentSlot.MAIN_HAND,
                        new ItemStack(Decoder.instance().carrotOnAStickItemId()))
        });
        Entity pig = player.world().addEntity(2, EntityType.PIG,
                Vector3d.from(8.0, 1.0, 8.0), Vector3d.ZERO, 0.0f, 0.0f);
        LivingEntity livingPig = (LivingEntity) pig;
        livingPig.updateEquipment(new Equipment[]{
                new Equipment(EquipmentSlot.SADDLE,
                        new ItemStack(Decoder.instance().saddleItemId()))
        });
        player.world().setPassengers(2, new int[]{player.id()});
        pig.applyMetadata(Decoder.instance().entity(EntityType.PIG).vehicle().steeringBoostMetadataId(), 20);

        assertTrue(pig.isControlledBy(player));
        pig.tickVehicle(player.world());
        assertTrue(pig.position().getZ() > 8.056);

        Entity horse = player.world().addEntity(3, EntityType.HORSE,
                Vector3d.from(8.0, 1.0, 10.0), Vector3d.from(0.2, 0.0, 0.0),
                0.0f, 0.0f);
        ((LivingEntity) horse).updateEquipment(new Equipment[]{
                new Equipment(EquipmentSlot.SADDLE,
                        new ItemStack(Decoder.instance().saddleItemId()))
        });
        player.world().setPassengers(2, new int[0]);
        player.world().setPassengers(3, new int[]{player.id()});
        horse.setCollisionFlags(true, false);
        horse.applyMetadata(Decoder.instance().entity(EntityType.HORSE).vehicle().horseFlagsMetadataId(),
                (byte) 32);
        horse.tickVehicle(player.world());
        assertEquals(8.0, horse.position().getX(), 1.0E-7);
    }

    @ParameterizedTest
    @CsvSource({
            "2247, 0.25",
            "20941, 0.8",
            "27162, 0.9"
    })
    void stickyBlocksApplyTheirNextMovementMultiplier(int stateId, double multiplier) {
        Player player = playerWithBlockAtBody(stateId);
        player.applyServerPosition(Vector3d.from(8.5, 1.0, 8.5),
                Vector3d.from(0.2, 0.0, 0.0), 0.0f, 0.0f, false);
        player.travel(player.world(), false);
        double before = player.position().getX();
        double velocityBefore = player.velocity().getX();

        player.travel(player.world(), false);

        assertEquals(velocityBefore * multiplier, player.position().getX() - before, 1.0E-7);
    }

    @ParameterizedTest
    @CsvSource({
            "15294, false",
            "15295, true"
    })
    void bubbleColumnDirectionChangesVerticalMovement(int stateId, boolean upward) {
        Player player = playerWithBlockAtBody(stateId);
        player.applyServerPosition(Vector3d.from(8.5, 1.0, 8.5),
                Vector3d.ZERO, 0.0f, 0.0f, false);

        player.travel(player.world(), false);

        assertEquals(upward, player.velocity().getY() > 0.0);
    }

    @Test
    void bubbleColumnSurfaceRequiresAirInsteadOfOnlyAnEmptyCollisionShape() {
        Player openSurface = playerWithBlockAtBody(15295);
        Player saplingSurface = playerWithBlockAtBody(15295);
        saplingSurface.world().updateBlock(
                new BlockChangeEntry(Vector3i.from(8, 2, 8), 29));
        openSurface.applyServerPosition(Vector3d.from(8.5, 1.0, 8.5),
                Vector3d.ZERO, 0.0f, 0.0f, false);
        saplingSurface.applyServerPosition(Vector3d.from(8.5, 1.0, 8.5),
                Vector3d.ZERO, 0.0f, 0.0f, false);

        openSurface.travel(openSurface.world(), false);
        saplingSurface.travel(saplingSurface.world(), false);

        assertTrue(openSurface.velocity().getY() > saplingSurface.velocity().getY());
    }

    @ParameterizedTest
    @CsvSource({
            "12532, 0.9016",
            "2158, 0.6566"
    })
    void collisionBouncinessComesFromFixedBlockScalar(int stateId, double expectedUpwardSpeed) {
        Player player = playerWithFloorState(stateId);
        player.applyServerPosition(Vector3d.from(8.5, 1.2, 8.5),
                Vector3d.from(0.0, -1.0, 0.0), 0.0f, 0.0f, false);

        player.travel(player.world(), false);

        assertEquals(expectedUpwardSpeed, player.velocity().getY(), 1.0E-5);
    }

    @ParameterizedTest
    @CsvSource({
            "6998",
            "21816"
    })
    void speedFactorBlocksReduceHorizontalVelocity(int stateId) {
        Player player = playerWithFloorState(stateId);
        player.applyServerPosition(Vector3d.from(8.5, 1.0, 8.5),
                Vector3d.from(0.4, 0.0, 0.0), 0.0f, 0.0f, true);

        player.travel(player.world(), false);

        assertTrue(player.velocity().getX() < 0.2);
    }

    @Test
    void climbableClampsZeroInputExternalMovement() {
        Player player = playerWithBlockAtBody(5720);
        player.applyServerPosition(Vector3d.from(8.5, 1.0, 8.5),
                Vector3d.from(0.4, -0.5, 0.4), 0.0f, 0.0f, false);

        player.travel(player.world(), false);

        assertTrue(player.position().getX() <= 8.65 + 1.0E-9);
        assertTrue(player.position().getY() >= 0.85 - 1.0E-9);
        assertTrue(player.position().getZ() <= 8.65 + 1.0E-9);
    }

    @Test
    void honeySideSlideThrottlesFallingSpeed() {
        Player player = playerWithBlockAtBody(21816);
        player.applyServerPosition(Vector3d.from(7.71, 1.2, 8.5),
                Vector3d.from(0.0, -0.3, 0.0), 0.0f, 0.0f, false);

        player.travel(player.world(), false);

        assertTrue(player.velocity().getY() > -0.3);
    }

    @ParameterizedTest
    @MethodSource("vehicleBranches")
    void distinctVehicleBranchesRetainTheirOwnershipModel(EntityType type, boolean movesLocally) {
        Player player = playerWithFloor();
        Entity vehicle = player.world().addEntity(2, type,
                Vector3d.from(8.0, 2.0, 8.0), Vector3d.from(0.2, 0.0, 0.0),
                0.0f, 0.0f);
        if (vehicle instanceof LivingEntity livingEntity) {
            if (type == EntityType.HORSE) {
                livingEntity.updateEquipment(new Equipment[]{
                        new Equipment(EquipmentSlot.SADDLE,
                                new ItemStack(Decoder.instance().saddleItemId()))
                });
            } else if (type == EntityType.HAPPY_GHAST) {
                livingEntity.updateEquipment(new Equipment[]{
                        new Equipment(EquipmentSlot.BODY, new ItemStack(866))
                });
            }
        }
        player.world().setPassengers(2, new int[]{player.id()});
        if (vehicle.isControlledBy(player)) {
            vehicle.tickVehicle(player.world());
        }

        assertEquals(movesLocally, vehicle.position().getX() > 8.0);
    }

    @Test
    void passengerAndControlledVehicleOutputsAreMutuallyExclusive() {
        Player player = playerWithFloor();
        MinecraftConnection backend = mock(MinecraftConnection.class);
        Entity serverVehicle = player.world().addEntity(2, EntityType.ZOMBIE,
                Vector3d.from(8.0, 1.0, 8.0), Vector3d.ZERO, 0.0f, 0.0f);
        player.world().setPassengers(2, new int[]{player.id()});

        player.tick(backend, true);

        verify(backend).sendPacket(argThat(packet -> packet instanceof ServerboundMovePlayerRotPacket));
        verify(backend, never()).sendPacket(argThat(packet -> packet instanceof ServerboundMoveVehiclePacket));
        verify(backend, never()).sendPacket(argThat(packet -> packet instanceof ServerboundMovePlayerPosPacket));

        clearInvocations(backend);
        Entity boat = player.world().addEntity(3, EntityType.OAK_BOAT,
                Vector3d.from(8.0, 1.0, 8.0), Vector3d.ZERO, 0.0f, 0.0f);
        player.world().setPassengers(2, new int[0]);
        player.world().setPassengers(3, new int[]{player.id()});

        player.tick(backend, true);

        InOrder output = inOrder(backend);
        output.verify(backend).sendPacket(argThat(packet -> packet instanceof ServerboundPaddleBoatPacket));
        output.verify(backend).sendPacket(argThat(packet -> packet instanceof ServerboundMovePlayerRotPacket));
        output.verify(backend).sendPacket(argThat(packet -> packet instanceof ServerboundMoveVehiclePacket));
        verify(backend, never()).sendPacket(argThat(packet -> packet instanceof ServerboundMovePlayerPosPacket));
        assertTrue(boat.passengers().contains(player));
        assertFalse(serverVehicle.passengers().contains(player));
    }

    private static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> vehicleBranches() {
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(EntityType.OAK_BOAT, true),
                org.junit.jupiter.params.provider.Arguments.of(EntityType.HORSE, true),
                org.junit.jupiter.params.provider.Arguments.of(EntityType.HAPPY_GHAST, true),
                org.junit.jupiter.params.provider.Arguments.of(EntityType.MINECART, false));
    }

    private static Player playerWithFloor() {
        Player player = player();
        configure(player.world());
        player.initializeGame(1, spawnInfo());
        ChunkSection section = chunk();
        fillFloor(section);
        installChunk(player.world(), new ChunkSection[]{section});
        return player;
    }

    private static Player playerWithFloorState(int stateId) {
        Player player = playerWithFloor();
        ChunkSection section = chunk();
        fillFloor(section);
        section.setBlock(8, 0, 8, stateId);
        installChunk(player.world(), new ChunkSection[]{section});
        return player;
    }

    private static Player playerWithBlockAtBody(int stateId) {
        Player player = playerWithFloor();
        ChunkSection section = chunk();
        fillFloor(section);
        section.setBlock(8, 1, 8, stateId);
        installChunk(player.world(), new ChunkSection[]{section});
        return player;
    }

    private static void configure(World world) {
        world.registry(Key.key("minecraft", "dimension_type"), List.of(new RegistryEntry(
                Key.key("minecraft", "overworld"),
                NbtMap.builder().putInt("min_y", 0).putInt("height", 16).build())));
        world.registry(Key.key("minecraft", "worldgen/biome"), List.of(
                new RegistryEntry(Key.key("minecraft", "plains"), NbtMap.EMPTY)));
    }

    private static ChunkSection chunk() {
        return new ChunkSection(
                0, Decoder.instance().blockStateCount(), 0, 1);
    }

    private static void fillFloor(ChunkSection section) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                section.setBlock(x, 0, z, 1);
            }
        }
    }

    private static void installChunk(World world, ChunkSection[] sections) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            for (ChunkSection section : sections) {
                MinecraftTypes.writeChunkSection(buffer, section);
            }
            byte[] encoded = new byte[buffer.readableBytes()];
            buffer.readBytes(encoded);
            assertTrue(world.decodeAndInstallChunk(
                    0, 0, encoded, new BlockEntityInfo[0]).installed());
        } finally {
            buffer.release();
        }
    }

    private static PlayerSpawnInfo spawnInfo() {
        return new PlayerSpawnInfo(
                0, Key.key("minecraft", "overworld"), 0L, GameMode.SURVIVAL,
                GameMode.SURVIVAL, false, false, null, 0, 63);
    }

    private static Player player() {
        com.velocitypowered.api.proxy.Player velocityPlayer =
                mock(com.velocitypowered.api.proxy.Player.class);
        when(velocityPlayer.getUniqueId()).thenReturn(UUID.randomUUID());
        return new Player(velocityPlayer);
    }
}
