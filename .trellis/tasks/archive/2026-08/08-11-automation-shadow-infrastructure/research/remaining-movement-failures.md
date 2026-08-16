# Research: Remaining movement failures after the LevelChunk P0

- Query: Classify the remaining knockback, fall, water, boat, and sticky-piston failures against fixed Minecraft Java 26.2 and the current Plugin.
- Scope: internal
- Date: 2026-08-15

## Findings

### 1. Evidence boundary

This report uses these fixed local sources:

- Minecraft merged deobf 26.2 source JAR: `E:/Gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.2/minecraft-merged-deobf-26.2-sources.jar`.
- MCProtocolLib `26.2-20260809.160751-16`: `build/tmp/mcprotocollib-sources.jar`.
- Plugin target: Minecraft 26.2, protocol 776, and MCProtocolLib build 16 (`plugin/build.gradle.kts:12`; `plugin/src/main/java/com/fakeplayerproxy/automation/ProtocolTarget.java:5-8`).

The separate P0 causes every failed LevelChunk install to remain unknown. `World.collisions()` then has no block collision from that chunk, and `World.fluid()` has no fluid from it. This state is not loaded air (`.trellis/spec/backend/velocity-plugin.md:89-94`).

### 2. Classification summary

| Symptom | Classification | Confidence | Result after the registry/chunk repair |
| --- | --- | --- | --- |
| Player attack knockback is lost or too short | Partly amplified by missing chunks | High for the shared packet/correction path; medium for the observed frequency | Retest first. Do not add a source-specific knockback path. Fix the independent collider and correction defects if the retest still shortens movement. |
| Mob and projectile knockback is fully lost | Fully explained by missing chunks for an isolated self target | High | Retest first. The Plugin has no mob/projectile-specific drop after Motion receipt. |
| Fall damage is absent | Fully explained by missing chunks on the clean accepted-movement path | High | Retest first. Do not add client fall-damage calculation. The server owns damage. |
| Player water current is absent or wrong | Partly amplified by missing chunks | High | Chunks restore fluid samples, but the current formula remains non-Vanilla. |
| Boat water current, buoyancy, and movement are absent or wrong | Independent implementation defect, masked by missing chunks | High | Chunks do not add boat status, buoyancy, authority guards, or correct output order. |
| Sticky piston retract/carry fails | Independent implementation defect, masked by missing chunks | High | Chunks do not add the missing BlockEvent state transition or correct piston carry semantics. |

### 3. Knockback packet and owner paths

#### 3.1 Vanilla source branches

Player attack uses an immediate target-self Motion packet. `Player.attack()` saves the old target velocity and calls damage plus extra knockback (`Player.java:945-999`). `Player.causeExtraKnockback()` sends `ClientboundSetEntityMotionPacket` to a `ServerPlayer`, clears `hurtMarked`, and restores the saved server velocity (`Player.java:1113-1140`). The client must integrate that velocity.

Mob and arrow attacks use the delayed tracker path:

- `Mob.doHurtTarget()` applies damage and extra knockback without an immediate self send (`Mob.java:1376-1393`).
- Arrow damage and arrow-specific knockback finish inside `AbstractArrow.onHitEntity()` (`AbstractArrow.java:419-524`).
- `ServerEntity.sendChanges()` sees `hurtMarked` and calls `sendToTrackingPlayersAndSelf(new ClientboundSetEntityMotionPacket(entity))` (`ServerEntity.java:221-224`).

After those server branches, all sources use the same Plugin path:

`backend frame -> MinecraftDecoder.dispatchPacketEvent -> S2CPacketEvent<ClientboundSetEntityMotionPacket> -> FakePlayerProxyPlugin.onMotion -> AutomationService.motion -> entity.setVelocity`.

The current owners are:

- Raw packet dispatch before normal Velocity decode: `plugin/patch/0002-automation-extension.patch:800-852`.
- Plugin listener: `plugin/src/main/java/com/fakeplayerproxy/FakePlayerProxyPlugin.java:366-370`.
- Entity lookup and velocity overwrite: `plugin/src/main/java/com/fakeplayerproxy/automation/AutomationService.java:214-219`.

The target self id resolves to the Plugin `Player` registered in the same `World`. `Player.tick()` then calls `LivingEntity.travel()` and `sendMovement()` (`Player.java:175-187`). `sendMovement()` emits PosRot, Pos, Rot, or Status with the fixed Vanilla threshold (`Player.java:238-270`).

There is no source-type switch after `AutomationService.motion()`. A mob-only or projectile-only implementation fix is not supported by the current code.

#### 3.2 Bundle handling is not the loss point

Motion can occur between bundle delimiters. The patched decoder dispatches each framed packet before Velocity or MCProtocolLib bundle aggregation (`0002-automation-extension.patch:800-852`). Therefore, `onMotion()` sees the Motion packet inside a wire bundle. A listener after `BundlerUnpackerDecoder` would need to expand `ClientboundBundlePacket`, but this listener is before that owner.

Do not add a second Bundle listener. It would duplicate dispatch at the current layer.

#### 3.3 Missing chunks cause the overwrite loop

Without block collisions, local travel moves into server solids. The Plugin sends that new position. Fixed Vanilla validates it in `ServerGamePacketListenerImpl.handleMovePlayer()` (`ServerGamePacketListenerImpl.java:1106-1140`). A rejected move calls `teleport(startX, startY, startZ, ...)` (`ServerGamePacketListenerImpl.java:1174-1177`).

The resulting PlayerPosition correction has absolute position and zero delta movement. Fixed Vanilla applies the correction velocity as an overwrite. The Plugin does the same through `AutomationService.position()` and `Player.applyServerPosition()` (`AutomationService.java:178-203`; `Player.java:107-119`). Thus, a received attack Motion can produce one short local step and then become zero.

This mechanism is common to player, mob, and projectile attacks. Source timing explains different visible lengths: player attack sends Motion inside the attack call; mob and arrow attacks send at tracker flush. The static code does not prove a second source-specific loss.

#### 3.4 Independent knockback-adjacent defects

Two defects remain after chunks work:

1. `World.collisions()` inserts every tracked entity with a non-zero box as a solid movement obstacle (`World.java:190-198`; `Entity.java:145-148`). Fixed Vanilla uses `level.getEntityCollisions()` and `canCollideWith()` (`Entity.java:1138-1141`). Base `Entity.canBeCollidedWith()` returns false (`Entity.java:2356-2362`). Ordinary living entities and projectiles are pushed later; they are not general solid movement shapes. The Plugin also runs `World.pushEntities()` (`World.java:370-387`). It can therefore clip and push the same ordinary entity relation. This can shorten knockback in crowded tests and can produce a false landing on an entity.
2. A PlayerPosition correction must be acknowledged with `onGround=false` and `horizontalCollision=false` in fixed Vanilla (`ClientPacketListener.java:796-805`). The Plugin retains the old flags and sends them in its acknowledgement (`AutomationService.java:196-202`). A stale `onGround=true` can close server fall state during a correction.

These defects are not a reason to add custom attack impulse formulas. Retest isolated knockback after the P0. Then test the collider and correction cases separately.

### 4. Motion overwrite ordering for tracked entities and boats

The current interpolation owner has two independent velocity overwrite defects:

- `Entity.interpolate()` stores the current velocity in `interpolationVelocity`, and `tickInterpolation()` writes that saved value on every interpolation tick (`Entity.java:251-283`). Fixed 26.2 `InterpolationHandler.interpolate()` changes position and rotation only. It does not change delta movement. A later Motion packet can therefore be undone by an earlier Plugin interpolation target.
- `AutomationService.syncEntity()` passes the PositionSync movement into `Entity.sync()`, which overwrites velocity (`AutomationService.java:257-268`; `Entity.java:244-249`). Fixed `ClientPacketListener.handleEntityPositionSync()` ignores the packet delta movement (`ClientPacketListener.java:642-665`).

There is also an authority defect. Fixed `handleMoveEntity()` updates only the position codec when `entity.isLocalInstanceAuthoritative()` (`ClientPacketListener.java:732-755`). Fixed PositionSync also does not apply the transform to a local-authoritative entity (`ClientPacketListener.java:642-665`). The Plugin always interpolates or syncs the tracked entity (`FakePlayerProxyPlugin.java:392-445`). A locally controlled root boat can receive a server tracking update that rewinds local movement before `tickVehicle()`.

Player self knockback does not use `World.tickInterpolation()` because `World.tick()` excludes the owner (`World.java:128-139`). These interpolation defects are not the source-specific explanation for player, mob, or arrow damage to the owner. They are direct defects for tracked entities and local vehicles.

### 5. Fall damage after chunks work

The correct end-to-end chain is:

1. `Player.tick()` runs `travel()` (`Player.java:175-187`).
2. `Entity.move()` clips the requested downward movement against blocks and sets `onGround` from a downward vertical collision (`Entity.java:299-349`).
3. `Player.sendMovement()` sends Pos or PosRot when position changed. It sends StatusOnly when only the flags changed (`Player.java:238-270`).
4. The server accepts the target coordinate, snaps to it, creates `clientDeltaMovement`, applies packet flags, and calls `doCheckFallDamage(clientDeltaMovement, packet.isOnGround())` (`ServerGamePacketListenerImpl.java:1141-1156`).
5. Negative accepted Y deltas accumulate server `fallDistance`. A later accepted `onGround=true` packet settles damage. Pos/PosRot accumulates distance. StatusOnly can settle prior distance but cannot add a new Y delta (`ServerboundMovePlayerPacket.java:9-225`; `Entity.java:1540-1566`).

The Plugin packet selection is sufficient for this chain. Its local `fallDistance` field does not cause server player damage. Do not send client fall distance and do not calculate damage in the Plugin.

With unknown chunks, step 2 cannot find the ground. The Plugin either falls through the ground or enters the correction loop in section 3.3. The accepted server chain cannot complete normally. This fully explains the current clean fall-damage failure.

Retest after the P0 with these observations: every descent packet type and Y, server acceptance or correction, the first `onGround=true`, and health after settlement. If a correction occurs, also test the false/false acknowledgement defect in section 3.4.

### 6. Player water current

Missing chunks make `World.fluid()` return no sampled state (`World.java:238-277`). Chunks will restore samples, but the current formula remains wrong.

Fixed 26.2 does this (`EntityFluidInteraction.java:32-89,153-188`):

- Require all x/z neighbor chunks in the interaction range to be FULL (`EntityFluidInteraction.java:91-119`).
- Scan x, then y, then z.
- Track water and lava separately by fluid tag.
- Update the tracker maximum immersion before scaling the current cell flow. Scale the cell flow by immersion when the tracker height is less than `0.4`.
- For `Player`, divide the accumulated flow by the intersected cell count. For a non-player, normalize it.
- Apply the fluid-specific scale. Water uses `0.014`. Lava uses its separate scale.
- If old horizontal velocity is below `0.003` on both axes and the impulse is below `0.0045`, normalize the impulse to `0.0045`.

The Plugin does this (`World.java:238-277,737-794`):

- Scan x, then z, then y.
- Sum water and lava into one vector and count.
- Do not apply shallow-immersion weighting.
- Normalize all entity flow to `0.014`.
- Do not apply the Player average or the minimum nudge.
- Use `fluidAmount / 9` without the same-fluid-above full-height rule.
- Add falling-fluid Y whenever horizontal flow exists. Fixed `FlowingFluid.getFlow()` adds that branch only with the required blocking face.
- Skip unknown neighbor states instead of aborting the full interaction update.

`Entity.baseTick()` adds this one generic vector for every pushed entity (`Entity.java:290-296`). This is an independent formula defect. The API must know the entity class and must retain separate fluid trackers or equivalent state.

### 7. Boat state, buoyancy, and output

The current boat branch is not a fixed 26.2 boat calculation. It calls generic `baseTick()`, moves once, applies horizontal `0.9` and vertical `0.95`, and stops (`Entity.java:406-418`). It has no boat status or water level.

Fixed `AbstractBoat.tick()` uses this order (`AbstractBoat.java:206-247`):

1. Calculate `IN_WATER`, `UNDER_WATER`, `UNDER_FLOWING_WATER`, `ON_LAND`, or `IN_AIR` (`AbstractBoat.java:369-385,459-516`).
2. Run `super.tick()`, which also applies the generic non-player fluid current.
3. If local-authoritative, run `floatBoat()`, zero-input `controlBoat()`, send Paddle, and move.
4. If not local-authoritative, set velocity to zero.

`floatBoat()` applies gravity, water-entry snap, water-level buoyancy, status friction, and status vertical speed (`AbstractBoat.java:524-563`). Zero input does not remove current or buoyancy (`AbstractBoat.java:572-600`). The boat also clips the passenger fluid interaction box when it is not underwater (`AbstractBoat.java:769-780`).

The Plugin also sends packets in the wrong order. Fixed zero-input client order is Paddle during the boat tick, then player Rot, then root MoveVehicle during `LocalPlayer.tick()` (`AbstractBoat.java:234-240`; `LocalPlayer.java:237-243`). The Plugin sends MoveVehicle, Paddle, then Rot (`Player.java:194-207`).

Boat failures therefore remain after chunks work because of four independent defects:

- The generic non-player fluid current is wrong.
- Boat water status and `floatBoat()` are absent.
- local-authoritative relative MoveEntity and PositionSync updates are not ignored.
- The serverbound packet order is wrong.

### 8. Sticky piston retract and carry

The Plugin listens to BlockEntityData but not `ClientboundBlockEventPacket` (`FakePlayerProxyPlugin.java:300-340`). `World.blockEntity()` is the only constructor for `MovingPiston` (`World.java:529-543`).

Fixed Vanilla creates runtime piston state from the BlockEvent:

- Event `0` extends and calls `moveBlocks(..., true)` (`PistonBaseBlock.java:150-173`).
- Event `1` or `2` retracts and creates the retracting source moving block entity (`PistonBaseBlock.java:174-190`).
- Sticky event `1` can also call `moveBlocks(..., false)` and create the pulled block moving entity (`PistonBaseBlock.java:191-211`).
- `moveBlocks()` creates each moved-block entity explicitly (`PistonBaseBlock.java:267-335`). A `MovingPistonBlock` does not create the complete state by itself.

MCProtocolLib decodes this as `ClientboundBlockEventPacket`. Piston raw type maps to `PUSHING`, `PULLING`, or `CANCELLED_MID_PUSH`; the value contains the direction. The packet also contains position and block id.

The current carry calculation is also not Vanilla:

- Fixed code calculates actual axis penetration for each collision shape, caps it at the `0.5` progress delta, adds `0.01`, and calls `entity.move(MoverType.PISTON, ...)` (`PistonMovingBlockEntity.java:120-199`).
- Fixed honey carry is a separate horizontal, on-ground/support test (`PistonMovingBlockEntity.java:201-224`). Sticky piston does not mean sticky entity carry; honey does.
- The Plugin unions the previous and current shapes, inflates them, and directly adds the full `movement + 0.01` to every intersecting pushable entity (`World.java:640-664`). It bypasses piston movement limits and normal collision/effect handling.
- A completed `MovingPiston` remains in the map. `tick()` stops at progress `1.0`, but neither `updateBlock()` nor `tickMovingPistons()` removes it (`MovingPiston.java:68-76`; `World.java:546-564,640-665`). This can leave a stale moving collision.

Missing chunks can hide all piston block state. It cannot supply the missing BlockEvent transition. Sticky retract is an independent implementation defect.

### 9. Ordered implementation list after the P0

This list excludes behavior that the Known Packs/registry/LevelChunk repair should restore by itself.

1. Add fixed 26.2 `ClientboundBlockEventPacket` handling. Build extension, retracting source, sticky pulled-block, and cancelled-mid-push state from the event plus current world blocks.
2. Replace piston carry with per-shape penetration, PushReaction filtering, piston movement limits, honey-only carry, and finished-state removal.
3. Add local-authority guards for relative MoveEntity and EntityPositionSync. For remote PositionSync, update position/onGround but do not write velocity.
4. Remove velocity writes from ordinary interpolation. A later SetEntityMotion must survive an older position interpolation target.
5. Implement entity collision predicates separately from entity push predicates. Do not use every non-zero entity box as a solid obstacle.
6. Replace the shared fluid vector with fixed water/lava trackers. Add the Player average, shallow immersion, full-height, face, loaded-neighbor, scale, and minimum-nudge rules.
7. Implement the fixed boat status and `floatBoat()` path. Preserve generic non-player current before float logic. Add passenger fluid-box behavior.
8. Emit zero-input vehicle packets in fixed order: Paddle, player Rot, root MoveVehicle.
9. Send false/false flags in a PlayerPosition correction acknowledgement. Keep normal movement baselines unchanged.
10. After items 1-9 and the P0, retest isolated player, mob, and projectile knockback plus accepted fall damage. Add no new attack-source or fall-damage branch unless packet traces show a new failure after `AutomationService.motion()` or `ServerGamePacketListenerImpl` acceptance.

## Files Found

- `plugin/src/main/java/com/fakeplayerproxy/FakePlayerProxyPlugin.java`: S2C and C2S packet listener ownership; no BlockEvent listener.
- `plugin/src/main/java/com/fakeplayerproxy/automation/AutomationService.java`: Motion, Position, interpolation, PositionSync, and correction response owners.
- `plugin/src/main/java/com/fakeplayerproxy/automation/Player.java`: local tick, vehicle output, and MovePlayer selection.
- `plugin/src/main/java/com/fakeplayerproxy/automation/Entity.java`: collision movement, interpolation velocity overwrite, fluid entry, and current boat branch.
- `plugin/src/main/java/com/fakeplayerproxy/automation/World.java`: chunk state, collision policy, fluid formula, piston state, and piston carry.
- `plugin/src/main/java/com/fakeplayerproxy/automation/MovingPiston.java`: two-tick progress and direction state.
- `plugin/patch/0002-automation-extension.patch`: raw packet-event dispatch before Velocity decode.
- `minecraft-merged-deobf-26.2-sources.jar!/net/minecraft/server/level/ServerEntity.java`: delayed tracking-and-self hurt Motion.
- `...!/net/minecraft/server/network/ServerGamePacketListenerImpl.java`: movement acceptance, correction, and fall-damage call chain.
- `...!/net/minecraft/client/multiplayer/ClientPacketListener.java`: Motion overwrite, authority guards, PositionSync, teleport, and correction semantics.
- `...!/net/minecraft/world/entity/InterpolationHandler.java`: position/rotation-only interpolation.
- `...!/net/minecraft/world/entity/EntityFluidInteraction.java` and `world/level/material/FlowingFluid.java`: fluid scan, accumulation, current, and face rules.
- `...!/net/minecraft/world/entity/vehicle/boat/AbstractBoat.java`: boat status, buoyancy, authority, passenger fluid box, and Paddle output.
- `...!/net/minecraft/world/level/block/piston/PistonBaseBlock.java` and `PistonMovingBlockEntity.java`: BlockEvent transitions and piston entity carry.
- `mcprotocollib-sources.jar!/org/geysermc/mcprotocollib/protocol/packet/ingame/clientbound/level/ClientboundBlockEventPacket.java`: fixed build-16 BlockEvent fields and piston value decode.

## External References

None. This report uses the fixed local Minecraft 26.2 and MCProtocolLib build-16 sources.

## Related Specs

- `.trellis/spec/backend/velocity-plugin.md`: fixed versions, world ownership, unknown-chunk behavior, movement owners, and output contract.
- `.trellis/spec/backend/quality-guidelines.md`: no additional project rule is defined.
- `.trellis/spec/language/java.md`: Java design guidance; this report makes no production edit.
- `.trellis/tasks/08-11-automation-shadow-infrastructure/prd.md`: Vanilla zero-input position behavior and acceptance scope.
- `.trellis/tasks/08-11-automation-shadow-infrastructure/design.md`: shadow world, player, packet, and vehicle ownership.

## Caveats / Not Found

- No same-connection packet capture was available for the listed live failures. Static evidence proves owner paths and implementation mismatches. It does not prove the exact arrival order in one failed run.
- The player, mob, projectile, and fall classifications assume the clean isolated acceptance tests described above. They require retest after the registry/chunk P0. Do not close them from static analysis alone.
- A data pack can change tags. This report uses the fixed 26.2 built-in tags and the task's fixed-version scope.
- Exact piston event reconstruction also needs the event-time block states around the piston. A BlockEvent listener without those states is not sufficient.
