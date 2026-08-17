package com.fakeplayerproxy.automation;

import com.fakeplayerproxy.world.player.Player;
import com.velocitypowered.proxy.connection.MinecraftConnection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;

/** Owns registration, replacement, tick scheduling, and removal of Plugin players. */
public final class AutomationManager {
    private static final long TICK_MILLIS = 50L;

    private final Map<com.velocitypowered.api.proxy.Player, Player> players = new ConcurrentHashMap<>();
    private final Logger logger;

    public AutomationManager(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public CompletableFuture<Void> register(com.velocitypowered.api.proxy.Player velocityPlayer) {
        Objects.requireNonNull(velocityPlayer, "velocityPlayer");
        Player player = new Player(velocityPlayer);
        // IDEA reports the borrowed EventLoop as unclosed. Velocity owns its lifecycle.
        //noinspection resource
        var eventLoop = player.eventLoop();
        if (!eventLoop.inEventLoop()) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            eventLoop.execute(() -> register(velocityPlayer).whenComplete((ignored, failure) -> {
                if (failure == null) {
                    future.complete(null);
                } else {
                    future.completeExceptionally(failure);
                }
            }));
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
                closes.add(close);
            }
        });

        CompletableFuture<Void> result = new CompletableFuture<>();
        CompletableFuture.allOf(closes.toArray(CompletableFuture[]::new))
                .whenComplete((ignored, failure) -> eventLoop.execute(() -> {
                    if (failure != null) {
                        logger.warn("Cannot replace an existing automation player", failure);
                        result.completeExceptionally(failure);
                        return;
                    }
                    if (player.backendConnection() == null) {
                        result.complete(null);
                        return;
                    }
                    players.put(velocityPlayer, player);
                    player.automationService().setTickTask(eventLoop.scheduleAtFixedRate(
                            () -> tick(velocityPlayer, player),
                            TICK_MILLIS, TICK_MILLIS, TimeUnit.MILLISECONDS));
                    result.complete(null);
                }));
        return result;
    }

    public Player get(com.velocitypowered.api.proxy.Player player) {
        return players.get(player);
    }

    public Player getByName(String name) {
        Objects.requireNonNull(name, "name");
        return players.values().stream()
                .filter(AutomationManager::isActive)
                .filter(player -> player.velocityPlayer().getUsername().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }

    public List<String> names() {
        return players.values().stream()
                .filter(AutomationManager::isActive)
                .map(player -> player.velocityPlayer().getUsername())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private static boolean isActive(Player player) {
        return !player.automationService().isClosed() && player.backendConnection() != null;
    }

    private void tick(com.velocitypowered.api.proxy.Player velocityPlayer, Player player) {
        AutomationService service = player.automationService();
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
    }

    public void shutdown() {
        players.forEach((velocityPlayer, player) -> {
            if (players.remove(velocityPlayer, player)) {
                // IDEA reports the borrowed EventLoop as unclosed. Velocity owns its lifecycle.
                //noinspection resource
                var eventLoop = player.eventLoop();
                eventLoop.execute(player.automationService()::close);
            }
        });
    }
}
