package com.fakeplayerproxy.command;

import static com.velocitypowered.api.command.BrigadierCommand.literalArgumentBuilder;
import static com.velocitypowered.api.command.BrigadierCommand.requiredArgumentBuilder;

import com.fakeplayerproxy.automation.AutomationManager;
import com.fakeplayerproxy.config.PermissionProvider;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;

/** Owns the local Carpet-style player action tree. */
public final class PlayerCommand {
    private final AutomationManager automationManager;

    public PlayerCommand(AutomationManager automationManager) {
        this.automationManager = Objects.requireNonNull(automationManager, "automationManager");
    }

    public BrigadierCommand create() {
        var root = literalArgumentBuilder("player");
        var target = requiredArgumentBuilder("player", StringArgumentType.word())
                .suggests(this::suggestPlayers);

        // Shadow
        var shadow = literalArgumentBuilder("shadow")
                .executes(context -> {
                    com.fakeplayerproxy.world.player.Player player;
                    if (context.getArguments().containsKey("player")) {
                        player = automationManager.getByName(
                                StringArgumentType.getString(context, "player"));
                        if (player == null) {
                            context.getSource().sendMessage(Component.translatable(
                                    "fakeplayerproxy.command.target_unavailable"));
                            return 0;
                        }
                    } else {
                        if (!(context.getSource() instanceof Player sourcePlayer)) {
                            context.getSource().sendMessage(Component.translatable(
                                    "fakeplayerproxy.command.player_required"));
                            return 0;
                        }
                        player = automationManager.get(sourcePlayer);
                    }
                    if (player == null) {
                        context.getSource().sendMessage(Component.translatable(
                                "fakeplayerproxy.command.automation_unavailable"));
                        return 0;
                    }
                    player.automationService().shadow().thenAccept(enabled -> {
                        if (!enabled) {
                            context.getSource().sendMessage(Component.translatable(
                                    "fakeplayerproxy.command.automation_unavailable"));
                        }
                    });
                    return 1;
                })
                .build();

        root.then(shadow);
        target.then(shadow);
        root.then(literalArgumentBuilder("as")
                .requires(source -> source.hasPermission(PermissionProvider.OP_PERMISSION))
                .then(target));
        return new BrigadierCommand(root);
    }

    private CompletableFuture<Suggestions> suggestPlayers(
            CommandContext<CommandSource> context,
            SuggestionsBuilder builder) {
        automationManager.names().forEach(builder::suggest);
        return builder.buildFuture();
    }
}
