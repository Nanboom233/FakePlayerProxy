package com.fakeplayerproxy.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fakeplayerproxy.automation.AutomationManager;
import com.fakeplayerproxy.automation.AutomationService;
import com.fakeplayerproxy.utils.PermissionProvider;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

final class PlayerCommandTest {
    @Test
    void selfShadowUsesTheExactSourcePlayer() throws CommandSyntaxException {
        AutomationManager manager = mock(AutomationManager.class);
        Player source = mock(Player.class);
        com.fakeplayerproxy.world.player.Player player = automationPlayer(true);
        when(manager.get(source)).thenReturn(player);
        CommandDispatcher<CommandSource> dispatcher = dispatcher(manager);

        assertEquals(1, dispatcher.execute("player shadow", source));

        verify(manager).get(source);
        verify(player.automationService()).shadow();
        verify(source, never()).getUniqueId();
    }

    @Test
    void authorizedAsShadowUsesTheNamedTarget() throws CommandSyntaxException {
        AutomationManager manager = mock(AutomationManager.class);
        CommandSource source = mock(CommandSource.class);
        when(source.hasPermission(PermissionProvider.OP_PERMISSION)).thenReturn(true);
        com.fakeplayerproxy.world.player.Player target = automationPlayer(true);
        when(manager.getByName("Target")).thenReturn(target);
        CommandDispatcher<CommandSource> dispatcher = dispatcher(manager);

        assertEquals(1, dispatcher.execute("player as Target shadow", source));
        verify(manager).getByName("Target");
        verify(target.automationService()).shadow();
    }

    @Test
    void deniedSourceCannotEnterAsBranch() {
        AutomationManager manager = mock(AutomationManager.class);
        CommandSource source = mock(CommandSource.class);
        CommandDispatcher<CommandSource> dispatcher = dispatcher(manager);

        assertThrows(CommandSyntaxException.class,
                () -> dispatcher.execute("player as Target shadow", source));
        verify(manager, never()).getByName(anyString());
    }

    @Test
    void missingTargetReportsUnavailable() throws CommandSyntaxException {
        AutomationManager manager = mock(AutomationManager.class);
        CommandSource source = mock(CommandSource.class);
        when(source.hasPermission(PermissionProvider.OP_PERMISSION)).thenReturn(true);
        CommandDispatcher<CommandSource> dispatcher = dispatcher(manager);

        assertEquals(0, dispatcher.execute("player as Missing shadow", source));
        verify(source).sendMessage(any(Component.class));
    }

    @Test
    void targetedShadowFailureReportsToTheCommandSource() throws CommandSyntaxException {
        AutomationManager manager = mock(AutomationManager.class);
        CommandSource source = mock(CommandSource.class);
        when(source.hasPermission(PermissionProvider.OP_PERMISSION)).thenReturn(true);
        com.fakeplayerproxy.world.player.Player target = automationPlayer(false);
        when(manager.getByName("Target")).thenReturn(target);
        CommandDispatcher<CommandSource> dispatcher = dispatcher(manager);

        assertEquals(1, dispatcher.execute("player as Target shadow", source));
        verify(source).sendMessage(any(Component.class));
    }

    @Test
    void targetSuggestionsReadTheLiveManagerSnapshot() {
        AutomationManager manager = mock(AutomationManager.class);
        CommandSource source = mock(CommandSource.class);
        when(source.hasPermission(PermissionProvider.OP_PERMISSION)).thenReturn(true);
        when(manager.names()).thenReturn(List.of("First")).thenReturn(List.of("Second"));
        CommandDispatcher<CommandSource> dispatcher = dispatcher(manager);

        assertEquals(List.of("First"), suggestions(dispatcher, source));
        assertEquals(List.of("Second"), suggestions(dispatcher, source));
    }

    private static CommandDispatcher<CommandSource> dispatcher(AutomationManager manager) {
        CommandDispatcher<CommandSource> dispatcher = new CommandDispatcher<>();
        dispatcher.getRoot().addChild(new PlayerCommand(manager).create().getNode());
        return dispatcher;
    }

    private static List<String> suggestions(
            CommandDispatcher<CommandSource> dispatcher,
            CommandSource source) {
        return dispatcher.getCompletionSuggestions(dispatcher.parse("player as ", source)).join()
                .getList().stream()
                .map(suggestion -> suggestion.getText())
                .toList();
    }

    private static com.fakeplayerproxy.world.player.Player automationPlayer(boolean shadowResult) {
        com.fakeplayerproxy.world.player.Player player =
                mock(com.fakeplayerproxy.world.player.Player.class);
        AutomationService service = mock(AutomationService.class);
        when(player.automationService()).thenReturn(service);
        when(service.shadow()).thenReturn(CompletableFuture.completedFuture(shadowResult));
        return player;
    }
}
