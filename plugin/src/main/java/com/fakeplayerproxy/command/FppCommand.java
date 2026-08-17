package com.fakeplayerproxy.command;

import static com.velocitypowered.api.command.BrigadierCommand.literalArgumentBuilder;
import static com.velocitypowered.api.command.BrigadierCommand.requiredArgumentBuilder;

import com.fakeplayerproxy.config.PermissionProvider;
import com.fakeplayerproxy.util.ProxyResult;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

/** Owns the local FakePlayerProxy configuration command tree. */
public final class FppCommand {
    private final ProxyServer server;
    private final PermissionProvider permissionProvider;
    private final Logger logger;

    public FppCommand(ProxyServer server, PermissionProvider permissionProvider, Logger logger) {
        this.server = Objects.requireNonNull(server, "server");
        this.permissionProvider = Objects.requireNonNull(permissionProvider, "permissionProvider");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public BrigadierCommand create() {
        var root = literalArgumentBuilder("fpp");
        root.then(literalArgumentBuilder("op")
                .requires(source -> source.hasPermission(PermissionProvider.OP_PERMISSION))
                .then(requiredArgumentBuilder("player", StringArgumentType.word())
                        .suggests(this::suggestOnlinePlayers)
                        .executes(context -> {
                            String name = StringArgumentType.getString(context, "player");
                            Player player = server.getPlayer(name)
                                    .filter(Player::isOnlineMode)
                                    .orElse(null);
                            if (player == null) {
                                context.getSource().sendMessage(Component.translatable(
                                        "fakeplayerproxy.command.operator_player_unavailable"));
                                return 0;
                            }
                            permissionProvider.grant(player).thenAccept(result -> {
                                if (result.isSuccess()) {
                                    player.refreshCommands();
                                }
                                render(context.getSource(), result,
                                        "fakeplayerproxy.command.operator_added");
                            });
                            return 1;
                        })));
        root.then(literalArgumentBuilder("deop")
                .requires(source -> source.hasPermission(PermissionProvider.OP_PERMISSION))
                .then(requiredArgumentBuilder("player", StringArgumentType.word())
                        .suggests(this::suggestOperators)
                        .executes(context -> {
                            permissionProvider.revoke(StringArgumentType.getString(context, "player"))
                                    .thenAccept(result -> {
                                        if (result.isSuccess()) {
                                            server.getPlayer(result.valueOrThrow())
                                                    .ifPresent(Player::refreshCommands);
                                        }
                                        render(context.getSource(), result,
                                                "fakeplayerproxy.command.operator_removed");
                                    });
                            return 1;
                        })));
        return new BrigadierCommand(root);
    }

    private CompletableFuture<Suggestions> suggestOnlinePlayers(
            CommandContext<CommandSource> context,
            SuggestionsBuilder builder) {
        server.getAllPlayers().stream()
                .filter(Player::isOnlineMode)
                .map(Player::getUsername)
                .forEach(builder::suggest);
        return builder.buildFuture();
    }

    private CompletableFuture<Suggestions> suggestOperators(
            CommandContext<CommandSource> context,
            SuggestionsBuilder builder) {
        permissionProvider.names().forEach(builder::suggest);
        return builder.buildFuture();
    }

    private void render(
            CommandSource source,
            ProxyResult<String> result,
            String successKey) {
        if (result.isSuccess()) {
            source.sendMessage(Component.translatable(
                    successKey, Component.text(result.valueOrThrow())));
            return;
        }
        logger.warn("Cannot update the FakePlayerProxy operator configuration: {}",
                result.errorOrThrow().safeMessage());
        String key = "operator_not_found".equals(result.errorOrThrow().code())
                ? "fakeplayerproxy.command.operator_not_found"
                : "fakeplayerproxy.command.operator_update_failed";
        source.sendMessage(Component.translatable(key));
    }
}
