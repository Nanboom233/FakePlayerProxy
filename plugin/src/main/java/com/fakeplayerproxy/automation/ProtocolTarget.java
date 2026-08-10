package com.fakeplayerproxy.automation;

public final class ProtocolTarget {
    public static final String MINECRAFT_VERSION = "26.2";
    public static final int PROTOCOL_VERSION = 776;
    public static final String MCPROTOCOLLIB_COORDINATE =
            "org.geysermc.mcprotocollib:protocol:26.2-20260709.110151-15";

    private ProtocolTarget() {
    }

    public static String displayName() {
        return "Minecraft Java " + MINECRAFT_VERSION
                + " (protocol " + PROTOCOL_VERSION
                + ", MCProtocolLib " + MCPROTOCOLLIB_COORDINATE + ")";
    }
}
