# Vanilla local prediction gap report

## Decision

The local predictor has no unknown result.

- A modeled Vanilla `InteractionResult.Success` returns `true`.
- A modeled non-success result returns `false`.
- An unmodeled Vanilla behavior also returns `false`.
- The implementation adds no `UNKNOWN` state or wrapper.

Packet output still follows the Vanilla request path. A block or entity request
can be sent before its local behavior returns `false`.

This decision does not predict backend plugin behavior. A backend plugin can
accept, reject, or change a request after local prediction.

## Evidence baseline

The Vanilla baseline is the mapped Minecraft 26.2 jar:

```text
.gradle/loom-cache/minecraftMaven/net/minecraft/
minecraft-merged-f8532f8966/26.2/
minecraft-merged-f8532f8966-26.2.jar
```

The inspected methods are:

- `Minecraft.startUseItem`
- `Minecraft.startAttack`
- `LocalPlayer.raycastHitResult`
- `MultiPlayerGameMode.useItemOn`
- `MultiPlayerGameMode.performUseItemOn`
- `MultiPlayerGameMode.useItem`
- `MultiPlayerGameMode.interact`
- `Entity.pick`

## Vanilla use result path

Vanilla blocks use while block destruction is active. It then applies a
four-tick right-click delay and checks whether vehicle input occupies the
hands.

Vanilla checks the main hand before the offhand. It rejects a hand when the
item requires a disabled feature.

For an entity hit, Vanilla checks the world border and the entity interaction
range. It sends an interaction request and runs `Player.interactOn` locally.
A local `Success` stops the hand loop.

For a block hit, Vanilla checks the world border. It then evaluates
`BlockState.useItemOn`, optional empty-hand use, and `ItemStack.useOn`.
A `Success` stops the hand loop. A `Fail` also stops generic item use for that
hand.

If target use does not stop the hand, Vanilla runs `ItemStack.use`. A local
`Success` stops the hand loop.

## Existing usable state

The repository already tracks these inputs:

- The player position, rotation, pose, eye height, and game mode.
- The player shift input and vehicle relation.
- Loaded chunk block states and block updates.
- Collision shapes for fixed block states.
- Entity IDs, types, positions, poses, and base bounds.
- The world border.
- A small equipment summary for movement and vehicle control.

These inputs provide a base for prediction. They do not complete any full
Vanilla use branch.

## Missing scheduler contract

`AutomationService` processes each scheduled action independently at
`AutomationService.java:345`. It cannot pass the use result to attack.

The action enum lists attack before use at `AutomationService.java:455`.
Current map iteration therefore does not provide Carpet use priority.

`Player.use` and `Player.attack` return only packet submission results at
`Player.java:202` and `Player.java:206`. They return no local action result.

The scheduler needs a boolean local result. It must run due use before due
attack. A successful attack must retry one failed use when both actions are
due.

## Missing raycast state

The world has no block or entity raycast. `World` only exposes entity lookup by
ID at `World.java:108`.

The current `AABB` supports movement overlap only. It has no segment clip,
inside test, closest-point result, or hit-face result.

Vanilla block picking uses the block outline shape. The fixed data contains
only collision shapes at `Block.java:8` and `Decoder.java:102`. Collision and
outline shapes are not interchangeable for all blocks.

Vanilla entity picking also needs these rules:

- The entity must satisfy `CAN_BE_PICKED`.
- The query expands the player bounds along the view vector.
- Each entity adds its pick radius.
- A block hit occludes a farther entity hit.
- The final hit must satisfy its separate interaction range.

`EntityTypeData` contains movement and geometry data. It has no pickability or
pick-radius data.

Minecraft 26.2 can also replace the normal raycast with an active item's
`ATTACK_RANGE` component. The repository does not track the active item or
this component.

## Missing interaction ranges

`LivingEntity.updateAttributes` reads movement attributes at
`LivingEntity.java:118`. It ignores `BLOCK_INTERACTION_RANGE` and
`ENTITY_INTERACTION_RANGE`.

The fixed entity defaults also omit both ranges. Local raycast and final range
checks need the effective values after modifiers.

## Missing player inventory

The repository has no player inventory owner. It does not store these values:

- Container-zero slots.
- The selected hotbar slot.
- The main-hand and offhand `ItemStack` values.
- The carried item and container state ID.
- The active use hand and active item.

`Player.selectHotbar` sends a packet at `Player.java:163`, but it does not
update local selected-slot state.

The plugin has no handlers for these available protocol packets:

- `ClientboundContainerSetContentPacket`
- `ClientboundContainerSetSlotPacket`
- `ClientboundSetHeldSlotPacket`
- `ClientboundSetPlayerInventoryPacket`
- `ClientboundSetCursorItemPacket`
- `ClientboundOpenScreenPacket`
- `ClientboundMountScreenOpenPacket`
- `ClientboundContainerClosePacket`

Without this state, the predictor cannot run the Vanilla hand loop.

## Missing effective item data

MCProtocolLib supplies the received item ID, amount, and data-component patch.
The patch does not contain unchanged default components.

The fixed resource generator reads `items.json` at `GenResources.kt:26`. It
retains only five named item IDs and harness IDs at `GenResources.kt:205`.

The predictor needs a fixed item table with the effective defaults used by the
approved local model. It stores base generic-use ownership, food and consumable
inputs, cooldown groups, tool rules, attack range, feature requirements,
`BLOCKS_ATTACKS`, and `KINETIC_WEAPON`.

The predictor must merge each received component patch over these defaults.

## Missing cooldown and player state

The plugin does not handle `ClientboundCooldownPacket`. It does not maintain
cooldown groups or decrement their remaining ticks.

The health handler stores only health at `FakePlayerProxyPlugin.java:428`.
Vanilla consumable checks also need food and saturation from the same packet.

The abilities handler stores only flight state at
`FakePlayerProxyPlugin.java:607`. Item behavior also needs the creative and
infinite-material state.

The plugin does not handle `ClientboundUpdateEnabledFeaturesPacket`. Vanilla
checks item and block feature requirements before local use.

Active use also needs use duration, release state, and server correction. The
current player state has none of these fields.

## Missing block behavior

The fixed `Block.Behavior` enum only describes movement effects at
`Block.java:26`.

The approved local predictor does not model `BlockState.useItemOn`,
`BlockState.useWithoutItem`, or the empty-hand transition.

The world discards all block-entity data except piston data at
`World.java:729`. Some Vanilla block interactions depend on block-entity
state.

An unmodeled block behavior returns `false` under the approved policy.

## Missing item behavior

The repository has no local equivalent for `ItemStack.useOn`, `ItemStack.use`,
or `ItemStack.interactLivingEntity`.

The approved model implements only the inherited base `Item.use` component
paths. Item subclass overrides remain unmodeled.

An unmodeled item behavior returns `false` under the approved policy.

## Missing entity behavior

The entity tracker retains only metadata used by movement. It does not retain
the wider entity state used by `Player.interactOn`.

The approved predictor sends the entity request but does not model entity
interaction behavior.

An unmodeled entity behavior returns `false` under the approved policy.

## Missing prediction sequence

Block use consumes a prediction sequence. `Player.use` currently hard-codes
sequence zero at `Player.java:206`.

The player needs one monotonic sequence for block use and block destruction.
It does not need acknowledgement state because requests do not mutate local
blocks.

The block-use packet needs the exact block position, face, cursor coordinates,
inside-block flag, world-border flag, hand, and sequence.

The entity-use packet needs the target ID, relative hit position, hand, and
shift state.

## Missing attack prediction

The same-tick retry rule also needs a local attack result. Current attack only
sends a swing at `Player.java:202`.

Entity attack uses `ServerboundAttackPacket(int entityId)`. The pinned
MCProtocolLib 26.2 jar contains this packet. `ServerboundInteractPacket` is the
separate entity interaction request used by mount and use.

Block attack needs active destruction state, the last target, the hit face,
destroy progress, and the post-break delay.

Destroy progress needs effective tool data, block hardness, block tags,
effects, game mode, and these attributes:

- `BLOCK_BREAK_SPEED`
- `MINING_EFFICIENCY`
- `SUBMERGED_MINING_SPEED`

The current fixed block data has no hardness or destroy-tool data. The current
attribute tracker ignores these attributes.

## Reset and correction gaps

Login, respawn, configuration entry, and connection close must reset inventory,
cooldowns, active use, destruction, and prediction sequence.

Server content, slot, held-slot, cooldown, metadata, and block packets replace
or correct local prediction.

## Dependency order

The minimum dependency order is:

1. Add fixed outline, item default, mining, and pickability data.
2. Add inventory, cooldown, feature, range, and active-use tracking.
3. Add the shared Vanilla raycast.
4. Add packet construction and the monotonic prediction sequence.
5. Add the boolean local use and attack results.
6. Change the scheduler to consume those results in Carpet order.

Behavior outside the approved fixed table returns `false` without another
result type.
