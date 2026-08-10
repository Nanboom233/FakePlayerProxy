package com.fakeplayerproxy.command;

import com.fakeplayerproxy.automation.ActionMode;
import java.util.Objects;

public record ParsedPlayerCommand(
        PlayerCommandKind kind,
        ActionMode actionMode,
        int intervalTicks,
        float yaw,
        float pitch,
        float yawDelta,
        float pitchDelta,
        int hotbarSlot,
        String moveDirection,
        boolean enabled,
        String safeMessage) {
    public ParsedPlayerCommand {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(actionMode, "actionMode");
    }

    public static ParsedPlayerCommand simple(PlayerCommandKind kind) {
        return new ParsedPlayerCommand(kind, ActionMode.ONCE, 0, 0, 0, 0, 0, 0, "", false, "");
    }

    public static ParsedPlayerCommand action(PlayerCommandKind kind, ActionMode actionMode, int intervalTicks) {
        return new ParsedPlayerCommand(kind, actionMode, intervalTicks, 0, 0, 0, 0, 0, "", false, "");
    }

    public static ParsedPlayerCommand look(float yaw, float pitch) {
        return new ParsedPlayerCommand(PlayerCommandKind.LOOK, ActionMode.ONCE, 0, yaw, pitch, 0, 0, 0, "", false, "");
    }

    public static ParsedPlayerCommand turn(float yawDelta, float pitchDelta) {
        return new ParsedPlayerCommand(PlayerCommandKind.TURN, ActionMode.ONCE, 0, 0, 0, yawDelta, pitchDelta, 0, "", false, "");
    }

    public static ParsedPlayerCommand hotbar(int slot) {
        return new ParsedPlayerCommand(PlayerCommandKind.HOTBAR, ActionMode.ONCE, 0, 0, 0, 0, 0, slot, "", false, "");
    }

    public static ParsedPlayerCommand move(String direction) {
        return new ParsedPlayerCommand(PlayerCommandKind.MOVE, ActionMode.ONCE, 0, 0, 0, 0, 0, 0, direction, false, "");
    }

    public static ParsedPlayerCommand enabled(PlayerCommandKind kind, boolean enabled) {
        return new ParsedPlayerCommand(kind, ActionMode.ONCE, 0, 0, 0, 0, 0, 0, "", enabled, "");
    }

    public static ParsedPlayerCommand message(PlayerCommandKind kind, String safeMessage) {
        return new ParsedPlayerCommand(kind, ActionMode.ONCE, 0, 0, 0, 0, 0, 0, "", false, safeMessage);
    }
}
