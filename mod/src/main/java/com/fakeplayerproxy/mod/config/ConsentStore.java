package com.fakeplayerproxy.mod.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.fabricmc.loader.api.FabricLoader;

/** Stores per-server consent booleans in the Fabric configuration directory. */
public final class ConsentStore {
    private static final Pattern ENTRY = Pattern.compile(
            "^\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*=\\s*(true|false)\\s*(?:#.*)?$");
    private final Path file;

    public ConsentStore(Path file) {
        this.file = file;
    }

    public static ConsentStore fromFabricConfig() {
        return new ConsentStore(FabricLoader.getInstance()
                .getConfigDir()
                .resolve("fakeplayerproxy")
                .resolve("consent_store.toml"));
    }

    public synchronized Optional<Boolean> find(String serverAddress) throws IOException {
        return Optional.ofNullable(readDecisions().get(serverAddress));
    }

    public synchronized void remember(String serverAddress, boolean allow) throws IOException {
        Map<String, Boolean> decisions = readDecisions();
        decisions.put(serverAddress, allow);

        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            StringBuilder content = new StringBuilder();
            decisions.forEach((address, decision) -> content
                    .append('"')
                    .append(escape(address))
                    .append("\" = ")
                    .append(decision)
                    .append(System.lineSeparator()));
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(
                        temporary,
                        file,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Map<String, Boolean> readDecisions() throws IOException {
        Map<String, Boolean> decisions = new LinkedHashMap<>();
        if (!Files.exists(file)) {
            return decisions;
        }
        int lineNumber = 0;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            lineNumber++;
            if (line.isBlank() || line.stripLeading().startsWith("#")) {
                continue;
            }
            Matcher matcher = ENTRY.matcher(line);
            if (!matcher.matches()) {
                throw new IOException("Cannot parse consent store line " + lineNumber + ": " + file);
            }
            decisions.put(unescape(matcher.group(1)), Boolean.parseBoolean(matcher.group(2)));
        }
        return decisions;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescape(String value) throws IOException {
        StringBuilder result = new StringBuilder(value.length());
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (escaped) {
                if (character != '\\' && character != '\"') {
                    throw new IOException("Unsupported escape in consent store key");
                }
                result.append(character);
                escaped = false;
            } else if (character == '\\') {
                escaped = true;
            } else {
                result.append(character);
            }
        }
        if (escaped) {
            throw new IOException("Incomplete escape in consent store key");
        }
        return result.toString();
    }
}
