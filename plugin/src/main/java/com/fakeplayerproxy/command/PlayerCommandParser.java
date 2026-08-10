package com.fakeplayerproxy.command;

import com.fakeplayerproxy.util.ProxyError;
import com.fakeplayerproxy.util.ProxyResult;
import com.fakeplayerproxy.automation.ActionMode;
import java.util.Locale;
import java.util.Objects;

public final class PlayerCommandParser {
    public ProxyResult<ParsedPlayerCommand> parse(String executorName, String[] args) {
        Objects.requireNonNull(executorName, "executorName");
        Objects.requireNonNull(args, "args");
        if (args.length < 2) {
            return usage();
        }
        if (!isSelfTarget(executorName, args[0])) {
            return ProxyResult.failure(new ProxyError(
                    "player_not_self",
                    "This proxy only allows /player commands targeting yourself."));
        }

        String command = args[1].toLowerCase(Locale.ROOT);
        return switch (command) {
            case "shadow" -> exactArgs(args, ParsedPlayerCommand.simple(PlayerCommandKind.SHADOW));
            case "stop" -> exactArgs(args, ParsedPlayerCommand.simple(PlayerCommandKind.STOP));
            case "kill" -> exactArgs(args, ParsedPlayerCommand.simple(PlayerCommandKind.KILL));
            case "hotbar" -> parseHotbar(args);
            case "look" -> parseLook(args);
            case "turn" -> parseTurn(args);
            case "move" -> parseMove(args);
            case "jump" -> parseJump(args);
            case "sneak" -> exactArgs(args, ParsedPlayerCommand.enabled(PlayerCommandKind.SNEAK_SET, true));
            case "unsneak" -> exactArgs(args, ParsedPlayerCommand.enabled(PlayerCommandKind.SNEAK_SET, false));
            case "sprint" -> exactArgs(args, ParsedPlayerCommand.enabled(PlayerCommandKind.SPRINT_SET, true));
            case "unsprint" -> exactArgs(args, ParsedPlayerCommand.enabled(PlayerCommandKind.SPRINT_SET, false));
            case "attack" -> parseActionMode(args, PlayerCommandKind.ATTACK, "attack");
            case "use" -> parseActionMode(args, PlayerCommandKind.USE, "use");
            case "drop" -> parseDropLike(args, PlayerCommandKind.DROP, "drop");
            case "dropstack" -> parseDropLike(args, PlayerCommandKind.DROP_STACK, "dropStack");
            case "swaphands" -> parseActionMode(args, PlayerCommandKind.SWAP_HANDS, "swapHands");
            case "dismount" -> exactArgs(args, ParsedPlayerCommand.simple(PlayerCommandKind.DISMOUNT));
            case "mount" -> parseMount(command, args);
            case "spawn" -> ProxyResult.success(ParsedPlayerCommand.message(
                    PlayerCommandKind.DEFERRED,
                    "spawn is deferred until self-only spawn semantics are defined."));
            default -> ProxyResult.failure(new ProxyError("player_unknown_command", "Unknown /player command."));
        };
    }

    private ProxyResult<ParsedPlayerCommand> parseHotbar(String[] args) {
        if (args.length != 3) {
            return ProxyResult.failure(new ProxyError("player_usage", "Usage: /player self hotbar <1-9>"));
        }
        try {
            int slot = Integer.parseInt(args[2]);
            if (slot < 1 || slot > 9) {
                return ProxyResult.failure(new ProxyError("player_invalid_hotbar", "Hotbar slot must be 1 through 9."));
            }
            return ProxyResult.success(ParsedPlayerCommand.hotbar(slot));
        } catch (NumberFormatException e) {
            return ProxyResult.failure(new ProxyError("player_invalid_hotbar", "Hotbar slot must be 1 through 9."));
        }
    }

    private ProxyResult<ParsedPlayerCommand> parseLook(String[] args) {
        if (args.length == 3) {
            return switch (args[2].toLowerCase(Locale.ROOT)) {
                case "north" -> ProxyResult.success(ParsedPlayerCommand.look(180.0f, 0.0f));
                case "south" -> ProxyResult.success(ParsedPlayerCommand.look(0.0f, 0.0f));
                case "east" -> ProxyResult.success(ParsedPlayerCommand.look(-90.0f, 0.0f));
                case "west" -> ProxyResult.success(ParsedPlayerCommand.look(90.0f, 0.0f));
                case "up" -> ProxyResult.success(ParsedPlayerCommand.look(0.0f, -90.0f));
                case "down" -> ProxyResult.success(ParsedPlayerCommand.look(0.0f, 90.0f));
                default -> ProxyResult.failure(new ProxyError(
                        "player_invalid_look",
                        "Usage: /player self look <north|south|east|west|up|down> or <yaw> <pitch>"));
            };
        }
        if (args.length == 4) {
            try {
                return ProxyResult.success(ParsedPlayerCommand.look(Float.parseFloat(args[2]), Float.parseFloat(args[3])));
            } catch (NumberFormatException e) {
                return ProxyResult.failure(new ProxyError("player_invalid_look", "Yaw and pitch must be numbers."));
            }
        }
        return ProxyResult.failure(new ProxyError(
                "player_usage",
                "Usage: /player self look <north|south|east|west|up|down> or <yaw> <pitch>"));
    }

    private ProxyResult<ParsedPlayerCommand> parseTurn(String[] args) {
        if (args.length == 3) {
            return switch (args[2].toLowerCase(Locale.ROOT)) {
                case "left" -> ProxyResult.success(ParsedPlayerCommand.turn(-90.0f, 0.0f));
                case "right" -> ProxyResult.success(ParsedPlayerCommand.turn(90.0f, 0.0f));
                case "back" -> ProxyResult.success(ParsedPlayerCommand.turn(180.0f, 0.0f));
                default -> ProxyResult.failure(new ProxyError(
                        "player_invalid_turn",
                        "Usage: /player self turn <left|right|back> or <yaw-delta> <pitch-delta>"));
            };
        }
        if (args.length == 4) {
            try {
                return ProxyResult.success(ParsedPlayerCommand.turn(Float.parseFloat(args[2]), Float.parseFloat(args[3])));
            } catch (NumberFormatException e) {
                return ProxyResult.failure(new ProxyError("player_invalid_turn", "Turn deltas must be numbers."));
            }
        }
        return ProxyResult.failure(new ProxyError(
                "player_usage",
                "Usage: /player self turn <left|right|back> or <yaw-delta> <pitch-delta>"));
    }

    private ProxyResult<ParsedPlayerCommand> parseMove(String[] args) {
        if (args.length == 2) {
            return ProxyResult.success(ParsedPlayerCommand.move(""));
        }
        if (args.length != 3) {
            return ProxyResult.failure(new ProxyError("player_usage", "Usage: /player self move [forward|backward|left|right]"));
        }
        return switch (args[2].toLowerCase(Locale.ROOT)) {
            case "forward", "backward", "back", "left", "right" -> ProxyResult.success(ParsedPlayerCommand.move(args[2]));
            default -> ProxyResult.failure(new ProxyError(
                    "player_invalid_move",
                    "Move direction must be forward, backward, left, or right."));
        };
    }

    private ProxyResult<ParsedPlayerCommand> parseJump(String[] args) {
        if (args.length == 2 || (args.length == 3 && "once".equalsIgnoreCase(args[2]))) {
            return ProxyResult.success(ParsedPlayerCommand.simple(PlayerCommandKind.JUMP_ONCE));
        }
        if (args.length == 3 && "continuous".equalsIgnoreCase(args[2])) {
            return ProxyResult.success(ParsedPlayerCommand.enabled(PlayerCommandKind.JUMP_SET, true));
        }
        if (args.length == 4 && "interval".equalsIgnoreCase(args[2])) {
            ProxyResult<Integer> ticks = parseIntervalTicks(args[3]);
            if (!ticks.isSuccess()) {
                return ProxyResult.failure(ticks.errorOrThrow());
            }
            return ProxyResult.success(ParsedPlayerCommand.action(
                    PlayerCommandKind.JUMP_INTERVAL,
                    ActionMode.INTERVAL,
                    ticks.valueOrThrow()));
        }
        return ProxyResult.failure(new ProxyError("player_usage", "Usage: /player self jump [once|continuous|interval <ticks>]"));
    }

    private ProxyResult<ParsedPlayerCommand> parseActionMode(String[] args, PlayerCommandKind kind, String commandLabel) {
        if (args.length == 2) {
            return ProxyResult.success(ParsedPlayerCommand.action(kind, ActionMode.ONCE, 0));
        }
        if (args.length == 3 && "once".equalsIgnoreCase(args[2])) {
            return ProxyResult.success(ParsedPlayerCommand.action(kind, ActionMode.ONCE, 0));
        }
        if (args.length == 3 && "continuous".equalsIgnoreCase(args[2])) {
            return ProxyResult.success(ParsedPlayerCommand.action(kind, ActionMode.CONTINUOUS, 1));
        }
        if (args.length == 4 && "interval".equalsIgnoreCase(args[2])) {
            ProxyResult<Integer> ticks = parseIntervalTicks(args[3]);
            if (!ticks.isSuccess()) {
                return ProxyResult.failure(ticks.errorOrThrow());
            }
            return ProxyResult.success(ParsedPlayerCommand.action(kind, ActionMode.INTERVAL, ticks.valueOrThrow()));
        }
        return ProxyResult.failure(new ProxyError(
                "player_usage",
                "Usage: /player self " + commandLabel + " [once|continuous|interval <ticks>]"));
    }

    private ProxyResult<ParsedPlayerCommand> parseDropLike(String[] args, PlayerCommandKind kind, String commandLabel) {
        if (args.length >= 3 && isInventoryDropTarget(args[2])) {
            return ProxyResult.success(ParsedPlayerCommand.message(
                    PlayerCommandKind.DEFERRED,
                    commandLabel + " " + args[2] + " is deferred until inventory slot tracking exists."));
        }
        return parseActionMode(args, kind, commandLabel);
    }

    private boolean isInventoryDropTarget(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        if ("all".equals(normalized) || "mainhand".equals(normalized) || "offhand".equals(normalized)) {
            return true;
        }
        try {
            Integer.parseInt(normalized);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private ProxyResult<Integer> parseIntervalTicks(String value) {
        try {
            int ticks = Integer.parseInt(value);
            if (ticks < 1) {
                return ProxyResult.failure(new ProxyError("player_invalid_interval", "Interval ticks must be 1 or greater."));
            }
            return ProxyResult.success(ticks);
        } catch (NumberFormatException e) {
            return ProxyResult.failure(new ProxyError("player_invalid_interval", "Interval ticks must be numeric."));
        }
    }

    private ProxyResult<ParsedPlayerCommand> parseMount(String command, String[] args) {
        if ("mount".equals(command) && args.length == 3 && "anything".equalsIgnoreCase(args[2])) {
            return ProxyResult.success(ParsedPlayerCommand.message(
                    PlayerCommandKind.UNSUPPORTED,
                    "mount anything is unsupported by protocol-only automation."));
        }
        return ProxyResult.success(ParsedPlayerCommand.message(
                PlayerCommandKind.DEFERRED,
                command + " is parsed but deferred until entity tracking exists."));
    }

    private ProxyResult<ParsedPlayerCommand> exactArgs(String[] args, ParsedPlayerCommand action) {
        if (args.length != 2) {
            return usage();
        }
        return ProxyResult.success(action);
    }

    private boolean isSelfTarget(String executorName, String target) {
        return "self".equalsIgnoreCase(target) || executorName.equalsIgnoreCase(target);
    }

    private ProxyResult<ParsedPlayerCommand> usage() {
        return ProxyResult.failure(new ProxyError("player_usage", "Usage: /player self <shadow|stop|kill|attack|use|look|turn|move|jump|hotbar|sneak|sprint|drop|dropStack|swapHands>"));
    }
}
