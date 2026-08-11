package com.fakeplayerproxy;

import com.fakeplayerproxy.command.FppCommand;
import com.fakeplayerproxy.command.PlayerCommand;
import com.fakeplayerproxy.command.PlayerCommandHandler;
import com.fakeplayerproxy.config.ProxyConfig;
import com.fakeplayerproxy.config.ProxyConfigLoader;
import com.fakeplayerproxy.automation.AutomationService;
import com.fakeplayerproxy.automation.ProtocolTarget;
import com.fakeplayerproxy.util.ProxyResult;
import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import java.nio.file.Path;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

@Plugin(
        id = "fakeplayerproxy",
        name = "FakePlayerProxy",
        version = "0.1.0",
        description = "FakePlayerProxy runtime for a Velocity-based fake player proxy.",
        authors = {"FakePlayerProxy"})
public final class FakePlayerProxyPlugin {
    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private final AutomationService automationService;

    @Inject
    public FakePlayerProxyPlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.automationService = new AutomationService(logger::warn);
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        ProxyConfigLoader configLoader = new ProxyConfigLoader();

        ProxyResult<Path> configFile = configLoader.ensureConfigFile(dataDirectory);
        if (!configFile.isSuccess()) {
            logger.warn(configFile.errorOrThrow().safeMessage());
        }

        ProxyResult<ProxyConfig> loadedConfig = configLoader.load(dataDirectory);
        ProxyConfig proxyConfig;
        if (loadedConfig.isSuccess()) {
            proxyConfig = loadedConfig.valueOrThrow();
        } else {
            logger.warn("{} Falling back to built-in default settings.", loadedConfig.errorOrThrow().safeMessage());
            proxyConfig = ProxyConfig.DEFAULT;
        }
        automationService.setReconnectConfig(proxyConfig.reconnect());

        CommandManager commandManager = server.getCommandManager();
        PlayerCommandHandler playerCommandHandler = new PlayerCommandHandler(proxyConfig, automationService);
        CommandMeta fppMeta = commandManager.metaBuilder("fpp")
                .plugin(this)
                .build();
        commandManager.register(fppMeta, new FppCommand(proxyConfig, automationService, playerCommandHandler));

        CommandMeta playerMeta = commandManager.metaBuilder("player")
                .plugin(this)
                .build();
        commandManager.register(playerMeta, new PlayerCommand(playerCommandHandler));

        logger.info(
                "FakePlayerProxy loaded. Protocol target: {}. Default target: {}",
                ProtocolTarget.displayName(),
                proxyConfig.targetLabel());
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        automationService.shutdown();
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        event.getPlayer().sendMessage(Component.translatable(
                "fakeplayerproxy.message.encryption_verified", NamedTextColor.GREEN));
    }
}
