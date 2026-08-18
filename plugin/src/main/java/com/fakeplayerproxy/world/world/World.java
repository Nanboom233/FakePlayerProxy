package com.fakeplayerproxy.world.world;

import com.fakeplayerproxy.world.data.Block;
import com.fakeplayerproxy.world.data.Decoder;
import com.fakeplayerproxy.world.data.EntityTypeData;
import com.fakeplayerproxy.world.entity.Entity;
import com.fakeplayerproxy.world.entity.LivingEntity;
import com.fakeplayerproxy.world.entity.Vehicle;
import com.fakeplayerproxy.world.phys.AABB;
import com.fakeplayerproxy.world.phys.FluidPhysics;
import com.fakeplayerproxy.world.phys.PistonPhysics;
import com.fakeplayerproxy.world.phys.InteractionHit;
import com.fakeplayerproxy.world.player.Player;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.Pair;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import net.kyori.adventure.key.Key;
import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftTypes;
import org.geysermc.mcprotocollib.protocol.data.game.RegistryEntry;
import org.geysermc.mcprotocollib.protocol.data.game.chunk.ChunkSection;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerSpawnInfo;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.Pose;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockChangeEntry;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockEntityInfo;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockEntityType;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.value.PistonValueType;
import org.jetbrains.annotations.NotNull;

/** Client world view owned by exactly one Plugin player and its connection EventLoop. */
public final class World {
    private static final Key DIMENSION_TYPE_REGISTRY = Key.key("minecraft", "dimension_type");
    private static final Key BIOME_REGISTRY = Key.key("minecraft", "worldgen/biome");
    private static final Key BLOCK_REGISTRY = Key.key("minecraft", "block");
    private static final Key CLIMBABLE_TAG = Key.key("minecraft", "climbable");
    private static final Key OVERWORLD = Key.key("minecraft", "overworld");
    private static final Key NETHER = Key.key("minecraft", "the_nether");

    private final Player owner;
    private final Decoder minecraftData;
    private List<RegistryEntry> dimensionTypes = List.of();
    private final Map<Long, ChunkSection[]> chunks = new HashMap<>();
    private final Map<Integer, Entity> entities = new HashMap<>();
    private final Map<Vector3i, MovingPiston> movingPistons = new HashMap<>();
    private MovingPiston activePiston;
    private Set<Integer> climbableBlockIds = Set.of();
    private Map<Key, Set<Integer>> blockTags = Map.of();
    private boolean physicalTagsReceived;

    private Key dimension = OVERWORLD;
    private int selectedDimensionId = -1;
    private int minimumY;
    private int height;
    private int biomeRegistrySize;
    @Getter
    private boolean levelChunksLoadStarted;
    private double borderCenterX;
    private double borderCenterZ;
    private double borderSize = 5.9999968E7;
    private double borderTargetSize = borderSize;
    private long borderLerpRemainingMillis;
    private double serverMillisecondsPerTick = 50.0;
    private boolean frozen;
    private int tickingSteps;

    public World(@NotNull Player owner) {
        this.owner = owner;
        this.minecraftData = Decoder.instance();
    }

    public void registerPlayer(int entityId) {
        entities.values().removeIf(entity -> entity == owner);
        entities.put(entityId, owner);
    }

    public Entity addEntity(
            int id,
            EntityType type,
            Vector3d position,
            Vector3d movement,
            float yaw,
            float pitch) {
        EntityTypeData definition = minecraftData.entity(type);
        Entity entity = definition.living()
                ? new LivingEntity(id, definition, position, movement, yaw, pitch)
                : new Entity(id, definition, position, movement, yaw, pitch);
        Entity previous = entities.put(id, entity);
        if (previous != null && previous != owner) {
            detach(previous);
        }
        return entity;
    }

    public Entity entity(int id) {
        return entities.get(id);
    }

    public Optional<Entity> mountCandidate(Player player, Vector3d target, boolean coordinate) {
        AABB search = player.boundingBox().inflate(3.0, 1.0, 3.0);
        return entities.values().stream()
                .filter(entity -> entity != player && entity != player.vehicle())
                .filter(Entity::isCarpetRideable)
                .filter(entity -> coordinate || search.intersects(entity.boundingBox()))
                .min((left, right) -> {
                    double leftDistance = left.boundingBox().center().distanceSquared(target);
                    double rightDistance = right.boundingBox().center().distanceSquared(target);
                    int distance = Double.compare(leftDistance, rightDistance);
                    return distance != 0 ? distance : Integer.compare(left.id(), right.id());
                });
    }

    public Optional<InteractionHit> raycast(
            Player player,
            double minimumEntityRange,
            double blockRange,
            double entityRange,
            double hitboxMargin) {
        Vector3d eye = player.position().add(0.0, player.eyeHeight(), 0.0);
        double maximumRange = Math.max(blockRange, entityRange);
        double yaw = Math.toRadians(player.yaw());
        double pitch = Math.toRadians(player.pitch());
        Vector3d direction = Vector3d.from(
                -Math.sin(yaw) * Math.cos(pitch),
                -Math.sin(pitch),
                Math.cos(yaw) * Math.cos(pitch));
        Vector3d end = eye.add(direction.mul(maximumRange));
        Vector3d entityStart = eye.add(direction.mul(minimumEntityRange));

        InteractionHit.BlockHit blockHit = null;
        double blockDistance = maximumRange * maximumRange;
        for (int x = floor(Math.min(eye.getX(), end.getX())) - 1;
                x <= floor(Math.max(eye.getX(), end.getX())) + 1; x++) {
            for (int y = floor(Math.min(eye.getY(), end.getY())) - 1;
                    y <= floor(Math.max(eye.getY(), end.getY())) + 1; y++) {
                for (int z = floor(Math.min(eye.getZ(), end.getZ())) - 1;
                        z <= floor(Math.max(eye.getZ(), end.getZ())) + 1; z++) {
                    OptionalInt state = blockState(x, y, z);
                    if (state.isEmpty()) {
                        continue;
                    }
                    Block block = minecraftData.block(state.getAsInt());
                    for (AABB local : minecraftData.outlineShape(block.outlineShapeId())) {
                        AABB box = local.move(x, y, z);
                        var clipped = box.clip(eye, end);
                        if (clipped.isEmpty()) {
                            continue;
                        }
                        Vector3d point = clipped.get().left();
                        double distance = point.distanceSquared(eye);
                        if (distance < blockDistance) {
                            blockDistance = distance;
                            blockHit = new InteractionHit.BlockHit(
                                    Vector3i.from(x, y, z), clipped.get().right(), point,
                                    Math.sqrt(distance), box.contains(eye), insideWorldBorder(x, z));
                        }
                    }
                }
            }
        }

        InteractionHit.EntityHit entityHit = null;
        double entityDistance = blockHit == null ? maximumRange * maximumRange : blockDistance;
        Vector3d entityOffset = entityStart.sub(eye);
        AABB swept = player.boundingBox()
                .move(entityOffset.getX(), entityOffset.getY(), entityOffset.getZ())
                .expand(
                        direction.getX() * (maximumRange - minimumEntityRange),
                        direction.getY() * (maximumRange - minimumEntityRange),
                        direction.getZ() * (maximumRange - minimumEntityRange))
                .inflate(1.0);
        for (Entity entity : entities.values()) {
            if (entity == player || entity == player.vehicle() || !entity.isPickable()
                    || !swept.intersects(entity.boundingBox().inflate(entity.pickRadius() + hitboxMargin))) {
                continue;
            }
            AABB box = entity.boundingBox().inflate(entity.pickRadius() + hitboxMargin);
            Vector3d point;
            if (box.contains(entityStart)) {
                point = entityStart;
            } else {
                var clipped = box.clip(entityStart, end);
                if (clipped.isEmpty()) {
                    continue;
                }
                point = clipped.get().left();
            }
            double distance = point.distanceSquared(eye);
            if (distance < entityDistance) {
                entityDistance = distance;
                entityHit = new InteractionHit.EntityHit(entity, point, Math.sqrt(distance));
            }
        }
        if (entityHit != null && entityDistance < entityRange * entityRange) {
            return Optional.of(entityHit);
        }
        if (blockHit != null && blockDistance < blockRange * blockRange) {
            return Optional.of(blockHit);
        }
        return Optional.empty();
    }

    public boolean blockTagContains(Key tag, int blockId) {
        return blockTags.getOrDefault(tag, Set.of()).contains(blockId);
    }

    public boolean insideWorldBorder(int x, int z) {
        double half = borderSize / 2.0;
        return x + 1.0 > borderCenterX - half && x < borderCenterX + half
                && z + 1.0 > borderCenterZ - half && z < borderCenterZ + half;
    }

    public Block block(Vector3i position) {
        OptionalInt state = blockState(position.getX(), position.getY(), position.getZ());
        return state.isEmpty() ? null : minecraftData.block(state.getAsInt());
    }

    public boolean eyesInWater(Player player) {
        OptionalInt state = blockState(
                floor(player.position().getX()),
                floor(player.position().getY() + player.eyeHeight()),
                floor(player.position().getZ()));
        return state.isPresent() && minecraftData.block(state.getAsInt()).water();
    }

    public void removeEntities(int[] entityIds) {
        for (int id : entityIds) {
            Entity entity = entities.get(id);
            if (entity == null || entity == owner) {
                continue;
            }
            detach(entity);
            entities.remove(id, entity);
        }
    }

    public void setPassengers(int vehicleId, int[] passengerIds) {
        Entity vehicle = entities.get(vehicleId);
        if (vehicle == null) {
            return;
        }
        for (Entity oldPassenger : List.copyOf(vehicle.passengers())) {
            vehicle.detachPassenger(oldPassenger);
        }
        List<Entity> ordered = new ArrayList<>();
        for (int passengerId : passengerIds) {
            Entity passenger = entities.get(passengerId);
            if (passenger != null && passenger != vehicle
                    && ordered.stream().noneMatch(existing -> existing == passenger)) {
                ordered.add(passenger);
            }
        }
        for (Entity passenger : ordered) {
            if (wouldCreatePassengerCycle(vehicle, passenger)) {
                continue;
            }
            if (passenger.vehicle() != null) {
                passenger.vehicle().detachPassenger(passenger);
            }
            vehicle.attachPassenger(passenger);
        }
    }

    public void tick() {
        long elapsedMillis = Math.max(1L, Math.round(clientTickCadenceMillis()));
        tickBorder(elapsedMillis);
        boolean advanceServerState = !frozen || tickingSteps > 0;
        if (advanceServerState) {
            entities.values().forEach(Entity::resetPistonMovement);
            tickMovingPistons();
            for (Entity entity : List.copyOf(entities.values())) {
                if (entity != owner) {
                    entity.tickInterpolation();
                }
            }
        }
        for (Entity entity : List.copyOf(entities.values())) {
            if (entity.vehicle() == null) {
                entity.placePassengerTree();
            }
        }
        if (advanceServerState && frozen && tickingSteps > 0) {
            tickingSteps--;
        }
    }

    public List<AABB> collisions(Entity mover, AABB query) {
        List<AABB> boxes = new ArrayList<>();
        int minX = floor(query.minX());
        int maxX = floor(query.maxX());
        int minY = floor(query.minY());
        int maxY = floor(query.maxY());
        int minZ = floor(query.minZ());
        int maxZ = floor(query.maxZ());
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    OptionalInt state = blockState(x, y, z);
                    if (state.isEmpty()) {
                        continue;
                    }
                    Block block = minecraftData.block(state.getAsInt());
                    if (block.behavior() == Block.Behavior.SCAFFOLDING) {
                        addScaffoldingCollisions(mover, query, x, y, z, block, boxes);
                        continue;
                    }
                    if (block.behavior() == Block.Behavior.POWDER_SNOW
                            && (mover.fallDistance() > 2.5
                            || mover.canWalkOnPowderSnow()
                            && mover.boundingBox().minY() > y + 1.0 - 1.0E-5
                            && !mover.descending())) {
                        double height = mover.fallDistance() > 2.5 ? 0.9 : 1.0;
                        AABB blockBox = new AABB(x, y, z, x + 1.0, y + height, z + 1.0);
                        if (query.intersects(blockBox)) {
                            boxes.add(blockBox);
                        }
                    }
                    for (AABB local : minecraftData.shape(block.collisionShapeId())) {
                        AABB blockBox = local.move(x, y, z);
                        if (query.intersects(blockBox)) {
                            boxes.add(blockBox);
                        }
                    }
                }
            }
        }
        for (Entity entity : entities.values()) {
            if (entity != mover && entity != mover.vehicle()
                    && !mover.passengers().contains(entity)
                    && !mover.sharesVehicleRoot(entity) && entity.hasMovementCollision()) {
                AABB entityBox = entity.boundingBox();
                if (query.intersects(entityBox)) {
                    boxes.add(entityBox);
                }
            }
        }
        for (MovingPiston piston : movingPistons.values()) {
            if (piston != activePiston) {
                addMovingPistonCollisions(piston, piston.progress(), query, boxes);
            }
        }
        addBorderCollisions(query, boxes);
        return boxes;
    }

    private static void addScaffoldingCollisions(
            Entity mover,
            AABB query,
            int x,
            int y,
            int z,
            Block block,
            List<AABB> boxes) {
        double entityBottom = mover.boundingBox().minY();
        if (entityBottom > y + 1.0 - 1.0E-5 && !mover.descending()) {
            addIfIntersecting(query, boxes, new AABB(x, y + 0.875, z, x + 1.0, y + 1.0, z + 1.0));
            addIfIntersecting(query, boxes, new AABB(x, y, z, x + 0.125, y + 1.0, z + 0.125));
            addIfIntersecting(query, boxes, new AABB(x + 0.875, y, z, x + 1.0, y + 1.0, z + 0.125));
            addIfIntersecting(query, boxes, new AABB(x, y, z + 0.875, x + 0.125, y + 1.0, z + 1.0));
            addIfIntersecting(query, boxes, new AABB(x + 0.875, y, z + 0.875, x + 1.0, y + 1.0, z + 1.0));
            return;
        }
        int parameter = block.behaviorParameter();
        int distance = parameter >>> 1;
        boolean bottom = (parameter & 1) != 0;
        if (distance != 0 && bottom && entityBottom > y - 1.0E-5) {
            addIfIntersecting(query, boxes, new AABB(x, y, z, x + 1.0, y + 0.125, z + 1.0));
        }
    }

    private static void addIfIntersecting(AABB query, List<AABB> boxes, AABB candidate) {
        if (query.intersects(candidate)) {
            boxes.add(candidate);
        }
    }

    public FluidSample fluid(Entity entity, AABB box) {
        double entityY = box.minY();
        box = passengerFluidBox(entity, box);
        if (box == null) {
            return FluidSample.EMPTY;
        }
        int x0 = floor(box.minX());
        int y0 = floor(box.minY());
        int z0 = floor(box.minZ());
        int x1 = (int) Math.ceil(box.maxX()) - 1;
        int y1 = (int) Math.ceil(box.maxY()) - 1;
        int z1 = (int) Math.ceil(box.maxZ()) - 1;
        if (!fluidNeighborsLoaded(x0 - 1, z0 - 1, x1 + 1, z1 + 1)) {
            return FluidSample.EMPTY;
        }

        double waterHeight = 0.0;
        double lavaHeight = 0.0;
        Vector3d accumulatedWaterFlow = Vector3d.ZERO;
        Vector3d accumulatedLavaFlow = Vector3d.ZERO;
        int waterCount = 0;
        int lavaCount = 0;
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    OptionalInt state = blockState(x, y, z);
                    if (state.isEmpty()) {
                        return FluidSample.EMPTY;
                    }
                    Block current = minecraftData.block(state.getAsInt());
                    if (!current.water() && !current.lava()) {
                        continue;
                    }
                    double fluidTop = y + fluidHeightAt(x, y, z, current);
                    if (fluidTop < box.minY()) {
                        continue;
                    }
                    Vector3d flow = fluidFlowAt(x, y, z, current);
                    if (current.water()) {
                        waterHeight = Math.max(waterHeight, fluidTop - entityY);
                        if (waterHeight < 0.4) {
                            flow = flow.mul(waterHeight);
                        }
                        accumulatedWaterFlow = accumulatedWaterFlow.add(flow);
                        waterCount++;
                    } else {
                        lavaHeight = Math.max(lavaHeight, fluidTop - entityY);
                        if (lavaHeight < 0.4) {
                            flow = flow.mul(lavaHeight);
                        }
                        accumulatedLavaFlow = accumulatedLavaFlow.add(flow);
                        lavaCount++;
                    }
                }
            }
        }

        Vector3d waterFlow = FluidPhysics.current(
                accumulatedWaterFlow, waterCount, entity instanceof Player, 0.014, entity.velocity());
        double lavaScale = NETHER.equals(dimension)
                ? 0.007 : 0.0023333333333333335;
        Vector3d lavaFlow = FluidPhysics.current(
                accumulatedLavaFlow, lavaCount, entity instanceof Player, lavaScale,
                entity.velocity().add(waterFlow));
        return new FluidSample(waterHeight, lavaHeight, waterFlow.add(lavaFlow));
    }

    private static AABB passengerFluidBox(Entity entity, AABB box) {
        Entity vehicle = entity.vehicle();
        Vehicle state = vehicle == null ? null : vehicle.clientVehicle();
        if (vehicle == null || state == null || !vehicle.isBoat()) {
            return box;
        }
        if (!state.clipsPassengerFluid()) {
            return box;
        }
        AABB boatBox = vehicle.boundingBox();
        if (boatBox.maxY() >= box.maxY()) {
            return null;
        }
        return new AABB(box.minX(), Math.max(box.minY(), boatBox.maxY()), box.minZ(),
                box.maxX(), box.maxY(), box.maxZ());
    }

    private boolean fluidNeighborsLoaded(int x0, int z0, int x1, int z1) {
        for (int chunkX = x0 >> 4; chunkX <= x1 >> 4; chunkX++) {
            for (int chunkZ = z0 >> 4; chunkZ <= z1 >> 4; chunkZ++) {
                if (!chunks.containsKey(key(chunkX, chunkZ))) {
                    return false;
                }
            }
        }
        return true;
    }

    public boolean canMoveWithoutCollision(Entity entity, Vector3d movement) {
        AABB moved = entity.boundingBox().move(
                movement.getX(), movement.getY(), movement.getZ());
        return collisions(entity, moved).isEmpty();
    }

    public BoatEnvironment boatEnvironment(Entity boat) {
        AABB box = boat.boundingBox();
        double top = box.maxY() + 0.001;
        boolean underWater = false;
        for (int x = floor(box.minX()); x < Math.ceil(box.maxX()); x++) {
            for (int y = floor(box.maxY()); y < Math.ceil(top); y++) {
                for (int z = floor(box.minZ()); z < Math.ceil(box.maxZ()); z++) {
                    Block block = waterBlockAt(x, y, z);
                    if (block == null) {
                        continue;
                    }
                    double height = fluidHeightAtKnown(x, y, z, block);
                    if (height >= 0.0 && top < y + height) {
                        if (block.fluidAmount() != 8 || block.fluidFalling()) {
                            return new BoatEnvironment(
                                    Vehicle.BoatStatus.UNDER_FLOWING_WATER, box.maxY(), 0.0f);
                        }
                        underWater = true;
                    }
                }
            }
        }
        if (underWater) {
            return new BoatEnvironment(Vehicle.BoatStatus.UNDER_WATER, box.maxY(), 0.0f);
        }

        double waterLevel = -Double.MAX_VALUE;
        boolean inWater = false;
        for (int x = floor(box.minX()); x < Math.ceil(box.maxX()); x++) {
            for (int y = floor(box.minY()); y < Math.ceil(box.minY() + 0.001); y++) {
                for (int z = floor(box.minZ()); z < Math.ceil(box.maxZ()); z++) {
                    Block block = waterBlockAt(x, y, z);
                    if (block == null) {
                        continue;
                    }
                    double height = fluidHeightAtKnown(x, y, z, block);
                    if (height >= 0.0) {
                        double surface = y + height;
                        waterLevel = Math.max(waterLevel, surface);
                        inWater |= box.minY() < surface;
                    }
                }
            }
        }
        if (inWater) {
            return new BoatEnvironment(Vehicle.BoatStatus.IN_WATER, waterLevel, 0.0f);
        }

        float landFriction = boatGroundFriction(box);
        return landFriction > 0.0f
                ? new BoatEnvironment(Vehicle.BoatStatus.ON_LAND, waterLevel, landFriction)
                : new BoatEnvironment(Vehicle.BoatStatus.IN_AIR, waterLevel, 0.0f);
    }

    private Block waterBlockAt(int x, int y, int z) {
        OptionalInt state = blockState(x, y, z);
        if (state.isEmpty()) {
            return null;
        }
        Block block = minecraftData.block(state.getAsInt());
        return block.water() ? block : null;
    }

    private float boatGroundFriction(AABB boatBox) {
        AABB contact = new AABB(
                boatBox.minX(), boatBox.minY() - 0.001, boatBox.minZ(),
                boatBox.maxX(), boatBox.minY(), boatBox.maxZ());
        float friction = 0.0f;
        int count = 0;
        int x0 = floor(contact.minX()) - 1;
        int x1 = (int) Math.ceil(contact.maxX()) + 1;
        int y0 = floor(contact.minY()) - 1;
        int y1 = (int) Math.ceil(contact.maxY()) + 1;
        int z0 = floor(contact.minZ()) - 1;
        int z1 = (int) Math.ceil(contact.maxZ()) + 1;
        for (int x = x0; x < x1; x++) {
            for (int z = z0; z < z1; z++) {
                for (int y = y0; y < y1; y++) {
                    OptionalInt state = blockState(x, y, z);
                    if (state.isEmpty()) {
                        continue;
                    }
                    Block block = minecraftData.block(state.getAsInt());
                    if (block.stateKey().startsWith("minecraft:lily_pad")) {
                        continue;
                    }
                    for (AABB shape : minecraftData.shape(block.collisionShapeId())) {
                        if (contact.intersects(shape.move(x, y, z))) {
                            friction += block.friction();
                            count++;
                            break;
                        }
                    }
                }
            }
        }
        return count == 0 ? 0.0f : friction / count;
    }

    public double waterLevelAbove(Entity boat) {
        AABB box = boat.boundingBox();
        int minX = floor(box.minX());
        int maxX = (int) Math.ceil(box.maxX());
        int minY = floor(box.maxY());
        int maxY = (int) Math.ceil(box.maxY() - boat.velocity().getY());
        for (int y = minY; y < maxY; y++) {
            double blockHeight = 0.0;
            for (int x = minX; x < maxX; x++) {
                for (int z = floor(box.minZ()); z < Math.ceil(box.maxZ()); z++) {
                    OptionalInt state = blockState(x, y, z);
                    if (state.isEmpty()) {
                        continue;
                    }
                    Block block = minecraftData.block(state.getAsInt());
                    if (block.water()) {
                        blockHeight = Math.max(blockHeight, fluidHeightAtKnown(x, y, z, block));
                    }
                }
            }
            if (blockHeight < 1.0) {
                return y + blockHeight;
            }
        }
        return maxY + 1.0;
    }

    public boolean canFit(Entity entity, Pose pose) {
        AABB box = entity.boundingBox(pose).inflate(-1.0E-7);
        return collisions(entity, box).isEmpty();
    }

    public Block blockBelow(Entity entity) {
        OptionalInt state = blockState(
                floor(entity.position().getX()),
                floor(entity.boundingBox().minY() - 0.5000001),
                floor(entity.position().getZ()));
        return state.isEmpty() ? minecraftData.block(0) : minecraftData.block(state.getAsInt());
    }

    public List<BlockSample> blocksInside(Entity entity) {
        AABB box = entity.boundingBox();
        List<BlockSample> result = new ArrayList<>();
        for (int x = floor(box.minX() + 1.0E-7); x <= floor(box.maxX() - 1.0E-7); x++) {
            for (int z = floor(box.minZ() + 1.0E-7); z <= floor(box.maxZ() - 1.0E-7); z++) {
                for (int y = floor(box.minY() + 1.0E-7); y <= floor(box.maxY() - 1.0E-7); y++) {
                    OptionalInt state = blockState(x, y, z);
                    if (state.isEmpty()) {
                        continue;
                    }
                    Block block = minecraftData.block(state.getAsInt());
                    if (block.behavior() != Block.Behavior.NONE || isClimbable(block)) {
                        result.add(new BlockSample(x, y, z, block));
                    }
                }
            }
        }
        return result;
    }

    public boolean hasBehavior(Entity entity, Block.Behavior behavior) {
        return blocksInside(entity).stream().anyMatch(sample -> behavior == Block.Behavior.CLIMBABLE
                ? isClimbable(sample.block()) : sample.block().behavior() == behavior);
    }

    public void physicalTags(Map<Key, Map<Key, int[]>> tags) {
        Map<Key, int[]> blockTags = tags.get(BLOCK_REGISTRY);
        int[] climbable = blockTags == null ? null : blockTags.get(CLIMBABLE_TAG);
        if (blockTags == null) {
            this.blockTags = Map.of();
        } else {
            Map<Key, Set<Integer>> copied = new HashMap<>();
            blockTags.forEach((key, values) -> {
                Set<Integer> ids = new HashSet<>();
                for (int value : values) {
                    ids.add(value);
                }
                copied.put(key, Set.copyOf(ids));
            });
            this.blockTags = Map.copyOf(copied);
        }
        if (climbable == null) {
            climbableBlockIds = Set.of();
        } else {
            Set<Integer> ids = new HashSet<>();
            for (int id : climbable) {
                ids.add(id);
            }
            climbableBlockIds = Set.copyOf(ids);
        }
        physicalTagsReceived = true;
    }

    private boolean isClimbable(Block block) {
        return physicalTagsReceived ? climbableBlockIds.contains(block.blockId())
                : block.behavior() == Block.Behavior.CLIMBABLE
                || block.behavior() == Block.Behavior.SCAFFOLDING;
    }

    public float speedFactor(Entity entity) {
        OptionalInt state = blockState(
                floor(entity.position().getX()),
                floor(entity.position().getY()),
                floor(entity.position().getZ()));
        if (state.isEmpty()) {
            return 1.0f;
        }
        Block block = minecraftData.block(state.getAsInt());
        if (block.speedFactor() != 1.0f || block.water()
                || block.behavior() == Block.Behavior.BUBBLE_COLUMN) {
            return block.speedFactor();
        }
        return blockBelow(entity).speedFactor();
    }

    public boolean bubbleColumnHasOpenSurface(BlockSample sample) {
        OptionalInt aboveState = blockState(sample.x(), sample.y() + 1, sample.z());
        if (aboveState.isEmpty()) {
            return false;
        }
        Block above = minecraftData.block(aboveState.getAsInt());
        return above.air();
    }

    public void pushEntities(Entity source) {
        AABB query = source.boundingBox().inflate(0.2);
        for (Entity other : entities.values()) {
            if (other == source || other.noPhysics() || source.sharesVehicleRoot(other)
                    || !other.isPushable() || !query.intersects(other.boundingBox())) {
                continue;
            }
            double x = source.position().getX() - other.position().getX();
            double z = source.position().getZ() - other.position().getZ();
            double largest = Math.max(Math.abs(x), Math.abs(z));
            if (largest < 0.01) {
                continue;
            }
            double scale = 0.05 / largest;
            Vector3d push = Vector3d.from(x * scale, 0.0, z * scale);
            source.addVelocity(push);
        }
    }

    public void registry(Key key, List<RegistryEntry> entries) {
        if (DIMENSION_TYPE_REGISTRY.equals(key)) {
            dimensionTypes = List.copyOf(entries);
        } else if (BIOME_REGISTRY.equals(key)) {
            biomeRegistrySize = entries.size();
        }
    }

    public void select(PlayerSpawnInfo spawnInfo) {
        if (spawnInfo == null || spawnInfo.getWorldName() == null) {
            return;
        }
        Key nextDimension = spawnInfo.getWorldName();
        if (!nextDimension.equals(dimension)) {
            clearDimensionState();
        }
        dimension = nextDimension;
        levelChunksLoadStarted = false;
        int dimensionId = spawnInfo.getDimension();
        selectedDimensionId = dimensionId;
        if (dimensionId < 0 || dimensionId >= dimensionTypes.size()) {
            minimumY = 0;
            height = 0;
            return;
        }
        NbtMap data = dimensionTypes.get(dimensionId).getData();
        minimumY = data == null ? 0 : data.getInt("min_y", 0);
        height = data == null ? 0 : data.getInt("height", 0);
    }

    public void clearDimensionState() {
        chunks.clear();
        movingPistons.clear();
        for (Entity entity : List.copyOf(entities.values())) {
            if (entity != owner) {
                detach(entity);
            }
        }
        entities.values().removeIf(entity -> entity != owner);
        levelChunksLoadStarted = false;
    }

    public void clear() {
        clearDimensionState();
        dimensionTypes = List.of();
        climbableBlockIds = Set.of();
        blockTags = Map.of();
        physicalTagsReceived = false;
        dimension = OVERWORLD;
        selectedDimensionId = -1;
        minimumY = 0;
        height = 0;
        biomeRegistrySize = 0;
        borderCenterX = 0.0;
        borderCenterZ = 0.0;
        borderSize = 5.9999968E7;
        borderTargetSize = borderSize;
        borderLerpRemainingMillis = 0L;
        serverMillisecondsPerTick = 50.0;
        frozen = false;
        tickingSteps = 0;
    }

    public void levelChunksLoadStarted() {
        levelChunksLoadStarted = true;
    }

    public LevelChunkInstallResult decodeAndInstallChunk(
            int x, int z, byte[] data, BlockEntityInfo[] blockEntities) {
        if (dimensionTypes.isEmpty()) {
            return LevelChunkInstallResult.failure(
                    "the dimension type registry contains no entries");
        }
        if (biomeRegistrySize == 0) {
            return LevelChunkInstallResult.failure(
                    "the biome registry contains no entries");
        }
        if (selectedDimensionId < 0 || selectedDimensionId >= dimensionTypes.size()) {
            return LevelChunkInstallResult.failure(
                    "dimension ID " + selectedDimensionId + " is outside the registry range 0 through "
                            + (dimensionTypes.size() - 1));
        }
        RegistryEntry dimensionType = dimensionTypes.get(selectedDimensionId);
        NbtMap dimensionData = dimensionType.getData();
        if (dimensionData == null) {
            return LevelChunkInstallResult.failure(
                    "dimension type " + dimensionType.getId() + " at ID "
                            + selectedDimensionId + " has null data");
        }
        int count = height > 0 ? height / 16 : 0;
        if (height <= 0 || height % 16 != 0) {
            return LevelChunkInstallResult.failure(
                    "dimension height " + height + " produces section count " + count
                            + ". The height must be positive and divisible by 16");
        }
        ByteBuf buffer = Unpooled.wrappedBuffer(data);
        try {
            ChunkSection[] sections = new ChunkSection[count];
            int blockStateCount = minecraftData.blockStateCount();
            for (int index = 0; index < sections.length; index++) {
                try {
                    sections[index] = MinecraftTypes.readChunkSection(
                            buffer, blockStateCount, biomeRegistrySize);
                } catch (RuntimeException exception) {
                    return LevelChunkInstallResult.failure(
                            "the LevelChunk section decoder cannot decode section index "
                                    + index + " of " + count + ". The payload has " + data.length
                                    + " bytes, the block-state registry has " + blockStateCount
                                    + " entries, and the biome registry has " + biomeRegistrySize
                                    + " entries",
                            exception);
                }
            }
            if (buffer.isReadable()) {
                int unreadByteCount = buffer.readableBytes();
                String byteUnit = unreadByteCount == 1 ? " byte" : " bytes";
                return LevelChunkInstallResult.failure(
                        "the LevelChunk section payload contains " + unreadByteCount + byteUnit
                                + " after " + count + " decoded sections");
            }
            chunks.put(key(x, z), sections);
            movingPistons.keySet().removeIf(position -> position.getX() >> 4 == x
                    && position.getZ() >> 4 == z);
            for (BlockEntityInfo blockEntity : blockEntities) {
                blockEntity(Vector3i.from(
                        (x << 4) + blockEntity.getX(), blockEntity.getY(),
                        (z << 4) + blockEntity.getZ()), blockEntity.getType(), blockEntity.getNbt());
            }
            return LevelChunkInstallResult.success();
        } finally {
            buffer.release();
        }
    }

    public void forgetChunk(int x, int z) {
        chunks.remove(key(x, z));
        movingPistons.keySet().removeIf(position -> position.getX() >> 4 == x
                && position.getZ() >> 4 == z);
    }

    public void blockEntity(Vector3i position, BlockEntityType type, NbtMap nbt) {
        if (type != BlockEntityType.PISTON || nbt == null || nbt.isEmpty()) {
            movingPistons.remove(position);
            return;
        }
        int movedState = minecraftData.blockState(nbt.getCompound("blockState", NbtMap.EMPTY));
        int facing = nbt.getInt("facing", -1);
        if (movedState < 0 || facing < 0 || facing >= Direction.VALUES.length) {
            movingPistons.remove(position);
            return;
        }
        movingPistons.put(position, new MovingPiston(
                position, movedState, Direction.from(facing),
                nbt.getBoolean("extending", false), nbt.getBoolean("source", false),
                nbt.getFloat("progress", 0.0f)));
    }

    public Optional<String> blockEvent(
            Vector3i position, PistonValueType event, Direction direction, int blockId) {
        OptionalInt currentState = blockState(position.getX(), position.getY(), position.getZ());
        if (currentState.isEmpty()) {
            return pistonDiagnostic(position, event, direction,
                    "the piston base chunk is not loaded");
        }
        Block base = minecraftData.block(currentState.getAsInt());
        boolean sticky = base.stateKey().startsWith("minecraft:sticky_piston[");
        boolean normal = base.stateKey().startsWith("minecraft:piston[");
        if ((!sticky && !normal) || base.blockId() != blockId) {
            return pistonDiagnostic(position, event, direction,
                    "expected block id " + blockId + " at the piston base but found "
                            + base.stateKey() + " with block id " + base.blockId());
        }
        String facing = direction.name().toLowerCase(java.util.Locale.ROOT);
        if (!base.stateKey().contains("facing=" + facing)) {
            return pistonDiagnostic(position, event, direction,
                    "the event direction does not match base state " + base.stateKey());
        }
        return switch (event) {
            case PUSHING -> extendPiston(position, direction, sticky, event);
            case PULLING -> retractPiston(position, direction, sticky, true, event);
            case CANCELLED_MID_PUSH -> retractPiston(position, direction, sticky, false, event);
        };
    }

    private Optional<String> extendPiston(
            Vector3i position, Direction direction, boolean sticky, PistonValueType event) {
        String facing = direction.name().toLowerCase(java.util.Locale.ROOT);
        String type = sticky ? "sticky" : "normal";
        int baseState = minecraftData.blockState((sticky ? "minecraft:sticky_piston" : "minecraft:piston")
                + "[extended=true,facing=" + facing + "]");
        int headState = minecraftData.blockState("minecraft:piston_head[facing=" + facing
                + ",short=false,type=" + type + "]");
        int movingState = minecraftData.blockState("minecraft:moving_piston[facing=" + facing
                + ",type=" + type + "]");
        int airState = minecraftData.blockState("minecraft:air");
        if (baseState < 0 || headState < 0 || movingState < 0 || airState < 0) {
            return pistonDiagnostic(position, event, direction,
                    "fixed 26.2 piston transition states are missing");
        }

        List<Pair<Vector3i, Integer>> moves = new ArrayList<>();
        Vector3i cursor = relative(position, direction, 1);
        for (int depth = 0; depth <= 12; depth++) {
            OptionalInt state = blockState(cursor.getX(), cursor.getY(), cursor.getZ());
            if (state.isEmpty()) {
                return pistonDiagnostic(position, event, direction,
                        "required pushed block state is unknown at " + cursor);
            }
            Block block = minecraftData.block(state.getAsInt());
            if (block.air()) {
                break;
            }
            if (depth == 12) {
                return pistonDiagnostic(position, event, direction,
                        "the event-time push line exceeds the fixed 12-block limit at " + cursor);
            }
            moves.add(Pair.of(cursor, state.getAsInt()));
            cursor = relative(cursor, direction, 1);
        }

        movingPistons.remove(relative(position, direction, 1));
        for (Pair<Vector3i, Integer> move : moves) {
            setBlockState(move.left(), airState);
        }
        for (int index = moves.size() - 1; index >= 0; index--) {
            Pair<Vector3i, Integer> move = moves.get(index);
            Vector3i destination = relative(move.left(), direction, 1);
            setBlockState(destination, movingState);
            movingPistons.put(destination, new MovingPiston(
                    destination, move.right(), direction, true, false, 0.0f));
        }
        Vector3i arm = relative(position, direction, 1);
        setBlockState(position, baseState);
        setBlockState(arm, movingState);
        movingPistons.put(arm, new MovingPiston(
                arm, headState, direction, true, true, 0.0f));
        return Optional.empty();
    }

    private Optional<String> retractPiston(
            Vector3i position,
            Direction direction,
            boolean sticky,
            boolean pull,
            PistonValueType event) {
        String facing = direction.name().toLowerCase(java.util.Locale.ROOT);
        String type = sticky ? "sticky" : "normal";
        int baseState = minecraftData.blockState((sticky ? "minecraft:sticky_piston" : "minecraft:piston")
                + "[extended=false,facing=" + facing + "]");
        int movingState = minecraftData.blockState("minecraft:moving_piston[facing=" + facing
                + ",type=" + type + "]");
        int airState = minecraftData.blockState("minecraft:air");
        if (baseState < 0 || movingState < 0 || airState < 0) {
            return pistonDiagnostic(position, event, direction,
                    "fixed 26.2 piston transition states are missing");
        }

        Vector3i arm = relative(position, direction, 1);
        Vector3i pullSource = relative(position, direction, 2);
        int pulledState = -1;
        if (sticky && pull) {
            OptionalInt state = blockState(pullSource.getX(), pullSource.getY(), pullSource.getZ());
            if (state.isEmpty()) {
                return pistonDiagnostic(position, event, direction,
                        "required sticky pull state is unknown at " + pullSource);
            }
            if (!minecraftData.block(state.getAsInt()).air()) {
                pulledState = state.getAsInt();
            }
        }

        movingPistons.remove(arm);
        setBlockState(position, movingState);
        setBlockState(arm, airState);
        movingPistons.put(position, new MovingPiston(
                position, baseState, direction, false, true, 0.0f));
        if (pulledState >= 0) {
            setBlockState(pullSource, airState);
            setBlockState(arm, movingState);
            movingPistons.put(arm, new MovingPiston(
                    arm, pulledState, direction, false, false, 0.0f));
        }
        return Optional.empty();
    }

    private static Optional<String> pistonDiagnostic(
            Vector3i position, PistonValueType event, Direction direction, String detail) {
        return Optional.of("position=" + position + ", event=" + event
                + ", direction=" + direction + ": " + detail);
    }

    private void setBlockState(Vector3i position, int state) {
        ChunkSection[] sections = chunks.get(key(position.getX() >> 4, position.getZ() >> 4));
        if (sections == null || position.getY() < minimumY || position.getY() >= minimumY + height) {
            return;
        }
        sections[Math.floorDiv(position.getY() - minimumY, 16)].setBlock(
                position.getX() & 15, position.getY() & 15, position.getZ() & 15, state);
    }

    public void updateBlock(BlockChangeEntry entry) {
        Vector3i position = entry.getPosition();
        ChunkSection[] sections = chunks.get(key(position.getX() >> 4, position.getZ() >> 4));
        if (sections == null || height <= 0) {
            return;
        }
        int sectionIndex = Math.floorDiv(position.getY() - minimumY, 16);
        if (sectionIndex < 0 || sectionIndex >= sections.length) {
            return;
        }
        sections[sectionIndex].setBlock(
                position.getX() & 15, position.getY() & 15, position.getZ() & 15, entry.getBlock());
    }

    public void updateSection(BlockChangeEntry[] entries) {
        for (BlockChangeEntry entry : entries) {
            updateBlock(entry);
        }
    }

    public OptionalInt blockState(int x, int y, int z) {
        ChunkSection[] sections = chunks.get(key(x >> 4, z >> 4));
        if (sections == null) {
            return OptionalInt.empty();
        }
        if (y < minimumY || y >= minimumY + height) {
            return OptionalInt.of(0);
        }
        return OptionalInt.of(sections[Math.floorDiv(y - minimumY, 16)]
                .getBlock(x & 15, y & 15, z & 15));
    }

    public boolean currentPlayerChunkLoaded(double x, double z) {
        return chunks.containsKey(key(floor(x) >> 4, floor(z) >> 4));
    }

    public Optional<String> automationUnavailableReason() {
        if (dimensionTypes.isEmpty()) {
            return Optional.of("Cannot enable shadow because the dimension type registry has no entries.");
        }
        if (biomeRegistrySize == 0) {
            return Optional.of("Cannot enable shadow because the biome registry has no entries.");
        }
        if (selectedDimensionId < 0 || selectedDimensionId >= dimensionTypes.size()) {
            return Optional.of("Cannot enable shadow because dimension ID " + selectedDimensionId
                    + " is outside the dimension type registry.");
        }
        RegistryEntry dimensionType = dimensionTypes.get(selectedDimensionId);
        if (dimensionType.getData() == null) {
            return Optional.of("Cannot enable shadow because dimension type " + dimensionType.getId()
                    + " at ID " + selectedDimensionId + " has no resolved data.");
        }
        if (height < 16 || height % 16 != 0 || minimumY % 16 != 0) {
            return Optional.of("Cannot enable shadow because dimension type " + dimensionType.getId()
                    + " has minimum Y " + minimumY + " and height " + height
                    + ". The values must be multiples of 16, and height must be at least 16.");
        }
        return Optional.empty();
    }

    public void tickingState(float tickRate, boolean frozen) {
        if (tickRate > 0.0f) {
            serverMillisecondsPerTick = 1000.0 / tickRate;
        }
        this.frozen = frozen;
    }

    public void tickingStep(int steps) {
        tickingSteps = Math.max(0, steps);
    }

    public double clientTickCadenceMillis() {
        return Math.max(50.0, serverMillisecondsPerTick);
    }

    public void border(double centerX, double centerZ, double size, double targetSize, long lerpMillis) {
        borderCenterX = centerX;
        borderCenterZ = centerZ;
        borderSize = size;
        borderTargetSize = targetSize;
        borderLerpRemainingMillis = Math.max(0L, lerpMillis);
    }

    public void borderCenter(double x, double z) {
        borderCenterX = x;
        borderCenterZ = z;
    }

    public void borderSize(double size) {
        borderSize = size;
        borderTargetSize = size;
        borderLerpRemainingMillis = 0L;
    }

    public void borderLerp(double oldSize, double newSize, long millis) {
        borderSize = oldSize;
        borderTargetSize = newSize;
        borderLerpRemainingMillis = Math.max(0L, millis);
    }

    private void tickBorder(long elapsedMillis) {
        if (borderLerpRemainingMillis <= 0L) {
            borderSize = borderTargetSize;
            return;
        }
        long applied = Math.min(elapsedMillis, borderLerpRemainingMillis);
        borderSize += (borderTargetSize - borderSize) * applied / borderLerpRemainingMillis;
        borderLerpRemainingMillis -= applied;
    }

    private void tickMovingPistons() {
        Iterator<MovingPiston> iterator = movingPistons.values().iterator();
        while (iterator.hasNext()) {
            MovingPiston piston = iterator.next();
            float previous = piston.progress();
            float movement = piston.tick();
            if (movement <= 0.0f) {
                setBlockState(piston.position(), piston.movedState());
                iterator.remove();
                continue;
            }
            moveCollidedEntities(piston, previous, movement);
            moveHoneyEntities(piston, movement);
            if (piston.progress() >= 1.0f) {
                setBlockState(piston.position(), piston.movedState());
                iterator.remove();
            }
        }
    }

    private void moveCollidedEntities(MovingPiston piston, float previous, float movement) {
        int collisionState = collisionState(piston, previous);
        if (collisionState < 0) {
            return;
        }
        Vector3d movementVector = directionVector(piston.movementDirection());
        Vector3d pistonOffset = directionVector(piston.direction()).mul(piston.extendedProgress(previous));
        boolean slime = minecraftData.block(piston.movedState()).behavior() == Block.Behavior.SLIME;
        for (Entity entity : List.copyOf(entities.values())) {
            if (entity.ignoresPistonMovement()) {
                continue;
            }
            double penetration = 0.0;
            AABB entityBox = entity.boundingBox();
            for (AABB local : minecraftData.shape(minecraftData.block(collisionState).collisionShapeId())) {
                AABB current = local.move(
                        piston.position().getX() + pistonOffset.getX(),
                        piston.position().getY() + pistonOffset.getY(),
                        piston.position().getZ() + pistonOffset.getZ());
                AABB movementArea = PistonPhysics.movementArea(
                        current, piston.movementDirection(), movement);
                if (movementArea.intersects(entityBox)) {
                    penetration = Math.max(penetration,
                            PistonPhysics.penetration(
                                    movementArea, piston.movementDirection(), entityBox));
                    if (penetration >= movement) {
                        break;
                    }
                }
            }
            if (penetration <= 0.0) {
                continue;
            }
            if (slime) {
                Vector3d velocity = entity.velocity();
                entity.setVelocity(Vector3d.from(
                        movementVector.getX() == 0.0 ? velocity.getX() : movementVector.getX(),
                        movementVector.getY() == 0.0 ? velocity.getY() : movementVector.getY(),
                        movementVector.getZ() == 0.0 ? velocity.getZ() : movementVector.getZ()));
            }
            moveEntityByPiston(piston, entity,
                    movementVector.mul(Math.min(penetration, movement) + 0.01));
            if (!piston.extending() && piston.source()) {
                fixEntityWithinPistonBase(piston, entity, movement);
            }
        }
    }

    private void fixEntityWithinPistonBase(
            MovingPiston piston, Entity entity, double progressMovement) {
        AABB entityBox = entity.boundingBox();
        AABB base = new AABB(
                piston.position().getX(), piston.position().getY(), piston.position().getZ(),
                piston.position().getX() + 1.0, piston.position().getY() + 1.0,
                piston.position().getZ() + 1.0);
        if (!base.intersects(entityBox)) {
            return;
        }
        Direction outward = piston.direction();
        double movement = PistonPhysics.penetration(base, outward, entityBox) + 0.01;
        AABB intersection = new AABB(
                Math.max(base.minX(), entityBox.minX()),
                Math.max(base.minY(), entityBox.minY()),
                Math.max(base.minZ(), entityBox.minZ()),
                Math.min(base.maxX(), entityBox.maxX()),
                Math.min(base.maxY(), entityBox.maxY()),
                Math.min(base.maxZ(), entityBox.maxZ()));
        double intersectedMovement = PistonPhysics.penetration(base, outward, intersection) + 0.01;
        if (Math.abs(movement - intersectedMovement) < 0.01) {
            moveEntityByPiston(piston, entity, directionVector(outward)
                    .mul(Math.min(movement, progressMovement) + 0.01));
        }
    }

    private void moveHoneyEntities(MovingPiston piston, float movement) {
        if (minecraftData.block(piston.movedState()).behavior() != Block.Behavior.HONEY
                || piston.movementDirection() == Direction.UP
                || piston.movementDirection() == Direction.DOWN) {
            return;
        }
        AABB[] shapes = minecraftData.shape(minecraftData.block(piston.movedState()).collisionShapeId());
        double top = 0.0;
        for (AABB shape : shapes) {
            top = Math.max(top, shape.maxY());
        }
        Vector3d offset = directionVector(piston.direction()).mul(
                piston.extendedProgress(piston.progress()));
        AABB carry = new AABB(0.0, top, 0.0, 1.0, 1.500001, 1.0).move(
                piston.position().getX() + offset.getX(),
                piston.position().getY() + offset.getY(),
                piston.position().getZ() + offset.getZ());
        Vector3d movementVector = directionVector(piston.movementDirection()).mul(movement);
        for (Entity entity : List.copyOf(entities.values())) {
            if (entity.ignoresPistonMovement()
                    || !entity.onGround() || !carry.intersects(entity.boundingBox())) {
                continue;
            }
            double x = entity.position().getX();
            double z = entity.position().getZ();
            if (x >= carry.minX() && x <= carry.maxX() && z >= carry.minZ() && z <= carry.maxZ()) {
                moveEntityByPiston(piston, entity, movementVector);
            }
        }
    }

    private void moveEntityByPiston(MovingPiston piston, Entity entity, Vector3d movement) {
        activePiston = piston;
        try {
            entity.moveByPiston(this, movement);
        } finally {
            activePiston = null;
        }
    }

    private int collisionState(MovingPiston piston, float progress) {
        if (!piston.source() || piston.extending()) {
            return piston.movedState();
        }
        String facing = piston.direction().name().toLowerCase(java.util.Locale.ROOT);
        boolean sticky = minecraftData.block(piston.movedState()).stateKey()
                .startsWith("minecraft:sticky_piston[");
        boolean shortHead = progress > 0.25f;
        return minecraftData.blockState("minecraft:piston_head[facing=" + facing
                + ",short=" + shortHead + ",type=" + (sticky ? "sticky" : "normal") + "]");
    }

    private void addMovingPistonCollisions(
            MovingPiston piston, float progress, AABB query, List<AABB> boxes) {
        int stateId = collisionState(piston, progress);
        if (piston.source() && !piston.extending()) {
            String baseKey = minecraftData.block(piston.movedState()).stateKey()
                    .replace("extended=false", "extended=true");
            addStateShape(minecraftData.blockState(baseKey), piston.position(), Vector3d.ZERO, query, boxes);
        }
        double offset = piston.extendedProgress(progress);
        addStateShape(stateId, piston.position(), directionVector(piston.direction()).mul(offset), query, boxes);
    }

    private void addStateShape(
            int stateId, Vector3i position, Vector3d offset, AABB query, List<AABB> boxes) {
        if (stateId < 0) {
            return;
        }
        Block block = minecraftData.block(stateId);
        for (AABB local : minecraftData.shape(block.collisionShapeId())) {
            AABB box = local.move(
                    position.getX() + offset.getX(),
                    position.getY() + offset.getY(),
                    position.getZ() + offset.getZ());
            if (query == null || query.intersects(box)) {
                boxes.add(box);
            }
        }
    }

    private static Vector3d directionVector(Direction direction) {
        return switch (direction) {
            case DOWN -> Vector3d.from(0.0, -1.0, 0.0);
            case UP -> Vector3d.from(0.0, 1.0, 0.0);
            case NORTH -> Vector3d.from(0.0, 0.0, -1.0);
            case SOUTH -> Vector3d.from(0.0, 0.0, 1.0);
            case WEST -> Vector3d.from(-1.0, 0.0, 0.0);
            case EAST -> Vector3d.from(1.0, 0.0, 0.0);
        };
    }

    private static Vector3i relative(Vector3i position, Direction direction, int distance) {
        Vector3d vector = directionVector(direction).mul(distance);
        return Vector3i.from(
                position.getX() + (int) vector.getX(),
                position.getY() + (int) vector.getY(),
                position.getZ() + (int) vector.getZ());
    }

    private void addBorderCollisions(AABB query, List<AABB> boxes) {
        double half = borderSize / 2.0;
        double west = borderCenterX - half;
        double east = borderCenterX + half;
        double north = borderCenterZ - half;
        double south = borderCenterZ + half;
        double extent = 3.0E7;
        if (query.minX() < west) {
            boxes.add(new AABB(west - 1.0, -extent, -extent, west, extent, extent));
        }
        if (query.maxX() > east) {
            boxes.add(new AABB(east, -extent, -extent, east + 1.0, extent, extent));
        }
        if (query.minZ() < north) {
            boxes.add(new AABB(-extent, -extent, north - 1.0, extent, extent, north));
        }
        if (query.maxZ() > south) {
            boxes.add(new AABB(-extent, -extent, south, extent, extent, south + 1.0));
        }
    }

    private Vector3d fluidFlowAt(int x, int y, int z, Block current) {
        double currentHeight = fluidHeight(current);
        double flowX = 0.0;
        double flowZ = 0.0;
        Direction[] directions = {Direction.WEST, Direction.EAST, Direction.NORTH, Direction.SOUTH};
        for (Direction direction : directions) {
            Vector3d step = directionVector(direction);
            int nextX = x + (int) step.getX();
            int nextZ = z + (int) step.getZ();
            OptionalInt neighborState = blockState(nextX, y, nextZ);
            Block neighbor = minecraftData.block(neighborState.orElse(0));
            double difference = 0.0;
            if (sameFluid(current, neighbor)) {
                difference = currentHeight - fluidHeight(neighbor);
            } else if (!neighbor.water() && !neighbor.lava()
                    && minecraftData.shape(neighbor.collisionShapeId()).length == 0) {
                Block below = minecraftData.block(
                        blockState(nextX, y - 1, nextZ).orElseThrow());
                difference = sameFluid(current, below)
                        ? currentHeight - (fluidHeight(below) - 8.0 / 9.0) : 0.0;
            }
            flowX += step.getX() * difference;
            flowZ += step.getZ() * difference;
        }
        double flowY = 0.0;
        if (current.fluidFalling()) {
            for (Direction direction : directions) {
                Vector3d step = directionVector(direction);
                int nextX = x + (int) step.getX();
                int nextZ = z + (int) step.getZ();
                if (fluidSolidFace(current, nextX, y, nextZ, direction)
                        || fluidSolidFace(current, nextX, y + 1, nextZ, direction)) {
                    double horizontalLength = Math.sqrt(flowX * flowX + flowZ * flowZ);
                    if (horizontalLength > 1.0E-7) {
                        flowX /= horizontalLength;
                        flowZ /= horizontalLength;
                    }
                    flowY = -6.0;
                    break;
                }
            }
        }
        double length = Math.sqrt(flowX * flowX + flowY * flowY + flowZ * flowZ);
        return length < 1.0E-7 ? Vector3d.ZERO
                : Vector3d.from(flowX / length, flowY / length, flowZ / length);
    }

    private boolean fluidSolidFace(
            Block fluid, int x, int y, int z, Direction direction) {
        Block block = minecraftData.block(blockState(x, y, z).orElseThrow());
        return !sameFluid(fluid, block) && block.hasFluidFace(direction);
    }

    private void detach(Entity entity) {
        if (entity.vehicle() != null) {
            entity.vehicle().detachPassenger(entity);
        }
        for (Entity passenger : List.copyOf(entity.passengers())) {
            entity.detachPassenger(passenger);
        }
    }

    private static boolean wouldCreatePassengerCycle(Entity vehicle, Entity passenger) {
        Entity ancestor = vehicle;
        while (ancestor != null) {
            if (ancestor == passenger) {
                return true;
            }
            ancestor = ancestor.vehicle();
        }
        return false;
    }

    private static double fluidHeight(Block block) {
        return block.fluidAmount() / 9.0;
    }

    private double fluidHeightAt(int x, int y, int z, Block block) {
        Block above = minecraftData.block(blockState(x, y + 1, z).orElseThrow());
        return sameFluid(block, above) ? 1.0 : fluidHeight(block);
    }

    private double fluidHeightAtKnown(int x, int y, int z, Block block) {
        OptionalInt aboveState = blockState(x, y + 1, z);
        if (aboveState.isEmpty()) {
            return -1.0;
        }
        Block above = minecraftData.block(aboveState.getAsInt());
        return sameFluid(block, above) ? 1.0 : fluidHeight(block);
    }

    private static boolean sameFluid(
            Block left, Block right) {
        return left.water() && right.water() || left.lava() && right.lava();
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    public record FluidSample(double waterHeight, double lavaHeight, @NotNull Vector3d flow) {
        private static final FluidSample EMPTY = new FluidSample(0.0, 0.0, Vector3d.ZERO);
    }

    public record BoatEnvironment(
            @NotNull Vehicle.BoatStatus status, double waterLevel, float landFriction) {
    }

    public record BlockSample(int x, int y, int z, @NotNull Block block) {
    }

    public record LevelChunkInstallResult(
            boolean installed,
            @NotNull String detail,
            @NotNull Optional<RuntimeException> cause) {
        public LevelChunkInstallResult {
            if (installed && (!detail.isEmpty() || cause.isPresent())) {
                throw new IllegalArgumentException(
                        "An installed LevelChunk result cannot contain failure detail or a cause.");
            }
            if (!installed && detail.isBlank()) {
                throw new IllegalArgumentException(
                        "A failed LevelChunk result must contain failure detail.");
            }
        }

        private static LevelChunkInstallResult success() {
            return new LevelChunkInstallResult(true, "", Optional.empty());
        }

        private static LevelChunkInstallResult failure(String detail) {
            return new LevelChunkInstallResult(false, detail, Optional.empty());
        }

        private static LevelChunkInstallResult failure(String detail, RuntimeException cause) {
            return new LevelChunkInstallResult(false, detail, Optional.of(cause));
        }
    }

    @Getter(AccessLevel.PRIVATE)
    @Accessors(fluent = true)
    private static final class MovingPiston {
        private final Vector3i position;
        private final int movedState;
        private final Direction direction;
        private final boolean extending;
        private final boolean source;
        private float progress;

        private MovingPiston(
                Vector3i position,
                int movedState,
                Direction direction,
                boolean extending,
                boolean source,
                float progress) {
            this.position = position;
            this.movedState = movedState;
            this.direction = direction;
            this.extending = extending;
            this.source = source;
            this.progress = Math.clamp(progress, 0.0f, 1.0f);
        }

        private Direction movementDirection() {
            if (extending) {
                return direction;
            }
            return switch (direction) {
                case DOWN -> Direction.UP;
                case UP -> Direction.DOWN;
                case NORTH -> Direction.SOUTH;
                case SOUTH -> Direction.NORTH;
                case WEST -> Direction.EAST;
                case EAST -> Direction.WEST;
            };
        }

        private float tick() {
            float previous = progress;
            progress = Math.min(1.0f, progress + 0.5f);
            return progress - previous;
        }

        private double extendedProgress(float value) {
            return extending ? value - 1.0 : 1.0 - value;
        }
    }
}
