package com.fakeplayerproxy.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fakeplayerproxy.world.data.Decoder;
import com.fakeplayerproxy.world.entity.Entity;
import com.fakeplayerproxy.world.player.Player;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.backend.VelocityServerConnection;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.List;
import java.util.UUID;

import net.kyori.adventure.key.Key;
import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftTypes;
import org.geysermc.mcprotocollib.protocol.data.game.KnownPack;
import org.geysermc.mcprotocollib.protocol.data.game.RegistryEntry;
import org.geysermc.mcprotocollib.protocol.data.game.chunk.ChunkSection;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerSpawnInfo;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockEntityInfo;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundKeepAlivePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatAckPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientTickEndPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundPlayerLoadedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundMoveVehiclePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.serverbound.ServerboundFinishConfigurationPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.serverbound.ServerboundSelectKnownPacks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class AutomationServiceTest {
    private final MinecraftConnection backend = mock(MinecraftConnection.class);
    private final EventLoop eventLoop = mock(EventLoop.class);
    private final Channel channel = mock(Channel.class);
    private final Player player = player();
    private final AutomationService service = player.automationService();

    @BeforeEach
    void prepareConnection() {
        when(backend.eventLoop()).thenReturn(eventLoop);
        when(backend.getChannel()).thenReturn(channel);
        when(channel.isActive()).thenReturn(true);
        when(eventLoop.inEventLoop()).thenReturn(true);
        org.mockito.Mockito.doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(eventLoop).execute(any(Runnable.class));
        registry(Key.key("minecraft", "dimension_type"), List.of(new RegistryEntry(
                Key.key("minecraft", "overworld"),
                NbtMap.builder().putInt("min_y", 0).putInt("height", 16).build())));
        registry(Key.key("minecraft", "worldgen/biome"), List.of(
                new RegistryEntry(Key.key("minecraft", "plains"), NbtMap.EMPTY)));
        player.initializeGame(17, spawnInfo());
        service.enterGame();
    }

    @Test
    void keepsTheExactSupportedFrontendKnownPackSelection() {
        KnownPack core = new KnownPack("minecraft", "core", "26.2");
        service.startConfiguration();
        service.offerKnownPacks(backend, List.of(core));

        ServerboundSelectKnownPacks selected = service.selectKnownPacks(List.of(core));
        registry(Key.key("minecraft", "dimension_type"), List.of(new RegistryEntry(
                Key.key("minecraft", "overworld"), null)));
        registry(Key.key("minecraft", "worldgen/biome"), List.of(
                new RegistryEntry(Key.key("minecraft", "plains"), NbtMap.EMPTY)));
        player.initializeGame(17, spawnInfo());
        service.enterGame();

        assertEquals(List.of(core), selected.getKnownPacks());
        assertTrue(service.shadow().join());
    }

    @Test
    void selectsNoKnownPacksWhenThePluginDoesNotSupportTheCompleteOffer() {
        KnownPack core = new KnownPack("minecraft", "core", "26.2");
        KnownPack extension = new KnownPack("example", "extension", "1");
        service.startConfiguration();
        service.offerKnownPacks(backend, List.of(core, extension));

        ServerboundSelectKnownPacks selected = service.selectKnownPacks(List.of(core, extension));
        registry(Key.key("minecraft", "dimension_type"), List.of(new RegistryEntry(
                Key.key("minecraft", "overworld"), null)));
        registry(Key.key("minecraft", "worldgen/biome"), List.of(
                new RegistryEntry(Key.key("minecraft", "plains"), NbtMap.EMPTY)));
        player.initializeGame(17, spawnInfo());
        service.enterGame();

        assertTrue(selected.getKnownPacks().isEmpty());
        assertFalse(service.shadow().join());
        assertFalse(service.isShadow());
    }

    @Test
    void selectsNoKnownPacksWhenTheFrontendSelectionDiffersFromTheOffer() {
        KnownPack core = new KnownPack("minecraft", "core", "26.2");
        service.startConfiguration();
        service.offerKnownPacks(backend, List.of(core));

        ServerboundSelectKnownPacks selected = service.selectKnownPacks(List.of());

        assertTrue(selected.getKnownPacks().isEmpty());
    }

    @Test
    void clearsKnownPackOfferForTheNextConfiguration() {
        KnownPack core = new KnownPack("minecraft", "core", "26.2");
        service.startConfiguration();
        service.offerKnownPacks(backend, List.of(core));
        assertEquals(List.of(core), service.selectKnownPacks(List.of(core)).getKnownPacks());

        service.startConfiguration();
        ServerboundSelectKnownPacks selectedWithoutOffer = service.selectKnownPacks(List.of(core));

        assertTrue(selectedWithoutOffer.getKnownPacks().isEmpty());
    }

    @Test
    void clearsKnownPackSelectionForTheNextConfiguration() {
        KnownPack core = new KnownPack("minecraft", "core", "26.2");
        service.startConfiguration();
        service.offerKnownPacks(backend, List.of(core));
        assertEquals(List.of(core), service.selectKnownPacks(List.of(core)).getKnownPacks());

        service.startConfiguration();
        registry(Key.key("minecraft", "dimension_type"), List.of(new RegistryEntry(
                Key.key("minecraft", "overworld"), null)));
        registry(Key.key("minecraft", "worldgen/biome"), List.of(
                new RegistryEntry(Key.key("minecraft", "plains"), NbtMap.EMPTY)));
        player.initializeGame(17, spawnInfo());
        service.enterGame();

        assertFalse(service.shadow().join());
        assertFalse(service.isShadow());
    }

    @Test
    void sendsTheSupportedKnownPackOfferDuringShadowConfiguration() {
        service.shadow();
        service.startConfiguration();
        clearInvocations(backend);
        KnownPack core = new KnownPack("minecraft", "core", "26.2");

        service.offerKnownPacks(backend, List.of(core));

        verify(backend).sendPacket(argThat(packet -> packet instanceof ServerboundSelectKnownPacks selected
                && selected.getKnownPacks().equals(List.of(core))), eq(false));

        clearInvocations(backend);
        service.offerKnownPacks(backend, List.of(
                core, new KnownPack("example", "extension", "1")));

        verify(backend).sendPacket(argThat(packet -> packet instanceof ServerboundSelectKnownPacks selected
                && selected.getKnownPacks().isEmpty()), eq(false));
    }

    @Test
    void doesNotRespondBeforeShadow() {
        service.keepAlive(backend, 42L);

        verify(backend, never()).sendPacket(any(Packet.class), eq(false));
    }

    @Test
    void shadowKeepsProtocolAliveAndTicks() {
        assertTrue(service.shadow().join());

        service.keepAlive(backend, 42L);
        service.tick(backend);

        verify(backend).sendPacket(
                argThat(packet -> packet instanceof ServerboundKeepAlivePacket keepAlive
                        && keepAlive.getPingId() == 42L),
                eq(false));
        verify(backend).sendPacket(ServerboundClientTickEndPacket.INSTANCE);
    }

    @Test
    void acknowledgesEachBatchOfDistinctSignedMessages() {
        service.shadow();

        for (int index = 0; index < 65; index++) {
            service.chat(backend, new byte[]{(byte) index});
        }
        service.chat(backend, new byte[]{64});

        verify(backend, times(1)).sendPacket(
                argThat(packet -> packet instanceof ServerboundChatAckPacket ack
                        && ack.getOffset() == 65),
                eq(false));
    }

    @Test
    void closeCancelsFutureResponses() {
        service.shadow();
        service.close();

        service.keepAlive(backend, 7L);

        assertFalse(service.isShadow());
        verify(backend, never()).sendPacket(
                argThat(packet -> packet instanceof ServerboundKeepAlivePacket), eq(false));
    }

    @Test
    void configurationFinishWaitsForTheNewGameState() {
        service.shadow();
        service.startConfiguration();
        clearInvocations(backend);

        service.tick(backend);
        verify(backend, never()).sendPacket(ServerboundClientTickEndPacket.INSTANCE);

        service.markConfigurationFinish();
        service.finishConfiguration(backend);
        service.tick(backend);

        verify(backend).sendPacket(ServerboundFinishConfigurationPacket.INSTANCE, false);
        verify(backend).sendPacket(ServerboundClientTickEndPacket.INSTANCE);
    }

    @Test
    void deathDoesNotStopClientTickEnd() {
        prepareLoadedWorld();
        service.shadow();
        service.playerLoaded();
        player.setHealth(0.0f);
        clearInvocations(backend);

        service.tick(backend);

        verify(backend).sendPacket(ServerboundClientTickEndPacket.INSTANCE);
        verify(backend, never()).sendPacket(argThat(packet -> packet instanceof ServerboundMovePlayerPosPacket));
    }

    @Test
    void motionAndExplosionProduceContinuousMovement() {
        prepareLoadedWorld();
        service.shadow();
        service.playerLoaded();
        player.setVelocity(Vector3d.from(0.2, 0.0, 0.0));
        player.addVelocity(Vector3d.from(0.2, 0.0, 0.0));
        clearInvocations(backend);

        service.tick(backend);
        service.tick(backend);

        verify(backend).sendPacket(argThat(packet -> packet instanceof ServerboundMovePlayerPosPacket movement
                && movement.getX() == 8.4));
        verify(backend, times(2)).sendPacket(argThat(packet -> packet instanceof ServerboundMovePlayerPosPacket));
    }

    @Test
    void pendingInterpolationDoesNotOverwriteLaterMotion() {
        prepareLoadedWorld();
        Entity zombie = player.world().addEntity(18, EntityType.ZOMBIE,
                Vector3d.ZERO, Vector3d.ZERO, 0.0f, 0.0f);

        zombie.interpolate(Vector3d.from(0.6, 0.0, 0.0),
                true, 0.0f, 0.0f, false, false);
        zombie.setVelocity(Vector3d.from(0.25, 0.0, 0.0));
        player.world().tick();
        player.world().tick();
        player.world().tick();

        assertEquals(Vector3d.from(0.25, 0.0, 0.0), zombie.velocity());
    }

    @Test
    void entityPositionSyncDoesNotApplyPacketDeltaVelocity() {
        prepareLoadedWorld();
        Entity zombie = player.world().addEntity(18, EntityType.ZOMBIE,
                Vector3d.ZERO, Vector3d.from(0.25, 0.5, 0.75), 0.0f, 0.0f);

        zombie.positionSync(Vector3d.from(4.0, 5.0, 6.0), 90.0f, 15.0f, true, false);

        assertEquals(Vector3d.from(0.25, 0.5, 0.75), zombie.velocity());
        assertEquals(Vector3d.from(4.0, 5.0, 6.0), zombie.position());
    }

    @Test
    void sendsPlayerLoadedOnceWhenInitialChunkIsReady() {
        prepareLoadedWorld();
        service.shadow();
        clearInvocations(backend);

        service.tick(backend);
        service.tick(backend);

        verify(backend, times(1)).sendPacket(ServerboundPlayerLoadedPacket.INSTANCE);
    }

    @Test
    void forcesAPositionPacketAfterTwentyPhysicsTicks() {
        prepareLoadedWorld();
        service.shadow();
        service.playerLoaded();
        clearInvocations(backend);

        for (int tick = 0; tick < 20; tick++) {
            service.tick(backend);
        }

        verify(backend).sendPacket(argThat(packet -> packet instanceof ServerboundMovePlayerPosPacket movement
                && movement.getX() == 8.0
                && movement.getY() == 1.0
                && movement.getZ() == 8.0));
        verify(backend, times(20)).sendPacket(ServerboundClientTickEndPacket.INSTANCE);
    }

    @Test
    void positionCorrectionUsesTheVanillaFalseCollisionBaseline() {
        player.clientStatus(true, true);
        service.shadow();
        clearInvocations(backend);

        player.applyServerPosition(Vector3d.from(4.0, 5.0, 6.0),
                Vector3d.ZERO, 90.0f, 10.0f, List.of());
        service.acknowledgePosition(backend, 3);

        verify(backend).sendPacket(argThat(packet -> packet instanceof ServerboundMovePlayerPosRotPacket movement
                && !movement.isOnGround()
                && !movement.isHorizontalCollision()
                && movement.getX() == 4.0
                && movement.getY() == 5.0
                && movement.getZ() == 6.0
                && movement.getYaw() == 90.0f
                && movement.getPitch() == 10.0f), eq(true));
    }

    @Test
    void correctionsRotateVelocityAndAcknowledgeAuthoritativeTransforms() {
        player.applyServerPosition(Vector3d.ZERO, Vector3d.from(1.0, 0.0, 0.0),
                0.0f, 0.0f, false);
        service.shadow();

        player.applyServerPosition(Vector3d.ZERO, Vector3d.ZERO, 90.0f, 0.0f,
                List.of(PositionElement.ROTATE_DELTA,
                        PositionElement.DELTA_X, PositionElement.DELTA_Y, PositionElement.DELTA_Z));
        service.acknowledgePosition(backend, 3);

        assertEquals(0.0, player.velocity().getX(), 1.0E-9);
        assertEquals(1.0, player.velocity().getZ(), 1.0E-9);

        clearInvocations(backend);
        player.applyServerRotation(15.0f, true, -5.0f, true);
        service.acknowledgeRotation(backend);

        verify(backend).sendPacket(argThat(packet -> packet instanceof ServerboundMovePlayerRotPacket movement
                && !movement.isOnGround()
                && !movement.isHorizontalCollision()
                && movement.getYaw() == 105.0f
                && movement.getPitch() == -5.0f), eq(true));

        Entity boat = player.world().addEntity(2, EntityType.OAK_BOAT,
                Vector3d.ZERO, Vector3d.ZERO, 0.0f, 0.0f);
        player.world().setPassengers(2, new int[]{player.id()});
        clearInvocations(backend);

        player.applyVehiclePosition(Vector3d.from(4.0, 5.0, 6.0), 30.0f, 10.0f);
        service.acknowledgeVehicle(backend);

        assertEquals(Vector3d.from(4.0, 5.0, 6.0), boat.position());
        verify(backend).sendPacket(argThat(packet -> packet instanceof ServerboundMoveVehiclePacket), eq(true));
    }

    @Test
    void lookPreservesTheKnownCollisionFlags() {
        player.clientStatus(true, true);

        assertTrue(service.look(45.0f, -15.0f).isSuccess());

        verify(backend).sendPacket(argThat(packet -> packet instanceof ServerboundMovePlayerRotPacket movement
                && movement.isOnGround()
                && movement.isHorizontalCollision()
                && movement.getYaw() == 45.0f
                && movement.getPitch() == -15.0f));
    }

    private void registry(Key key, List<RegistryEntry> entries) {
        player.world().registry(key, Decoder.instance().completeDimensionTypes(
                key, entries, service.selectedKnownPacksProvideFixedRegistry()));
    }

    private void prepareLoadedWorld() {
        registry(Key.key("minecraft", "dimension_type"), List.of(new RegistryEntry(
                Key.key("minecraft", "overworld"),
                NbtMap.builder().putInt("min_y", 0).putInt("height", 16).build())));
        registry(Key.key("minecraft", "worldgen/biome"), List.of(
                new RegistryEntry(Key.key("minecraft", "plains"), NbtMap.EMPTY)));
        player.initializeGame(17, spawnInfo());
        service.enterGame();
        player.applyServerPosition(Vector3d.from(8.0, 1.0, 8.0),
                Vector3d.ZERO, 180.0f, 0.0f, List.of());
        service.acknowledgePosition(backend, 1);
        player.world().levelChunksLoadStarted();
        ChunkSection section = new ChunkSection(0, 32366, 0, 1);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                section.setBlock(x, 0, z, 1);
            }
        }
        ByteBuf buffer = Unpooled.buffer();
        try {
            MinecraftTypes.writeChunkSection(buffer, section);
            byte[] encoded = new byte[buffer.readableBytes()];
            buffer.readBytes(encoded);
            assertTrue(player.world().decodeAndInstallChunk(
                    0, 0, encoded, new BlockEntityInfo[0]).installed());
        } finally {
            buffer.release();
        }
    }

    private static PlayerSpawnInfo spawnInfo() {
        return new PlayerSpawnInfo(
                0,
                net.kyori.adventure.key.Key.key("minecraft", "overworld"),
                0L,
                GameMode.SURVIVAL,
                GameMode.SURVIVAL,
                false,
                false,
                null,
                0,
                63);
    }

    private Player player() {
        ConnectedPlayer velocityPlayer = mock(ConnectedPlayer.class);
        when(velocityPlayer.getUniqueId()).thenReturn(UUID.randomUUID());
        MinecraftConnection frontend = mock(MinecraftConnection.class);
        when(velocityPlayer.getConnection()).thenReturn(frontend);
        when(frontend.eventLoop()).thenReturn(eventLoop);
        VelocityServerConnection serverConnection = mock(VelocityServerConnection.class);
        when(velocityPlayer.getConnectionInFlightOrConnectedServer()).thenReturn(serverConnection);
        when(serverConnection.getConnection()).thenReturn(backend);
        return new Player(velocityPlayer);
    }
}
