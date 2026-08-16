package com.fakeplayerproxy.mod.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ConsentStoreTest {
    @Test
    void persistsAllowAndDeclineByServer(@TempDir Path temporaryDirectory) throws Exception {
        Path file = temporaryDirectory.resolve("consent_store.toml");
        var store = new ConsentStore(file);
        store.remember("allow.example:25565", true);
        store.remember("decline.example:25565", false);

        var reloaded = new ConsentStore(file);
        assertEquals(
                true,
                reloaded.find("allow.example:25565").orElseThrow());
        assertEquals(
                false,
                reloaded.find("decline.example:25565").orElseThrow());
    }
}
