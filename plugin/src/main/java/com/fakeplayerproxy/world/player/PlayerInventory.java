package com.fakeplayerproxy.world.player;

import com.fakeplayerproxy.utils.Result;
import com.fakeplayerproxy.world.data.Decoder;
import com.fakeplayerproxy.world.data.ItemData;
import com.fakeplayerproxy.world.data.Block;
import com.fakeplayerproxy.world.world.World;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.Pair;
import java.util.Arrays;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.DropItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.Consumable;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentType;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.FoodProperties;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.UseCooldown;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.AttackRange;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.ToolData;
import net.kyori.adventure.key.Key;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundSetHeldSlotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerClosePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetSlotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundMountScreenOpenPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundOpenScreenPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundSetCursorItemPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundSetPlayerInventoryPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClosePacket;

/** Owns the Carpet-visible player inventory and container-zero projection. */
public final class PlayerInventory {
    private final ItemStack[] slots = new ItemStack[41];
    @Getter
    @Accessors(fluent = true)
    private ItemStack cursor;
    @Getter
    @Setter
    @Accessors(fluent = true)
    private int selectedSlot;
    @Getter
    @Accessors(fluent = true)
    private int stateId;
    @Getter
    @Accessors(fluent = true)
    private int openContainerId;

    //noinspection unused
    public ItemStack slot(int slot) {
        return slots[slot];
    }

    public ItemStack selected() {
        return slots[selectedSlot];
    }

    public ItemStack offhand() {
        return slots[40];
    }

    public ItemData fixed(ItemStack stack) {
        return Decoder.instance().item(stack.getId());
    }

    public boolean foodCanAlwaysEat(ItemStack stack) {
        FoodProperties patch = component(stack, DataComponentTypes.FOOD);
        if (hasPatch(stack, DataComponentTypes.FOOD)) {
            return patch != null && patch.isCanAlwaysEat();
        }
        return fixed(stack).food().map(ItemData.FoodData::canAlwaysEat).orElse(false);
    }

    public Float consumeSeconds(ItemStack stack) {
        Consumable patch = component(stack, DataComponentTypes.CONSUMABLE);
        if (hasPatch(stack, DataComponentTypes.CONSUMABLE)) {
            return patch == null ? null : patch.consumeSeconds();
        }
        return fixed(stack).consumeSeconds().orElse(null);
    }

    public Key cooldownGroup(ItemStack stack) {
        UseCooldown patch = component(stack, DataComponentTypes.USE_COOLDOWN);
        if (hasPatch(stack, DataComponentTypes.USE_COOLDOWN)) {
            return patch == null || patch.cooldownGroup() == null
                    ? fixed(stack).registryKey() : patch.cooldownGroup();
        }
        return fixed(stack).cooldownGroup().orElse(fixed(stack).registryKey());
    }

    public boolean blocksAttacks(ItemStack stack) {
        return effectivePresence(stack, DataComponentTypes.BLOCKS_ATTACKS, fixed(stack).blocksAttacks());
    }

    public boolean kineticWeapon(ItemStack stack) {
        return effectivePresence(stack, DataComponentTypes.KINETIC_WEAPON, fixed(stack).kineticWeapon());
    }

    public ItemData.AttackRangeData attackRange(ItemStack stack) {
        AttackRange patch = component(stack, DataComponentTypes.ATTACK_RANGE);
        if (hasPatch(stack, DataComponentTypes.ATTACK_RANGE)) {
            return patch == null ? null : new ItemData.AttackRangeData(
                    patch.minReach(), patch.maxReach(), patch.minCreativeReach(),
                    patch.maxCreativeReach(), patch.hitboxMargin(), patch.mobFactor());
        }
        return fixed(stack).attackRange().orElse(null);
    }

    @SuppressWarnings("PatternValidation")
    public Pair<Float, Boolean> tool(ItemStack stack, Block block, World world) {
        ToolData patch = component(stack, DataComponentTypes.TOOL);
        if (hasPatch(stack, DataComponentTypes.TOOL)) {
            if (patch == null) {
                return Pair.of(1.0f, false);
            }
            float speed = patch.getDefaultMiningSpeed();
            boolean correct = false;
            for (ToolData.Rule rule : patch.getRules()) {
                var holders = rule.getBlocks();
                var directHolders = holders.getHolders();
                boolean matches = holders.getLocation() != null
                        ? world.blockTagContains(holders.getLocation(), block.blockId())
                        : directHolders != null && directHolders.contains(block.blockId());
                if (matches) {
                    if (rule.getSpeed() != null) {
                        speed = rule.getSpeed();
                    }
                    if (rule.getCorrectForDrops() != null) {
                        correct = rule.getCorrectForDrops();
                    }
                }
            }
            return Pair.of(speed, correct);
        }
        ItemData.ToolData fixed = fixed(stack).tool().orElse(null);
        if (fixed == null) {
            return Pair.of(1.0f, false);
        }
        float speed = fixed.defaultMiningSpeed();
        boolean correct = false;
        String stateKey = block.stateKey();
        int propertiesStart = stateKey.indexOf('[');
        String blockKey = stateKey.substring(0, propertiesStart < 0 ? stateKey.length() : propertiesStart);
        for (ItemData.ToolData.Rule rule : fixed.rules()) {
            // IDEA's validation warning is a false positive. Decoder checks this generator-owned key.
            //noinspection PatternValidation
            boolean matches = rule.tag() != null
                    ? world.blockTagContains(rule.tag(), block.blockId())
                    : rule.blocks().contains(Key.key(blockKey));
            if (matches) {
                if (rule.speed() != null) {
                    speed = rule.speed();
                }
                if (rule.correctForDrops() != null) {
                    correct = rule.correctForDrops();
                }
            }
        }
        return Pair.of(speed, correct);
    }

    private static boolean effectivePresence(
            ItemStack stack, DataComponentType<?> type, boolean fixed) {
        return hasPatch(stack, type) ? component(stack, type) != null : fixed;
    }

    private static boolean hasPatch(ItemStack stack, DataComponentType<?> type) {
        return stack.getDataComponentsPatch() != null
                && stack.getDataComponentsPatch().getDataComponents().containsKey(type);
    }

    private static <T> T component(ItemStack stack, DataComponentType<T> type) {
        return stack.getDataComponentsPatch() == null ? null : stack.getDataComponentsPatch().get(type);
    }

    public void reset() {
        Arrays.fill(slots, null);
        cursor = null;
        selectedSlot = 0;
        stateId = 0;
        openContainerId = 0;
    }

    public void apply(ClientboundContainerSetContentPacket packet) {
        if (packet.getContainerId() != 0) {
            if (packet.getContainerId() == openContainerId) {
                stateId = packet.getStateId();
                cursor = packet.getCarriedItem();
            }
            return;
        }
        stateId = packet.getStateId();
        cursor = packet.getCarriedItem();
        Arrays.fill(slots, null);
        ItemStack[] items = packet.getItems();
        for (int menuSlot = 0; menuSlot < items.length; menuSlot++) {
            int inventorySlot = inventorySlot(menuSlot);
            if (inventorySlot >= 0) {
                slots[inventorySlot] = items[menuSlot];
            }
        }
    }

    public void apply(ClientboundContainerSetSlotPacket packet) {
        if (packet.getContainerId() == -1) {
            cursor = packet.getItem();
            return;
        }
        if (packet.getContainerId() == -2) {
            if (packet.getSlot() >= 0 && packet.getSlot() < slots.length) {
                slots[packet.getSlot()] = packet.getItem();
            }
            return;
        }
        if (packet.getContainerId() != 0) {
            if (packet.getContainerId() == openContainerId) {
                stateId = packet.getStateId();
            }
            return;
        }
        stateId = packet.getStateId();
        int inventorySlot = inventorySlot(packet.getSlot());
        if (inventorySlot >= 0) {
            slots[inventorySlot] = packet.getItem();
        }
    }

    public void apply(ClientboundSetPlayerInventoryPacket packet) {
        if (packet.getSlot() >= 0 && packet.getSlot() < slots.length) {
            slots[packet.getSlot()] = packet.getContents();
        }
    }

    public void apply(ClientboundSetCursorItemPacket packet) {
        cursor = packet.getContents();
    }

    public void apply(ClientboundSetHeldSlotPacket packet) {
        selectedSlot = packet.getSlot();
    }

    public void apply(ClientboundOpenScreenPacket packet) {
        openContainerId = packet.getContainerId();
        stateId = 0;
    }

    public void apply(ClientboundMountScreenOpenPacket packet) {
        openContainerId = packet.getContainerId();
        stateId = 0;
    }

    public void apply(ClientboundContainerClosePacket packet) {
        if (packet.getContainerId() == openContainerId) {
            openContainerId = 0;
            stateId = 0;
        }
    }

    public void swapHands() {
        ItemStack selected = slots[selectedSlot];
        slots[selectedSlot] = slots[40];
        slots[40] = selected;
    }

    public void closeMenu(MinecraftConnection backend) {
        if (openContainerId != 0) {
            backend.sendPacket(new ServerboundContainerClosePacket(openContainerId));
            openContainerId = 0;
            stateId = 0;
        }
    }

    public Result<Void, String> throwSlot(
            MinecraftConnection backend, int slot, boolean stack) {
        closeMenu(backend);
        int menuSlot = menuSlot(slot);
        backend.sendPacket(new ServerboundContainerClickPacket(
                0, stateId, menuSlot, ContainerActionType.DROP_ITEM,
                stack ? DropItemAction.DROP_SELECTED_STACK : DropItemAction.DROP_FROM_SELECTED,
                null, new Int2ObjectOpenHashMap<>()));
        ItemStack current = slots[slot];
        if (current != null) {
            int remaining = stack ? 0 : current.getAmount() - 1;
            slots[slot] = remaining <= 0 ? null : new ItemStack(
                    current.getId(), remaining, current.getDataComponentsPatch());
        }
        return new Result.Success<>(null);
    }

    private static int inventorySlot(int menuSlot) {
        if (menuSlot >= 36 && menuSlot <= 44) {
            return menuSlot - 36;
        }
        if (menuSlot >= 9 && menuSlot <= 35) {
            return menuSlot;
        }
        if (menuSlot >= 5 && menuSlot <= 8) {
            return 44 - menuSlot;
        }
        return menuSlot == 45 ? 40 : -1;
    }

    private static int menuSlot(int inventorySlot) {
        if (inventorySlot <= 8) {
            return inventorySlot + 36;
        }
        if (inventorySlot <= 35) {
            return inventorySlot;
        }
        if (inventorySlot <= 39) {
            return 44 - inventorySlot;
        }
        return 45;
    }
}
