package com.fakeplayerproxy.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fakeplayerproxy.automation.AutomationManager;
import com.fakeplayerproxy.automation.AutomationService;
import com.velocitypowered.api.proxy.Player;
import java.util.List;
import org.junit.jupiter.api.Test;

final class PlayerCommandTest {
    @Test
    void shadowUsesTheExactSourcePlayer() {
        AutomationManager manager = mock(AutomationManager.class);
        Player source = mock(Player.class);
        com.fakeplayerproxy.world.player.Player player =
                mock(com.fakeplayerproxy.world.player.Player.class);
        AutomationService service = mock(AutomationService.class);
        when(manager.get(source)).thenReturn(player);
        when(player.automationService()).thenReturn(service);
        when(service.shadow()).thenReturn(true);

        new PlayerCommand(manager).execute(source, new String[] {"shadow"});

        verify(manager).get(source);
        verify(service).shadow();
        verify(source, never()).getUniqueId();
    }

    @Test
    void onlyExactShadowIsAcceptedAndSuggested() {
        AutomationManager manager = mock(AutomationManager.class);
        Player source = mock(Player.class);
        com.fakeplayerproxy.world.player.Player player =
                mock(com.fakeplayerproxy.world.player.Player.class);
        AutomationService service = mock(AutomationService.class);
        when(manager.get(source)).thenReturn(player);
        when(player.automationService()).thenReturn(service);
        PlayerCommand command = new PlayerCommand(manager);

        command.execute(source, new String[] {"self", "shadow"});
        command.execute(source, new String[] {"stop"});

        verify(service, never()).shadow();
        assertEquals(List.of("shadow"), command.suggest(new String[] {}));
        assertEquals(List.of("shadow"), command.suggest(new String[] {"sh"}));
        assertEquals(List.of(), command.suggest(new String[] {"stop"}));
    }
}
