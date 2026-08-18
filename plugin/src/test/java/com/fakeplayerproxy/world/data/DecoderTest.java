package com.fakeplayerproxy.world.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import net.kyori.adventure.key.Key;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.mcprotocollib.protocol.data.game.RegistryEntry;
import org.junit.jupiter.api.Test;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;

final class DecoderTest {
    @Test
    void decodesInteractionBlockItemAndEntityData() {
        Decoder data = Decoder.instance();
        Block stone = data.block(data.blockState("minecraft:stone"));

        assertEquals(1.5f, stone.destroySpeed());
        assertTrue(stone.requiresCorrectToolForDrops());
        assertEquals(1, data.outlineShape(stone.outlineShapeId()).length);
        assertEquals(Key.key("minecraft", "saddle"), data.item(data.saddleItemId()).registryKey());
        assertTrue(data.entity(EntityType.OAK_BOAT).pickable());
    }

    @Test
    void resolvesOnlyNullDimensionEntriesAndPreservesServerOrder() {
        Decoder data = Decoder.instance();
        RegistryEntry serverOverworld = new RegistryEntry(
                Key.key("minecraft", "overworld"),
                NbtMap.builder().putInt("min_y", 0).putInt("height", 16).build());
        RegistryEntry nullNether = new RegistryEntry(Key.key("minecraft", "the_nether"), null);

        List<RegistryEntry> resolved = data.completeDimensionTypes(
                Key.key("minecraft", "dimension_type"),
                List.of(serverOverworld, nullNether),
                true);

        assertSame(serverOverworld, resolved.get(0));
        assertEquals(Key.key("minecraft", "the_nether"), resolved.get(1).getId());
        NbtMap resolvedNether = assertInstanceOf(NbtMap.class, resolved.get(1).getData());
        assertEquals(NbtMap.builder().putInt("min_y", 0).putInt("height", 256).build(), resolvedNether);
    }
}
