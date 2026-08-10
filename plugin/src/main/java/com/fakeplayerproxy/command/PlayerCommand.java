package com.fakeplayerproxy.command;

import com.velocitypowered.api.command.SimpleCommand;
import java.util.List;
import java.util.Objects;

public final class PlayerCommand implements SimpleCommand {
    private final PlayerCommandHandler handler;

    public PlayerCommand(PlayerCommandHandler handler) {
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    @Override
    public void execute(Invocation invocation) {
        handler.execute(invocation.source(), invocation.arguments());
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        return handler.suggest(invocation.arguments());
    }
}
