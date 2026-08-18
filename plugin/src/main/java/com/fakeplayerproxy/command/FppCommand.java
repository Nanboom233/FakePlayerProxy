package com.fakeplayerproxy.command;

import static com.velocitypowered.api.command.BrigadierCommand.literalArgumentBuilder;
import static com.velocitypowered.api.command.BrigadierCommand.requiredArgumentBuilder;

import com.fakeplayerproxy.utils.PermissionProvider;
import com.fakeplayerproxy.utils.Result;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/** Owns the local FakePlayerProxy configuration command tree. */
public final class FppCommand {
    private final ProxyServer server;
    private final PermissionProvider permissionProvider;
    private final Logger logger;

    public FppCommand(
            @NotNull ProxyServer server,
            @NotNull PermissionProvider permissionProvider,
            @NotNull Logger logger) {
        this.server = server;
        this.permissionProvider = permissionProvider;
        this.logger = logger;
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
                                switch (result) {
                                    case Result.Success<String, String>(var storedName) -> {
                                        player.refreshCommands();
                                        context.getSource().sendMessage(Component.translatable(
                                                "fakeplayerproxy.command.operator_added",
                                                Component.text(storedName)));
                                    }
                                    case Result.Failure<String, String>(var error) -> {
                                        logger.warn(
                                                "Cannot update the FakePlayerProxy operator configuration: {}",
                                                error);
                                        context.getSource().sendMessage(Component.translatable(
                                                "fakeplayerproxy.command.operator_update_failed"));
                                    }
                                }
                            });
                            return 1;
                        })));
        root.then(literalArgumentBuilder("deop")
                .requires(source -> source.hasPermission(PermissionProvider.OP_PERMISSION))
                .then(requiredArgumentBuilder("player", StringArgumentType.word())
                        .suggests(this::suggestOperators)
                        .executes(context -> {
                            String name = StringArgumentType.getString(context, "player");
                            permissionProvider.revoke(name)
                                    .thenAccept(result -> {
                                        switch (result) {
                                            case Result.Success<Optional<String>, String>(var removedName) -> {
                                                if (removedName.isEmpty()) {
                                                    logger.warn(
                                                            "Cannot deop the FakePlayerProxy operator: No saved operator has the name {}.",
                                                            name);
                                                    context.getSource().sendMessage(Component.translatable(
                                                            "fakeplayerproxy.command.operator_not_found"));
                                                    return;
                                                }
                                                String storedName = removedName.orElseThrow();
                                                server.getPlayer(storedName).ifPresent(Player::refreshCommands);
                                                context.getSource().sendMessage(Component.translatable(
                                                        "fakeplayerproxy.command.operator_removed",
                                                        Component.text(storedName)));
                                            }
                                            case Result.Failure<Optional<String>, String>(var error) -> {
                                                logger.warn(
                                                        "Cannot deop the FakePlayerProxy operator: {}",
                                                        error);
                                                context.getSource().sendMessage(Component.translatable(
                                                        "fakeplayerproxy.command.operator_update_failed"));
                                            }
                                        }
                                    });
                            return 1;
                        })));
        return new BrigadierCommand(root);
    }

    private CompletableFuture<Suggestions> suggestOnlinePlayers(
            // IDEA reports this callback context as unused. Brigadier owns the callback signature.
            //noinspection unused
            CommandContext<CommandSource> context,
            SuggestionsBuilder builder) {
        server.getAllPlayers().stream()
                .filter(Player::isOnlineMode)
                .map(Player::getUsername)
                .forEach(builder::suggest);
        return builder.buildFuture();
    }

    private CompletableFuture<Suggestions> suggestOperators(
            // IDEA reports this callback context as unused. Brigadier owns the callback signature.
            //noinspection unused
            CommandContext<CommandSource> context,
            SuggestionsBuilder builder) {
        permissionProvider.names().forEach(builder::suggest);
        return builder.buildFuture();
    }
}
