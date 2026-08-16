package com.fakeplayerproxy.world.player;

import com.fakeplayerproxy.automation.AutomationService;
import com.fakeplayerproxy.util.ProxyError;
import com.fakeplayerproxy.util.ProxyResult;
import com.fakeplayerproxy.world.data.Decoder;
import com.fakeplayerproxy.world.world.World;
import com.fakeplayerproxy.world.entity.Entity;
import com.fakeplayerproxy.world.entity.LivingEntity;
import com.fakeplayerproxy.world.phys.CollisionPhysics;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.backend.VelocityServerConnection;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import io.netty.channel.EventLoop;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.List;
import java.util.Objects;

import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.entity.RotationOrigin;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.Pose;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerAction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerState;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerSpawnInfo;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundMoveVehiclePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundPaddleBoatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundPlayerInputPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerActionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSetCarriedItemPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSwingPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerStatusOnlyPacket;

/** Plugin local player. It wraps, but never implements, the exact Velocity Player. */
public final class Player extends LivingEntity {
    @Getter
    @Accessors(fluent = true)
    private final com.velocitypowered.api.proxy.Player velocityPlayer;
    @Getter
    @Accessors(fluent = true)
    private final World world;
    @Getter
    @Accessors(fluent = true)
    private final AutomationService automationService;

    @Getter
    @Accessors(fluent = true)
    private InputState inputState = InputState.CLEAR;
    private Vector3d lastSentPosition = Vector3d.ZERO;
    private float lastSentYaw = 180.0f;
    private float lastSentPitch;
    private int positionReminder;
    private boolean lastSentOnGround;
    private boolean lastSentHorizontalCollision;
    private boolean flying;
    private GameMode gameMode = GameMode.SURVIVAL;

    public record InputState(
            boolean forward,
            boolean backward,
            boolean left,
            boolean right,
            boolean jump,
            boolean shift,
            boolean sprint) {
        public static final InputState CLEAR =
                new InputState(false, false, false, false, false, false, false);

        public InputState withMovement(String direction) {
            if (direction == null || direction.isBlank()) {
                return CLEAR;
            }
            return switch (direction.toLowerCase()) {
                case "forward" -> new InputState(true, false, left, right, jump, shift, sprint);
                case "backward", "back" -> new InputState(false, true, left, right, jump, shift, sprint);
                case "left" -> new InputState(forward, backward, true, false, jump, shift, sprint);
                case "right" -> new InputState(forward, backward, false, true, jump, shift, sprint);
                default -> this;
            };
        }

        public InputState withJump(boolean value) {
            return new InputState(forward, backward, left, right, value, shift, sprint);
        }

        public InputState withShift(boolean value) {
            return new InputState(forward, backward, left, right, jump, value, !value && sprint);
        }

        public InputState withSprint(boolean value) {
            return new InputState(forward, backward, left, right, jump, !value && shift, value);
        }
    }

    public Player(com.velocitypowered.api.proxy.Player velocityPlayer) {
        super(
                0,
                Decoder.instance().entity(
                        org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType.PLAYER),
                Vector3d.ZERO,
                Vector3d.ZERO,
                180.0f,
                0.0f);
        this.velocityPlayer = Objects.requireNonNull(velocityPlayer, "velocityPlayer");
        world = new World(this);
        automationService = new AutomationService(this);
    }

    public MinecraftConnection frontendConnection() {
        if (!(velocityPlayer instanceof ConnectedPlayer connectedPlayer)) {
            throw new IllegalStateException("Player is not backed by a Velocity connection");
        }
        return connectedPlayer.getConnection();
    }

    public MinecraftConnection backendConnection() {
        if (!(velocityPlayer instanceof ConnectedPlayer connectedPlayer)) {
            return null;
        }
        VelocityServerConnection serverConnection =
                connectedPlayer.getConnectionInFlightOrConnectedServer();
        if (serverConnection == null) {
            return null;
        }
        MinecraftConnection connection = serverConnection.getConnection();
        return connection != null && connection.getChannel().isActive() ? connection : null;
    }

    public EventLoop eventLoop() {
        return frontendConnection().eventLoop();
    }

    public void setInputState(InputState inputState) {
        this.inputState = Objects.requireNonNull(inputState, "inputState");
    }

    public ProxyResult<Void> stopActions(MinecraftConnection backend) {
        setInputState(InputState.CLEAR);
        return send(backend, inputPacket(inputState));
    }

    public ProxyResult<Void> look(MinecraftConnection backend, float yaw, float pitch) {
        setRotation(yaw, clampPitch(pitch));
        ProxyResult<Void> result = send(backend, new ServerboundMovePlayerRotPacket(
                onGround(), horizontalCollision(), yaw(), pitch()));
        if (result.isSuccess()) {
            clientRotation(yaw(), pitch(), onGround(), horizontalCollision());
        }
        return result;
    }

    public ProxyResult<Void> selectHotbar(MinecraftConnection backend, int slotOneBased) {
        if (slotOneBased < 1 || slotOneBased > 9) {
            return unavailable("Hotbar slot must be 1 through 9.");
        }
        return send(backend, new ServerboundSetCarriedItemPacket(slotOneBased - 1));
    }

    public ProxyResult<Void> moveInput(MinecraftConnection backend, String direction) {
        setInputState(inputState.withMovement(direction));
        return send(backend, inputPacket(inputState));
    }

    public ProxyResult<Void> setJump(MinecraftConnection backend, boolean enabled) {
        setInputState(inputState.withJump(enabled));
        return send(backend, inputPacket(inputState));
    }

    public ProxyResult<Void> setSneak(MinecraftConnection backend, boolean enabled) {
        setInputState(inputState.withShift(enabled));
        return send(backend, inputPacket(inputState));
    }

    public ProxyResult<Void> setSprint(MinecraftConnection backend, boolean enabled) {
        setInputState(inputState.withSprint(enabled));
        ProxyResult<Void> input = send(backend, inputPacket(inputState));
        if (!input.isSuccess()) {
            return input;
        }
        PlayerState state = enabled ? PlayerState.START_SPRINTING : PlayerState.STOP_SPRINTING;
        return send(backend, new ServerboundPlayerCommandPacket(id(), state));
    }

    public ProxyResult<Void> pulseInput(MinecraftConnection backend, InputState pulse) {
        ProxyResult<Void> pressed = send(backend, inputPacket(pulse));
        return pressed.isSuccess() ? send(backend, inputPacket(inputState)) : pressed;
    }

    public ProxyResult<Void> attack(MinecraftConnection backend) {
        return send(backend, new ServerboundSwingPacket(Hand.MAIN_HAND));
    }

    public ProxyResult<Void> use(MinecraftConnection backend) {
        return send(backend, new ServerboundUseItemPacket(Hand.MAIN_HAND, 0, yaw(), pitch()));
    }

    public ProxyResult<Void> drop(MinecraftConnection backend, boolean stack) {
        return send(backend, playerAction(stack ? PlayerAction.DROP_ITEM_STACK : PlayerAction.DROP_ITEM));
    }

    public ProxyResult<Void> swapHands(MinecraftConnection backend) {
        return send(backend, playerAction(PlayerAction.SWAP_HANDS));
    }

    public void prepareShadow(MinecraftConnection backend) {
        backend.sendPacket(inputPacket(InputState.CLEAR));
        backend.sendPacket(new ServerboundPlayerCommandPacket(id(), PlayerState.STOP_SPRINTING));
    }

    private static ServerboundPlayerActionPacket playerAction(PlayerAction action) {
        return new ServerboundPlayerActionPacket(action, Vector3i.ZERO, Direction.DOWN, 0);
    }

    private static ServerboundPlayerInputPacket inputPacket(InputState input) {
        return new ServerboundPlayerInputPacket(
                input.forward(), input.backward(), input.left(), input.right(),
                input.jump(), input.shift(), input.sprint());
    }

    private static ProxyResult<Void> send(MinecraftConnection backend, Packet packet) {
        if (!backend.getChannel().isActive()) {
            return unavailable("Automation is not in an active game connection.");
        }
        backend.sendPacket(packet);
        return ProxyResult.success();
    }

    private static ProxyResult<Void> unavailable(String message) {
        return ProxyResult.failure(new ProxyError("automation_unavailable", message));
    }

    @Override
    public boolean descending() {
        return inputState.shift() || super.descending();
    }

    public void initializeGame(int entityId, PlayerSpawnInfo spawnInfo) {
        id(entityId);
        world.clearDimensionState();
        world.select(spawnInfo);
        world.registerPlayer(entityId);
        gameMode(spawnInfo.getGameMode());
        resetForSpawn();
    }

    public void respawn(PlayerSpawnInfo spawnInfo, boolean keepMetadata, boolean keepAttributeModifiers) {
        world.clearDimensionState();
        world.select(spawnInfo);
        world.registerPlayer(id());
        gameMode(spawnInfo.getGameMode());
        setVelocity(Vector3d.ZERO);
        setCollisionFlags(false, false);
        if (!keepMetadata) {
            resetMetadataDefaults();
        }
        setInputState(InputState.CLEAR);
        resetLivingState(keepAttributeModifiers, keepMetadata);
        resetMovementBaseline();
    }

    public void resetForConfiguration() {
        world.clear();
        setVelocity(Vector3d.ZERO);
        setCollisionFlags(false, false);
        setSharedFlags((byte) 0);
        noGravity(false);
        setInputState(InputState.CLEAR);
        resetLivingState();
        resetMovementBaseline();
    }

    public void applyServerPosition(
            Vector3d position,
            Vector3d movement,
            float yaw,
            float pitch,
            boolean onGround) {
        sync(position, movement, yaw, clampPitch(pitch));
        setCollisionFlags(onGround, false);
        resetMovementBaseline();
    }

    public void applyServerPosition(
            Vector3d packetPosition,
            Vector3d packetMovement,
            float packetYaw,
            float packetPitch,
            List<PositionElement> relatives) {
        Vector3d oldPosition = position();
        Vector3d oldVelocity = velocity();
        double x = relatives.contains(PositionElement.X)
                ? oldPosition.getX() + packetPosition.getX() : packetPosition.getX();
        double y = relatives.contains(PositionElement.Y)
                ? oldPosition.getY() + packetPosition.getY() : packetPosition.getY();
        double z = relatives.contains(PositionElement.Z)
                ? oldPosition.getZ() + packetPosition.getZ() : packetPosition.getZ();
        float yaw = relatives.contains(PositionElement.Y_ROT) ? yaw() + packetYaw : packetYaw;
        float pitch = clampPitch(relatives.contains(PositionElement.X_ROT) ? pitch() + packetPitch : packetPitch);
        Vector3d movement = velocityFromRelatives(
                oldVelocity, yaw(), pitch(), yaw, pitch, packetMovement, relatives);
        applyServerPosition(Vector3d.from(x, y, z), movement, yaw, pitch, false);
    }

    public void applyServerRotation(float yaw, boolean relativeYaw, float pitch, boolean relativePitch) {
        setRotation(relativeYaw ? yaw() + yaw : yaw,
                clampPitch(relativePitch ? pitch() + pitch : pitch));
    }

    public void lookAt(
            RotationOrigin origin,
            double x,
            double y,
            double z,
            int targetEntityId,
            RotationOrigin targetOrigin) {
        Entity target = world.entity(targetEntityId);
        if (target != null) {
            x = target.position().getX();
            y = target.position().getY()
                    + (targetOrigin == RotationOrigin.EYES ? target.eyeHeight() : 0.0);
            z = target.position().getZ();
        }
        double sourceY = position().getY() + (origin == RotationOrigin.EYES ? eyeHeight() : 0.0);
        double dx = x - position().getX();
        double dy = y - sourceY;
        double dz = z - position().getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        setRotation(
                (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0),
                clampPitch((float) -Math.toDegrees(Math.atan2(dy, horizontal))));
    }

    public void applyVehiclePosition(Vector3d position, float yaw, float pitch) {
        Entity root = this;
        while (root.vehicle() != null) {
            root = root.vehicle();
        }
        if (root != this) {
            root.sync(position, root.velocity(), yaw, pitch);
            root.placePassengerTree();
        }
    }

    public void clientPosition(double x, double y, double z, boolean onGround, boolean horizontalCollision) {
        setPosition(Vector3d.from(x, y, z));
        setCollisionFlags(onGround, horizontalCollision);
        lastSentPosition = position();
        lastSentOnGround = onGround;
        lastSentHorizontalCollision = horizontalCollision;
        positionReminder = 0;
    }

    public void clientRotation(float yaw, float pitch, boolean onGround, boolean horizontalCollision) {
        setRotation(yaw, clampPitch(pitch));
        setCollisionFlags(onGround, horizontalCollision);
        lastSentYaw = yaw();
        lastSentPitch = pitch();
        lastSentOnGround = onGround;
        lastSentHorizontalCollision = horizontalCollision;
    }

    public void clientStatus(boolean onGround, boolean horizontalCollision) {
        setCollisionFlags(onGround, horizontalCollision);
        lastSentOnGround = onGround;
        lastSentHorizontalCollision = horizontalCollision;
    }

    public void recordSentRotation() {
        lastSentYaw = yaw();
        lastSentPitch = pitch();
        lastSentOnGround = false;
        lastSentHorizontalCollision = false;
    }

    private static Vector3d velocityFromRelatives(
            Vector3d oldVelocity,
            float oldYaw,
            float oldPitch,
            float newYaw,
            float newPitch,
            Vector3d packetMovement,
            List<PositionElement> relatives) {
        Vector3d relativeVelocity = relatives.contains(PositionElement.ROTATE_DELTA)
                ? CollisionPhysics.rotateDelta(
                oldVelocity, oldYaw, oldPitch, newYaw, newPitch)
                : oldVelocity;
        return Vector3d.from(
                relatives.contains(PositionElement.DELTA_X)
                        ? relativeVelocity.getX() + packetMovement.getX() : packetMovement.getX(),
                relatives.contains(PositionElement.DELTA_Y)
                        ? relativeVelocity.getY() + packetMovement.getY() : packetMovement.getY(),
                relatives.contains(PositionElement.DELTA_Z)
                        ? relativeVelocity.getZ() + packetMovement.getZ() : packetMovement.getZ());
    }

    public void abilities(boolean canFly, boolean flying) {
        this.flying = canFly && flying;
    }

    public void gameMode(GameMode gameMode) {
        this.gameMode = Objects.requireNonNull(gameMode, "gameMode");
        if (gameMode == GameMode.SPECTATOR) {
            flying = true;
        } else if (gameMode != GameMode.CREATIVE) {
            flying = false;
        }
    }

    @Override
    public boolean noPhysics() {
        return gameMode == GameMode.SPECTATOR;
    }

    @Override
    public boolean isPushedByFluid() {
        return !flying && gameMode != GameMode.SPECTATOR;
    }

    public void tick(MinecraftConnection backend, boolean movementEnabled) {
        world.tick();
        if (!movementEnabled) {
            return;
        }
        if (vehicle() == null) {
            if (gameMode == GameMode.SPECTATOR) {
                setCollisionFlags(false, false);
            }
            adaptPose();
            travel(world, flying || gameMode == GameMode.SPECTATOR);
            sendMovement(backend);
            return;
        }

        Entity root = this;
        while (root.vehicle() != null) {
            root = root.vehicle();
        }
        boolean controlsRoot = root.isControlledBy(this);
        if (controlsRoot) {
            root.tickVehicle(world);
            root.placePassengerTree();
            if (root.isBoat()) {
                backend.sendPacket(new ServerboundPaddleBoatPacket(false, false));
            }
        } else {
            root.releaseLocalControl();
        }
        backend.sendPacket(new ServerboundMovePlayerRotPacket(
                onGround(), horizontalCollision(), yaw(), pitch()));
        updateRotationBaseline();
        if (controlsRoot) {
            backend.sendPacket(new ServerboundMoveVehiclePacket(
                    root.position(), root.yaw(), root.pitch(), root.onGround()));
        }
    }

    private void adaptPose() {
        Pose current = pose();
        if (current == Pose.SLEEPING || current == Pose.SPIN_ATTACK || current == Pose.DYING) {
            return;
        }
        Pose desired = fallFlying() ? Pose.FALL_FLYING
                : swimming() ? Pose.SWIMMING
                : inputState.shift() ? Pose.SNEAKING : Pose.STANDING;
        if (noPhysics() || world.canFit(this, desired)) {
            setPose(desired);
        } else if (world.canFit(this, Pose.SNEAKING)) {
            setPose(Pose.SNEAKING);
        } else if (world.canFit(this, Pose.SWIMMING)) {
            setPose(Pose.SWIMMING);
        }
    }

    private void sendMovement(MinecraftConnection backend) {
        positionReminder++;
        double x = position().getX() - lastSentPosition.getX();
        double y = position().getY() - lastSentPosition.getY();
        double z = position().getZ() - lastSentPosition.getZ();
        boolean moved = x * x + y * y + z * z > 4.0E-8 || positionReminder >= 20;
        boolean rotated = yaw() != lastSentYaw || pitch() != lastSentPitch;
        boolean flagsChanged = onGround() != lastSentOnGround
                || horizontalCollision() != lastSentHorizontalCollision;
        if (moved && rotated) {
            backend.sendPacket(new ServerboundMovePlayerPosRotPacket(
                    onGround(), horizontalCollision(), position().getX(), position().getY(), position().getZ(),
                    yaw(), pitch()));
        } else if (moved) {
            backend.sendPacket(new ServerboundMovePlayerPosPacket(
                    onGround(), horizontalCollision(), position().getX(), position().getY(), position().getZ()));
        } else if (rotated) {
            backend.sendPacket(new ServerboundMovePlayerRotPacket(
                    onGround(), horizontalCollision(), yaw(), pitch()));
        } else if (flagsChanged) {
            backend.sendPacket(new ServerboundMovePlayerStatusOnlyPacket(onGround(), horizontalCollision()));
        } else {
            return;
        }
        if (moved) {
            lastSentPosition = position();
            positionReminder = 0;
        }
        if (rotated) {
            updateRotationBaseline();
        }
        lastSentOnGround = onGround();
        lastSentHorizontalCollision = horizontalCollision();
    }

    private void resetForSpawn() {
        setPose(org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.Pose.STANDING);
        setVelocity(Vector3d.ZERO);
        setCollisionFlags(false, false);
        setSharedFlags((byte) 0);
        noGravity(false);
        setInputState(InputState.CLEAR);
        resetLivingState();
        resetMovementBaseline();
    }

    private void resetMovementBaseline() {
        lastSentPosition = position();
        lastSentYaw = yaw();
        lastSentPitch = pitch();
        lastSentOnGround = onGround();
        lastSentHorizontalCollision = horizontalCollision();
        positionReminder = 0;
    }

    private void updateRotationBaseline() {
        lastSentYaw = yaw();
        lastSentPitch = pitch();
    }

    public static float clampPitch(float value) {
        return Math.clamp(value, -90.0f, 90.0f);
    }
}
