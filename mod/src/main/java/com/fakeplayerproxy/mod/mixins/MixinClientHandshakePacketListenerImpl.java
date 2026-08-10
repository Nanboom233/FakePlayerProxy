package com.fakeplayerproxy.mod.mixins;

import com.fakeplayerproxy.mod.FakePlayerProxyMod;
import com.fakeplayerproxy.mod.packets.ServerHelloPacketEnvelope;
import java.security.PublicKey;
import java.util.Objects;

import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ClientboundHelloPacket;
import net.minecraft.util.CryptException;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Connects the decorated Server Hello to Minecraft's normal login code.
 *
 * <p>The head hook inspects the public key once. An ordinary key leaves both
 * later argument hooks inactive. A supported key gives the first argument hook
 * the original target key for the Mojang session digest.
 *
 * <p>The second argument hook keeps the proxy key and client-generated AES key
 * in the standard key packet. It changes only the challenge plaintext to the
 * Mod acknowledgement. Velocity can then recover the AES key and relay the same
 * key to the target server.
 *
 * <p>The Mixin does not install encryption and does not send a custom packet.
 * Minecraft keeps ownership of packet send order and frontend cipher setup.
 */
@Mixin(ClientHandshakePacketListenerImpl.class)
public abstract class MixinClientHandshakePacketListenerImpl {
    @Shadow @Final private @NotNull Connection connection;

    // These values belong to one handleHello call. The head hook clears old data.
    @Unique
    private PublicKey fakePlayerProxy$targetPublicKey;

    @Unique
    private byte[] fakePlayerProxy$acknowledgement;

    /**
     * Inspects the Server Hello before Minecraft computes the session digest.
     *
     * <p>A malformed declared carrier cannot continue as an ordinary key. The
     * hook logs its diagnostic cause and disconnects with a stable message.
     * Ordinary malformed keys remain under Minecraft's existing error handling.
     */
    @Inject(method = "handleHello", at = @At("HEAD"), cancellable = true)
    private void fakePlayerProxy$inspectServerHello(
            @NotNull ClientboundHelloPacket packet, @NotNull CallbackInfo callbackInfo) {
        this.fakePlayerProxy$targetPublicKey = null;
        this.fakePlayerProxy$acknowledgement = null;

        try {
            PublicKey proxyPublicKey = packet.getPublicKey();
            ServerHelloPacketEnvelope.Inspection inspection =
                    ServerHelloPacketEnvelope.inspect(proxyPublicKey);
            if (inspection.status() == ServerHelloPacketEnvelope.Status.PASSTHROUGH) {
                return;
            }
            if (inspection.status() == ServerHelloPacketEnvelope.Status.INVALID) {
                fakePlayerProxy$rejectInvalidEnvelope(inspection.failure(), callbackInfo);
                return;
            }

            PublicKey targetPublicKey = inspection.targetPublicKey();
            byte[] challenge = packet.getChallenge();
            if (targetPublicKey == null) {
                fakePlayerProxy$rejectInvalidEnvelope(null, callbackInfo);
                return;
            }
            byte[] acknowledgement = ServerHelloPacketEnvelope
                    .acknowledgement(challenge)
                    .orElse(null);
            if (acknowledgement == null) {
                fakePlayerProxy$rejectInvalidEnvelope(null, callbackInfo);
                return;
            }

            this.fakePlayerProxy$targetPublicKey = targetPublicKey;
            this.fakePlayerProxy$acknowledgement = acknowledgement;
        } catch (CryptException ignored) {
            // This key did not expose a relay carrier. Minecraft owns its error path.
        }
    }

    /**
     * Rejects a key that declares FakePlayerProxy but has invalid relay data.
     *
     * <p>The log keeps the parsing cause for diagnosis. The player receives no
     * internal key data or exception text.
     */
    @Unique
    private void fakePlayerProxy$rejectInvalidEnvelope(
            Throwable failure, @NotNull CallbackInfo callbackInfo) {
        if (failure == null) {
            FakePlayerProxyMod.LOGGER.error("Invalid FakePlayerProxy Server Hello envelope");
        } else {
            FakePlayerProxyMod.LOGGER.error(
                    "Invalid FakePlayerProxy Server Hello envelope", failure);
        }
        this.connection.disconnect(Component.literal(
                "Unable to continue the proxy connection. Please try again."));
        callbackInfo.cancel();
    }

    /**
     * Uses the target key only for the Mojang session digest.
     *
     * <p>The target server validates this digest against the real client session.
     * The standard key response still uses the proxy key, which lets Velocity
     * decrypt the client-generated AES key.
     */
    @ModifyArg(
            method = "handleHello",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/Crypt;digestData(Ljava/lang/String;"
                            + "Ljava/security/PublicKey;Ljavax/crypto/SecretKey;)[B"),
            index = 1)
    private @NotNull PublicKey fakePlayerProxy$useTargetJoinKey(
            @NotNull PublicKey proxyPublicKey) {
        return Objects.requireNonNullElse(this.fakePlayerProxy$targetPublicKey, proxyPublicKey);
    }

    /**
     * Places the Mod acknowledgement in the standard encrypted challenge field.
     *
     * <p>The acknowledgement proves support and binds the response to the target
     * challenge. The proxy public key and the AES key remain unchanged.
     */
    @ModifyArg(
            method = "handleHello",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/protocol/login/ServerboundKeyPacket;<init>"
                            + "(Ljavax/crypto/SecretKey;Ljava/security/PublicKey;[B)V"),
            index = 2)
    private byte @NotNull [] fakePlayerProxy$acknowledgeServerHello(
            byte @NotNull [] challenge) {
        if (this.fakePlayerProxy$acknowledgement == null) {
            return challenge;
        }
        return this.fakePlayerProxy$acknowledgement.clone();
    }
}
