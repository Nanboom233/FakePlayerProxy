package com.fakeplayerproxy.mod.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ConsentStoreTest {
    @Test
    void absentFileReturnsEmptyMap(@TempDir Path temporaryDirectory) throws Exception {
        var store = new ConsentStore(temporaryDirectory.resolve("consent_store.toml"));

        assertEquals(Map.of(), store.read());
    }

    @Test
    void readsEntriesInOrderAndUnescapesAddresses(@TempDir Path temporaryDirectory) throws Exception {
        Path file = temporaryDirectory.resolve("consent_store.toml");
        Files.writeString(
                file,
                "\"allow.example:25565\" = true\n"
                        + "\"quoted\\\"\\\\address\" = false\n",
                StandardCharsets.UTF_8);

        assertEquals(
                List.of(
                        Map.entry("allow.example:25565", true),
                        Map.entry("quoted\"\\address", false)),
                new ConsentStore(file).read().entrySet().stream().toList());
    }

    @Test
    void writesNewAndReplacementValuesWithoutChangingOtherEntries(
            @TempDir Path temporaryDirectory) throws Exception {
        Path file = temporaryDirectory.resolve("consent_store.toml");
        var store = new ConsentStore(file);

        store.write("first.example:25565", true);
        store.write("second.example:25565", false);
        store.write("first.example:25565", false);

        assertEquals(
                Map.of("first.example:25565", false, "second.example:25565", false),
                store.read());
    }

    @Test
    void deletesOneValueWithoutChangingOtherEntries(@TempDir Path temporaryDirectory)
            throws Exception {
        Path file = temporaryDirectory.resolve("consent_store.toml");
        var store = new ConsentStore(file);
        store.write("removed.example:25565", true);
        store.write("kept.example:25565", false);

        store.delete("removed.example:25565");

        assertEquals(Map.of("kept.example:25565", false), store.read());
    }

    @Test
    void writeRejectsMalformedSourceWithoutChangingIt(@TempDir Path temporaryDirectory)
            throws Exception {
        Path file = temporaryDirectory.resolve("consent_store.toml");
        String source = "not valid TOML\n";
        Files.writeString(file, source, StandardCharsets.UTF_8);

        assertThrows(
                IOException.class,
                () -> new ConsentStore(file).write("new.example:25565", true));
        assertEquals(source, Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void readOmitsBlankKeysWithoutChangingSource(@TempDir Path temporaryDirectory)
            throws Exception {
        Path file = temporaryDirectory.resolve("consent_store.toml");
        String source = "\"valid.example:25565\" = true\n\"   \" = false\n";
        Files.writeString(file, source, StandardCharsets.UTF_8);

        Map<String, Boolean> decisions = new ConsentStore(file).read();

        assertEquals(Map.of("valid.example:25565", true), decisions);
        assertFalse(decisions.containsKey("   "));
        assertEquals(source, Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void readRemovesEveryDuplicateOccurrenceWithoutChangingSource(
            @TempDir Path temporaryDirectory) throws Exception {
        Path file = temporaryDirectory.resolve("consent_store.toml");
        String source = "\"duplicate.example:25565\" = true\n"
                + "\"valid.example:25565\" = false\n"
                + "\"duplicate.example:25565\" = false\n"
                + "\"duplicate.example:25565\" = true\n";
        Files.writeString(file, source, StandardCharsets.UTF_8);

        Map<String, Boolean> decisions = new ConsentStore(file).read();

        assertEquals(Map.of("valid.example:25565", false), decisions);
        assertFalse(decisions.containsKey("duplicate.example:25565"));
        assertEquals(source, Files.readString(file, StandardCharsets.UTF_8));
    }
}
