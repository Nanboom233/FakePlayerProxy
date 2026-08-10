package com.fakeplayerproxy.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fakeplayerproxy.util.ProxyResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ProxyConfigLoaderTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsBundledDefaultsWhenUserConfigIsMissing() {
        ProxyConfigLoader loader = new ProxyConfigLoader();

        ProxyResult<ProxyConfig> result = loader.load(tempDir);

        assertTrue(result.isSuccess());
        assertEquals("127.0.0.1", result.valueOrThrow().targetHost());
        assertEquals(25566, result.valueOrThrow().targetPort());
        assertEquals("ProxyBot", result.valueOrThrow().username());
    }

    @Test
    void writesDefaultConfigFileWhenMissing() {
        ProxyConfigLoader loader = new ProxyConfigLoader();

        ProxyResult<Path> result = loader.ensureConfigFile(tempDir);

        assertTrue(result.isSuccess());
        assertTrue(Files.exists(result.valueOrThrow()));
    }

    @Test
    void userConfigOverridesBundledDefaults() throws IOException {
        ProxyConfigLoader loader = new ProxyConfigLoader();
        Files.writeString(
                loader.configPath(tempDir),
                """
                proxy.targetHost=localhost
                proxy.targetPort=25567
                proxy.username=OtherBot
                """);

        ProxyResult<ProxyConfig> result = loader.load(tempDir);

        assertTrue(result.isSuccess());
        assertEquals("localhost", result.valueOrThrow().targetHost());
        assertEquals(25567, result.valueOrThrow().targetPort());
        assertEquals("OtherBot", result.valueOrThrow().username());
    }

    @Test
    void invalidPortReturnsTypedError() {
        Properties properties = new Properties();
        properties.setProperty("proxy.targetHost", "localhost");
        properties.setProperty("proxy.targetPort", "not-a-port");
        properties.setProperty("proxy.username", "ProxyBot");

        ProxyResult<ProxyConfig> result = new ProxyConfigLoader().fromProperties(properties);

        assertTrue(result.error().isPresent());
        assertEquals("config_invalid_port", result.errorOrThrow().code());
    }
}
