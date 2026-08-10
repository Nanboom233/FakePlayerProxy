package com.fakeplayerproxy.config;

public record ReconnectConfig(
        boolean enabled,
        int maxAttempts,
        long delayMillis,
        String authMode) {
    public static final String OFFLINE_CONTROLLED_AUTH_MODE = "offline-controlled";

    public static final ReconnectConfig DEFAULT =
            new ReconnectConfig(true, 3, 1000L, OFFLINE_CONTROLLED_AUTH_MODE);

    public ReconnectConfig {
        authMode = authMode == null || authMode.isBlank() ? OFFLINE_CONTROLLED_AUTH_MODE : authMode.trim();
        if (maxAttempts < 0) {
            throw new IllegalArgumentException("proxy.reconnect.maxAttempts must be zero or greater.");
        }
        if (delayMillis < 0) {
            throw new IllegalArgumentException("proxy.reconnect.delayMillis must be zero or greater.");
        }
        if (enabled && !OFFLINE_CONTROLLED_AUTH_MODE.equals(authMode)) {
            throw new IllegalArgumentException(
                    "proxy.reconnect.authMode only supports offline-controlled in this implementation.");
        }
    }
}
