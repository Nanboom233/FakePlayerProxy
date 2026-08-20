package com.velocitypowered.proxy.network;

import static com.velocitypowered.proxy.network.Connections.FLOW_HANDLER;
import static com.velocitypowered.proxy.network.Connections.FRAME_DECODER;
import static com.velocitypowered.proxy.network.Connections.FRAME_ENCODER;
import static com.velocitypowered.proxy.network.Connections.MINECRAFT_DECODER;
import static com.velocitypowered.proxy.network.Connections.MINECRAFT_ENCODER;
import static com.velocitypowered.proxy.network.Connections.READ_TIMEOUT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.config.VelocityConfiguration;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.backend.VelocityServerConnection;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class BackendChannelInitializerTest {
  @Test
  void gatesPerAddressWithPriorityFifoMessageOrderAndCloseCleanup() {
    VelocityServer server = mock(VelocityServer.class);
    VelocityConfiguration configuration = mock(VelocityConfiguration.class);
    when(server.getConfiguration()).thenReturn(configuration);
    when(configuration.getReadTimeout()).thenReturn(30_000);
    BackendChannelInitializer initializer = new BackendChannelInitializer(server);

    Fixture active = fixture(initializer, server, false);
    Fixture low = fixture(initializer, server, true);
    Fixture highOne = fixture(initializer, server, false);
    Fixture highTwo = fixture(initializer, server, false);
    ByteBuf first = Unpooled.buffer().writeByte(1);
    ByteBuf lowMessage = Unpooled.buffer().writeByte(2);
    ByteBuf highOneFirst = Unpooled.buffer().writeByte(3);
    ByteBuf highOneSecond = Unpooled.buffer().writeByte(4);
    ByteBuf highTwoMessage = Unpooled.buffer().writeByte(5);
    ChannelPromise lowPromise = low.channel.newPromise();

    active.channel.writeAndFlush(first);
    low.channel.pipeline().writeAndFlush(lowMessage, lowPromise);
    highOne.channel.write(highOneFirst);
    highOne.channel.writeAndFlush(highOneSecond);
    highTwo.channel.writeAndFlush(highTwoMessage);

    assertSame(first, active.channel.readOutbound());
    assertFalse(lowPromise.isDone());
    assertFalse(highOne.channel.outboundMessages().iterator().hasNext());
    active.channel.advanceTimeBy(4, TimeUnit.SECONDS);
    active.channel.runScheduledPendingTasks();
    highOne.channel.runPendingTasks();

    assertSame(highOneFirst, highOne.channel.readOutbound());
    assertSame(highOneSecond, highOne.channel.readOutbound());
    assertFalse(highTwo.channel.outboundMessages().iterator().hasNext());
    low.channel.close();
    assertTrue(lowPromise.isDone());
    assertFalse(lowPromise.isSuccess());

    highOne.channel.advanceTimeBy(4, TimeUnit.SECONDS);
    highOne.channel.runScheduledPendingTasks();
    highTwo.channel.runPendingTasks();
    assertSame(highTwoMessage, highTwo.channel.readOutbound());
    assertEquals(0, highTwo.channel.outboundMessages().size());

    active.channel.finishAndReleaseAll();
    low.channel.finishAndReleaseAll();
    highOne.channel.finishAndReleaseAll();
    highTwo.channel.finishAndReleaseAll();
  }

  private static Fixture fixture(
      BackendChannelInitializer initializer, VelocityServer server, boolean lowPriority) {
    EmbeddedChannel channel = new EmbeddedChannel(initializer);
    channel.pipeline().remove(FRAME_DECODER);
    channel.pipeline().remove(READ_TIMEOUT);
    channel.pipeline().remove(FRAME_ENCODER);
    channel.pipeline().remove(MINECRAFT_DECODER);
    channel.pipeline().remove(FLOW_HANDLER);
    channel.pipeline().remove(MINECRAFT_ENCODER);
    MinecraftConnection connection = new MinecraftConnection(channel, server);
    VelocityServerConnection association = mock(VelocityServerConnection.class);
    when(association.isLogoutCancelled()).thenReturn(lowPriority);
    connection.setAssociation(association);
    channel.pipeline().addLast("connection", connection);
    return new Fixture(channel);
  }

  private record Fixture(EmbeddedChannel channel) {
  }
}
