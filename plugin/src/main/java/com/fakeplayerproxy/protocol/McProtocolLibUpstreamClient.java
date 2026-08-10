package com.fakeplayerproxy.protocol;

import com.fakeplayerproxy.automation.InputState;
import com.fakeplayerproxy.util.ProxyError;
import com.fakeplayerproxy.util.ProxyResult;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.ConnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.PacketErrorEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.network.session.ClientNetworkSession;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerAction;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundPlayerInputPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerActionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSetCarriedItemPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSwingPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemPacket;

public final class McProtocolLibUpstreamClient implements UpstreamClient {
    private final String host;
    private final int port;
    private final String username;
    private final AtomicBoolean playReady = new AtomicBoolean(false);

    private volatile Session session;
    private volatile float yaw = 180.0f;
    private volatile float pitch = 0.0f;

    public McProtocolLibUpstreamClient(String host, int port, String username) {
        this.host = Objects.requireNonNull(host, "host");
        this.port = port;
        this.username = Objects.requireNonNull(username, "username");
    }

    @Override
    public void connect(Listener listener) {
        Objects.requireNonNull(listener, "listener");
        MinecraftProtocol protocol = new MinecraftProtocol(username);
        ClientNetworkSession newSession = ClientNetworkSessionFactory.factory()
                .setAddress(host, port)
                .setProtocol(protocol)
                .create();
        newSession.addListener(new SessionAdapter() {
            @Override
            public void connected(ConnectedEvent event) {
                listener.onTransportConnected();
            }

            @Override
            public void packetReceived(Session session, org.geysermc.mcprotocollib.network.packet.Packet packet) {
                if (packet instanceof ClientboundLoginPacket && playReady.compareAndSet(false, true)) {
                    listener.onPlayReady();
                }
            }

            @Override
            public void packetError(PacketErrorEvent event) {
                listener.onError("Upstream protocol packet error.", event.getCause());
            }

            @Override
            public void disconnected(DisconnectedEvent event) {
                playReady.set(false);
                listener.onDisconnected(String.valueOf(event.getReason()), event.getCause());
            }
        });

        session = newSession;
        newSession.connect(false);
    }

    @Override
    public void disconnect(String reason) {
        Session activeSession = session;
        if (activeSession != null) {
            activeSession.disconnect(reason == null || reason.isBlank() ? "Proxy disconnect" : reason);
        }
    }

    @Override
    public ProxyResult<Void> look(float yaw, float pitch) {
        Session activeSession = session;
        if (activeSession == null || !playReady.get() || !activeSession.isConnected()) {
            return ProxyResult.failure(new ProxyError(
                    "protocol_not_play_ready",
                    "Protocol client has not reached play state."));
        }

        try {
            this.yaw = yaw;
            this.pitch = clampPitch(pitch);
            activeSession.send(new ServerboundMovePlayerRotPacket(true, false, this.yaw, this.pitch));
            return ProxyResult.success();
        } catch (RuntimeException e) {
            return ProxyResult.failure(new ProxyError(
                    "protocol_send_failed",
                    "Could not send look packet: " + e.getMessage()));
        }
    }

    @Override
    public ProxyResult<Void> turn(float yawDelta, float pitchDelta) {
        return look(this.yaw + yawDelta, this.pitch + pitchDelta);
    }

    @Override
    public ProxyResult<Void> selectHotbar(int slotOneBased) {
        if (slotOneBased < 1 || slotOneBased > 9) {
            return ProxyResult.failure(new ProxyError("protocol_invalid_hotbar", "Hotbar slot must be 1 through 9."));
        }
        Session activeSession = session;
        if (activeSession == null || !playReady.get() || !activeSession.isConnected()) {
            return ProxyResult.failure(new ProxyError(
                    "protocol_not_play_ready",
                    "Protocol client has not reached play state."));
        }

        try {
            activeSession.send(new ServerboundSetCarriedItemPacket(slotOneBased - 1));
            return ProxyResult.success();
        } catch (RuntimeException e) {
            return ProxyResult.failure(new ProxyError(
                    "protocol_send_failed",
                    "Could not send hotbar packet: " + e.getMessage()));
        }
    }

    @Override
    public ProxyResult<Void> sendInput(InputState inputState) {
        Objects.requireNonNull(inputState, "inputState");
        try {
            return sendPlayPacket(new ServerboundPlayerInputPacket(
                    inputState.forward(),
                    inputState.backward(),
                    inputState.left(),
                    inputState.right(),
                    inputState.jump(),
                    inputState.shift(),
                    inputState.sprint()), "input");
        } catch (RuntimeException e) {
            return ProxyResult.failure(new ProxyError(
                    "protocol_send_failed",
                    "Could not send input packet: " + e.getMessage()));
        }
    }

    @Override
    public ProxyResult<Void> swingMainHand() {
        return sendPlayPacket(new ServerboundSwingPacket(Hand.MAIN_HAND), "main-hand swing");
    }

    @Override
    public ProxyResult<Void> useMainHand() {
        return sendPlayPacket(new ServerboundUseItemPacket(Hand.MAIN_HAND, 0, yaw, pitch), "main-hand use");
    }

    @Override
    public ProxyResult<Void> dropSelectedItem(boolean stack) {
        PlayerAction action = stack ? PlayerAction.DROP_ITEM_STACK : PlayerAction.DROP_ITEM;
        return sendPlayPacket(
                new ServerboundPlayerActionPacket(action, Vector3i.ZERO, Direction.DOWN, 0),
                stack ? "drop selected item stack" : "drop selected item");
    }

    @Override
    public ProxyResult<Void> swapHands() {
        return sendPlayPacket(
                new ServerboundPlayerActionPacket(PlayerAction.SWAP_HANDS, Vector3i.ZERO, Direction.DOWN, 0),
                "swap hands");
    }

    @Override
    public void close() {
        disconnect("Upstream client closed");
    }

    private ProxyResult<Void> sendPlayPacket(Packet packet, String actionName) {
        Session activeSession = session;
        if (activeSession == null || !playReady.get() || !activeSession.isConnected()) {
            return ProxyResult.failure(new ProxyError(
                    "protocol_not_play_ready",
                    "Protocol client has not reached play state."));
        }

        try {
            activeSession.send(packet);
            return ProxyResult.success();
        } catch (RuntimeException e) {
            return ProxyResult.failure(new ProxyError(
                    "protocol_send_failed",
                    "Could not send " + actionName + " packet: " + e.getMessage()));
        }
    }

    private static float clampPitch(float value) {
        return Math.max(-90.0f, Math.min(90.0f, value));
    }
}
