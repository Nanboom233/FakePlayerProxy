package com.fakeplayerproxy.mod.packets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class AutoReconnectPayloadTest {
    @Test
    void emptyRequestHasNoBody() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            AutoReconnectPayload.Request.STREAM_CODEC.encode(
                    buffer, new AutoReconnectPayload.Request());
            assertFalse(buffer.isReadable());
            assertEquals(new AutoReconnectPayload.Request(),
                    AutoReconnectPayload.Request.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void allowAndDeclineResponsesRoundTrip() {
        String payload = UUID.randomUUID().toString();
        assertEquals(payload, roundTrip(payload));
        assertEquals("", roundTrip(""));
    }

    @Test
    void oversizedTokenEncodesAsDecline() {
        assertEquals("", roundTrip("x".repeat(AutoReconnectPayload.MAX_TOKEN_BYTES + 1)));
    }

    @Test
    void malformedUtf8AndTrailingBytesDecodeAsDecline() {
        assertEquals("", decode(new byte[]{1, (byte) 0x80}));
        assertEquals("", decode(new byte[]{1, 'a', 'x'}));
    }

    private static String roundTrip(String token) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            AutoReconnectPayload.Response.STREAM_CODEC.encode(
                    buffer, new AutoReconnectPayload.Response(token));
            return AutoReconnectPayload.Response.STREAM_CODEC.decode(buffer).accessToken();
        } finally {
            buffer.release();
        }
    }

    private static String decode(byte[] payload) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(payload));
        try {
            return AutoReconnectPayload.Response.STREAM_CODEC.decode(buffer).accessToken();
        } finally {
            buffer.release();
        }
    }
}
