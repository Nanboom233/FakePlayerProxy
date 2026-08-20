package com.velocitypowered.proxy.connection.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import com.mojang.authlib.exceptions.InvalidCredentialsException;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.connection.util.ConnectionRequestResults.Impl;
import com.velocitypowered.proxy.crypto.EncryptionUtils;
import com.velocitypowered.proxy.protocol.packet.EncryptionRequestPacket;
import com.velocitypowered.proxy.protocol.packet.EncryptionResponsePacket;
import com.velocitypowered.proxy.server.VelocityRegisteredServer;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoop;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import javax.crypto.Cipher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

final class ReconnectLoginSessionHandlerTest {
  @Test
  void joinsTheProfileAndWritesPlaintextResponseBeforeEncryption() throws Exception {
    Context context = context(null);
    ArgumentCaptor<UUID> profile = ArgumentCaptor.forClass(UUID.class);
    ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> digest = ArgumentCaptor.forClass(String.class);

    context.handler.handle(context.request);

    verify(context.server, timeout(5_000)).joinBackendSession(
        profile.capture(), token.capture(), digest.capture());
    ArgumentCaptor<Object> response = ArgumentCaptor.forClass(Object.class);
    verify(context.backend, timeout(5_000)).write(response.capture());
    verify(context.backend, timeout(5_000)).enableEncryption(any());
    InOrder order = inOrder(context.backend);
    order.verify(context.backend).write(any(EncryptionResponsePacket.class));
    order.verify(context.backend).enableEncryption(any());

    EncryptionResponsePacket keyResponse =
        assertInstanceOf(EncryptionResponsePacket.class, response.getValue());
    Cipher rsa = Cipher.getInstance("RSA");
    rsa.init(Cipher.DECRYPT_MODE, context.keys.getPrivate());
    byte[] secret = rsa.doFinal(keyResponse.getSharedSecret());
    assertEquals(context.profileId, profile.getValue());
    assertEquals(context.authorization, token.getValue());
    assertEquals(EncryptionUtils.generateServerId(secret, context.keys.getPublic()),
        digest.getValue());
    rsa.init(Cipher.DECRYPT_MODE, context.keys.getPrivate());
    assertEquals(java.util.List.of((byte) 1, (byte) 2, (byte) 3),
        bytes(rsa.doFinal(keyResponse.getVerifyToken())));
  }

  @Test
  void propagatesCredentialAndRetryableSessionFailures() throws Exception {
    assertSameFailure(new InvalidCredentialsException(), InvalidCredentialsException.class);
    assertSameFailure(
        new AuthenticationUnavailableException(), AuthenticationUnavailableException.class);
  }

  @Test
  void replacementOwnsItsTokenAndStartsWithoutClientLoadedState() {
    VelocityRegisteredServer target = mock(VelocityRegisteredServer.class);
    ConnectedPlayer player = mock(ConnectedPlayer.class);
    VelocityServer server = mock(VelocityServer.class);
    byte[] source = new byte[]{1, 2, 3};
    VelocityServerConnection replacement = new VelocityServerConnection(
        target, target, player, server, source);

    source[0] = 9;
    byte[] owned = replacement.takeReconnectToken();

    assertFalse(replacement.isClientLoaded());
    assertNotSame(source, owned);
    assertEquals(java.util.List.of((byte) 1, (byte) 2, (byte) 3), bytes(owned));
    assertNull(replacement.takeReconnectToken());

    VelocityServerConnection cancelled = new VelocityServerConnection(
        target, target, player, server, new byte[]{4, 5, 6});
    cancelled.disconnect();
    assertNull(cancelled.takeReconnectToken());
  }

  private static void assertSameFailure(
      Exception expected, Class<? extends Exception> expectedType) throws Exception {
    Context context = context(expected);
    context.handler.handle(context.request);

    CompletionException completion = org.junit.jupiter.api.Assertions.assertThrows(
        CompletionException.class, () -> context.result.orTimeout(5, java.util.concurrent.TimeUnit.SECONDS).join());
    assertSame(expected, assertInstanceOf(expectedType, completion.getCause()));
    verify(context.serverConnection, timeout(5_000)).disconnect();
  }

  private static Context context(Exception joinFailure) throws Exception {
    VelocityServer server = mock(VelocityServer.class);
    VelocityServerConnection serverConnection = mock(VelocityServerConnection.class);
    ConnectedPlayer player = mock(ConnectedPlayer.class);
    MinecraftConnection backend = mock(MinecraftConnection.class);
    EventLoop eventLoop = mock(EventLoop.class);
    ChannelFuture write = mock(ChannelFuture.class);
    CompletableFuture<Impl> result = new CompletableFuture<>();
    UUID profileId = UUID.randomUUID();
    String authorization = UUID.randomUUID().toString();
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(1024);
    KeyPair keys = generator.generateKeyPair();
    EncryptionRequestPacket request = new EncryptionRequestPacket();
    request.setPublicKey(keys.getPublic().getEncoded());
    request.setVerifyToken(new byte[]{1, 2, 3});
    LoginSessionHandler handler = new LoginSessionHandler(server, serverConnection, result);

    when(serverConnection.takeReconnectToken())
        .thenReturn(authorization.getBytes(StandardCharsets.UTF_8));
    when(serverConnection.getPlayer()).thenReturn(player);
    when(serverConnection.ensureConnected()).thenReturn(backend);
    when(serverConnection.getConnection()).thenReturn(backend);
    when(player.getUniqueId()).thenReturn(profileId);
    when(backend.eventLoop()).thenReturn(eventLoop);
    when(backend.getActiveSessionHandler()).thenReturn(handler);
    when(backend.write(any(EncryptionResponsePacket.class))).thenReturn(write);
    when(write.isSuccess()).thenReturn(true);
    doAnswer(invocation -> {
      ((Runnable) invocation.getArgument(0)).run();
      return null;
    }).when(eventLoop).execute(any(Runnable.class));
    doAnswer(invocation -> {
      io.netty.util.concurrent.GenericFutureListener<?> listener = invocation.getArgument(0);
      @SuppressWarnings("unchecked")
      io.netty.util.concurrent.GenericFutureListener<io.netty.util.concurrent.Future<? super Void>>
          typed = (io.netty.util.concurrent.GenericFutureListener<
              io.netty.util.concurrent.Future<? super Void>>) listener;
      typed.operationComplete(write);
      return write;
    }).when(write).addListener(any());
    CompletableFuture<Void> join = joinFailure == null
        ? CompletableFuture.completedFuture(null)
        : CompletableFuture.failedFuture(joinFailure);
    when(server.joinBackendSession(any(), any(), any())).thenReturn(join);
    return new Context(
        handler, request, result, server, serverConnection, backend,
        profileId, authorization, keys);
  }

  private static java.util.List<Byte> bytes(byte[] value) {
    java.util.List<Byte> bytes = new java.util.ArrayList<>(value.length);
    for (byte item : value) {
      bytes.add(item);
    }
    return bytes;
  }

  private record Context(
      LoginSessionHandler handler,
      EncryptionRequestPacket request,
      CompletableFuture<Impl> result,
      VelocityServer server,
      VelocityServerConnection serverConnection,
      MinecraftConnection backend,
      UUID profileId,
      String authorization,
      KeyPair keys) {
  }
}
