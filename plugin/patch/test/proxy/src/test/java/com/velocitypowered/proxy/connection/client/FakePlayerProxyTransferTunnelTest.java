/*
 * Copyright (C) 2018-2023 Velocity Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.velocitypowered.proxy.connection.client;

import static com.velocitypowered.proxy.network.Connections.FLOW_HANDLER;
import static com.velocitypowered.proxy.network.Connections.FRAME_DECODER;
import static com.velocitypowered.proxy.network.Connections.FRAME_ENCODER;
import static com.velocitypowered.proxy.network.Connections.HANDLER;
import static com.velocitypowered.proxy.network.Connections.MINECRAFT_DECODER;
import static com.velocitypowered.proxy.network.Connections.MINECRAFT_ENCODER;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.network.HandshakeIntent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.config.VelocityConfiguration;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.protocol.MinecraftPacket;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.netty.AutoReadHolderHandler;
import com.velocitypowered.proxy.protocol.netty.MinecraftDecoder;
import com.velocitypowered.proxy.protocol.netty.MinecraftEncoder;
import com.velocitypowered.proxy.protocol.netty.MinecraftVarintFrameDecoder;
import com.velocitypowered.proxy.protocol.netty.MinecraftVarintLengthEncoder;
import com.velocitypowered.proxy.protocol.packet.DisconnectPacket;
import com.velocitypowered.proxy.protocol.packet.EncryptionRequestPacket;
import com.velocitypowered.proxy.protocol.packet.HandshakePacket;
import com.velocitypowered.proxy.protocol.packet.ServerLoginPacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.Test;

/**
 * Exercises the framed Transfer bootstrap and the raw byte handoff.
 *
 * <p>The test uses the production packet codecs and session handlers. A test
 * connector supplies an embedded target channel at the outbound connect boundary.
 */
class FakePlayerProxyTransferTunnelTest {

  private static final ProtocolVersion VERSION = ProtocolVersion.MINECRAFT_26_2;
  private static final InetSocketAddress TARGET_ADDRESS =
      InetSocketAddress.createUnresolved("target.example", 25566);

  @Test
  void coalescedBootstrapWaitsForForwardedTargetHelloBeforeRawHandoff() {
    EmbeddedChannel target = createTargetChannel();
    AtomicReference<InetSocketAddress> selectedAddress = new AtomicReference<>();
    AtomicInteger connectAttempts = new AtomicInteger();
    Harness harness = createFrontend((eventLoop, address) -> {
      connectAttempts.incrementAndGet();
      selectedAddress.set(address);
      target.config().setAutoRead(false);
      return new DefaultChannelPromise(target, eventLoop).setSuccess();
    });
    ClientFrames clientFrames = createClientFrames();

    harness.frontend.writeInbound(coalesce(clientFrames.handshake, clientFrames.login));
    runTasks(harness.frontend, target);

    assertEquals(1, connectAttempts.get());
    assertEquals(TARGET_ADDRESS, selectedAddress.get());
    ByteBuf targetHandshakeFrame = readFrame(target);
    ByteBuf targetLoginFrame = readFrame(target);
    TargetBootstrap targetBootstrap = decodeTargetBootstrap(
        targetHandshakeFrame, targetLoginFrame);
    assertEquals(HandshakeIntent.LOGIN, targetBootstrap.handshake.getIntent());
    assertEquals(VERSION, targetBootstrap.handshake.getProtocolVersion());
    assertEquals("gateway.example", targetBootstrap.handshake.getServerAddress());
    assertEquals(25565, targetBootstrap.handshake.getPort());
    assertEquals("TunnelUser", targetBootstrap.login.getUsername());
    assertEquals(clientFrames.holderUuid, targetBootstrap.login.getHolderUuid());
    assertNull(target.readOutbound());
    assertFalse(harness.connection.isAutoReading());
    assertFalse(target.config().isAutoRead());
    assertNull(target.pipeline().context(FLOW_HANDLER));
    assertNull(harness.frontend.pipeline().context(RawTunnelForwardHandler.HANDLER_NAME));
    assertNull(target.pipeline().context(RawTunnelForwardHandler.HANDLER_NAME));

    EncryptionRequestPacket targetHello = createTargetHello();
    target.writeInbound(encodeLoginPacket(targetHello, ProtocolUtils.Direction.CLIENTBOUND));
    runTasks(harness.frontend, target);

    EncryptionRequestPacket frontendHello = assertInstanceOf(
        EncryptionRequestPacket.class,
        decodeLoginPacket(readFrame(harness.frontend), ProtocolUtils.Direction.CLIENTBOUND));
    assertArrayEquals(targetHello.getPublicKey(), frontendHello.getPublicKey());
    assertArrayEquals(targetHello.getVerifyToken(), frontendHello.getVerifyToken());
    assertNull(harness.frontend.readOutbound());
    assertTrue(harness.connection.isAutoReading());
    assertTrue(target.config().isAutoRead());
    assertInstanceOf(
        RawTunnelForwardHandler.class,
        harness.frontend.pipeline().get(RawTunnelForwardHandler.HANDLER_NAME));
    assertInstanceOf(
        RawTunnelForwardHandler.class,
        target.pipeline().get(RawTunnelForwardHandler.HANDLER_NAME));

    byte[] clientBytes = new byte[] {0x12, 0x34, 0x56};
    harness.frontend.writeInbound(Unpooled.wrappedBuffer(clientBytes));
    runTasks(harness.frontend, target);
    ByteBuf targetRaw = assertInstanceOf(ByteBuf.class, target.readOutbound());
    assertArrayEquals(clientBytes, ByteBufUtil.getBytes(targetRaw));
    ReferenceCountUtil.release(targetRaw);

    byte[] targetBytes = new byte[] {(byte) 0xA1, (byte) 0xB2};
    target.writeInbound(Unpooled.wrappedBuffer(targetBytes));
    runTasks(harness.frontend, target);
    ByteBuf frontendRaw = assertInstanceOf(ByteBuf.class, harness.frontend.readOutbound());
    assertArrayEquals(targetBytes, ByteBufUtil.getBytes(frontendRaw));
    ReferenceCountUtil.release(frontendRaw);

    harness.frontend.close();
    runTasks(harness.frontend, target);
    assertFalse(target.isActive());

    harness.frontend.finishAndReleaseAll();
    target.finishAndReleaseAll();
  }

  @Test
  void writesOneExplicitBootstrapToTheSelectedTarget() {
    EmbeddedChannel target = createTargetChannel();
    AtomicInteger connectAttempts = new AtomicInteger();
    Harness harness = createFrontend((eventLoop, address) -> {
      connectAttempts.incrementAndGet();
      target.config().setAutoRead(false);
      return new DefaultChannelPromise(target, eventLoop).setSuccess();
    });
    ClientFrames clientFrames = createClientFrames();

    harness.frontend.writeInbound(clientFrames.handshake);
    runTasks(harness.frontend, target);
    harness.frontend.writeInbound(clientFrames.login);
    runTasks(harness.frontend, target);

    assertEquals(1, connectAttempts.get());
    TargetBootstrap bootstrap = decodeTargetBootstrap(readFrame(target), readFrame(target));
    assertEquals(HandshakeIntent.LOGIN, bootstrap.handshake.getIntent());
    assertEquals("TunnelUser", bootstrap.login.getUsername());
    assertNull(target.readOutbound());
    assertFalse(harness.connection.isAutoReading());
    assertNull(harness.frontend.readOutbound());

    harness.frontend.finishAndReleaseAll();
    target.finishAndReleaseAll();
  }

  @Test
  void targetLoginDisconnectAndCleanCloseDoNotReconnectAndLogTheHelloStage() {
    TestLogAppender appender = new TestLogAppender();
    org.apache.logging.log4j.core.Logger logger =
        (org.apache.logging.log4j.core.Logger) LogManager.getLogger(
            TransferTunnelLoginSessionHandler.class);
    appender.start();
    logger.addAppender(appender);
    try {
      verifyTargetLoginFailure(false);
      verifyTargetLoginFailure(true);
    } finally {
      logger.removeAppender(appender);
      appender.stop();
    }

    assertEquals(2, appender.events.stream()
        .map(event -> event.getMessage().getFormattedMessage())
        .filter(message -> message.contains("target.example")
            && message.contains("waiting for Server Hello"))
        .count());
  }

  @Test
  void sendsLoginDisconnectWhenTargetConnectFails() {
    EmbeddedChannel failedTarget = new EmbeddedChannel();
    Harness harness = createFrontend((eventLoop, address) ->
        new DefaultChannelPromise(failedTarget, eventLoop)
            .setFailure(new ConnectException("test failure")));
    ClientFrames clientFrames = createClientFrames();

    harness.frontend.writeInbound(clientFrames.handshake);
    runTasks(harness.frontend, failedTarget);
    assertEquals(StateRegistry.LOGIN, harness.connection.getState());
    harness.frontend.writeInbound(clientFrames.login);
    runTasks(harness.frontend, failedTarget);

    ByteBuf disconnectFrame = readFrame(harness.frontend);
    assertInstanceOf(
        DisconnectPacket.class,
        decodeLoginPacket(disconnectFrame, ProtocolUtils.Direction.CLIENTBOUND));
    assertFalse(harness.frontend.isActive());
    assertFalse(failedTarget.isActive());

    harness.frontend.finishAndReleaseAll();
    failedTarget.finishAndReleaseAll();
  }

  private static void verifyTargetLoginFailure(boolean cleanClose) {
    EmbeddedChannel target = createTargetChannel();
    AtomicInteger connectAttempts = new AtomicInteger();
    Harness harness = createFrontend((eventLoop, address) -> {
      connectAttempts.incrementAndGet();
      target.config().setAutoRead(false);
      return new DefaultChannelPromise(target, eventLoop).setSuccess();
    });
    ClientFrames clientFrames = createClientFrames();

    harness.frontend.writeInbound(coalesce(clientFrames.handshake, clientFrames.login));
    runTasks(harness.frontend, target);
    ReferenceCountUtil.release(readFrame(target));
    ReferenceCountUtil.release(readFrame(target));

    if (cleanClose) {
      target.close();
    } else {
      DisconnectPacket disconnect = DisconnectPacket.create(
          Component.text("target rejected login"), VERSION, StateRegistry.LOGIN);
      target.writeInbound(encodeLoginPacket(disconnect, ProtocolUtils.Direction.CLIENTBOUND));
    }
    runTasks(harness.frontend, target);

    assertEquals(1, connectAttempts.get());
    assertFalse(target.isActive());
    assertFalse(harness.frontend.isActive());

    harness.frontend.finishAndReleaseAll();
    target.finishAndReleaseAll();
  }

  private static Harness createFrontend(
      TransferTunnelLoginSessionHandler.TargetConnector connector) {
    VelocityServer server = mock(VelocityServer.class);
    VelocityConfiguration configuration = mock(VelocityConfiguration.class);
    RegisteredServer target = mock(RegisteredServer.class);
    when(server.getConfiguration()).thenReturn(configuration);
    when(configuration.getAttemptConnectionOrder()).thenReturn(List.of("target", "unused"));
    when(server.getServer("target")).thenReturn(Optional.of(target));
    when(target.getServerInfo()).thenReturn(new ServerInfo("target", TARGET_ADDRESS));

    EmbeddedChannel frontend = new EmbeddedChannel();
    frontend.pipeline()
        .addLast(FRAME_DECODER,
            new MinecraftVarintFrameDecoder(ProtocolUtils.Direction.SERVERBOUND))
        .addLast(FRAME_ENCODER, MinecraftVarintLengthEncoder.INSTANCE)
        .addLast(MINECRAFT_DECODER,
            new MinecraftDecoder(ProtocolUtils.Direction.SERVERBOUND))
        .addLast(MINECRAFT_ENCODER,
            new MinecraftEncoder(ProtocolUtils.Direction.CLIENTBOUND));
    MinecraftConnection connection = new MinecraftConnection(frontend, server);
    connection.setActiveSessionHandler(StateRegistry.HANDSHAKE,
        new HandshakeSessionHandler(connection, server, connector));
    frontend.pipeline().addLast(HANDLER, connection);
    return new Harness(frontend, connection);
  }

  private static EmbeddedChannel createTargetChannel() {
    EmbeddedChannel target = new EmbeddedChannel();
    target.pipeline()
        .addLast(FRAME_DECODER,
            new MinecraftVarintFrameDecoder(ProtocolUtils.Direction.CLIENTBOUND))
        .addLast(FRAME_ENCODER, MinecraftVarintLengthEncoder.INSTANCE)
        .addLast(MINECRAFT_DECODER,
            new MinecraftDecoder(ProtocolUtils.Direction.CLIENTBOUND))
        .addLast(FLOW_HANDLER, new AutoReadHolderHandler())
        .addLast(MINECRAFT_ENCODER,
            new MinecraftEncoder(ProtocolUtils.Direction.SERVERBOUND));
    return target;
  }

  private static ClientFrames createClientFrames() {
    HandshakePacket handshake = new HandshakePacket();
    handshake.setProtocolVersion(VERSION);
    handshake.setServerAddress("gateway.example");
    handshake.setPort(25565);
    handshake.setIntent(HandshakeIntent.TRANSFER);
    UUID holderUuid = UUID.fromString("12345678-1234-5678-1234-567812345678");
    ServerLoginPacket login = new ServerLoginPacket("TunnelUser", holderUuid);

    MinecraftEncoder encoder = new MinecraftEncoder(ProtocolUtils.Direction.SERVERBOUND);
    encoder.setProtocolVersion(VERSION);
    EmbeddedChannel channel = new EmbeddedChannel(
        MinecraftVarintLengthEncoder.INSTANCE, encoder);
    channel.writeOutbound(handshake);
    ByteBuf handshakeFrame = readFrame(channel);
    encoder.setState(StateRegistry.LOGIN);
    channel.writeOutbound(login);
    ByteBuf loginFrame = readFrame(channel);
    channel.finishAndReleaseAll();
    return new ClientFrames(handshakeFrame, loginFrame, holderUuid);
  }

  private static EncryptionRequestPacket createTargetHello() {
    ByteBuf payload = Unpooled.buffer();
    ProtocolUtils.writeString(payload, "target-server-id");
    ProtocolUtils.writeByteArray(payload, new byte[] {1, 2, 3, 4});
    ProtocolUtils.writeByteArray(payload, new byte[] {5, 6, 7, 8});
    payload.writeBoolean(true);
    EncryptionRequestPacket packet = new EncryptionRequestPacket();
    packet.decode(payload, ProtocolUtils.Direction.CLIENTBOUND, VERSION);
    ReferenceCountUtil.release(payload);
    return packet;
  }

  private static ByteBuf encodeLoginPacket(
      MinecraftPacket packet, ProtocolUtils.Direction direction) {
    MinecraftEncoder encoder = new MinecraftEncoder(direction);
    encoder.setProtocolVersion(VERSION);
    encoder.setState(StateRegistry.LOGIN);
    EmbeddedChannel channel = new EmbeddedChannel(
        MinecraftVarintLengthEncoder.INSTANCE, encoder);
    channel.writeOutbound(packet);
    ByteBuf frame = readFrame(channel);
    channel.finishAndReleaseAll();
    return frame;
  }

  private static ByteBuf coalesce(ByteBuf first, ByteBuf second) {
    ByteBuf result = Unpooled.buffer(first.readableBytes() + second.readableBytes());
    result.writeBytes(first);
    result.writeBytes(second);
    ReferenceCountUtil.release(first);
    ReferenceCountUtil.release(second);
    return result;
  }

  private static TargetBootstrap decodeTargetBootstrap(
      ByteBuf handshakeFrame, ByteBuf loginFrame) {
    MinecraftDecoder decoder = new MinecraftDecoder(ProtocolUtils.Direction.SERVERBOUND);
    decoder.setProtocolVersion(VERSION);
    EmbeddedChannel channel = new EmbeddedChannel(
        new MinecraftVarintFrameDecoder(ProtocolUtils.Direction.SERVERBOUND), decoder);
    channel.writeInbound(handshakeFrame);
    HandshakePacket handshake = assertInstanceOf(HandshakePacket.class, channel.readInbound());
    decoder.setState(StateRegistry.LOGIN);
    channel.writeInbound(loginFrame);
    ServerLoginPacket login = assertInstanceOf(ServerLoginPacket.class, channel.readInbound());
    channel.finishAndReleaseAll();
    return new TargetBootstrap(handshake, login);
  }

  private static Object decodeLoginPacket(ByteBuf frame, ProtocolUtils.Direction direction) {
    MinecraftDecoder decoder = new MinecraftDecoder(direction);
    decoder.setProtocolVersion(VERSION);
    decoder.setState(StateRegistry.LOGIN);
    EmbeddedChannel channel = new EmbeddedChannel(
        new MinecraftVarintFrameDecoder(direction), decoder);
    channel.writeInbound(frame);
    Object packet = channel.readInbound();
    channel.finishAndReleaseAll();
    return packet;
  }

  private static void runTasks(EmbeddedChannel first, EmbeddedChannel second) {
    for (int index = 0; index < 8; index++) {
      first.runPendingTasks();
      second.runPendingTasks();
    }
  }

  private static ByteBuf readFrame(EmbeddedChannel channel) {
    ByteBuf length = assertInstanceOf(ByteBuf.class, channel.readOutbound());
    ByteBuf body = assertInstanceOf(ByteBuf.class, channel.readOutbound());
    ByteBuf frame = Unpooled.buffer(length.readableBytes() + body.readableBytes());
    frame.writeBytes(length);
    frame.writeBytes(body);
    ReferenceCountUtil.release(length);
    ReferenceCountUtil.release(body);
    return frame;
  }

  private record Harness(EmbeddedChannel frontend, MinecraftConnection connection) {
  }

  private record ClientFrames(ByteBuf handshake, ByteBuf login, UUID holderUuid) {
  }

  private record TargetBootstrap(HandshakePacket handshake, ServerLoginPacket login) {
  }

  private static final class TestLogAppender extends AbstractAppender {

    private final List<LogEvent> events = new ArrayList<>();

    private TestLogAppender() {
      super(
          "fake-player-proxy-transfer-tunnel-test",
          null,
          PatternLayout.createDefaultLayout(),
          false,
          Property.EMPTY_ARRAY);
    }

    @Override
    public void append(LogEvent event) {
      events.add(event.toImmutable());
    }
  }
}
