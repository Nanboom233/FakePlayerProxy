/*
 * Copyright (C) 2018-2026 Velocity Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.velocitypowered.proxy.event;

import static com.velocitypowered.proxy.testutil.FakePluginManager.PLUGIN_A;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.ServerboundPacketEvent;
import com.velocitypowered.api.event.connection.ClientboundPacketEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.proxy.testutil.FakePluginManager;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.network.packet.PacketRegistry;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftCodec;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundKeepAlivePacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundKeepAlivePacket;
import org.junit.jupiter.api.Test;

final class PacketEventTest {
  private final VelocityEventManager eventManager =
      new VelocityEventManager(new FakePluginManager());
  private final Player player = mock(Player.class);
  private final ServerConnection source = mock(ServerConnection.class);

  @Test
  void replacesAndCancelsOneDecodedPacket() {
    eventManager.register(PLUGIN_A, new ReplacingListener());
    PacketEventHandler handler = eventManager.packetHandler(
        true, ProtocolState.GAME, serverboundKeepAliveId());

    ServerboundKeepAlivePacket replaced = (ServerboundKeepAlivePacket) handler.dispatch(
        player, source, new ServerboundKeepAlivePacket(1));

    assertEquals(2, replaced.getPingId());

    eventManager.unregisterListeners(PLUGIN_A);
    assertNull(eventManager.packetHandler(true, ProtocolState.GAME, serverboundKeepAliveId()));

    eventManager.register(PLUGIN_A, new CancellingListener());
    handler = eventManager.packetHandler(true, ProtocolState.GAME, serverboundKeepAliveId());
    assertNull(handler.dispatch(player, source, new ServerboundKeepAlivePacket(1)));
  }

  @Test
  void s2cListenerReceivesTheExactSourceAndCanReplace() {
    eventManager.register(PLUGIN_A, new S2cListener(source));
    PacketRegistry registry = MinecraftCodec.CODEC.getCodec(ProtocolState.GAME);
    int packetId = registry.getClientboundId(ClientboundKeepAlivePacket.class);

    PacketEventHandler handler = eventManager.packetHandler(false, ProtocolState.GAME, packetId);
    ClientboundKeepAlivePacket result = (ClientboundKeepAlivePacket) handler.dispatch(
        player, source, new ClientboundKeepAlivePacket(3));

    assertEquals(4, result.getPingId());
  }

  @Test
  void rejectsListenersWithoutOneConcreteSynchronousPacketType() {
    assertThrows(IllegalArgumentException.class,
        () -> eventManager.register(PLUGIN_A, new RawListener()));
    assertThrows(IllegalArgumentException.class,
        () -> eventManager.register(PLUGIN_A, new WildcardListener()));
    assertThrows(IllegalArgumentException.class,
        () -> eventManager.register(PLUGIN_A, new AsyncListener()));
    assertThrows(IllegalArgumentException.class,
        () -> eventManager.register(PLUGIN_A, new TaskListener()));
    assertThrows(IllegalArgumentException.class,
        () -> eventManager.register(PLUGIN_A, ServerboundPacketEvent.class, ignored -> { }));
  }

  @Test
  void noListenerHasNoPacketHandler() {
    assertNull(eventManager.packetHandler(
        true, ProtocolState.GAME, serverboundKeepAliveId()));
  }

  @Test
  void listenerFailureFallsBackToTheOriginalPacket() {
    eventManager.register(PLUGIN_A, new ReplacingListener());
    eventManager.register(FakePluginManager.PLUGIN_B, new FailingListener());
    PacketEventHandler handler = eventManager.packetHandler(
        true, ProtocolState.GAME, serverboundKeepAliveId());
    ServerboundKeepAlivePacket original = new ServerboundKeepAlivePacket(1);

    assertEquals(original, handler.dispatch(player, source, original));
  }

  private int serverboundKeepAliveId() {
    return MinecraftCodec.CODEC.getCodec(ProtocolState.GAME)
        .getServerboundId(ServerboundKeepAlivePacket.class);
  }

  private static final class ReplacingListener {
    @Subscribe(async = false)
    public void onPacket(ServerboundPacketEvent<ServerboundKeepAlivePacket> event) {
      event.setPacket(new ServerboundKeepAlivePacket(2));
    }
  }

  private static final class CancellingListener {
    @Subscribe(async = false)
    public void onPacket(ServerboundPacketEvent<ServerboundKeepAlivePacket> event) {
      event.cancel();
    }
  }

  private static final class S2cListener {
    private final ServerConnection source;

    private S2cListener(ServerConnection source) {
      this.source = source;
    }

    @Subscribe(async = false)
    public void onPacket(ClientboundPacketEvent<ClientboundKeepAlivePacket> event) {
      assertTrue(event.getPlayer() != null);
      assertTrue(event.isSource(source));
      assertFalse(event.isSource(mock(ServerConnection.class)));
      event.setPacket(new ClientboundKeepAlivePacket(4));
    }
  }

  private static final class RawListener {
    @Subscribe(async = false)
    public void onPacket(ServerboundPacketEvent event) {
    }
  }

  private static final class WildcardListener {
    @Subscribe(async = false)
    public void onPacket(ServerboundPacketEvent<?> event) {
    }
  }

  private static final class AsyncListener {
    @Subscribe
    public void onPacket(ServerboundPacketEvent<ServerboundKeepAlivePacket> event) {
    }
  }

  private static final class FailingListener {
    @Subscribe(async = false, order = com.velocitypowered.api.event.PostOrder.LAST)
    public void onPacket(ServerboundPacketEvent<ServerboundKeepAlivePacket> event) {
      throw new IllegalStateException("test failure");
    }
  }

  private static final class TaskListener {
    @Subscribe(async = false)
    public EventTask onPacket(ServerboundPacketEvent<ServerboundKeepAlivePacket> event) {
      return EventTask.async(() -> { });
    }
  }
}
