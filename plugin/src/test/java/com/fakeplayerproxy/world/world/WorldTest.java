package com.fakeplayerproxy.world.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fakeplayerproxy.utils.Result;
import com.fakeplayerproxy.world.data.Block;
import com.fakeplayerproxy.world.data.Decoder;
import com.fakeplayerproxy.world.entity.Entity;
import com.fakeplayerproxy.world.entity.Vehicle;
import com.fakeplayerproxy.world.phys.AABB;
import com.fakeplayerproxy.world.phys.InteractionHit;
import com.fakeplayerproxy.world.player.Player;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.kyori.adventure.key.Key;
import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftTypes;
import org.geysermc.mcprotocollib.protocol.data.game.RegistryEntry;
import org.geysermc.mcprotocollib.protocol.data.game.chunk.ChunkSection;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerSpawnInfo;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockChangeEntry;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockEntityInfo;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockEntityType;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.value.PistonValueType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class WorldTest {
    private static final Key DIMENSION_TYPES = Key.key("minecraft", "dimension_type");
    private static final Key BIOMES = Key.key("minecraft", "worldgen/biome");
    private static final Key OVERWORLD = Key.key("minecraft", "overworld");
    private static final Key NETHER = Key.key("minecraft", "the_nether");

    @Test
    void raycastSelectsVisibleEntityAndLetsBlocksOccludeIt() {
        Player player = singleSectionPlayer();
        player.setPosition(Vector3d.from(8.5, 1.0, 8.5));
        Entity zombie = player.world().addEntity(
                2, EntityType.ZOMBIE, Vector3d.from(8.5, 1.0, 5.5),
                Vector3d.ZERO, 0.0f, 0.0f);
        player.world().addEntity(
                3, EntityType.ZOMBIE, Vector3d.from(8.5, 1.0, 7.4),
                Vector3d.ZERO, 0.0f, 0.0f);

        InteractionHit.EntityHit visible = assertInstanceOf(
                InteractionHit.EntityHit.class,
                player.world().raycast(player, 2.0, 4.5, 4.5, 0.0).orElseThrow());
        assertSame(zombie, visible.entity());

        ChunkSection section = sections(1, 1)[0];
        section.setBlock(8, 2, 7, Decoder.instance().blockState("minecraft:stone"));
        installChunk(player.world(), 0, 0, new ChunkSection[] {section});

        assertInstanceOf(InteractionHit.BlockHit.class,
                player.world().raycast(player, 0.0, 4.5, 4.5, 0.0).orElseThrow());
    }

    @Test
    void mountSelectionUsesCarpetBoxAndCoordinateDistance() {
        Player player = singleSectionPlayer();
        player.setPosition(Vector3d.from(8.0, 1.0, 8.0));
        Entity lowerId = player.world().addEntity(
                2, EntityType.OAK_BOAT, Vector3d.from(9.0, 1.0, 8.0),
                Vector3d.ZERO, 0.0f, 0.0f);
        player.world().addEntity(
                3, EntityType.MINECART, Vector3d.from(7.0, 1.0, 8.0),
                Vector3d.ZERO, 0.0f, 0.0f);
        player.world().addEntity(
                4, EntityType.ZOMBIE, Vector3d.from(8.0, 1.0, 8.0),
                Vector3d.ZERO, 0.0f, 0.0f);
        player.world().addEntity(
                5, EntityType.OAK_BOAT, Vector3d.from(16.0, 1.0, 8.0),
                Vector3d.ZERO, 0.0f, 0.0f);

        assertSame(lowerId, player.world().mountCandidate(player, player.position(), false).orElseThrow());
        assertEquals(3, player.world().mountCandidate(
                player, Vector3d.from(6.5, 1.0, 8.0), true).orElseThrow().id());
        assertEquals(new Result.Failure<Void, String>(
                        "fakeplayerproxy.command.rideable_out_of_range"),
                player.mount(mock(MinecraftConnection.class), Vector3d.from(16.0, 1.0, 8.0), true));
    }

    @Test
    void appliesUpdatesOnlyToLoadedChunksAndKeepsUnknownChunksUnknown() {
        World world = configuredWorld();
        world.select(spawnInfo(0, OVERWORLD));
        ChunkSection[] sections = sections(24, 2);
        installChunk(world, -1, 2, sections);

        world.updateBlock(new BlockChangeEntry(Vector3i.from(-1, -63, 32), 7));
        world.updateBlock(new BlockChangeEntry(Vector3i.from(32, -63, 32), 9));

        assertEquals(7, world.blockState(-1, -63, 32).orElseThrow());
        assertTrue(world.currentPlayerChunkLoaded(-0.1, 32.0));
        assertFalse(world.currentPlayerChunkLoaded(32.0, 32.0));
        assertTrue(world.blockState(32, -63, 32).isEmpty());

        world.forgetChunk(-1, 2);
        assertFalse(world.currentPlayerChunkLoaded(-0.1, 32.0));
    }

    @Test
    void installsLevelChunkOnlyAfterCompleteDecode() {
        World world = configuredWorld();
        world.select(spawnInfo(1, NETHER));
        ChunkSection[] sections = sections(16, 2);
        sections[0].setBlock(1, 2, 3, 7);
        ByteBuf buffer = Unpooled.buffer();
        byte[] encoded;
        try {
            for (ChunkSection section : sections) {
                MinecraftTypes.writeChunkSection(buffer, section);
            }
            encoded = new byte[buffer.readableBytes()];
            buffer.readBytes(encoded);
        } finally {
            buffer.release();
        }

        assertTrue(world.decodeAndInstallChunk(
                0, 0, encoded, new BlockEntityInfo[0]).installed());
        assertEquals(7, world.blockState(1, 2, 3).orElseThrow());
        var decodeFailure = world.decodeAndInstallChunk(
                0, 0, java.util.Arrays.copyOf(encoded, encoded.length - 1),
                new BlockEntityInfo[0]);
        assertFalse(decodeFailure.installed());
        assertTrue(decodeFailure.cause().isPresent());
        assertEquals(7, world.blockState(1, 2, 3).orElseThrow());
    }

    @Test
    void rejectsNullLevelChunkDimensionDataWithoutCause() {
        World nullDimensionData = player().world();
        nullDimensionData.registry(DIMENSION_TYPES, List.of(
                new RegistryEntry(Key.key("minecraft", "overworld"), null)));
        nullDimensionData.registry(BIOMES, List.of(
                new RegistryEntry(Key.key("minecraft", "plains"), NbtMap.EMPTY)));
        nullDimensionData.select(spawnInfo(0, OVERWORLD));
        var result = nullDimensionData.decodeAndInstallChunk(
                0, 0, new byte[0], new BlockEntityInfo[0]);

        assertFalse(result.installed());
        assertTrue(result.cause().isEmpty());
    }

    @Test
    void passengerReplacementAndRemovalKeepBothSidesConsistent() {
        Player player = player();
        player.initializeGame(1, spawnInfo(0, OVERWORLD));
        World world = player.world();
        Entity vehicle = world.addEntity(2, EntityType.OAK_BOAT,
                org.cloudburstmc.math.vector.Vector3d.ZERO,
                org.cloudburstmc.math.vector.Vector3d.ZERO, 0.0f, 0.0f);
        Entity other = world.addEntity(3, EntityType.ZOMBIE,
                org.cloudburstmc.math.vector.Vector3d.ZERO,
                org.cloudburstmc.math.vector.Vector3d.ZERO, 0.0f, 0.0f);

        world.setPassengers(2, new int[]{99, 1, 3});

        assertEquals(List.of(player, other), vehicle.passengers());
        assertSame(vehicle, player.vehicle());
        assertSame(vehicle, other.vehicle());

        world.setPassengers(player.id(), new int[]{vehicle.id()});
        assertTrue(player.passengers().isEmpty());
        assertNull(vehicle.vehicle());
        assertTrue(world.collisions(player, player.boundingBox().inflate(0.1)).isEmpty());

        world.setPassengers(2, new int[]{3});
        assertNull(player.vehicle());
        assertEquals(List.of(other), vehicle.passengers());

        world.removeEntities(new int[]{2});
        assertNull(other.vehicle());
        assertNull(world.entity(2));
    }

    @Test
    void missingVehicleDoesNotCreatePendingRelationships() {
        Player player = player();
        player.initializeGame(1, spawnInfo(0, OVERWORLD));

        player.world().setPassengers(99, new int[]{1});
        player.world().addEntity(99, EntityType.OAK_BOAT,
                org.cloudburstmc.math.vector.Vector3d.ZERO,
                org.cloudburstmc.math.vector.Vector3d.ZERO, 0.0f, 0.0f);

        assertNull(player.vehicle());
        assertTrue(player.world().entity(99).passengers().isEmpty());
    }

    @Test
    void frozenAndZeroTickRateDoNotStopLocalClientCadence() {
        Player player = player();
        World world = player.world();

        world.tickingState(10.0f, false);
        assertEquals(100.0, world.clientTickCadenceMillis());

        world.tickingState(0.0f, true);
        assertEquals(100.0, world.clientTickCadenceMillis());

        world.tickingState(40.0f, false);
        assertEquals(50.0, world.clientTickCadenceMillis());

        world.border(0.0, 0.0, 4.0, 8.0, 100L);
        AABB beyondInitialBorder = new AABB(3.2, 0.0, 0.0, 3.5, 1.0, 1.0);
        assertFalse(world.collisions(player, beyondInitialBorder).isEmpty());
        world.tick();
        world.tick();
        assertTrue(world.collisions(player, beyondInitialBorder).isEmpty());
    }

    @Test
    void dynamicClimbableTagOverridesTheFixedVanillaTag() {
        Player player = player();
        World world = player.world();
        world.registry(DIMENSION_TYPES, List.of(registryEntry(OVERWORLD, 0, 16)));
        world.registry(BIOMES, List.of(new RegistryEntry(Key.key("minecraft", "plains"), NbtMap.EMPTY)));
        player.initializeGame(1, spawnInfo(0, OVERWORLD));
        ChunkSection section = sections(1, 1)[0];
        section.setBlock(8, 1, 8, 5720);
        installChunk(world, 0, 0, new ChunkSection[]{section});
        player.setPosition(Vector3d.from(8.5, 1.0, 8.5));
        int ladderBlockId = Decoder.instance().block(5720).blockId();

        world.physicalTags(Map.of(Key.key("minecraft", "block"),
                Map.of(Key.key("minecraft", "climbable"), new int[]{ladderBlockId})));
        assertTrue(world.hasBehavior(player, Block.Behavior.CLIMBABLE));

        world.physicalTags(Map.of(Key.key("minecraft", "block"),
                Map.of(Key.key("minecraft", "climbable"), new int[0])));
        assertFalse(world.hasBehavior(player, Block.Behavior.CLIMBABLE));
    }

    @Test
    void frozenServerStateAdvancesOnlyForTickingStepsIncludingPistons() {
        Player player = player();
        World world = player.world();
        player.initializeGame(1, spawnInfo(0, OVERWORLD));
        Entity interpolated = world.addEntity(2, EntityType.ZOMBIE,
                Vector3d.ZERO, Vector3d.ZERO, 0.0f, 0.0f);
        interpolated.interpolate(
                Vector3d.from(3.0, 0.0, 0.0), true, 90.0f, 30.0f, true, false);
        player.setPosition(Vector3d.from(8.45, 1.0, 8.5));
        NbtMap piston = NbtMap.builder()
                .putCompound("blockState", NbtMap.builder()
                        .putString("Name", "minecraft:stone").build())
                .putInt("facing", Direction.EAST.ordinal())
                .putFloat("progress", 0.0f)
                .putBoolean("extending", true)
                .putBoolean("source", false)
                .build();
        world.blockEntity(Vector3i.from(8, 1, 8), BlockEntityType.PISTON, piston);
        world.tickingState(20.0f, true);

        world.tick();
        assertEquals(Vector3d.ZERO, interpolated.position());
        assertEquals(0.0f, interpolated.yaw());
        assertEquals(0.0f, interpolated.pitch());
        assertEquals(8.45, player.position().getX(), 1.0E-9);

        world.tickingStep(1);
        world.tick();
        assertTrue(interpolated.position().getX() > 0.0);
        assertTrue(interpolated.yaw() > 0.0f);
        assertTrue(interpolated.pitch() > 0.0f);
        assertTrue(player.position().getX() > 8.45);
    }

    @Test
    void orderedMultiSeatAttachmentsProduceDistinctPassengerPositions() {
        Player player = player();
        player.initializeGame(1, spawnInfo(0, OVERWORLD));
        World world = player.world();
        Entity vehicle = world.addEntity(2, EntityType.HAPPY_GHAST,
                Vector3d.from(10.0, 20.0, 30.0), Vector3d.ZERO, 90.0f, 90.0f);
        Entity other = world.addEntity(3, EntityType.ZOMBIE,
                Vector3d.ZERO, Vector3d.ZERO, 0.0f, 0.0f);
        world.setPassengers(2, new int[]{1, 3});

        vehicle.placePassengerTree();

        assertNotEquals(player.position(), other.position());
        assertTrue(player.position().getY() > vehicle.position().getY());
        assertTrue(other.position().getY() > vehicle.position().getY());
    }

    @ParameterizedTest
    @CsvSource({
            "true, false, false, 0.0045",
            "false, false, false, 0.014",
            "true, true, false, 0.0045",
            "true, false, true, 0.0"
    })
    void fluidAccumulationCoversEntityPolicyFallingFaceAndUnknownNeighbor(
            boolean playerEntity, boolean falling, boolean unknownNeighbor, double expectedMagnitude) {
        Player player = singleSectionPlayer();
        World world = player.world();
        ChunkSection section = sections(1, 1)[0];
        int x = unknownNeighbor ? 15 : 8;
        section.setBlock(x, 1, 8, falling ? 94 : 86);
        if (!unknownNeighbor) {
            section.setBlock(7, 1, 8, falling ? 94 : 86);
            section.setBlock(8, 1, 7, falling ? 94 : 86);
            section.setBlock(8, 1, 9, falling ? 94 : 86);
            section.setBlock(9, 1, 8, falling ? 1 : 93);
        }
        installChunk(world, 0, 0, new ChunkSection[]{section});
        Entity entity = playerEntity ? player : world.addEntity(
                2, EntityType.ZOMBIE, Vector3d.ZERO, Vector3d.ZERO, 0.0f, 0.0f);
        entity.setPosition(Vector3d.from(x + 0.5, 1.8, 8.5));

        World.FluidSample sample = world.fluid(entity, entity.boundingBox());

        assertEquals(expectedMagnitude, sample.flow().length(), 1.0E-6);
        if (falling) {
            assertTrue(sample.flow().getY() < 0.0);
        }
        if (unknownNeighbor) {
            assertEquals(0.0, sample.waterHeight());
        } else {
            assertEquals(8.0 / 9.0 - 0.8, sample.waterHeight(), 1.0E-6);
        }
    }

    @ParameterizedTest
    @CsvSource({
            "93, 1.0, IN_WATER",
            "86, 1.0, UNDER_WATER",
            "94, 1.0, UNDER_FLOWING_WATER",
            "0, 1.0, ON_LAND",
            "0, 3.0, IN_AIR"
    })
    void boatStatusUsesWaterSurfaceAndGroundFriction(
            int fluidState, double y, Vehicle.BoatStatus expected) {
        Player player = singleSectionPlayer();
        World world = player.world();
        ChunkSection section = sections(1, 1)[0];
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                section.setBlock(x, 0, z, 1);
            }
        }
        if (fluidState != 0) {
            section.setBlock(8, 1, 8, fluidState);
        }
        installChunk(world, 0, 0, new ChunkSection[]{section});
        Entity boat = world.addEntity(2, EntityType.OAK_BOAT,
                Vector3d.from(8.5, y, 8.5), Vector3d.ZERO, 0.0f, 0.0f);

        assertEquals(expected, world.boatEnvironment(boat).status());
    }

    @Test
    void controlledBoatConsumesCurrentAndShieldsPassengerBelowItsHull() {
        Player player = singleSectionPlayer();
        World world = player.world();
        ChunkSection section = sections(1, 1)[0];
        for (int x = 7; x <= 9; x++) {
            for (int z = 7; z <= 9; z++) {
                section.setBlock(x, 1, z, 86);
            }
        }
        for (int z = 7; z <= 9; z++) {
            section.setBlock(10, 1, z, 93);
        }
        installChunk(world, 0, 0, new ChunkSection[]{section});
        Entity boat = world.addEntity(2, EntityType.OAK_BOAT,
                Vector3d.from(8.5, 1.0, 8.5), Vector3d.ZERO, 0.0f, 0.0f);
        world.setPassengers(2, new int[]{1});
        double before = boat.position().getX();

        boat.tickVehicle(world);

        assertTrue(boat.position().getX() > before);

        boat.setPosition(Vector3d.from(8.5, 1.5, 8.5));
        player.setPosition(Vector3d.from(8.5, 1.0, 8.5));
        assertEquals(Vehicle.BoatStatus.IN_WATER, world.boatEnvironment(boat).status());
        World.FluidSample mounted = world.fluid(player, player.boundingBox());
        world.setPassengers(2, new int[0]);
        World.FluidSample unmounted = world.fluid(player, player.boundingBox());

        assertEquals(0.0, mounted.waterHeight());
        assertTrue(unmounted.waterHeight() > 0.0);
    }

    @ParameterizedTest
    @CsvSource({
            "PUSHING, false, false",
            "PULLING, false, false",
            "PULLING, true, true",
            "CANCELLED_MID_PUSH, true, false"
    })
    void pistonBlockEventsBuildTransitionsAndCleanCompletedStates(
            PistonValueType event, boolean sticky, boolean pullsBlock) {
        Player player = singleSectionPlayer();
        World world = player.world();
        ChunkSection section = sections(1, 1)[0];
        Decoder physics = Decoder.instance();
        String block = sticky ? "minecraft:sticky_piston" : "minecraft:piston";
        boolean extending = event == PistonValueType.PUSHING;
        int baseState = physics.blockState(block + "[extended=" + !extending + ",facing=east]");
        section.setBlock(8, 1, 8, baseState);
        if (extending) {
            section.setBlock(9, 1, 8, 1);
        } else {
            section.setBlock(9, 1, 8, physics.blockState(
                    "minecraft:piston_head[facing=east,short=false,type="
                            + (sticky ? "sticky" : "normal") + "]"));
            section.setBlock(10, 1, 8, 1);
        }
        installChunk(world, 0, 0, new ChunkSection[]{section});
        int blockId = physics.block(baseState).blockId();

        assertTrue(world.blockEvent(
                Vector3i.from(8, 1, 8), event, Direction.EAST, blockId).isEmpty());
        int movingState = physics.blockState("minecraft:moving_piston[facing=east,type="
                + (sticky ? "sticky" : "normal") + "]");
        if (extending) {
            assertEquals(movingState, world.blockState(9, 1, 8).orElseThrow());
            assertEquals(movingState, world.blockState(10, 1, 8).orElseThrow());
        } else {
            assertEquals(movingState, world.blockState(8, 1, 8).orElseThrow());
            assertEquals(pullsBlock ? movingState : 0,
                    world.blockState(9, 1, 8).orElseThrow());
            assertEquals(pullsBlock ? 0 : 1, world.blockState(10, 1, 8).orElseThrow());
        }

        world.tick();
        world.tick();
        if (extending) {
            assertEquals(1, world.blockState(10, 1, 8).orElseThrow());
        } else {
            assertEquals(Decoder.instance().blockState(
                            block + "[extended=false,facing=east]"),
                    world.blockState(8, 1, 8).orElseThrow());
            if (pullsBlock) {
                assertEquals(1, world.blockState(9, 1, 8).orElseThrow());
            }
        }

        Vector3i completedPosition = extending
                ? Vector3i.from(10, 1, 8)
                : pullsBlock ? Vector3i.from(9, 1, 8) : Vector3i.from(8, 1, 8);
        world.updateBlock(new BlockChangeEntry(completedPosition, 0));
        AABB probe = new AABB(
                completedPosition.getX() + 0.1, completedPosition.getY() + 0.1,
                completedPosition.getZ() + 0.1, completedPosition.getX() + 0.9,
                completedPosition.getY() + 0.9, completedPosition.getZ() + 0.9);
        assertTrue(world.collisions(player, probe).isEmpty());
    }

    @Test
    void pistonReactionDoesNotReuseOrdinaryEntityPushability() {
        Player player = singleSectionPlayer();
        World world = player.world();
        installChunk(world, 0, 0, sections(1, 1));
        Entity armorStand = world.addEntity(2, EntityType.ARMOR_STAND,
                Vector3d.from(8.1, 1.0, 8.5), Vector3d.ZERO, 0.0f, 0.0f);
        Entity ignored = world.addEntity(3, EntityType.AREA_EFFECT_CLOUD,
                Vector3d.from(8.1, 1.0, 8.5), Vector3d.ZERO, 0.0f, 0.0f);
        NbtMap piston = NbtMap.builder()
                .putCompound("blockState", NbtMap.builder()
                        .putString("Name", "minecraft:stone").build())
                .putInt("facing", Direction.EAST.ordinal())
                .putFloat("progress", 0.0f)
                .putBoolean("extending", true)
                .putBoolean("source", false)
                .build();
        world.blockEntity(Vector3i.from(8, 1, 8), BlockEntityType.PISTON, piston);

        world.tick();

        assertTrue(armorStand.position().getX() > 8.1);
        assertEquals(8.1, ignored.position().getX(), 1.0E-9);
    }

    @Test
    void retractingSourcePushesEntitiesOutOfThePistonBase() {
        Player player = singleSectionPlayer();
        World world = player.world();
        installChunk(world, 0, 0, sections(1, 1));
        player.setPosition(Vector3d.from(9.2, 1.0, 8.5));
        NbtMap piston = NbtMap.builder()
                .putCompound("blockState", NbtMap.builder()
                        .putString("Name", "minecraft:piston")
                        .putCompound("Properties", NbtMap.builder()
                                .putString("extended", "false")
                                .putString("facing", "east")
                                .build())
                        .build())
                .putInt("facing", Direction.EAST.ordinal())
                .putFloat("progress", 0.0f)
                .putBoolean("extending", false)
                .putBoolean("source", true)
                .build();
        world.blockEntity(Vector3i.from(8, 1, 8), BlockEntityType.PISTON, piston);

        world.tick();

        AABB base = new AABB(8.0, 1.0, 8.0, 9.0, 2.0, 9.0);
        assertFalse(base.intersects(player.boundingBox()),
                () -> "player remained inside base at " + player.position());
    }

    @ParameterizedTest
    @CsvSource({
            "12532, 8.1, 1.2, 1.0",
            "21816, 7.6, 2.0, 0.5"
    })
    void pistonSlimeVelocityAndHoneyCarryUseTheirDistinctRules(
            int movedState, double entityX, double entityY, double expected) {
        Player player = singleSectionPlayer();
        World world = player.world();
        ChunkSection section = sections(1, 1)[0];
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                section.setBlock(x, 0, z, 1);
            }
        }
        installChunk(world, 0, 0, new ChunkSection[]{section});
        player.setPosition(Vector3d.from(entityX, entityY, 8.5));
        player.setVelocity(Vector3d.ZERO);
        player.setCollisionFlags(true, false);
        Block moved = Decoder.instance().block(movedState);
        String name = moved.stateKey().substring(0, moved.stateKey().indexOf('[') < 0
                ? moved.stateKey().length() : moved.stateKey().indexOf('['));
        NbtMap piston = NbtMap.builder()
                .putCompound("blockState", NbtMap.builder().putString("Name", name).build())
                .putInt("facing", Direction.EAST.ordinal())
                .putFloat("progress", 0.0f)
                .putBoolean("extending", true)
                .putBoolean("source", false)
                .build();
        world.blockEntity(Vector3i.from(8, 1, 8), BlockEntityType.PISTON, piston);
        double before = player.position().getX();

        world.tick();

        if (moved.behavior() == Block.Behavior.SLIME) {
            assertEquals(expected, player.velocity().getX(), 1.0E-7);
        } else {
            assertEquals(expected, player.position().getX() - before, 1.0E-7);
        }
    }

    private static Player singleSectionPlayer() {
        Player player = player();
        World world = player.world();
        world.registry(DIMENSION_TYPES, List.of(registryEntry(OVERWORLD, 0, 16)));
        world.registry(BIOMES, List.of(
                new RegistryEntry(Key.key("minecraft", "plains"), NbtMap.EMPTY)));
        player.initializeGame(1, spawnInfo(0, OVERWORLD));
        return player;
    }

    private static World configuredWorld() {
        World world = player().world();
        world.registry(DIMENSION_TYPES, List.of(
                registryEntry(OVERWORLD, -64, 384),
                registryEntry(NETHER, 0, 256)));
        world.registry(BIOMES, List.of(
                new RegistryEntry(Key.key("minecraft", "plains"), NbtMap.EMPTY),
                new RegistryEntry(Key.key("minecraft", "desert"), NbtMap.EMPTY)));
        return world;
    }

    private static RegistryEntry registryEntry(Key id, int minimumY, int height) {
        return new RegistryEntry(
                id,
                NbtMap.builder().putInt("min_y", minimumY).putInt("height", height).build());
    }

    private static ChunkSection[] sections(int count, int biomeCount) {
        ChunkSection[] sections = new ChunkSection[count];
        for (int index = 0; index < sections.length; index++) {
            sections[index] = new ChunkSection(0, 32366, 0, biomeCount);
        }
        return sections;
    }

    private static void installChunk(World world, int x, int z, ChunkSection[] sections) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            for (ChunkSection section : sections) {
                MinecraftTypes.writeChunkSection(buffer, section);
            }
            byte[] encoded = new byte[buffer.readableBytes()];
            buffer.readBytes(encoded);
            assertTrue(world.decodeAndInstallChunk(
                    x, z, encoded, new BlockEntityInfo[0]).installed());
        } finally {
            buffer.release();
        }
    }

    private static PlayerSpawnInfo spawnInfo(int dimension, Key world) {
        return new PlayerSpawnInfo(
                dimension, world, 0L, GameMode.SURVIVAL,
                GameMode.SURVIVAL, false, false, null, 0, 63);
    }

    private static Player player() {
        com.velocitypowered.api.proxy.Player velocityPlayer =
                mock(com.velocitypowered.api.proxy.Player.class);
        when(velocityPlayer.getUniqueId()).thenReturn(UUID.randomUUID());
        return new Player(velocityPlayer);
    }
}
