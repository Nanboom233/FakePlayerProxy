package com.fakeplayerproxy.command;

import com.fakeplayerproxy.automation.AutomationManager;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

import net.kyori.adventure.text.Component;

/** Exact command-source entry for handing a relay player to Shadow automation. */
public final class PlayerCommand implements SimpleCommand {
    private static final String SHADOW = "shadow";

    private final AutomationManager automationManager;

    public PlayerCommand(AutomationManager automationManager) {
        this.automationManager = Objects.requireNonNull(automationManager, "automationManager");
    }

    @Override
    public void execute(Invocation invocation) {
        execute(invocation.source(), invocation.arguments());
    }

    public void execute(CommandSource source, String[] arguments) {
        if (!(source instanceof Player velocityPlayer)) {
            source.sendMessage(Component.translatable("fakeplayerproxy.command.player_required"));
            return;
        }
        com.fakeplayerproxy.world.player.Player player = automationManager.get(velocityPlayer);
        if (player == null
                || arguments.length != 1
                || !SHADOW.equals(arguments[0].toLowerCase(Locale.ROOT))
                || !player.automationService().shadow()) {
            source.sendMessage(Component.translatable(
                    "fakeplayerproxy.command.automation_unavailable"));
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        return suggest(invocation.arguments());
    }

    public List<String> suggest(String[] arguments) {
        if (arguments.length == 0) {
            return List.of(SHADOW);
        }
        if (arguments.length == 1
                && SHADOW.startsWith(arguments[0].toLowerCase(Locale.ROOT))) {
            return List.of(SHADOW);
        }
        return List.of();
    }
}
