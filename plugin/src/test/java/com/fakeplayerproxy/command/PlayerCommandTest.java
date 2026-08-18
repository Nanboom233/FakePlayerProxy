package com.fakeplayerproxy.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

import com.fakeplayerproxy.automation.AutomationManager;
import com.fakeplayerproxy.automation.AutomationService;
import com.fakeplayerproxy.utils.PermissionProvider;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import io.netty.channel.EventLoop;
import org.cloudburstmc.math.vector.Vector3d;
import com.fakeplayerproxy.utils.Result;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

final class PlayerCommandTest {
    @Test
    void selfActionUsesOwnerEventLoopAndCoordinateMountCentersIntegers() throws Exception {
        AutomationManager manager = mock(AutomationManager.class);
        Player source = mock(Player.class);
        com.fakeplayerproxy.world.player.Player player = automationPlayer(true);
        AutomationService service = player.automationService();
        EventLoop eventLoop = mock(EventLoop.class);
        when(player.eventLoop()).thenReturn(eventLoop);
        when(player.position()).thenReturn(Vector3d.from(10.0, 20.0, 30.0));
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(eventLoop).execute(any(Runnable.class));
        when(service.stopActions()).thenReturn(new Result.Success<>(null));
        when(service.mount(Vector3d.from(1.5, 2.0, 3.5), true))
                .thenReturn(new Result.Success<>(null));
        when(manager.get(source)).thenReturn(player);
        CommandDispatcher<CommandSource> dispatcher = dispatcher(manager);

        assertEquals(1, dispatcher.execute("player stop", source));
        assertEquals(1, dispatcher.execute("player mount 1 2 3", source));

        verify(service).stopActions();
        verify(service).mount(Vector3d.from(1.5, 2.0, 3.5), true);
        verify(manager, times(2)).get(source);
    }

    @Test
    void deferredBranchesAreAbsent() {
        var player = dispatcher(mock(AutomationManager.class)).getRoot().getChild("player");

        assertNull(player.getChild("spawn"));
        assertNull(player.getChild("mount").getChild("anything"));
        assertNull(player.getChild("jump"));
        assertNull(player.getChild("move"));
        assertNull(player.getChild("sprint"));
        assertNull(player.getChild("unsprint"));
        assertNull(player.getChild("kill"));
    }

    @Test
    void nonShadowTargetCannotEnterAContextGuardedAction() {
        AutomationManager manager = mock(AutomationManager.class);
        CommandSource source = mock(CommandSource.class);
        when(source.hasPermission(PermissionProvider.OP_PERMISSION)).thenReturn(true);
        com.fakeplayerproxy.world.player.Player target = automationPlayer(false);
        when(manager.getByName("Target")).thenReturn(target);
        CommandDispatcher<CommandSource> dispatcher = dispatcher(manager);

        assertThrows(CommandSyntaxException.class,
                () -> dispatcher.execute("player as Target move forward", source));
        verify(target.automationService(), never()).move(anyString());
    }
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

    @Test
    void actionFailureIsContainedAndReported() throws CommandSyntaxException {
        AutomationManager manager = mock(AutomationManager.class);
        Logger logger = mock(Logger.class);
        Player source = mock(Player.class);
        com.fakeplayerproxy.world.player.Player player = automationPlayer(true);
        EventLoop eventLoop = mock(EventLoop.class);
        when(player.eventLoop()).thenReturn(eventLoop);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(eventLoop).execute(any(Runnable.class));
        when(player.automationService().stopActions()).thenThrow(new IllegalStateException("action failed"));
        when(manager.get(source)).thenReturn(player);
        CommandDispatcher<CommandSource> dispatcher = dispatcher(manager, logger);

        assertEquals(1, dispatcher.execute("player stop", source));

        verify(source).sendMessage(any(Component.class));
        verify(logger).error(anyString(), any(IllegalStateException.class));
    }

    private static CommandDispatcher<CommandSource> dispatcher(AutomationManager manager) {
        return dispatcher(manager, mock(Logger.class));
    }

    private static CommandDispatcher<CommandSource> dispatcher(
            AutomationManager manager, Logger logger) {
        CommandDispatcher<CommandSource> dispatcher = new CommandDispatcher<>();
        dispatcher.getRoot().addChild(new PlayerCommand(manager, logger).create().getNode());
        return dispatcher;
    }

    private static List<String> suggestions(
            CommandDispatcher<CommandSource> dispatcher,
            CommandSource source) {
        return dispatcher.getCompletionSuggestions(dispatcher.parse("player as ", source)).join()
                .getList().stream()
                .map(com.mojang.brigadier.suggestion.Suggestion::getText)
                .toList();
    }

    private static com.fakeplayerproxy.world.player.Player automationPlayer(boolean shadowResult) {
        com.fakeplayerproxy.world.player.Player player =
                mock(com.fakeplayerproxy.world.player.Player.class);
        AutomationService service = mock(AutomationService.class);
        when(player.automationService()).thenReturn(service);
        when(service.isShadow()).thenReturn(shadowResult);
        when(service.shadow()).thenReturn(CompletableFuture.completedFuture(shadowResult));
        return player;
    }
}
