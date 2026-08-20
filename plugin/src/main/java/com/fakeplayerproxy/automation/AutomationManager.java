package com.fakeplayerproxy.automation;

import com.fakeplayerproxy.utils.Result;
import com.fakeplayerproxy.world.player.Player;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.ClientboundPacketEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.mojang.authlib.exceptions.ForcedUsernameChangeException;
import com.mojang.authlib.exceptions.InsufficientPrivilegesException;
import com.mojang.authlib.exceptions.InvalidCredentialsException;
import com.mojang.authlib.exceptions.UserBannedException;
import io.netty.channel.EventLoop;
import io.netty.buffer.ByteBufUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.jetbrains.annotations.NotNull;
import net.kyori.adventure.text.TranslatableComponent;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundDisconnectPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.clientbound.ClientboundLoginDisconnectPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundResourcePackPushPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundCodeOfConductPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundTransferPacket;

/** Owns registration, replacement, tick scheduling, and removal of Plugin players. */
public final class AutomationManager {
    private static final long TICK_MILLIS = 50L;

    private final Map<com.velocitypowered.api.proxy.Player, Player> players = new ConcurrentHashMap<>();
    private final Logger logger;

    public AutomationManager(@NotNull Logger logger) {
        this.logger = logger;
    }

    public CompletableFuture<Void> register(com.velocitypowered.api.proxy.Player velocityPlayer) {
        if (!(velocityPlayer instanceof ConnectedPlayer connectedPlayer)) {
            logger.warn("Cannot register automation: Velocity player implementation is {}",
                    velocityPlayer == null ? "null" : velocityPlayer.getClass().getName());
            return CompletableFuture.completedFuture(null);
        }
        Player player;
        EventLoop eventLoop;
        try {
            player = new Player(connectedPlayer);
            // IDEA reports the borrowed EventLoop as unclosed. Velocity owns its lifecycle.
            //noinspection resource
            eventLoop = player.eventLoop();
        } catch (RuntimeException | ExceptionInInitializerError failure) {
            logger.error("Cannot initialize automation for Velocity player {}",
                    connectedPlayer.getUsername(), failure);
            return CompletableFuture.completedFuture(null);
        }
        if (!eventLoop.inEventLoop()) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            try {
                eventLoop.execute(() -> register(velocityPlayer).whenComplete((_, failure) -> {
                    if (failure == null) {
                        future.complete(null);
                    } else {
                        future.completeExceptionally(failure);
                    }
                }));
            } catch (RuntimeException submissionFailure) {
                logger.error("Cannot submit automation registration for Velocity player {}",
                        connectedPlayer.getUsername(), submissionFailure);
                future.complete(null);
            }
            return future;
        }
        if (player.backendConnection() == null) {
            return CompletableFuture.completedFuture(null);
        }

        List<CompletableFuture<Void>> closes = new ArrayList<>();
        players.forEach((oldVelocityPlayer, oldPlayer) -> {
            if (oldVelocityPlayer != velocityPlayer
                    && oldVelocityPlayer.getUniqueId().equals(velocityPlayer.getUniqueId())
                    && players.remove(oldVelocityPlayer, oldPlayer)) {
                CompletableFuture<Void> close = new CompletableFuture<>();
                try {
                    // IDEA reports the borrowed EventLoop as unclosed. Velocity owns its lifecycle.
                    //noinspection resource
                    var oldEventLoop = oldPlayer.eventLoop();
                    oldEventLoop.execute(() -> {
                        try {
                            MinecraftConnection oldBackend = oldPlayer.backendConnection();
                            if (oldPlayer.automationService().isAutoReconnect()) {
                                logger.info(
                                        "Auto-reconnect session for {} ({}) on backend {} was replaced by a fresh login.",
                                        oldVelocityPlayer.getUsername(), oldVelocityPlayer.getUniqueId(),
                                        backendName(oldPlayer));
                            }
                            oldPlayer.automationService().close();
                            if (oldBackend == null) {
                                close.complete(null);
                                return;
                            }
                            oldBackend.getChannel().closeFuture().addListener(closed -> {
                                if (closed.isSuccess()) {
                                    close.complete(null);
                                } else {
                                    close.completeExceptionally(closed.cause());
                                }
                            });
                            oldBackend.close();
                        } catch (Throwable failure) {
                            close.completeExceptionally(failure);
                        }
                    });
                } catch (RuntimeException submissionFailure) {
                    close.completeExceptionally(submissionFailure);
                }
                closes.add(close);
            }
        });

        CompletableFuture<Void> result = new CompletableFuture<>();
        CompletableFuture.allOf(closes.toArray(CompletableFuture[]::new))
                .whenComplete((_, failure) -> {
                    try {
                        eventLoop.execute(() -> {
                            if (failure != null) {
                                logger.warn("Cannot replace an existing automation player", failure);
                                result.completeExceptionally(failure);
                                return;
                            }
                            if (player.backendConnection() == null) {
                                result.complete(null);
                                return;
                            }
                            try {
                                var tickTask = eventLoop.scheduleAtFixedRate(
                                        () -> tick(velocityPlayer, player),
                                        TICK_MILLIS, TICK_MILLIS, TimeUnit.MILLISECONDS);
                                player.automationService().setTickTask(tickTask);
                                players.put(velocityPlayer, player);
                            } catch (RuntimeException setupFailure) {
                                logger.error("Cannot schedule automation ticks for Velocity player {}",
                                        connectedPlayer.getUsername(), setupFailure);
                                players.remove(velocityPlayer, player);
                                try {
                                    player.automationService().close();
                                } catch (RuntimeException closeFailure) {
                                    logger.error("Cannot close automation after tick setup failed for {}",
                                            connectedPlayer.getUsername(), closeFailure);
                                }
                                try {
                                    MinecraftConnection backend = player.backendConnection();
                                    if (backend != null) {
                                        backend.close();
                                    }
                                } catch (RuntimeException backendCloseFailure) {
                                    logger.error("Cannot close backend after tick setup failed for {}",
                                            connectedPlayer.getUsername(), backendCloseFailure);
                                }
                            }
                            result.complete(null);
                        });
                    } catch (RuntimeException submissionFailure) {
                        logger.error("Cannot submit automation tick setup for Velocity player {}",
                                connectedPlayer.getUsername(), submissionFailure);
                        result.complete(null);
                    }
                });
        return result;
    }

    @Subscribe
    public EventTask onPostLogin(PostLoginEvent event) {
        return EventTask.withContinuation(continuation ->
                register(event.getPlayer()).whenComplete((_, failure) -> {
                    if (failure == null) {
                        continuation.resume();
                    } else {
                        continuation.resumeWithException(failure);
                    }
                }));
    }

    @Subscribe
    public EventTask onDisconnect(DisconnectEvent event) {
        com.velocitypowered.api.proxy.Player velocityPlayer = event.getPlayer();
        Player player = get(velocityPlayer);
        if (player == null) {
            return EventTask.withContinuation(Continuation::resume);
        }
        return EventTask.withContinuation(continuation -> {
            try {
                // IDEA reports the borrowed EventLoop as unclosed. Velocity owns its lifecycle.
                //noinspection resource
                var eventLoop = player.eventLoop();
                eventLoop.execute(() -> {
                    if (players.get(velocityPlayer) == player
                            && player.automationService().isShadow()) {
                        event.cancel();
                        if (player.automationService().isAutoReconnect()) {
                            logger.info("Auto-reconnect activated for Shadow {} ({}) on backend {}.",
                                    velocityPlayer.getUsername(), velocityPlayer.getUniqueId(),
                                    backendName(player));
                        }
                    }
                    continuation.resume();
                });
            } catch (RuntimeException submissionFailure) {
                continuation.resumeWithException(submissionFailure);
            }
        });
    }

    public Player get(com.velocitypowered.api.proxy.Player player) {
        return players.get(player);
    }

    public Player getByName(@NotNull String name) {
        return players.values().stream()
                .filter(AutomationManager::isActive)
                .filter(player -> player.velocityPlayer().getUsername().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }

    public List<String> names() {
        return players.values().stream()
                .filter(AutomationManager::isActive)
                .filter(player -> player.automationService().isShadow())
                .map(player -> player.velocityPlayer().getUsername())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    public Result<Void, String> kill(@NotNull Player player) {
        // IDEA reports the borrowed EventLoop as unclosed. Velocity owns its lifecycle.
        //noinspection resource
        if (!player.eventLoop().inEventLoop()) {
            return new Result.Failure<>("fakeplayerproxy.command.automation_unavailable");
        }
        if (!player.automationService().isShadow()) {
            return new Result.Failure<>("fakeplayerproxy.command.kill_requires_shadow");
        }
        logger.warn("Auto-reconnect disabled for {} ({}) on backend {}: player kill.",
                player.velocityPlayer().getUsername(), player.velocityPlayer().getUniqueId(),
                backendName(player));
        if (!terminalClose(player.velocityPlayer(), player)) {
            return new Result.Failure<>("fakeplayerproxy.command.automation_unavailable");
        }
        return new Result.Success<>(null);
    }

    @Subscribe(async = false)
    public void onDisconnectPacket(ClientboundPacketEvent<ClientboundDisconnectPacket> event) {
        terminalDisconnect(event, event.getPacket().getReason());
    }

    @Subscribe(async = false)
    public void onLoginDisconnectPacket(
            ClientboundPacketEvent<ClientboundLoginDisconnectPacket> event) {
        terminalDisconnect(event, event.getPacket().getReason());
    }

    @Subscribe(async = false)
    public void onResourcePack(ClientboundPacketEvent<ClientboundResourcePackPushPacket> event) {
        Player player = currentPlayer(event);
        if (player == null || !player.automationService().isAutoReconnect()
                || !event.getPacket().isRequired()) {
            return;
        }
        if (player.velocityPlayer() instanceof ConnectedPlayer connectedPlayer) {
            try {
                if (connectedPlayer.resourcePackHandler()
                        .hasPackAppliedByHash(ByteBufUtil.decodeHexDump(
                        event.getPacket().getHash()))) {
                    return;
                }
            } catch (IllegalArgumentException _) {
                // A malformed required-pack hash cannot identify a pack already applied by the client.
            }
        }
        logger.warn("Auto-reconnect disabled for {} ({}) on backend {}: required resource pack.",
                event.getPlayer().getUsername(), event.getPlayer().getUniqueId(),
                backendName(player));
        terminalClose(event.getPlayer(), player);
    }

    @Subscribe(async = false)
    public void onCodeOfConduct(ClientboundPacketEvent<ClientboundCodeOfConductPacket> event) {
        terminalPacket(event, "Code of Conduct");
    }

    @Subscribe(async = false)
    public void onTransfer(ClientboundPacketEvent<ClientboundTransferPacket> event) {
        terminalPacket(event, "backend Transfer");
    }

    private void terminalDisconnect(
            ClientboundPacketEvent<?> event,
            net.kyori.adventure.text.Component reason) {
        com.velocitypowered.api.proxy.Player velocityPlayer = event.getPlayer();
        Player player = currentPlayer(event);
        if (player == null) {
            return;
        }
        String key = reason instanceof TranslatableComponent translated
                ? translated.key() : "backend kick";
        if (!"multiplayer.disconnect.duplicate_login".equals(key)
                && !"multiplayer.disconnect.banned.reason".equals(key)
                && !"multiplayer.disconnect.banned_ip.reason".equals(key)) {
            if (player.automationService().isAutoReconnect()) {
                logger.warn("Auto-reconnect received a retryable backend kick for {} ({}) on backend {}: backend kick.",
                        velocityPlayer.getUsername(), velocityPlayer.getUniqueId(),
                        backendName(player));
            }
            return;
        }
        terminalPacket(event, key);
    }

    private void terminalPacket(ClientboundPacketEvent<?> event, String category) {
        com.velocitypowered.api.proxy.Player velocityPlayer = event.getPlayer();
        Player player = currentPlayer(event);
        if (player == null) {
            return;
        }
        if (!player.automationService().isAutoReconnect()) {
            return;
        }
        logger.warn("Auto-reconnect disabled for {} ({}) on backend {}: {}.",
                velocityPlayer.getUsername(), velocityPlayer.getUniqueId(),
                backendName(player), category);
        terminalClose(velocityPlayer, player);
    }

    private Player currentPlayer(ClientboundPacketEvent<?> event) {
        Player player = get(event.getPlayer());
        return player != null && event.isSource(player.serverConnection()) ? player : null;
    }

    private static boolean isActive(@NotNull Player player) {
        AutomationService service = player.automationService();
        return !service.isClosed() && (player.backendConnection() != null
                || service.isShadow() && service.isAutoReconnect());
    }

    private void tick(
            @NotNull com.velocitypowered.api.proxy.Player velocityPlayer,
            @NotNull Player player) {
        AutomationService service = player.automationService();
        try {
            if (players.get(velocityPlayer) != player) {
                service.close();
                return;
            }
            MinecraftConnection backend = player.backendConnection();
            if (backend == null) {
                if (service.isShadow() && service.isAutoReconnect()) {
                    boolean backendLost = service.prepareReconnect();
                    String backendName = backendName(player);
                    if (backendLost) {
                        logger.info("Auto-reconnect backend loss for {} ({}) on backend {} scheduled an immediate attempt.",
                                velocityPlayer.getUsername(), velocityPlayer.getUniqueId(), backendName);
                    }
                    int previousAttempt = service.getReconnectAttempt();
                    Throwable reconnectFailure = service.tickReconnect();
                    if (service.getReconnectAttempt() > previousAttempt) {
                        logger.info("Auto-reconnect attempt {} for {} ({}) started on backend {}.",
                                service.getReconnectAttempt(), velocityPlayer.getUsername(),
                                velocityPlayer.getUniqueId(), backendName);
                    }
                    if (reconnectFailure != null) {
                        boolean credentialRejection = switch (reconnectFailure) {
                            case InvalidCredentialsException _, UserBannedException _,
                                 ForcedUsernameChangeException _, InsufficientPrivilegesException _ -> true;
                            default -> false;
                        };
                        if (credentialRejection) {
                            logger.warn("Auto-reconnect disabled for {} ({}) on backend {}: credential rejection.",
                                    velocityPlayer.getUsername(), velocityPlayer.getUniqueId(),
                                    backendName);
                            terminalClose(velocityPlayer, player);
                        } else {
                            logger.warn("Auto-reconnect attempt {} for {} ({}) failed during backend Login on backend {}: retryable failure. Next attempt in {} seconds.",
                                    service.getReconnectAttempt(), velocityPlayer.getUsername(),
                                    velocityPlayer.getUniqueId(), backendName,
                                    service.nextReconnectDelaySeconds());
                        }
                    }
                } else if (players.remove(velocityPlayer, player)) {
                    service.close();
                }
                return;
            }
            int activeAttempt = service.getReconnectAttempt();
            service.tick(backend);
            if (activeAttempt > 0 && service.getReconnectAttempt() == 0) {
                logger.info("Auto-reconnect reached ready PLAY for {} ({}) on backend {} after attempt {}. Automation resumed.",
                        velocityPlayer.getUsername(), velocityPlayer.getUniqueId(),
                        backendName(player), activeAttempt);
            }
        } catch (RuntimeException tickFailure) {
            logger.error("Auto-reconnect controller failed for {} ({}) during attempt {}.",
                    velocityPlayer.getUsername(), velocityPlayer.getUniqueId(),
                    service.getReconnectAttempt(), tickFailure);
            terminalClose(velocityPlayer, player);
        }
    }

    public void shutdown() {
        players.forEach((velocityPlayer, player) -> {
            try {
                // IDEA reports the borrowed EventLoop as unclosed. Velocity owns its lifecycle.
                //noinspection resource
                var eventLoop = player.eventLoop();
                eventLoop.execute(() -> {
                    if (player.automationService().isAutoReconnect()) {
                        logger.info("Auto-reconnect session for {} ({}) on backend {} was cleared during plugin shutdown.",
                                velocityPlayer.getUsername(), velocityPlayer.getUniqueId(),
                                backendName(player));
                    }
                    terminalClose(velocityPlayer, player);
                });
            } catch (RuntimeException shutdownFailure) {
                logger.error("Cannot submit automation shutdown for Velocity player {}",
                        velocityPlayer.getUsername(), shutdownFailure);
            }
        });
    }

    private boolean terminalClose(
            @NotNull com.velocitypowered.api.proxy.Player velocityPlayer,
            @NotNull Player player) {
        MinecraftConnection backend = player.backendConnection();
        var serverConnection = player.serverConnection();
        try {
            player.automationService().disableAutoReconnect();
        } catch (RuntimeException disableFailure) {
            logger.error("Auto-reconnect terminal cleanup could not cancel reconnect work for {} ({}).",
                    velocityPlayer.getUsername(), velocityPlayer.getUniqueId(), disableFailure);
        }
        if (!players.remove(velocityPlayer, player)) {
            return false;
        }
        try {
            player.automationService().close();
        } catch (RuntimeException closeFailure) {
            logger.error("Auto-reconnect terminal cleanup could not close the retained service for {} ({}).",
                    velocityPlayer.getUsername(), velocityPlayer.getUniqueId(), closeFailure);
        }
        try {
            if (backend != null) {
                backend.close();
            } else if (serverConnection != null) {
                serverConnection.disconnect();
            }
        } catch (RuntimeException backendCloseFailure) {
            logger.error("Auto-reconnect terminal cleanup could not close reconnect work for {} ({}).",
                    velocityPlayer.getUsername(), velocityPlayer.getUniqueId(), backendCloseFailure);
        }
        return true;
    }

    private static String backendName(@NotNull Player player) {
        var connection = player.serverConnection();
        return connection == null ? "unavailable" : connection.getServerInfo().getName();
    }
}
