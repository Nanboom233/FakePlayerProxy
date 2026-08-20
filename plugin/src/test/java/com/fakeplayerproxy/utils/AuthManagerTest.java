package com.fakeplayerproxy.utils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fakeplayerproxy.automation.AutomationManager;
import com.fakeplayerproxy.automation.AutomationService;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.proxy.connection.backend.VelocityServerConnection;
import io.netty.channel.EventLoop;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

final class AuthManagerTest {
    private final AutomationManager automationManager = mock(AutomationManager.class);
    private final Logger logger = mock(Logger.class);
    private final AuthManager authManager = new AuthManager(automationManager, logger);
    private final Player velocityPlayer = mock(Player.class);
    private final VelocityServerConnection target = mock(VelocityServerConnection.class);
    private final EventLoop eventLoop = mock(EventLoop.class);
    private final com.fakeplayerproxy.world.player.Player player =
            mock(com.fakeplayerproxy.world.player.Player.class);
    private final AutomationService service = new AutomationService(player);

    @BeforeEach
    void preparePlayer() {
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(eventLoop).execute(any(Runnable.class));
        when(velocityPlayer.getUsername()).thenReturn("Shadow");
        when(velocityPlayer.getUniqueId()).thenReturn(UUID.randomUUID());
        var serverInfo = mock(com.velocitypowered.api.proxy.server.ServerInfo.class);
        when(serverInfo.getName()).thenReturn("backend");
        when(target.getServerInfo()).thenReturn(serverInfo);
        when(player.eventLoop()).thenReturn(eventLoop);
        when(player.serverConnection()).thenReturn(target);
        when(player.automationService()).thenReturn(service);
        when(automationManager.get(velocityPlayer)).thenReturn(player);
    }

    @Test
    void rejectsBackendSourceAfterMarkingTheMessageHandled() {
        ServerConnection backend = mock(ServerConnection.class);
        when(backend.getServerInfo()).thenReturn(
                mock(com.velocitypowered.api.proxy.server.ServerInfo.class));
        PluginMessageEvent event = new PluginMessageEvent(
                backend, velocityPlayer, AuthManager.CHANNEL, new byte[]{1, 'x'});

        authManager.onPluginMessage(event);

        assertFalse(event.getResult().isAllowed());
        verify(automationManager, never()).get(velocityPlayer);
    }

    @Test
    void emptyAndMalformedPayloadsStayDisabledAndHandled() {
        for (byte[] payload : new byte[][]{
                new byte[0],
                new byte[]{(byte) 0x80},
                new byte[]{1, (byte) 0x80},
                new byte[]{1, 'a', 'x'}}) {
            PluginMessageEvent event = event(payload);
            authManager.onPluginMessage(event);
            assertFalse(event.getResult().isAllowed());
            assertFalse(service.isAutoReconnect());
        }
    }

    @Test
    void declineDisablesAndValidUtf8TokenEnables() {
        authManager.onPluginMessage(event(new byte[]{0}));
        assertFalse(service.isAutoReconnect());

        byte[] token = UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[token.length + 1];
        payload[0] = (byte) token.length;
        System.arraycopy(token, 0, payload, 1, token.length);
        authManager.onPluginMessage(event(payload));

        assertTrue(service.isAutoReconnect());
    }

    @Test
    void ignoresAResponseWhoseBackendChangesBeforeTheEventLoopAppliesIt() {
        AtomicReference<Runnable> callback = new AtomicReference<>();
        doAnswer(invocation -> {
            callback.set(invocation.getArgument(0));
            return null;
        }).when(eventLoop).execute(any(Runnable.class));
        byte[] token = UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[token.length + 1];
        payload[0] = (byte) token.length;
        System.arraycopy(token, 0, payload, 1, token.length);

        authManager.onPluginMessage(event(payload));
        when(player.serverConnection()).thenReturn((VelocityServerConnection) null);
        callback.get().run();

        assertFalse(service.isAutoReconnect());
        verify(velocityPlayer, never()).sendMessage(any());
    }

    private PluginMessageEvent event(byte[] payload) {
        return new PluginMessageEvent(velocityPlayer, target, AuthManager.CHANNEL, payload);
    }
}
