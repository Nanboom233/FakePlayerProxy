package com.fakeplayerproxy.automation;

import com.fakeplayerproxy.utils.Result;
import com.fakeplayerproxy.world.entity.Entity;
import com.fakeplayerproxy.world.player.Player;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import io.netty.util.concurrent.ScheduledFuture;
import it.unimi.dsi.fastutil.Pair;
import lombok.Getter;
import net.kyori.adventure.text.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

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
import org.cloudburstmc.math.vector.Vector3d;
import org.jetbrains.annotations.NotNull;

/** Mutable state for one authenticated relay connection. Access is restricted to its EventLoop. */
public final class AutomationService {
    private static final int CHAT_ACK_BATCH_SIZE = 65;
    private static final Set<KnownPack> SUPPORTED_KNOWN_PACKS = Set.of(
            new KnownPack("minecraft", "core", "26.2"));

    private final Map<ScheduledAction, Pair<Integer, Integer>> scheduledActions =
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
    private int scheduledUseCooldown;
    private List<KnownPack> offeredKnownPacks = List.of();
    private List<KnownPack> selectedKnownPacks = List.of();
    @Getter
    private boolean shadow;
    @Getter
    private boolean closed;

    public AutomationService(@NotNull Player owner) {
        this.owner = owner;
    }

    public void setTickTask(@NotNull ScheduledFuture<?> tickTask) {
        if (this.tickTask != null) {
            throw new IllegalStateException("Automation tick is already scheduled");
        }
        this.tickTask = tickTask;
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
        scheduledActions.clear();
        scheduledUseCooldown = 0;
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

    public CompletableFuture<Boolean> shadow() {
        // IDEA reports the borrowed EventLoop as unclosed. Velocity owns its lifecycle.
        //noinspection resource
        var eventLoop = owner.eventLoop();
        if (!eventLoop.inEventLoop()) {
            CompletableFuture<Boolean> result = new CompletableFuture<>();
            eventLoop.execute(() -> shadow().whenComplete((enabled, failure) -> {
                if (failure == null) {
                    result.complete(enabled);
                } else {
                    result.completeExceptionally(failure);
                }
            }));
            return result;
        }
        MinecraftConnection backend = owner.backendConnection();
        if (shadow || closed || backend == null) {
            return CompletableFuture.completedFuture(false);
        }
        var unavailableReason = owner.world().automationUnavailableReason();
        if (unavailableReason.isPresent()) {
            return CompletableFuture.completedFuture(false);
        }
        shadow = true;
        owner.prepareShadow(backend);
        owner.velocityPlayer().disconnect(Component.translatable(
                "fakeplayerproxy.disconnect.shadow"));
        return CompletableFuture.completedFuture(true);
    }

    public Result<Void, String> stopActions() {
        return runAction(backend -> {
            for (ScheduledAction action : ScheduledAction.values()) {
                inactiveScheduledAction(backend, action);
            }
            scheduledActions.clear();
            scheduledUseCooldown = 0;
            return owner.stopActions(backend);
        });
    }

    public Result<Void, String> look(float yaw, float pitch) {
        return runAction(backend -> owner.look(backend, yaw, pitch));
    }

    public Result<Void, String> turn(float yawDelta, float pitchDelta) {
        return runAction(backend -> owner.look(
                backend,
                owner.yaw() + yawDelta,
                owner.pitch() + pitchDelta
        ));
    }

    public Result<Void, String> lookAt(Vector3d target) {
        return runAction(backend -> owner.lookAt(backend, target));
    }

    public Result<Void, String> selectHotbar(int slotOneBased) {
        return runAction(backend -> owner.selectHotbar(backend, slotOneBased));
    }

    public Result<Void, String> move(String direction) {
        return runShadowAction(backend -> owner.moveInput(backend, direction));
    }

    public Result<Void, String> jump(ActionMode mode, int intervalTicks) {
        return runShadowAction(backend -> schedule(backend, ScheduledAction.JUMP, mode, intervalTicks));
    }

    public Result<Void, String> setSneak(boolean enabled) {
        return runAction(backend -> owner.setSneak(backend, enabled));
    }

    public Result<Void, String> setSprint(boolean enabled) {
        return runShadowAction(backend -> owner.setSprint(backend, enabled));
    }

    public Result<Void, String> attack(ActionMode mode, int intervalTicks) {
        return runAction(backend -> schedule(backend, ScheduledAction.ATTACK, mode, intervalTicks));
    }

    public Result<Void, String> use(ActionMode mode, int intervalTicks) {
        return runAction(backend -> schedule(backend, ScheduledAction.USE, mode, intervalTicks));
    }

    public boolean ownsContinuousUse() {
        Pair<Integer, Integer> state = scheduledActions.get(ScheduledAction.USE);
        return state != null && state.left() == -1;
    }

    public Result<Void, String> dropSelectedItem(boolean stack, ActionMode mode, int intervalTicks) {
        return runAction(backend -> schedule(
                backend, stack ? ScheduledAction.DROP_STACK : ScheduledAction.DROP, mode, intervalTicks));
    }

    public Result<Void, String> drop(boolean stack, int slot) {
        return runAction(backend -> owner.drop(backend, stack, slot));
    }

    public Result<Void, String> dropAll(boolean stack) {
        return runAction(backend -> {
            for (int slot = 40; slot >= 0; slot--) {
                Result<Void, String> result = owner.drop(backend, stack, slot);
                if (result instanceof Result.Failure<Void, String>) {
                    return result;
                }
            }
            return new Result.Success<>(null);
        });
    }

    public Result<Void, String> swapHands(ActionMode mode, int intervalTicks) {
        return runAction(backend -> schedule(backend, ScheduledAction.SWAP_HANDS, mode, intervalTicks));
    }

    public Result<Void, String> dismount() {
        return runAction(owner::dismount);
    }

    public Result<Void, String> mount(Vector3d target, boolean coordinate) {
        return runAction(backend -> owner.mount(backend, target, coordinate));
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
        if (inGame) {
            owner.passiveTick();
        }
        EnumSet<ScheduledAction> completed = runScheduledActions(backend);

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
        for (ScheduledAction action : completed) {
            scheduledActions.remove(action);
            inactiveScheduledAction(backend, action);
        }
        if (inGame) {
            owner.releaseDelayedInput(backend);
        }
    }

    private void respond(MinecraftConnection backend, Packet packet, boolean bypass) {
        if (!shadow || closed) {
            return;
        }
        // IDEA reports the borrowed backend EventLoop as unclosed. Velocity owns its lifecycle.
        //noinspection resource
        var eventLoop = backend.eventLoop();
        eventLoop.execute(() -> {
            if (!closed && shadow && backend.getChannel().isActive()) {
                backend.sendPacket(packet, bypass);
            }
        });
    }

    private Result<Void, String> schedule(
            MinecraftConnection backend,
            ScheduledAction action,
            ActionMode mode,
            int intervalTicks) {
        if (scheduledActions.containsKey(action)) {
            inactiveScheduledAction(backend, action);
        }
        int period = switch (mode) {
            case ONCE -> 0;
            case CONTINUOUS -> -1;
            case INTERVAL -> intervalTicks;
        };
        scheduledActions.put(action, Pair.of(period, period > 0 ? period : 1));
        return new Result.Success<>(null);
    }

    private EnumSet<ScheduledAction> runScheduledActions(MinecraftConnection backend) {
        EnumSet<ScheduledAction> due = EnumSet.noneOf(ScheduledAction.class);
        EnumSet<ScheduledAction> completed = EnumSet.noneOf(ScheduledAction.class);
        scheduledActions.replaceAll((action, state) -> {
            int remaining = state.right() - 1;
            if (remaining <= 0) {
                due.add(action);
                if (state.left() == 0) {
                    completed.add(action);
                    return Pair.of(0, 0);
                }
                return Pair.of(state.left(), state.left() < 0 ? 1 : state.left());
            }
            inactiveScheduledAction(backend, action);
            return Pair.of(state.left(), remaining);
        });
        for (ScheduledAction action : due) {
            Pair<Integer, Integer> state = scheduledActions.get(action);
            if (state.left() == 0 || state.left() == 1) {
                inactiveScheduledAction(backend, action);
            }
        }

        Boolean use = due.contains(ScheduledAction.USE) ? scheduledUse(backend) : null;
        Boolean attack = null;
        if (due.contains(ScheduledAction.ATTACK) && !Boolean.TRUE.equals(use)) {
            Result<Boolean, String> result = owner.attack(
                    backend, scheduledActions.get(ScheduledAction.ATTACK).left() == -1);
            attack = result instanceof Result.Success<Boolean, String>(var success) && success;
        }
        if (Boolean.FALSE.equals(use) && Boolean.TRUE.equals(attack)) {
            scheduledUse(backend);
        }
        for (ScheduledAction action : due) {
            if (action != ScheduledAction.USE && action != ScheduledAction.ATTACK) {
                sendScheduledAction(backend, action);
            }
        }
        return completed;
    }

    private boolean scheduledUse(MinecraftConnection backend) {
        if (scheduledUseCooldown > 0) {
            scheduledUseCooldown--;
            return true;
        }
        Result<Boolean, String> result = owner.use(backend);
        if (result instanceof Result.Success<Boolean, String>(var success) && success) {
            scheduledUseCooldown = 3;
            return true;
        }
        return false;
    }

    private void sendScheduledAction(MinecraftConnection backend, ScheduledAction action) {
        switch (action) {
            case USE, ATTACK -> {
            }
            case DROP -> owner.drop(backend, false);
            case DROP_STACK -> owner.drop(backend, true);
            case SWAP_HANDS -> owner.swapHands(backend);
            case JUMP -> owner.jump(backend);
        }
    }

    private void inactiveScheduledAction(MinecraftConnection backend, ScheduledAction action) {
        switch (action) {
            case USE -> {
                scheduledUseCooldown = 0;
                owner.inactiveUse(backend);
            }
            case ATTACK -> owner.inactiveAttack(backend);
            case JUMP -> owner.inactiveJump(backend);
            case DROP, DROP_STACK, SWAP_HANDS -> {
            }
        }
    }

    private Result<Void, String> runAction(
            Function<MinecraftConnection, Result<Void, String>> action) {
        // IDEA reports the borrowed Velocity EventLoop as unclosed. Velocity owns its lifecycle.
        //noinspection resource
        var eventLoop = owner.eventLoop();
        if (!eventLoop.inEventLoop()) {
            return new Result.Failure<>("fakeplayerproxy.command.automation_unavailable");
        }
        MinecraftConnection backend = owner.backendConnection();
        if (closed || !inGame || backend == null) {
            return new Result.Failure<>("fakeplayerproxy.command.automation_unavailable");
        }
        return action.apply(backend);
    }

    private Result<Void, String> runShadowAction(
            Function<MinecraftConnection, Result<Void, String>> action) {
        if (!shadow) {
            return new Result.Failure<>("fakeplayerproxy.command.automation_unavailable");
        }
        return runAction(action);
    }

    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        shadow = false;
        scheduledActions.clear();
        scheduledUseCooldown = 0;
        owner.resetForClose();
        if (tickTask != null) {
            tickTask.cancel(false);
            tickTask = null;
        }
    }

    private enum ScheduledAction {
        USE,
        ATTACK,
        JUMP,
        DROP,
        DROP_STACK,
        SWAP_HANDS
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
