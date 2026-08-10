package com.fakeplayerproxy.config;

import com.fakeplayerproxy.util.ProxyError;
import com.fakeplayerproxy.util.ProxyResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;

public final class ProxyConfigLoader {
    public static final String CONFIG_FILE_NAME = "fakeplayerproxy.properties";
    public static final String DEFAULT_RESOURCE_NAME = "fakeplayerproxy-default.properties";

    private final ClassLoader classLoader;

    public ProxyConfigLoader() {
        this(ProxyConfigLoader.class.getClassLoader());
    }

    ProxyConfigLoader(ClassLoader classLoader) {
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
    }

    public ProxyResult<Path> ensureConfigFile(Path dataDirectory) {
        Path configPath = configPath(dataDirectory);
        if (Files.exists(configPath)) {
            return ProxyResult.success(configPath);
        }

        ProxyResult<Properties> defaults = loadBundledDefaults();
        if (!defaults.isSuccess()) {
            return ProxyResult.failure(defaults.errorOrThrow());
        }

        try {
            Files.createDirectories(dataDirectory);
            try (Writer writer = Files.newBufferedWriter(configPath, StandardCharsets.UTF_8)) {
                defaults.valueOrThrow().store(writer, "FakePlayerProxy configuration");
            }
            return ProxyResult.success(configPath);
        } catch (IOException e) {
            return ProxyResult.failure(new ProxyError(
                    "config_write_failed",
                    "Could not create config file: " + e.getMessage()));
        }
    }

    public ProxyResult<ProxyConfig> load(Path dataDirectory) {
        ProxyResult<Properties> defaults = loadBundledDefaults();
        if (!defaults.isSuccess()) {
            return ProxyResult.failure(defaults.errorOrThrow());
        }

        Properties properties = defaults.valueOrThrow();
        Path configPath = configPath(dataDirectory);
        if (Files.exists(configPath)) {
            try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
                properties.load(reader);
            } catch (IOException e) {
                return ProxyResult.failure(new ProxyError(
                        "config_read_failed",
                        "Could not read config file: " + e.getMessage()));
            }
        }

        return fromProperties(properties);
    }

    public ProxyResult<ProxyConfig> fromProperties(Properties properties) {
        Objects.requireNonNull(properties, "properties");
        String host = requireProperty(properties, "proxy.targetHost");
        String portText = requireProperty(properties, "proxy.targetPort");
        String username = requireProperty(properties, "proxy.username");
        String reconnectEnabledText = optionalProperty(properties, "proxy.reconnect.enabled", "true");
        String reconnectMaxAttemptsText = optionalProperty(properties, "proxy.reconnect.maxAttempts", "3");
        String reconnectDelayMillisText = optionalProperty(properties, "proxy.reconnect.delayMillis", "1000");
        String reconnectAuthMode = optionalProperty(
                properties,
                "proxy.reconnect.authMode",
                ReconnectConfig.OFFLINE_CONTROLLED_AUTH_MODE);

        if (host == null || portText == null || username == null) {
            return ProxyResult.failure(new ProxyError(
                    "config_missing_value",
                    "Config must define proxy.targetHost, proxy.targetPort, and proxy.username."));
        }

        int port;
        try {
            port = Integer.parseInt(portText.trim());
        } catch (NumberFormatException e) {
            return ProxyResult.failure(new ProxyError(
                    "config_invalid_port",
                "proxy.targetPort must be a number between 1 and 65535."));
        }

        boolean reconnectEnabled = Boolean.parseBoolean(reconnectEnabledText);
        int reconnectMaxAttempts;
        long reconnectDelayMillis;
        try {
            reconnectMaxAttempts = Integer.parseInt(reconnectMaxAttemptsText.trim());
            reconnectDelayMillis = Long.parseLong(reconnectDelayMillisText.trim());
        } catch (NumberFormatException e) {
            return ProxyResult.failure(new ProxyError(
                    "config_invalid_reconnect",
                    "proxy.reconnect.maxAttempts and proxy.reconnect.delayMillis must be numeric."));
        }

        try {
            ReconnectConfig reconnect = new ReconnectConfig(
                    reconnectEnabled,
                    reconnectMaxAttempts,
                    reconnectDelayMillis,
                    reconnectAuthMode);
            return ProxyResult.success(new ProxyConfig(host, port, username, reconnect));
        } catch (IllegalArgumentException e) {
            return ProxyResult.failure(new ProxyError("config_invalid_value", e.getMessage()));
        }
    }

    public Path configPath(Path dataDirectory) {
        return Objects.requireNonNull(dataDirectory, "dataDirectory").resolve(CONFIG_FILE_NAME);
    }

    private ProxyResult<Properties> loadBundledDefaults() {
        Properties properties = new Properties();
        try (InputStream input = classLoader.getResourceAsStream(DEFAULT_RESOURCE_NAME)) {
            if (input == null) {
                return ProxyResult.failure(new ProxyError(
                        "config_defaults_missing",
                        "Bundled default settings are missing from the plugin jar."));
            }
            properties.load(input);
            return ProxyResult.success(properties);
        } catch (IOException e) {
            return ProxyResult.failure(new ProxyError(
                    "config_defaults_read_failed",
                    "Could not read bundled default settings: " + e.getMessage()));
        }
    }

    private static String requireProperty(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    private static String optionalProperty(Properties properties, String key, String defaultValue) {
        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return value.trim();
    }
}
