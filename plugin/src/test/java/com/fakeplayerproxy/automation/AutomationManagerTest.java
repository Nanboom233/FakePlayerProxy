package com.fakeplayerproxy.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.backend.VelocityServerConnection;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.ScheduledFuture;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

final class AutomationManagerTest {
    private final AutomationManager manager = new AutomationManager(mock(Logger.class));

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
    void nameLookupIsCaseInsensitiveAndExcludesInactivePlayers() {
        ConnectedPlayer player = player(UUID.randomUUID(), true);
        when(player.getUsername()).thenReturn("ShadowPlayer");
        manager.register(player).join();

        assertSame(manager.get(player), manager.getByName("shadowplayer"));
        assertEquals(java.util.List.of("ShadowPlayer"), manager.names());

        MinecraftConnection backend = player
                .getConnectionInFlightOrConnectedServer()
                .getConnection();
        when(backend.getChannel().isActive()).thenReturn(false);
        assertNull(manager.getByName("ShadowPlayer"));
        assertEquals(java.util.List.of(), manager.names());
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
