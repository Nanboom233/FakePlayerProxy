package com.fakeplayerproxy.mod;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fakeplayerproxy.mod.packets.ServerHelloPacketEnvelope;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class ServerHelloPacketEnvelopeTest {
    private static KeyPair proxyKeyPair;
    private static KeyPair targetKeyPair;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        proxyKeyPair = generator.generateKeyPair();
        generator.initialize(2048);
        targetKeyPair = generator.generateKeyPair();
    }

    @Test
    void ordinaryRsaKeyPassesThrough() {
        assertTrue(ServerHelloPacketEnvelope
                .decodeTargetPublicKey(proxyKeyPair.getPublic())
                .isEmpty());
    }

    @Test
    void extractsTargetKeyFromDecoratedProxySpki() throws Exception {
        byte[] decorated = decorate(
                (RSAPublicKey) proxyKeyPair.getPublic(), targetKeyPair.getPublic().getEncoded());
        PublicKey parsedProxyKey = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(decorated));

        PublicKey targetPublicKey = ServerHelloPacketEnvelope
                .decodeTargetPublicKey(parsedProxyKey)
                .orElseThrow();

        assertArrayEquals(decorated, parsedProxyKey.getEncoded());
        assertArrayEquals(
                targetKeyPair.getPublic().getEncoded(), targetPublicKey.getEncoded());
    }

    @Test
    void malformedRecognizedEnvelopeReturnsEmptyResult() throws Exception {
        byte[] malformedEnvelope = envelope(targetKeyPair.getPublic().getEncoded());
        malformedEnvelope[6] = 2;
        byte[] decorated = decorateWithEnvelope(
                (RSAPublicKey) proxyKeyPair.getPublic(), malformedEnvelope);
        PublicKey parsedProxyKey = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(decorated));

        assertTrue(ServerHelloPacketEnvelope
                .decodeTargetPublicKey(parsedProxyKey)
                .isEmpty());
    }

    @Test
    void acknowledgementUsesExactEnvelopeAndRsaBound() {
        byte[] largestChallenge = new byte[110];
        Arrays.fill(largestChallenge, (byte) 7);

        byte[] acknowledgement = ServerHelloPacketEnvelope
                .acknowledgement(largestChallenge)
                .orElseThrow();

        assertEquals(117, acknowledgement.length);
        assertArrayEquals(
                "FPPACK".getBytes(StandardCharsets.US_ASCII),
                Arrays.copyOf(acknowledgement, 6));
        assertEquals(1, acknowledgement[6]);
        assertArrayEquals(largestChallenge, Arrays.copyOfRange(acknowledgement, 7, 117));
        assertTrue(ServerHelloPacketEnvelope.acknowledgement(new byte[111]).isEmpty());
    }

    private static byte[] decorate(RSAPublicKey proxyKey, byte[] targetKey) {
        return decorateWithEnvelope(proxyKey, envelope(targetKey));
    }

    private static byte[] decorateWithEnvelope(RSAPublicKey proxyKey, byte[] envelope) {
        byte[] algorithmIdentifier = derSequence(
                derValue(0x06, new byte[] {
                    0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7,
                    0x0D, 0x01, 0x01, 0x01
                }),
                derValue(0x04, envelope));
        byte[] rsaPublicKey = derSequence(
                derInteger(proxyKey.getModulus()), derInteger(proxyKey.getPublicExponent()));
        byte[] bitString = new byte[rsaPublicKey.length + 1];
        System.arraycopy(rsaPublicKey, 0, bitString, 1, rsaPublicKey.length);
        return derSequence(algorithmIdentifier, derValue(0x03, bitString));
    }

    private static byte[] envelope(byte[] targetKey) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes("FPPMOD".getBytes(StandardCharsets.US_ASCII));
        output.write(1);
        writeVarInt(output, targetKey.length);
        output.writeBytes(targetKey);
        return output.toByteArray();
    }

    private static void writeVarInt(ByteArrayOutputStream output, int value) {
        do {
            int current = value & 0x7F;
            value >>>= 7;
            output.write(value == 0 ? current : current | 0x80);
        } while (value != 0);
    }

    private static byte[] derInteger(BigInteger value) {
        return derValue(0x02, value.toByteArray());
    }

    private static byte[] derSequence(byte[]... values) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] value : values) {
            output.writeBytes(value);
        }
        return derValue(0x30, output.toByteArray());
    }

    private static byte[] derValue(int tag, byte[] value) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(tag);
        writeDerLength(output, value.length);
        output.writeBytes(value);
        return output.toByteArray();
    }

    private static void writeDerLength(ByteArrayOutputStream output, int length) {
        if (length < 128) {
            output.write(length);
            return;
        }

        int byteCount = (Integer.SIZE - Integer.numberOfLeadingZeros(length) + 7) / 8;
        output.write(0x80 | byteCount);
        for (int shift = (byteCount - 1) * 8; shift >= 0; shift -= 8) {
            output.write(length >>> shift);
        }
    }
}
