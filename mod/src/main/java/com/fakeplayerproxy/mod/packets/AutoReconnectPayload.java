package com.fakeplayerproxy.mod.packets;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.NotNull;

/** Defines the connection-scoped auto-reconnect consent request and response. */
public final class AutoReconnectPayload {
    public static final int MAX_TOKEN_BYTES = 8192;
    private static final Identifier ID = Identifier.fromNamespaceAndPath(
            "fakeplayerproxy", "auto_reconnect_v1");

    private AutoReconnectPayload() {
    }

    public record Request() implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(ID);
        // IDEA's unused callback warning is a false positive. Minecraft owns the codec signature.
        @SuppressWarnings("unused")
        public static final StreamCodec<FriendlyByteBuf, Request> STREAM_CODEC =
                CustomPacketPayload.codec((request, output) -> {
                }, input -> new Request());

        @Override
        public @NotNull Type<Request> type() {
            return TYPE;
        }
    }

    public record Response(@NotNull String accessToken) implements CustomPacketPayload {
        public static final Type<Response> TYPE = new Type<>(ID);
        public static final StreamCodec<FriendlyByteBuf, Response> STREAM_CODEC =
                CustomPacketPayload.codec((response, output) -> {
                    byte[] bytes = response.accessToken.getBytes(StandardCharsets.UTF_8);
                    if (bytes.length > MAX_TOKEN_BYTES) {
                        bytes = new byte[0];
                    }
                    output.writeVarInt(bytes.length);
                    output.writeBytes(bytes);
                }, input -> {
                    int length;
                    try {
                        length = input.readVarInt();
                    } catch (RuntimeException malformedLength) {
                        return new Response("");
                    }
                    if (length < 0 || length > MAX_TOKEN_BYTES || length != input.readableBytes()) {
                        return new Response("");
                    }
                    byte[] bytes = new byte[length];
                    input.readBytes(bytes);
                    try {
                        return new Response(StandardCharsets.UTF_8.newDecoder()
                                .onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT)
                                .decode(ByteBuffer.wrap(bytes)).toString());
                    } catch (CharacterCodingException malformedToken) {
                        return new Response("");
                    }
                });

        @Override
        public @NotNull Type<Response> type() {
            return TYPE;
        }
    }
}
