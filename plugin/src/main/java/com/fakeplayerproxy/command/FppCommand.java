package com.fakeplayerproxy.command;

import com.fakeplayerproxy.config.ProxyConfig;
import com.fakeplayerproxy.automation.AutomationManager;
import com.fakeplayerproxy.protocol.ProtocolTarget;
import com.fakeplayerproxy.util.ProxyError;
import com.fakeplayerproxy.util.ProxyResult;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import net.kyori.adventure.text.Component;

public final class FppCommand implements SimpleCommand {
    private final ProxyConfig defaultConfig;
    private final AutomationManager automationManager;
    private final PlayerCommand playerCommandHandler;

    public FppCommand(
            ProxyConfig defaultConfig,
            AutomationManager automationManager,
            PlayerCommand playerCommandHandler) {
        this.defaultConfig = Objects.requireNonNull(defaultConfig, "defaultConfig");
        this.automationManager = Objects.requireNonNull(automationManager, "automationManager");
        this.playerCommandHandler = Objects.requireNonNull(playerCommandHandler, "playerCommandHandler");
    }

    @Override
    public void execute(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length == 0) {
            sendUsage(invocation);
            return;
        }

        String root = args[0].toLowerCase(Locale.ROOT);
        switch (root) {
            case "status" -> sendStatus(invocation);
            case "connect" -> sendError(invocation, new ProxyError(
                    "command_unsupported", "This connection already owns its authenticated backend."));
            case "disconnect" -> withPlayer(invocation).ifPresent(player ->
                    render(invocation, automationManager.closeBackend(player), "Closed backend connection."));
            case "look-north" -> withAutomationPlayer(invocation).ifPresent(player ->
                    render(invocation, player.automationService().look(180.0f, 0.0f),
                            "Sent look-north packet."));
            case "player" -> playerCommandHandler.execute(invocation.source(), Arrays.copyOfRange(args, 1, args.length));
            default -> sendError(invocation, new ProxyError("command_unknown", "Unknown /fpp command."));
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length == 0) {
            return List.of("status", "connect", "disconnect", "look-north", "player");
        }
        if (args.length == 1) {
            return filterPrefix(List.of("status", "connect", "disconnect", "look-north", "player"), args[0]);
        }
        if ("player".equalsIgnoreCase(args[0])) {
            return playerCommandHandler.suggest(Arrays.copyOfRange(args, 1, args.length));
        }
        if ("connect".equalsIgnoreCase(args[0]) && args.length == 2) {
            return List.of(defaultConfig.targetHost());
        }
        if ("connect".equalsIgnoreCase(args[0]) && args.length == 3) {
            return List.of(Integer.toString(defaultConfig.targetPort()));
        }
        if ("connect".equalsIgnoreCase(args[0]) && args.length == 4) {
            return List.of(defaultConfig.username());
        }
        return List.of();
    }

    private void sendStatus(Invocation invocation) {
        Optional<Player> player = withPlayer(invocation);
        if (player.isEmpty()) {
            return;
        }
        var automationPlayer = automationManager.get(player.get());
        var service = automationPlayer == null ? null : automationPlayer.automationService();
        invocation.source().sendPlainMessage("[FPP] Automation: " + (service == null ? "unavailable" : "registered"));
        invocation.source().sendPlainMessage("[FPP] Shadow: " + (service != null && service.isShadow()));
        invocation.source().sendPlainMessage("[FPP] Default config: " + defaultConfig.targetLabel());
        invocation.source().sendPlainMessage("[FPP] Auto reconnect: " + defaultConfig.reconnect().enabled()
                + " (" + defaultConfig.reconnect().authMode()
                + ", maxAttempts=" + defaultConfig.reconnect().maxAttempts()
                + ", delayMillis=" + defaultConfig.reconnect().delayMillis() + ")");
        invocation.source().sendPlainMessage("[FPP] Protocol target: " + ProtocolTarget.displayName());
    }

    private void sendUsage(Invocation invocation) {
        invocation.source().sendPlainMessage("[FPP] Usage: /fpp status");
        invocation.source().sendPlainMessage("[FPP] Usage: /fpp connect [host] [port] [username]");
        invocation.source().sendPlainMessage("[FPP] Usage: /fpp disconnect");
        invocation.source().sendPlainMessage("[FPP] Usage: /fpp look-north");
        invocation.source().sendPlainMessage("[FPP] Usage: /fpp player <shadow|stop|kill|look|turn|move|jump|hotbar|sneak|sprint>");
    }

    private void sendError(Invocation invocation, ProxyError error) {
        invocation.source().sendPlainMessage("[FPP] Error: " + error.safeMessage());
    }

    private Optional<Player> withPlayer(Invocation invocation) {
        if (invocation.source() instanceof Player player) {
            return Optional.of(player);
        }
        invocation.source().sendMessage(Component.translatable("fakeplayerproxy.command.player_required"));
        return Optional.empty();
    }

    private Optional<com.fakeplayerproxy.world.player.Player> withAutomationPlayer(Invocation invocation) {
        Optional<Player> player = withPlayer(invocation);
        if (player.isEmpty()) {
            return Optional.empty();
        }
        var automationPlayer = automationManager.get(player.get());
        if (automationPlayer == null) {
            sendError(invocation, new ProxyError(
                    "automation_registration_missing",
                    "No automation is registered for this player."));
            return Optional.empty();
        }
        return Optional.of(automationPlayer);
    }

    private void render(Invocation invocation, ProxyResult<Void> result, String successMessage) {
        if (result.isSuccess()) {
            invocation.source().sendPlainMessage("[FPP] " + successMessage);
        } else {
            sendError(invocation, result.errorOrThrow());
        }
    }

    private List<String> filterPrefix(List<String> options, String prefix) {
        String normalizedPrefix = prefix.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(normalizedPrefix))
                .toList();
    }
}
