package com.fakeplayerproxy.config;

import java.util.Objects;
import java.util.regex.Pattern;

public record ProxyConfig(
        String targetHost,
        int targetPort,
        String username,
        ReconnectConfig reconnect) {
    private static final Pattern USERNAME_PATTERN = Pattern.compile("[A-Za-z0-9_]{3,16}");

    public static final ProxyConfig DEFAULT =
            new ProxyConfig("127.0.0.1", 25566, "ProxyBot", ReconnectConfig.DEFAULT);

    public ProxyConfig(String targetHost, int targetPort, String username) {
        this(targetHost, targetPort, username, ReconnectConfig.DEFAULT);
    }

    public ProxyConfig {
        targetHost = requireNonBlank(targetHost, "targetHost");
        username = requireNonBlank(username, "username");
        Objects.requireNonNull(reconnect, "reconnect");
        if (targetPort < 1 || targetPort > 65535) {
            throw new IllegalArgumentException("targetPort must be between 1 and 65535.");
        }
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw new IllegalArgumentException("username must be 3-16 characters: letters, numbers, or underscore.");
        }
    }

    public String targetLabel() {
        return username + "@" + targetHost + ":" + targetPort;
    }

    private static String requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank.");
        }
        return trimmed;
    }
}
