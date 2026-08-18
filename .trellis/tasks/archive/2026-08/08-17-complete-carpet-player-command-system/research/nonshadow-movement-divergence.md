# Research: Non-shadow movement divergence between backend and real client

- Query: What happens when Velocity injects `ServerboundMovePlayer*` for a non-shadow online player, causing the backend position to diverge from the real client's local position?
- Scope: internal (archived Minecraft Java 26.2 Vanilla sources, repository Velocity patch/API, and current plugin chain); no network access
- Date: 2026-08-18

## Findings

### Short conclusion

An accepted ordinary `ServerboundMovePlayer*` is **not echoed to that same player's frontend**. The backend adopts the packet, updates server collision/on-ground/fall/known-movement state and player chunk tracking, and normal entity tracking publishes the changed player entity to *other* tracking players. The real client stays at its locally simulated position until one of these happens:

1. its own later movement packet is accepted and overwrites the backend position;
2. a movement packet is rejected and the backend sends `ClientboundPlayerPositionPacket` to correct the real client;
3. some independent server teleport sends that same correction packet.

Therefore movement injection without frontend synchronization is not a stable way to control a non-shadow player. It is at best a transient server-side overwrite and at worst creates persistent split-brain state, rubber-banding, invalid interactions, wrong chunk delivery, or anti-flight/movement correction.

### Files found

- `.trellis/tasks/archive/2026-08/08-11-automation-shadow-infrastructure/research/_evidence/vanilla/net/minecraft/server/network/ServerGamePacketListenerImpl.java` - authoritative 26.2 movement validation, teleport correction, vehicle movement, and teleport acknowledgement.
- `.trellis/tasks/archive/2026-08/08-11-automation-shadow-infrastructure/research/_evidence/vanilla/net/minecraft/client/multiplayer/ClientPacketListener.java` - local-player correction, teleport acknowledgement, entity sync, vehicle correction, motion, and chunk-cache center handling.
- `.trellis/tasks/archive/2026-08/08-11-automation-shadow-infrastructure/research/_evidence/vanilla/net/minecraft/client/player/LocalPlayer.java` - local movement simulation output cadence and packet variants.
- `.trellis/tasks/archive/2026-08/08-11-automation-shadow-infrastructure/research/_evidence/vanilla/net/minecraft/server/level/ServerEntity.java` - tracking-player position broadcasts and the distinct motion-to-self path.
- `.trellis/tasks/archive/2026-08/08-11-automation-shadow-infrastructure/research/_evidence/vanilla/net/minecraft/world/entity/Entity.java` - collision/on-ground flag semantics.
- `plugin/patch/0002-automation-extension.patch` - direct backend packet send, synchronous packet event cancellation, and clientbound non-cancellation contract.
- `plugin/src/main/java/com/fakeplayerproxy/FakePlayerProxyPlugin.java` - current passive tracking of real-client movement and server player-position packets.
- `plugin/src/main/java/com/fakeplayerproxy/automation/AutomationService.java` - current shadow-only teleport acknowledgement and shadow-only movement ticking.
- `plugin/src/main/java/com/fakeplayerproxy/world/player/Player.java` - current movement packet generation and local baseline tracking.

### Backend acceptance and rejection

For a loaded, non-passenger player with no pending teleport, `handleMovePlayer` resolves omitted coordinates/rotation against current server state, validates finite values, clamps coordinates, and checks movement from the tick's `firstGood` position. Invalid numeric values disconnect. Excess movement (`movedDist - expectedDist > 100 * deltaPackets`, or `300 * deltaPackets` while fall-flying) sends a correction teleport and returns. More than five movement packets in one tick is logged and the multiplier is clamped back to one; extra proxy packets do not gain unbounded tolerance (`ServerGamePacketListenerImpl.java:1050-1103`).

The accepted path collision-moves from `lastGood` to the packet target, rejects a sufficiently large residual/new collision by teleporting to the pre-packet position, or else snaps exactly to the target. It then updates chunk tracking, copies packet `onGround` and `horizontalCollision`, performs fall damage/statistics, stores known movement, and advances `lastGood` (`ServerGamePacketListenerImpl.java:1106-1177`). At each server tick, `firstGood` and `lastGood` reset to the current backend position; the player tick is run and positional drift from that tick is snapped back before packets are processed (`ServerGamePacketListenerImpl.java:313-321,375-382`). Thus ordering within a tick and across a tick boundary materially changes the outcome.

The movement packet's displacement becomes `knownMovement`, not the entity's network velocity (`ServerGamePacketListenerImpl.java:2162-2168`). Packet flags directly become backend ground/collision state (`Entity.java:672-680`). Consequently a proxy can perturb fall damage, jump-edge detection, impulse grace reset, floating detection, and movement statistics even when the position is soon overwritten. It does not directly make the real client's local velocity match. A separate server motion update uses `ClientboundSetEntityMotionPacket`; the client applies it through `lerpMotion` (`ClientPacketListener.java:624-629`).

### What the backend sends to the frontend

On ordinary acceptance, `handleMovePlayer` contains no send to the moving player's connection. `ServerEntity` emits position/rotation packets with `sendToTrackingPlayers`, not `sendToTrackingPlayersAndSelf` (`ServerEntity.java:125-196`). The latter is used explicitly for hurt-marked velocity (`ServerEntity.java:220-224`). This distinction is the direct evidence that accepted ordinary player movement is visible to other tracking clients but not reflected back to the controlling client.

On rejection, the server calls `teleport`: it increments `awaitingTeleport`, updates server position, records `awaitingPositionFromClient`, and sends `ClientboundPlayerPositionPacket` to that player (`ServerGamePacketListenerImpl.java:1232-1245`). The real client applies the absolute/relative position, rotation, and packet delta movement immediately (unless the player is a passenger), then sends `ServerboundAcceptTeleportationPacket` followed by a `PosRot` packet (`ClientPacketListener.java:796-826`). This is the rubber-band/correction path.

The patched Velocity clientbound event cannot be cancelled; it can only replace a packet (`0002-automation-extension.patch:91-141`; test `PacketEventTest.java:57-68`). Therefore the existing plugin's observation of a backend player-position packet cannot silently prevent the real frontend correction. The plugin updates its model for every such packet, but sends its own teleport acknowledgement and movement response only while shadowing (`FakePlayerProxyPlugin.java:463-470`; `AutomationService.java:173-181`). In non-shadow mode, the real client's ack/response remains responsible.

While a teleport acknowledgement is pending, further move packets do not change position; only rotation is adopted. The server resends a correction after more than 20 server ticks. A matching teleport ID snaps to the awaited position, updates `lastGood`, and clears the wait (`ServerGamePacketListenerImpl.java:527-544,1061-1066,1200-1213`). Ordinary accepted movement has no teleport ID and needs no acknowledgement.

### Three concrete timelines

#### 1. Proxy injects once; real-client packets continue

`A` is the real client's local position and initial backend position; `B` is the injected target.

1. Proxy writes `Pos/PosRot(B)` directly to the backend.
2. If validation/collision accepts it, backend becomes `B`, updates ground/fall/known movement and chunk tracking, and other observers may see the player at `B`. No position packet is sent to this player's frontend, which stays at `A`.
3. The real client sends its next position-bearing movement when it moves, or at the 20-tick reminder even if effectively stationary (`LocalPlayer.java:264-299`).
4. If `Pos(A')` arrives in the same server tick and passes collision, it is measured for the speed check against the tick's original `firstGood` and can overwrite `B` with `A'`. In open space, a short-lived injection can therefore disappear without the frontend ever moving.
5. If a server tick ends with backend at `B`, the next tick's baseline becomes `B`. A sufficiently distant client packet back toward `A'` is now "moved too quickly" and causes a correction to `B`; a nearby/collision-valid packet can still overwrite `B`.

Feasibility: only suitable for small, opportunistic, transient changes where losing to the next real packet is acceptable. It cannot guarantee a visible player displacement. A large one-shot displacement is normally rejected immediately; a displacement that survives a tick can make the later real packet trigger rubber-banding.

#### 2. Proxy injects continuously; real-client packets are not intercepted

1. Both streams enter the same backend connection and are processed serially in arrival order. There is no source identity in Vanilla movement handling: each accepted packet simply becomes the next target.
2. Within tolerance/open space, backend state oscillates between proxy position `B(t)` and client position `A(t)`; the last accepted packet at observation/tick time wins. Other clients, collision checks, interaction reach, and chunk tracking observe whichever server state is current. The controlling frontend still renders and locally collides at `A(t)` because accepted movements are not echoed to it.
3. Crossing a tick boundary makes the prior winner the next speed baseline. With separated streams, one side can repeatedly trigger `ClientboundPlayerPositionPacket`, causing visible snaps and teleport ack traffic. More than five packets per tick is logged and evaluated with a one-packet speed allowance (`ServerGamePacketListenerImpl.java:1088-1101`).
4. Contradictory `onGround`/horizontal-collision flags are also last-packet-wins and can produce incorrect fall/floating state. Backend velocity remains a separate state, so neither stream makes frontend and backend velocity coherent.

Feasibility: not deterministic control. It is a race between two valid movement producers, sensitive to latency, EventLoop ordering, and server tick boundaries. It produces oscillation or corrections rather than a stable combined trajectory.

#### 3. Proxy injects continuously and cancels real-client movement, but does not correct the frontend

1. A synchronous concrete `ServerboundPacketEvent<ServerboundMovePlayer...>` can call `cancel()`; the decoder then returns `null` and Velocity does not forward that packet (`0002-automation-extension.patch:223-277,2853-2933,3807-3839`). Each of the four movement variants must be covered; otherwise rotation/status or periodic position packets still leak through.
2. Proxy packets sent with the patched backend `sendPacket` default bypass go directly to the backend (`0002-automation-extension.patch:1183-1205`) and can advance `B(t)` incrementally within validation limits.
3. Backend, observers, collisions, interaction reach, and chunk tracking follow `B(t)`. Frontend camera/body/local physics remain at `A(t)` indefinitely because no accepted-movement echo exists and this scenario supplies no `ClientboundPlayerPositionPacket` replacement.
4. Backend chunk tracking is explicitly moved on each accepted packet (`ServerGamePacketListenerImpl.java:1152`). The client independently obeys backend `ClientboundSetChunkCacheCenterPacket` by moving its chunk-cache view center (`ClientPacketListener.java:2420-2423`). It can therefore receive/unload chunks around `B` while its camera remains at `A`, leading to missing/wrongly centered terrain and entity visibility at the rendered location.
5. Client-originated use/attack/dig packets are still judged against backend position `B` (for example block use checks `isWithinBlockInteractionRange`, `ServerGamePacketListenerImpl.java:1326-1334`). What the crosshair predicts at `A` can fail server reach/line/world checks, or act against state near `B` if packet target data happens to be valid there. Local block prediction may then be corrected by normal block acknowledgements/updates, not by moving the player.

Feasibility: backend-only movement is technically sustainable if injected steps pass validation, but the user-facing session is incoherent and not usable as normal gameplay. Stable control requires also making the frontend adopt the authoritative position (or disconnecting it, i.e. shadow ownership). Merely cancelling client movement is insufficient.

### Collision, visuals, interactions, and chunks

- **Visuals:** the controlling client remains at its locally simulated position until a player-position correction. Other players track the backend position through ordinary entity movement broadcasts.
- **Block collision:** proxy targets are checked by the backend from backend `lastGood`; the real client continues local collision from its own position and currently loaded client world. The two results can disagree without an immediate correction if both packet paths remain individually acceptable.
- **Entity collision/push:** backend entity relationships are evaluated at backend position. A later server impulse can send motion to self, changing client velocity but still not inherently reconciling position.
- **Chunks/entities:** accepted movement calls backend chunk-source `move(player)`. A persistent divergence shifts subscriptions/cache-center and tracked entities toward backend position, even though camera position did not move.
- **Interactions:** backend reach and state checks use backend player position. Frontend raycast/animation at the local position is predictive only and can be denied or corrected separately.

### Vehicles

`ServerboundMovePlayer*` does not move a passenger's position: while passenger, the backend only applies player rotation and keeps the existing passenger position (`ServerGamePacketListenerImpl.java:1070-1072`). Vehicle control uses `ServerboundMoveVehiclePacket` and requires that the player control the same root vehicle recorded for the tick. Too-fast/wrong/colliding vehicle motion is rejected with `ClientboundMoveVehiclePacket`; accepted motion updates the vehicle, passenger chunk tracking, movement/fall state, and vehicle `lastGood` (`ServerGamePacketListenerImpl.java:443-518`).

Unlike ordinary player movement, the real client handles a vehicle correction by snapping its locally authoritative root vehicle and immediately echoing `ServerboundMoveVehiclePacket` (`ClientPacketListener.java:2142-2163`). Thus injecting only player movement while mounted is ineffective, while racing injected and real vehicle packets creates a separate correction/ack loop and visible vehicle snapping.

### High-latency boundaries

Vanilla processes arrival order and server tick state; movement packets carry no client timestamp. High latency increases the chance that a proxy overwrite remains through a tick boundary and becomes `firstGood`, after which delayed real-client packets are judged as movement from the proxy position. It also delays teleport acknowledgement. While awaiting an ack, position packets are suppressed and the same correction is resent after more than 20 server ticks. These are deterministic ordering consequences; exact visual duration depends on network latency and server scheduling and is not derivable from the archived sources.

### Existing plugin-chain implications

The current implementation already avoids this failure mode for scheduled locomotion: every in-game service tick advances passive state, but `Player.tick(...movementEnabled...)` and movement output occur only under `shadow && inGame` (`AutomationService.java:341-369`; `Player.java:833-844,936-968`). Real-client movement events merely update the proxy's model and are not cancelled (`FakePlayerProxyPlugin.java:750-784`). This boundary is necessary: non-shadow action commands may send discrete actions/rotation, but proxy-owned continuous positional physics must not compete with an attached Vanilla client.

## Recorded product decision

The current phase keeps positional movement under shadow ownership. A
non-shadow real client remains the only producer of its normal movement.

The shadow-only command set is `jump`, `move`, `sprint`, `unsprint`, and
`kill`. The first four commands depend on proxy-owned movement state. `kill`
depends on the frontend already being detached by shadow.

The implementation must reject these commands when the resolved target is not
shadow. The `as` player suggestion must list only current shadow targets.

## Brigadier visibility boundary

Standard Brigadier `.requires(Predicate<CommandSource>)` can inspect only the
command source. It cannot inspect the parsed `<player>` argument. It can filter
a self command node, but it cannot decide whether the target in
`/player as <player>` is shadow.

The pinned Brigadier fork also provides `requiresWithContext`. It can inspect
the parsed target during server parsing and server-side completion. The
Minecraft available-command protocol does not encode this predicate.
`CommandGraphInjector` also copies child nodes by the source-only requirement.
Therefore a context requirement cannot remove a target-dependent literal from
the command tree already sent to the real client.

The approved solution filters the existing `<player>` suggestion helper. It
returns only targets whose automation service is shadow. Each completion
request reads current service state, so the solution does not refresh the
command tree and does not add another suggestion provider.

The player argument appears before the action. Therefore the filter applies to
all `/player as <player>` forms, including `shadow` and non-movement actions.
An operator can still type a non-shadow name manually. Shadow-only action
nodes use a context requirement and the service guard to reject that target.
Other actions retain their existing manual target behavior.

## External References

None. Network access was explicitly disallowed. All protocol and behavior evidence is repository-local and version-pinned to Minecraft Java 26.2 / protocol 776 by `.trellis/spec/backend/velocity-plugin.md`.

## Related Specs

- `.trellis/spec/backend/velocity-plugin.md` - non-shadow ticks are passive only; only shadow runs local player movement and sends movement/tick-end packets; movement baseline and teleport acknowledgement contracts.
- `.trellis/spec/guides/cross-layer-thinking-guide.md` - relevant to keeping frontend prediction, proxy model, and backend authority consistent.

## Caveats / Not Found

- The archived Vanilla evidence does not include the complete server chunk-map implementation, so this report does not assert an exact chunk-center packet delay or unload schedule. It relies only on the proven accepted-move call to `getChunkSource().move(player)` and the client's proven cache-center handler.
- Exact anti-cheat behavior can be changed by backend mods/plugins or the `PLAYER_MOVEMENT_CHECK` game rule. Conclusions above describe the archived Vanilla 26.2 path.
- Collision acceptance depends on world geometry, game mode, impulse grace, sleeping/passenger/dimension state, and packet ordering. The report intentionally does not claim that every displacement below a numeric threshold is accepted.
- "Interception" in case 3 is hypothetical. The current plugin registers movement observers but does not cancel them, and its production movement loop remains shadow-only.
