package com.fakeplayerproxy.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.fakeplayerproxy.utils.Result;
import com.velocitypowered.proxy.connection.backend.VelocityServerConnection;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.api.event.connection.ClientboundPacketEvent;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.ScheduledFuture;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundTransferPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundDisconnectPacket;
import net.kyori.adventure.text.Component;

final class AutomationManagerTest {
    private final Logger logger = mock(Logger.class);
    private final AutomationManager manager = new AutomationManager(logger);

    @Test
    void doesNotRegisterAPlayerWithoutTheRelayedBackend() {
        ConnectedPlayer player = player(UUID.randomUUID(), false);

        manager.register(player).join();

        assertNull(manager.get(player));
    }

    @Test
    void freshLoginClosesOnlyTheOldService() {
        UUID uuid = UUID.randomUUID();
        ConnectedPlayer oldPlayer = player(uuid, true);
        ConnectedPlayer freshPlayer = player(uuid, true);

        manager.register(oldPlayer).join();
        AutomationService oldService = manager.get(oldPlayer).automationService();
        MinecraftConnection oldBackend = oldPlayer
                .getConnectionInFlightOrConnectedServer()
                .getConnection();
        manager.register(freshPlayer).join();
        AutomationService freshService = manager.get(freshPlayer).automationService();

        assertNotNull(oldService);
        assertNotNull(freshService);
        assertNotSame(oldService, freshService);
        assertFalse(oldService.shadow().join());
        assertNull(manager.get(oldPlayer));
        verify(oldBackend).close();
    }

    @Test
    void unresolvedRegistryPreventsShadowFromDisconnectingTheFrontend() {
        ConnectedPlayer player = player(UUID.randomUUID(), true);
        manager.register(player).join();

        assertFalse(manager.get(player).automationService().shadow().join());

        verify(player, never()).disconnect(any());
    }

    @Test
    void nameLookupIsCaseInsensitiveAndSuggestionsRequireShadow() {
        ConnectedPlayer player = player(UUID.randomUUID(), true);
        when(player.getUsername()).thenReturn("ShadowPlayer");
        manager.register(player).join();

        assertSame(manager.get(player), manager.getByName("shadowplayer"));
        assertEquals(java.util.List.of(), manager.names());

        MinecraftConnection backend = player
                .getConnectionInFlightOrConnectedServer()
                .getConnection();
        when(backend.getChannel().isActive()).thenReturn(false);
        assertNull(manager.getByName("ShadowPlayer"));
        assertEquals(java.util.List.of(), manager.names());
    }

    @Test
    void killRejectsANonShadowPlayerWithoutRemovingOrClosingIt() {
        ConnectedPlayer velocityPlayer = player(UUID.randomUUID(), true);
        manager.register(velocityPlayer).join();
        com.fakeplayerproxy.world.player.Player player = manager.get(velocityPlayer);
        MinecraftConnection backend = velocityPlayer
                .getConnectionInFlightOrConnectedServer()
                .getConnection();

        Result<Void, String> result = manager.kill(player);

        assertInstanceOf(Result.Failure.class, result);
        assertSame(player, manager.get(velocityPlayer));
        verify(backend, never()).close();
    }

    @Test
    void tickTaskSetupFailureRefusesAndClosesTheAutomation() {
        ConnectedPlayer player = player(UUID.randomUUID(), true);
        when(player.getUsername()).thenReturn("SetupFailure");
        EventLoop eventLoop = player.getConnection().eventLoop();
        doThrow(new IllegalStateException("schedule failed")).when(eventLoop).scheduleAtFixedRate(
                any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));
        MinecraftConnection backend = player
                .getConnectionInFlightOrConnectedServer()
                .getConnection();

        manager.register(player).join();

        assertNull(manager.get(player));
        verify(backend).close();
        verify(logger).error(
                org.mockito.ArgumentMatchers.contains("Cannot schedule automation ticks"),
                org.mockito.ArgumentMatchers.eq("SetupFailure"),
                any(IllegalStateException.class));
    }

    @Test
    void transferClosesOnlyTheAutomationOwnedByTheExactSource() {
        ConnectedPlayer velocityPlayer = player(UUID.randomUUID(), true);
        manager.register(velocityPlayer).join();
        com.fakeplayerproxy.world.player.Player player = manager.get(velocityPlayer);
        VelocityServerConnection source = velocityPlayer.getConnectionInFlightOrConnectedServer();
        MinecraftConnection backend = source.getConnection();
        player.automationService().enableAutoReconnect(new byte[]{1});
        ClientboundTransferPacket packet = new ClientboundTransferPacket("example.test", 25565);

        manager.onTransfer(new ClientboundPacketEvent<>(
                velocityPlayer, mock(VelocityServerConnection.class),
                ClientboundTransferPacket.class, packet));
        assertSame(player, manager.get(velocityPlayer));

        manager.onTransfer(new ClientboundPacketEvent<>(
                velocityPlayer, source, ClientboundTransferPacket.class, packet));
        assertNull(manager.get(velocityPlayer));
        assertFalse(player.automationService().isAutoReconnect());
        verify(backend).close();
    }

    @Test
    void onlyRecognizedDisconnectKeysApplyTerminalPolicy() {
        ConnectedPlayer velocityPlayer = player(UUID.randomUUID(), true);
        manager.register(velocityPlayer).join();
        com.fakeplayerproxy.world.player.Player player = manager.get(velocityPlayer);
        VelocityServerConnection source = velocityPlayer.getConnectionInFlightOrConnectedServer();
        player.automationService().enableAutoReconnect(new byte[]{1});

        manager.onDisconnectPacket(new ClientboundPacketEvent<>(
                velocityPlayer, source, ClientboundDisconnectPacket.class,
                new ClientboundDisconnectPacket(Component.text("maintenance"))));
        assertSame(player, manager.get(velocityPlayer));

        manager.onDisconnectPacket(new ClientboundPacketEvent<>(
                velocityPlayer, source, ClientboundDisconnectPacket.class,
                new ClientboundDisconnectPacket(Component.translatable(
                        "multiplayer.disconnect.duplicate_login"))));
        assertNull(manager.get(velocityPlayer));
        assertFalse(player.automationService().isAutoReconnect());
    }

    private static ConnectedPlayer player(UUID uuid, boolean backendActive) {
        ConnectedPlayer player = mock(ConnectedPlayer.class);
        MinecraftConnection frontend = mock(MinecraftConnection.class);
        EventLoop eventLoop = immediateEventLoop();
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getConnection()).thenReturn(frontend);
        when(frontend.eventLoop()).thenReturn(eventLoop);

        if (backendActive) {
            VelocityServerConnection serverConnection = mock(VelocityServerConnection.class);
            MinecraftConnection backend = mock(MinecraftConnection.class);
            Channel channel = mock(Channel.class);
            ChannelFuture closeFuture = mock(ChannelFuture.class);
            when(player.getConnectionInFlightOrConnectedServer()).thenReturn(serverConnection);
            when(serverConnection.getConnection()).thenReturn(backend);
            when(serverConnection.getServerInfo()).thenReturn(
                    mock(com.velocitypowered.api.proxy.server.ServerInfo.class));
            when(backend.getChannel()).thenReturn(channel);
            when(channel.isActive()).thenReturn(true);
            when(channel.closeFuture()).thenReturn(closeFuture);
            when(closeFuture.isSuccess()).thenReturn(true);
            org.mockito.Mockito.doAnswer(invocation -> {
                io.netty.util.concurrent.GenericFutureListener<io.netty.util.concurrent.Future<? super Void>> listener =
                        invocation.getArgument(0);
                listener.operationComplete(closeFuture);
                return closeFuture;
            }).when(closeFuture).addListener(any());
        }
        return player;
    }

    private static EventLoop immediateEventLoop() {
        EventLoop eventLoop = mock(EventLoop.class);
        ScheduledFuture<?> scheduled = mock(ScheduledFuture.class);
        when(eventLoop.inEventLoop()).thenReturn(true);
        org.mockito.Mockito.doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(eventLoop).execute(any(Runnable.class));
        org.mockito.Mockito.doReturn(scheduled).when(eventLoop).scheduleAtFixedRate(
                any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));
        return eventLoop;
    }
}
