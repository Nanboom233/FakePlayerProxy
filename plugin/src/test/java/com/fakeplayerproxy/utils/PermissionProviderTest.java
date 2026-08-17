package com.fakeplayerproxy.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.event.permission.PermissionsSetupEvent;
import com.velocitypowered.api.permission.PermissionFunction;
import com.velocitypowered.api.permission.PermissionSubject;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PermissionProviderTest {
    @TempDir
    Path tempDir;

    @Test
    void fppPermissionUsesTheLiveOperatorSnapshotAndDelegatesOtherNodes() {
        PermissionProvider provider = new PermissionProvider(tempDir, Runnable::run);
        UUID storedUuid = UUID.randomUUID();
        Player stored = player(storedUuid, "Stored");
        Player other = player(UUID.randomUUID(), "Other");
        ConsoleCommandSource console = mock(ConsoleCommandSource.class);
        var bound = bind(provider, stored, permission -> Tristate.UNDEFINED);

        PermissionFunction storedPermissions = bound.createFunction(stored);
        assertEquals(Tristate.FALSE,
                storedPermissions.getPermissionValue(PermissionProvider.OP_PERMISSION));
        assertEquals(Tristate.TRUE, bound.createFunction(console)
                .getPermissionValue(PermissionProvider.OP_PERMISSION));
        assertEquals(Tristate.FALSE, bound.createFunction(other)
                .getPermissionValue(PermissionProvider.OP_PERMISSION));
        assertEquals(Tristate.UNDEFINED,
                storedPermissions.getPermissionValue("another.permission"));

        provider.grant(stored).join();
        assertEquals(Tristate.TRUE,
                storedPermissions.getPermissionValue(PermissionProvider.OP_PERMISSION));
        provider.revoke("stored").join();
        assertEquals(Tristate.FALSE,
                storedPermissions.getPermissionValue(PermissionProvider.OP_PERMISSION));
    }

    @Test
    void mutationsPersistAndPublishOnlyAfterAValidWrite() throws IOException {
        PermissionProvider provider = new PermissionProvider(tempDir, Runnable::run);
        Player alice = player(UUID.randomUUID(), "Alice");

        assertTrue(provider.grant(alice).join() instanceof Result.Success<?, ?>);
        assertEquals(List.of("Alice"), provider.names());
        PermissionProvider reloaded = new PermissionProvider(tempDir, Runnable::run);
        assertTrue(reloaded.load() instanceof Result.Success<?, ?>);
        assertEquals(List.of("Alice"), reloaded.names());
        assertTrue(provider.revoke("alice").join() instanceof Result.Success<?, ?>);
        assertTrue(provider.names().isEmpty());

        Path blockedDirectory = tempDir.resolve("blocked");
        Files.writeString(blockedDirectory, "not a directory");
        PermissionProvider blocked = new PermissionProvider(blockedDirectory, Runnable::run);
        assertTrue(blocked.grant(alice).join() instanceof Result.Failure<?, ?>);
        assertTrue(blocked.names().isEmpty());
    }

    @Test
    void malformedConfigurationFailsClosedWithoutRewritingTheFile() throws IOException {
        Path file = tempDir.resolve("ops.json");
        String malformed = "[{\"uuid\":\"not-a-uuid\",\"name\":\"Alice\"}]";
        Files.writeString(file, malformed);
        PermissionProvider provider = new PermissionProvider(tempDir, Runnable::run);

        assertTrue(provider.load() instanceof Result.Failure<?, ?>);
        assertTrue(provider.names().isEmpty());
        assertEquals(malformed, Files.readString(file));
    }

    private static com.velocitypowered.api.permission.PermissionProvider bind(
            PermissionProvider provider,
            PermissionSubject subject,
            PermissionFunction delegated) {
        PermissionsSetupEvent event = new PermissionsSetupEvent(subject, ignored -> delegated);
        provider.onPermissionsSetup(event);
        return event.getProvider();
    }

    private static Player player(UUID uuid, String name) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getUsername()).thenReturn(name);
        when(player.isOnlineMode()).thenReturn(true);
        return player;
    }
}
