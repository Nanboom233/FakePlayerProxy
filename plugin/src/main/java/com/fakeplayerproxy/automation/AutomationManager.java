package com.fakeplayerproxy.automation;

import com.fakeplayerproxy.utils.Result;
import com.fakeplayerproxy.world.player.Player;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import io.netty.channel.EventLoop;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.jetbrains.annotations.NotNull;

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
                eventLoop.execute(() -> register(velocityPlayer).whenComplete((ignored, failure) -> {
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
                .whenComplete((ignored, failure) -> {
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
        if (!players.remove(player.velocityPlayer(), player)) {
            return new Result.Failure<>("fakeplayerproxy.command.automation_unavailable");
        }
        MinecraftConnection backend = player.backendConnection();
        player.automationService().close();
        if (backend != null) {
            backend.close();
        }
        return new Result.Success<>(null);
    }

    private static boolean isActive(@NotNull Player player) {
        return !player.automationService().isClosed() && player.backendConnection() != null;
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
                if (players.remove(velocityPlayer, player)) {
                    service.close();
                }
                return;
            }
            service.tick(backend);
        } catch (RuntimeException tickFailure) {
            logger.error("Cannot tick automation for Velocity player {}",
                    velocityPlayer.getUsername(), tickFailure);
            players.remove(velocityPlayer, player);
            try {
                service.close();
            } catch (RuntimeException closeFailure) {
                logger.error("Cannot close failed automation for Velocity player {}",
                        velocityPlayer.getUsername(), closeFailure);
            }
            try {
                MinecraftConnection backend = player.backendConnection();
                if (backend != null) {
                    backend.close();
                }
            } catch (RuntimeException backendCloseFailure) {
                logger.error("Cannot close backend for failed automation player {}",
                        velocityPlayer.getUsername(), backendCloseFailure);
            }
        }
    }

    public void shutdown() {
        players.forEach((velocityPlayer, player) -> {
            if (players.remove(velocityPlayer, player)) {
                try {
                    // IDEA reports the borrowed EventLoop as unclosed. Velocity owns its lifecycle.
                    //noinspection resource
                    var eventLoop = player.eventLoop();
                    eventLoop.execute(player.automationService()::close);
                } catch (RuntimeException shutdownFailure) {
                    logger.error("Cannot submit automation shutdown for Velocity player {}",
                            velocityPlayer.getUsername(), shutdownFailure);
                }
            }
        });
    }
}
