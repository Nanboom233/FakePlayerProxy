package com.fakeplayerproxy.mod.packets;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.Arrays;
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
 * <p>An ordinary RSA key returns {@link Status#PASSTHROUGH}. Minecraft then uses
 * its original login flow without changed arguments. A valid carrier returns the
 * target key. The Mixin uses that key for the Mojang session digest, but it keeps
 * the proxy key for the standard key response.
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
    private static final byte[] RSA_ENCRYPTION_OID = new byte[] {
        0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x01
    };

    private ServerHelloPacketEnvelope() {
    }

    /**
     * Classifies the received key and extracts the original target key.
     *
     * <p>The method returns passthrough before it applies relay rules to an
     * ordinary RSA key. If the key declares the relay marker, malformed relay
     * data becomes invalid. This distinction protects ordinary server behavior
     * and rejects a damaged relay request.
     *
     * @param proxyPublicKey the JCA key from the Server Hello
     * @return the classification and the target key for a supported carrier
     */
    public static @NotNull Inspection inspect(@NotNull PublicKey proxyPublicKey) {
        // The PublicKey interface does not expose AlgorithmIdentifier parameters.
        // Read the preserved SPKI bytes to get the relay carrier.
        byte[] encoded = proxyPublicKey.getEncoded();
        if (encoded == null) {
            return Inspection.invalid(null);
        }

        DerReader outer = new DerReader(encoded);
        byte[] subjectPublicKeyInfo = outer.readValue(0x30);
        if (subjectPublicKeyInfo == null || outer.hasRemaining()) {
            return Inspection.passthrough();
        }

        DerReader subjectReader = new DerReader(subjectPublicKeyInfo);
        byte[] algorithmIdentifier = subjectReader.readValue(0x30);
        byte[] subjectPublicKey = subjectReader.readValue(0x03);
        if (algorithmIdentifier == null
                || subjectPublicKey == null
                || subjectReader.hasRemaining()) {
            return Inspection.passthrough();
        }

        DerReader algorithmReader = new DerReader(algorithmIdentifier);
        byte[] oid = algorithmReader.readValue(0x06);
        byte[] envelope = algorithmReader.readValue(0x04);
        // An ordinary RSA key uses NULL parameters. It must keep the Minecraft flow.
        if (envelope == null || !startsWith(envelope)) {
            return Inspection.passthrough();
        }
        if (oid == null
                || !Arrays.equals(oid, RSA_ENCRYPTION_OID)
                || algorithmReader.hasRemaining()) {
            return Inspection.invalid(null);
        }

        int offset = MAGIC.length;
        if (offset >= envelope.length || envelope[offset++] != VERSION) {
            return Inspection.invalid(null);
        }

        VarInt targetLength = readVarInt(envelope, offset);
        if (targetLength == null
                || targetLength.value() <= 0
                || targetLength.value() > MAX_TARGET_KEY_LENGTH
                || targetLength.nextOffset() + targetLength.value() != envelope.length) {
            return Inspection.invalid(null);
        }

        byte[] targetKeyBytes = Arrays.copyOfRange(
                envelope, targetLength.nextOffset(), envelope.length);
        try {
            // Validate the embedded SPKI now. The Mixin receives a usable JCA key.
            PublicKey targetPublicKey = Crypt.byteToPublicKey(targetKeyBytes);
            if (!"RSA".equals(targetPublicKey.getAlgorithm())) {
                return Inspection.invalid(null);
            }
            return Inspection.supported(targetPublicKey);
        } catch (CryptException exception) {
            return Inspection.invalid(exception);
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
    public static @NotNull Optional<byte[]> acknowledgement(byte @NotNull [] challenge) {
        if (ACKNOWLEDGEMENT.length + 1 + challenge.length > MAX_RSA_PLAINTEXT_LENGTH) {
            return Optional.empty();
        }

        byte[] result = new byte[ACKNOWLEDGEMENT.length + 1 + challenge.length];
        System.arraycopy(ACKNOWLEDGEMENT, 0, result, 0, ACKNOWLEDGEMENT.length);
        result[ACKNOWLEDGEMENT.length] = VERSION;
        System.arraycopy(challenge, 0, result, ACKNOWLEDGEMENT.length + 1, challenge.length);
        return Optional.of(result);
    }

    private static boolean startsWith(byte @NotNull [] value) {
        if (value.length < MAGIC.length) {
            return false;
        }
        for (int index = 0; index < MAGIC.length; index++) {
            if (value[index] != MAGIC[index]) {
                return false;
            }
        }
        return true;
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
        int size = 1;
        while ((value & ~0x7F) != 0) {
            value >>>= 7;
            size++;
        }
        return size;
    }

    public enum Status {
        PASSTHROUGH,
        SUPPORTED,
        INVALID
    }

    /**
     * Carries the result of one Server Hello inspection.
     *
     * <p>Passthrough and invalid results do not expose a target key. An invalid
     * result can retain the parsing failure for the client log.
     */
    public static final class Inspection {
        private final Status status;
        private final PublicKey targetPublicKey;
        private final Throwable failure;

        private Inspection(
                @NotNull Status status,
                PublicKey targetPublicKey,
                Throwable failure) {
            this.status = status;
            this.targetPublicKey = targetPublicKey;
            this.failure = failure;
        }

        private static @NotNull Inspection passthrough() {
            return new Inspection(Status.PASSTHROUGH, null, null);
        }

        private static @NotNull Inspection supported(@NotNull PublicKey targetPublicKey) {
            return new Inspection(Status.SUPPORTED, targetPublicKey, null);
        }

        private static @NotNull Inspection invalid(Throwable failure) {
            return new Inspection(Status.INVALID, null, failure);
        }

        public @NotNull Status status() {
            return this.status;
        }

        public PublicKey targetPublicKey() {
            return this.targetPublicKey;
        }

        public Throwable failure() {
            return this.failure;
        }
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
