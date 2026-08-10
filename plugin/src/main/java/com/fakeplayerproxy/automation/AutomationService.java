package com.fakeplayerproxy.automation;

import com.fakeplayerproxy.config.ReconnectConfig;
import com.fakeplayerproxy.protocol.McProtocolLibUpstreamClient;
import com.fakeplayerproxy.protocol.UpstreamClient;
import com.fakeplayerproxy.util.ProxyError;
import com.fakeplayerproxy.util.ProxyResult;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class AutomationService {
    private static final long MINECRAFT_TICK_MILLIS = 50L;

    private final FailureReporter failureReporter;
    private final ScheduledExecutorService reconnectExecutor;
    private final Map<ScheduledAction, ScheduledFuture<?>> scheduledActions = new EnumMap<>(ScheduledAction.class);

    private AutomationSnapshot snapshot = AutomationSnapshot.idle("No upstream connection.");
    private ReconnectConfig reconnectConfig = ReconnectConfig.DEFAULT;
    private UpstreamClient currentClient;
    private UpstreamConnectRequest currentRequest;
    private InputState inputState = InputState.CLEAR;
    private int reconnectAttempts;
    private boolean intentionalDisconnect;
    private boolean shuttingDown;

    public AutomationService(FailureReporter failureReporter) {
        this(failureReporter, Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "fakeplayerproxy-reconnect");
            thread.setDaemon(true);
            return thread;
        }));
    }

    AutomationService(
            FailureReporter failureReporter,
            ScheduledExecutorService reconnectExecutor) {
        this.failureReporter = Objects.requireNonNull(failureReporter, "failureReporter");
        this.reconnectExecutor = Objects.requireNonNull(reconnectExecutor, "reconnectExecutor");
    }

    public synchronized void setReconnectConfig(ReconnectConfig reconnectConfig) {
        this.reconnectConfig = Objects.requireNonNull(reconnectConfig, "reconnectConfig");
    }

    public synchronized ProxyResult<AutomationSnapshot> connect(UpstreamConnectRequest request) {
        Objects.requireNonNull(request, "request");
        if (!canStartConnection()) {
            return ProxyResult.failure(new ProxyError(
                    "automation_connection_active",
                    "A upstream connection is already active: " + snapshot.targetLabel()));
        }

        reconnectAttempts = 0;
        intentionalDisconnect = false;
        inputState = InputState.CLEAR;
        cancelAllScheduledActions();
        return startClient(request, AutomationState.CONNECTING, "Starting offline-mode upstream login.");
    }

    private ProxyResult<AutomationSnapshot> startClient(
            UpstreamConnectRequest request,
            AutomationState state,
            String message) {
        UpstreamClient client = new McProtocolLibUpstreamClient(request.host(), request.port(), request.username());
        currentClient = client;
        currentRequest = request;
        snapshot = AutomationSnapshot.forRequest(state, request, false, message);

        try {
            client.connect(new ServiceClientListener(client, request));
            return ProxyResult.success(snapshot);
        } catch (Exception e) {
            currentClient = null;
            currentRequest = null;
            closeQuietly(client);
            failureReporter.report("Could not start upstream client.", e);
            if (state == AutomationState.RECONNECTING && canScheduleReconnect()) {
                scheduleReconnect(request, "start failed", e);
                return ProxyResult.success(snapshot);
            }
            snapshot = AutomationSnapshot.forRequest(
                    AutomationState.FAILED,
                    request,
                    false,
                    "Could not start upstream client.");
            return ProxyResult.failure(new ProxyError(
                    "automation_connect_start_failed",
                    "Could not start upstream client; check proxy logs for details."));
        }
    }

    public synchronized ProxyResult<AutomationSnapshot> disconnect() {
        if (currentClient == null) {
            if (snapshot.state() == AutomationState.FAILED) {
                snapshot = AutomationSnapshot.idle("Cleared failed upstream connection.");
                currentRequest = null;
                return ProxyResult.success(snapshot);
            }
            return ProxyResult.failure(new ProxyError(
                    "automation_connection_missing",
                    "No upstream connection is active."));
        }

        UpstreamClient client = currentClient;
        UpstreamConnectRequest request = currentRequest;
        intentionalDisconnect = true;
        cancelAllScheduledActions();
        snapshot = AutomationSnapshot.forRequest(
                AutomationState.DISCONNECTING,
                request,
                false,
                "Disconnect requested by operator.");
        try {
            client.disconnect("Operator requested disconnect");
            return ProxyResult.success(snapshot);
        } catch (RuntimeException e) {
            currentClient = null;
            currentRequest = null;
            closeQuietly(client);
            snapshot = AutomationSnapshot.forRequest(
                    AutomationState.FAILED,
                    request,
                    false,
                    "Could not disconnect upstream client cleanly.");
            failureReporter.report("Could not disconnect upstream client.", e);
            return ProxyResult.failure(new ProxyError(
                    "automation_disconnect_failed",
                    "Could not disconnect upstream client cleanly; check proxy logs for details."));
        }
    }

    public synchronized ProxyResult<Void> lookNorth() {
        return look(180.0f, 0.0f);
    }

    public synchronized ProxyResult<Void> look(float yaw, float pitch) {
        if (currentClient == null || snapshot.state() != AutomationState.CONNECTED || !snapshot.playReady()) {
            return ProxyResult.failure(new ProxyError(
                    "automation_not_play_ready",
                    "Upstream client is not in play state yet."));
        }

        try {
            return currentClient.look(yaw, pitch);
        } catch (RuntimeException e) {
            failureReporter.report("Could not send look action.", e);
            return ProxyResult.failure(new ProxyError(
                    "automation_action_failed",
                    "Could not send look action; check proxy logs for details."));
        }
    }

    public synchronized ProxyResult<Void> turn(float yawDelta, float pitchDelta) {
        if (currentClient == null || snapshot.state() != AutomationState.CONNECTED || !snapshot.playReady()) {
            return ProxyResult.failure(new ProxyError(
                    "automation_not_play_ready",
                    "Upstream client is not in play state yet."));
        }
        return currentClient.turn(yawDelta, pitchDelta);
    }

    public synchronized ProxyResult<Void> selectHotbar(int slotOneBased) {
        if (currentClient == null || snapshot.state() != AutomationState.CONNECTED || !snapshot.playReady()) {
            return ProxyResult.failure(new ProxyError(
                    "automation_not_play_ready",
                    "Upstream client is not in play state yet."));
        }
        return currentClient.selectHotbar(slotOneBased);
    }

    public synchronized ProxyResult<Void> move(String direction) {
        inputState = inputState.withMovement(direction);
        return sendInputState();
    }

    public synchronized ProxyResult<Void> stopActions() {
        cancelAllScheduledActions();
        inputState = InputState.CLEAR;
        if (currentClient == null || snapshot.state() != AutomationState.CONNECTED || !snapshot.playReady()) {
            return ProxyResult.success();
        }
        return sendInputState();
    }

    public synchronized ProxyResult<Void> jumpOnce() {
        cancelScheduledAction(ScheduledAction.JUMP);
        return jumpOnceInternal();
    }

    public synchronized ProxyResult<Void> jumpInterval(int intervalTicks) {
        return runActionMode(ScheduledAction.JUMP, ActionMode.INTERVAL, intervalTicks);
    }

    private ProxyResult<Void> jumpOnceInternal() {
        InputState beforeJump = inputState;
        inputState = inputState.withJump(true);
        ProxyResult<Void> jump = sendInputState();
        inputState = beforeJump.withJump(false);
        ProxyResult<Void> release = sendInputState();
        return jump.isSuccess() ? release : jump;
    }

    public synchronized ProxyResult<Void> setJump(boolean enabled) {
        cancelScheduledAction(ScheduledAction.JUMP);
        inputState = inputState.withJump(enabled);
        return sendInputState();
    }

    public synchronized ProxyResult<Void> setSneak(boolean enabled) {
        inputState = inputState.withShift(enabled);
        return sendInputState();
    }

    public synchronized ProxyResult<Void> attack(ActionMode mode, int intervalTicks) {
        return runActionMode(ScheduledAction.ATTACK, mode, intervalTicks);
    }

    public synchronized ProxyResult<Void> use(ActionMode mode, int intervalTicks) {
        return runActionMode(ScheduledAction.USE, mode, intervalTicks);
    }

    public synchronized ProxyResult<Void> dropSelectedItem(boolean stack, ActionMode mode, int intervalTicks) {
        return runActionMode(stack ? ScheduledAction.DROP_STACK : ScheduledAction.DROP, mode, intervalTicks);
    }

    public synchronized ProxyResult<Void> swapHands(ActionMode mode, int intervalTicks) {
        return runActionMode(ScheduledAction.SWAP_HANDS, mode, intervalTicks);
    }

    public synchronized ProxyResult<Void> dismount() {
        InputState beforeDismount = inputState;
        inputState = inputState.withShift(true);
        ProxyResult<Void> press = sendInputState();
        inputState = beforeDismount;
        ProxyResult<Void> release = sendInputState();
        return press.isSuccess() ? release : press;
    }

    private ProxyResult<Void> runActionMode(ScheduledAction action, ActionMode mode, int intervalTicks) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(mode, "mode");
        cancelScheduledAction(action);

        ProxyResult<Void> firstRun = runScheduledActionOnce(action);
        if (!firstRun.isSuccess() || mode == ActionMode.ONCE) {
            return firstRun;
        }

        int normalizedIntervalTicks = mode == ActionMode.CONTINUOUS ? 1 : intervalTicks;
        if (normalizedIntervalTicks < 1) {
            return ProxyResult.failure(new ProxyError(
                    "automation_invalid_interval",
                    "Interval ticks must be 1 or greater."));
        }

        long periodMillis = normalizedIntervalTicks * MINECRAFT_TICK_MILLIS;
        ScheduledFuture<?> future = reconnectExecutor.scheduleAtFixedRate(
                () -> runScheduledActionTick(action),
                periodMillis,
                periodMillis,
                TimeUnit.MILLISECONDS);
        scheduledActions.put(action, future);
        return ProxyResult.success();
    }

    private void runScheduledActionTick(ScheduledAction action) {
        synchronized (this) {
            if (shuttingDown || intentionalDisconnect || snapshot.state() == AutomationState.DISCONNECTING) {
                cancelScheduledAction(action);
                return;
            }
            if (currentClient == null || snapshot.state() != AutomationState.CONNECTED || !snapshot.playReady()) {
                return;
            }

            ProxyResult<Void> result = runScheduledActionOnce(action);
            if (!result.isSuccess()) {
                cancelScheduledAction(action);
                failureReporter.report(
                        "Scheduled automation action failed: " + result.errorOrThrow().safeMessage(),
                        null);
            }
        }
    }

    private ProxyResult<Void> runScheduledActionOnce(ScheduledAction action) {
        if (currentClient == null || snapshot.state() != AutomationState.CONNECTED || !snapshot.playReady()) {
            return ProxyResult.failure(new ProxyError(
                    "automation_not_play_ready",
                    "Upstream client is not in play state yet."));
        }

        try {
            return switch (action) {
                case ATTACK -> currentClient.swingMainHand();
                case USE -> currentClient.useMainHand();
                case DROP -> currentClient.dropSelectedItem(false);
                case DROP_STACK -> currentClient.dropSelectedItem(true);
                case SWAP_HANDS -> currentClient.swapHands();
                case JUMP -> jumpOnceInternal();
            };
        } catch (RuntimeException e) {
            failureReporter.report("Could not send " + action.safeName() + " action.", e);
            return ProxyResult.failure(new ProxyError(
                    "automation_action_failed",
                    "Could not send " + action.safeName() + " action; check proxy logs for details."));
        }
    }

    public synchronized ProxyResult<Void> setSprint(boolean enabled) {
        inputState = inputState.withSprint(enabled);
        return sendInputState();
    }

    private ProxyResult<Void> sendInputState() {
        if (currentClient == null || snapshot.state() != AutomationState.CONNECTED || !snapshot.playReady()) {
            return ProxyResult.failure(new ProxyError(
                    "automation_not_play_ready",
                    "Upstream client is not in play state yet."));
        }
        try {
            return currentClient.sendInput(inputState);
        } catch (RuntimeException e) {
            failureReporter.report("Could not send input action.", e);
            return ProxyResult.failure(new ProxyError(
                    "automation_action_failed",
                    "Could not send input action; check proxy logs for details."));
        }
    }

    public synchronized AutomationSnapshot snapshot() {
        return snapshot;
    }

    public synchronized void shutdown() {
        cancelAllScheduledActions();
        if (currentClient == null) {
            reconnectExecutor.shutdownNow();
            return;
        }

        UpstreamClient client = currentClient;
        shuttingDown = true;
        intentionalDisconnect = true;
        currentClient = null;
        currentRequest = null;
        snapshot = AutomationSnapshot.idle("Proxy shutdown closed upstream connection.");
        try {
            client.disconnect("Proxy shutdown");
        } catch (RuntimeException e) {
            failureReporter.report("Could not disconnect upstream client during shutdown.", e);
        } finally {
            closeQuietly(client);
            reconnectExecutor.shutdownNow();
        }
    }

    private boolean canStartConnection() {
        return currentClient == null
                && (snapshot.state() == AutomationState.IDLE || snapshot.state() == AutomationState.FAILED);
    }

    private void onTransportConnected(UpstreamClient client, UpstreamConnectRequest request) {
        synchronized (this) {
            if (client != currentClient || snapshot.state() == AutomationState.DISCONNECTING) {
                return;
            }
            snapshot = AutomationSnapshot.forRequest(
                    AutomationState.CONNECTING,
                    request,
                    false,
                    "Transport connected; waiting for play state.");
        }
    }

    private void onPlayReady(UpstreamClient client, UpstreamConnectRequest request) {
        synchronized (this) {
            if (client != currentClient || snapshot.state() == AutomationState.DISCONNECTING) {
                return;
            }
            snapshot = AutomationSnapshot.forRequest(
                    AutomationState.CONNECTED,
                    request,
                    true,
                    "Offline-mode upstream client reached play state.");
        }
    }

    private void onDisconnected(UpstreamClient client, UpstreamConnectRequest request, String safeReason, Throwable cause) {
        synchronized (this) {
            if (client != currentClient) {
                return;
            }

            currentClient = null;
            currentRequest = null;
            if (snapshot.state() == AutomationState.DISCONNECTING) {
                cancelAllScheduledActions();
                snapshot = AutomationSnapshot.idle("Upstream disconnected.");
            } else if (!intentionalDisconnect && canScheduleReconnect()) {
                scheduleReconnect(request, safeReason, cause);
            } else {
                cancelAllScheduledActions();
                snapshot = AutomationSnapshot.forRequest(
                        AutomationState.FAILED,
                        request,
                        false,
                        "Upstream disconnected: " + safeReason);
            }
        }

        if (cause != null) {
            failureReporter.report("Upstream disconnected with an error.", cause);
        }
    }

    private void onClientError(UpstreamClient client, UpstreamConnectRequest request, String safeMessage, Throwable cause) {
        synchronized (this) {
            if (client != currentClient) {
                return;
            }
            currentClient = null;
            currentRequest = null;
            cancelAllScheduledActions();
            snapshot = AutomationSnapshot.forRequest(
                    AutomationState.FAILED,
                    request,
                    false,
                    safeMessage);
        }
        failureReporter.report(safeMessage, cause);
    }

    private boolean canScheduleReconnect() {
        return !shuttingDown
                && reconnectConfig.enabled()
                && ReconnectConfig.OFFLINE_CONTROLLED_AUTH_MODE.equals(reconnectConfig.authMode())
                && reconnectAttempts < reconnectConfig.maxAttempts();
    }

    private void scheduleReconnect(UpstreamConnectRequest request, String reason, Throwable cause) {
        reconnectAttempts++;
        snapshot = AutomationSnapshot.forRequest(
                AutomationState.RECONNECTING,
                request,
                false,
                "Auto reconnect " + reconnectAttempts + "/" + reconnectConfig.maxAttempts()
                        + " scheduled after disconnect: " + reason);
        reconnectExecutor.schedule(
                () -> runReconnectAttempt(request),
                reconnectConfig.delayMillis(),
                TimeUnit.MILLISECONDS);
        if (cause != null) {
            failureReporter.report("Upstream scheduled for auto reconnect.", cause);
        }
    }

    private void runReconnectAttempt(UpstreamConnectRequest request) {
        synchronized (this) {
            if (shuttingDown || snapshot.state() != AutomationState.RECONNECTING || currentClient != null) {
                return;
            }
            intentionalDisconnect = false;
            startClient(
                    request,
                    AutomationState.RECONNECTING,
                    "Auto reconnect attempt " + reconnectAttempts + "/" + reconnectConfig.maxAttempts() + ".");
        }
    }

    private void closeQuietly(UpstreamClient client) {
        try {
            client.close();
        } catch (RuntimeException e) {
            failureReporter.report("Could not close upstream client.", e);
        }
    }

    private void cancelScheduledAction(ScheduledAction action) {
        ScheduledFuture<?> future = scheduledActions.remove(action);
        if (future != null) {
            future.cancel(false);
        }
    }

    private void cancelAllScheduledActions() {
        for (ScheduledFuture<?> future : scheduledActions.values()) {
            future.cancel(false);
        }
        scheduledActions.clear();
    }

    @FunctionalInterface
    public interface FailureReporter {
        void report(String message, Throwable cause);
    }

    private enum ScheduledAction {
        ATTACK("attack"),
        USE("use"),
        DROP("drop"),
        DROP_STACK("dropStack"),
        SWAP_HANDS("swapHands"),
        JUMP("jump");

        private final String safeName;

        ScheduledAction(String safeName) {
            this.safeName = safeName;
        }

        String safeName() {
            return safeName;
        }
    }

    private final class ServiceClientListener implements UpstreamClient.Listener {
        private final UpstreamClient client;
        private final UpstreamConnectRequest request;

        private ServiceClientListener(UpstreamClient client, UpstreamConnectRequest request) {
            this.client = client;
            this.request = request;
        }

        @Override
        public void onTransportConnected() {
            AutomationService.this.onTransportConnected(client, request);
        }

        @Override
        public void onPlayReady() {
            AutomationService.this.onPlayReady(client, request);
        }

        @Override
        public void onDisconnected(String safeReason, Throwable cause) {
            AutomationService.this.onDisconnected(client, request, safeReason, cause);
        }

        @Override
        public void onError(String safeMessage, Throwable cause) {
            onClientError(client, request, safeMessage, cause);
        }
    }
}
