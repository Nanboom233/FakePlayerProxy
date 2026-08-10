package com.fakeplayerproxy.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class ProtocolTargetTest {
    @Test
    void pinsMinecraftProtocolVersion() {
        assertEquals("26.2", ProtocolTarget.MINECRAFT_VERSION);
        assertEquals(776, ProtocolTarget.PROTOCOL_VERSION);
        assertEquals(
                "org.geysermc.mcprotocollib:protocol:26.2-20260709.110151-15",
                ProtocolTarget.MCPROTOCOLLIB_COORDINATE);
        assertTrue(ProtocolTarget.displayName().contains("Minecraft Java 26.2"));
        assertTrue(ProtocolTarget.displayName().contains("protocol 776"));
    }
}
