package com.fakeplayerproxy.world.entity;

import java.util.List;

import com.fakeplayerproxy.world.data.Block;
import com.fakeplayerproxy.world.data.Decoder;
import com.fakeplayerproxy.world.world.World;
import com.fakeplayerproxy.world.data.EntityTypeData;
import com.fakeplayerproxy.world.player.Player;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.cloudburstmc.math.vector.Vector3d;
import org.geysermc.mcprotocollib.protocol.data.game.entity.Effect;
import org.geysermc.mcprotocollib.protocol.data.game.entity.attribute.Attribute;
import org.geysermc.mcprotocollib.protocol.data.game.entity.attribute.AttributeModifier;
import org.geysermc.mcprotocollib.protocol.data.game.entity.attribute.AttributeType;
import org.geysermc.mcprotocollib.protocol.data.game.entity.attribute.ModifierOperation;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.Equipment;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;

/** Position-relevant living state and the zero-input travel integration. */
public class LivingEntity extends Entity {
    private double gravity;
    private double effectiveScale;
    @Getter(AccessLevel.PUBLIC)
    @Accessors(fluent = true)
    private double effectiveStepHeight;
    @Getter(AccessLevel.PUBLIC)
    @Accessors(fluent = true)
    private double effectiveMovementSpeed;
    private double movementEfficiency;
    private double waterMovementEfficiency;
    private double entityBounciness;
    private double jumpStrength;
    @Setter
    private float health;
    private int levitationAmplifier = -1;
    private int slowFallingAmplifier = -1;
    private int dolphinsGraceAmplifier = -1;
    private int weavingAmplifier = -1;
    private int jumpBoostAmplifier = -1;
    private boolean saddleEquipped;
    private boolean canWalkOnPowderSnow;
    private boolean gliderEquipped;
    private boolean bodyEquipmentPresent;
    private boolean mainHandCarrotOnAStick;
    private boolean offHandCarrotOnAStick;
    private boolean mainHandWarpedFungusOnAStick;
    private boolean offHandWarpedFungusOnAStick;

    public LivingEntity(
            int id,
            EntityTypeData definition,
            Vector3d position,
            Vector3d velocity,
            float yaw,
            float pitch) {
        super(id, definition, position, velocity, yaw, pitch);
        gravity = definition.gravity();
        effectiveScale = definition.scale();
        effectiveStepHeight = definition.stepHeight();
        effectiveMovementSpeed = definition.movementSpeed();
        movementEfficiency = definition.movementEfficiency();
        waterMovementEfficiency = definition.waterMovementEfficiency();
        entityBounciness = definition.bounciness();
        jumpStrength = AttributeType.Builtin.JUMP_STRENGTH.getDef();
        health = definition.defaultHealth();
    }

    public boolean dead() {
        return health <= 0.0f;
    }

    @Override
    public double effectiveScale() {
        return effectiveScale;
    }

    @Override
    public double entityBounciness() {
        return entityBounciness;
    }

    @Override
    public boolean canWalkOnPowderSnow() {
        return canWalkOnPowderSnow;
    }

    @Override
    public double effectiveGravity() {
        return noGravity() ? 0.0 : gravity;
    }

    public void resetLivingState() {
        resetLivingState(false, false);
    }

    public void resetLivingState(boolean keepAttributeModifiers, boolean keepMetadata) {
        if (!keepMetadata) {
            health = definition().defaultHealth();
        }
        if (!keepAttributeModifiers) {
            gravity = definition().gravity();
            effectiveScale = definition().scale();
            effectiveStepHeight = definition().stepHeight();
            effectiveMovementSpeed = definition().movementSpeed();
            movementEfficiency = definition().movementEfficiency();
            waterMovementEfficiency = definition().waterMovementEfficiency();
            entityBounciness = definition().bounciness();
            jumpStrength = AttributeType.Builtin.JUMP_STRENGTH.getDef();
        }
        levitationAmplifier = -1;
        slowFallingAmplifier = -1;
        dolphinsGraceAmplifier = -1;
        weavingAmplifier = -1;
        jumpBoostAmplifier = -1;
        saddleEquipped = false;
        canWalkOnPowderSnow = false;
        gliderEquipped = false;
        bodyEquipmentPresent = false;
        mainHandCarrotOnAStick = false;
        offHandCarrotOnAStick = false;
        mainHandWarpedFungusOnAStick = false;
        offHandWarpedFungusOnAStick = false;
    }

    public void updateAttributes(List<Attribute> attributes) {
        for (Attribute attribute : attributes) {
            if (!(attribute.getType() instanceof AttributeType.Builtin builtin)) {
                continue;
            }
            double value = calculateAttribute(attribute);
            switch (builtin) {
                case GRAVITY -> gravity = value;
                case SCALE -> effectiveScale = value;
                case STEP_HEIGHT -> effectiveStepHeight = value;
                case MOVEMENT_SPEED -> effectiveMovementSpeed = value;
                case MOVEMENT_EFFICIENCY -> movementEfficiency = value;
                case WATER_MOVEMENT_EFFICIENCY -> waterMovementEfficiency = value;
                case BOUNCINESS -> entityBounciness = value;
                case JUMP_STRENGTH -> jumpStrength = value;
                default -> {
                    // Other attributes do not participate in retained position behavior.
                }
            }
        }
    }

    public void updateEffect(Effect effect, int amplifier) {
        switch (effect) {
            case LEVITATION -> levitationAmplifier = amplifier;
            case SLOW_FALLING -> slowFallingAmplifier = amplifier;
            case DOLPHINS_GRACE -> dolphinsGraceAmplifier = amplifier;
            case WEAVING -> weavingAmplifier = amplifier;
            case JUMP_BOOST -> jumpBoostAmplifier = amplifier;
            default -> {
                // Other effects are not read by the zero-input position path.
            }
        }
    }

    public void removeEffect(Effect effect) {
        updateEffect(effect, -1);
    }

    public void updateEquipment(Equipment[] equipment) {
        for (Equipment entry : equipment) {
            ItemStack item = entry.getItem();
            boolean present = item != null && item.getAmount() > 0;
            switch (entry.getSlot()) {
                case BOOTS -> canWalkOnPowderSnow = present
                        && item.getId() == Decoder.instance().leatherBootsItemId();
                case CHESTPLATE -> gliderEquipped = present && (item.getId()
                        == Decoder.instance().elytraItemId()
                        || item.getDataComponentsPatch() != null
                        && item.getDataComponentsPatch().contains(DataComponentTypes.GLIDER));
                case BODY -> bodyEquipmentPresent = present
                        && Decoder.instance().isHarnessItem(item.getId());
                case SADDLE -> saddleEquipped = present
                        && item.getId() == Decoder.instance().saddleItemId();
                case MAIN_HAND -> {
                    mainHandCarrotOnAStick = present
                            && item.getId() == Decoder.instance().carrotOnAStickItemId();
                    mainHandWarpedFungusOnAStick = present
                            && item.getId() == Decoder.instance().warpedFungusOnAStickItemId();
                }
                case OFF_HAND -> {
                    offHandCarrotOnAStick = present
                            && item.getId() == Decoder.instance().carrotOnAStickItemId();
                    offHandWarpedFungusOnAStick = present
                            && item.getId() == Decoder.instance().warpedFungusOnAStickItemId();
                }
                default -> {
                    // Other slots are not read by retained position behavior.
                }
            }
        }
    }

    public boolean canControl(EntityTypeData.MovementKind kind, Player player) {
        return switch (kind) {
            case BOAT -> true;
            case HORSE, CAMEL, NAUTILUS -> saddleEquipped;
            case PIG -> saddleEquipped
                    && holdsCarrotOnAStick(player);
            case STRIDER -> saddleEquipped
                    && holdsWarpedFungusOnAStick(player);
            case HAPPY_GHAST -> bodyEquipmentPresent;
            case SERVER, MINECART -> false;
        };
    }

    private static boolean holdsCarrotOnAStick(LivingEntity entity) {
        return entity.mainHandCarrotOnAStick || entity.offHandCarrotOnAStick;
    }

    private static boolean holdsWarpedFungusOnAStick(LivingEntity entity) {
        return entity.mainHandWarpedFungusOnAStick || entity.offHandWarpedFungusOnAStick;
    }

    @Override
    public Vector3d cobwebStuckMultiplier() {
        return weavingAmplifier >= 0
                ? Vector3d.from(0.5, 0.25, 0.5)
                : super.cobwebStuckMultiplier();
    }

    protected double effectiveJumpVelocity() {
        return jumpStrength
                + (jumpBoostAmplifier < 0 ? 0.0 : 0.1 * (jumpBoostAmplifier + 1));
    }

    @Override
    public void applyMetadata(int metadataId, Object value) {
        if (metadataId == definition().livingFlagsMetadataId() && value instanceof Byte flags) {
            applyLivingFlags(flags);
        } else {
            super.applyMetadata(metadataId, value);
        }
    }

    protected void applyLivingFlags(byte flags) {
    }

    public void travel(World world, boolean flying) {
        travel(world, flying, Vector3d.ZERO, false, false, false);
    }

    public void travel(
            World world,
            boolean flying,
            Vector3d input,
            boolean sprinting,
            boolean jumping,
            boolean descending) {
        baseTick(world);
        if (jumping && (inWater() || inLava())) {
            addVelocity(Vector3d.from(0.0, 0.04, 0.0));
        }
        if (descending && inWater()) {
            addVelocity(Vector3d.from(0.0, -0.04, 0.0));
        }
        double originalVerticalMovement = velocity().getY();
        Vector3d requested = velocity();
        if (inWater()) {
            travelInWater(world, input, sprinting);
        } else if (inLava()) {
            travelInLava(world, input, sprinting);
        } else if (fallFlying() && gliderEquipped) {
            travelGliding(world, requested);
        } else {
            travelInAir(world, input, sprinting, flying);
        }
        if (flying) {
            Vector3d moved = velocity();
            setVelocity(Vector3d.from(moved.getX(), originalVerticalMovement * 0.6, moved.getZ()));
        }
        if (!noPhysics()) {
            world.pushEntities(this);
        }
    }

    private void travelInAir(
            World world, Vector3d input, boolean sprinting, boolean flying) {
        double blockFriction = onGround() ? world.blockBelow(this).friction() : 1.0;
        double effectiveSpeed = effectiveMovementSpeed() * (sprinting ? 1.3 : 1.0);
        double acceleration = flying ? (sprinting ? 0.1 : 0.05)
                : onGround() ? effectiveSpeed
                * (blockFriction > 0.6
                ? 0.21600002 / (blockFriction * blockFriction * blockFriction) : 1.0)
                : sprinting ? 0.026 : 0.02;
        applyRelativeInput(input, acceleration);
        Vector3d requested = velocity();
        boolean climbable = world.hasBehavior(this, Block.Behavior.CLIMBABLE)
                || canWalkOnPowderSnow && world.hasBehavior(this, Block.Behavior.POWDER_SNOW);
        if (climbable) {
            requested = Vector3d.from(
                    Math.clamp(requested.getX(), -0.15, 0.15),
                    Math.max(requested.getY(), -0.15),
                    Math.clamp(requested.getZ(), -0.15, 0.15));
        }
        move(world, requested, effectiveStepHeight);
        Vector3d moved = velocity();
        if (climbable && horizontalCollision()) {
            moved = Vector3d.from(moved.getX(), 0.2, moved.getZ());
        }
        double vertical;
        if (levitationAmplifier >= 0) {
            vertical = moved.getY() + (0.05 * (levitationAmplifier + 1) - moved.getY()) * 0.2;
        } else {
            double effectiveGravity = effectiveGravity();
            double appliedGravity = slowFallingAmplifier >= 0 && moved.getY() <= 0.0
                    ? Math.min(effectiveGravity, 0.01) : effectiveGravity;
            vertical = moved.getY() - appliedGravity;
        }
        double friction = onGround()
                ? world.blockBelow(this).friction() * 0.91 + movementEfficiency * 0.09
                : 0.91;
        setVelocity(Vector3d.from(moved.getX() * friction, vertical * 0.98, moved.getZ() * friction));
    }

    private void travelInWater(World world, Vector3d input, boolean sprinting) {
        double waterEfficiency = onGround()
                ? waterMovementEfficiency : waterMovementEfficiency * 0.5;
        double drag = sprinting ? 0.9 : 0.8;
        double acceleration = 0.02;
        if (waterEfficiency > 0.0) {
            drag += (0.54600006 - drag) * waterEfficiency;
            acceleration += (effectiveMovementSpeed() * (sprinting ? 1.3 : 1.0) - acceleration)
                    * waterEfficiency;
        }
        applyRelativeInput(input, acceleration);
        Vector3d requested = velocity();
        double oldY = position().getY();
        boolean falling = requested.getY() <= 0.0;
        move(world, requested, effectiveStepHeight);
        Vector3d moved = velocity();
        if (dolphinsGraceAmplifier >= 0) {
            drag = 0.96;
        }
        Vector3d adjusted = fluidFallingAdjusted(
                effectiveGravity(), falling, sprinting,
                Vector3d.from(moved.getX() * drag, moved.getY() * 0.8, moved.getZ() * drag));
        setVelocity(jumpOutOfFluid(world, oldY, adjusted));
    }

    private void travelInLava(World world, Vector3d input, boolean sprinting) {
        applyRelativeInput(input, 0.02);
        Vector3d requested = velocity();
        double oldY = position().getY();
        boolean falling = requested.getY() <= 0.0;
        move(world, requested, effectiveStepHeight);
        Vector3d moved = velocity();
        double gravity = effectiveGravity();
        Vector3d adjusted;
        if (lavaHeight() <= 0.4) {
            adjusted = fluidFallingAdjusted(gravity, falling, sprinting,
                    Vector3d.from(moved.getX() * 0.5, moved.getY() * 0.8, moved.getZ() * 0.5));
        } else {
            adjusted = moved.mul(0.5);
        }
        if (gravity != 0.0) {
            adjusted = adjusted.sub(0.0, gravity / 4.0, 0.0);
        }
        setVelocity(jumpOutOfFluid(world, oldY, adjusted));
    }

    private void travelGliding(World world, Vector3d requested) {
        move(world, requested, effectiveStepHeight);
        Vector3d moved = velocity();
        setVelocity(Vector3d.from(moved.getX() * 0.99,
                moved.getY() * 0.98 - effectiveGravity() * 0.1,
                moved.getZ() * 0.99));
    }

    private void applyRelativeInput(Vector3d input, double speed) {
        double lengthSquared = input.lengthSquared();
        if (lengthSquared < 1.0E-7) {
            return;
        }
        Vector3d movement = (lengthSquared > 1.0 ? input.normalize() : input).mul(speed);
        float radians = yaw() * ((float) Math.PI / 180.0f);
        float sin = (float) Math.sin(radians);
        float cos = (float) Math.cos(radians);
        addVelocity(Vector3d.from(
                movement.getX() * cos - movement.getZ() * sin,
                movement.getY(),
                movement.getZ() * cos + movement.getX() * sin));
    }

    protected static double calculateAttribute(Attribute attribute) {
        double base = attribute.getValue();
        double result = base;
        for (AttributeModifier modifier : attribute.getModifiers()) {
            if (modifier.getOperation() == ModifierOperation.ADD) {
                result += modifier.getAmount();
            }
        }
        for (AttributeModifier modifier : attribute.getModifiers()) {
            if (modifier.getOperation() == ModifierOperation.ADD_MULTIPLIED_BASE) {
                result += base * modifier.getAmount();
            }
        }
        for (AttributeModifier modifier : attribute.getModifiers()) {
            if (modifier.getOperation() == ModifierOperation.ADD_MULTIPLIED_TOTAL) {
                result *= 1.0 + modifier.getAmount();
            }
        }
        return result;
    }

    private Vector3d jumpOutOfFluid(World world, double oldY, Vector3d movement) {
        double up = movement.getY() + 0.6 - position().getY() + oldY;
        return horizontalCollision()
                && world.canMoveWithoutCollision(this, Vector3d.from(movement.getX(), up, movement.getZ()))
                ? Vector3d.from(movement.getX(), 0.3, movement.getZ()) : movement;
    }

    private static Vector3d fluidFallingAdjusted(
            double gravity, boolean falling, boolean sprinting, Vector3d movement) {
        if (gravity == 0.0 || sprinting) {
            return movement;
        }
        double y = falling && Math.abs(movement.getY() - 0.005) >= 0.003
                && Math.abs(movement.getY() - gravity / 16.0) < 0.003
                ? -0.003 : movement.getY() - gravity / 16.0;
        return Vector3d.from(movement.getX(), y, movement.getZ());
    }

}
