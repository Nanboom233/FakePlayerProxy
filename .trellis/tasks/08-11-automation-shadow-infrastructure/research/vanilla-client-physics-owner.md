# Research: Vanilla 26.2 Client Physics Ownership

- Query: In Minecraft Java 26.2 Vanilla, which classes own local-player physics decisions and the related packet flow?
- Scope: internal and external
- Date: 2026-08-13

## Source Baseline

- Local version manifest: `%APPDATA%/.minecraft/versions/26.2/26.2.json`. It identifies Minecraft `26.2` and client object `2dc72797acbc1b63fc16a11c4ac393605f453754`.
- Client artifact: `https://piston-data.mojang.com/v1/objects/2dc72797acbc1b63fc16a11c4ac393605f453754/client.jar`.
- Verified client SHA-1: `2dc72797acbc1b63fc16a11c4ac393605f453754`.
- The artifact uses readable Mojang class and member names. CFR 0.152 produced the cited Java text. Line numbers below refer to that fixed decompilation.
- Relevant project spec: `.trellis/spec/backend/velocity-plugin.md`. It pins Minecraft Java `26.2` and protocol `776`.

## Findings

### 1. No single class owns all player physics

Vanilla splits the client path across six owners:

| Owner | Responsibility |
| --- | --- |
| `LocalPlayer` | Drives the local-player tick, applies local input policy, and selects the movement packet form. |
| `LivingEntity` | Integrates player velocity through air, water, lava, gravity, drag, friction, effects, and entity pushing. |
| `Entity` | Resolves a requested movement against collision shapes. It derives collision and ground flags. |
| `ClientLevel` and `CollisionGetter` | Store the client world and entities. They supply block, entity, liquid, and world-border collision candidates. |
| `BlockState` and `Block` | Supply the state-dependent block collision shape, fluid state, friction, and block effects. |
| `VoxelShape` and `Shapes` | Perform geometric clipping against an `AABB`. |

The accurate high-level chain is:

```text
ClientLevel entity tick
  -> LocalPlayer.tick
  -> LivingEntity.tick
  -> LocalPlayer.aiStep
  -> LivingEntity.aiStep
  -> LivingEntity.travel
  -> Entity.move
  -> ClientLevel / CollisionGetter queries
  -> BlockState collision shapes and fluid states
  -> VoxelShape clipping
  -> Entity updates position, onGround, and horizontalCollision
  -> LocalPlayer selects and sends a movement packet
```

### 2. `ClientLevel` schedules the entity tick, but it does not calculate player movement

`ClientLevel.tickNonPassenger` records the old transform, increments `tickCount`, and calls `entity.tick()` (`ClientLevel.java:570-575`). It also ticks passengers through `rideTick()` (`ClientLevel.java:581-593`).

Therefore, `ClientLevel` owns the client-side entity collection and tick dispatch. It does not own the player movement formulas.

### 3. `LocalPlayer` drives the local-player frame and owns movement packet selection

`LocalPlayer.tick` first calls `super.tick()` (`LocalPlayer.java:340-345`). The superclass path reaches `LivingEntity.tick`, which calls `aiStep()` while the entity is not removed (`LivingEntity.java:2760-2795`). Java virtual dispatch returns to `LocalPlayer.aiStep`.

`LocalPlayer.aiStep` reads and mutates client input, sprint, crouch, jump, flight, and water-descent state (`LocalPlayer.java:830-951`). It then calls `super.aiStep()` at `LocalPlayer.java:951`. This enters the common living-entity movement path.

After the superclass tick returns, `LocalPlayer.tick` sends player input when it changed and then sends player or vehicle movement (`LocalPlayer.java:345-359`).

`LocalPlayer.sendPosition` owns the packet selection rules:

- `PosRot` when position and rotation changed (`LocalPlayer.java:386-387`).
- `Pos` when only position changed (`LocalPlayer.java:388-389`).
- `Rot` when only rotation changed (`LocalPlayer.java:390-391`).
- `StatusOnly` when only `onGround` or `horizontalCollision` changed (`LocalPlayer.java:392-394`).
- A position update is forced after 20 ticks even without a material position delta (`LocalPlayer.java:378-400`).

Every movement form includes the current `onGround` and `horizontalCollision` values. `LocalPlayer` reports these values. It does not calculate them.

### 4. `LivingEntity` owns velocity integration and medium selection

`LivingEntity.aiStep` applies input, processes jump state, creates the travel input vector, calls `travel`, applies block effects, and then calls `pushEntities` (`LivingEntity.java:2999-3107`). For an idle Shadow player, the input vector is zero. Existing velocity still goes through the same travel path.

`LivingEntity.travel` selects exactly one movement model (`LivingEntity.java:2466-2473`):

- fluid travel when the entity is in water or lava;
- fall-flying travel when Elytra flight is active;
- air and ground travel otherwise.

For air and ground travel, `travelInAir` reads the block below, derives block friction, applies relative movement, gravity or levitation, air drag, and vertical friction (`LivingEntity.java:2504-2519`). It calls `Entity.move` through the lower common movement helpers.

For water, `travelInWater` applies water movement efficiency, relative movement, collision movement, fluid drag, gravity adjustment, and the leave-fluid step (`LivingEntity.java:2539-2561`). Lava has a separate drag and gravity path (`LivingEntity.java:2568-2581`).

`LivingEntity.aiStep` also performs local entity pushing through `pushEntities` after travel (`LivingEntity.java:3101-3107`). The base push formula changes both pushable entities' horizontal velocity (`Entity.java:1960-1985`). Therefore, normal Vanilla behavior includes local entity-push calculation. Tracking entity coordinates without running this calculation is not Vanilla-equivalent.

### 5. `Entity.move` owns collision resolution and ground-state derivation

`Entity.move` accepts an intended displacement and resolves it with `collide` (`Entity.java:928-965`). It compares the intended and resolved components:

- X or Z mismatch sets `horizontalCollision` (`Entity.java:969-971`).
- Y mismatch sets `verticalCollision` (`Entity.java:972-975`).
- Downward Y collision sets `verticalCollisionBelow` (`Entity.java:975`).
- `verticalCollisionBelow` becomes `onGround` through `setOnGroundWithMovement` (`Entity.java:976`, `Entity.java:894-897`).

`Entity.collide` requests entity collision shapes from the level, resolves the bounding box against all shapes, and tests step-up candidates when horizontal movement is blocked (`Entity.java:1300-1325`).

`Entity.collectCollidersIgnoringWorldBorder` combines entity shapes, the world-border shape, and block collision shapes (`Entity.java:1361-1373`). The final displacement is clipped by the shape geometry. `VoxelShape.collide` performs axis clipping (`VoxelShape.java:253-269`).

This means `onGround` and `horizontalCollision` are outputs of the client collision solve. They are not copied from a normal server movement packet each tick.

### 6. `ClientLevel`, `CollisionGetter`, and `BlockState` answer world queries

`CollisionGetter.noCollision` combines block, entity, and border checks (`CollisionGetter.java:73-101`). It exposes entity collision lookup at `CollisionGetter.java:106` and creates block collision iteration at `CollisionGetter.java:120-129`.

`BlockCollisions` scans block positions around the tested `AABB`, reads each `BlockState`, gets its context-dependent collision shape, moves the shape into world coordinates, and tests intersection (`BlockCollisions.java:67-107`).

`BlockState` delegates `getCollisionShape` to its `Block` with the current world, position, and `CollisionContext` (`BlockBehaviour$BlockStateBase.java:380-388`). It also exposes the cached `FluidState` (`BlockBehaviour$BlockStateBase.java:558`). The shape can depend on block state, neighbors, world data, and entity context. A block protocol ID alone is not a complete general collision rule unless the implementation supplies equivalent state and context data.

`Entity.baseTick` updates fluid interaction before travel (`Entity.java:729-753`). `Entity.updateFluidInteraction` applies water or lava current to velocity (`Entity.java:1738-1758`). `LivingEntity.travel` then selects and runs the fluid movement formula. Fluid behavior is therefore split between `Entity` and `LivingEntity`, with fluid data supplied by the level and block state.

### 7. The server supplies attack and explosion impulses, then the client simulates them

For a received `ClientboundSetEntityMotionPacket`, `ClientPacketListener.handleSetEntityMotion` finds the entity and applies the packet movement through `lerpMotion` (`ClientPacketListener.java:973-980`). For the local player, the server has already decided the attack knockback vector.

For a received `ClientboundExplodePacket`, `ClientPacketListener.handleExplosion` adds `playerKnockback` to the local player's current velocity (`ClientPacketListener.java:1578-1585`). It does not recompute explosion exposure or knockback from blocks on the client.

After either packet updates velocity, the next local-player ticks run that velocity through `LivingEntity.travel`, `Entity.move`, world collision queries, and fluid interaction. `LocalPlayer.sendPosition` then reports the resulting position and flags.

The responsibility split is:

```text
Server:
  damage result
  attack knockback vector
  explosion player-knockback vector
  authoritative correction

Client:
  add or set received velocity
  apply gravity, drag, friction, fluid current, and effects each tick
  resolve blocks, entities, steps, and world border
  derive onGround and horizontalCollision
  send the resulting movement
```

The server also simulates and validates player movement. Client prediction is not the sole authority. However, a client that stops the local tick path does not produce normal post-hit movement. It waits for later server correction and can diverge from the normal protocol behavior.

## Design Consequences for Shadow

To reproduce the relevant Vanilla behavior, the Plugin cannot implement only a packet-state table.

It needs equivalents of these responsibilities:

1. A local-player tick driver equivalent to the relevant `LocalPlayer.tick` order.
2. A living-player integrator equivalent to the required `LivingEntity.travel` branches.
3. An `AABB` collision solver equivalent to the required `Entity.move` behavior.
4. A client-world query layer that supplies loaded block states, fluid states, entity collision boxes, and the world border.
5. Version-fixed block and entity physical data for Minecraft 26.2.
6. Movement packet selection that includes locally derived `onGround` and `horizontalCollision`.
7. Packet handlers that set attack motion and add explosion motion before the next physics tick.

The Plugin does not need rendering, particles, sound, or entity AI for this path. It does need local player-to-entity pushing if “normal movement” includes physical contact with nearby pushable entities.

## Files Found

- `%APPDATA%/.minecraft/versions/26.2/26.2.json` - fixed 26.2 download manifest.
- `client.jar!/net/minecraft/client/player/LocalPlayer.class` - local tick, local input, and movement packet selection.
- `client.jar!/net/minecraft/world/entity/LivingEntity.class` - travel, gravity, friction, fluids, and entity pushing.
- `client.jar!/net/minecraft/world/entity/Entity.class` - collision solve and collision flags.
- `client.jar!/net/minecraft/client/multiplayer/ClientLevel.class` - client entity tick dispatch and world ownership.
- `client.jar!/net/minecraft/world/level/CollisionGetter.class` - world collision query contract.
- `client.jar!/net/minecraft/world/level/BlockCollisions.class` - block collision candidate iterator.
- `client.jar!/net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase.class` - state-dependent block shape and fluid access.
- `client.jar!/net/minecraft/world/phys/shapes/VoxelShape.class` - shape-axis clipping.
- `client.jar!/net/minecraft/client/multiplayer/ClientPacketListener.class` - server motion and explosion impulse ingestion.

## External References

- Mojang 26.2 client artifact: `https://piston-data.mojang.com/v1/objects/2dc72797acbc1b63fc16a11c4ac393605f453754/client.jar`.
- CFR 0.152: `https://www.benf.org/other/cfr/`.

## Caveats / Not Found

- Mojang's 26.2 version manifest does not expose a separate `client_mappings` download. The client artifact already contains readable names.
- CFR could not fully structure one control-flow region in `LivingEntity.aiStep`. The ownership conclusion does not depend on that damaged branch. The surrounding calls to `travel` and `pushEntities` remain explicit.
- This research identifies the Vanilla owner and call chain. It does not authorize copying Mojang implementation code into the Plugin.
- This research does not define the complete Shadow physics scope. It establishes which responsibilities must exist for the selected behaviors to match Vanilla.
