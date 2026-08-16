package com.fakeplayerproxy.world.entity;

import java.util.List;

import com.fakeplayerproxy.world.data.Block;
import com.fakeplayerproxy.world.data.Decoder;
import com.fakeplayerproxy.world.world.World;
import com.fakeplayerproxy.world.data.EntityTypeData;
import com.fakeplayerproxy.world.player.Player;
import lombok.Setter;
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
    private double scale;
    private double stepHeight;
    private double movementSpeed;
    private double movementEfficiency;
    private double waterMovementEfficiency;
    private double bounciness;
    @Setter
    private float health;
    private int levitationAmplifier = -1;
    private int slowFallingAmplifier = -1;
    private int dolphinsGraceAmplifier = -1;
    private int weavingAmplifier = -1;
    private boolean saddleEquipped;
    private boolean leatherBootsEquipped;
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
        scale = definition.scale();
        stepHeight = definition.stepHeight();
        movementSpeed = definition.movementSpeed();
        movementEfficiency = definition.movementEfficiency();
        waterMovementEfficiency = definition.waterMovementEfficiency();
        bounciness = definition.bounciness();
        health = definition.defaultHealth();
    }

    public boolean dead() {
        return health <= 0.0f;
    }

    @Override
    public double effectiveScale() {
        return scale;
    }

    @Override
    public double entityBounciness() {
        return bounciness;
    }

    @Override
    public double effectiveGravity() {
        return noGravity() ? 0.0 : gravity;
    }

    @Override
    public boolean canWalkOnPowderSnow() {
        return leatherBootsEquipped;
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
            scale = definition().scale();
            stepHeight = definition().stepHeight();
            movementSpeed = definition().movementSpeed();
            movementEfficiency = definition().movementEfficiency();
            waterMovementEfficiency = definition().waterMovementEfficiency();
            bounciness = definition().bounciness();
        }
        levitationAmplifier = -1;
        slowFallingAmplifier = -1;
        dolphinsGraceAmplifier = -1;
        weavingAmplifier = -1;
        saddleEquipped = false;
        leatherBootsEquipped = false;
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
                case SCALE -> scale = value;
                case STEP_HEIGHT -> stepHeight = value;
                case MOVEMENT_SPEED -> movementSpeed = value;
                case MOVEMENT_EFFICIENCY -> movementEfficiency = value;
                case WATER_MOVEMENT_EFFICIENCY -> waterMovementEfficiency = value;
                case BOUNCINESS -> bounciness = value;
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
                case BOOTS -> leatherBootsEquipped = present
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

    public double effectiveStepHeight() {
        return stepHeight;
    }

    @Override
    public Vector3d cobwebStuckMultiplier() {
        return weavingAmplifier >= 0
                ? Vector3d.from(0.5, 0.25, 0.5)
                : super.cobwebStuckMultiplier();
    }

    public double effectiveMovementSpeed() {
        return movementSpeed;
    }

    public void travel(World world, boolean flying) {
        baseTick(world);
        double originalVerticalMovement = velocity().getY();
        Vector3d requested = velocity();
        if (inWater()) {
            travelInWater(world, requested);
        } else if (inLava()) {
            travelInLava(world, requested);
        } else if (fallFlying() && gliderEquipped) {
            travelGliding(world, requested);
        } else {
            travelInAir(world, requested);
        }
        if (flying) {
            Vector3d moved = velocity();
            setVelocity(Vector3d.from(moved.getX(), originalVerticalMovement * 0.6, moved.getZ()));
        }
        if (!noPhysics()) {
            world.pushEntities(this);
        }
    }

    private void travelInAir(World world, Vector3d requested) {
        boolean climbable = world.hasBehavior(this, Block.Behavior.CLIMBABLE)
                || leatherBootsEquipped && world.hasBehavior(this, Block.Behavior.POWDER_SNOW);
        if (climbable) {
            requested = Vector3d.from(
                    Math.clamp(requested.getX(), -0.15, 0.15),
                    Math.max(requested.getY(), -0.15),
                    Math.clamp(requested.getZ(), -0.15, 0.15));
        }
        move(world, requested, stepHeight);
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

    private void travelInWater(World world, Vector3d requested) {
        double oldY = position().getY();
        boolean falling = requested.getY() <= 0.0;
        move(world, requested, stepHeight);
        Vector3d moved = velocity();
        double drag = dolphinsGraceAmplifier >= 0 ? 0.96 : 0.8 + 0.16 * waterMovementEfficiency;
        Vector3d adjusted = fluidFallingAdjusted(
                effectiveGravity(), falling,
                Vector3d.from(moved.getX() * drag, moved.getY() * 0.8, moved.getZ() * drag));
        setVelocity(jumpOutOfFluid(world, oldY, adjusted));
    }

    private void travelInLava(World world, Vector3d requested) {
        double oldY = position().getY();
        boolean falling = requested.getY() <= 0.0;
        move(world, requested, stepHeight);
        Vector3d moved = velocity();
        double gravity = effectiveGravity();
        Vector3d adjusted;
        if (lavaHeight() <= 0.4) {
            adjusted = fluidFallingAdjusted(gravity, falling,
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
        move(world, requested, stepHeight);
        Vector3d moved = velocity();
        setVelocity(Vector3d.from(moved.getX() * 0.99,
                moved.getY() * 0.98 - effectiveGravity() * 0.1,
                moved.getZ() * 0.99));
    }

    private static double calculateAttribute(Attribute attribute) {
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
            double gravity, boolean falling, Vector3d movement) {
        if (gravity == 0.0) {
            return movement;
        }
        double y = falling && Math.abs(movement.getY() - 0.005) >= 0.003
                && Math.abs(movement.getY() - gravity / 16.0) < 0.003
                ? -0.003 : movement.getY() - gravity / 16.0;
        return Vector3d.from(movement.getX(), y, movement.getZ());
    }

}
