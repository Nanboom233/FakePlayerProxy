package com.fakeplayerproxy.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fakeplayerproxy.utils.PermissionProvider;
import com.fakeplayerproxy.utils.AuthManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

final class FppCommandTest {
    @TempDir
    Path tempDir;

    @Test
    void authorizedOpAndDeopUpdateTheOperatorSnapshot() throws CommandSyntaxException {
        ProxyServer server = mock(ProxyServer.class);
        CommandSource source = mock(CommandSource.class);
        when(source.hasPermission(PermissionProvider.OP_PERMISSION)).thenReturn(true);
        UUID uuid = UUID.randomUUID();
        Player player = player(uuid, "Alice");
        when(server.getPlayer("Alice")).thenReturn(Optional.of(player));
        PermissionProvider config = new PermissionProvider(tempDir);
        CommandDispatcher<CommandSource> dispatcher = dispatcher(server, config);

        try {
            assertEquals(1, dispatcher.execute("fpp op Alice", source));
            verify(source, timeout(5000)).sendMessage(any(Component.class));
            verify(player, timeout(5000)).refreshCommands();
            assertEquals(List.of("Alice"), config.names());

            when(server.getPlayer("Alice")).thenReturn(Optional.of(player));
            assertEquals(1, dispatcher.execute("fpp deop alice", source));
            verify(source, timeout(5000).times(2)).sendMessage(any(Component.class));
            verify(player, timeout(5000).times(2)).refreshCommands();
            assertEquals(List.of(), config.names());
        } finally {
            config.close();
        }
    }

    @Test
    void deniedBranchesAreNotVisibleOrExecutable() {
        ProxyServer server = mock(ProxyServer.class);
        CommandSource source = mock(CommandSource.class);
        PermissionProvider config = new PermissionProvider(tempDir);
        try {
            CommandDispatcher<CommandSource> dispatcher = dispatcher(server, config);

            assertFalse(dispatcher.getRoot().getChild("fpp").getChild("op").canUse(source));
            assertFalse(dispatcher.getRoot().getChild("fpp").getChild("deop").canUse(source));
            assertThrows(CommandSyntaxException.class,
                    () -> dispatcher.execute("fpp op Alice", source));
            assertThrows(CommandSyntaxException.class,
                    () -> dispatcher.execute("fpp deop Alice", source));
        } finally {
            config.close();
        }
    }

    @Test
    void failedPersistenceDoesNotRefreshTheTarget() throws Exception {
        Path blockedDirectory = tempDir.resolve("blocked");
        Files.writeString(blockedDirectory, "not a directory");
        PermissionProvider config = new PermissionProvider(blockedDirectory);
        ProxyServer server = mock(ProxyServer.class);
        CommandSource source = mock(CommandSource.class);
        when(source.hasPermission(PermissionProvider.OP_PERMISSION)).thenReturn(true);
        Player player = player(UUID.randomUUID(), "Alice");
        when(server.getPlayer("Alice")).thenReturn(Optional.of(player));

        try {
            assertEquals(1, dispatcher(server, config).execute("fpp op Alice", source));
            verify(source, timeout(5000)).sendMessage(any(Component.class));
            verify(player, never()).refreshCommands();
        } finally {
            config.close();
        }
    }

    private static CommandDispatcher<CommandSource> dispatcher(
            ProxyServer server,
            PermissionProvider config) {
        CommandDispatcher<CommandSource> dispatcher = new CommandDispatcher<>();
        dispatcher.getRoot().addChild(
                new FppCommand(server, config, mock(AuthManager.class), mock(Logger.class))
                        .create().getNode());
        return dispatcher;
    }

    private static Player player(UUID uuid, String name) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getUsername()).thenReturn(name);
        when(player.isOnlineMode()).thenReturn(true);
        return player;
    }
}
