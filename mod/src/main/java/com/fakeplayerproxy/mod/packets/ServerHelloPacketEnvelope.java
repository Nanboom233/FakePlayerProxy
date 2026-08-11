package com.fakeplayerproxy.mod.packets;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import net.minecraft.util.Crypt;
import net.minecraft.util.CryptException;
import org.jetbrains.annotations.NotNull;

/**
 * Decodes the FakePlayerProxy extension from a modified Server Hello key.
 *
 * <p>Minecraft parses the public-key field before the Mod can use it. Velocity
 * must therefore send a valid RSA public key with a matching private key. The
 * subject key contains the proxy modulus and exponent. Its AlgorithmIdentifier
 * parameter contains the protocol marker and the original target SPKI.
 *
 * <p>A valid carrier returns the original target key. An ordinary RSA key or
 * malformed carrier returns an empty value, so Minecraft keeps its original
 * login flow without changed arguments.
 *
 * <p>This design keeps both login packets standard. It also avoids a custom
 * payload and leaves connections to ordinary servers unchanged. SunRsaSign must
 * preserve the non-NULL AlgorithmIdentifier parameter for this design to work.
 * See `.trellis/tasks/06-12-fake-player-proxy-research/research/
 * minecraft-1.20-26.2-spki-carrier-compatibility.md` for the provider evidence.
 */
public final class ServerHelloPacketEnvelope {
    private static final byte[] MAGIC = "FPPMOD".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ACKNOWLEDGEMENT = "FPPACK".getBytes(StandardCharsets.US_ASCII);
    private static final byte VERSION = 1;
    private static final int MAX_TARGET_KEY_LENGTH = 512;
    private static final int MAX_RSA_PLAINTEXT_LENGTH = 117;
    private static final byte[] RSA_ENCRYPTION_OID =
            HexFormat.of().parseHex("2A864886F70D010101");

    private ServerHelloPacketEnvelope() {
    }

    /**
     * Decodes the original target key from a supported Server Hello.
     *
     * <p>The decoder validates the complete carrier before it returns a key.
     * An empty result covers both an ordinary key and malformed carrier data.
     * The caller therefore preserves Minecraft's original login behavior when
     * the proxy protocol cannot be used.
     *
     * @param proxyPublicKey the JCA key from the Server Hello
     * @return the target key for a supported carrier, or an empty value
     */
    public static @NotNull Optional<PublicKey> decodeTargetPublicKey(
            PublicKey proxyPublicKey) {
        if (proxyPublicKey == null) {
            return Optional.empty();
        }

        // The PublicKey interface does not expose AlgorithmIdentifier parameters.
        // Read the preserved SPKI bytes to get the relay carrier.
        byte[] encoded = proxyPublicKey.getEncoded();
        if (encoded == null) {
            return Optional.empty();
        }

        DerReader outer = new DerReader(encoded);
        byte[] subjectPublicKeyInfo = outer.readValue(0x30);
        if (subjectPublicKeyInfo == null || outer.hasRemaining()) {
            return Optional.empty();
        }

        DerReader subjectReader = new DerReader(subjectPublicKeyInfo);
        byte[] algorithmIdentifier = subjectReader.readValue(0x30);
        byte[] subjectPublicKey = subjectReader.readValue(0x03);
        if (algorithmIdentifier == null
                || subjectPublicKey == null
                || subjectReader.hasRemaining()) {
            return Optional.empty();
        }

        DerReader algorithmReader = new DerReader(algorithmIdentifier);
        byte[] oid = algorithmReader.readValue(0x06);
        byte[] envelope = algorithmReader.readValue(0x04);
        // An ordinary RSA key uses NULL parameters. It must keep the Minecraft flow.
        if (envelope == null || !startsWith(envelope)) {
            return Optional.empty();
        }
        if (oid == null
                || !Arrays.equals(oid, RSA_ENCRYPTION_OID)
                || algorithmReader.hasRemaining()) {
            return Optional.empty();
        }

        int offset = MAGIC.length;
        if (offset >= envelope.length || envelope[offset++] != VERSION) {
            return Optional.empty();
        }

        VarInt targetLength = readVarInt(envelope, offset);
        if (targetLength == null
                || targetLength.value() <= 0
                || targetLength.value() > MAX_TARGET_KEY_LENGTH
                || targetLength.nextOffset() + targetLength.value() != envelope.length) {
            return Optional.empty();
        }

        byte[] targetKeyBytes = Arrays.copyOfRange(
                envelope, targetLength.nextOffset(), envelope.length);
        try {
            // Validate the embedded SPKI now. The Mixin receives a usable JCA key.
            PublicKey targetPublicKey = Crypt.byteToPublicKey(targetKeyBytes);
            if (!"RSA".equals(targetPublicKey.getAlgorithm())) {
                return Optional.empty();
            }
            return Optional.of(targetPublicKey);
        } catch (CryptException _) {
            return Optional.empty();
        }
    }

    /**
     * Adds the Mod acknowledgement to the unchanged target challenge.
     *
     * <p>The standard key packet encrypts this value with the proxy public key.
     * Velocity decrypts it and distinguishes the Mod from a Vanilla client. The
     * original challenge stays in the value, so Velocity can also verify that
     * this acknowledgement belongs to the active target login.
     *
     * <p>RSA-1024 with PKCS#1 v1.5 accepts at most 117 plaintext bytes. The empty
     * result prevents Minecraft from constructing an invalid RSA operation.
     *
     * @param challenge the original target challenge
     * @return the acknowledgement, or an empty result when it exceeds the bound
     */
    public static @NotNull Optional<byte[]> acknowledgement(byte[] challenge) {
        if (challenge == null
                || ACKNOWLEDGEMENT.length + 1 + challenge.length > MAX_RSA_PLAINTEXT_LENGTH) {
            return Optional.empty();
        }

        byte[] result = new byte[ACKNOWLEDGEMENT.length + 1 + challenge.length];
        System.arraycopy(ACKNOWLEDGEMENT, 0, result, 0, ACKNOWLEDGEMENT.length);
        result[ACKNOWLEDGEMENT.length] = VERSION;
        System.arraycopy(challenge, 0, result, ACKNOWLEDGEMENT.length + 1, challenge.length);
        return Optional.of(result);
    }

    private static boolean startsWith(byte @NotNull [] value) {
        return value.length >= MAGIC.length
                && Arrays.equals(value, 0, MAGIC.length, MAGIC, 0, MAGIC.length);
    }

    private static VarInt readVarInt(byte @NotNull [] input, int offset) {
        int value = 0;
        int bytesRead = 0;
        while (offset + bytesRead < input.length && bytesRead < 5) {
            int current = input[offset + bytesRead] & 0xFF;
            value |= (current & 0x7F) << (bytesRead * 7);
            bytesRead++;
            if ((current & 0x80) == 0) {
                if (bytesRead != varIntSize(value)) {
                    return null;
                }
                return new VarInt(value, offset + bytesRead);
            }
        }
        return null;
    }

    private static int varIntSize(int value) {
        int bitCount = Integer.SIZE - Integer.numberOfLeadingZeros(value);
        return Math.max(1, Math.ceilDiv(bitCount, 7));
    }

    private record VarInt(int value, int nextOffset) {
    }

    /**
     * Reads only the DER values that the selected SPKI carrier needs.
     *
     * <p>Strict lengths reject ambiguous relay data. This small reader avoids an
     * ASN.1 dependency in the client Mod.
     */
    private static final class DerReader {
        private final byte[] input;
        private int offset;

        private DerReader(byte @NotNull [] input) {
            this.input = input;
        }

        private byte[] readValue(int expectedTag) {
            if (this.offset >= this.input.length
                    || (this.input[this.offset++] & 0xFF) != expectedTag) {
                return null;
            }

            int length = readLength();
            if (length < 0 || length > this.input.length - this.offset) {
                return null;
            }

            byte[] value = Arrays.copyOfRange(this.input, this.offset, this.offset + length);
            this.offset += length;
            return value;
        }

        private int readLength() {
            if (this.offset >= this.input.length) {
                return -1;
            }

            int first = this.input[this.offset++] & 0xFF;
            if ((first & 0x80) == 0) {
                return first;
            }

            int byteCount = first & 0x7F;
            if (byteCount == 0
                    || byteCount > 4
                    || byteCount > this.input.length - this.offset
                    || this.input[this.offset] == 0) {
                return -1;
            }

            int length = 0;
            for (int index = 0; index < byteCount; index++) {
                length = (length << 8) | (this.input[this.offset++] & 0xFF);
            }
            return length >= 128 ? length : -1;
        }

        private boolean hasRemaining() {
            return this.offset != this.input.length;
        }
    }
}
