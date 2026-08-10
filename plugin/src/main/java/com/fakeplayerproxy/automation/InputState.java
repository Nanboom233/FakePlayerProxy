package com.fakeplayerproxy.automation;

public record InputState(
        boolean forward,
        boolean backward,
        boolean left,
        boolean right,
        boolean jump,
        boolean shift,
        boolean sprint) {
    public static final InputState CLEAR = new InputState(false, false, false, false, false, false, false);

    public InputState withMovement(String direction) {
        if (direction == null || direction.isBlank()) {
            return new InputState(false, false, false, false, false, false, false);
        }
        return switch (direction.toLowerCase()) {
            case "forward" -> new InputState(true, false, left, right, jump, shift, sprint);
            case "backward", "back" -> new InputState(false, true, left, right, jump, shift, sprint);
            case "left" -> new InputState(forward, backward, true, false, jump, shift, sprint);
            case "right" -> new InputState(forward, backward, false, true, jump, shift, sprint);
            default -> this;
        };
    }

    public InputState withJump(boolean value) {
        return new InputState(forward, backward, left, right, value, shift, sprint);
    }

    public InputState withShift(boolean value) {
        return new InputState(forward, backward, left, right, jump, value, !value && sprint);
    }

    public InputState withSprint(boolean value) {
        return new InputState(forward, backward, left, right, jump, !value && shift, value);
    }
}
