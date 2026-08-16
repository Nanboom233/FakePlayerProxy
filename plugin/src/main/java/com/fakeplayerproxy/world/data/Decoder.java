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
import java.util.Set;

import com.fakeplayerproxy.world.data.EntityTypeData.MovementKind;
import com.fakeplayerproxy.world.data.EntityTypeData.PoseData;
import com.fakeplayerproxy.world.phys.AABB;
import it.unimi.dsi.fastutil.Pair;
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
    private static final Decoder INSTANCE = load();

    private final Block[] blocks;
    private final AABB[][] shapes;
    private final EntityTypeData[] entities;
    private final Map<String, Integer> blockStateIds;
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
            EntityTypeData[] entities,
            int leatherBootsItemId,
            int elytraItemId,
            int saddleItemId,
            int carrotOnAStickItemId,
            int warpedFungusOnAStickItemId,
            Set<Integer> harnessItemIds) {
        this.blocks = blocks;
        this.shapes = shapes;
        this.entities = entities;
        Map<String, Integer> stateIds = new HashMap<>();
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

    public static Decoder instance() {
        return INSTANCE;
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

    private static Decoder load() {
        try {
            byte[] resource;
            try (InputStream input = requiredResource()) {
                resource = input.readAllBytes();
            }

            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(resource))) {
                int[] header = readInts(input, 8);
                int blockCount = header[0];
                int shapeCount = header[1];
                int entityCount = header[2];
                int leatherBootsItemId = header[3];
                int elytraItemId = header[4];
                int saddleItemId = header[5];
                int carrotOnAStickItemId = header[6];
                int warpedFungusOnAStickItemId = header[7];
                int harnessCount = input.readUnsignedShort();
                Set<Integer> harnessItemIds = new HashSet<>();
                for (int index = 0; index < harnessCount; index++) {
                    harnessItemIds.add(input.readInt());
                }
                if (blockCount < 1 || shapeCount < 1 || entityCount < 1) {
                    throw new IllegalStateException("Physics data contains an empty fixed registry");
                }

                AABB[][] shapes = new AABB[shapeCount][];
                for (int shapeId = 0; shapeId < shapes.length; shapeId++) {
                    int boxCount = input.readUnsignedShort();
                    AABB[] boxes = new AABB[boxCount];
                    for (int boxIndex = 0; boxIndex < boxes.length; boxIndex++) {
                        boxes[boxIndex] = new AABB(
                                input.readFloat(), input.readFloat(), input.readFloat(),
                                input.readFloat(), input.readFloat(), input.readFloat());
                    }
                    shapes[shapeId] = boxes;
                }

                Block[] blocks = new Block[blockCount];
                for (int stateId = 0; stateId < blocks.length; stateId++) {
                    String stateKey = input.readUTF();
                    int shapeId = input.readInt();
                    int blockId = input.readInt();
                    float friction = input.readFloat();
                    float speedFactor = input.readFloat();
                    float bounciness = input.readFloat();
                    int flags = input.readUnsignedByte();
                    int fluidAmount = input.readUnsignedByte();
                    int behaviorId = input.readUnsignedByte();
                    int behaviorParameter = input.readUnsignedByte();
                    int fluidFaceMask = input.readUnsignedByte();
                    if (shapeId < 0 || shapeId >= shapes.length || fluidAmount > 9
                            || behaviorId >= Block.Behavior.values().length
                            || fluidFaceMask > 0x3f) {
                        throw new IllegalStateException("Physics data has invalid block state " + stateId);
                    }
                    blocks[stateId] = new Block(
                            stateKey, shapeId, blockId, friction, speedFactor, bounciness,
                            (flags & 1) != 0, (flags & 2) != 0, (flags & 8) != 0,
                            fluidAmount, (flags & 4) != 0, Block.Behavior.values()[behaviorId],
                            behaviorParameter, fluidFaceMask);
                }
                EntityTypeData[] entities = new EntityTypeData[entityCount];
                for (int index = 0; index < entities.length; index++) {
                    int protocolId = input.readInt();
                    int movementKind = input.readUnsignedByte();
                    int flags = input.readUnsignedByte();
                    int pistonReaction = input.readUnsignedByte();
                    byte defaultSharedFlags = input.readByte();
                    int defaultPose = input.readUnsignedByte();
                    int[] metadataIds = readInts(input, 9);
                    float defaultHealth = input.readFloat();
                    int poseCount = input.readUnsignedByte();
                    EntityType type = EntityType.from(protocolId);
                    if (protocolId != index || type == null
                            || movementKind >= MovementKind.values().length
                            || pistonReaction > 1
                            || defaultPose >= poseCount || poseCount != Pose.values().length) {
                        throw new IllegalStateException("Physics data has invalid entity " + protocolId);
                    }
                    List<PoseData> poses = new ArrayList<>(poseCount);
                    for (int poseIndex = 0; poseIndex < poseCount; poseIndex++) {
                        float width = input.readFloat();
                        float height = input.readFloat();
                        float eyeHeight = input.readFloat();
                        int attachmentCount = input.readUnsignedByte();
                        List<Vector3d> passengerAttachments = new ArrayList<>(attachmentCount);
                        for (int attachmentIndex = 0; attachmentIndex < attachmentCount; attachmentIndex++) {
                            passengerAttachments.add(Vector3d.from(
                                    input.readFloat(), input.readFloat(), input.readFloat()));
                        }
                        Vector3d vehicleAttachment = Vector3d.from(
                                input.readFloat(), input.readFloat(), input.readFloat());
                        poses.add(new PoseData(
                                width, height, eyeHeight, passengerAttachments, vehicleAttachment));
                    }
                    MovementKind kind = MovementKind.values()[movementKind];
                    EntityTypeData.VehicleData vehicle = kind == MovementKind.SERVER
                            || kind == MovementKind.MINECART ? null : new EntityTypeData.VehicleData(
                                    metadataIds[4], metadataIds[5], metadataIds[6],
                                    metadataIds[7], metadataIds[8]);
                    entities[index] = new EntityTypeData(
                            poses, kind, vehicle,
                            (flags & 1) != 0, (flags & 2) != 0, (flags & 8) != 0,
                            pistonReaction == 0,
                            defaultSharedFlags, (flags & 4) != 0, Pose.values()[defaultPose],
                            metadataIds[0], metadataIds[1], metadataIds[2], metadataIds[3],
                            defaultHealth,
                            input.readDouble(), input.readDouble(), input.readDouble(), input.readDouble(),
                            input.readDouble(), input.readDouble(), input.readDouble());
                }
                if (input.read() != -1) {
                    throw new IllegalStateException("Physics data has trailing bytes");
                }
                return new Decoder(
                        blocks, shapes, entities, leatherBootsItemId, elytraItemId,
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

    private static int[] readInts(DataInputStream input, int count) throws IOException {
        int[] values = new int[count];
        for (int index = 0; index < count; index++) {
            values[index] = input.readInt();
        }
        return values;
    }
}
