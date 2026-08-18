# Implementation architecture research

## Evidence

The implementation baseline is Minecraft 26.2 and Carpet commit
`21993f2585d6714ced9fc86b1f6d3721cf4c60ab`.

The local Minecraft evidence is the mapped jar under `.gradle/loom-cache`.
The protocol evidence is the MCProtocolLib code inside the pinned Velocity jar.
The Carpet evidence is its `PlayerCommand` and `EntityPlayerActionPack` source.

The pinned MCProtocolLib packet split is exact:

- `ServerboundAttackPacket(int entityId)` performs an entity attack.
- `ServerboundInteractPacket` performs a hand interaction at a relative point.
- `ServerboundUseItemOnPacket` performs block use with a sequence.
- `ServerboundPlayerActionPacket` performs block destruction and direct player
  inventory actions.

## Carpet command details

Carpet defines these exact direct values:

- `turn left` adds yaw `-90`.
- `turn right` adds yaw `90`.
- `turn back` adds yaw `180`.
- `drop mainhand` uses selected inventory slot `-1`.
- `drop offhand` uses inventory slot `40`.
- `drop all` visits valid command slots in descending order.

The proxy visits `40..0`. Carpet starts at `getContainerSize()`, which performs
one harmless out-of-range call before the valid slots.

The Carpet action order is `USE`, `ATTACK`, `JUMP`, `DROP_ITEM`, `DROP_STACK`,
and `SWAP_HANDS`.

An action with interval one runs its inactive step before its execution. A
one-shot action stays visible until the action loop ends. This lets a failed
one-shot use retry after a successful attack in the same tick.

## Scheduler representation

The existing fastutil `Pair<Integer, Integer>` is sufficient.

- Left `0` means one shot.
- Left `-1` means continuous.
- Positive left values mean interval.
- Right stores ticks until the next attempt.

One local `EnumSet` marks one-shot actions for removal after the action loop.
This preserves the Carpet retry rule without a scheduler record.

Input cleanup runs after the player movement step in the same proxy tick. This
lets a one-shot jump affect one movement calculation before its jump flag is
cleared.

## Container-zero inventory mapping

Minecraft 26.2 `InventoryMenu` defines these menu slots:

- `0` is the crafting result.
- `1..4` are the crafting grid.
- `5..8` are armor.
- `9..35` are main inventory.
- `36..44` are hotbar.
- `45` is offhand.

Carpet command slots map to menu slots as follows:

- `0..8` map to `36..44`.
- `9..35` keep the same number.
- `36..39` map to `8..5`.
- `40` maps to `45`.

Minecraft 26.2 can update this state through all of these packets:

- `ClientboundContainerSetContentPacket`
- `ClientboundContainerSetSlotPacket`
- `ClientboundSetPlayerInventoryPacket`
- `ClientboundSetCursorItemPacket`
- `ClientboundSetHeldSlotPacket`
- `ClientboundOpenScreenPacket`
- `ClientboundMountScreenOpenPacket`
- `ClientboundContainerClosePacket`

A container-zero throw click is invalid while a nonzero menu is current. The
proxy sends `ServerboundContainerClosePacket` once before the first throw and
then marks menu zero as current.

`ServerboundContainerClickPacket` accepts a null carried hash and an empty
changed-slot map. The server executes the click before it uses these values for
remote prediction. It then sends authoritative slot state. The proxy therefore
does not implement Minecraft's registry-aware `HashedStack` algorithm.

## Fixed item data

The fixed resource needs only data read by this task.

Each item entry stores these values:

- Registry key.
- Required feature keys.
- Whether `Item.use` is inherited from the base item implementation.
- Default `FOOD` data needed by `canEat`.
- Default `CONSUMABLE` duration.
- Default `USE_COOLDOWN` group.
- Default `TOOL` rules.
- Default `ATTACK_RANGE` values.
- Presence of `BLOCKS_ATTACKS`.
- Presence of `KINETIC_WEAPON`.

The generator identifies base generic use from the declaring method. It does
not maintain a hand-written item-name list.

The generator patch applies to the 26.2 source layout in
`Complexity-ML/minecraft-data-generator-26.2`. Its `ItemsDataGenerator`
already has the item registry, item components, default stack, and registry
key in `generateItem`. The required feature names come from
`FeatureFlags.REGISTRY.toNames`. The base-use flag comes from the declaring
class of the public `use` method.

The received data-component patch overrides these defaults. An explicit null
component removes the default. No `EffectiveItem` value type is needed.

## Local use prediction boundary

The packet path follows `Minecraft.startUseItem`.

1. Check block destruction and active use.
2. Check main hand before offhand.
3. Check required features and cooldown.
4. Send entity interaction for an entity hit.
5. Send block use for a block hit.
6. Send generic item use when the earlier modeled result did not succeed.

Entity behavior is not modeled. Block behavior is not modeled. Item subclass
overrides are not modeled. These paths return local failure after sending the
matching request.

The base `Item.use` implementation is modeled for these component paths:

- `CONSUMABLE` starts use when its food gate permits it.
- `BLOCKS_ATTACKS` starts use.
- `KINETIC_WEAPON` starts use.
- The absence of those components returns failure.

This boundary is deterministic from inventory, food, cooldown, feature, and
active-use state. It does not add approximate block, entity, or item behavior.

## Mining prediction

Survival destroy progress is:

```text
destroy speed / block hardness / divisor
```

The divisor is `30` with the correct tool and `100` otherwise.

Destroy speed applies these operations in order:

1. Read the selected item's tool speed.
2. Add `MINING_EFFICIENCY` when the base speed exceeds one.
3. Multiply by `1 + 0.2 * (dig speed amplifier + 1)` for haste or conduit.
4. Multiply by mining fatigue `0.3`, `0.09`, `0.0027`, or `0.00081`.
5. Multiply by `BLOCK_BREAK_SPEED`.
6. Multiply by `SUBMERGED_MINING_SPEED` while the eyes are in water.
7. Divide by five while airborne.

The world stores every received block tag. `ToolData.Rule` resolves either its
direct holder list or its tag through that map.

World-border, adventure, and spectator destruction return failure without a
destroy request. Creative and survival destruction are modeled. The proxy does
not predict a block removal. Server block updates remain authoritative.

The shared interaction sequence increases for block use and block destruction.
No pending block-change map is needed because the proxy does not mutate a block
from a request. A block acknowledgement therefore needs no state handler.

## Raycast and mount geometry

The fixed block entry adds an outline shape ID, hardness, and the correct-tool
requirement. The fixed entity entry adds pickability and pick radius.

`AABB` adds segment clipping and closest-point calculation. Segment clipping
returns the hit point and face as the existing fastutil `Pair`.

One sealed `InteractionHit` contract represents block and entity alternatives.
An empty `Optional` represents a miss.

Bare mount uses the Carpet search box. It inflates the player box by `(3, 1,
3)`. Coordinate mount searches every tracked qualifying rideable around its
selection point. Only coordinate mount performs the approved player-range
error check.

The rideable kinds are minecart, boat, horse, and camel. Equal distances use
the lower entity ID.

## Ownership result

The task needs three new production files:

- `world/data/ItemData.java`
- `world/player/PlayerInventory.java`
- `world/phys/InteractionHit.java`

It needs no new manager, action class, coordinate type, scheduler state type,
prediction result type, hash helper, or suggestion provider.

`PlayerInventory` owns inventory state and component resolution. `World` owns
spatial queries. `Player` owns protocol action state. `AutomationService` owns
Carpet scheduling. `PlayerCommand` owns target resolution and grammar.

## Resolved implementation limits

- Backend plugins can reject or alter every request.
- Dynamic block outline shapes can differ from the fixed isolated shape.
- Block entities and data packs can change interaction behavior.
- Entity metadata can change interaction behavior.
- Item subclass overrides can depend on untracked client state.

These limits affect the local boolean only. The proxy still sends the Vanilla
request path. The approved result for every unmodeled path is `false`.
