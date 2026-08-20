package com.fakeplayerproxy.utils;

import com.fakeplayerproxy.automation.AutomationManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/** Owns explicit client consent and the bounded access-token response channel. */
public final class AuthManager {
    public static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.create(
            "fakeplayerproxy", "auto_reconnect_v1");
    private static final int MAX_TOKEN_BYTES = 8192;

    private final AutomationManager automationManager;
    private final Logger logger;

    public AuthManager(@NotNull AutomationManager automationManager, @NotNull Logger logger) {
        this.automationManager = automationManager;
        this.logger = logger;
    }

    // IDEA's duplication warning is a false positive. This command's side effects own its flow.
    @SuppressWarnings("DuplicatedCode")
    public boolean request(@NotNull Player velocityPlayer) {
        var player = automationManager.get(velocityPlayer);
        if (player == null) {
            return false;
        }
        var serverConnection = player.serverConnection();
        if (player.backendConnection() == null || serverConnection == null
                || player.automationService().isClosed()) {
            return false;
        }
        player.automationService().disableAutoReconnect();
        boolean sent = velocityPlayer.sendPluginMessage(CHANNEL, new byte[0]);
        if (sent) {
            logger.info("Auto-reconnect consent requested for {} ({}) on backend {}.",
                    velocityPlayer.getUsername(), velocityPlayer.getUniqueId(),
                    serverConnection.getServerInfo().getName());
        }
        return sent;
    }

    // IDEA's duplication warning is a false positive. This command's side effects own its flow.
    @SuppressWarnings("DuplicatedCode")
    public boolean disable(@NotNull Player velocityPlayer) {
        var player = automationManager.get(velocityPlayer);
        if (player == null) {
            return false;
        }
        var serverConnection = player.serverConnection();
        if (player.backendConnection() == null || serverConnection == null
                || player.automationService().isClosed()) {
            return false;
        }
        player.automationService().disableAutoReconnect();
        velocityPlayer.sendMessage(Component.translatable(
                "fakeplayerproxy.command.auto_reconnect_disabled"));
        logger.info("Auto-reconnect disabled for {} ({}) on backend {}: explicit command.",
                velocityPlayer.getUsername(), velocityPlayer.getUniqueId(),
                serverConnection.getServerInfo().getName());
        return true;
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!CHANNEL.equals(event.getIdentifier())) {
            return;
        }
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (event.getSource() instanceof ServerConnection backend) {
            logger.warn("Auto-reconnect authorization response rejected from backend {}.",
                    backend.getServerInfo().getName());
            return;
        }
        if (!(event.getSource() instanceof Player velocityPlayer)) {
            logger.warn("Auto-reconnect authorization response rejected from an unknown source.");
            return;
        }
        if (!(event.getTarget() instanceof ServerConnection backend)) {
            logger.warn("Auto-reconnect authorization response rejected for {} ({}): missing backend target.",
                    velocityPlayer.getUsername(), velocityPlayer.getUniqueId());
            return;
        }
        var player = currentPlayer(velocityPlayer, backend);
        if (player == null || player.automationService().isClosed()) {
            logger.warn("Auto-reconnect authorization response ignored for stale player {} ({}).",
                    velocityPlayer.getUsername(), velocityPlayer.getUniqueId());
            return;
        }

        byte[] data = event.getData();
        int index = 0;
        int length = 0;
        int shift = 0;
        boolean terminated = false;
        while (index < data.length && shift < 35) {
            int value = data[index++] & 0xff;
            length |= (value & 0x7f) << shift;
            if ((value & 0x80) == 0) {
                terminated = true;
                break;
            }
            shift += 7;
        }
        if (!terminated || length < 0
                || length > MAX_TOKEN_BYTES || data.length - index != length) {
            logger.warn("Auto-reconnect authorization response rejected for {} ({}): malformed payload.",
                    velocityPlayer.getUsername(), velocityPlayer.getUniqueId());
            return;
        }
        byte[] token = Arrays.copyOfRange(data, index, data.length);
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(token));
        } catch (CharacterCodingException failure) {
            Arrays.fill(token, (byte) 0);
            logger.warn("Auto-reconnect authorization response rejected for {} ({}): malformed UTF-8.",
                    velocityPlayer.getUsername(), velocityPlayer.getUniqueId());
            return;
        }

        var eventLoop = player.eventLoop();
        Runnable applyAuthorization = () -> {
            try {
                if (currentPlayer(velocityPlayer, backend) != player
                        || player.automationService().isClosed()) {
                    logger.warn("Auto-reconnect authorization response ignored for stale player {} ({}).",
                            velocityPlayer.getUsername(), velocityPlayer.getUniqueId());
                    return;
                }
                if (token.length == 0) {
                    player.automationService().disableAutoReconnect();
                    logger.info("Auto-reconnect consent declined for {} ({}) on backend {}.",
                            velocityPlayer.getUsername(), velocityPlayer.getUniqueId(),
                            backend.getServerInfo().getName());
                    return;
                }
                player.automationService().enableAutoReconnect(token);
                velocityPlayer.sendMessage(Component.translatable(
                        "fakeplayerproxy.command.auto_reconnect_enabled"));
                logger.info("Auto-reconnect enabled for {} ({}) on backend {}.",
                        velocityPlayer.getUsername(), velocityPlayer.getUniqueId(),
                        backend.getServerInfo().getName());
            } catch (Throwable callbackFailure) {
                logger.error("Cannot apply auto-reconnect authorization for {} ({}) on backend {}.",
                        velocityPlayer.getUsername(), velocityPlayer.getUniqueId(),
                        backend.getServerInfo().getName(), callbackFailure);
            } finally {
                Arrays.fill(token, (byte) 0);
            }
        };
        try {
            eventLoop.execute(applyAuthorization);
        } catch (Throwable submissionFailure) {
            Arrays.fill(token, (byte) 0);
            logger.error("Cannot submit auto-reconnect authorization for {} ({}) on backend {}.",
                    velocityPlayer.getUsername(), velocityPlayer.getUniqueId(),
                    backend.getServerInfo().getName(), submissionFailure);
        }
    }

    private com.fakeplayerproxy.world.player.Player currentPlayer(
            Player velocityPlayer, ServerConnection backend) {
        var player = automationManager.get(velocityPlayer);
        return player != null && player.serverConnection() == backend ? player : null;
    }
}
