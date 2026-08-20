package com.fakeplayerproxy.utils;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.permission.PermissionsSetupEvent;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.jetbrains.annotations.NotNull;

/** Owns the persistent FPP operator snapshot and its Velocity permission behavior. */
public final class PermissionProvider implements AutoCloseable {
    public static final String OP_PERMISSION = "fakeplayerproxy.op";

    private static final Set<String> ENTRY_FIELDS = Set.of("uuid", "name");
    private static final Gson GSON = new Gson();
    private static final String FILE_NAME = "ops.json";
    private static final short LAST_PRIORITY = Short.MIN_VALUE + 1;

    private final Path dataDirectory;
    private final Path file;
    private final Executor executor;
    private volatile Map<UUID, String> operators = Map.of();

    public PermissionProvider(@NotNull Path dataDirectory) {
        this(dataDirectory, Executors.newSingleThreadExecutor(
                Thread.ofPlatform().daemon().name("fakeplayerproxy-config").factory()));
    }

    PermissionProvider(@NotNull Path dataDirectory, @NotNull Executor executor) {
        this.dataDirectory = dataDirectory;
        this.file = dataDirectory.resolve(FILE_NAME);
        this.executor = executor;
    }

    public Result<Void, String> load() {
        operators = Map.of();
        if (!Files.exists(file)) {
            return new Result.Success<>(null);
        }

        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement document = JsonParser.parseReader(reader);
            if (!document.isJsonArray()) {
                return new Result.Failure<>("The operator document must be a JSON array.");
            }
            JsonArray entries = document.getAsJsonArray();
            Map<UUID, String> loaded = HashMap.newHashMap(entries.size());
            Set<String> names = HashSet.newHashSet(entries.size());
            for (JsonElement element : entries) {
                if (!element.isJsonObject()) {
                    return new Result.Failure<>("Every operator entry must be a JSON object.");
                }
                JsonObject object = element.getAsJsonObject();
                if (!object.keySet().equals(ENTRY_FIELDS)
                        || !object.get("uuid").isJsonPrimitive()
                        || !object.get("uuid").getAsJsonPrimitive().isString()
                        || !object.get("name").isJsonPrimitive()
                        || !object.get("name").getAsJsonPrimitive().isString()) {
                    return new Result.Failure<>(
                            "Every operator entry must contain only string uuid and name fields.");
                }
                UUID uuid = UUID.fromString(object.get("uuid").getAsString());
                String name = object.get("name").getAsString();
                if (name.isBlank()) {
                    return new Result.Failure<>("Operator names must not be blank.");
                }
                if (loaded.putIfAbsent(uuid, name) != null
                        || !names.add(name.toLowerCase(Locale.ROOT))) {
                    return new Result.Failure<>("Operator UUIDs and names must be unique.");
                }
            }
            operators = Map.copyOf(loaded);
            return new Result.Success<>(null);
        } catch (IOException | IllegalArgumentException | JsonParseException exception) {
            return new Result.Failure<>("Could not read a valid ops.json: " + exception.getMessage());
        }
    }

    @Subscribe(priority = LAST_PRIORITY)
    public void onPermissionsSetup(PermissionsSetupEvent event) {
        var delegate = event.getProvider();
        event.setProvider(subject -> {
            var delegated = delegate.createFunction(subject);
            return permission -> {
                if (!OP_PERMISSION.equals(permission)) {
                    return delegated.getPermissionValue(permission);
                }
                if (subject instanceof ConsoleCommandSource) {
                    return Tristate.TRUE;
                }
                if (subject instanceof Player player && operators.containsKey(player.getUniqueId())) {
                    return Tristate.TRUE;
                }
                return Tristate.FALSE;
            };
        });
    }

    public List<String> names() {
        return operators.values().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    public CompletableFuture<Result<String, String>> grant(Player player) {
        if (player == null) {
            return CompletableFuture.completedFuture(new Result.Failure<>(
                    "Cannot grant operator access without a Velocity player."));
        }
        UUID uuid = player.getUniqueId();
        String name = player.getUsername();
        return CompletableFuture.supplyAsync(() -> {
            Map<UUID, String> candidate = new HashMap<>(operators);
            candidate.entrySet().removeIf(entry -> entry.getKey().equals(uuid)
                    || entry.getValue().equalsIgnoreCase(name));
            candidate.put(uuid, name);
            Result<Void, String> saved = save(candidate);
            return switch (saved) {
                case Result.Success<Void, String>(_) -> new Result.Success<>(name);
                case Result.Failure<Void, String>(var error) -> new Result.Failure<>(error);
            };
        }, executor);
    }

    public CompletableFuture<Result<Optional<String>, String>> revoke(@NotNull String name) {
        return CompletableFuture.supplyAsync(() -> {
            var removed = operators.entrySet().stream()
                    .filter(entry -> entry.getValue().equalsIgnoreCase(name))
                    .findFirst()
                    .orElse(null);
            if (removed == null) {
                return new Result.Success<>(Optional.empty());
            }
            Map<UUID, String> candidate = new HashMap<>(operators);
            candidate.remove(removed.getKey());
            Result<Void, String> saved = save(candidate);
            return switch (saved) {
                case Result.Success<Void, String>(_) ->
                        new Result.Success<>(Optional.of(removed.getValue()));
                case Result.Failure<Void, String>(var error) -> new Result.Failure<>(error);
            };
        }, executor);
    }

    private Result<Void, String> save(Map<UUID, String> candidate) {
        Path temporary = null;
        try {
            Files.createDirectories(dataDirectory);
            temporary = Files.createTempFile(dataDirectory, "ops-", ".tmp");
            JsonArray document = new JsonArray();
            candidate.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue(String.CASE_INSENSITIVE_ORDER))
                    .forEach(entry -> {
                        JsonObject object = new JsonObject();
                        object.addProperty("uuid", entry.getKey().toString());
                        object.addProperty("name", entry.getValue());
                        document.add(object);
                    });
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                GSON.toJson(document, writer);
            }
            Files.move(temporary, file,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            temporary = null;
            operators = Map.copyOf(candidate);
            return new Result.Success<>(null);
        } catch (AtomicMoveNotSupportedException exception) {
            return new Result.Failure<>(
                    "The filesystem does not support atomic ops.json replacement.");
        } catch (IOException exception) {
            return new Result.Failure<>(
                    "Could not atomically write ops.json: " + exception.getMessage());
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException _) {
                    // The failed temporary file is not active configuration.
                }
            }
        }
    }

    @Override
    public void close() {
        if (executor instanceof java.util.concurrent.ExecutorService service) {
            service.shutdown();
        }
    }
}
