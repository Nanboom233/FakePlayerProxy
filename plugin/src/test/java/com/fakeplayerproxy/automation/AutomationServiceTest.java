package com.fakeplayerproxy.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;

import com.fakeplayerproxy.utils.Result;
import com.fakeplayerproxy.world.data.Decoder;
import com.fakeplayerproxy.world.entity.Entity;
import com.fakeplayerproxy.world.player.Player;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.backend.VelocityServerConnection;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
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
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerState;
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
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerActionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundPlayerInputPacket;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerAction;
import org.geysermc.mcprotocollib.protocol.packet.configuration.serverbound.ServerboundFinishConfigurationPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.serverbound.ServerboundSelectKnownPacks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

final class AutomationServiceTest {
    private final MinecraftConnection backend = mock(MinecraftConnection.class);
    private final EventLoop eventLoop = mock(EventLoop.class);
    private final Channel channel = mock(Channel.class);
    private final ConnectedPlayer velocityPlayer = mock(ConnectedPlayer.class);
    private final VelocityServerConnection serverConnection = mock(VelocityServerConnection.class);
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
        service.playerLoaded();
    }

    @Test
    void successfulConnectionThatEndsBeforeReadyPlaySchedulesAnotherAttempt() {
        prepareLoadedWorld();
        assertTrue(service.shadow().join());
        service.enableAutoReconnect(new byte[]{1});
        ConnectionRequestBuilder.Result result = mock(ConnectionRequestBuilder.Result.class);
        when(result.isSuccessful()).thenReturn(true);
        when(velocityPlayer.reconnectShadow(eq(serverConnection), any(byte[].class)))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(result));
        when(channel.isActive()).thenReturn(false);

        assertTrue(service.prepareReconnect());
        org.junit.jupiter.api.Assertions.assertNull(service.tickReconnect());
        assertInstanceOf(IllegalStateException.class, service.tickReconnect());
        assertEquals(1, service.getReconnectAttempt());
        assertEquals(10L, service.nextReconnectDelaySeconds());
    }

    @Test
    void failedReconnectReturnsTheOriginalFailureAndSchedulesADelay() {
        prepareLoadedWorld();
        assertTrue(service.shadow().join());
        service.enableAutoReconnect(new byte[]{1});
        IllegalStateException failure = new IllegalStateException("reconnect failed");
        when(velocityPlayer.reconnectShadow(eq(serverConnection), any(byte[].class)))
                .thenReturn(java.util.concurrent.CompletableFuture.failedFuture(failure));

        assertTrue(service.prepareReconnect());
        org.junit.jupiter.api.Assertions.assertNull(service.tickReconnect());
        org.junit.jupiter.api.Assertions.assertSame(failure, service.tickReconnect());
        org.junit.jupiter.api.Assertions.assertNull(service.tickReconnect());
        verify(velocityPlayer, times(1)).reconnectShadow(eq(serverConnection), any(byte[].class));
    }

    @Test
    void cancelledReconnectCanRetryWithoutAddingADelay() {
        prepareLoadedWorld();
        assertTrue(service.shadow().join());
        service.enableAutoReconnect(new byte[]{1});
        var cancelled = new java.util.concurrent.CompletableFuture<ConnectionRequestBuilder.Result>();
        cancelled.cancel(false);
        when(velocityPlayer.reconnectShadow(eq(serverConnection), any(byte[].class)))
                .thenReturn(cancelled, new java.util.concurrent.CompletableFuture<>());

        assertTrue(service.prepareReconnect());
        org.junit.jupiter.api.Assertions.assertNull(service.tickReconnect());
        org.junit.jupiter.api.Assertions.assertNull(service.tickReconnect());
        org.junit.jupiter.api.Assertions.assertNull(service.tickReconnect());
        verify(velocityPlayer, times(2)).reconnectShadow(eq(serverConnection), any(byte[].class));
    }

    @Test
    void shadowReportsEventLoopSubmissionAndBodyFailures() {
        Player owner = mock(Player.class);
        EventLoop loop = mock(EventLoop.class);
        when(owner.eventLoop()).thenReturn(loop);
        when(loop.inEventLoop()).thenReturn(false);
        doThrow(new java.util.concurrent.RejectedExecutionException("closed"))
                .when(loop).execute(any(Runnable.class));
        AutomationService scheduler = new AutomationService(owner);

        var submission = org.junit.jupiter.api.Assertions.assertThrows(
                java.util.concurrent.CompletionException.class, () -> scheduler.shadow().join());
        assertInstanceOf(java.util.concurrent.RejectedExecutionException.class,
                submission.getCause());

        EventLoop bodyLoop = mock(EventLoop.class);
        when(owner.eventLoop()).thenReturn(bodyLoop);
        when(bodyLoop.inEventLoop()).thenReturn(false, true);
        org.mockito.Mockito.doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(bodyLoop).execute(any(Runnable.class));
        when(owner.backendConnection()).thenThrow(new IllegalStateException("missing backend"));

        var body = org.junit.jupiter.api.Assertions.assertThrows(
                java.util.concurrent.CompletionException.class, () -> scheduler.shadow().join());
        assertInstanceOf(IllegalStateException.class, body.getCause());
    }

    @Test
    void oneShotAndIntervalWaitForTheirActionTicks() {
        assertInstanceOf(Result.Success.class, service.dropSelectedItem(false, ActionMode.ONCE, 0));
        verify(backend, never()).sendPacket(argThat(packet -> packet instanceof ServerboundPlayerActionPacket));

        service.tick(backend);

        verify(backend, times(1)).sendPacket(argThat(packet -> packet instanceof ServerboundPlayerActionPacket action
                && action.getAction() == PlayerAction.DROP_ITEM));
        clearInvocations(backend);
        service.dropSelectedItem(false, ActionMode.INTERVAL, 3);
        service.tick(backend);
        service.tick(backend);
        verify(backend, never()).sendPacket(argThat(packet -> packet instanceof ServerboundPlayerActionPacket));
        service.tick(backend);
        verify(backend, times(1)).sendPacket(argThat(packet -> packet instanceof ServerboundPlayerActionPacket));
    }

    @Test
    void movementCommandsRejectANonShadowOwner() {
        assertInstanceOf(Result.Failure.class, service.move("forward"));
        assertInstanceOf(Result.Failure.class, service.jump(ActionMode.ONCE, 0));
        assertInstanceOf(Result.Failure.class, service.setSprint(true));
        assertInstanceOf(Result.Failure.class, service.setSprint(false));

        verify(backend, never()).sendPacket(argThat(packet ->
                packet instanceof ServerboundPlayerInputPacket
                        || packet instanceof ServerboundPlayerCommandPacket));
    }

    @Test
    void intervalOneCleansUpBeforeEveryRunButContinuousDoesNot() {
        Player owner = mock(Player.class);
        when(owner.eventLoop()).thenReturn(eventLoop);
        when(owner.backendConnection()).thenReturn(backend);
        when(owner.attack(backend, false)).thenReturn(new Result.Success<>(false));
        when(owner.attack(backend, true)).thenReturn(new Result.Success<>(false));
        AutomationService scheduler = new AutomationService(owner);
        scheduler.enterGame();
        scheduler.playerLoaded();

        scheduler.attack(ActionMode.INTERVAL, 1);
        scheduler.tick(backend);
        scheduler.tick(backend);
        verify(owner, times(2)).inactiveAttack(backend);
        clearInvocations(owner);
        scheduler.attack(ActionMode.CONTINUOUS, 0);
        clearInvocations(owner);
        scheduler.tick(backend);
        scheduler.tick(backend);
        verify(owner, never()).inactiveAttack(backend);
    }

    @Test
    void useRunsBeforeAttackAndRetriesAfterSuccessfulAttackAndStopCleansEverything() {
        Player owner = mock(Player.class);
        when(owner.eventLoop()).thenReturn(eventLoop);
        when(owner.backendConnection()).thenReturn(backend);
        doReturn(new Result.Success<Boolean, String>(false), new Result.Success<Boolean, String>(true))
                .when(owner).use(backend);
        when(owner.attack(backend, false)).thenReturn(new Result.Success<>(true));
        when(owner.stopActions(backend)).thenReturn(new Result.Success<>(null));
        AutomationService scheduler = new AutomationService(owner);
        scheduler.enterGame();
        scheduler.playerLoaded();
        scheduler.use(ActionMode.ONCE, 0);
        scheduler.attack(ActionMode.ONCE, 0);

        scheduler.tick(backend);

        InOrder order = inOrder(owner);
        order.verify(owner).use(backend);
        order.verify(owner).attack(backend, false);
        order.verify(owner).use(backend);
        scheduler.jump(ActionMode.CONTINUOUS, 0);
        clearInvocations(owner);
        scheduler.stopActions();
        verify(owner).inactiveUse(backend);
        verify(owner).inactiveAttack(backend);
        verify(owner).inactiveJump(backend);
        verify(owner).stopActions(backend);
    }

    @Test
    void passiveStatePrecedesAttackAndContinuousUseKeepsItsHeldAction() {
        Player owner = mock(Player.class);
        when(owner.eventLoop()).thenReturn(eventLoop);
        when(owner.backendConnection()).thenReturn(backend);
        when(owner.attack(backend, false)).thenReturn(new Result.Success<>(true));
        when(owner.use(backend)).thenReturn(new Result.Success<>(true));
        AutomationService scheduler = new AutomationService(owner);
        scheduler.enterGame();
        scheduler.playerLoaded();
        scheduler.attack(ActionMode.ONCE, 0);

        scheduler.tick(backend);

        InOrder order = inOrder(owner);
        order.verify(owner).passiveTick();
        order.verify(owner).attack(backend, false);

        scheduler.use(ActionMode.CONTINUOUS, 0);
        clearInvocations(owner);
        scheduler.tick(backend);
        scheduler.tick(backend);

        verify(owner, times(1)).use(backend);
        verify(owner, never()).inactiveUse(backend);
    }

    @Test
    void continuousUseOwnershipTracksTheScheduledActionLifecycle() {
        assertFalse(service.ownsContinuousUse());

        service.use(ActionMode.CONTINUOUS, 0);
        assertTrue(service.ownsContinuousUse());

        service.use(ActionMode.ONCE, 0);
        assertFalse(service.ownsContinuousUse());

        service.use(ActionMode.CONTINUOUS, 0);
        service.stopActions();
        assertFalse(service.ownsContinuousUse());

        service.use(ActionMode.CONTINUOUS, 0);
        service.startConfiguration();
        assertFalse(service.ownsContinuousUse());

        service.enterGame();
        service.use(ActionMode.CONTINUOUS, 0);
        service.close();
        assertFalse(service.ownsContinuousUse());
    }

    @Test
    void dismountHoldsShiftForOneCompleteServiceTickThenRestoresCurrentInput() {
        prepareLoadedWorld();
        service.playerLoaded();
        player.setInputState(Player.InputState.CLEAR.withMovement("forward"));

        assertInstanceOf(Result.Success.class, service.dismount());
        verify(backend).sendPacket(argThat(packet ->
                packet instanceof ServerboundPlayerInputPacket input && input.isShift()));
        clearInvocations(backend);

        service.tick(backend);

        verify(backend, never()).sendPacket(argThat(packet ->
                packet instanceof ServerboundPlayerInputPacket));
        player.setInputState(Player.InputState.CLEAR.withMovement("right"));
        service.tick(backend);

        verify(backend).sendPacket(argThat(packet ->
                packet instanceof ServerboundPlayerInputPacket input
                        && input.isRight() && !input.isShift()));
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
                && selected.getKnownPacks().equals(List.of(core))), eq(true));

        clearInvocations(backend);
        service.offerKnownPacks(backend, List.of(
                core, new KnownPack("example", "extension", "1")));

        verify(backend).sendPacket(argThat(packet -> packet instanceof ServerboundSelectKnownPacks selected
                && selected.getKnownPacks().isEmpty()), eq(true));
    }

    @Test
    void responseWriteFailureDoesNotEscapeTheOwningEventLoop() {
        service.shadow();
        doThrow(new IllegalArgumentException("test encoding failure"))
                .when(backend).sendPacket(any(ServerboundSelectKnownPacks.class), eq(true));

        assertDoesNotThrow(() -> service.offerKnownPacks(
                backend, List.of(new KnownPack("minecraft", "core", "26.2"))));
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
    void shadowClearsFrontendMovementAndSprintIntent() {
        prepareLoadedWorld();
        player.setInputState(Player.InputState.CLEAR
                .withMovement("forward")
                .withSprint(true));

        assertTrue(service.shadow().join());
        service.playerLoaded();
        Vector3d position = player.position();
        clearInvocations(backend);

        service.tick(backend);

        assertEquals(position, player.position());
        verify(backend, never()).sendPacket(argThat(packet ->
                packet instanceof ServerboundPlayerCommandPacket command
                        && command.getState() == PlayerState.START_SPRINTING));
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
        service.playerLoaded();
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

        assertInstanceOf(Result.Success.class, service.look(45.0f, -15.0f));

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
        when(velocityPlayer.getUniqueId()).thenReturn(UUID.randomUUID());
        MinecraftConnection frontend = mock(MinecraftConnection.class);
        when(velocityPlayer.getConnection()).thenReturn(frontend);
        when(frontend.eventLoop()).thenReturn(eventLoop);
        when(velocityPlayer.getConnectionInFlightOrConnectedServer()).thenReturn(serverConnection);
        when(serverConnection.getConnection()).thenReturn(backend);
        return new Player(velocityPlayer);
    }
}
