package com.velocitypowered.proxy.connection.backend;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.event.player.PlayerResourcePackStatusEvent;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.connection.player.resourcepack.handler.ResourcePackHandler;
import com.velocitypowered.proxy.connection.player.resourcepack.handler.ModernResourcePackHandler;
import com.velocitypowered.proxy.connection.util.ConnectionRequestResults.Impl;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.netty.MinecraftDecoder;
import com.velocitypowered.proxy.protocol.netty.MinecraftVarintFrameDecoder;
import com.velocitypowered.proxy.protocol.packet.ResourcePackRequestPacket;
import com.velocitypowered.proxy.protocol.packet.ResourcePackResponsePacket;
import com.velocitypowered.proxy.protocol.packet.config.FinishedUpdatePacket;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

final class HeadlessConfigSessionHandlerTest {
  private final VelocityServer server = mock(VelocityServer.class);
  private final VelocityServerConnection serverConnection = mock(VelocityServerConnection.class);
  private final ConnectedPlayer player = mock(ConnectedPlayer.class);
  private final MinecraftConnection backend = mock(MinecraftConnection.class);
  private final ResourcePackHandler resourcePacks = mock(ModernResourcePackHandler.class);
  private final ConfigSessionHandler handler = new ConfigSessionHandler(
      server, serverConnection, new CompletableFuture<Impl>());

  @BeforeEach
  void prepareReconnect() {
    when(serverConnection.isLogoutCancelled()).thenReturn(true);
    when(serverConnection.getPlayer()).thenReturn(player);
    when(serverConnection.ensureConnected()).thenReturn(backend);
    when(player.resourcePackHandler()).thenReturn(resourcePacks);
  }

  @Test
  void handlesAppliedOptionalAndFinishedUpdateWithoutFrontend() {
    ResourcePackRequestPacket applied = pack("01", true);
    when(resourcePacks.hasPackAppliedByHash(any())).thenReturn(true);

    handler.handle(applied);

    InOrder appliedOrder = inOrder(backend);
    appliedOrder.verify(backend).write(responseWith(
        PlayerResourcePackStatusEvent.Status.ACCEPTED));
    appliedOrder.verify(backend).write(responseWith(
        PlayerResourcePackStatusEvent.Status.DOWNLOADED));
    appliedOrder.verify(backend).write(responseWith(
        PlayerResourcePackStatusEvent.Status.SUCCESSFUL));

    ResourcePackRequestPacket optional = pack("02", false);
    when(resourcePacks.hasPackAppliedByHash(any())).thenReturn(false);
    handler.handle(optional);
    verify(backend).write(responseWith(PlayerResourcePackStatusEvent.Status.DECLINED));

    Channel channel = mock(Channel.class);
    ChannelPipeline pipeline = mock(ChannelPipeline.class);
    MinecraftVarintFrameDecoder frameDecoder = mock(MinecraftVarintFrameDecoder.class);
    MinecraftDecoder decoder = mock(MinecraftDecoder.class);
    when(backend.getChannel()).thenReturn(channel);
    when(channel.pipeline()).thenReturn(pipeline);
    when(pipeline.get(MinecraftVarintFrameDecoder.class)).thenReturn(frameDecoder);
    when(pipeline.get(MinecraftDecoder.class)).thenReturn(decoder);

    handler.handle(FinishedUpdatePacket.INSTANCE);

    verify(frameDecoder).setState(StateRegistry.PLAY);
    verify(decoder).setState(StateRegistry.PLAY);
    verify(backend).write(FinishedUpdatePacket.INSTANCE);
    verify(backend).setActiveSessionHandler(
        org.mockito.ArgumentMatchers.eq(StateRegistry.PLAY),
        any(TransitionSessionHandler.class));
  }

  private static ResourcePackRequestPacket pack(String hash, boolean required) {
    ResourcePackRequestPacket packet = new ResourcePackRequestPacket();
    packet.setId(UUID.randomUUID());
    packet.setUrl("https://example.test/pack.zip");
    packet.setHash(hash);
    packet.setRequired(required);
    return packet;
  }

  private static ResourcePackResponsePacket responseWith(
      PlayerResourcePackStatusEvent.Status status) {
    return argThat(response -> response.getStatus() == status);
  }
}
