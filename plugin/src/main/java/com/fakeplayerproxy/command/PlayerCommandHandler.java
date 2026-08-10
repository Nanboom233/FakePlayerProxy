package com.fakeplayerproxy.command;

import com.fakeplayerproxy.config.ProxyConfig;
import com.fakeplayerproxy.automation.UpstreamConnectRequest;
import com.fakeplayerproxy.automation.AutomationService;
import com.fakeplayerproxy.automation.AutomationSnapshot;
import com.fakeplayerproxy.util.ProxyError;
import com.fakeplayerproxy.util.ProxyResult;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class PlayerCommandHandler {
    private final ProxyConfig defaultConfig;
    private final AutomationService automationService;
    private final PlayerCommandParser parser = new PlayerCommandParser();

    public PlayerCommandHandler(ProxyConfig defaultConfig, AutomationService automationService) {
        this.defaultConfig = Objects.requireNonNull(defaultConfig, "defaultConfig");
        this.automationService = Objects.requireNonNull(automationService, "automationService");
    }

    public void execute(CommandSource source, String[] args) {
        String executorName = executorName(source);
        ProxyResult<ParsedPlayerCommand> parsed = parser.parse(executorName, args);
        if (!parsed.isSuccess()) {
            sendError(source, parsed.errorOrThrow());
            return;
        }

        ParsedPlayerCommand action = parsed.valueOrThrow();
        switch (action.kind()) {
            case SHADOW -> connect(source);
            case STOP -> renderVoid(source, automationService.stopActions(), "Stopped all player actions.");
            case KILL -> disconnect(source);
            case ATTACK -> renderVoid(
                    source,
                    automationService.attack(action.actionMode(), action.intervalTicks()),
                    actionMessage("attack", action));
            case USE -> renderVoid(
                    source,
                    automationService.use(action.actionMode(), action.intervalTicks()),
                    actionMessage("use", action));
            case LOOK -> renderVoid(source, automationService.look(action.yaw(), action.pitch()), "Sent look action.");
            case TURN -> renderVoid(source, automationService.turn(action.yawDelta(), action.pitchDelta()), "Sent turn action.");
            case HOTBAR -> renderVoid(source, automationService.selectHotbar(action.hotbarSlot()), "Selected hotbar slot " + action.hotbarSlot() + ".");
            case MOVE -> renderVoid(source, automationService.move(action.moveDirection()), "Updated movement input.");
            case JUMP_ONCE -> renderVoid(source, automationService.jumpOnce(), "Sent jump action.");
            case JUMP_SET -> renderVoid(source, automationService.setJump(action.enabled()), "Updated jump input.");
            case JUMP_INTERVAL -> renderVoid(
                    source,
                    automationService.jumpInterval(action.intervalTicks()),
                    "Scheduled jump every " + action.intervalTicks() + " tick(s).");
            case SNEAK_SET -> renderVoid(source, automationService.setSneak(action.enabled()), "Updated sneak input.");
            case SPRINT_SET -> renderVoid(source, automationService.setSprint(action.enabled()), "Updated sprint input.");
            case DROP -> renderVoid(
                    source,
                    automationService.dropSelectedItem(false, action.actionMode(), action.intervalTicks()),
                    actionMessage("drop", action));
            case DROP_STACK -> renderVoid(
                    source,
                    automationService.dropSelectedItem(true, action.actionMode(), action.intervalTicks()),
                    actionMessage("dropStack", action));
            case SWAP_HANDS -> renderVoid(
                    source,
                    automationService.swapHands(action.actionMode(), action.intervalTicks()),
                    actionMessage("swapHands", action));
            case DISMOUNT -> renderVoid(source, automationService.dismount(), "Sent dismount input pulse.");
            case DEFERRED -> source.sendPlainMessage("[FPP] Deferred: " + action.safeMessage());
            case UNSUPPORTED -> source.sendPlainMessage("[FPP] Unsupported: " + action.safeMessage());
        }
    }

    public List<String> suggest(String[] args) {
        if (args.length == 0) {
            return List.of("self", defaultConfig.username());
        }
        if (args.length == 1) {
            return filter(List.of("self", defaultConfig.username()), args[0]);
        }
        if (args.length == 2) {
            return filter(List.of(
                    "shadow", "stop", "kill", "attack", "use", "jump", "hotbar",
                    "move", "look", "turn", "sneak", "unsneak", "sprint", "unsprint",
                    "drop", "dropStack", "swapHands", "mount", "dismount"), args[1]);
        }
        if (args.length == 3 && "look".equalsIgnoreCase(args[1])) {
            return filter(List.of("north", "south", "east", "west", "up", "down"), args[2]);
        }
        if (args.length == 3 && "turn".equalsIgnoreCase(args[1])) {
            return filter(List.of("left", "right", "back"), args[2]);
        }
        if (args.length == 3 && "move".equalsIgnoreCase(args[1])) {
            return filter(List.of("forward", "backward", "left", "right"), args[2]);
        }
        if (args.length == 3 && "jump".equalsIgnoreCase(args[1])) {
            return filter(List.of("once", "continuous", "interval"), args[2]);
        }
        return List.of();
    }

    private String actionMessage(String label, ParsedPlayerCommand action) {
        return switch (action.actionMode()) {
            case ONCE -> "Sent " + label + " action.";
            case CONTINUOUS -> "Scheduled continuous " + label + " action.";
            case INTERVAL -> "Scheduled " + label + " every " + action.intervalTicks() + " tick(s).";
        };
    }

    private void connect(CommandSource source) {
        UpstreamConnectRequest request = new UpstreamConnectRequest(
                defaultConfig.targetHost(),
                defaultConfig.targetPort(),
                defaultConfig.username());
        ProxyResult<AutomationSnapshot> result = automationService.connect(request);
        if (result.isSuccess()) {
            source.sendPlainMessage("[FPP] Shadow session starting: " + request.targetLabel());
            source.sendPlainMessage("[FPP] State: " + result.valueOrThrow().state());
        } else {
            sendError(source, result.errorOrThrow());
        }
    }

    private void disconnect(CommandSource source) {
        ProxyResult<AutomationSnapshot> result = automationService.disconnect();
        if (result.isSuccess()) {
            source.sendPlainMessage("[FPP] " + result.valueOrThrow().message());
        } else {
            sendError(source, result.errorOrThrow());
        }
    }

    private void renderVoid(CommandSource source, ProxyResult<Void> result, String message) {
        if (result.isSuccess()) {
            source.sendPlainMessage("[FPP] " + message);
        } else {
            sendError(source, result.errorOrThrow());
        }
    }

    private String executorName(CommandSource source) {
        if (source instanceof Player player) {
            return player.getUsername();
        }
        return defaultConfig.username();
    }

    private void sendError(CommandSource source, ProxyError error) {
        source.sendPlainMessage("[FPP] Error: " + error.safeMessage());
    }

    private List<String> filter(List<String> options, String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(normalized))
                .toList();
    }
}
