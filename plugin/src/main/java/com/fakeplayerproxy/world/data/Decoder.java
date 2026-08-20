package com.fakeplayerproxy.world.data;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fakeplayerproxy.world.data.EntityTypeData.MovementKind;
import com.fakeplayerproxy.world.data.EntityTypeData.PoseData;
import com.fakeplayerproxy.world.phys.AABB;
import it.unimi.dsi.fastutil.Pair;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.experimental.Accessors;
import net.kyori.adventure.key.Key;
import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.mcprotocollib.protocol.data.game.RegistryEntry;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.Pose;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;

/** Immutable fixed-version block physics table loaded from the committed compact resource. */
public final class Decoder {
    private static final String RESOURCE = "/minecraft-data/minecraft-data.bin";
    private static final Key DIMENSION_TYPE_REGISTRY = Key.key("minecraft", "dimension_type");
    private static final Map<Key, Pair<Integer, Integer>> DIMENSION_TYPES = Map.of(
            Key.key("minecraft", "overworld"), Pair.of(-64, 384),
            Key.key("minecraft", "overworld_caves"), Pair.of(-64, 384),
            Key.key("minecraft", "the_end"), Pair.of(0, 256),
            Key.key("minecraft", "the_nether"), Pair.of(0, 256));
    @Getter(AccessLevel.PUBLIC)
    @Accessors(fluent = true)
    private static final Decoder instance = load();

    private final Block[] blocks;
    private final AABB[][] shapes;
    private final AABB[][] outlineShapes;
    private final EntityTypeData[] entities;
    private final ItemData[] items;
    private final Map<String, Integer> blockStateIds;
    private final Map<Key, Integer> itemIds;
    @Getter
    @Accessors(fluent = true)
    private final int leatherBootsItemId;
    @Getter
    @Accessors(fluent = true)
    private final int elytraItemId;
    @Getter
    @Accessors(fluent = true)
    private final int saddleItemId;
    @Getter
    @Accessors(fluent = true)
    private final int carrotOnAStickItemId;
    @Getter
    @Accessors(fluent = true)
    private final int warpedFungusOnAStickItemId;
    private final Set<Integer> harnessItemIds;

    private Decoder(
            Block[] blocks,
            AABB[][] shapes,
            AABB[][] outlineShapes,
            EntityTypeData[] entities,
            ItemData[] items,
            int leatherBootsItemId,
            int elytraItemId,
            int saddleItemId,
            int carrotOnAStickItemId,
            int warpedFungusOnAStickItemId,
            Set<Integer> harnessItemIds) {
        this.blocks = blocks;
        this.shapes = shapes;
        this.outlineShapes = outlineShapes;
        this.entities = entities;
        this.items = items;
        Map<Key, Integer> fixedItemIds = HashMap.newHashMap(items.length);
        for (int itemId = 0; itemId < items.length; itemId++) {
            if (fixedItemIds.put(items[itemId].registryKey(), itemId) != null) {
                throw new IllegalStateException("Duplicate fixed item key " + items[itemId].registryKey());
            }
        }
        itemIds = Map.copyOf(fixedItemIds);
        Map<String, Integer> stateIds = HashMap.newHashMap(blocks.length);
        for (int stateId = 0; stateId < blocks.length; stateId++) {
            if (stateIds.put(blocks[stateId].stateKey(), stateId) != null) {
                throw new IllegalStateException("Duplicate fixed block state key " + blocks[stateId].stateKey());
            }
        }
        this.blockStateIds = Map.copyOf(stateIds);
        this.leatherBootsItemId = leatherBootsItemId;
        this.elytraItemId = elytraItemId;
        this.saddleItemId = saddleItemId;
        this.carrotOnAStickItemId = carrotOnAStickItemId;
        this.warpedFungusOnAStickItemId = warpedFungusOnAStickItemId;
        this.harnessItemIds = Set.copyOf(harnessItemIds);
    }

    public int blockStateCount() {
        return blocks.length;
    }

    public Block block(int stateId) {
        if (stateId < 0 || stateId >= blocks.length) {
            throw new IllegalArgumentException("Block state id is outside fixed 26.2 registry: " + stateId);
        }
        return blocks[stateId];
    }

    public AABB[] shape(int shapeId) {
        return shapes[shapeId];
    }

    public AABB[] outlineShape(int shapeId) {
        return outlineShapes[shapeId];
    }

    public ItemData item(int itemId) {
        if (itemId < 0 || itemId >= items.length) {
            throw new IllegalArgumentException("Item id is outside fixed 26.2 registry: " + itemId);
        }
        return items[itemId];
    }

    public int itemId(Key key) {
        return itemIds.getOrDefault(key, -1);
    }

    public int blockState(NbtMap value) {
        String name = value.getString("Name", "");
        if (name.isEmpty()) {
            return -1;
        }
        NbtMap properties = value.getCompound("Properties", NbtMap.EMPTY);
        StringBuilder key = new StringBuilder(name.indexOf(':') >= 0 ? name : "minecraft:" + name);
        if (!properties.isEmpty()) {
            key.append('[');
            properties.keySet().stream().sorted().forEach(property -> key.append(property)
                    .append('=').append(properties.getString(property, "")).append(','));
            key.setCharAt(key.length() - 1, ']');
        }
        return blockStateIds.getOrDefault(key.toString(), -1);
    }

    public int blockState(String key) {
        return blockStateIds.getOrDefault(key, -1);
    }

    public EntityTypeData entity(EntityType type) {
        return entities[type.ordinal()];
    }

    public boolean isHarnessItem(int itemId) {
        return harnessItemIds.contains(itemId);
    }

    public List<RegistryEntry> completeDimensionTypes(
            Key registry, List<RegistryEntry> entries, boolean useFixedValues) {
        if (!DIMENSION_TYPE_REGISTRY.equals(registry) || !useFixedValues) {
            return List.copyOf(entries);
        }
        List<RegistryEntry> resolved = new ArrayList<>(entries.size());
        for (RegistryEntry entry : entries) {
            Pair<Integer, Integer> fixed = DIMENSION_TYPES.get(entry.getId());
            if (entry.getData() != null || fixed == null) {
                resolved.add(entry);
            } else {
                resolved.add(new RegistryEntry(entry.getId(), NbtMap.builder()
                        .putInt("min_y", fixed.left())
                        .putInt("height", fixed.right())
                        .build()));
            }
        }
        return List.copyOf(resolved);
    }

    @SuppressWarnings("PatternValidation")
    private static Decoder load() {
        try {
            byte[] resource;
            try (InputStream input = requiredResource()) {
                resource = input.readAllBytes();
            }

            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(resource))) {
                int[] header = readInts(input, 10);
                int blockCount = header[0];
                int shapeCount = header[1];
                int outlineShapeCount = header[2];
                int entityCount = header[3];
                int itemCount = header[4];
                int leatherBootsItemId = header[5];
                int elytraItemId = header[6];
                int saddleItemId = header[7];
                int carrotOnAStickItemId = header[8];
                int warpedFungusOnAStickItemId = header[9];
                int harnessCount = input.readUnsignedShort();
                Set<Integer> harnessItemIds = HashSet.newHashSet(harnessCount);
                for (int index = 0; index < harnessCount; index++) {
                    harnessItemIds.add(input.readInt());
                }
                if (blockCount < 1 || shapeCount < 1 || outlineShapeCount < 1
                        || entityCount < 1 || itemCount < 1) {
                    throw new IllegalStateException("Physics data contains an empty fixed registry");
                }
                for (int itemId : new int[] {
                        leatherBootsItemId, elytraItemId, saddleItemId,
                        carrotOnAStickItemId, warpedFungusOnAStickItemId
                }) {
                    if (itemId < 0 || itemId >= itemCount) {
                        throw new IllegalStateException("Physics data has an invalid named item id");
                    }
                }
                if (harnessItemIds.stream().anyMatch(itemId -> itemId < 0 || itemId >= itemCount)
                        || harnessItemIds.size() != harnessCount) {
                    throw new IllegalStateException("Physics data has invalid harness item ids");
                }

                AABB[][] shapes = readShapes(input, shapeCount);
                AABB[][] outlineShapes = readShapes(input, outlineShapeCount);

                ItemData[] items = new ItemData[itemCount];
                for (int itemId = 0; itemId < items.length; itemId++) {
                    // IDEA's validation warning is a false positive. Decoder checks this generator-owned key.
                    //noinspection PatternValidation
                    Key registryKey = Key.key(input.readUTF());
                    int featureCount = input.readUnsignedShort();
                    Set<Key> features = HashSet.newHashSet(featureCount);
                    for (int feature = 0; feature < featureCount; feature++) {
                        // IDEA's validation warning is a false positive. Decoder checks this generator-owned key.
                        //noinspection PatternValidation
                        if (!features.add(Key.key(input.readUTF()))) {
                            throw new IllegalStateException("Item " + itemId + " has duplicate feature keys");
                        }
                    }
                    int flags = input.readUnsignedByte();
                    Optional<ItemData.FoodData> food = (flags & 8) != 0
                            ? Optional.of(new ItemData.FoodData(input.readBoolean())) : Optional.empty();
                    Optional<Float> consumeSeconds = (flags & 16) != 0
                            ? Optional.of(readFiniteFloat(input, "item consume duration")) : Optional.empty();
                    // IDEA's validation warning is a false positive. Decoder checks this generator-owned key.
                    //noinspection PatternValidation
                    Optional<Key> cooldownGroup = (flags & 32) != 0
                            ? Optional.of(Key.key(input.readUTF())) : Optional.empty();
                    Optional<ItemData.ToolData> tool = Optional.empty();
                    if ((flags & 64) != 0) {
                        float defaultSpeed = readFiniteFloat(input, "tool default speed");
                        int ruleCount = input.readUnsignedShort();
                        List<ItemData.ToolData.Rule> rules = new ArrayList<>(ruleCount);
                        for (int ruleIndex = 0; ruleIndex < ruleCount; ruleIndex++) {
                            Key tag = null;
                            Set<Key> blocks;
                            if (input.readBoolean()) {
                                blocks = Set.of();
                                // IDEA's validation warning is a false positive. Decoder checks this generator-owned key.
                                //noinspection PatternValidation
                                tag = Key.key(input.readUTF());
                            } else {
                                int blockHolderCount = input.readUnsignedShort();
                                blocks = HashSet.newHashSet(blockHolderCount);
                                for (int holder = 0; holder < blockHolderCount; holder++) {
                                    // IDEA's validation warning is a false positive. Decoder checks this generator-owned key.
                                    //noinspection PatternValidation
                                    blocks.add(Key.key(input.readUTF()));
                                }
                            }
                            Float speed = input.readBoolean()
                                    ? readFiniteFloat(input, "tool rule speed") : null;
                            Boolean correct = input.readBoolean() ? input.readBoolean() : null;
                            rules.add(new ItemData.ToolData.Rule(blocks, tag, speed, correct));
                        }
                        tool = Optional.of(new ItemData.ToolData(defaultSpeed, rules));
                    }
                    Optional<ItemData.AttackRangeData> attackRange = Optional.empty();
                    if ((flags & 128) != 0) {
                        float minimum = readFiniteFloat(input, "minimum attack range");
                        float maximum = readFiniteFloat(input, "maximum attack range");
                        float creativeMinimum = readFiniteFloat(input, "creative minimum attack range");
                        float creativeMaximum = readFiniteFloat(input, "creative maximum attack range");
                        float hitboxMargin = readFiniteFloat(input, "attack hitbox margin");
                        float mobFactor = readFiniteFloat(input, "attack mob factor");
                        if (minimum < 0.0f || maximum < minimum
                                || creativeMinimum < 0.0f || creativeMaximum < creativeMinimum
                                || hitboxMargin < 0.0f || hitboxMargin > 1.0f
                                || mobFactor < 0.0f || mobFactor > 2.0f) {
                            throw new IllegalStateException(
                                    "Item " + itemId + " has an invalid attack range");
                        }
                        attackRange = Optional.of(new ItemData.AttackRangeData(
                                minimum, maximum, creativeMinimum, creativeMaximum,
                                hitboxMargin, mobFactor));
                    }
                    items[itemId] = new ItemData(
                            registryKey, features, (flags & 1) != 0, (flags & 2) != 0,
                            (flags & 4) != 0, food, consumeSeconds, cooldownGroup, tool, attackRange);
                }

                Block[] blocks = new Block[blockCount];
                for (int stateId = 0; stateId < blocks.length; stateId++) {
                    String stateKey = input.readUTF();
                    int shapeId = input.readInt();
                    int outlineShapeId = input.readInt();
                    int blockId = input.readInt();
                    float friction = readFiniteFloat(input, "block friction");
                    float speedFactor = readFiniteFloat(input, "block speed factor");
                    float bounciness = readFiniteFloat(input, "block bounciness");
                    int flags = input.readUnsignedByte();
                    int fluidAmount = input.readUnsignedByte();
                    int behaviorId = input.readUnsignedByte();
                    int behaviorParameter = input.readUnsignedByte();
                    int fluidFaceMask = input.readUnsignedByte();
                    float destroySpeed = readFiniteFloat(input, "block destroy speed");
                    boolean requiresCorrectTool = input.readBoolean();
                    if (shapeId < 0 || shapeId >= shapes.length
                            || outlineShapeId < 0 || outlineShapeId >= outlineShapes.length || fluidAmount > 9
                            || behaviorId >= Block.Behavior.values().length
                            || fluidFaceMask > 0x3f) {
                        throw new IllegalStateException("Physics data has invalid block state " + stateId);
                    }
                    blocks[stateId] = new Block(
                            stateKey, shapeId, outlineShapeId, blockId, friction, speedFactor, bounciness,
                            (flags & 1) != 0, (flags & 2) != 0, (flags & 8) != 0,
                            fluidAmount, (flags & 4) != 0, Block.Behavior.values()[behaviorId],
                            behaviorParameter, fluidFaceMask, destroySpeed, requiresCorrectTool);
                }
                EntityTypeData[] entities = new EntityTypeData[entityCount];
                for (int index = 0; index < entities.length; index++) {
                    int protocolId = input.readInt();
                    int movementKind = input.readUnsignedByte();
                    int flags = input.readUnsignedByte();
                    boolean pickable = input.readBoolean();
                    float pickRadius = readFiniteFloat(input, "entity pick radius");
                    int pistonReaction = input.readUnsignedByte();
                    byte defaultSharedFlags = input.readByte();
                    int defaultPose = input.readUnsignedByte();
                    int[] metadataIds = readInts(input, 10);
                    float defaultHealth = readFiniteFloat(input, "entity default health");
                    int poseCount = input.readUnsignedByte();
                    EntityType type = EntityType.from(protocolId);
                    if (protocolId != index || type == null
                            || movementKind >= MovementKind.values().length
                            || pistonReaction > 1 || pickRadius < 0.0f
                            || defaultPose >= poseCount || poseCount != Pose.values().length) {
                        throw new IllegalStateException("Physics data has invalid entity " + protocolId);
                    }
                    List<PoseData> poses = new ArrayList<>(poseCount);
                    for (int poseIndex = 0; poseIndex < poseCount; poseIndex++) {
                        float width = readFiniteFloat(input, "entity width");
                        float height = readFiniteFloat(input, "entity height");
                        float eyeHeight = readFiniteFloat(input, "entity eye height");
                        int attachmentCount = input.readUnsignedByte();
                        List<Vector3d> passengerAttachments = new ArrayList<>(attachmentCount);
                        for (int attachmentIndex = 0; attachmentIndex < attachmentCount; attachmentIndex++) {
                            passengerAttachments.add(Vector3d.from(
                                    readFiniteFloat(input, "passenger attachment x"),
                                    readFiniteFloat(input, "passenger attachment y"),
                                    readFiniteFloat(input, "passenger attachment z")));
                        }
                        Vector3d vehicleAttachment = Vector3d.from(
                                readFiniteFloat(input, "vehicle attachment x"),
                                readFiniteFloat(input, "vehicle attachment y"),
                                readFiniteFloat(input, "vehicle attachment z"));
                        poses.add(new PoseData(
                                width, height, eyeHeight, passengerAttachments, vehicleAttachment));
                    }
                    MovementKind kind = MovementKind.values()[movementKind];
                    EntityTypeData.VehicleData vehicle = kind == MovementKind.SERVER
                            || kind == MovementKind.MINECART ? null : new EntityTypeData.VehicleData(
                                    metadataIds[5], metadataIds[6], metadataIds[7],
                                    metadataIds[8], metadataIds[9]);
                    entities[index] = new EntityTypeData(
                            poses, kind, vehicle,
                            (flags & 1) != 0, (flags & 2) != 0, (flags & 8) != 0,
                            pickable, pickRadius,
                            pistonReaction == 0,
                            defaultSharedFlags, (flags & 4) != 0, Pose.values()[defaultPose],
                            metadataIds[0], metadataIds[1], metadataIds[2], metadataIds[3], metadataIds[4],
                            defaultHealth,
                            readFiniteDouble(input, "entity gravity"),
                            readFiniteDouble(input, "entity scale"),
                            readFiniteDouble(input, "entity step height"),
                            readFiniteDouble(input, "entity movement speed"),
                            readFiniteDouble(input, "entity movement efficiency"),
                            readFiniteDouble(input, "entity water movement efficiency"),
                            readFiniteDouble(input, "entity bounciness"));
                }
                if (input.read() != -1) {
                    throw new IllegalStateException("Physics data has trailing bytes");
                }
                return new Decoder(
                        blocks, shapes, outlineShapes, entities, items,
                        leatherBootsItemId, elytraItemId,
                        saddleItemId, carrotOnAStickItemId, warpedFungusOnAStickItemId,
                        harnessItemIds);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load fixed Minecraft 26.2 physics data", exception);
        }
    }

    private static InputStream requiredResource() {
        InputStream input = Decoder.class.getResourceAsStream(RESOURCE);
        if (input == null) {
            throw new IllegalStateException("Missing required Minecraft data resource " + RESOURCE);
        }
        return input;
    }

    @SuppressWarnings("SameParameterValue")
    private static int[] readInts(DataInputStream input, int count) throws IOException {
        int[] values = new int[count];
        for (int index = 0; index < count; index++) {
            values[index] = input.readInt();
        }
        return values;
    }

    private static AABB[][] readShapes(DataInputStream input, int count) throws IOException {
        AABB[][] shapes = new AABB[count][];
        for (int shapeId = 0; shapeId < shapes.length; shapeId++) {
            int boxCount = input.readUnsignedShort();
            AABB[] boxes = new AABB[boxCount];
            for (int boxIndex = 0; boxIndex < boxes.length; boxIndex++) {
                boxes[boxIndex] = new AABB(
                        readFiniteFloat(input, "shape minimum x"),
                        readFiniteFloat(input, "shape minimum y"),
                        readFiniteFloat(input, "shape minimum z"),
                        readFiniteFloat(input, "shape maximum x"),
                        readFiniteFloat(input, "shape maximum y"),
                        readFiniteFloat(input, "shape maximum z"));
            }
            shapes[shapeId] = boxes;
        }
        return shapes;
    }

    private static float readFiniteFloat(DataInputStream input, String field) throws IOException {
        float value = input.readFloat();
        if (!Float.isFinite(value)) {
            throw new IllegalStateException("Physics data contains a non-finite " + field);
        }
        return value;
    }

    private static double readFiniteDouble(DataInputStream input, String field) throws IOException {
        double value = input.readDouble();
        if (!Double.isFinite(value)) {
            throw new IllegalStateException("Physics data contains a non-finite " + field);
        }
        return value;
    }
}
