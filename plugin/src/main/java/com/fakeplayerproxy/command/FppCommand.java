package com.fakeplayerproxy.command;

import com.fakeplayerproxy.config.ProxyConfig;
import com.fakeplayerproxy.automation.UpstreamConnectRequest;
import com.fakeplayerproxy.automation.AutomationService;
import com.fakeplayerproxy.automation.AutomationSnapshot;
import com.fakeplayerproxy.automation.ProtocolTarget;
import com.fakeplayerproxy.util.ProxyError;
import com.fakeplayerproxy.util.ProxyResult;
import com.velocitypowered.api.command.SimpleCommand;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class FppCommand implements SimpleCommand {
    private final ProxyConfig defaultConfig;
    private final AutomationService automationService;
    private final PlayerCommandHandler playerCommandHandler;

    public FppCommand(
            ProxyConfig defaultConfig,
            AutomationService automationService,
            PlayerCommandHandler playerCommandHandler) {
        this.defaultConfig = Objects.requireNonNull(defaultConfig, "defaultConfig");
        this.automationService = Objects.requireNonNull(automationService, "automationService");
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
            case "connect" -> connect(invocation, Arrays.copyOfRange(args, 1, args.length));
            case "disconnect" -> disconnect(invocation);
            case "look-north" -> lookNorth(invocation);
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

    private void connect(Invocation invocation, String[] connectArgs) {
        ProxyResult<UpstreamConnectRequest> parsed = ConnectCommandParser.parseConnectArgs(connectArgs, defaultConfig);
        if (!parsed.isSuccess()) {
            sendError(invocation, parsed.errorOrThrow());
            return;
        }

        UpstreamConnectRequest request = parsed.valueOrThrow();
        ProxyResult<AutomationSnapshot> result = automationService.connect(request);
        if (result.isSuccess()) {
            invocation.source().sendPlainMessage("[FPP] Started upstream connection: " + request.targetLabel());
            invocation.source().sendPlainMessage("[FPP] State: " + result.valueOrThrow().state());
        } else {
            sendError(invocation, result.errorOrThrow());
        }
    }

    private void disconnect(Invocation invocation) {
        ProxyResult<AutomationSnapshot> result = automationService.disconnect();
        if (result.isSuccess()) {
            invocation.source().sendPlainMessage("[FPP] " + result.valueOrThrow().message());
            invocation.source().sendPlainMessage("[FPP] State: " + result.valueOrThrow().state());
        } else {
            sendError(invocation, result.errorOrThrow());
        }
    }

    private void lookNorth(Invocation invocation) {
        ProxyResult<Void> result = automationService.lookNorth();
        if (result.isSuccess()) {
            invocation.source().sendPlainMessage("[FPP] Sent look-north packet.");
        } else {
            sendError(invocation, result.errorOrThrow());
        }
    }

    private void sendStatus(Invocation invocation) {
        AutomationSnapshot snapshot = automationService.snapshot();
        invocation.source().sendPlainMessage("[FPP] State: " + snapshot.state());
        invocation.source().sendPlainMessage("[FPP] Target: " + snapshot.targetLabel());
        invocation.source().sendPlainMessage("[FPP] Play ready: " + snapshot.playReady());
        invocation.source().sendPlainMessage("[FPP] Message: " + snapshot.message());
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
        invocation.source().sendPlainMessage("[FPP] Usage: /fpp player self <shadow|stop|kill|look|turn|move|jump|hotbar|sneak|sprint>");
    }

    private void sendError(Invocation invocation, ProxyError error) {
        invocation.source().sendPlainMessage("[FPP] Error: " + error.safeMessage());
    }

    private List<String> filterPrefix(List<String> options, String prefix) {
        String normalizedPrefix = prefix.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(normalizedPrefix))
                .toList();
    }
}
