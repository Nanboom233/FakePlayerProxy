package com.fakeplayerproxy.world.data;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.kyori.adventure.key.Key;

/** Immutable Minecraft 26.2 item defaults used by local interaction prediction. */
public record ItemData(
        Key registryKey,
        Set<Key> requiredFeatures,
        boolean baseUse,
        boolean blocksAttacks,
        boolean kineticWeapon,
        Optional<FoodData> food,
        Optional<Float> consumeSeconds,
        Optional<Key> cooldownGroup,
        Optional<ToolData> tool,
        Optional<AttackRangeData> attackRange) {

    public ItemData {
        requiredFeatures = Set.copyOf(requiredFeatures);
    }

    public record FoodData(boolean canAlwaysEat) {
    }

    public record ToolData(float defaultMiningSpeed, List<Rule> rules) {
        public ToolData {
            rules = List.copyOf(rules);
        }

        public record Rule(Set<Key> blocks, Key tag, Float speed, Boolean correctForDrops) {
            public Rule {
                blocks = Set.copyOf(blocks);
                if ((tag == null) == blocks.isEmpty()) {
                    throw new IllegalArgumentException("A tool rule must have one holder form");
                }
            }
        }
    }

    public record AttackRangeData(
            float minimum,
            float maximum,
            float creativeMinimum,
            float creativeMaximum,
            float hitboxMargin,
            float mobFactor) {
    }
}
