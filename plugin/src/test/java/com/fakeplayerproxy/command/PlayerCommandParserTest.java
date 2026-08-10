package com.fakeplayerproxy.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fakeplayerproxy.automation.ActionMode;
import com.fakeplayerproxy.util.ProxyResult;
import org.junit.jupiter.api.Test;

final class PlayerCommandParserTest {
    private final PlayerCommandParser parser = new PlayerCommandParser();

    @Test
    void acceptsSelfShadow() {
        ProxyResult<ParsedPlayerCommand> result = parser.parse("ProxyBot", new String[] {"self", "shadow"});

        assertTrue(result.isSuccess());
        assertEquals(PlayerCommandKind.SHADOW, result.valueOrThrow().kind());
    }

    @Test
    void rejectsOtherPlayerTarget() {
        ProxyResult<ParsedPlayerCommand> result = parser.parse("ProxyBot", new String[] {"Other", "shadow"});

        assertEquals("player_not_self", result.errorOrThrow().code());
    }

    @Test
    void parsesCarpetLookDirection() {
        ProxyResult<ParsedPlayerCommand> result = parser.parse("ProxyBot", new String[] {"ProxyBot", "look", "north"});

        assertTrue(result.isSuccess());
        assertEquals(PlayerCommandKind.LOOK, result.valueOrThrow().kind());
        assertEquals(180.0f, result.valueOrThrow().yaw());
        assertEquals(0.0f, result.valueOrThrow().pitch());
    }

    @Test
    void parsesHotbarSlot() {
        ProxyResult<ParsedPlayerCommand> result = parser.parse("ProxyBot", new String[] {"self", "hotbar", "9"});

        assertTrue(result.isSuccess());
        assertEquals(PlayerCommandKind.HOTBAR, result.valueOrThrow().kind());
        assertEquals(9, result.valueOrThrow().hotbarSlot());
    }

    @Test
    void rejectsBadHotbarSlot() {
        ProxyResult<ParsedPlayerCommand> result = parser.parse("ProxyBot", new String[] {"self", "hotbar", "10"});

        assertEquals("player_invalid_hotbar", result.errorOrThrow().code());
    }

    @Test
    void marksMountAnythingUnsupported() {
        ProxyResult<ParsedPlayerCommand> result = parser.parse("ProxyBot", new String[] {"self", "mount", "anything"});

        assertTrue(result.isSuccess());
        assertEquals(PlayerCommandKind.UNSUPPORTED, result.valueOrThrow().kind());
    }

    @Test
    void parsesAttackInterval() {
        ProxyResult<ParsedPlayerCommand> result = parser.parse("ProxyBot", new String[] {"self", "attack", "interval", "4"});

        assertTrue(result.isSuccess());
        assertEquals(PlayerCommandKind.ATTACK, result.valueOrThrow().kind());
        assertEquals(ActionMode.INTERVAL, result.valueOrThrow().actionMode());
        assertEquals(4, result.valueOrThrow().intervalTicks());
    }

    @Test
    void parsesUseDefaultOnce() {
        ProxyResult<ParsedPlayerCommand> result = parser.parse("ProxyBot", new String[] {"self", "use"});

        assertTrue(result.isSuccess());
        assertEquals(PlayerCommandKind.USE, result.valueOrThrow().kind());
        assertEquals(ActionMode.ONCE, result.valueOrThrow().actionMode());
    }

    @Test
    void parsesSimpleInventoryPackets() {
        ProxyResult<ParsedPlayerCommand> drop = parser.parse("ProxyBot", new String[] {"self", "drop"});
        ProxyResult<ParsedPlayerCommand> dropStack = parser.parse("ProxyBot", new String[] {"self", "dropStack", "continuous"});
        ProxyResult<ParsedPlayerCommand> swapHands = parser.parse("ProxyBot", new String[] {"self", "swapHands"});

        assertEquals(PlayerCommandKind.DROP, drop.valueOrThrow().kind());
        assertEquals(PlayerCommandKind.DROP_STACK, dropStack.valueOrThrow().kind());
        assertEquals(ActionMode.CONTINUOUS, dropStack.valueOrThrow().actionMode());
        assertEquals(PlayerCommandKind.SWAP_HANDS, swapHands.valueOrThrow().kind());
    }

    @Test
    void parsesDismount() {
        ProxyResult<ParsedPlayerCommand> result = parser.parse("ProxyBot", new String[] {"self", "dismount"});

        assertTrue(result.isSuccess());
        assertEquals(PlayerCommandKind.DISMOUNT, result.valueOrThrow().kind());
    }

    @Test
    void marksSlotDropDeferred() {
        ProxyResult<ParsedPlayerCommand> result = parser.parse("ProxyBot", new String[] {"self", "drop"});

        assertTrue(result.isSuccess());
        assertEquals(PlayerCommandKind.DROP, result.valueOrThrow().kind());

        ProxyResult<ParsedPlayerCommand> slotDrop = parser.parse("ProxyBot", new String[] {"self", "drop", "all"});

        assertTrue(slotDrop.isSuccess());
        assertEquals(PlayerCommandKind.DEFERRED, slotDrop.valueOrThrow().kind());
    }
}
