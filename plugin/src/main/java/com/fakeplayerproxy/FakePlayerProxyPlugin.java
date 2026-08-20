package com.fakeplayerproxy;

import com.fakeplayerproxy.automation.AutomationManager;
import com.fakeplayerproxy.command.FppCommand;
import com.fakeplayerproxy.command.PlayerCommand;
import com.fakeplayerproxy.utils.AuthManager;
import com.fakeplayerproxy.utils.EventHandler;
import com.fakeplayerproxy.utils.PermissionProvider;
import com.fakeplayerproxy.utils.Result;
import com.google.inject.Inject;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.ResourceBundle;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.translation.GlobalTranslator;
import net.kyori.adventure.translation.TranslationStore;
import org.slf4j.Logger;

@Plugin(
        id = "fakeplayerproxy",
        name = "FakePlayerProxy",
        version = "0.1.0",
        description = "FakePlayerProxy runtime for a Velocity-based fake player proxy.",
        authors = {"FakePlayerProxy"})
public final class FakePlayerProxyPlugin {
    private static final String TRANSLATION_BUNDLE = "com.fakeplayerproxy.i18n.messages";

    private final ProxyServer server;
    private final Logger logger;
    private final AutomationManager automationManager;
    private final PermissionProvider permissionProvider;
    private final AuthManager authManager;
    private final EventHandler eventHandler;
    private final TranslationStore.StringBased<MessageFormat> translations;

    @Inject
    public FakePlayerProxyPlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.automationManager = new AutomationManager(logger);
        this.authManager = new AuthManager(automationManager, logger);
        this.eventHandler = new EventHandler(automationManager, logger);
        this.permissionProvider = new PermissionProvider(dataDirectory);
        this.translations = TranslationStore.messageFormat(Key.key("fakeplayerproxy", "translations"));
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        translations.defaultLocale(Locale.US);
        translations.registerAll(Locale.US,
                ResourceBundle.getBundle(TRANSLATION_BUNDLE, Locale.US), true);
        translations.registerAll(Locale.SIMPLIFIED_CHINESE,
                ResourceBundle.getBundle(TRANSLATION_BUNDLE, Locale.SIMPLIFIED_CHINESE), true);
        GlobalTranslator.translator().addSource(translations);

        server.getEventManager().register(this, permissionProvider);
        server.getEventManager().register(this, automationManager);
        server.getEventManager().register(this, authManager);
        server.getEventManager().register(this, eventHandler);
        server.getChannelRegistrar().register(AuthManager.CHANNEL);
        var loadedOperators = permissionProvider.load();
        if (loadedOperators instanceof Result.Failure<Void, String>(var error)) {
            logger.warn("Cannot load FakePlayerProxy operators; player access remains denied: {}",
                    error);
        }

        CommandManager commandManager = server.getCommandManager();
        BrigadierCommand fppCommand = new FppCommand(
                server, permissionProvider, authManager, logger).create();
        CommandMeta fppMeta = commandManager.metaBuilder(fppCommand).plugin(this).build();
        commandManager.register(fppMeta, fppCommand);
        BrigadierCommand playerCommand = new PlayerCommand(automationManager, logger).create();
        CommandMeta playerMeta = commandManager.metaBuilder(playerCommand).plugin(this).build();
        commandManager.register(playerMeta, playerCommand);

        logger.info("FakePlayerProxy loaded.");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        automationManager.shutdown();
        server.getChannelRegistrar().unregister(AuthManager.CHANNEL);
        permissionProvider.close();
        GlobalTranslator.translator().removeSource(translations);
    }
}
