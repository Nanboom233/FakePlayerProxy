package com.fakeplayerproxy.automation;

import com.fakeplayerproxy.world.entity.Entity;
import com.fakeplayerproxy.world.player.Player;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import io.netty.util.concurrent.ScheduledFuture;
import lombok.Getter;
import net.kyori.adventure.text.Component;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.KnownPack;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundKeepAlivePacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundPongPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.serverbound.ServerboundFinishConfigurationPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.serverbound.ServerboundSelectKnownPacks;
import org.geysermc.mcprotocollib.protocol.packet.cookie.serverbound.ServerboundCookieResponsePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatAckPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundConfigurationAcknowledgedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundPlayerLoadedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientTickEndPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundChunkBatchReceivedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerRotPacket;
import com.fakeplayerproxy.util.ProxyError;
import com.fakeplayerproxy.util.ProxyResult;
import java.util.function.Function;

/** Mutable state for one authenticated relay connection. Access is restricted to its EventLoop. */
public final class AutomationService {
    private static final int CHAT_ACK_BATCH_SIZE = 65;
    private static final Set<KnownPack> SUPPORTED_KNOWN_PACKS = Set.of(
            new KnownPack("minecraft", "core", "26.2"));

    private final Map<ScheduledAction, ScheduledActionState> scheduledActions =
            new EnumMap<>(ScheduledAction.class);

    private final Player owner;
    private ScheduledFuture<?> tickTask;
    private final Map<net.kyori.adventure.key.Key, byte[]> cookies = new HashMap<>();
    private final Set<Signature> pendingChatSignatures = new HashSet<>();
    private boolean inGame;
    private double clientTickAccumulatorMillis;
    private boolean initialPosition;
    private boolean playerLoaded;
    private boolean pendingConfigurationSwitch;
    private boolean waitingConfigurationFinish;
    private List<KnownPack> offeredKnownPacks = List.of();
    private List<KnownPack> selectedKnownPacks = List.of();
    @Getter
    private boolean shadow;
    private boolean closed;

    public AutomationService(Player owner) {
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    public void setTickTask(ScheduledFuture<?> tickTask) {
        if (this.tickTask != null) {
            throw new IllegalStateException("Automation tick is already scheduled");
        }
        this.tickTask = Objects.requireNonNull(tickTask, "tickTask");
    }

    public void enterGame() {
        inGame = true;
        playerLoaded = false;
        initialPosition = false;
        waitingConfigurationFinish = false;
        clientTickAccumulatorMillis = 0.0;
        scheduledActions.clear();
    }

    public void resumeGame() {
        inGame = true;
        playerLoaded = false;
        initialPosition = false;
        clientTickAccumulatorMillis = 0.0;
    }

    public void startConfiguration() {
        pendingConfigurationSwitch = false;
        waitingConfigurationFinish = false;
        pendingChatSignatures.clear();
        offeredKnownPacks = List.of();
        selectedKnownPacks = List.of();
        owner.resetForConfiguration();
        clientTickAccumulatorMillis = 0.0;
        inGame = false;
    }

    public void allowConfigurationSwitch() {
        pendingConfigurationSwitch = true;
    }

    public void markConfigurationFinish() {
        waitingConfigurationFinish = true;
    }

    public void finishConfiguration(MinecraftConnection backend) {
        if (!shadow || !waitingConfigurationFinish) {
            return;
        }
        waitingConfigurationFinish = false;
        backend.sendPacket(ServerboundFinishConfigurationPacket.INSTANCE, false);
        inGame = true;
    }

    public void keepAlive(MinecraftConnection backend, long pingId) {
        respond(backend, new ServerboundKeepAlivePacket(pingId), false);
    }

    public void pong(MinecraftConnection backend, int id) {
        respond(backend, new ServerboundPongPacket(id), true);
    }

    public void offerKnownPacks(MinecraftConnection backend, List<KnownPack> knownPacks) {
        offeredKnownPacks = copyPacks(knownPacks);
        if (shadow) {
            selectedKnownPacks = selectOfferedPacks(offeredKnownPacks);
            respond(backend, new ServerboundSelectKnownPacks(selectedKnownPacks), false);
        }
    }

    public ServerboundSelectKnownPacks selectKnownPacks(List<KnownPack> clientSelection) {
        List<KnownPack> copiedSelection = copyPacks(clientSelection);
        selectedKnownPacks = copiedSelection.equals(offeredKnownPacks)
                ? selectOfferedPacks(offeredKnownPacks)
                : List.of();
        return new ServerboundSelectKnownPacks(selectedKnownPacks);
    }

    public void chunkBatchFinished(MinecraftConnection backend, int batchSize) {
        float desiredChunksPerTick = Math.max(1.0f, batchSize);
        respond(backend, new ServerboundChunkBatchReceivedPacket(desiredChunksPerTick), true);
    }

    public void storeCookie(net.kyori.adventure.key.Key key, byte[] payload) {
        cookies.put(key, payload.clone());
    }

    public void requestCookie(MinecraftConnection backend, net.kyori.adventure.key.Key key) {
        byte[] payload = cookies.get(key);
        respond(backend, new ServerboundCookieResponsePacket(
                key, payload == null ? null : payload.clone()), true);
    }

    public void chat(MinecraftConnection backend, byte[] signature) {
        if (!shadow || signature == null || !pendingChatSignatures.add(new Signature(signature))) {
            return;
        }
        if (pendingChatSignatures.size() == CHAT_ACK_BATCH_SIZE) {
            pendingChatSignatures.clear();
            respond(backend, new ServerboundChatAckPacket(CHAT_ACK_BATCH_SIZE), false);
        }
    }

    public void acknowledgePosition(MinecraftConnection backend, int teleportId) {
        initialPosition = true;
        if (shadow) {
            respond(backend, new ServerboundAcceptTeleportationPacket(teleportId), true);
            respond(backend, new ServerboundMovePlayerPosRotPacket(
                    false, false,
                    owner.position().getX(), owner.position().getY(), owner.position().getZ(),
                    owner.yaw(), owner.pitch()), true);
        }
    }

    public void playerLoaded() {
        playerLoaded = true;
    }

    public void acknowledgeVehicle(MinecraftConnection backend) {
        Entity root = owner;
        while (root.vehicle() != null) {
            root = root.vehicle();
        }
        if (root != owner) {
            respond(backend, new org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundMoveVehiclePacket(
                    root.position(), root.yaw(), root.pitch(), root.onGround()), true);
        }
    }

    public boolean selectedKnownPacksProvideFixedRegistry() {
        return selectedKnownPacks.size() == SUPPORTED_KNOWN_PACKS.size()
                && SUPPORTED_KNOWN_PACKS.containsAll(selectedKnownPacks);
    }

    private static List<KnownPack> selectOfferedPacks(List<KnownPack> offeredPacks) {
        return SUPPORTED_KNOWN_PACKS.containsAll(offeredPacks)
                ? copyPacks(offeredPacks) : List.of();
    }

    private static List<KnownPack> copyPacks(List<KnownPack> packs) {
        return packs.stream()
                .map(pack -> new KnownPack(pack.getNamespace(), pack.getId(), pack.getVersion()))
                .toList();
    }

    public void acknowledgeRotation(MinecraftConnection backend) {
        if (shadow) {
            owner.recordSentRotation();
            respond(backend, new ServerboundMovePlayerRotPacket(
                    false, false, owner.yaw(), owner.pitch()), true);
        }
    }

    public boolean shadow() {
        // IDEA reports the borrowed EventLoop as unclosed. Velocity owns its lifecycle.
        //noinspection resource
        var eventLoop = owner.eventLoop();
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(() -> {
                if (!shadow()) {
                    owner.velocityPlayer().sendMessage(Component.translatable(
                            "fakeplayerproxy.command.automation_unavailable"));
                }
            });
            return true;
        }
        MinecraftConnection backend = owner.backendConnection();
        if (shadow || closed || backend == null) {
            return false;
        }
        var unavailableReason = owner.world().automationUnavailableReason();
        if (unavailableReason.isPresent()) {
            return false;
        }
        shadow = true;
        owner.prepareShadow(backend);
        owner.velocityPlayer().disconnect(Component.translatable(
                "fakeplayerproxy.disconnect.shadow"));
        return true;
    }

    public ProxyResult<Void> stopActions() {
        return runAction(backend -> {
            scheduledActions.clear();
            return owner.stopActions(backend);
        });
    }

    public ProxyResult<Void> look(float yaw, float pitch) {
        return runAction(backend -> owner.look(backend, yaw, pitch));
    }

    public ProxyResult<Void> turn(float yawDelta, float pitchDelta) {
        return runAction(backend -> owner.look(
                backend,
                owner.yaw() + yawDelta,
                owner.pitch() + pitchDelta
        ));
    }

    // The exact-shadow command does not expose this retained Automation action yet.
    @SuppressWarnings("unused")
    public ProxyResult<Void> selectHotbar(int slotOneBased) {
        return runAction(backend -> owner.selectHotbar(backend, slotOneBased));
    }

    public ProxyResult<Void> move(String direction) {
        return runAction(backend -> owner.moveInput(backend, direction));
    }

    // The exact-shadow command does not expose this retained Automation action yet.
    @SuppressWarnings("unused")
    public ProxyResult<Void> setJump(boolean enabled) {
        return runAction(backend -> {
            scheduledActions.remove(ScheduledAction.JUMP);
            return owner.setJump(backend, enabled);
        });
    }

    // The exact-shadow command does not expose this retained Automation action yet.
    @SuppressWarnings("unused")
    public ProxyResult<Void> jumpOnce() {
        return runAction(backend -> {
            scheduledActions.remove(ScheduledAction.JUMP);
            return owner.pulseInput(backend, owner.inputState().withJump(true));
        });
    }

    // The exact-shadow command does not expose this retained Automation action yet.
    @SuppressWarnings("unused")
    public ProxyResult<Void> jumpInterval(int intervalTicks) {
        return runAction(backend -> schedule(backend, ScheduledAction.JUMP, ActionMode.INTERVAL, intervalTicks));
    }

    // The exact-shadow command does not expose this retained Automation action yet.
    @SuppressWarnings("unused")
    public ProxyResult<Void> setSneak(boolean enabled) {
        return runAction(backend -> owner.setSneak(backend, enabled));
    }

    // The exact-shadow command does not expose this retained Automation action yet.
    @SuppressWarnings("unused")
    public ProxyResult<Void> setSprint(boolean enabled) {
        return runAction(backend -> owner.setSprint(backend, enabled));
    }

    public ProxyResult<Void> attack(ActionMode mode, int intervalTicks) {
        return runAction(backend -> schedule(backend, ScheduledAction.ATTACK, mode, intervalTicks));
    }

    public ProxyResult<Void> use(ActionMode mode, int intervalTicks) {
        return runAction(backend -> schedule(backend, ScheduledAction.USE, mode, intervalTicks));
    }

    // The exact-shadow command does not expose this retained Automation action yet.
    @SuppressWarnings("unused")
    public ProxyResult<Void> dropSelectedItem(boolean stack, ActionMode mode, int intervalTicks) {
        return runAction(backend -> schedule(
                backend, stack ? ScheduledAction.DROP_STACK : ScheduledAction.DROP, mode, intervalTicks));
    }

    public ProxyResult<Void> swapHands(ActionMode mode, int intervalTicks) {
        return runAction(backend -> schedule(backend, ScheduledAction.SWAP_HANDS, mode, intervalTicks));
    }

    public ProxyResult<Void> dismount() {
        return runAction(backend -> owner.pulseInput(backend, owner.inputState().withShift(true)));
    }

    public void tick(MinecraftConnection backend) {
        if (closed) {
            return;
        }
        if (shadow && pendingConfigurationSwitch) {
            pendingConfigurationSwitch = false;
            backend.sendPacket(new ServerboundConfigurationAcknowledgedPacket(), false);
            inGame = false;
        }
        scheduledActions.replaceAll((action, state) -> new ScheduledActionState(
                state.periodTicks(), state.remainingTicks() - 1));
        for (Map.Entry<ScheduledAction, ScheduledActionState> entry : scheduledActions.entrySet()) {
            ScheduledActionState state = entry.getValue();
            if (state.remainingTicks() > 0) {
                continue;
            }
            sendScheduledAction(backend, entry.getKey());
            entry.setValue(new ScheduledActionState(state.periodTicks(), state.periodTicks()));
        }

        if (shadow && inGame) {
            if (!playerLoaded
                    && initialPosition
                    && owner.world().isLevelChunksLoadStarted()
                    && owner.world().currentPlayerChunkLoaded(
                    owner.position().getX(), owner.position().getZ())) {
                backend.sendPacket(ServerboundPlayerLoadedPacket.INSTANCE);
                playerLoaded = true;
            }
            clientTickAccumulatorMillis += 50.0;
            double cadence = owner.world().clientTickCadenceMillis();
            if (clientTickAccumulatorMillis + 1.0E-7 >= cadence) {
                clientTickAccumulatorMillis -= cadence;
                owner.tick(backend, playerLoaded && !owner.dead());
                backend.sendPacket(ServerboundClientTickEndPacket.INSTANCE);
            }
        }
    }

    private void respond(MinecraftConnection backend, Packet packet, boolean bypass) {
        if (!shadow || closed) {
            return;
        }
        // Velocity owns the borrowed backend connection and event loop lifecycle.
        //noinspection resource
        var eventLoop = backend.eventLoop();
        eventLoop.execute(() -> {
            if (!closed && shadow && backend.getChannel().isActive()) {
                backend.sendPacket(packet, bypass);
            }
        });
    }

    private ProxyResult<Void> schedule(
            MinecraftConnection backend,
            ScheduledAction action,
            ActionMode mode,
            int intervalTicks) {
        Objects.requireNonNull(mode, "mode");
        if (mode == ActionMode.INTERVAL && intervalTicks < 1) {
            return unavailable("Interval ticks must be 1 or greater.");
        }
        ProxyResult<Void> first = sendScheduledAction(backend, action);
        if (!first.isSuccess() || mode == ActionMode.ONCE) {
            scheduledActions.remove(action);
            return first;
        }
        int period = mode == ActionMode.CONTINUOUS ? 1 : intervalTicks;
        scheduledActions.put(action, new ScheduledActionState(period, period));
        return ProxyResult.success();
    }

    private ProxyResult<Void> sendScheduledAction(
            MinecraftConnection backend, ScheduledAction action) {
        return switch (action) {
            case ATTACK -> owner.attack(backend);
            case USE -> owner.use(backend);
            case DROP -> owner.drop(backend, false);
            case DROP_STACK -> owner.drop(backend, true);
            case SWAP_HANDS -> owner.swapHands(backend);
            case JUMP -> owner.pulseInput(backend, owner.inputState().withJump(true));
        };
    }

    private ProxyResult<Void> runAction(
            Function<MinecraftConnection, ProxyResult<Void>> action) {
        // IDEA reports the borrowed EventLoop as unclosed. Velocity owns its lifecycle.
        //noinspection resource
        var eventLoop = owner.eventLoop();
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(() -> {
                if (closed) {
                    return;
                }
                MinecraftConnection backend = owner.backendConnection();
                if (backend != null && inGame) {
                    action.apply(backend);
                }
            });
            return ProxyResult.success();
        }
        MinecraftConnection backend = owner.backendConnection();
        if (closed || !inGame || backend == null) {
            return unavailable("Automation is not in an active game connection.");
        }
        return action.apply(backend);
    }

    private static ProxyResult<Void> unavailable(String message) {
        return ProxyResult.failure(new ProxyError("automation_unavailable", message));
    }

    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        shadow = false;
        scheduledActions.clear();
        if (tickTask != null) {
            tickTask.cancel(false);
            tickTask = null;
        }
    }

    private record ScheduledActionState(int periodTicks, int remainingTicks) {
    }

    private enum ScheduledAction {
        ATTACK,
        USE,
        DROP,
        DROP_STACK,
        SWAP_HANDS,
        JUMP
    }

    private record Signature(byte[] value) {
        Signature {
            value = value.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Signature(byte[] otherValue)
                    && java.util.Arrays.equals(value, otherValue);
        }

        @Override
        public int hashCode() {
            return java.util.Arrays.hashCode(value);
        }
    }
}
