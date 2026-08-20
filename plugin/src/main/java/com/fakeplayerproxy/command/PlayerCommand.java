package com.fakeplayerproxy.command;

import static com.velocitypowered.api.command.BrigadierCommand.literalArgumentBuilder;
import static com.velocitypowered.api.command.BrigadierCommand.requiredArgumentBuilder;

import com.fakeplayerproxy.automation.ActionMode;
import com.fakeplayerproxy.automation.AutomationManager;
import com.fakeplayerproxy.utils.PermissionProvider;
import com.fakeplayerproxy.utils.Result;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.CommandContextBuilder;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import org.cloudburstmc.math.vector.Vector3d;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/** Owns the local Carpet-style player action tree. */
public final class PlayerCommand {
    private static final String INVALID_POSITION = "fakeplayerproxy.command.invalid_position";

    private final AutomationManager automationManager;
    private final Logger logger;

    public PlayerCommand(@NotNull AutomationManager automationManager, @NotNull Logger logger) {
        this.automationManager = automationManager;
        this.logger = logger;
    }

    public BrigadierCommand create() {
        var root = literalArgumentBuilder("player");
        var target = requiredArgumentBuilder("player", StringArgumentType.word())
                .suggests(this::suggestPlayers);

        // Lifecycle actions
        var stop = literalArgumentBuilder("stop")
                .executes(context -> execute(context, player -> player.automationService().stopActions()))
                .build();
        var kill = literalArgumentBuilder("kill")
                .requiresWithContext((context, _) -> isShadowTarget(context))
                .executes(context -> execute(context, automationManager::kill))
                .build();
        var shadow = literalArgumentBuilder("shadow")
                .executes(context -> execute(
                        context,
                        (player, _) -> {
                            return player.automationService().shadow();
                        }))
                .build();

        // Scheduled actions
        var use = scheduledAction(
                "use", player -> player.automationService()::use).build();
        var jump = scheduledAction(
                "jump", player -> player.automationService()::jump)
                .requiresWithContext((context, _) -> isShadowTarget(context))
                .build();
        var attack = scheduledAction(
                "attack", player -> player.automationService()::attack).build();
        var drop = scheduledAction(
                "drop", player -> (mode, interval) -> player.automationService()
                        .dropSelectedItem(false, mode, interval))
                .then(literalArgumentBuilder("all").executes(context -> execute(
                        context, player -> player.automationService().dropAll(false))))
                .then(literalArgumentBuilder("mainhand").executes(context -> execute(
                        context, player -> player.automationService().drop(false, -1))))
                .then(literalArgumentBuilder("offhand").executes(context -> execute(
                        context, player -> player.automationService().drop(false, 40))))
                .then(requiredArgumentBuilder("slot", IntegerArgumentType.integer(0, 40))
                        .executes(context -> execute(context, player -> player.automationService().drop(
                                false, IntegerArgumentType.getInteger(context, "slot")))))
                .build();
        var dropStack = scheduledAction(
                "dropStack", player -> (mode, interval) -> player.automationService()
                        .dropSelectedItem(true, mode, interval))
                .then(literalArgumentBuilder("all").executes(context -> execute(
                        context, player -> player.automationService().dropAll(true))))
                .then(literalArgumentBuilder("mainhand").executes(context -> execute(
                        context, player -> player.automationService().drop(true, -1))))
                .then(literalArgumentBuilder("offhand").executes(context -> execute(
                        context, player -> player.automationService().drop(true, 40))))
                .then(requiredArgumentBuilder("slot", IntegerArgumentType.integer(0, 40))
                        .executes(context -> execute(context, player -> player.automationService().drop(
                                true, IntegerArgumentType.getInteger(context, "slot")))))
                .build();
        var swapHands = scheduledAction(
                "swapHands", player -> player.automationService()::swapHands).build();

        // Inventory actions
        var hotbar = literalArgumentBuilder("hotbar")
                .then(requiredArgumentBuilder("slot", IntegerArgumentType.integer(1, 9))
                        .executes(context -> execute(context, player -> player.automationService()
                                .selectHotbar(IntegerArgumentType.getInteger(context, "slot")))))
                .build();

        // Input actions
        var sneak = literalArgumentBuilder("sneak")
                .executes(context -> execute(context, player -> player.automationService().setSneak(true)))
                .build();
        var unsneak = literalArgumentBuilder("unsneak")
                .executes(context -> execute(context, player -> player.automationService().setSneak(false)))
                .build();
        var sprint = literalArgumentBuilder("sprint")
                .requiresWithContext((context, _) -> isShadowTarget(context))
                .executes(context -> execute(context, player -> player.automationService().setSprint(true)))
                .build();
        var unsprint = literalArgumentBuilder("unsprint")
                .requiresWithContext((context, _) -> isShadowTarget(context))
                .executes(context -> execute(context, player -> player.automationService().setSprint(false)))
                .build();
        var move = literalArgumentBuilder("move")
                .requiresWithContext((context, _) -> isShadowTarget(context))
                .executes(context -> execute(context, player -> player.automationService().move(null)));
        for (String direction : new String[] {"forward", "backward", "left", "right"}) {
            move.then(literalArgumentBuilder(direction).executes(context -> execute(
                    context, player -> player.automationService().move(direction))));
        }
        var dismount = literalArgumentBuilder("dismount")
                .executes(context -> execute(context, player -> player.automationService().dismount()))
                .build();

        // Rotation actions
        var look = literalArgumentBuilder("look");
        addLookLiteral(look, "north", 180.0f);
        addLookLiteral(look, "south", 0.0f);
        addLookLiteral(look, "east", -90.0f);
        addLookLiteral(look, "west", 90.0f);
        look.then(literalArgumentBuilder("up").executes(context -> execute(
                context, player -> player.automationService().look(player.yaw(), -90.0f))));
        look.then(literalArgumentBuilder("down").executes(context -> execute(
                context, player -> player.automationService().look(player.yaw(), 90.0f))));
        look.then(requiredArgumentBuilder("yaw", FloatArgumentType.floatArg())
                .then(requiredArgumentBuilder("pitch", FloatArgumentType.floatArg())
                        .executes(context -> execute(context, player -> player.automationService().look(
                                FloatArgumentType.getFloat(context, "yaw"),
                                FloatArgumentType.getFloat(context, "pitch"))))));
        look.then(literalArgumentBuilder("at")
                .then(requiredArgumentBuilder("position", StringArgumentType.greedyString())
                        .suggests(this::suggestPosition)
                        .executes(context -> execute(context, player -> {
                            Result<Vector3d, String> position = resolvePosition(
                                    StringArgumentType.getString(context, "position"), player);
                            if (position instanceof Result.Failure<Vector3d, String>(var error)) {
                                return new Result.Failure<>(error);
                            }
                            return player.automationService().lookAt(
                                    ((Result.Success<Vector3d, String>) position).value());
                        }))));

        var turn = literalArgumentBuilder("turn");
        addTurnLiteral(turn, "left", -90.0f);
        addTurnLiteral(turn, "right", 90.0f);
        addTurnLiteral(turn, "back", 180.0f);
        turn.then(requiredArgumentBuilder("yaw-delta", FloatArgumentType.floatArg())
                .then(requiredArgumentBuilder("pitch-delta", FloatArgumentType.floatArg())
                        .executes(context -> execute(context, player -> player.automationService().turn(
                                FloatArgumentType.getFloat(context, "yaw-delta"),
                                FloatArgumentType.getFloat(context, "pitch-delta"))))));

        // Mount
        var mount = literalArgumentBuilder("mount")
                .executes(context -> execute(context, player -> player.automationService().mount(
                        player.position(), false)))
                .then(requiredArgumentBuilder("position", StringArgumentType.greedyString())
                        .suggests(this::suggestPosition)
                        .executes(context -> execute(context, player -> {
                            Result<Vector3d, String> position = resolvePosition(
                                    StringArgumentType.getString(context, "position"), player);
                            if (position instanceof Result.Failure<Vector3d, String>(var error)) {
                                return new Result.Failure<>(error);
                            }
                            return player.automationService().mount(
                                    ((Result.Success<Vector3d, String>) position).value(), true);
                        })))
                .build();

        var ordinaryNodes = java.util.List.of(
                stop, use, attack, drop, dropStack, swapHands, hotbar, shadow, mount,
                dismount, sneak, unsneak, look.build(), turn.build());
        ordinaryNodes.forEach(node -> {
            root.then(node);
            target.then(node);
        });
        java.util.List.of(jump, kill, sprint, unsprint, move.build()).forEach(target::then);
        root.then(literalArgumentBuilder("as")
                .requires(source -> source.hasPermission(PermissionProvider.OP_PERMISSION))
                .then(target));
        return new BrigadierCommand(root);
    }

    private LiteralArgumentBuilder<CommandSource> scheduledAction(
            String name,
            Function<com.fakeplayerproxy.world.player.Player,
                    BiFunction<ActionMode, Integer, Result<Void, String>>> operation) {
        return literalArgumentBuilder(name)
                .executes(context -> execute(
                        context, player -> operation.apply(player).apply(ActionMode.ONCE, 0)))
                .then(literalArgumentBuilder("once").executes(
                        context -> execute(
                                context, player -> operation.apply(player).apply(ActionMode.ONCE, 0))))
                .then(literalArgumentBuilder("continuous").executes(context -> execute(
                        context, player -> operation.apply(player).apply(ActionMode.CONTINUOUS, 0))))
                .then(literalArgumentBuilder("interval")
                        .then(requiredArgumentBuilder("ticks", IntegerArgumentType.integer(1))
                                .executes(context -> execute(context, player -> operation.apply(player).apply(
                                        ActionMode.INTERVAL,
                                        IntegerArgumentType.getInteger(context, "ticks"))))));
    }

    private void addLookLiteral(
            LiteralArgumentBuilder<CommandSource> parent, String name, float yaw) {
        parent.then(literalArgumentBuilder(name).executes(context -> execute(
                context, player -> player.automationService().look(yaw, 0.0f))));
    }

    private void addTurnLiteral(LiteralArgumentBuilder<CommandSource> parent, String name, float yaw) {
        parent.then(literalArgumentBuilder(name).executes(context -> execute(
                context, player -> player.automationService().turn(yaw, 0.0f))));
    }

    private int execute(
            CommandContext<CommandSource> context,
            Function<com.fakeplayerproxy.world.player.Player, Result<Void, String>> operation) {
        return execute(context, (player, source) -> {
            // IDEA reports the borrowed Velocity EventLoop as unclosed. Velocity owns its lifecycle.
            //noinspection resource
            var eventLoop = player.eventLoop();
            try {
                eventLoop.execute(() -> {
                    try {
                        Result<Void, String> result = operation.apply(player);
                        if (result instanceof Result.Failure<Void, String>(var error)) {
                            source.sendMessage(Component.translatable(error));
                        }
                    } catch (RuntimeException actionFailure) {
                        logger.error("Cannot execute the selected automation player action", actionFailure);
                        source.sendMessage(Component.translatable(
                                "fakeplayerproxy.command.automation_unavailable"));
                    }
                });
            } catch (RuntimeException submissionFailure) {
                logger.error("Cannot submit the selected automation player action", submissionFailure);
                source.sendMessage(Component.translatable(
                        "fakeplayerproxy.command.automation_unavailable"));
            }
        });
    }

    private int execute(
            CommandContext<CommandSource> context,
            BiFunction<com.fakeplayerproxy.world.player.Player, CommandSource,
                    CompletableFuture<Boolean>> operation) {
        return execute(context, (player, source) -> {
            CompletableFuture<Boolean> completion;
            try {
                completion = operation.apply(player, source);
            } catch (RuntimeException actionFailure) {
                logger.error("Cannot start the selected asynchronous automation player action",
                        actionFailure);
                source.sendMessage(Component.translatable(
                        "fakeplayerproxy.command.automation_unavailable"));
                return;
            }
            completion.whenComplete((enabled, failure) -> {
                if (failure != null) {
                    logger.error("Cannot complete the selected asynchronous automation player action",
                            failure);
                    source.sendMessage(Component.translatable(
                            "fakeplayerproxy.command.automation_unavailable"));
                } else if (!enabled) {
                    source.sendMessage(Component.translatable(
                            "fakeplayerproxy.command.automation_unavailable"));
                }
            });
        });
    }

    private int execute(
            CommandContext<CommandSource> context,
            BiConsumer<com.fakeplayerproxy.world.player.Player, CommandSource> operation) {
        Result<com.fakeplayerproxy.world.player.Player, String> resolved = resolveTarget(context);
        if (resolved instanceof Result.Failure<com.fakeplayerproxy.world.player.Player, String>(var error)) {
            context.getSource().sendMessage(Component.translatable(error));
            return 0;
        }
        var player = ((Result.Success<com.fakeplayerproxy.world.player.Player, String>) resolved).value();
        operation.accept(player, context.getSource());
        return 1;
    }

    private Result<com.fakeplayerproxy.world.player.Player, String> resolveTarget(
            CommandContext<CommandSource> context) {
        if (context.getArguments().containsKey("player")) {
            var player = automationManager.getByName(StringArgumentType.getString(context, "player"));
            return player == null
                    ? new Result.Failure<>("fakeplayerproxy.command.target_unavailable")
                    : new Result.Success<>(player);
        }
        if (!(context.getSource() instanceof Player sourcePlayer)) {
            return new Result.Failure<>("fakeplayerproxy.command.player_required");
        }
        var player = automationManager.get(sourcePlayer);
        return player == null
                ? new Result.Failure<>("fakeplayerproxy.command.automation_unavailable")
                : new Result.Success<>(player);
    }

    private boolean isShadowTarget(CommandContextBuilder<CommandSource> context) {
        var argument = context.getArguments().get("player");
        if (argument == null || !(argument.getResult() instanceof String name)) {
            return false;
        }
        var player = automationManager.getByName(name);
        return player != null && player.automationService().isShadow();
    }

    private static Result<Vector3d, String> resolvePosition(
            String input, com.fakeplayerproxy.world.player.Player player) {
        try {
            StringReader reader = new StringReader(input);
            String first = readPart(reader);
            String second = readPart(reader);
            String third = readPart(reader);
            reader.skipWhitespace();
            if (reader.canRead()) {
                return new Result.Failure<>(INVALID_POSITION);
            }
            boolean local = first.startsWith("^");
            if (local != second.startsWith("^") || local != third.startsWith("^")) {
                return new Result.Failure<>(INVALID_POSITION);
            }
            Vector3d result;
            if (local) {
                double left = parsePart(first.substring(1));
                double up = parsePart(second.substring(1));
                double forward = parsePart(third.substring(1));
                float yawRadians = (player.yaw() + 90.0f) * ((float) Math.PI / 180.0f);
                float pitchRadians = -player.pitch() * ((float) Math.PI / 180.0f);
                float upRadians = (-player.pitch() + 90.0f) * ((float) Math.PI / 180.0f);
                float yawCos = (float) Math.cos(yawRadians);
                float yawSin = (float) Math.sin(yawRadians);
                float pitchCos = (float) Math.cos(pitchRadians);
                float pitchSin = (float) Math.sin(pitchRadians);
                float upCos = (float) Math.cos(upRadians);
                float upSin = (float) Math.sin(upRadians);
                Vector3d forwardVector = Vector3d.from(
                        yawCos * pitchCos, pitchSin, yawSin * pitchCos);
                Vector3d upVector = Vector3d.from(yawCos * upCos, upSin, yawSin * upCos);
                Vector3d leftVector = forwardVector.cross(upVector).mul(-1.0);
                result = player.position().add(
                        leftVector.mul(left)).add(upVector.mul(up)).add(forwardVector.mul(forward));
            } else {
                result = Vector3d.from(
                        worldPart(first, player.position().getX(), true),
                        worldPart(second, player.position().getY(), false),
                        worldPart(third, player.position().getZ(), true));
            }
            return Double.isFinite(result.getX()) && Double.isFinite(result.getY())
                    && Double.isFinite(result.getZ())
                    ? new Result.Success<>(result) : new Result.Failure<>(INVALID_POSITION);
        } catch (RuntimeException exception) {
            return new Result.Failure<>(INVALID_POSITION);
        }
    }

    private static String readPart(StringReader reader) {
        reader.skipWhitespace();
        if (!reader.canRead()) {
            throw new IllegalArgumentException("Incomplete position");
        }
        return reader.readUnquotedString();
    }

    private static double parsePart(String value) {
        return value.isEmpty() ? 0.0 : Double.parseDouble(value);
    }

    private static double worldPart(String value, double base, boolean center) {
        boolean relative = value.startsWith("~");
        String numeric = relative ? value.substring(1) : value;
        double parsed = parsePart(numeric);
        if (relative) {
            return base + parsed;
        }
        return parsed + (center && !numeric.contains(".") ? 0.5 : 0.0);
    }

    private CompletableFuture<Suggestions> suggestPlayers(
            // IDEA reports this callback context as unused. Brigadier owns the callback signature.
            //noinspection unused
            CommandContext<CommandSource> context,
            SuggestionsBuilder builder) {
        automationManager.names().forEach(builder::suggest);
        return builder.buildFuture();
    }

    private CompletableFuture<Suggestions> suggestPosition(
            CommandContext<CommandSource> context, SuggestionsBuilder builder) {
        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
        if (remaining.startsWith("^")) {
            if ("^ ^ ^".startsWith(remaining)) {
                builder.suggest("^ ^ ^");
            }
            return builder.buildFuture();
        }
        Result<com.fakeplayerproxy.world.player.Player, String> target = resolveTarget(context);
        if (target instanceof Result.Success<com.fakeplayerproxy.world.player.Player, String>(var player)) {
            Vector3d position = player.position();
            String absolute = position.getX() + " " + position.getY() + " " + position.getZ();
            if (absolute.startsWith(remaining)) {
                builder.suggest(absolute);
            }
        }
        if ("~ ~ ~".startsWith(remaining)) {
            builder.suggest("~ ~ ~");
        }
        return builder.buildFuture();
    }
}
