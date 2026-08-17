package com.fakeplayerproxy;

import com.fakeplayerproxy.automation.AutomationManager;
import com.fakeplayerproxy.command.FppCommand;
import com.fakeplayerproxy.command.PlayerCommand;
import com.fakeplayerproxy.config.PermissionProvider;
import com.fakeplayerproxy.world.data.Decoder;
import com.google.inject.Inject;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.ServerboundPacketEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.connection.ClientboundPacketEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.configuration.PlayerEnterConfigurationEvent;
import com.velocitypowered.api.event.player.configuration.PlayerFinishedConfigurationEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.proxy.connection.MinecraftConnection;

import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.function.BiConsumer;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.translation.GlobalTranslator;
import net.kyori.adventure.translation.TranslationStore;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundKeepAlivePacket;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundPingPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundStoreCookiePacket;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundUpdateTagsPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundFinishConfigurationPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundRegistryDataPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundSelectKnownPacks;
import org.geysermc.mcprotocollib.protocol.packet.configuration.serverbound.ServerboundSelectKnownPacks;
import org.geysermc.mcprotocollib.protocol.packet.cookie.clientbound.ClientboundCookieRequestPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundRespawnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundStartConfigurationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundTickingStatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundTickingStepPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundAddEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundEntityPositionSyncPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundMoveEntityPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundMoveEntityPosRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundMoveEntityRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundMoveMinecartPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundMoveVehiclePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundRemoveMobEffectPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundRemoveEntitiesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityDataPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityMotionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEquipmentPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetPassengersPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundTeleportEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundUpdateAttributesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundUpdateMobEffectPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerAbilitiesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerLookAtPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerRotationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundSetHealthPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundChunkBatchFinishedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundExplodePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundBlockUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundBlockEntityDataPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundBlockEventPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundForgetLevelChunkPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundGameEventPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelChunkWithLightPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSectionBlocksUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.border.ClientboundInitializeBorderPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.border.ClientboundSetBorderCenterPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.border.ClientboundSetBorderLerpSizePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.border.ClientboundSetBorderSizePacket;
import org.geysermc.mcprotocollib.protocol.data.game.level.notify.GameEvent;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundPlayerLoadedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerStatusOnlyPacket;
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
    private final TranslationStore.StringBased<MessageFormat> translations;

    @Inject
    public FakePlayerProxyPlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.automationManager = new AutomationManager(logger);
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
        var loadedOperators = permissionProvider.load();
        if (!loadedOperators.isSuccess()) {
            logger.warn("Cannot load FakePlayerProxy operators; player access remains denied: {}",
                    loadedOperators.errorOrThrow().safeMessage());
        }

        CommandManager commandManager = server.getCommandManager();
        BrigadierCommand fppCommand = new FppCommand(server, permissionProvider, logger).create();
        CommandMeta fppMeta = commandManager.metaBuilder(fppCommand).plugin(this).build();
        commandManager.register(fppMeta, fppCommand);
        BrigadierCommand playerCommand = new PlayerCommand(automationManager).create();
        CommandMeta playerMeta = commandManager.metaBuilder(playerCommand).plugin(this).build();
        commandManager.register(playerMeta, playerCommand);

        logger.info("FakePlayerProxy loaded.");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        automationManager.shutdown();
        permissionProvider.close();
        GlobalTranslator.translator().removeSource(translations);
    }

    @Subscribe
    public EventTask onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        return EventTask.withContinuation(continuation ->
            automationManager.register(player).whenComplete((ignored, failure) -> {
                if (failure == null) {
                    continuation.resume();
                } else {
                    continuation.resumeWithException(failure);
                }
            }));
    }

    @Subscribe
    public EventTask onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        return EventTask.withContinuation(continuation -> {
            com.fakeplayerproxy.world.player.Player automationPlayer = automationManager.get(player);
            if (automationPlayer == null) {
                continuation.resume();
                return;
            }
            // IDEA reports the borrowed EventLoop as unclosed. Velocity owns its lifecycle.
            //noinspection resource
            var eventLoop = automationPlayer.eventLoop();
            eventLoop.execute(() -> {
                if (automationPlayer.automationService().isShadow()) {
                    event.cancel();
                }
                continuation.resume();
            });
        });
    }

    @Subscribe
    public EventTask onPlayerEnterConfiguration(PlayerEnterConfigurationEvent event) {
        Player player = event.player();
        return EventTask.withContinuation(continuation -> {
            com.fakeplayerproxy.world.player.Player automationPlayer = automationManager.get(player);
            if (automationPlayer == null) {
                continuation.resume();
                return;
            }
            // IDEA reports the borrowed EventLoop as unclosed. Velocity owns its lifecycle.
            //noinspection resource
            var eventLoop = automationPlayer.eventLoop();
            eventLoop.execute(() -> {
                withPlayer(player, (playerState, backend) ->
                        playerState.automationService().allowConfigurationSwitch());
                continuation.resume();
            });
        });
    }

    @Subscribe
    public void onPlayerFinishedConfiguration(PlayerFinishedConfigurationEvent event) {
        Player player = event.player();
        com.fakeplayerproxy.world.player.Player automationPlayer = automationManager.get(player);
        if (automationPlayer == null) {
            return;
        }
        // IDEA reports the borrowed EventLoop as unclosed. Velocity owns its lifecycle.
        //noinspection resource
        var eventLoop = automationPlayer.eventLoop();
        eventLoop.execute(() ->
                withPlayer(player, (playerState, backend) ->
                        playerState.automationService().finishConfiguration(backend)));
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        if (automationManager.get(event.getPlayer()) != null) {
            event.getPlayer().sendMessage(Component.translatable(
                    "fakeplayerproxy.message.encryption_verified", NamedTextColor.GREEN));
        }
    }

    @Subscribe(async = false)
    public void onKeepAlive(ClientboundPacketEvent<ClientboundKeepAlivePacket> event) {
        withPlayer(event.getPlayer(), (player, backend) ->
                player.automationService().keepAlive(backend, event.getPacket().getPingId()));
    }

    @Subscribe(async = false)
    public void onPing(ClientboundPacketEvent<ClientboundPingPacket> event) {
        withPlayer(event.getPlayer(), (player, backend) ->
                player.automationService().pong(backend, event.getPacket().getId()));
    }

    @Subscribe(async = false)
    public void onStartConfiguration(ClientboundPacketEvent<ClientboundStartConfigurationPacket> event) {
        withPlayer(event.getPlayer(), (player, backend) -> player.automationService().startConfiguration());
    }

    @Subscribe(async = false)
    public void onKnownPacks(ClientboundPacketEvent<ClientboundSelectKnownPacks> event) {
        withPlayer(event.getPlayer(), (player, backend) ->
                player.automationService().offerKnownPacks(backend, event.getPacket().getKnownPacks()));
    }

    @Subscribe(async = false)
    public void onSelectedKnownPacks(ServerboundPacketEvent<ServerboundSelectKnownPacks> event) {
        withPlayer(event.getPlayer(), (player, backend) -> event.setPacket(
                player.automationService().selectKnownPacks(event.getPacket().getKnownPacks())));
    }

    @Subscribe(async = false)
    public void onRegistryData(ClientboundPacketEvent<ClientboundRegistryDataPacket> event) {
        withPlayer(event.getPlayer(), (player, backend) -> {
            ClientboundRegistryDataPacket packet = event.getPacket();
            player.world().registry(packet.getRegistry(), Decoder.instance().completeDimensionTypes(
                    packet.getRegistry(), packet.getEntries(),
                    player.automationService().selectedKnownPacksProvideFixedRegistry()));
        });
    }

    @Subscribe(async = false)
    public void onFinishConfiguration(ClientboundPacketEvent<ClientboundFinishConfigurationPacket> event) {
        withPlayer(event.getPlayer(), (player, backend) ->
                player.automationService().markConfigurationFinish());
    }

    @Subscribe(async = false)
    public void onChunkBatchFinished(ClientboundPacketEvent<ClientboundChunkBatchFinishedPacket> event) {
        withPlayer(event.getPlayer(), (player, backend) ->
                player.automationService().chunkBatchFinished(backend, event.getPacket().getBatchSize()));
    }

    @Subscribe(async = false)
    public void onStoreCookie(ClientboundPacketEvent<ClientboundStoreCookiePacket> event) {
        withPlayer(event.getPlayer(), (player, backend) ->
                player.automationService().storeCookie(event.getPacket().getKey(), event.getPacket().getPayload()));
    }

    @Subscribe(async = false)
    public void onCookieRequest(ClientboundPacketEvent<ClientboundCookieRequestPacket> event) {
        withPlayer(event.getPlayer(), (player, backend) ->
                player.automationService().requestCookie(backend, event.getPacket().getKey()));
    }

    @Subscribe(async = false)
    public void onPlayerChat(ClientboundPacketEvent<ClientboundPlayerChatPacket> event) {
        withPlayer(event.getPlayer(), (player, backend) ->
                player.automationService().chat(backend, event.getPacket().getMessageSignature()));
    }

    @Subscribe(async = false)
    public void onLogin(ClientboundPacketEvent<ClientboundLoginPacket> event) {
        withPlayer(event.getPlayer(), (player, backend) ->
        {
            player.initializeGame(
                    event.getPacket().getEntityId(), event.getPacket().getCommonPlayerSpawnInfo());
            player.automationService().enterGame();
        });
    }

    @Subscribe(async = false)
    public void onRespawn(ClientboundPacketEvent<ClientboundRespawnPacket> event) {
        withPlayer(event.getPlayer(), (player, backend) -> {
            ClientboundRespawnPacket packet = event.getPacket();
            player.respawn(packet.getCommonPlayerSpawnInfo(),
                    packet.isKeepMetadata(), packet.isKeepAttributeModifiers());
            player.automationService().resumeGame();
        });
    }

    @Subscribe(async = false)
    public void onTickingState(ClientboundPacketEvent<ClientboundTickingStatePacket> event) {
        withPlayer(event.getPlayer(), (player, backend) -> player.world().tickingState(
                event.getPacket().getTickRate(), event.getPacket().isFrozen()));
    }

    @Subscribe(async = false)
    public void onTickingStep(ClientboundPacketEvent<ClientboundTickingStepPacket> event) {
        withPlayer(event.getPlayer(), (player, backend) ->
                player.world().tickingStep(event.getPacket().getTickSteps()));
    }

    @Subscribe(async = false)
    public void onUpdateTags(ClientboundPacketEvent<ClientboundUpdateTagsPacket> event) {
        withPlayer(event.getPlayer(), (player, backend) ->
                player.world().physicalTags(event.getPacket().getTags()));
    }

    @Subscribe(async = false)
    public void onGameEvent(ClientboundPacketEvent<ClientboundGameEventPacket> event) {
        ClientboundGameEventPacket packet = event.getPacket();
        if (packet.getNotification() == GameEvent.LEVEL_CHUNKS_LOAD_START) {
            withPlayer(event.getPlayer(), (player, backend) -> player.world().levelChunksLoadStarted());
        } else if (packet.getNotification() == GameEvent.CHANGE_GAME_MODE
                && packet.getValue() instanceof org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode gameMode) {
            withPlayer(event.getPlayer(), (player, backend) -> player.gameMode(gameMode));
        }
    }

    @Subscribe(async = false)
    public void onForgetChunk(ClientboundPacketEvent<ClientboundForgetLevelChunkPacket> event) {
        withPlayer(event.getPlayer(), (player, backend) ->
                player.world().forgetChunk(event.getPacket().getX(), event.getPacket().getZ()));
    }

    @Subscribe(async = false)
    public void onLevelChunk(ClientboundPacketEvent<ClientboundLevelChunkWithLightPacket> event) {
        withPlayer(event.getPlayer(), (player, backend) -> {
            ClientboundLevelChunkWithLightPacket packet = event.getPacket();
            var result = player.world().decodeAndInstallChunk(
                    packet.getX(), packet.getZ(), packet.getChunkData(), packet.getBlockEntities());
            if (!result.installed()) {
                result.cause().ifPresentOrElse(
                        cause -> logger.warn(
                                "Cannot install LevelChunk at ({}, {}): {}",
                                packet.getX(), packet.getZ(), result.detail(), cause),
                        () -> logger.warn(
                                "Cannot install LevelChunk at ({}, {}): {}",
                                packet.getX(), packet.getZ(), result.detail()));
            }
        });
    }

    @Subscribe(async = false)
    public void onBlockEntityData(ClientboundPacketEvent<ClientboundBlockEntityDataPacket> event) {
        withPlayer(event.getPlayer(), (player, backend) -> player.world().blockEntity(
                event.getPacket().getPosition(), event.getPacket().getType(), event.getPacket().getNbt()));
    }

    @Subscribe(async = false)
    public void onBlockEvent(ClientboundPacketEvent<ClientboundBlockEventPacket> event) {
        withPlayer(event.getPlayer(), (player, backend) -> {
            ClientboundBlockEventPacket packet = event.getPacket();
            if (packet.getType()
                    instanceof org.geysermc.mcprotocollib.protocol.data.game.level.block.value.PistonValueType type
                    && packet.getValue()
                    instanceof org.geysermc.mcprotocollib.protocol.data.game.level.block.value.PistonValue value) {
                player.world().blockEvent(packet.getPosition(), type, value.getDirection(), packet.getBlockId())
                        .ifPresent(detail -> logger.warn("Cannot apply piston BlockEvent: {}", detail));
            }
        });
    }

    @Subscribe(async = false)
    public void onBlockUpdate(ClientboundPacketEvent<ClientboundBlockUpdatePacket> event) {
        withPlayer(event.getPlayer(), (player, backend) ->
                player.world().updateBlock(event.getPacket().getEntry()));
    }

    @Subscribe(async = false)
    public void onSectionBlocksUpdate(ClientboundPacketEvent<ClientboundSectionBlocksUpdatePacket> event) {
        withPlayer(event.getPlayer(), (player, backend) ->
                player.world().updateSection(event.getPacket().getEntries()));
    }

    @Subscribe(async = false)
    public void onPlayerPosition(ClientboundPacketEvent<ClientboundPlayerPositionPacket> event) {
        withPlayer(event.getPlayer(), (player, backend) -> {
            ClientboundPlayerPositionPacket packet = event.getPacket();
            player.applyServerPosition(packet.getPosition(), packet.getDeltaMovement(),
                    packet.getYRot(), packet.getXRot(), packet.getRelatives());
            player.automationService().acknowledgePosition(backend, packet.getId());
        });
    }

    @Subscribe(async = false)
    public void onPlayerRotation(ClientboundPacketEvent<ClientboundPlayerRotationPacket> event) {
        withPlayer(event.getPlayer(), (player, backend) -> {
            ClientboundPlayerRotationPacket packet = event.getPacket();
            player.applyServerRotation(packet.getYRot(), packet.isRelativeY(),
                    packet.getXRot(), packet.isRelativeX());
            player.automationService().acknowledgeRotation(backend);
        });
    }

    @Subscribe(async = false)
    public void onHealth(ClientboundPacketEvent<ClientboundSetHealthPacket> event) {
        withPlayer(event.getPlayer(), (player, backend) ->
                player.setHealth(event.getPacket().getHealth()));
    }

    @Subscribe(async = false)
    public void onMotion(ClientboundPacketEvent<ClientboundSetEntityMotionPacket> event) {
        withPlayer(event.getPlayer(), (player, backend) -> {
            var entity = player.world().entity(event.getPacket().getEntityId());
            if (entity != null) {
                entity.setVelocity(event.getPacket().getMovement());
            }
        });
    }

    @Subscribe(async = false)
    public void onAddEntity(ClientboundPacketEvent<ClientboundAddEntityPacket> event) {
        withPlayer(event.getPlayer(), (player, backend) -> {
            ClientboundAddEntityPacket packet = event.getPacket();
            player.world().addEntity(
                    packet.getEntityId(),
                    packet.getType(),
                    org.cloudburstmc.math.vector.Vector3d.from(packet.getX(), packet.getY(), packet.getZ()),
                    packet.getMovement(),
                    packet.getYaw(),
                    packet.getPitch());
        });
    }

    @Subscribe(async = false)
    public void onRemoveEntities(ClientboundPacketEvent<ClientboundRemoveEntitiesPacket> event) {
        withPlayer(event.getPlayer(), (player, backend) ->
                player.world().removeEntities(event.getPacket().getEntityIds()));
    }

    @Subscribe(async = false)
    public void onMoveEntityPos(ClientboundPacketEvent<ClientboundMoveEntityPosPacket> event) {
        withPlayer(event.getPlayer(), (player, backend) -> {
            ClientboundMoveEntityPosPacket packet = event.getPacket();
            var entity = player.world().entity(packet.getEntityId());
            if (entity != null) {
                boolean localAuthoritative = entity.isControlledBy(player);
                entity.interpolate(
                        org.cloudburstmc.math.vector.Vector3d.from(
                                packet.getMoveX(), packet.getMoveY(), packet.getMoveZ()),
                        true, 0.0f, 0.0f, false, localAuthoritative);
                if (!localAuthoritative) {
                    entity.setCollisionFlags(packet.isOnGround(), entity.horizontalCollision());
                }
            }
        });
    }

    @Subscribe(async = false)
    public void onMoveEntityRot(ClientboundPacketEvent<ClientboundMoveEntityRotPacket> event) {
        withPlayer(event.getPlayer(), (player, backend) -> {
            ClientboundMoveEntityRotPacket packet = event.getPacket();
            var entity = player.world().entity(packet.getEntityId());
            if (entity != null) {
                boolean localAuthoritative = entity.isControlledBy(player);
                entity.interpolate(org.cloudburstmc.math.vector.Vector3d.ZERO,
                        false, packet.getYaw(), packet.getPitch(), true, localAuthoritative);
                if (!localAuthoritative) {
                    entity.setCollisionFlags(packet.isOnGround(), entity.horizontalCollision());
                }
            }
        });
    }

    @Subscribe(async = false)
    public void onMoveEntityPosRot(ClientboundPacketEvent<ClientboundMoveEntityPosRotPacket> event) {
        withPlayer(event.getPlayer(), (player, backend) -> {
            ClientboundMoveEntityPosRotPacket packet = event.getPacket();
            var entity = player.world().entity(packet.getEntityId());
            if (entity != null) {
                boolean localAuthoritative = entity.isControlledBy(player);
                entity.interpolate(
                        org.cloudburstmc.math.vector.Vector3d.from(
                                packet.getMoveX(), packet.getMoveY(), packet.getMoveZ()),
                        true, packet.getYaw(), packet.getPitch(), true, localAuthoritative);
                if (!localAuthoritative) {
                    entity.setCollisionFlags(packet.isOnGround(), entity.horizontalCollision());
                }
            }
        });
    }

    @Subscribe(async = false)
    public void onEntityPositionSync(ClientboundPacketEvent<ClientboundEntityPositionSyncPacket> event) {
        withPlayer(event.getPlayer(), (player, backend) -> {
            ClientboundEntityPositionSyncPacket packet = event.getPacket();
            var entity = player.world().entity(packet.getId());
            if (entity != null) {
                entity.positionSync(packet.getPosition(), packet.getYRot(), packet.getXRot(),
                        packet.isOnGround(), entity.isControlledBy(player));
            }
        });
    }

    @Subscribe(async = false)
    public void onTeleportEntity(ClientboundPacketEvent<ClientboundTeleportEntityPacket> event) {
        withPlayer(event.getPlayer(), (player, backend) -> {
            ClientboundTeleportEntityPacket packet = event.getPacket();
            var entity = player.world().entity(packet.getId());
            if (entity != null) {
                entity.teleport(packet.getPosition(), packet.getDeltaMovement(),
                        packet.getYRot(), packet.getXRot(), packet.getRelatives(), packet.isOnGround());
            }
        });
    }

    @Subscribe(async = false)
    public void onMoveVehicle(ClientboundPacketEvent<ClientboundMoveVehiclePacket> event) {
        withPlayer(event.getPlayer(), (player, backend) -> {
            player.applyVehiclePosition(
                    event.getPacket().getPosition(), event.getPacket().getYRot(), event.getPacket().getXRot());
            player.automationService().acknowledgeVehicle(backend);
        });
    }

    @Subscribe(async = false)
    public void onEntityData(ClientboundPacketEvent<ClientboundSetEntityDataPacket> event) {
        withPlayer(event.getPlayer(), (player, backend) -> {
            var entity = player.world().entity(event.getPacket().getEntityId());
            if (entity != null) {
                for (var metadata : event.getPacket().getMetadata()) {
                    entity.applyMetadata(metadata.getId(), metadata.getValue());
                }
            }
        });
    }

    @Subscribe(async = false)
    public void onPassengers(ClientboundPacketEvent<ClientboundSetPassengersPacket> event) {
        withPlayer(event.getPlayer(), (player, backend) -> player.world().setPassengers(
                event.getPacket().getEntityId(), event.getPacket().getPassengerIds()));
    }

    @Subscribe(async = false)
    public void onAttributes(ClientboundPacketEvent<ClientboundUpdateAttributesPacket> event) {
        withPlayer(event.getPlayer(), (player, backend) -> {
            var entity = player.world().entity(event.getPacket().getEntityId());
            if (entity instanceof com.fakeplayerproxy.world.entity.LivingEntity livingEntity) {
                livingEntity.updateAttributes(event.getPacket().getAttributes());
            }
        });
    }

    @Subscribe(async = false)
    public void onEffect(ClientboundPacketEvent<ClientboundUpdateMobEffectPacket> event) {
        withPlayer(event.getPlayer(), (player, backend) -> {
            var entity = player.world().entity(event.getPacket().getEntityId());
            if (entity instanceof com.fakeplayerproxy.world.entity.LivingEntity livingEntity) {
                livingEntity.updateEffect(event.getPacket().getEffect(), event.getPacket().getAmplifier());
            }
        });
    }

    @Subscribe(async = false)
    public void onRemoveEffect(ClientboundPacketEvent<ClientboundRemoveMobEffectPacket> event) {
        withPlayer(event.getPlayer(), (player, backend) -> {
            var entity = player.world().entity(event.getPacket().getEntityId());
            if (entity instanceof com.fakeplayerproxy.world.entity.LivingEntity livingEntity) {
                livingEntity.removeEffect(event.getPacket().getEffect());
            }
        });
    }

    @Subscribe(async = false)
    public void onEquipment(ClientboundPacketEvent<ClientboundSetEquipmentPacket> event) {
        withPlayer(event.getPlayer(), (player, backend) -> {
            var entity = player.world().entity(event.getPacket().getEntityId());
            if (entity instanceof com.fakeplayerproxy.world.entity.LivingEntity livingEntity) {
                livingEntity.updateEquipment(event.getPacket().getEquipment());
            }
        });
    }

    @Subscribe(async = false)
    public void onAbilities(ClientboundPacketEvent<ClientboundPlayerAbilitiesPacket> event) {
        withPlayer(event.getPlayer(), (player, backend) -> player.abilities(
                event.getPacket().isCanFly(), event.getPacket().isFlying()));
    }

    @Subscribe(async = false)
    public void onMinecartSteps(ClientboundPacketEvent<ClientboundMoveMinecartPacket> event) {
        withPlayer(event.getPlayer(), (player, backend) -> {
            var entity = player.world().entity(event.getPacket().getEntityId());
            if (entity != null && entity.isMinecart()) {
                entity.queueMinecartSteps(event.getPacket().getLerpSteps());
            }
        });
    }

    @Subscribe(async = false)
    public void onLookAt(ClientboundPacketEvent<ClientboundPlayerLookAtPacket> event) {
        withPlayer(event.getPlayer(), (player, backend) -> {
            ClientboundPlayerLookAtPacket packet = event.getPacket();
            player.lookAt(packet.getOrigin(), packet.getX(), packet.getY(), packet.getZ(),
                    packet.getTargetEntityId(), packet.getTargetEntityOrigin());
        });
    }

    @Subscribe(async = false)
    public void onInitializeBorder(ClientboundPacketEvent<ClientboundInitializeBorderPacket> event) {
        withPlayer(event.getPlayer(), (player, backend) -> {
            ClientboundInitializeBorderPacket packet = event.getPacket();
            player.world().border(packet.getNewCenterX(), packet.getNewCenterZ(),
                    packet.getOldSize(), packet.getNewSize(), packet.getLerpTime());
        });
    }

    @Subscribe(async = false)
    public void onBorderCenter(ClientboundPacketEvent<ClientboundSetBorderCenterPacket> event) {
        withPlayer(event.getPlayer(), (player, backend) -> player.world().borderCenter(
                event.getPacket().getNewCenterX(), event.getPacket().getNewCenterZ()));
    }

    @Subscribe(async = false)
    public void onBorderSize(ClientboundPacketEvent<ClientboundSetBorderSizePacket> event) {
        withPlayer(event.getPlayer(), (player, backend) ->
                player.world().borderSize(event.getPacket().getSize()));
    }

    @Subscribe(async = false)
    public void onBorderLerp(ClientboundPacketEvent<ClientboundSetBorderLerpSizePacket> event) {
        withPlayer(event.getPlayer(), (player, backend) -> player.world().borderLerp(
                event.getPacket().getOldSize(), event.getPacket().getNewSize(),
                event.getPacket().getLerpTime()));
    }

    @Subscribe(async = false)
    public void onExplosion(ClientboundPacketEvent<ClientboundExplodePacket> event) {
        withPlayer(event.getPlayer(), (player, backend) -> {
            if (event.getPacket().getPlayerKnockback() != null) {
                player.addVelocity(event.getPacket().getPlayerKnockback());
            }
        });
    }

    @Subscribe(async = false)
    public void onPlayerLoaded(ServerboundPacketEvent<ServerboundPlayerLoadedPacket> event) {
        withPlayer(event.getPlayer(), (player, backend) -> player.automationService().playerLoaded());
    }

    @Subscribe(async = false)
    public void onMovePos(ServerboundPacketEvent<ServerboundMovePlayerPosPacket> event) {
        withPlayer(event.getPlayer(), (player, backend) -> {
            ServerboundMovePlayerPosPacket packet = event.getPacket();
            player.clientPosition(packet.getX(), packet.getY(), packet.getZ(),
                    packet.isOnGround(), packet.isHorizontalCollision());
        });
    }

    @Subscribe(async = false)
    public void onMovePosRot(ServerboundPacketEvent<ServerboundMovePlayerPosRotPacket> event) {
        withPlayer(event.getPlayer(), (player, backend) -> {
            ServerboundMovePlayerPosRotPacket packet = event.getPacket();
            player.clientPosition(packet.getX(), packet.getY(), packet.getZ(),
                    packet.isOnGround(), packet.isHorizontalCollision());
            player.clientRotation(packet.getYaw(), packet.getPitch(),
                    packet.isOnGround(), packet.isHorizontalCollision());
        });
    }

    @Subscribe(async = false)
    public void onMoveRot(ServerboundPacketEvent<ServerboundMovePlayerRotPacket> event) {
        withPlayer(event.getPlayer(), (player, backend) -> {
            ServerboundMovePlayerRotPacket packet = event.getPacket();
            player.clientRotation(packet.getYaw(), packet.getPitch(),
                    packet.isOnGround(), packet.isHorizontalCollision());
        });
    }

    @Subscribe(async = false)
    public void onMoveStatus(ServerboundPacketEvent<ServerboundMovePlayerStatusOnlyPacket> event) {
        withPlayer(event.getPlayer(), (player, backend) -> {
            ServerboundMovePlayerStatusOnlyPacket packet = event.getPacket();
            player.clientStatus(packet.isOnGround(), packet.isHorizontalCollision());
        });
    }

    private void withPlayer(
            Player player,
            BiConsumer<com.fakeplayerproxy.world.player.Player, MinecraftConnection> action) {
        com.fakeplayerproxy.world.player.Player automationPlayer = automationManager.get(player);
        if (automationPlayer == null) {
            return;
        }
        MinecraftConnection backend = automationPlayer.backendConnection();
        if (backend != null) {
            action.accept(automationPlayer, backend);
        }
    }
}
