package com.fakeplayerproxy.mod.mixins;

import com.fakeplayerproxy.mod.FakePlayerProxyMod;
import com.fakeplayerproxy.mod.config.ConsentStore;
import com.fakeplayerproxy.mod.gui.FakePlayerProxyConsentScreen;
import com.fakeplayerproxy.mod.packets.ServerHelloPacketEnvelope;
import com.llamalad7.mixinextras.sugar.Local;

import java.math.BigInteger;
import java.security.PublicKey;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;

import com.mojang.datafixers.util.Pair;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ClientboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundKeyPacket;
import net.minecraft.util.Crypt;
import net.minecraft.util.CryptException;
import net.minecraft.util.Util;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds one consent gate to Minecraft's login listener.
 *
 * <p>Minecraft enters its authorizing state and creates one AES key, both
 * ciphers, the proxy digest, and the original response arguments. The injection
 * then stops a supported Server Hello immediately before Minecraft constructs
 * and sends its key response.
 *
 * <p>Allow selects the target digest and acknowledged response. Decline selects
 * the original proxy digest and challenge. Both choices use the same generated
 * key and ciphers, and both continue through Minecraft's authentication and
 * encryption helpers. The target method enters only once.
 */
@Mixin(ClientHandshakePacketListenerImpl.class)
public abstract class MixinClientHandshakePacketListenerImpl {
    @Shadow
    @Final
    private Connection connection;
    @Shadow
    @Final
    private Screen parent;
    @Shadow
    @Final
    private ServerData serverData;

    @Shadow
    private Component authenticateServer(String digest) {
        return null;
    }

    @Shadow
    private void setEncryption(
            ServerboundKeyPacket setKeyPacket,
            Cipher decryptCipher,
            Cipher encryptCipher) {
    }

    /**
     * Stops a supported Hello before authentication or a key response is sent.
     *
     * <p>This exact Minecraft 26.2 boundary captures Vanilla's live login values
     * before it constructs {@link ServerboundKeyPacket}. An empty target-key
     * result leaves every Vanilla action unchanged. A supported carrier creates
     * both encrypted choices from the same generated key, cancels the remaining
     * method, and moves only the user decision to the game thread.
     */
    @Inject(
            method = "handleHello",
            at = @At(
                    value = "NEW",
                    target = "net/minecraft/network/protocol/login/ServerboundKeyPacket"),
            cancellable = true)
    private void prepareConsentChoices(
            ClientboundHelloPacket packet,
            CallbackInfo callbackInfo,
            @Local(name = "decryptCipher") Cipher decryptCipher,
            @Local(name = "encryptCipher") Cipher encryptCipher,
            @Local(name = "digest") String digest,
            @Local(name = "secretKey") SecretKey secretKey,
            @Local(name = "publicKey") PublicKey publicKey,
            @Local(name = "challenge") byte[] challenge) {
        Optional<PublicKey> targetPublicKey =
                ServerHelloPacketEnvelope.decodeTargetPublicKey(publicKey);
        if (targetPublicKey.isEmpty()) {
            return;
        }

        Optional<byte[]> acknowledgement =
                ServerHelloPacketEnvelope.acknowledgement(challenge);
        if (acknowledgement.isEmpty()) {
            callbackInfo.cancel();
            if (challenge == null) {
                FakePlayerProxyMod.LOGGER.error(
                        "Cannot construct FPPACK response: target challenge is unavailable");
            } else {
                FakePlayerProxyMod.LOGGER.error(
                        "Cannot construct FPPACK response: acknowledgement plaintext is {} bytes, "
                                + "but RSA-1024 accepts at most 117 bytes",
                        challenge.length + 7);
            }
            disconnectProxyConnection();
            return;
        }

        PreparedLogin preparedLogin;
        try {
            var vanillaResponse =
                    new ServerboundKeyPacket(secretKey, publicKey, challenge);
            String relayDigest = new BigInteger(Crypt.digestData(
                    packet.getServerId(), targetPublicKey.get(), secretKey)).toString(16);
            var relayResponse = new ServerboundKeyPacket(
                    secretKey, publicKey, acknowledgement.get());
            preparedLogin = new PreparedLogin(
                    Pair.of(digest, vanillaResponse),
                    Pair.of(relayDigest, relayResponse),
                    decryptCipher,
                    encryptCipher,
                    packet.shouldAuthenticate());
        } catch (CryptException | RuntimeException exception) {
            callbackInfo.cancel();
            FakePlayerProxyMod.LOGGER.error(
                    "Cannot prepare FakePlayerProxy login choices: digest or RSA response "
                            + "construction failed",
                    exception);
            disconnectProxyConnection();
            return;
        }

        callbackInfo.cancel();
        try {
            Minecraft.getInstance().execute(() -> openConsentScreen(preparedLogin));
        } catch (RuntimeException exception) {
            FakePlayerProxyMod.LOGGER.error(
                    "Cannot schedule the FakePlayerProxy consent screen on the game thread",
                    exception);
            disconnectProxyConnection();
        }
    }

    /**
     * Replaces ConnectScreen only after the game thread receives the task.
     *
     * <p>The replacement screen delegates ticks to the current ConnectScreen.
     * Its consent result callback receives the immutable prepared login. The
     * separate Escape action closes the connection without authentication or a
     * key response.
     */
    @Unique
    private void openConsentScreen(@NotNull PreparedLogin preparedLogin) {
        var minecraft = Minecraft.getInstance();
        if (!this.connection.isConnected()) {
            this.connection.handleDisconnection();
            return;
        }
        Screen activeScreen = minecraft.gui.screen();
        if (!(activeScreen instanceof ConnectScreen connectionScreen)) {
            FakePlayerProxyMod.LOGGER.error(
                    "Cannot show the FakePlayerProxy consent screen: active screen is {}",
                    activeScreen == null ? "absent" : activeScreen.getClass().getName());
            disconnectProxyConnection();
            return;
        }

        String connectionAddress = String.valueOf(this.connection.getRemoteAddress());
        String serverAddress = this.serverData == null ? connectionAddress : this.serverData.ip;
        ConsentStore decisionStore = null;
        try {
            decisionStore = ConsentStore.fromFabricConfig();
            Optional<Boolean> decision = decisionStore.find(serverAddress);
            if (decision.isPresent()) {
                continueLoginAfterConsent(
                        connectionScreen,
                        preparedLogin,
                        decision.get());
                return;
            }
        } catch (RuntimeException | java.io.IOException exception) {
            FakePlayerProxyMod.LOGGER.error(
                    "Cannot read the saved FakePlayerProxy consent decision for {}",
                    serverAddress,
                    exception);
        }
        ConsentStore availableDecisionStore = decisionStore;

        try {
            minecraft.gui.setScreen(new FakePlayerProxyConsentScreen(
                    connectionScreen,
                    connectionAddress,
                    (allow, remember) -> {
                        if (remember && availableDecisionStore != null) {
                            try {
                                availableDecisionStore.remember(serverAddress, allow);
                            } catch (RuntimeException | java.io.IOException exception) {
                                FakePlayerProxyMod.LOGGER.error(
                                        "Cannot save the FakePlayerProxy consent decision for {}",
                                        serverAddress,
                                        exception);
                            }
                        }
                        continueLoginAfterConsent(connectionScreen, preparedLogin, allow);
                    },
                    () -> {
                        this.connection.disconnect(ConnectScreen.ABORT_CONNECTION);
                        minecraft.gui.setScreen(this.parent);
                    }));
        } catch (RuntimeException exception) {
            FakePlayerProxyMod.LOGGER.error(
                    "Cannot install the FakePlayerProxy consent screen on the active connection",
                    exception);
            disconnectProxyConnection();
        }
    }

    /**
     * Restores ConnectScreen and continues one selected Vanilla login branch.
     *
     * <p>Allow selects the target digest and acknowledged response. Decline
     * selects the proxy digest and original response. The callback extracts only
     * the selected choice, so the unselected ciphertext can be released when
     * this method returns.
     */
    @Unique
    private void continueLoginAfterConsent(
            @NotNull ConnectScreen connectionScreen,
            @NotNull PreparedLogin preparedLogin,
            boolean allow) {
        Minecraft.getInstance().gui.setScreen(connectionScreen);
        if (!this.connection.isConnected()) {
            this.connection.handleDisconnection();
            return;
        }

        Pair<String, ServerboundKeyPacket> choice = allow
                ? preparedLogin.relayChoice()
                : preparedLogin.vanillaChoice();
        try {
            if (preparedLogin.shouldAuthenticate()) {
                Util.ioPool().execute(() -> {
                    Component error = this.authenticateServer(choice.getFirst());
                    if (error != null) {
                        if (this.serverData == null || !this.serverData.isLan()) {
                            this.connection.disconnect(error);
                            return;
                        }
                        FakePlayerProxyMod.LOGGER.warn(error.getString());
                    }

                    this.setEncryption(
                            choice.getSecond(),
                            preparedLogin.decryptCipher(),
                            preparedLogin.encryptCipher());
                });
            } else {
                this.setEncryption(
                        choice.getSecond(),
                        preparedLogin.decryptCipher(),
                        preparedLogin.encryptCipher());
            }
        } catch (RuntimeException exception) {
            FakePlayerProxyMod.LOGGER.error(
                    "Cannot continue the selected FakePlayerProxy login: authentication "
                            + "scheduling or encryption setup failed",
                    exception);
            disconnectProxyConnection();
        }
    }

    /** Uses one localized action after the owning validation or catch site logs the cause. */
    @Unique
    private void disconnectProxyConnection() {
        this.connection.disconnect(Component.translatable(
                "fakeplayerproxy.disconnect.proxy_connection_failed"));
    }

    /** Keeps both user choices and their shared Vanilla continuation values. */
    private record PreparedLogin(
            @NotNull Pair<String, ServerboundKeyPacket> vanillaChoice,
            @NotNull Pair<String, ServerboundKeyPacket> relayChoice,
            @NotNull Cipher decryptCipher,
            @NotNull Cipher encryptCipher,
            boolean shouldAuthenticate) {
    }
}
