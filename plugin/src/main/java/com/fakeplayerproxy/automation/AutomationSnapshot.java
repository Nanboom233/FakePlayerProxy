package com.fakeplayerproxy.automation;

import java.time.Instant;
import java.util.Objects;

public record AutomationSnapshot(
        AutomationState state,
        String host,
        int port,
        String username,
        boolean playReady,
        String message,
        Instant updatedAt) {

    public AutomationSnapshot {
        Objects.requireNonNull(state, "state");
        host = host == null ? "" : host;
        username = username == null ? "" : username;
        playReady = state == AutomationState.CONNECTED && playReady;
        message = message == null ? "" : message;
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
    }

    public static AutomationSnapshot idle(String message) {
        return new AutomationSnapshot(AutomationState.IDLE, "", 0, "", false, message, Instant.now());
    }

    public static AutomationSnapshot forRequest(
            AutomationState state,
            UpstreamConnectRequest request,
            boolean playReady,
            String message) {
        return new AutomationSnapshot(
                state,
                request.host(),
                request.port(),
                request.username(),
                playReady,
                message,
                Instant.now());
    }

    public boolean hasTarget() {
        return !host.isBlank() && port > 0 && !username.isBlank();
    }

    public String targetLabel() {
        if (!hasTarget()) {
            return "none";
        }
        return username + "@" + host + ":" + port;
    }
}
