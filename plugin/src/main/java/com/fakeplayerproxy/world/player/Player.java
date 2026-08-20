package com.fakeplayerproxy.world.player;

import com.fakeplayerproxy.automation.AutomationService;
import com.fakeplayerproxy.utils.Result;
import com.fakeplayerproxy.world.data.Decoder;
import com.fakeplayerproxy.world.data.Block;
import com.fakeplayerproxy.world.world.World;
import com.fakeplayerproxy.world.entity.Entity;
import com.fakeplayerproxy.world.entity.LivingEntity;
import com.fakeplayerproxy.world.phys.CollisionPhysics;
import com.fakeplayerproxy.world.phys.InteractionHit;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.backend.VelocityServerConnection;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import io.netty.channel.EventLoop;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
import org.geysermc.mcprotocollib.protocol.data.game.entity.Effect;
import org.geysermc.mcprotocollib.protocol.data.game.entity.attribute.Attribute;
import org.geysermc.mcprotocollib.protocol.data.game.entity.attribute.AttributeType;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import net.kyori.adventure.key.Key;
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
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundInteractPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundAttackPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket;
import org.jetbrains.annotations.NotNull;

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
    private final PlayerInventory inventory = new PlayerInventory();

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
    private double blockInteractionRange = AttributeType.Builtin.BLOCK_INTERACTION_RANGE.getDef();
    private double entityInteractionRange = AttributeType.Builtin.ENTITY_INTERACTION_RANGE.getDef();
    private double blockBreakSpeed = AttributeType.Builtin.BLOCK_BREAK_SPEED.getDef();
    private double miningEfficiency = AttributeType.Builtin.MINING_EFFICIENCY.getDef();
    private double submergedMiningSpeed = AttributeType.Builtin.SUBMERGED_MINING_SPEED.getDef();
    private int food = 20;
    // IDEA's unused warning is a false positive. The Minecraft backend owns this planned value.
    @SuppressWarnings("unused")
    private float saturation = 5.0f;
    // IDEA's unused warning is a false positive. The Minecraft backend owns this planned value.
    @SuppressWarnings("unused")
    private boolean invincible;
    // IDEA's unused warning is a false positive. The Minecraft backend owns this planned value.
    @SuppressWarnings("unused")
    private boolean infiniteMaterials;
    private boolean canFly;
    private final Map<Key, Integer> cooldowns = new HashMap<>();
    private Set<Key> enabledFeatures = Set.of(Key.key("minecraft", "vanilla"));
    private Hand activeUseHand;
    private Integer continuousAttackEntityId;
    private Vector3i destroyPosition;
    private Direction destroyFace;
    private ItemStack destroyItem;
    private float destroyProgress;
    private int destroyDelay;
    private int hasteAmplifier = -1;
    private int conduitPowerAmplifier = -1;
    private int miningFatigueAmplifier = -1;
    private int interactionSequence;
    private boolean actualSprint;
    private int delayedInputReleaseTicks;

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
                return new InputState(false, false, false, false, jump, false, false);
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

    public Player(@NotNull com.velocitypowered.api.proxy.Player velocityPlayer) {
        super(
                0,
                Decoder.instance().entity(
                        org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType.PLAYER),
                Vector3d.ZERO,
                Vector3d.ZERO,
                180.0f,
                0.0f);
        this.velocityPlayer = velocityPlayer;
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

    public VelocityServerConnection serverConnection() {
        if (!(velocityPlayer instanceof ConnectedPlayer connectedPlayer)) {
            return null;
        }
        return connectedPlayer.getConnectionInFlightOrConnectedServer();
    }

    public EventLoop eventLoop() {
        return frontendConnection().eventLoop();
    }

    public void setInputState(@NotNull InputState inputState) {
        this.inputState = inputState;
    }

    public Result<Void, String> stopActions(MinecraftConnection backend) {
        inactiveUse(backend);
        inactiveAttack(backend);
        setInputState(InputState.CLEAR);
        Result<Void, String> input = send(backend, inputPacket(inputState));
        return input instanceof Result.Failure<Void, String> ? input : setActualSprint(backend, false);
    }

    public Result<Void, String> look(MinecraftConnection backend, float yaw, float pitch) {
        setRotation(yaw, clampPitch(pitch));
        Result<Void, String> result = send(backend, new ServerboundMovePlayerRotPacket(
                onGround(), horizontalCollision(), yaw(), pitch()));
        if (result instanceof Result.Success<Void, String>) {
            clientRotation(yaw(), pitch(), onGround(), horizontalCollision());
        }
        return result;
    }

    public Result<Void, String> selectHotbar(MinecraftConnection backend, int slotOneBased) {
        Result<Void, String> result = send(backend, new ServerboundSetCarriedItemPacket(slotOneBased - 1));
        if (result instanceof Result.Success<Void, String>) {
            selectedSlot(slotOneBased - 1);
        }
        return result;
    }

    public void selectedSlot(int slot) {
        if (activeUseHand == Hand.MAIN_HAND && inventory.selectedSlot() != slot) {
            activeUseHand = null;
        }
        inventory.selectedSlot(slot);
    }

    public Result<Void, String> moveInput(MinecraftConnection backend, String direction) {
        setInputState(inputState.withMovement(direction));
        Result<Void, String> result = send(backend, inputPacket(inputState));
        if ((direction == null || direction.isBlank())
                && result instanceof Result.Success<Void, String>) {
            return setActualSprint(backend, false);
        }
        return result;
    }

    public Result<Void, String> setSneak(MinecraftConnection backend, boolean enabled) {
        setInputState(inputState.withShift(enabled));
        return send(backend, inputPacket(inputState));
    }

    public Result<Void, String> setSprint(MinecraftConnection backend, boolean enabled) {
        setInputState(inputState.withSprint(enabled));
        return send(backend, inputPacket(inputState));
    }

    public Result<Void, String> dismount(MinecraftConnection backend) {
        Result<Void, String> pressed = send(backend, inputPacket(inputState.withShift(true)));
        if (pressed instanceof Result.Success<Void, String>) {
            delayedInputReleaseTicks = 2;
        }
        return pressed;
    }

    public Result<Boolean, String> attack(MinecraftConnection backend, boolean continuous) {
        if (destroyDelay > 0) {
            destroyDelay--;
            return new Result.Success<>(false);
        }
        ActiveAttackRange range = activeAttackRange();
        var hit = world.raycast(
                this, range.minimum(), range.block(), range.entity(), range.hitboxMargin());
        if (hit.isEmpty()) {
            continuousAttackEntityId = null;
            return new Result.Success<>(false);
        }
        InteractionHit.BlockHit blockHit;
        switch (hit.get()) {
            case InteractionHit.EntityHit entityHit -> {
                if (continuous && Objects.equals(
                        continuousAttackEntityId, entityHit.entity().id())) {
                    return new Result.Success<>(true);
                }
                Result<Void, String> attack = send(
                        backend, new ServerboundAttackPacket(entityHit.entity().id()));
                if (attack instanceof Result.Failure<Void, String>(var error)) {
                    return new Result.Failure<>(error);
                }
                Result<Void, String> swing = send(
                        backend, new ServerboundSwingPacket(Hand.MAIN_HAND));
                if (swing instanceof Result.Failure<Void, String>(var error)) {
                    return new Result.Failure<>(error);
                }
                continuousAttackEntityId = continuous ? entityHit.entity().id() : null;
                return new Result.Success<>(true);
            }
            case InteractionHit.BlockHit value -> blockHit = value;
        }
        continuousAttackEntityId = null;
        if (!blockHit.insideWorldBorder()
                || gameMode == GameMode.SPECTATOR || gameMode == GameMode.ADVENTURE) {
            return new Result.Success<>(false);
        }
        Block block = world.block(blockHit.position());
        if (block == null || block.air() || block.destroySpeed() < 0.0f) {
            return new Result.Success<>(false);
        }
        if (gameMode == GameMode.CREATIVE) {
            Result<Void, String> result = send(backend, playerAction(
                    PlayerAction.START_DIGGING, blockHit.position(), blockHit.face(),
                    nextInteractionSequence()));
            if (result instanceof Result.Success<Void, String>) {
                destroyDelay = 5;
                return new Result.Success<>(true);
            }
            return new Result.Failure<>(((Result.Failure<Void, String>) result).error());
        }

        ItemStack selected = inventory.selected();
        if (destroyPosition != null
                && (!destroyPosition.equals(blockHit.position()) || !Objects.equals(destroyItem, selected))) {
            send(backend, playerAction(
                    PlayerAction.CANCEL_DIGGING, destroyPosition, destroyFace, nextInteractionSequence()));
            clearDestroyState();
        }
        if (destroyPosition == null) {
            Result<Void, String> start = send(backend, playerAction(
                    PlayerAction.START_DIGGING, blockHit.position(), blockHit.face(),
                    nextInteractionSequence()));
            if (start instanceof Result.Failure<Void, String>(var error)) {
                return new Result.Failure<>(error);
            }
            destroyPosition = blockHit.position();
            destroyFace = blockHit.face();
            destroyItem = selected;
        }
        var tool = selected == null
                ? it.unimi.dsi.fastutil.Pair.of(1.0f, false)
                : inventory.tool(selected, block, world);
        double speed = tool.left();
        if (speed > 1.0) {
            speed += miningEfficiency;
        }
        int digAmplifier = Math.max(hasteAmplifier, conduitPowerAmplifier);
        if (digAmplifier >= 0) {
            speed *= 1.0 + 0.2 * (digAmplifier + 1);
        }
        if (miningFatigueAmplifier >= 0) {
            speed *= switch (miningFatigueAmplifier) {
                case 0 -> 0.3;
                case 1 -> 0.09;
                case 2 -> 0.0027;
                default -> 0.00081;
            };
        }
        speed *= blockBreakSpeed;
        if (world.eyesInWater(this)) {
            speed *= submergedMiningSpeed;
        }
        if (!onGround()) {
            speed /= 5.0;
        }
        boolean correct = !block.requiresCorrectToolForDrops() || tool.right();
        destroyProgress += (float) (speed / block.destroySpeed() / (correct ? 30.0 : 100.0));
        if (destroyProgress < 1.0f) {
            return new Result.Success<>(false);
        }
        Result<Void, String> finish = send(backend, playerAction(
                PlayerAction.FINISH_DIGGING, destroyPosition, destroyFace, nextInteractionSequence()));
        clearDestroyState();
        destroyDelay = 5;
        return finish instanceof Result.Failure<Void, String>(var error)
                ? new Result.Failure<>(error) : new Result.Success<>(true);
    }

    public Result<Boolean, String> use(MinecraftConnection backend) {
        if (destroyPosition != null) {
            return new Result.Success<>(false);
        }
        if (activeUseHand != null) {
            return new Result.Success<>(true);
        }
        var range = activeAttackRange();
        var hit = world.raycast(
                this, range.minimum(), range.block(), range.entity(), range.hitboxMargin());
        for (Hand hand : Hand.values()) {
            ItemStack stack = hand == Hand.MAIN_HAND ? inventory.selected() : inventory.offhand();
            var fixed = stack == null ? null : inventory.fixed(stack);
            if (fixed != null && (!enabledFeatures.containsAll(fixed.requiredFeatures())
                    || cooldowns.getOrDefault(inventory.cooldownGroup(stack), 0) > 0)) {
                continue;
            }
            if (hit.isPresent()) {
                switch (hit.get()) {
                    case InteractionHit.EntityHit entityHit -> {
                        Result<Void, String> interact = send(
                                backend, new ServerboundInteractPacket(
                                entityHit.entity().id(), hand,
                                entityHit.hitPoint().sub(entityHit.entity().position()),
                                inputState.shift()));
                        if (interact instanceof Result.Failure<Void, String>(var error)) {
                            return new Result.Failure<>(error);
                        }
                    }
                    case InteractionHit.BlockHit blockHit -> {
                        if (!blockHit.insideWorldBorder()) {
                            return new Result.Success<>(false);
                        }
                        Vector3d cursor = blockHit.hitPoint().sub(Vector3d.from(
                                blockHit.position().getX(), blockHit.position().getY(),
                                blockHit.position().getZ()));
                        Result<Void, String> blockUse = send(
                                backend, new ServerboundUseItemOnPacket(
                                blockHit.position(), blockHit.face(), hand,
                                (float) cursor.getX(), (float) cursor.getY(),
                                (float) cursor.getZ(), blockHit.insideBlock(), false,
                                nextInteractionSequence()));
                        if (blockUse instanceof Result.Failure<Void, String>(var error)) {
                            return new Result.Failure<>(error);
                        }
                    }
                }
            }
            if (fixed == null) {
                continue;
            }
            Result<Void, String> sent = send(
                    backend, new ServerboundUseItemPacket(hand, interactionSequence, yaw(), pitch()));
            if (sent instanceof Result.Failure<Void, String>(var error)) {
                return new Result.Failure<>(error);
            }
            if (!fixed.baseUse()) {
                continue;
            }
            Float duration = inventory.consumeSeconds(stack);
            boolean consumable = duration != null
                    && (food < 20 || inventory.foodCanAlwaysEat(stack));
            boolean success = consumable || inventory.blocksAttacks(stack) || inventory.kineticWeapon(stack);
            if (success) {
                if (duration != null && duration > 0.0f) {
                    activeUseHand = hand;
                }
                return new Result.Success<>(true);
            }
        }
        return new Result.Success<>(false);
    }

    @Override
    protected void applyLivingFlags(byte flags) {
        activeUseHand = (flags & 1) == 0 ? null
                : (flags & 2) == 0 ? Hand.MAIN_HAND : Hand.OFF_HAND;
    }

    public void inactiveUse(MinecraftConnection backend) {
        if (activeUseHand != null) {
            send(backend, playerAction(PlayerAction.RELEASE_USE_ITEM));
            activeUseHand = null;
        }
    }

    public void inactiveAttack(MinecraftConnection backend) {
        if (destroyPosition != null) {
            send(backend, playerAction(
                    PlayerAction.CANCEL_DIGGING, destroyPosition, destroyFace, nextInteractionSequence()));
        }
        clearDestroyState();
        continuousAttackEntityId = null;
    }

    public Result<Void, String> jump(MinecraftConnection backend) {
        if (onGround()) {
            setInputState(inputState.withJump(true));
            return send(backend, inputPacket(inputState));
        }
        if (!world.hasBehavior(this, com.fakeplayerproxy.world.data.Block.Behavior.CLIMBABLE)) {
            return send(backend, new ServerboundPlayerCommandPacket(id(), PlayerState.START_ELYTRA_FLYING));
        }
        return new Result.Success<>(null);
    }

    public void inactiveJump(MinecraftConnection backend) {
        if (!inputState.jump()) {
            return;
        }
        setInputState(inputState.withJump(false));
        send(backend, inputPacket(inputState));
    }

    public Result<Void, String> mount(MinecraftConnection backend, Vector3d target, boolean coordinate) {
        Entity candidate = world.mountCandidate(this, target, coordinate).orElse(null);
        if (candidate == null) {
            return new Result.Failure<>("fakeplayerproxy.command.no_rideable");
        }
        Vector3d eye = position().add(0.0, eyeHeight(), 0.0);
        Vector3d hit = candidate.boundingBox().closestPoint(eye);
        if (coordinate && hit.distanceSquared(eye) >= entityInteractionRange * entityInteractionRange) {
            return new Result.Failure<>("fakeplayerproxy.command.rideable_out_of_range");
        }
        return send(backend, new ServerboundInteractPacket(
                candidate.id(), Hand.MAIN_HAND, hit.sub(candidate.position()), inputState.shift()));
    }

    public Result<Void, String> drop(MinecraftConnection backend, boolean stack) {
        return send(backend, playerAction(stack ? PlayerAction.DROP_ITEM_STACK : PlayerAction.DROP_ITEM));
    }

    public Result<Void, String> drop(
            MinecraftConnection backend, boolean stack, int slot) {
        return slot == -1 ? drop(backend, stack) : inventory.throwSlot(backend, slot, stack);
    }

    public Result<Void, String> swapHands(MinecraftConnection backend) {
        Result<Void, String> result = send(backend, playerAction(PlayerAction.SWAP_HANDS));
        if (result instanceof Result.Success<Void, String>) {
            inventory.swapHands();
        }
        return result;
    }

    public void prepareShadow(MinecraftConnection backend) {
        inventory.closeMenu(backend);
        setInputState(InputState.CLEAR);
        actualSprint = false;
        backend.sendPacket(inputPacket(InputState.CLEAR));
        backend.sendPacket(new ServerboundPlayerCommandPacket(id(), PlayerState.STOP_SPRINTING));
    }

    public void passiveTick() {
        world.tick();
        cooldowns.replaceAll((group, ticks) -> ticks - 1);
        cooldowns.values().removeIf(ticks -> ticks <= 0);
    }

    public void releaseDelayedInput(MinecraftConnection backend) {
        if (delayedInputReleaseTicks > 0 && --delayedInputReleaseTicks == 0) {
            send(backend, inputPacket(inputState));
        }
    }

    private static ServerboundPlayerActionPacket playerAction(PlayerAction action) {
        return new ServerboundPlayerActionPacket(action, Vector3i.ZERO, Direction.DOWN, 0);
    }

    private static ServerboundPlayerActionPacket playerAction(
            PlayerAction action, Vector3i position, Direction face, int sequence) {
        return new ServerboundPlayerActionPacket(action, position, face, sequence);
    }

    private int nextInteractionSequence() {
        return ++interactionSequence;
    }

    private ActiveAttackRange activeAttackRange() {
        ItemStack selected = inventory.selected();
        if (selected == null) {
            return new ActiveAttackRange(
                    0.0, blockInteractionRange, entityInteractionRange, 0.0);
        }
        var range = inventory.attackRange(selected);
        if (range == null) {
            return new ActiveAttackRange(
                    0.0, blockInteractionRange, entityInteractionRange, 0.0);
        }
        double minimum = gameMode == GameMode.CREATIVE
                ? range.creativeMinimum() : range.minimum();
        double maximum = gameMode == GameMode.CREATIVE
                ? range.creativeMaximum() : range.maximum();
        return new ActiveAttackRange(minimum, maximum, maximum, range.hitboxMargin());
    }

    private void clearDestroyState() {
        destroyPosition = null;
        destroyFace = null;
        destroyItem = null;
        destroyProgress = 0.0f;
    }

    private static ServerboundPlayerInputPacket inputPacket(InputState input) {
        return new ServerboundPlayerInputPacket(
                input.forward(), input.backward(), input.left(), input.right(),
                input.jump(), input.shift(), input.sprint());
    }

    private static Result<Void, String> send(MinecraftConnection backend, Packet packet) {
        if (!backend.getChannel().isActive()) {
            return new Result.Failure<>("fakeplayerproxy.command.automation_unavailable");
        }
        backend.sendPacket(packet);
        return new Result.Success<>(null);
    }

    @Override
    public boolean descending() {
        return inputState.shift() || super.descending();
    }

    public void initializeGame(int entityId, @NotNull PlayerSpawnInfo spawnInfo) {
        id(entityId);
        world.clearDimensionState();
        world.select(spawnInfo);
        world.registerPlayer(entityId);
        gameMode(spawnInfo.getGameMode());
        resetForSpawn();
        inventory.reset();
    }

    public void respawn(
            @NotNull PlayerSpawnInfo spawnInfo,
            boolean keepMetadata,
            boolean keepAttributeModifiers) {
        MinecraftConnection backend = backendConnection();
        if (backend != null) {
            inventory.closeMenu(backend);
        }
        inventory.reset();
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
        resetInteractionState();
    }

    public void resetForConfiguration() {
        world.clear();
        setVelocity(Vector3d.ZERO);
        setCollisionFlags(false, false);
        setSharedFlags((byte) 0);
        noGravity(false);
        resetLivingState();
        resetMovementBaseline();
        inventory.reset();
        resetInteractionState();
    }

    public void resetForClose() {
        setInputState(InputState.CLEAR);
        inventory.reset();
        resetInteractionState();
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

    public Result<Void, String> lookAt(MinecraftConnection backend, Vector3d target) {
        double dx = target.getX() - position().getX();
        double dy = target.getY() - position().getY() - eyeHeight();
        double dz = target.getZ() - position().getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        return look(backend,
                (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0),
                (float) -Math.toDegrees(Math.atan2(dy, horizontal)));
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
        this.canFly = canFly;
        this.flying = canFly && flying;
    }

    public void abilities(
            boolean invincible,
            boolean canFly,
            boolean flying,
            boolean infiniteMaterials) {
        this.invincible = invincible;
        this.infiniteMaterials = infiniteMaterials;
        abilities(canFly, flying);
    }

    public void food(int food, float saturation) {
        this.food = food;
        this.saturation = saturation;
    }

    public void cooldown(Key group, int ticks) {
        if (ticks <= 0) {
            cooldowns.remove(group);
        } else {
            cooldowns.put(group, ticks);
        }
    }

    @Override
    public void updateAttributes(List<Attribute> attributes) {
        super.updateAttributes(attributes);
        for (Attribute attribute : attributes) {
            if (!(attribute.getType() instanceof AttributeType.Builtin builtin)) {
                continue;
            }
            double value = calculateAttribute(attribute);
            // IDEA's duplication warning is a false positive. MCProtocolLib owns the attribute type set.
            //noinspection DuplicatedCode
            switch (builtin) {
                case BLOCK_INTERACTION_RANGE -> blockInteractionRange = value;
                case ENTITY_INTERACTION_RANGE -> entityInteractionRange = value;
                case BLOCK_BREAK_SPEED -> blockBreakSpeed = value;
                case MINING_EFFICIENCY -> miningEfficiency = value;
                case SUBMERGED_MINING_SPEED -> submergedMiningSpeed = value;
                default -> {
                }
            }
        }
    }

    @Override
    public void updateEffect(Effect effect, int amplifier) {
        super.updateEffect(effect, amplifier);
        switch (effect) {
            case HASTE -> hasteAmplifier = amplifier;
            case CONDUIT_POWER -> conduitPowerAmplifier = amplifier;
            case MINING_FATIGUE -> miningFatigueAmplifier = amplifier;
            default -> {
            }
        }
    }

    public void enabledFeatures(Key[] features) {
        enabledFeatures = Set.copyOf(java.util.Arrays.asList(features));
    }

    public void gameMode(@NotNull GameMode gameMode) {
        this.gameMode = gameMode;
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
        if (!movementEnabled) {
            return;
        }
        if (vehicle() == null) {
            if (gameMode == GameMode.SPECTATOR) {
                setCollisionFlags(false, false);
            }
            adaptPose();
            Vector3d input = applyMovementInput(backend);
            travel(world, flying || gameMode == GameMode.SPECTATOR,
                    input, actualSprint, inputState.jump(), inputState.shift());
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

    private Vector3d applyMovementInput(MinecraftConnection backend) {
        boolean effectiveSprint = inputState.sprint()
                && inputState.forward() && !inputState.backward()
                && (food > 6 || canFly || flying)
                && !horizontalCollision();
        setActualSprint(backend, effectiveSprint);

        double forward = (inputState.forward() ? 1.0 : 0.0)
                - (inputState.backward() ? 1.0 : 0.0);
        double strafe = (inputState.left() ? 1.0 : 0.0)
                - (inputState.right() ? 1.0 : 0.0);
        forward *= 0.98;
        strafe *= 0.98;
        if (inputState.shift()) {
            forward *= 0.3;
            strafe *= 0.3;
        }
        double length = Math.sqrt(forward * forward + strafe * strafe);
        if (length > 0.0) {
            double directionForward = forward / length;
            double directionStrafe = strafe / length;
            double tan = Math.min(Math.abs(directionForward), Math.abs(directionStrafe))
                    / Math.max(Math.abs(directionForward), Math.abs(directionStrafe));
            double modifiedLength = Math.min(length * Math.sqrt(1.0 + tan * tan), 1.0);
            forward = directionForward * modifiedLength;
            strafe = directionStrafe * modifiedLength;
        }
        if (inputState.jump() && onGround()) {
            Vector3d current = velocity();
            setVelocity(Vector3d.from(current.getX(), effectiveJumpVelocity(), current.getZ()));
            if (actualSprint) {
                double radians = Math.toRadians(yaw());
                addVelocity(Vector3d.from(-Math.sin(radians) * 0.2, 0.0,
                        Math.cos(radians) * 0.2));
            }
        }
        return Vector3d.from(strafe, 0.0, forward);
    }

    private Result<Void, String> setActualSprint(MinecraftConnection backend, boolean sprint) {
        if (actualSprint == sprint) {
            return new Result.Success<>(null);
        }
        Result<Void, String> result = send(backend, new ServerboundPlayerCommandPacket(
                id(), sprint ? PlayerState.START_SPRINTING : PlayerState.STOP_SPRINTING));
        if (result instanceof Result.Success<Void, String>) {
            actualSprint = sprint;
        }
        return result;
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
        resetLivingState();
        resetMovementBaseline();
        resetInteractionState();
    }

    private void resetMovementBaseline() {
        lastSentPosition = position();
        lastSentYaw = yaw();
        lastSentPitch = pitch();
        lastSentOnGround = onGround();
        lastSentHorizontalCollision = horizontalCollision();
        positionReminder = 0;
    }

    private void resetInteractionState() {
        food = 20;
        saturation = 5.0f;
        invincible = false;
        infiniteMaterials = false;
        canFly = false;
        flying = gameMode == GameMode.SPECTATOR;
        cooldowns.clear();
        enabledFeatures = Set.of(Key.key("minecraft", "vanilla"));
        activeUseHand = null;
        continuousAttackEntityId = null;
        clearDestroyState();
        destroyDelay = 0;
        blockInteractionRange = AttributeType.Builtin.BLOCK_INTERACTION_RANGE.getDef();
        entityInteractionRange = AttributeType.Builtin.ENTITY_INTERACTION_RANGE.getDef();
        blockBreakSpeed = AttributeType.Builtin.BLOCK_BREAK_SPEED.getDef();
        miningEfficiency = AttributeType.Builtin.MINING_EFFICIENCY.getDef();
        submergedMiningSpeed = AttributeType.Builtin.SUBMERGED_MINING_SPEED.getDef();
        hasteAmplifier = -1;
        conduitPowerAmplifier = -1;
        miningFatigueAmplifier = -1;
        interactionSequence = 0;
        actualSprint = false;
        delayedInputReleaseTicks = 0;
    }

    private void updateRotationBaseline() {
        lastSentYaw = yaw();
        lastSentPitch = pitch();
    }

    private record ActiveAttackRange(
            double minimum, double block, double entity, double hitboxMargin) {
    }

    public static float clampPitch(float value) {
        return Math.clamp(value, -90.0f, 90.0f);
    }
}
