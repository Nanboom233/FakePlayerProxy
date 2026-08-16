# Research: Minecraft Java 26.2 all local-player position factors

- Query: Exhaustively identify the vanilla Minecraft Java 26.2 factors that directly change, integrate, constrain, correct, attach, or otherwise affect the local player's position, velocity, rotation, pose, collision flags, and C2S movement reporting; compare them with the current Shadow task plan.
- Scope: mixed (official-named client distribution, matching official server distribution, protocol library integration, current task artifacts and code)
- Date: 2026-08-14

## Findings

### 1. Evidence, version identity, and source boundary

This report is pinned to Minecraft Java **26.2**, protocol **776**, data version **4903**. It does not generalize behavior from an older Yarn/wiki description where a 26.2 symbol was available.

Primary source material inspected:

| Source | Identity / URL | Use |
| --- | --- | --- |
| Mojang 26.2 version manifest | local launcher manifest `%APPDATA%/.minecraft/versions/26.2/26.2.json`; release time 2026-06-16 | version and official artifact identity |
| Official client JAR | SHA-1 `2dc72797acbc1b63fc16a11c4ac393605f453754`; `https://piston-data.mojang.com/v1/objects/2dc72797acbc1b63fc16a11c4ac393605f453754/client.jar` | authoritative official-named client/shared classes |
| Official server bundle | SHA-1 `823e2250d24b3ddac457a60c92a6a941943fcd6a`; `https://piston-data.mojang.com/v1/objects/823e2250d24b3ddac457a60c92a6a941943fcd6a/server.jar` | authoritative server movement validation, push, teleport, and sync comparison |
| Mojang official names | class names are present directly in the distributed 26.2 JAR; project `mod/build.gradle.kts:17-18` also records that 26.2 uses official names without a mappings artifact | symbols cited below |
| MCProtocolLib | `org.geysermc.mcprotocollib:protocol:26.2-20260809.160751-16` (`plugin/build.gradle.kts:12`) | concrete packet surface available to Shadow |
| Decompiled source | CFR 0.152, used only to render the exact official class files above | line references below; symbols remain independently checkable with `javap`/IDE |

The temporary decompilation tree was used for research only and is not a product dependency. Line numbers below refer to the decompiled 26.2 files and are paired with symbols so they remain reviewable if a different decompiler shifts lines.

### 2. Files found

| File | One-line description |
| --- | --- |
| `.trellis/tasks/08-11-automation-shadow-infrastructure/prd.md` | approved Shadow requirements and explicit exclusions |
| `.trellis/tasks/08-11-automation-shadow-infrastructure/design.md` | current state ownership, physics sequence, packet plan, entity table boundary |
| `.trellis/tasks/08-11-automation-shadow-infrastructure/implement.md` | completed implementation checklist and validation claim |
| `plugin/src/main/java/com/fakeplayerproxy/automation/AutomationService.java` | current player/protocol state, external force handling, 20 TPS tick and movement output |
| `plugin/src/main/java/com/fakeplayerproxy/automation/PlayerPhysics.java` | current fixed-size block-only, water-only zero-input physics |
| `plugin/src/main/java/com/fakeplayerproxy/automation/WorldState.java` | decoded chunk/block state store |
| `plugin/src/main/java/com/fakeplayerproxy/automation/EntityState.java` | passive server-fed entity table |
| `plugin/src/main/java/com/fakeplayerproxy/FakePlayerProxyPlugin.java` | current S2C/C2S listener registration |
| `net.minecraft.client.player.LocalPlayer` | client tick, input, abilities, auto-jump, riding and movement reporting |
| `net.minecraft.client.player.AbstractClientPlayer` | client-avatar wrapper; delegates movement behavior to `Player` |
| `net.minecraft.world.entity.player.Player` | abilities, pose selection/fit, platform-edge backoff, flight and player travel |
| `net.minecraft.world.entity.LivingEntity` | travel branches, effects, attributes, climb, fluids, fall flying, pushes |
| `net.minecraft.world.entity.Entity` | base tick, collision resolution, block effects, fluid current, passenger attachment, piston limits |
| `net.minecraft.client.multiplayer.ClientPacketListener` | all relevant S2C state/correction/relationship handlers |
| `net.minecraft.server.network.ServerGamePacketListenerImpl` | C2S player/vehicle validation, teleport gate and correction behavior |
| `net.minecraft.server.level.ServerEntity` | authoritative motion packet emission on `hurtMarked` |
| `net.minecraft.world.level.CollisionGetter` / `BlockCollisions` | block, entity and world-border collision collection; chunk access |
| special block classes (`WebBlock`, `PowderSnowBlock`, `HoneyBlock`, `BubbleColumnBlock`, etc.) | callbacks that mutate movement or pose-relevant state |

### 3. Vanilla tick and call order

#### 3.1 Client game tick and local-player movement

The effective client sequence is:

1. `Minecraft.tick()` increments the client tick counter and advances `TickRateManager` (`Minecraft:1628-1633`).
2. `ClientLevel.tickEntities()` ticks non-passenger entities; the local player is a `Player`, so tick freeze does **not** suppress it (`ClientLevel:423-431`; `TickRateManager.isEntityFrozen:66-67`). A passenger is instead ticked through its vehicle/root relationship.
3. `LocalPlayer.tick()` first refuses to run until `connection.hasClientLoaded()`, then calls `AbstractClientPlayer.tick()` -> `Player.tick()` -> `LivingEntity.tick()` -> `Entity.tick()/baseTick()` (`LocalPlayer:229-247`, `AbstractClientPlayer:51-53`, `Player:370-415`, `LivingEntity:2764+`, `Entity:732-810`).
4. `Entity.baseTick()` processes portal cooldown/transition, prior powder-snow flag, fluid height/eye flags and fluid current (`Entity:736-810`, `1741-1762`).
5. `LivingEntity.tick()` calls `aiStep()` while alive (`LivingEntity:2764+`). `Player.aiStep()` establishes movement speed from the synced `MOVEMENT_SPEED` attribute and delegates (`Player:557-578`). `LocalPlayer.aiStep()` performs keyboard/input-derived sprint, crouch, flight, swim descent, auto-jump and fall-flying transitions before delegating (`LocalPlayer:752-875`).
6. `LivingEntity.aiStep()` thresholds velocity, applies input, jump logic, selects `travelRidden` or normal `travel`, then runs `applyEffectsFromBlocks`, and on server runs freezing and entity push (`LivingEntity:3003-3178`; server equivalent `LivingEntity:2878-3053`).
7. `LivingEntity.travel()` chooses fluid, fall-flying, or air (`LivingEntity:2470-2477`). All branches ultimately call `Entity.move(MoverType.SELF, delta)`.
8. `Entity.move()` applies stuck multiplier, crouch/platform-edge backoff, block/entity/world-border collision and step candidates; writes position and collision flags; handles bounce/restitution; invokes movement/step effects; applies block speed factor (`Entity:931-1035`, `1296-1398`).
9. `Player.tick()` later runs `updatePlayerPose`: desired pose is sleep/swim/fall-flying/spin/crouch/stand, constrained by collision checks that include blocks **and entities** (`Player:470-499`). `LocalPlayer.aiStep()` also determines `crouching` before input based on the standing/crouching fit checks (`LocalPlayer:765`).
10. Returning to `LocalPlayer.tick()`, changed input sends `ServerboundPlayerInputPacket`; if passenger, it sends player rotation and possibly root `ServerboundMoveVehiclePacket`; otherwise it calls `sendPosition()` (`LocalPlayer:229-247`).
11. `sendPosition()` sends sprint command if changed, then selects PosRot/Pos/Rot/StatusOnly based on baseline deltas. Position threshold is squared distance `> (2e-4)^2`, with a 20-tick position reminder. `onGround` and `horizontalCollision` are always packet flags and can alone trigger StatusOnly (`LocalPlayer:263-297`).
12. `Minecraft.tick()` sends `ServerboundClientTickEndPacket` after level/entity processing whenever connected and not client-paused (`Minecraft:1698-1707`).

Important ordering consequence: current `PlayerPhysics.tick` adds water current before moving, moves, then performs a simplified drag/gravity operation (`PlayerPhysics:26-78`). Vanilla instead updates base fluid interaction in base tick; then `travel` moves using the current velocity; `Entity.move` may mutate velocity through collision restitution/block speed; block callbacks run after travel; and branch-specific gravity/drag is applied at the positions shown in `travelInAir/Water/Lava/FallFlying`. A generic `move -> friction -> gravity -> drag` description is insufficient for non-air branches and special blocks.

#### 3.2 Server receipt and correction

`ServerGamePacketListenerImpl.handleMovePlayer` (`1068-1181`) performs:

1. finite/NaN validation and coordinate clamping;
2. Player Loaded and pending teleport gate;
3. passenger special case (rotation accepted, position ignored);
4. sleeping special case;
5. rate-aware moved-too-quickly validation when server tick runs normally;
6. server-side `player.move(MoverType.PLAYER, client delta)` and collision comparison;
7. moved-wrongly/new-collision correction via `ClientboundPlayerPositionPacket`;
8. snap to client target; compute floating state;
9. copy packet `onGround` and `horizontalCollision` into server player, perform fall checks and known-movement bookkeeping.

`teleport(PositionMoveRotation, Set<Relative>)` increments the teleport ID, applies the destination on the server, records `awaitingPositionFromClient`, and sends `ClientboundPlayerPositionPacket` (`1217-1229`). While awaiting acknowledgement, movement position is not accepted; the server resends after 20 server ticks (`1194-1204`). Therefore acknowledgement plus the immediate client PosRot response is protocol-critical, not merely a baseline optimization.

Vehicle movement is a separate server path: `handleMoveVehicle` validates and moves the **root vehicle**, checks that the local player is controlling it and it matches `lastVehicle`, corrects with `ClientboundMoveVehiclePacket`, and updates vehicle ground/fall state (`467-537`). It is not reducible to player movement.

### 4. State fields a truthful Shadow position model needs

The following state is directly read by the vanilla chains above. `B` = basic correct location, `C` = conditionally required when the corresponding server state/environment occurs, `F` = feature-related for deliberately active controls, `X` = safely excludable only under an explicit invariant.

| State group | Fields / source | Need | Current plan |
| --- | --- | --- | --- |
| transform | `position`, old/last-good/baseline position; `yRot`, `xRot`, old rotations | B | included, but old/interpolation values mostly omitted |
| velocity | `deltaMovement`, last known/client movement, impulse grace context | B/C | packet motion + explosion included; impulse context omitted |
| collision | bounding box, `onGround`, `horizontalCollision`, `verticalCollision`, `verticalCollisionBelow`, `minorHorizontalCollision`, supporting block | B | only first two retained; supporting/minor/vertical state omitted |
| movement reporting | last sent XYZ/YRot/XRot/onGround/horizontalCollision; `positionReminder`; controlled camera | B | included except controlled-camera invariant is implicit |
| life | health, alive/dead/dying, death tick/removal | B | health/dead included; death pose/tick removal excluded |
| pose/size | `Pose`, scaled `EntityDimensions`, eye height, crouching/swimming/fall-flying/spin/sleep flags, fit result | C | pose metadata plus hard-coded unscaled box; dynamic selection/fit omitted |
| attributes | movement speed, gravity, jump strength, step height, scale, water movement efficiency, movement efficiency, air drag modifier, friction modifier, bounciness, safe fall distance/fall damage multiplier, flying speed | C/F | omitted; hard-coded gravity, step, drag, scale |
| abilities | flying, mayfly, walking/flying speed, spectator/noPhysics | C/F | omitted |
| effects | levitation, slow falling, dolphins grace; speed/slowness/jump boost through attributes; other movement/pose effects | C | omitted |
| equipment/enchantments | glider availability, powder-snow walking/leather boots, soul speed, depth strider/water efficiency and attribute modifiers, item-use slowdown | C/F | omitted |
| inputs | seven `Input` booleans, input impulses, jumping, shift/crouch, sprint state, auto-jump counter, jump cooldown | F; zero values are B invariant | zero packet and stop sprint included; local persistent sprint/pose state not fully modeled |
| fluid | per-tag height, eye-in-water, prior water/lava, flowing vector, shallow/deep state, falling fluid, fluid push eligibility | C | approximate water only; lava/eye/shallow omitted |
| block effects | stuck multiplier, in-block state, powder-snow prior/current, bubble column, block speed factor, restitution, portal processor/cooldown | C | omitted |
| relationships | `vehicle`, passengers, root vehicle, controlling passenger, attachment points, boarding cooldown, removed former vehicle ID | C | entity table does not model relationships; explicitly excluded vehicle physics |
| entity collision | collidable entity AABBs/shapes and predicates | C | explicitly excluded; entity table is not consumed |
| border/world | world border shape/state; min/max Y; chunk known/ticking state; block/fluid registry/tag semantics | B/C | min/height/chunks included; border and tags omitted |
| timing | client scheduling, server tick rate/frozen/step state, reminder counter | B/C | fixed 20 TPS only; ticking state packets omitted |
| transition | dimension, configuration/game, Player Loaded, pending teleport ID, respawn/death | B | mostly included; auto respawn excluded; dimension transition relation states omitted |

### 5. Factor inventory and Shadow classification

Columns: **Owner** identifies vanilla authority; **Entry** is packet/event/local state; **Self** says whether local player transform/velocity/pose/flags are mutated; **C2S** is required output; **Need** uses B/C/F/X above; **Plan** is Included / Explicitly excluded / Missing.

#### 5.1 Core tick, input, locomotion and abilities

| Factor | Owner | Entry | Key symbols | Self | C2S | Need | Plan |
| --- | --- | --- | --- | --- | --- | --- | --- |
| local tick gate/order | client | `hasClientLoaded`, game tick | `LocalPlayer.tick:229`, `Minecraft.tick:1628` | yes, integrates all | TickEnd plus movement | B | partially included; precise vanilla ordering missing |
| keyboard input | client | `KeyboardInput.tick`, key states | `LocalPlayer.aiStep:752-875`, `KeyboardInput` | input -> velocity/pose | Input; possibly command/move | F; zero invariant B | active controls excluded; zero input included |
| auto-jump | client | option, prior horizontal move | `LocalPlayer.move:938`, `updateAutoJump:957`, `canAutoJump:1071` | schedules jump | Input then move | F | explicitly active movement excluded |
| ordinary travel | shared/client authoritative | zero or active input | `LivingEntity.travel:2470`, `travelInAir:2508` | yes | MovePlayer | B | simplified included |
| jump | shared/client and server validation | jump input / rising off ground | `jumpFromGround:2425`; server `handleMovePlayer:1136` | velocity Y | Input + MovePlayer | F; external forced jump C | active jump excluded; jump strength missing |
| crouch / platform-edge backoff | client/shared | shift, pose fit | `Player.maybeBackOffFromEdge:947-984`; `LocalPlayer:765` | constrains X/Z and pose | Input + move/status | F; existing crouch state C | active sneak excluded; inherited state not modeled |
| sprint | client/shared | input, food, collision, water | `LocalPlayer:780-887`; `LivingEntity.setSprinting:2357` | movement-speed modifier | PlayerCommand + move | F/C | stop sent; state/attribute effect missing |
| creative/spectator flight | client/shared | PlayerAbilities + input | `ClientPacketListener.handlePlayerAbilities:1943`; `Player.travel:1377-1410`; `LocalPlayer:807-875` | yes, gravity/vertical control/noPhysics | PlayerAbilities/Input/Move | C when server sets flying/spectator | missing |
| swimming | shared/client | water/pose/sprint | `Player.updatePlayerPose:470-499`, `LivingEntity.travelInWater:2531` | pose/drag/velocity | input/move/status | C | water partial; pose transition missing |
| climbing/scaffolding | shared | block/tag, collision, shift | `handleOnClimbable:2710`; `LivingEntity:2696` | clamps XYZ, may set Y=.2 | move | C | missing/special blocks excluded |
| fall flying / spin attack | shared/client | metadata/equipment/command | `travelFallFlying:2604-2649`, `canGlide`, `LocalPlayer:833-839` | yes, rotation-coupled velocity | command + move | C if already active; F to start | explicitly excluded but not paused/resynced |
| item-use slowdown | client | use state and item | `LocalPlayer.aiStep:780`, input scaling in client input | velocity input | move | F | use exists but movement state omitted |

#### 5.2 Gravity, drag, friction, collision and chunks

| Factor | Owner | Entry | Key symbols | Self | C2S | Need | Plan |
| --- | --- | --- | --- | --- | --- | --- | --- |
| gravity | shared, synced attribute | `GRAVITY`, slow falling | `getEffectiveGravity:2461`; `Attributes.GRAVITY` | velocity Y | move | B/C | hard-coded .08; attribute missing |
| air drag | shared, synced attribute | `AIR_DRAG_MODIFIER` | `travelInAir:2508-2523` | velocity XYZ | move | B/C | hard-coded .91/.98 |
| ground friction | shared block + attribute | block below, `FRICTION_MODIFIER` | `travelInAir:2509-2522` | velocity XZ | move | B/C | block friction included; modifier missing |
| movement efficiency / block speed factor | shared | block + attribute/equipment | `Entity.move:997-999`; `Player.getBlockSpeedFactor:1772` | velocity XZ | move | C | missing |
| block collision | shared, client authoritative | current/expanded AABB | `Entity.collide:1303`; `collideBoundingBox:1349` | position + flags | move/status | B | included approximately |
| axis order | shared | movement component magnitudes | `Entity.collideWithShapes:1388-1398` | position | move | B | included Y then smaller-horizontal-first |
| step candidates | shared + attribute | horizontal collision, ground, `STEP_HEIGHT` | `Entity.collide:1312-1327` | position/flags | move | B/C | one simplified fixed 0.6 candidate; vanilla candidate heights missing |
| entity collision shapes | shared/client | nearby entities + predicates | `Entity.collide:1307`, `CollisionGetter.getEntityCollisions` | constrains position | move/status | C | explicitly excluded |
| world border | shared/client | border init/update packets | `collectAllColliders:1366-1376` | constrains position | move/status | C | missing |
| platform-edge backoff | player/client | shift and safe-fall/step | `Player.maybeBackOffFromEdge:947-984` | constrains horizontal move | move | F/C | excluded with sneak |
| bounce/restitution | shared block + attribute | collision, bounciness, suppress tags | `Entity.restituteMovementAfterCollisions:1002-1035` | velocity | move | C | missing |
| block step/fall/inside callbacks | shared/client or server | post-move block scan | `Entity.applyEffectsFromBlocks:1089-1178`, `1450-1516` | often velocity/flags | move, or later authoritative motion | C | explicitly special blocks excluded |
| unknown/unloaded chunk | client/shared | `hasChunkAt`, collision chunk cache | `travelInAir:2514`; `BlockCollisions.getChunkForCollisions` | special gravity fallback / missing collision | move | B | task pauses unknown query, conservative and truthful; not vanilla fallback-equivalent |
| collision caches | client | `BlockState` cached/dynamic shape, chunk collision getter | `BlockBehaviour.BlockStateBase.getCollisionShape`; `BlockCollisions` | constrains position | move | B | compact static shape; dynamic/context shapes caveat |

#### 5.3 Fluids

| Factor | Owner | Entry | Key symbols | Self | C2S | Need | Plan |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| fluid intersection/height/eye | shared | block fluid state | `Entity.baseTick:752-756`, `updateFluidInteraction:1741` | flags and later branch | move/status | C | approximate body water; eye/lava omitted |
| fluid current | shared/client | flow vector and tag | `EntityFluidInteraction.applyCurrentTo`, `Entity:1754-1760` | additive velocity, .014 water | move | C | water approximate included |
| water travel | shared | in water, sprint/effects/attribute | `travelInWater:2531-2566` | drag, gravity, jump-out | move | C | simplified .8/.02 only |
| lava travel | shared | lava height/shallow state | `travelInLava:2568-2586` | drag/gravity | move | C | missing |
| stand-on-fluid | shared | equipment/entity rule | `shouldTravelInFluid:2484`, `canStandOnFluid` | changes whole branch | move | C | missing |
| bubble column | shared block callback | bubble column state | `BubbleColumnBlock.entityInside:106-115`; `Entity:2911-2945` | velocity Y | move | C | explicitly special blocks excluded |
| swimming descent/float | client/shared | shift, passenger, type | `LocalPlayer:842-875`; `floatInWaterWhileRidden:2588` | velocity Y | input/move | F/C | missing |

#### 5.4 Forces, damage and push

| Factor | Owner | Entry | Key symbols | Self | C2S | Need | Plan |
| --- | --- | --- | --- | --- | --- | --- | --- |
| SetEntityMotion | server -> client | `ClientboundSetEntityMotionPacket` | client handler `636-642`; `ServerEntity:218-220` | **replaces** velocity | subsequent move | B | included |
| explosion knockback | server -> client | `ClientboundExplodePacket.playerKnockback` | client `handleExplosion:1276`; `addDeltaMovement` | **adds** velocity | subsequent move | B | included |
| damage knockback | server calculation -> motion packet | damage/hurt/knockback | server `LivingEntity.knockback:1535-1546`; `ServerEntity:218-220` | replacement packet after server mutation | subsequent move | B | covered only insofar as every self motion packet is observed |
| other impulses (mace, sonic boom, riptide, mob attacks, fishing, projectile) | mostly server | gameplay event, then motion sync/correction | server call sites to `push`/`knockback`; `hurtMarked` sync | additive server-side then usually motion sync | subsequent move | C | not named; generic motion listener can cover synchronized cases |
| entity push/collision response | server for `LivingEntity.pushEntities`; some client-authoritative vehicles | nearby pushable entities | `LivingEntity.pushEntities:3007-3025`, `doPush:3052`; `Entity.push:1963-2003` | adds XZ velocity | subsequent move/motion correction | C | explicitly excluded; passive entity table is insufficient |
| cramming | server-only damage rule | pushable count + gamerule/random | `LivingEntity.pushEntities:3007-3024` | health, not direct client physics; resulting death/knockback only via packets | no special position C2S | X for client motion model; health remains server-owned | can exclude local calculation; health packet still required |
| collision predicate | shared/server/client | `isPushable`, `canCollideWith`, teams/passenger relation | `Entity.isPushable/canCollideWith`, vehicle overrides | decides collision/push | indirect move | C | missing |

Entity table tracking, collision shapes, push and vehicle physics are four different concerns:

1. **Entity table position tracking** only records server observations.
2. **Entity relation tracking** establishes passenger/root/controller graphs and affects which entity owns movement.
3. **Entity collision/push** consumes types, dimensions, predicates and nearby AABBs to constrain or add velocity.
4. **Vehicle physics** runs the specific vehicle's client-authoritative tick and reports the root vehicle.

The current entity table implements only (1).

#### 5.5 Vehicles and attachments

| Factor | Owner | Entry | Key symbols | Self | C2S | Need | Plan |
| --- | --- | --- | --- | --- | --- | --- | --- |
| passenger graph | server -> client relation | SetPassengers | `handleSetEntityPassengersPacket:1097-1124` | attaches player and changes pose/ground | Rot + Input, perhaps MoveVehicle | C | missing; SetPassengers not listened |
| passenger positioning | shared/client | vehicle tick/interpolation | `Entity.positionRider:2456-2486`, attachment points | directly sets player position | player Rot; vehicle report | C | missing |
| root/controlled vehicle | client | relationship + authority predicate | `LocalPlayer.tick:239-246`; `Entity.getRootVehicle/getControlledVehicle` | selects reported transform owner | MoveVehicle | C | explicitly vehicle physics excluded |
| vehicle correction | server -> client | MoveVehicle / entity teleport | `ClientPacketListener.handleMoveVehicle:1997`; teleport former-player-vehicle branch `683-702` | vehicle then passenger position | immediate MoveVehicle echo in relevant cases | C | missing |
| vehicle input/control | client/shared | Input, vehicle type | `LocalPlayer.rideTick:922-932`, vehicle `tickRidden` | vehicle velocity/rotation | Input/MoveVehicle/special jump packets | F/C | explicitly excluded |
| dismount | server relation + client input | shift input / SetPassengers | `Player.rideTick:549`, `DismountHelper`, `stopRiding` | relocates player to safe dismount position | Input; server relation/correction | C for forced dismount; F voluntary | only shift pulse command; state-aware behavior deferred |

A minimal non-vehicle Shadow must detect the local player becoming a passenger and **pause ordinary PlayerPhysics/MovePlayer position output** until it can either implement the relation/vehicle branch or wait for a server correction/dismount. Continuing ordinary free-body physics while mounted is false behavior.

#### 5.6 Special blocks, portals, beds and piston movement

| Branch | Owner / entry | Key effect and symbol | Self | C2S | Need | Plan |
| --- | --- | --- | --- | --- | --- | --- |
| cobweb | shared, inside callback | `WebBlock.entityInside:39-46` sets stuck multiplier | velocity next move | move | C | excluded |
| sweet berry bush | shared/server | `SweetBerryBushBlock.entityInside:126-145`, stuck multiplier + server damage | velocity/health | move | C | excluded |
| powder snow / freezing | shared/server | `PowderSnowBlock:108-153`; `LivingEntity:692-701`, `3095-3101` | stuck, speed attribute, flags; damage server-owned | move | C | excluded |
| honey | shared | `HoneyBlock.entityInside:91-140` slide changes Y and sometimes XZ; fall factor | velocity | move | C | excluded |
| soul sand / soul speed | shared + equipment/enchantment | block speed factor/attribute modifier | velocity XZ | move | C | excluded/missing |
| slime/bed bounce | shared collision callbacks | `SlimeBlock.fallOn/stepOn:34-46`; `BedBlock.fallOn:178` | velocity and fall | move | C | excluded |
| scaffolding/ladder/vines | shared tag/shape | `handleOnClimbable:2710`; collision/shift rules | velocity/pose | move | C | excluded |
| bubble column | shared | above/inside callbacks | velocity Y | move | C | excluded |
| portal | server transition; client visual processor | `Entity.handlePortal:2668-2689`; `NetherPortalBlock.entityInside:157` | server ultimately teleports/dimension switches; relative velocity/rotation may be supplied | teleport ack + PosRot | C | dimension/position packets included, local portal state omitted |
| bed/sleep | server metadata/state | `Player.tick:370-390`; `LivingEntity.startSleeping/stopSleeping:3696-3732`; server move handler sleeping branch | pose/rotation/attachment and wake position | movement mostly ignored while sleeping | C | missing beyond pose metadata |
| piston | shared/server moving block | `Entity.limitPistonMovement:1261-1289`; MovingPiston calls `move(PISTON)` | directly changes position with per-axis .51/tick cap | later move/correction | C | explicitly excluded |
| block replacement pushes | server/shared | `Block.pushEntitiesUp` call sites | position | correction/motion | C | missing |

Because each branch can occur while input is all false, these are not all “active movement features.” They are environment-triggered. Truthful exclusion requires pause/resync when the necessary block semantic is detected; merely treating these blocks as ordinary collision/air can cause persistent divergence.

#### 5.7 Attributes, effects, equipment and pose

| Factor | Owner / entry | Key symbols | Self | C2S | Need | Plan |
| --- | --- | --- | --- | --- | --- | --- |
| UpdateAttributes | server -> client | `ClientPacketListener.handleUpdateAttributes:2166-2190`; `Attributes` definitions | changes all derived physics | move | C | packet and state omitted |
| gravity | synced attribute | default .08, range -1..1 | velocity Y | move | B/C | hard-coded |
| step height | synced attribute | default .6; `LivingEntity.maxUpStep:3906` | collision position | move | C | hard-coded |
| scale | synced attribute | `LivingEntity.onAttributeUpdated:1269`, dimensions scale `3648` | AABB/eye/attachments | move/status | C | hard-coded size |
| air/friction modifiers | synced attributes | `travelInAir:2508` | velocity | move | C | omitted |
| water movement efficiency | synced attribute | `travelInWater:2537` | water drag/speed | move | C | omitted |
| bounciness | synced attribute + blocks | `Entity.restituteMovementAfterCollisions` | velocity | move | C | omitted |
| movement speed/flying speed | synced attrs/abilities | `Player.aiStep:575`, `getFlyingSpeed` | input acceleration; zero input only matters for some branches | move | F/C | omitted |
| jump/safe fall/fall multiplier | synced attributes/effects | `jumpFromGround`, server fall damage | position only when jumping/bounce; health server-owned | move | C/F | omitted |
| Mob effects | server -> client Update/RemoveMobEffect | levitation directly replaces gravity path; slow falling caps gravity; dolphins grace changes water drag | velocity | move | C | omitted |
| equipment/enchantments | SetEquipment/inventory packets | glider, leather powder-snow walking, modifiers, location effects | branch/velocity/AABB | move | C | omitted |
| PlayerAbilities | server -> client | `handlePlayerAbilities:1943-1952` | flight/no-gravity semantics | abilities/input/move | C | omitted |
| pose metadata | server data plus local recomputation | `Player.updatePlayerPose:470-499`; `LivingEntity.getDimensions:3648` | AABB and eye | status/move | C | packet pose retained; fit/recompute and scale missing |
| look-at / rotation correction | server -> client | `handleLookAt:1575`; `handleRotatePlayer:840+` | rotation | Rot immediately/next tick | C | PlayerRotation included; PlayerLookAt missing |

The current hard-coded pose table in `PlayerPhysics.playerBox:285-307` is correct only at scale 1 and does not model the local pose fit fallback. `canPlayerFitWithinBlocksAndEntitiesWhen` includes entity collisions, so block-only fit is also incomplete.

#### 5.8 Death, respawn, dimension and timing

| Factor | Owner / entry | Key behavior | Self | C2S | Need | Plan |
| --- | --- | --- | --- | --- | --- | --- |
| death | server health/data | local death pose/tick; server authoritative health | pose/removal; stops normal movement | TickEnd continues | B | movement stop included; only SetHealth considered authoritative |
| respawn | server Respawn + new position | client creates/rebinds LocalPlayer, may preserve selected state, resets level | all state | PlayerLoaded, teleport ack/move; optional respawn request | B | reset included; automatic respawn excluded |
| dimension switch | server Respawn + configuration/world packets | clears world/entity relationships and supplies new position | all spatial context | same as respawn | B | included at coarse level |
| frozen server tick | S2C TickingState/TickingStep | client `Minecraft.tick` continues; Player is exempt from entity freeze, but server movement checks differ and world/border do not advance | scheduling/context | TickEnd and player tick still occur | C | packets omitted; fixed 20 TPS wall-clock differs at non-20 tick rate |
| tick rate | S2C TickingState | render/game timer target uses `max(default 50ms, server millisecondsPerTick)` (`Minecraft:2701-2702`), so rates below 20 slow client game ticks; rates above 20 do not make normal client exceed 20 | integration frequency | TickEnd per client tick | C | fixed 50ms only |
| local pause | client UI only | multiplayer still sends ticks unless actual client pause applies | timing | TickEnd | X for headless | can exclude |

### 6. S2C/C2S packet matrix

#### 6.1 S2C packets that directly or conditionally affect the model

| S2C packet / event | Vanilla action | Required Shadow reaction | Current |
| --- | --- | --- | --- |
| Login | entity ID, dimension/spawn info, game mode | initialize/reset | included |
| Respawn | replace level/player context | clear spatial/relationship/physics state; await position | included |
| PlayerPosition | relative/absolute position, delta velocity and rotation correction | apply every `Relative` flag; send AcceptTeleportation and exact PosRot | included |
| PlayerRotation | relative rotation correction | update rotation; vanilla immediately sends Rot with false flags | included but response semantics differ/implicit |
| PlayerLookAt | computes rotation from anchor/target | update yaw/pitch and report rotation | missing |
| SetEntityMotion(self) | replace velocity | replace velocity | included |
| Explode | add optional player knockback | add velocity | included |
| SetEntityData(self) | pose/shared flags (sprint/swim/fall fly/spin/etc.) | decode relevant metadata and refresh dimensions/branch | pose only included |
| UpdateAttributes(self) | replace bases/modifiers | track physics attributes and refresh dimensions on scale | missing |
| Update/RemoveMobEffect(self) | effects and attribute modifiers | track movement-relevant effects | missing |
| PlayerAbilities | flying/mayfly/speeds/noPhysics context | track abilities | missing |
| SetEquipment / inventory slot/content | glider, boots, enchant/equipment modifiers | track movement-relevant equipment or pause affected branches | missing |
| DamageEvent / EntityEvent | animation/context; may signal state transitions | normally no direct velocity if motion packet follows; relevant special events require audit | missing; conditionally excludable |
| Add/Move/Sync/Teleport/Remove entity | entity observations | entity table | included |
| SetPassengers | relation graph | attach/detach, root/controller, pause or vehicle model | missing |
| MoveVehicle | correct client-authoritative root vehicle | update vehicle and rider; immediate MoveVehicle echo where vanilla does | missing |
| SetEntityMotion(vehicle) / entity sync | vehicle state | vehicle model if mounted | table only |
| Initialize/Update world border | collision shape | track collision boundary | missing |
| Chunk/Forget/Block/Section update | block/fluid collision world | update atomically; unknown is not air | included |
| Registry Data / tags | block/fluid/entity semantics | retain identifiers/tags needed by physics | registry size only; semantic tags largely missing |
| TickingState / TickingStep | tick rate/freeze | schedule accurately or document fixed-rate divergence | missing |
| SetHealth | death state | server-authoritative alive/dead | included |
| GameEvent | chunks load start; game mode/abilities-related notifications | track relevant game events, not only load start | only load start included |

#### 6.2 C2S packets selected by local state

| C2S packet | Vanilla trigger | Model dependency | Current |
| --- | --- | --- | --- |
| PlayerInput | any of seven input booleans changed | last sent input | zero sent on shadow; later scheduled actions send pulses |
| PlayerCommand START/STOP_SPRINTING | sprint state changed | local sprint state | STOP on shadow; later state not integrated with physics |
| MovePlayer PosRot | position reminder/threshold and rotation changed | current transform and two flags | included |
| MovePlayer Pos | position only | same | included |
| MovePlayer Rot | rotation only; always when passenger | same | included ordinary branch; passenger branch missing |
| MovePlayer StatusOnly | only ground/horizontal flag changed | exact collision flags | included |
| MoveVehicle | passenger's authoritative root vehicle each tick | relationship + vehicle transform + ground flag | missing |
| AcceptTeleportation | matching PlayerPosition ID | pending teleport | included |
| immediate PosRot after PlayerPosition | vanilla correction response | corrected transform/velocity/flags | included |
| immediate Rot after PlayerRotation | correction | corrected rotation; vanilla uses false,false | current sends from service path only as designed, exact timing needs verification |
| PlayerAbilities | local flight toggle | abilities | missing (feature-related unless already flying must be preserved) |
| PlayerCommand START_FALL_FLYING | input/equipment transition | equipment/pose | excluded |
| ClientCommand PERFORM_RESPAWN | user respawn | death | explicitly excluded |
| ClientTickEnd | every connected, unpaused client tick after level processing | timing | included even when dead/unknown |

### 7. Current implementation comparison

What is genuinely implemented:

- self transform, velocity, yaw/pitch, pose metadata, health/death, dimension;
- server motion replacement and explosion addition (`AutomationService.motion:252`, `explosion:259`);
- relative PlayerPosition application and teleport acknowledgement (`AutomationService.position:217-240`);
- block-only collision, one fixed step, approximate water current, fixed gravity/drag/friction (`PlayerPhysics:26-78`);
- four movement variants and 20-tick reminder (`AutomationService.sendMovement:561-595`);
- passive entity table and chunk/block state updates;
- unknown block lookup pauses physics writeback;
- TickEnd continues after ordinary physics is skipped.

Most important plan gaps, without making the product-scope decision:

1. **Attribute/effect/ability sync is a correctness gap, not only feature parity.** Gravity, scale, step height, air/friction modifiers, levitation, slow falling and already-active flight change zero-input motion. No listeners exist for UpdateAttributes, effects or PlayerAbilities.
2. **Environment-triggered special blocks are excluded without a truthful fallback.** Web, powder snow, honey, bubble column, slime/bed bounce, climbing and pistons can move a zero-input player. The current model treats most as ordinary shapes/air/friction and continues emitting invented positions.
3. **Mounted state is not detected.** SetPassengers is not tracked, and Shadow continues ordinary player physics/reporting where vanilla switches to rotation + root MoveVehicle. This should be implemented or explicitly pause player movement output while mounted.
4. **Entity table is not entity collision or push.** The plan explicitly excludes entity AABB push, but server-side pushes can change self velocity and client collision shapes can constrain movement. Motion packets may eventually correct velocity, but continuing local integration meanwhile is not equivalent.
5. **World border is absent from collision collection.** Vanilla `Entity.move` includes it.
6. **Pose is not fully modeled.** Scale, local desired-pose selection, fit checks and entity collision checks are missing; hard-coded dimensions are only correct for scale 1.
7. **Fluid handling covers only an approximation of water.** Lava, shallow/deep distinctions, eye state, stand-on-fluid, water attributes/effects and jump-out behavior are absent.
8. **The fixed 20 TPS wall-clock scheduler ignores TickingState.** At server tick rates below 20 vanilla slows normal client game ticks to server milliseconds/tick; freeze still ticks the local Player at the client cadence. This needs an explicit compatibility policy.
9. **Correction packet coverage is incomplete.** PlayerLookAt, vehicle corrections/teleports and some relationship-driven self relocation are absent. Generic entity teleport handling deliberately does not apply to self.
10. **Collision algorithm is a deliberate approximation.** Vanilla uses context-sensitive/dynamic voxel shapes, entity/world-border colliders, candidate step heights and restitution; the current AABB implementation uses one fixed step candidate and static compact shapes.

### 8. Minimal but non-false Shadow player-position model

A “minimal” model is defensible only if it distinguishes **calculated**, **server-authoritative**, and **unsupported/paused** states. The following boundary is the smallest found that does not knowingly invent vanilla positions:

1. Calculate locally only when all of these invariants hold:
   - alive, loaded, GAME, not passenger, not sleeping, not spectator/noPhysics, not flying/fall-flying/spin-attacking;
   - scale 1 or synced scale implemented; no unimplemented movement attribute/effect is active;
   - collision query chunks and required semantic registry/tag data are known;
   - player intersects no unimplemented movement-special block/fluid branch;
   - no unresolved moving piston, world-border collision, entity collider/push, or relationship transition affects the AABB;
   - supported pose and dimensions are known and fit-tested;
   - timing policy matches the received tick-rate state.
2. Within that safe subset, implement the exact air/water branch ordering, current velocity threshold, block collision, vanilla step candidates, collision flags, block speed factor and drag/gravity attributes.
3. Treat self SetEntityMotion, Explosion, PlayerPosition/Rotation/LookAt, Respawn and health as authoritative asynchronous inputs.
4. On any unsupported branch, **do not continue ordinary physics and do not emit a fabricated position reminder**. Continue required protocol traffic/TickEnd, track S2C state, and resume only after a server correction or after the unsupported condition is demonstrably gone with a valid baseline.
5. If the server requires movement liveness during a paused branch, send only a packet that is true of known state (for example rotation/status when those values are authoritative), not a guessed position. Whether a 20-tick position reminder may be omitted during a paused branch must be tested against the target server; vanilla normally sends it, but a wrong position is worse than a missing reminder.
6. Mounted players require a separate mode: relation graph + root vehicle physics/reporting, or paused ordinary movement until forced dismount/correction. Never reuse free-player physics.
7. Entity records can remain passive, but then entity collision/push must be an unsupported-condition boundary, not silently called “tracked.”

Under the current task's explicit exclusions, the honest product claim is therefore narrower than “basic vanilla player calculation”: **fixed-scale, unmounted, ordinary air/limited-water, block-static, no special-effect zero-input prediction, with server corrections and a conservative pause on unknown chunks**. Broadening the claim requires either implementing the conditional branches or adding detection-and-pause gates.

### 9. Related specs

- `.trellis/spec/backend/velocity-plugin.md`: currently states the fixed entry points and the simplified calculation contract; it also records explicit deferred entity attack/vehicle behavior.
- `.trellis/spec/language/java.md`: relevant only to later implementation shape, not vanilla semantics.
- `.trellis/spec/guides/cross-layer-thinking-guide.md`: the packet -> service state -> physics -> movement packet chain is a cross-layer contract; one owner should decode each packet/state semantic.

## Caveats / Not Found

- Mojang does not publish human-authored source files; line numbers here come from CFR 0.152 output of SHA-verified official-named artifacts. Every citation includes a stable class/method symbol. Control-flow labels in the large `LivingEntity.aiStep` decompilation were imperfect, so conclusions were cross-checked against surrounding calls and the matching server class.
- The audit covers all movement-relevant top-level packet families registered in the 26.2 game protocol and the principal `LocalPlayer -> Player -> LivingEntity -> Entity` call chain. It does not claim an exhaustive enumeration of every item/enchantment/custom data component that can install an attribute modifier; those reduce to the UpdateAttributes/equipment state requirements above.
- Dynamic collision shapes whose outcome depends on full `CollisionContext` (powder snow, scaffolding, trapdoors/fences and similar) were classified as a family. A complete per-block registry table was not derived.
- All server gameplay sources of `push`/`knockback` were not individually listed. Their protocol boundary is normally self SetEntityMotion or a position correction, but the exact delay/coalescing can differ because `ServerEntity` sends when `hurtMarked`.
- The exact MCProtocolLib class names for TickingState, PlayerLookAt, SetPassengers, PlayerAbilities, attributes/effects/equipment and world-border packets were not compiled against in this research; the vanilla packet existence and semantics are confirmed, but implementation should verify build-16 accessor names.
- The target server's behavior when a headless client deliberately pauses position reminders during an unsupported condition has not been end-to-end tested. This is a product/protocol compatibility decision, not evidence that guessed movement is safe.
- Client `TickRateManager` behavior is verified for vanilla 26.2. The current EventLoop scheduler may also drift due to executor delay; wall-clock catch-up behavior was not benchmarked.
- Resource pack-defined data cannot add Java block callback code in vanilla, but data-driven attributes/equipment/enchantments can change values. Modded servers/clients are outside this vanilla-only audit.

## 10. Implementation coverage manifest: every non-active transform input

This manifest is the implementation checklist implied by the later scope decision that the first release must handle **all movement not initiated by player input**. It deliberately assigns every effect to exactly one of four execution classes:

- **CS (client simulate):** run the vanilla-equivalent client tick and emit its normal C2S result.
- **SA (server-authoritative consume):** apply the value/transition received from the server; do not recompute the cause.
- **RF (relation-follow):** derive the passenger transform from the tracked vehicle and attachment relation; do not also run free-player physics.
- **NP (no position effect):** retain only if another required state machine uses it; it must not alter transform/velocity/pose/collision.

“Generated” below means a fixed 26.2 data row can describe the value or shape. “Handler” means executable, stateful or context-dependent semantics are required. MCProtocolLib statements refer to the resolved project artifact `org.geysermc.mcprotocollib:protocol:26.2-20260809.160751-16` (build 16), inspected from the Gradle cache on 2026-08-14.

### 10.1 Passenger, controller and root-vehicle coverage

The exhaustive protocol boundary is broader than the naturally rideable list: `ClientPacketListener.handleSetPassengers` applies the server passenger list and calls forced riding, while `Entity.startRiding(entity, force)` bypasses `canRide`/`canAddPassenger` when forced (`Entity.startRiding:2498-2531`). Therefore **any spawned entity type can be the immediate vehicle or an ancestor/root vehicle if the server sends that graph**. Arbitrarily forced vehicles use the generic RF row below; only code families overriding controller/ridden behavior become locally authoritative.

| Checklist / 26.2 entity types | Natural controller and authority | Zero-input tick required | C2S result | Class | MCProtocolLib build16 | Plan status |
|---|---|---|---|---|---|---|
| [ ] Generic forced passenger graph: every `EntityType`, nested to any depth | No controller by default. Consume `SetPassengers`; recompute the single parent of each passenger, reject/repair cycles, find root | Tick/interpolate every ancestor from its S2C entity state, then call equivalent `positionRider` from root down; self does **not** run free movement | `MovePlayer.Rot` for self while passenger; no `MoveVehicle` unless root reports self as controlling passenger/local authority | RF | `ClientboundAddEntityPacket`, `ClientboundSetPassengersPacket(entityId, passengerIds)`, remove/move/sync/teleport are present. Missing semantic attachment offsets/dimensions; generate a fixed entity-type table and consume metadata-driven pose/scale | Current PRD tracks a “minimal entity table” but explicitly excludes vehicle physics; relation-follow details are omitted |
| [ ] Boat family: ACACIA/BIRCH/CHERRY/DARK_OAK/JUNGLE/MANGROVE/OAK/PALE_OAK/SPRUCE boats and chest boats; BAMBOO_RAFT and BAMBOO_CHEST_RAFT | First living passenger controls. Local player controller makes root client-authoritative (`AbstractBoat.getControllingPassenger:720-724`; `Entity.isLocalInstanceAuthoritative:3567-3583`) | Always run `AbstractBoat.tick`: water status/out-of-control timer, `floatBoat`, zero controls, collision/move, passenger placement. At zero input paddles become false (`controlBoat:577-590`) | Every authoritative boat tick sends `ServerboundPaddleBoatPacket(false,false)` (`AbstractBoat.tick:272-280`); LocalPlayer also sends root `ServerboundMoveVehiclePacket` and self `MovePlayer.Rot` | CS root + RF self | All three packet classes and concrete boat/raft `EntityType` values exist; paddle fields are `rightPaddleTurning,leftPaddleTurning` | Explicitly excluded as “载具物理”; omitted from implementation steps |
| [ ] Saddle horse family: HORSE, DONKEY, MULE, SKELETON_HORSE, ZOMBIE_HORSE | First Player controls only when saddled (`AbstractHorse.getControllingPassenger:971-979`) | Run ridden living tick/travel, gravity/fluid/collision, `tickRidden`, zero `getRiddenInput`, jump state decay, passenger placement (`AbstractHorse:754-807`) | Root `MoveVehicle` + self `MovePlayer.Rot`; changed input via `PlayerInput`. Riding-jump `PlayerCommand` only for active jump, so none at zero input | CS root + RF self when controlled; otherwise RF | Packets/types represented. MCProtocolLib supplies metadata/attributes but not saddle semantics, horse physics or attachments; fixed code/data required | Explicitly excluded/omitted |
| [ ] CAMEL and CAMEL_HUSK | Inherit saddled-player authority from `AbstractHorse`; support two passengers (`Camel.canAddPassenger:583`) | In addition to horse travel, tick dash cooldown, pose/stand/sit state, zero ridden input and passenger-specific attachment (`Camel.tickRidden:298`, `getRiddenInput:324`) | Same controlled-root branch | CS root + RF self when controller; RF for second passenger | Both entity types and generic metadata exist; camel pose/attachment behavior is not supplied | Explicitly excluded/omitted |
| [ ] LLAMA and TRADER_LLAMA | Can carry a player but are not normally player-steered; no saddle-controlled root in normal gameplay | Consume server entity movement and place passenger | Self `MovePlayer.Rot`; no `MoveVehicle` | RF | Types/packets represented; attachment offset needs generated table | Explicitly excluded/omitted |
| [ ] PIG | Player controls only when saddled and first passenger holds carrot-on-a-stick (`Pig.getControllingPassenger:179-187`) | Run ridden living tick/travel, boost timer and zero input (`Pig.tickRidden:286`, `getRiddenInput:294`) | Controlled root `MoveVehicle` + self Rot; otherwise RF | CS root + RF self / RF | Type, metadata, equipment/inventory packets represented; control predicate/physics absent | Explicitly excluded/omitted |
| [ ] STRIDER | Saddled first player with warped-fungus-on-a-stick controls (`Strider.getControllingPassenger:273-281`) | Run lava/land travel, cold/shivering speed state, zero ridden input (`tickRidden:309`, `getRiddenInput:317`) | Controlled root `MoveVehicle` + self Rot; otherwise RF | CS root + RF self / RF | Type and packet state represented; behavior absent | Explicitly excluded/omitted |
| [ ] HAPPY_GHAST | Harness/first eligible player controller (`HappyGhast.getControllingPassenger:366-373`); up to four passenger attachments | Run airborne ridden travel, gravity/drag, zero input and all passenger positions (`tickRidden:399`) | Controlled root `MoveVehicle` + self Rot | CS root + RF self | Type exists; harness/controller and four attachment transforms not supplied | Explicitly excluded/omitted |
| [ ] NAUTILUS and ZOMBIE_NAUTILUS | `AbstractNautilus` first eligible player controller (`getControllingPassenger:223-230`) | Run aquatic ridden movement, buoyancy/fluid collision, zero input (`tickRidden:253-276`) | Controlled root `MoveVehicle` + self Rot | CS root + RF self | Types and protocol state represented; behavior absent | Explicitly excluded/omitted |
| [ ] Minecart family: MINECART, CHEST/FURNACE/TNT/HOPPER/COMMAND_BLOCK/SPAWNER_MINECART | No locally controlling passenger in this family. Rails and minecart behavior are server authoritative; 26.2 also has `ClientboundMoveMinecartPacket` interpolation steps | Apply minecart move/sync/teleport/lerp and RF passenger attachment; do not integrate a second client root trajectory | Self `MovePlayer.Rot`; no `MoveVehicle` | SA root + RF self | Concrete types, `ClientboundMoveMinecartPacket`, general entity packets and passengers exist; rail behavior need not be simulated for this authority branch | Vehicle relation omitted; physics explicitly excluded, but server-consume/RF is still required by new scope |
| [ ] Dismount/ejection/death/removal/relation replacement | Server passenger list is authoritative; local voluntary dismount is active input and out of this zero-input scope | On graph edge removal, use the authoritative self position/correction if supplied; only resume free-player CS after a collision-valid baseline. Boat underwater ejection and vehicle death can arrive through relation/removal before correction | No dismount command at zero input; subsequent free-player Move branch only after baseline | SA transition | SetPassengers, RemoveEntities, PlayerPosition/Respawn represented | Omitted |

Authority must be evaluated on the **root**, not the immediate vehicle. A chain such as player -> boat -> forced parent is RF throughout unless the actual root's controller predicate selects the local player. `LocalPlayer.tick` sends self rotation while passenger, then sends `MoveVehicle` only when `root != self && root.isLocalInstanceAuthoritative`; free-player movement packets are mutually exclusive with that branch (`LocalPlayer.tick:239-246`). This separation also means entity-table coordinates, relation attachment and vehicle physics are three different checklist items, not synonyms.

### 10.2 Non-active environment and block behavior coverage

| Checklist / code family | Non-active effect and vanilla owner | Data vs handler | Class | C2S consequence | MCProtocolLib build16 | Plan status |
|---|---|---|---|---|---|---|
| [ ] Static/context collision shapes: full cubes, slabs/stairs, fences/walls/gates, doors/trapdoors, panes, carpets, snow layers, plants and neighbor-shaped blocks | Client `Entity.move` clips velocity and updates `horizontalCollision`, `verticalCollision`, `onGround`; shape may depend on state, neighbors and `CollisionContext` | Generated shape/state tables cover static cases; neighbor/context selectors require a generic shape resolver, not one AABB per protocol ID | CS | MovePlayer variant/flags | Chunk, block and section updates represented; library has block IDs/states but no collision shapes | Basic AABB collision is planned, but dynamic/context shapes are omitted |
| [ ] Ice family and ordinary friction/speed/restitution | Foot block friction/`speedFactor`/restitution changes post-move velocity without input | Generated scalars plus one generic post-move handler | CS | Subsequent MovePlayer | World packets represented; physical scalars absent | Friction, speed and restitution planned |
| [ ] Water and flowing water | Fluid height/eye state/flow vector, buoyancy, drag, gravity choice and swimming pose affect velocity/pose | Generated fluid state/amount/falling and tags; dedicated generic fluid sampling/travel handler | CS | MovePlayer | Block states/chunks/tags represented; library does not calculate fluid state/flow | Water planned, but only compact water properties; complete depth/eye/tag semantics omitted |
| [ ] Lava and flowing lava | Lava height/flow/drag/gravity and collision escape affect zero-input trajectory | Same generated fluid representation plus lava travel branch | CS | MovePlayer | Protocol representation exists; semantics absent | Omitted and not explicitly listed among exclusions |
| [ ] Bubble columns (soul-sand upward, magma downward; inside vs surface) | `BubbleColumnBlock.entityInside`/`entityInsideAbove` adds/clamps vertical velocity even at zero input | Dedicated handler keyed by column drag direction and surface occupancy; block state is generated input | CS | MovePlayer | Block state represented; callback absent | “特殊方块行为” explicitly excluded |
| [ ] Cobweb / web-style stuck movement | `WebBlock.entityInside` sets stuck multiplier; `Entity.makeStuckInBlock` constrains velocity before movement | Dedicated inside-block handler; generated row may carry multiplier but cannot replace call ordering | CS | MovePlayer | Block represented; callback absent | Explicit special-block exclusion |
| [ ] Sweet berry bush | Inside-block horizontal/vertical slowdown; growth state changes collision/damage but damage is server-owned | Dedicated inside-block handler with state predicate | CS for slowdown; NP for damage | MovePlayer for slowdown | State represented; callback absent | Excluded |
| [ ] Powder snow and freezing | Context collision (falling through vs boots), stuck multiplier, `isInPowderSnow`, frozen ticks/speed modifier and pose/ground result | Dedicated collision-context + inside handler; generated shape/state/tags are inputs. Consume frozen metadata/attributes where server supplies them | CS local collision; SA synced frozen state | MovePlayer | Block/metadata/equipment/inventory/effects represented; semantic indices/boots rule absent | Excluded |
| [ ] Honey block | Side sliding clamps/modifies vertical and horizontal velocity; top speed factor; fall behavior | Dedicated side-collision handler plus generated scalar | CS | MovePlayer | State represented; callback absent | Excluded |
| [ ] Slime block and bed bounce | Landing restitution can invert vertical velocity with crouch/suppress-bounce and living-entity factors; slime `stepOn` scales horizontal velocity | Generic restitution handler using generated bounce scalar, plus slime-specific step handler. Bed occupied/state shape is generated input | CS | MovePlayer | States represented; restitution absent | Excluded |
| [ ] Soul sand and Soul Speed | Generic speed factor plus location-dependent equipment/enchantment modifier | Generated scalar; equipment/data-component and attribute/location-effect handler | CS | MovePlayer | Inventory/equipment/attributes and item components represented; enchantment execution semantics absent | Excluded |
| [ ] Climbable family: ladder, vine, twisting/weeping vines, scaffolding and trapdoor-as-ladder relation | `LivingEntity.handleOnClimbable` clamps velocity/fall distance and enables passive downward/held motion; scaffolding/powder shapes use entity context | Generated block tags/states/shapes plus dedicated climb/context handler | CS | MovePlayer | `ClientboundUpdateTagsPacket` and blocks exist; tag interpretation/physics absent | Excluded |
| [ ] Fluid stand-on/walk-on and jump-out checks | `canStandOnFluid`, eye height, adjacent collision and fluid surface affect passive settling and vertical velocity | Generic fluid/collision handler; generated tags/states | CS | MovePlayer | Inputs represented; behavior absent | Omitted |
| [ ] Moving piston and moving-block collision | Client `MovingPistonBlockEntity` advances progress and invokes `Entity.move(MoverType.PISTON, ...)`; per-axis piston deltas are clamped/accumulated | Dedicated stateful handler requiring moving-piston block entity, facing, extending/source state, moved state and client tick progress; cannot be a static generated row | CS | MovePlayer | Block updates and `ClientboundBlockEntityDataPacket(position,type,nbt)` exist; chunk block-entity NBT is representable. No piston simulation | Explicitly excluded |
| [ ] Collision-shape replacement push (`Block.pushEntitiesUp`) | Server block mutation can lift intersecting entities when a block's collision shape grows | Do not replay from S2C block update: consume resulting self correction/motion. Still update world shape | SA | AcceptTeleport + PosRot if correction arrives, otherwise later truthful MovePlayer from corrected baseline | Block update and correction represented | Omitted; do not fold into client piston handler |
| [ ] World border | Moving/static border participates in entity collision and can clip motion | Dedicated generic border collider driven by packet state; no generated block | CS | MovePlayer/flags | All six border packet families and fields are represented | Omitted |
| [ ] Nether portal contact | Portal processor/contact timer is client-visible, but multiplayer dimension transfer/teleport is server authoritative; do not predict destination | Contact-state handler may be retained, transform transition is SA | SA for transform | Respawn/correction acknowledgment branch | Portal block, Respawn and PlayerPosition represented | Excluded as special block |
| [ ] End portal and end gateway | Server initiates teleport/dimension transfer; client must not derive destination | No local physics handler beyond collision/world state; consume Respawn/PlayerPosition/entity sync | SA | AcceptTeleport + PosRot as required | Represented | Excluded |
| [ ] Bed enter/sleep/wake | Server controls sleeping pose/bed position and wake relocation; sleeping suspends ordinary travel | Consume entity metadata/pose/sleeping position and correction; dedicated sleep mode prevents free CS | SA | No ordinary Move while dead/sleep-paused; correction ack when sent | Generic entity metadata and PlayerPosition represented; metadata schema must be version-fixed | Excluded as special block; sleep mode omitted |
| [ ] Scaffolding/powder-snow contextual support | Collision support changes from entity vertical relation/equipment, affecting `onGround` even at zero input | Included once in climb/powder context handlers; **do not** also apply a static shape | CS | MovePlayer/flags | Inputs represented | Excluded |
| [ ] Magma, cactus, campfire, fire, berry damage and suffocation callbacks | Damage/health/death are server authoritative and do not independently add transform in vanilla; their collision shapes still use the generic collision row | NP for damage source; SA health/death; generic shape CS only | NP/SA | No movement response solely from damage; stop/resume per health/respawn state | Health/entity event packets represented | Health/death planned; avoid inventing knockback from a damage animation |
| [ ] Void, freezing damage, drowning, burning and fall damage | Server health decision only. Their preceding gravity/fluid/collision trajectory is already owned by corresponding CS rows | NP for damage calculation; SA health/death | NP/SA | None except normal movement and optional respawn (active/product policy) | SetHealth/Respawn represented | Health/death planned; auto-respawn excluded |

The “generated vs handler” boundary is strict: generated rows may supply collision shapes, friction, speed/jump/bounce coefficients, fluid classification, tags, dimensions and attachment constants. They cannot truthfully express callback ordering, `CollisionContext`, moving-piston progress, portal/sleep transitions, vehicle controller predicates or stateful timers. Those require handlers even when their constants are generated.

### 10.3 Complete movement-state S2C matrix

This matrix is complete for vanilla game-protocol inputs that can change, constrain or select the local-player/vehicle transform branch. Packets such as chat, sound, particles, statistics, recipes and damage animation are intentionally NP and not repeated.

| Packet class (MCProtocolLib build16 name) | Key fields to retain | Owner/classification | Required downstream action | Plan status |
|---|---|---|---|---|
| [ ] `ClientboundLoginPacket` | `entityId`, common `PlayerSpawnInfo` (dimension type/name, seed, game mode/previous mode, debug/flat, last death, portal cooldown), world list, view/simulation distance | SA | Initialize self ID/dimension/mode; clear relation/world baseline as appropriate | Included |
| [ ] `ClientboundRespawnPacket` | `commonPlayerSpawnInfo`, `keepMetadata`, `keepAttributeModifiers` | SA | Dimension/mode transition; preserve or clear metadata/attributes exactly; clear vehicle/world/physics baseline; await position | Included only at coarse reset level; keep flags omitted |
| [ ] `ClientboundPlayerPositionPacket` | teleport `id`, `position`, `deltaMovement`, `yRot`, `xRot`, relative `PositionElement` set | SA | Apply each absolute/relative component exactly, reset send baseline, send AcceptTeleport then exact PosRot | Included, relative flags need exact coverage |
| [ ] `ClientboundPlayerRotationPacket` | `yRot`, `relativeY`, `xRot`, `relativeX` | SA | Apply rotation and immediately send Rot with both collision flags false, matching handler branch | Omitted |
| [ ] `ClientboundPlayerLookAtPacket` | origin anchor, target xyz, optional target entity ID/origin | SA decision -> local rotation | Resolve target after entity lookup; next tick sends rotation change | Omitted |
| [ ] `ClientboundSetCameraPacket` | `cameraEntityId` | SA/branch selector | `sendPosition` runs only while self is controlled camera; track spectator camera without moving self to camera entity | Omitted |
| [ ] `ClientboundPlayerAbilitiesPacket` | invincible, canFly, flying, creative, flySpeed, walkSpeed | SA | Update no-gravity/flying travel branch and speeds | Omitted |
| [ ] `ClientboundGameEventPacket` | notification + value, notably game-mode change | SA | Update game mode/spectator/noPhysics and ability policy | Omitted |
| [ ] `ClientboundSetHealthPacket` | health, food, saturation | SA | Death gates ordinary movement; do not infer knockback | Included |
| [ ] `ClientboundSetEntityMotionPacket` | entityId, movement | SA input | For self, replace velocity; for tracked vehicle/entity update its velocity. Never add | Included only for self; vehicle/entity branch incomplete |
| [ ] `ClientboundExplodePacket` | center, radius, affected blocks, `playerKnockback`, particles/sound | SA input + CS continuation | Add only supplied player knockback; update destroyed blocks; do not recompute exposure | Included |
| [ ] `ClientboundSetEntityDataPacket` | entityId, metadata list | SA state | Self/vehicle pose, flags, sleeping position, frozen ticks, fall-flying, no-gravity, saddle/harness/boat states and type-specific control data using 26.2 schema | Minimal entity table planned; movement metadata coverage omitted |
| [ ] `ClientboundUpdateAttributesPacket` | entityId, attributes `(type identifier/id,value,modifiers[id,amount,operation])` | SA state -> CS parameters | Rebuild effective movement speed, gravity, jump strength, step height, scale, safe-fall, fall-damage multiplier, water efficiency/movement, oxygen bonus, movement efficiency, sneaking speed and related values | Omitted |
| [ ] `ClientboundUpdateMobEffectPacket` / `ClientboundRemoveMobEffectPacket` | entityId, effect, amplifier, duration, flags/blend | SA state -> CS branch | Levitation, slow falling, speed/slowness, jump, dolphins grace/conduit and pose/vision-independent movement effects | Omitted |
| [ ] `ClientboundSetEquipmentPacket` | entityId, equipment entries | SA state | Tracked entity equipment; self may also be updated through inventory packets | Entity equipment included only generically; movement semantics omitted |
| [ ] `ClientboundSetPlayerInventoryPacket` | player slot, ItemStack; `ClientboundContainerSetContentPacket` / `...SetSlotPacket`; `ClientboundSetHeldSlotPacket` | SA state | Maintain self boots/chest/held item and components needed by powder snow, Soul Speed, glider and mount controller predicates | Omitted |
| [ ] `ClientboundAddEntityPacket` | id, UUID, type, object data, xyz, movement, yaw/headYaw/pitch | SA entity table | Create possible vehicle/root/collider with initial transform | Minimal entity table included |
| [ ] `ClientboundRemoveEntitiesPacket` | entity IDs | SA relation transition | Remove records and all incident passenger edges; if self relation breaks, wait for valid self baseline | Minimal table included; edge cleanup unspecified |
| [ ] `ClientboundMoveEntityPosPacket`, `...PosRotPacket`, `...RotPacket` | id, deltas/rot, onGround | SA entity tracking | Interpolate/update non-local ancestors/colliders; never apply these generic packets to self as a substitute for PlayerPosition | Included generically |
| [ ] `ClientboundEntityPositionSyncPacket` | id, absolute position, deltaMovement, yRot, xRot, onGround | SA entity tracking | Full vehicle/root baseline and velocity sync | Included generically; velocity field significance omitted |
| [ ] `ClientboundTeleportEntityPacket` | entity id and teleport state/relative flags | SA entity tracking | Teleport vehicle/ancestor/collider; RF self follows | Included generically |
| [ ] `ClientboundMoveMinecartPacket` | entity id and interpolation step list | SA root | Advance server-authored minecart interpolation and RF attachment | Omitted |
| [ ] `ClientboundMoveVehiclePacket` | position, yRot, xRot | SA correction | Correct current locally authoritative vehicle/root; vanilla replies with exact `ServerboundMoveVehiclePacket` | Listed in research, omitted from plan |
| [ ] `ClientboundSetPassengersPacket` | vehicle `entityId`, ordered `passengerIds` | SA relation | Replace full passenger list/order, update reverse parent index/root/controller/attachments | Omitted |
| [ ] `ClientboundLevelChunkWithLightPacket` | chunk data including palette sections and block entities | SA world input | Replace chunk collision/fluid/special-block/piston state atomically | Included; chunk block-entity semantics omitted |
| [ ] `ClientboundBlockUpdatePacket`, `ClientboundSectionBlocksUpdatePacket` | positions/state IDs | SA world input | Update states before next collision/fluid tick | Included |
| [ ] `ClientboundBlockEntityDataPacket` | position, block-entity type, NBT | SA world input | Required for moving piston and other dynamic collision state | Packet represented, plan discards as non-basic |
| [ ] `ClientboundForgetLevelChunkPacket` | chunk position | SA world availability | Mark unknown and pause affected CS | Included |
| [ ] `ClientboundSetChunkCacheCenterPacket` / radius | center/radius | SA availability hint | Do not confuse cache center with player position; use to bound retained chunks | World tracking partly included |
| [ ] `ClientboundRegistryDataPacket`, `ClientboundUpdateTagsPacket` | registry entries / tag maps | SA semantic input | Bind dimension gravity/height, block/fluid/entity/enchantment/equipment semantics; fixed 26.2 generated data may cover vanilla constants but tags received from server remain authoritative | Registry/tag capture incomplete |
| [ ] Border initialize/set-center/set-size/set-lerp-size/warning packets | center, old/new size, lerp time, absolute max, warnings | SA state -> CS collider | Tick lerp against game time and include border collision exactly once | Omitted |
| [ ] `ClientboundTickingStatePacket` / `ClientboundTickingStepPacket` | tickRate, frozen, step count | SA scheduler state | Match client tick-rate manager/freeze/step policy; do not integrate multiple owners | Omitted |

`ClientboundDamageEventPacket`, `ClientboundHurtAnimationPacket` and ordinary `ClientboundEntityEventPacket` are **NP for movement** unless a separate Motion/Explosion/PlayerPosition packet is received. `ClientboundSetEntityLinkPacket` is leash linkage, not passenger linkage, and is NP for self position. `ClientboundBlockEventPacket` may drive piston visual/block state progression but is not, by itself, a self transform; piston motion remains owned by the moving-block state handler.

### 10.4 Complete C2S branch checklist

| Condition after tick/event | Exact output | Notes / build16 status |
|---|---|---|
| [ ] Free self, position + rotation changed | `ServerboundMovePlayerPosRotPacket(position,yRot,xRot,onGround,horizontalCollision)` | Present |
| [ ] Free self, position only (including 20-tick reminder) | `ServerboundMovePlayerPosPacket(position,onGround,horizontalCollision)` | Present; reminder uses position branch even with tiny delta |
| [ ] Free self, rotation only | `ServerboundMovePlayerRotPacket(yRot,xRot,onGround,horizontalCollision)` | Present |
| [ ] Free self, only ground/horizontal-collision status changed | `ServerboundMovePlayerStatusOnlyPacket(onGround,horizontalCollision)` | Present |
| [ ] Passenger every LocalPlayer movement tick | `ServerboundMovePlayerRotPacket` for self; no self Pos/PosRot/Status | Present |
| [ ] Passenger with locally authoritative root | Additionally `ServerboundMoveVehiclePacket(root position,yRot,xRot,onGround)` | Present |
| [ ] Locally authoritative boat root every tick, even zero input | Additionally `ServerboundPaddleBoatPacket(false,false)` | Present; confirmed booleans `rightPaddleTurning,leftPaddleTurning` |
| [ ] Non-authoritative minecart/forced vehicle passenger | Rot only; **no** MoveVehicle | Prevents claiming server-authored root physics as client-authored |
| [ ] Changed seven-button input state | `ServerboundPlayerInputPacket(forward,backward,left,right,jump,shift,sprint)` | Present; send transition to all false on takeover, then only on changes |
| [ ] PlayerPosition correction | `ServerboundAcceptTeleportationPacket(id)` then exact `ServerboundMovePlayerPosRotPacket` | Present; apply relative position/velocity/rotation before response |
| [ ] PlayerRotation packet | Immediate `ServerboundMovePlayerRotPacket(new angles,false,false)` | Present |
| [ ] Vehicle correction | Exact `ServerboundMoveVehiclePacket` echo after applying correction | Present |
| [ ] Riding jump start/stop, sprint/sneak/dismount/flight toggle | `ServerboundPlayerCommandPacket` / `ServerboundPlayerAbilitiesPacket` | Active-input transitions are outside this scope; a takeover-generated stop-sprint is policy, not physics |
| [ ] End of every GAME client tick | `ServerboundClientTickEndPacket` | Present; continues while physics is paused/dead as current PRD requires |

Only one movement branch may run per tick. A server-authoritative correction is consumed first; relation/controller authority is then resolved; CS is run only for the selected free player or locally authoritative root; RF places self last; packet selection observes the final state. This ordering prevents double-applying vehicle movement, piston pushes, server corrections or entity sync.

### 10.5 MCProtocolLib build16 representation verdict and minimum generic remediation

No required 26.2 state or movement packet class was missing from build16 in this audit. In particular, direct artifact inspection confirmed `ClientboundSetPassengersPacket`, both vehicle packets, `ClientboundMoveMinecartPacket`, all four MovePlayer variants, paddle/input/tick-end/teleport-ack packets, attributes/effects/abilities/equipment/inventory, player correction/rotation/look-at/camera, chunk/block/block-entity, registries/tags, ticking-state and all border packets.

The real representation gaps are above the codec layer:

1. **No vanilla physics implementation.** Supply internal, version-fixed handlers for player and each locally authoritative vehicle code family; MCProtocolLib only exposes packet values.
2. **No complete physical registry.** Generate fixed 26.2 rows for block-state shapes/properties/tags, entity dimensions/eye heights/passenger attachments, relevant attribute defaults and metadata schemas. Preserve source commit/license as already required by the task.
3. **No relation/authority model.** Build a generic entity record plus ordered passenger graph and reverse parent index. Controller predicates belong to version-fixed vanilla behavior, not a Patch-specific interface.
4. **No executable block callbacks.** Add generic engine concepts (inside-block effect, restitution, climbable, contextual shape, moving collision, portal/sleep mode) and register the vanilla 26.2 handlers. Do not encode behavior as Patch-only packet hooks.
5. **No piston clock/model.** Retain generic block-entity NBT and chunk block-entity records, then implement the vanilla moving-block state machine. The codec already supplies the generic data container.
6. **Generic metadata is not semantic state.** Decode metadata against a fixed 26.2 entity inheritance schema so pose, flags, saddle/harness, sleeping position and frozen state cannot be mistaken for stable cross-version indices.

### 10.6 Updated plan-gap ledger

The latest “all non-active position changes” decision is not reflected in the checked-in planning documents. `prd.md:306-320` still explicitly excludes entity AABB push, moving piston physics, vehicle physics and special block behavior; `design.md:421-459` limits the implementation to zero-input free-player physics, static block shapes and water; `implement.md:63-77` lists only Motion/Explosion, water, block AABB/step, drag/gravity and movement packets.

Accordingly, the manifest status is:

- **Already included:** self Motion replacement, Explosion addition, basic chunk/block state, basic static collision/step, friction/gravity/air drag, partial water, free-player packet selection, health/death, Respawn reset and unknown-chunk pause.
- **Explicitly excluded but now required by the decision:** locally authoritative vehicle physics, passenger relation-follow, moving pistons, special block callbacks, entity AABB constraint/push.
- **Previously omitted without an explicit exclusion:** lava/complete fluid semantics, world border, attribute/effect/ability/equipment-driven movement, contextual collision shapes, sleep/portal server-authoritative modes, minecart interpolation, tick-rate state, camera branch and complete correction variants.
- **Must remain non-duplicated:** server damage calculation, explosion-vector calculation, server teleport destination, minecart rail integration for a non-authoritative passenger, block-shape-replacement server push and damage-animation knockback inference.

### 10.7 Manifest caveats / not found

- The generic forced-passenger rule makes the relation list exhaustive without pretending every one of the hundreds of entity types has bespoke vehicle physics. The naturally controllable override search found the boat, `AbstractHorse`/Camel, Pig, Strider, HappyGhast and `AbstractNautilus` families. Minecarts and llamas are separately listed as natural passenger but server-authored roots.
- Exact passenger attachment transforms can be pose-, scale-, seat-index- and entity-state-dependent. `EntityTypes` supplies many static attachment constants, but a complete generated attachment table was not produced in this pass.
- This pass verified packet presence and important accessors against build16 bytecode, but did not compile a listener covering every row. “Represented” means codec/data container exists, not that current task code consumes it.
- The official client artifact contains only a subset of shared/server block classes needed for decompilation. Special-block family symbols in this manifest reuse the SHA-verified official client/server audit earlier in this report; a complete per-registered-block callback inventory was not machine-generated. The listed movement callback families are those reachable through the audited `Entity.move`/inside/fluid/portal/piston paths; future Mojang additions must be caught by a version-diff audit.
- No vanilla-mod extension points or modded entity/block callbacks are covered. The boundary remains fixed unmodified Minecraft Java 26.2, protocol 776/data version 4903.
