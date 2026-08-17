# Velocity Plugin Contract

## Scenario: FakePlayerProxy Runtime

### 1. Scope / Trigger

- Trigger: an accepted relay player reaches native `PostLoginEvent` with its
  original backend already created and paused.
- Scope: `plugin/src/main/java/com/fakeplayerproxy/**`,
  `plugin/src/main/resources/**`, `plugin/src/test/java/com/fakeplayerproxy/**`,
  `mod/src/main/resources/assets/fakeplayerproxy-mod/lang/**`, and
  `docs/product/operation-guide.md`.
- This runtime contract must not imply online auth, limbo, general gameplay
  persistence, or full Carpet `/player` parity. Only the operator snapshot is
  persisted as specified below.

### 2. Signatures

- Velocity plugin main:
  `com.fakeplayerproxy.FakePlayerProxyPlugin`.
- Commands:
  - `/player shadow`
  - `/player as <player> shadow`
  - `/fpp op <player>`
  - `/fpp deop <player>`
- Player command log: `<username> issued server command: /<command>`
- Authorization config file:
  `plugins/fakeplayerproxy/ops.json`.
- Authorization permission: `fakeplayerproxy.op`.
- Patched Velocity player API: `Player.refreshCommands()` rebuilds and resends
  the latest advertised command tree; it is a no-op without an active backend
  play handler or writable frontend.
- Protocol target:
  - Minecraft Java `26.2`
  - protocol version `776`
  - dependency `org.geysermc.mcprotocollib:protocol:26.2-20260809.160751-16`
  - Netty runtime `4.2.17.Final`
- Player calculation owners:
  - `world.entity.Entity.move(...)`
  - `world.entity.LivingEntity.travel(...)`
  - `world.player.Player.tick()`

### 3. Contracts

- `command` owns the local Brigadier roots, parses user-facing arguments, and
  renders translated messages.
- `config` owns the validated immutable operator snapshot, atomic `ops.json`
  replacement, and the FPP permission-provider wrapper.
- `automation` owns per-player protocol, Shadow lifecycle, and action state.
- `world`, `world/entity`, and `world/player` own world, entity, and player state.
- Use Java 21 and pin MCProtocolLib build 16. The plugin uses patched Velocity,
  MCProtocolLib, and Netty as `compileOnly`; patched Velocity supplies them at runtime.
- Patched Velocity must include MCProtocolLib's complete non-Netty runtime dependency
  closure. Velocity continues to own the Netty version. Do not move MCProtocolLib,
  fastutil, Lombok, or Netty classes into the Plugin JAR.
- When Plugin source uses Lombok, declare the same Lombok version as `compileOnly`
  and `annotationProcessor`; Lombok remains compile-time-only and must not be packaged.
- Fixed Player calculation loads only the committed
  `minecraft-data/minecraft-data.bin` resource. The binary contains runtime table
  data only: do not add format/version/commit/hash identity fields or a companion
  properties resource. Normal builds must not run Minecraft or the data generator.
- Stable Vanilla Known Pack and Dimension Type values are Java constants. Do not
  generate or load an Automation registry properties resource.
- The generator repository's root MIT `LICENSE` is packaged exactly as
  `minecraft-data/minecraft-data-generator-LICENSE`. Do not package old aliases or
  duplicate license paths.
- `AutomationManager` stores `Map<com.velocitypowered.api.proxy.Player, Player>`.
  The Plugin `Player` wraps the exact Velocity Player and owns its `World` and
  `AutomationService`. The service stores a final Plugin `Player` owner and no
  connection field. All mutable state is accessed on that player's connection EventLoop.
- The protocol version is compile-time pinned. Do not add a runtime protocol,
  target, username, or reconnect configuration surface.
- Shadow never creates a second backend connection and does not reconnect or copy
  connection secrets.
- `/player shadow` uses its exact command source. `/player as <player> shadow`
  resolves an active automation player by case-insensitive authenticated name.
  Both paths attach the same complete Brigadier action node.
- `/player` and `/fpp` have no root requirement and remain local to the proxy.
  Their root literals do not need an executor to prevent forwarding. The patched
  player command handler consumes a registered and usable Velocity root.
  Only `/player as`, `/fpp op`, and `/fpp deop` require
  `fakeplayerproxy.op`; protected suggestions remain behind the same child
  requirements.
- `AutomationManager` target lookup and suggestions scan its exact-player map,
  exclude inactive entries, and never use Velocity's public player registry.
- `ops.json` is an array of `{ "uuid": <uuid>, "name": <name> }` entries.
  UUID controls authorization; name is metadata used for display and offline
  revocation. Missing configuration loads an empty set and malformed content
  fails closed without automatic replacement.
- Operator mutations run away from the connection EventLoop, serialize trusted
  map state, atomically replace `ops.json`, and only then publish the immutable
  in-memory snapshot.
- A successful operator mutation refreshes the affected connected player's
  command tree after publication. The fixed Velocity host deep-copies the latest
  untouched backend graph, reinjects proxy nodes through current `.requires(...)`
  predicates, fires `PlayerAvailableCommandsEvent`, and writes only the newest
  completed revision on the frontend EventLoop.
- The FPP permission provider installs at `PostOrder.LAST`, returns `TRUE` for
  the console and stored player UUIDs on `fakeplayerproxy.op`, returns `FALSE`
  for other subjects on that node, and delegates all other nodes to the
  previously selected provider.
- In the patched player `CommandHandler.runCommand`, a registered and usable
  Velocity root confirms interception before execution. Log immediately with the
  Vanilla server template. After confirmation, always consume the command. An
  execution return value or failure must not forward it. Preserve forwarding for
  an unregistered or unusable root. Do not check command names. Do not add or
  change a logging configuration.
- Plugin keys rendered by Velocity are registered from UTF-8 server resource
  bundles in an Adventure `TranslationRegistry`. This includes command messages
  and `fakeplayerproxy.disconnect.shadow`. Register the source during
  initialization and remove it during shutdown. Mod language JSON alone cannot
  translate a component flattened by the Velocity console.
- An accepted Mod connection registers from native `PostLoginEvent` using an
  `EventTask`. Vanilla raw tunnel and ordinary logins have no backend at that point
  and do not create a service.
- `/player shadow` uses the exact command-source `Player`, clears input, sends Stop
  Sprinting, then closes the frontend. `DisconnectEvent.cancel()` retains the
  original backend only when that exact service is shadowing.
- `attack`, `use`, `drop`, `dropStack`, and `swapHands` support default/`once`,
  `continuous`, and `interval <ticks>` modes through the automation action scheduler.
- Scheduled actions run on the connection's 20 TPS EventLoop tick. Manual `stop`,
  `kill`, service replacement, and shutdown cancel scheduled actions.
- Decode every Level Chunk section into a temporary array and install the Chunk only
  after the complete payload succeeds. An unknown or incomplete Chunk is not inserted
  as loaded air. Collision and fluid queries return no result for that Chunk, and
  Player calculation continues without a pause state or waiting queue.
- Send Player Loaded only after the initial position, Level Chunks Load Start, the
  current decoded Chunk, and GAME state are all available.
- Shadow Player calculation runs at no more than 20 TPS while GAME is ready, Player
  Loaded is complete, and the Player is alive. It applies server-authoritative state
  and runs all Vanilla client position behavior that remains when active player input
  is zero. Damage, teleport destinations, sleep relocation, respawn destinations, and
  dimension destinations remain server-authoritative.
- A configuration switch clears world state and pauses GAME calculation. Sending
  Finish Configuration restores GAME readiness; otherwise physics and Client Tick End
  would remain stopped after the switch.
- Movement output selects PosRot, Pos, Rot, or Status from changes against the last
  successfully sent frontend baseline. Position correction and manual look preserve
  and update both `onGround` and `horizontalCollision` in that baseline.
- Player Position acknowledgement uses `false/false` for its collision baseline.
  Relative Move Entity decoding advances a separate codec position. A locally
  authoritative vehicle root advances that codec position without applying the
  server display transform; Position Sync never overwrites velocity.
- Movement collision uses the fixed Entity movement-collision value. Entity push is
  a separate query and remains active for ordinary pushable Living Entities.
- Water and lava retain separate height/current accumulators. Fluid sampling uses
  full-height, shallow-immersion, entity averaging/normalization, fluid-specific
  scale, falling-face, and minimum-current rules. If any required neighboring Chunk
  is unknown, the whole fluid sample is empty.
- Piston Block Events construct extension, retraction, sticky-pull, and cancelled
  transitions from event-time world state. Missing input returns a concrete
  diagnostic and never creates a placeholder. Moving shapes apply per-shape
  penetration, piston reaction, per-axis tick limits, Honey carry, Slime velocity,
  and finalize their moved Block State when complete.
- A locally authoritative Boat calculates status before generic fluid current, then
  applies float behavior and zero-input movement. Non-submerged Boats clip passenger
  fluid interaction. Output order is Paddle, Player Rot, then root Move Vehicle.
- Entity removal detaches both sides of every vehicle/passenger relation and removes
  the Entity from that Player's `World`. The Plugin does not retain a removed marker,
  placeholder Entity, pending relation, or unrelated entity-ground state.
- `attack` is implemented as a main-hand swing only. Target-aware entity attack
  and block breaking require world/entity tracking and must remain documented as
  deferred.
- `use` is implemented as main-hand item use only. Block use, entity
  interaction, offhand fallback, and exact Carpet cooldown behavior require
  target/inventory tracking and must remain documented as deferred.
- `drop`, `dropStack`, and `swapHands` send vanilla selected-slot packets but do
  not verify inventory state yet.
- `dismount` is a shift-input pulse. Vehicle-state-aware behavior remains
  deferred.

## Runtime Package Ownership

`automation/` owns protocol state, Shadow lifecycle, action planning, and action scheduling.

`world/world/` owns the per-player runtime world state.

`world/data/Decoder` decodes and queries immutable fixed-version block, entity,
shape, item, and Dimension Type data. Known Pack policy belongs to
`AutomationService`. `world/data/Block` owns one block-state definition and its
behavior kind.

`world/entity/` owns `Entity`, `LivingEntity`, `Vehicle`, and entity-owned state.
`world/data/EntityTypeData` owns entity geometry, pose data, nested fixed vehicle
data, movement kind, and the `affectedByPiston` value.

`world/player/` owns `Player`, Velocity connection access, actual action packet
execution, player-owned input, passive physics, and movement output.

`world/phys/` contains only `AABB`, `CollisionPhysics`, `FluidPhysics`,
`VehiclePhysics`, and `PistonPhysics`. These classes contain stateless
calculations and do not own connections, protocol state, world state, or tick
state.

`AutomationService` does not proxy world calculations. For example, use IDEA MCP
symbol analysis and refactoring to move the calculation in
`AutomationService.lookAt(...)` to `world/player/Player.lookAt(...)`. Update
`FakePlayerProxyPlugin.onLookAt(...)` to call `Player.lookAt(...)`. Keep the
backend response and EventLoop ordering in `AutomationService`.

Do not create a second world manager, entity manager, physics manager, handler,
service, or tick task for this package split.

### 4. Validation & Error Matrix

| Condition | Result |
| --- | --- |
| Missing `ops.json` | Load an empty player-operator set |
| Malformed `ops.json` | Log the validation failure and deny player operators |
| Atomic operator write fails | Keep the prior file and in-memory snapshot |
| `/fpp op` target is absent or unauthenticated | Translated unavailable response; no mutation |
| `/fpp deop` name is absent | Translated not-found response; no mutation |
| Player enters bare `/player` or `/fpp` | Confirm the registered root, log, and consume without a root executor |
| Intercepted command execution returns any result or fails | Keep the command consumed; never forward it |
| Command root is unregistered or unusable by the player | Preserve forwarding and do not use the interception log path |
| Operator persistence succeeds for a connected player | Publish permissions, then refresh that player's client command tree |
| Operator persistence fails | Keep the prior permissions and do not refresh the client tree |
| An older command-tree event completes after a newer refresh | Discard the older revision |
| Console receives a plugin translatable component | Resolve it through the registered server translation source and console locale |
| Velocity logs a shadow disconnect | Resolve `fakeplayerproxy.disconnect.shadow` through the server translation source |
| PostLogin has no active original backend | Do not create a service |
| `/player` source is not a player | `fakeplayerproxy.command.player_required` |
| `/player` source has no exact service | `fakeplayerproxy.command.automation_unavailable` |
| `/player as` target is inactive or absent | `fakeplayerproxy.command.target_unavailable` |
| Non-shadow action has no exact service | `automation_registration_missing` |
| `/player hotbar 10` | `player_invalid_hotbar` |
| `/player attack interval 0` | `player_invalid_interval` |
| `/player drop all` | `DEFERRED` command response |
| Original backend becomes inactive | remove the exact map entry and cancel its tick |
| `minecraft-data/minecraft-data.bin` is missing or structurally unreadable | Fail resource loading before Player calculation |
| Level Chunk section decoding fails or leaves trailing section bytes | Do not install or replace that Chunk |
| A collision or fluid query reaches an unknown Chunk | Return no collision or fluid from that Chunk and continue the Player tick |
| Finish Configuration completes while Shadow is active | Return to GAME and resume the normal 20 TPS tick |
| Remove Entities references a tracked entity | Detach its relations and remove it from that Player's World |

### 5. Good/Base/Bad Cases

- Good: two accepted Mod players can run actions concurrently. Each action uses
  only the exact command-source `Player` registration and scheduled actions.
- Good: after play state, run `/player attack interval 20`; one main-hand
  swing is sent immediately, then future swings repeat every 20 Minecraft ticks
  until `/player stop`, `/player kill`, or proxy shutdown.
- Base: `/fpp op <player>` records an online authenticated player's UUID and
  makes the protected branches and their suggestions available without reconnecting.
- Base: run `/player drop`; the selected item drop packet is sent once.
- Base: a dead Shadow continues required protocol ticks but does not calculate
  movement or auto-respawn.
- Bad: run against a non-`26.2` upstream server. The runtime may disconnect during
  login or packet handling because packet IDs and shapes are version-specific.
- Bad: treat an absent Chunk as air or add editable metadata/hash checks that have
  no runtime purpose.
- Bad: run `/player drop all`; the command returns deferred because full
  inventory traversal and destructive slot drops require an inventory tracker
  and product-level confirmation flow.
- Bad: update only the permission function and expect the client to discover a
  protected Brigadier child that was absent from its previously received tree.

### 6. Tests Required

- Operator configuration:
  - a missing file loads an empty set;
  - a valid snapshot survives reload;
  - malformed content fails closed without rewriting the file;
  - a failed atomic write does not publish its candidate.
- Permission provider:
  - console, stored UUID, other player, live snapshot change, and delegated
    permission behavior are covered.
- Service lifecycle:
  - a player without the accepted relay backend is not registered;
  - a fresh same-UUID login replaces only the old exact Player/service pair;
  - backend loss removes the exact pair and cancels its tick;
  - Shadow closes only the frontend and retains the original backend.
- Player command:
  - the bare root returns handled and does not enter an action;
  - exact-source `shadow` behavior is preserved;
  - an authorized target can use the shared `shadow` node;
  - unauthorized sources cannot enter `as`;
  - inactive targets report unavailable;
  - target suggestions read the live active-name snapshot.
- FPP command:
  - the bare root returns handled without exposing a configuration branch;
  - authorized `op` and `deop` persist and apply immediately;
  - successful online mutations refresh the affected Player, while failed writes do not;
  - unauthorized sources cannot enter either protected child.
- Command logging:
  - a registered and usable player command root uses the Vanilla format before execution;
  - a confirmed interception remains consumed for every execution outcome;
  - an unregistered or unusable root does not use the interception log path;
  - the patch adds no logging configuration.
- Velocity command graph:
  - a deep copy preserves shared child nodes, redirects, and child cycles;
  - mutating the emitted graph does not mutate the retained backend graph.
- Per-player automation service:
  - two exact Player registrations keep their input and scheduled actions isolated;
  - fresh login removes the old exact Player/service pair and closes it on the old EventLoop;
  - an old tick cannot remove the fresh Player/service pair;
  - simple Carpet packet actions use that Player's existing backend;
  - interval actions repeat until `stopActions`;
  - scheduled actions are canceled by manual stop/kill/shutdown.
- Boundary:
  - the service stores one final Plugin `Player` owner and no connection field;
  - no command resolves a Player by UUID during normal routing;
  - Patch files contain generic Velocity APIs only and no Plugin automation code.
- Fixed Player calculation:
  - deterministic compact export reproduces the committed runtime-only binary;
  - the JAR contains only the exact binary and generator-license resource paths;
  - stable Known Pack and Dimension Type values come from Java constants;
  - Chunk decode installs only a complete section array and preserves the old state
    after malformed input;
  - existing tests are reused after owner migration instead of copied;
  - independent World, relation, Player calculation, `MovementKind`, correction,
    and tick-rate branches have representative behavior assertions;
  - equivalent Packet, Metadata, Attribute, Entity Type, and Block State cases do
    not receive separate tests when they execute the same production branch.

### 7. Wrong vs Correct

#### Wrong

```java
// Command layer imports packet classes and sends packets directly.
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerRotPacket;
```

#### Correct

```java
// Command layer calls a protocol-neutral service method.
ProxyResult<Void> result = automationService.lookNorth();
```

#### Wrong

```properties
# Misleading: this would not actually switch MCProtocolLib's codec.
proxy.minecraftVersion=1.21.5
```

#### Correct

```java
public static final String MINECRAFT_VERSION = "26.2";
public static final int PROTOCOL_VERSION = 776;
```

#### Wrong: Stale Client Command Tree

```java
operators = Map.copyOf(candidate);
// The server permission changed, but the client still has its old filtered tree.
```

#### Correct: Publish Then Refresh

```java
operators = Map.copyOf(candidate);
player.refreshCommands();
```

The refresh rebuilds from the retained backend graph and reuses Velocity's normal
injector. Do not manually add protected literals or bypass `.requires(...)`.

#### Wrong: Non-Executable Local Root

```java
var root = literalArgumentBuilder("player");
```

Velocity treats a bare invocation as unknown and can forward it to the backend.

#### Correct: Locally Handled Root

```java
var root = literalArgumentBuilder("player").executes(context -> 1);
```

#### Wrong: Runtime Metadata Layer

```text
Load a companion metadata file and compare hashes, versions, or generator commits.
```

These values do not select runtime behavior and create a second, misleading data
layer.

#### Correct: Runtime-Only Minecraft Data

```java
private static final String RESOURCE = "/minecraft-data/minecraft-data.bin";
```

Load and structurally decode the one committed binary. Package the generator notice
as `minecraft-data/minecraft-data-generator-LICENSE`.

## Scenario: Velocity Server Hello, Transfer Fallback, And Direct Relay

### 1. Scope / Trigger

- Trigger: `plugin/patch/` and the client-only `mod/` jointly extend the login
  flow through a modified Server Hello.
- Scope: patched online-mode Velocity, Minecraft 26.2 Vanilla and Fabric
  clients, and one fixed online-mode target server.
- Scope: `plugin/patch/*.patch` is the Velocity patch set. The set can contain
  more than one patch file. Patch files contain no test source.
- Each patch file owns one complete function. Do not group patches by changed
  file, task, or development date.
- An accepted Mod connection uses the direct packet relay. Its client-generated
  AES secret lets Velocity decrypt and proxy both protected streams. A Vanilla
  or declined Mod connection completes a short first login, reconnects through
  Transfer, and uses an opaque raw tunnel for the target connection.

### 2. Signatures

- Connection proof message: one green clientbound translatable system chat
  component with key `fakeplayerproxy.message.encryption_verified`; its English
  rendering is `[FakePlayerProxy] AES encryption/decryption verified.`.
- Registration hook: `FakePlayerProxyPlugin.onPostLogin(PostLoginEvent)`.
- Proof hook: `FakePlayerProxyPlugin.onServerPostConnect(ServerPostConnectEvent)`.
- Generic patch API: concrete `ServerboundPacketEvent<T>` and
  `ClientboundPacketEvent<T>`, plus
  `MinecraftConnection.sendPacket(Packet, boolean)` and cancellable `DisconnectEvent`.
- Velocity patch set: `plugin/patch/*.patch`, applied in ascending file-name
  order.
- Patch file name: `<sequence>-<function>.patch`.

- IntelliJ IDEA exposes only `server/releaseJar` and `server/runServer`, invoking
  `:plugin:releaseJar` and `:plugin:runServer` respectively.
- `releaseJar` builds production artifacts only, and `runServer` prepares and
  starts those artifacts. Neither task compiles or executes test source, nor
  depends on `Test`, `check`, `patchCheck`, or another verification task.
- Server Hello carrier: a proxy RSA-1024 SPKI with the original target SPKI in an
  OCTET STRING AlgorithmIdentifier parameter encoded as
  `FPPMOD || 0x01 || VarInt(targetKeyLength) || targetSPKI`; original target
  server ID, challenge, and `shouldAuthenticate` remain unchanged.
- Mod acknowledgement plaintext:
  `FPPACK || 0x01 || originalTargetChallenge`.
- Vanilla fallback entry: a second handshake with intent `TRANSFER` on the
  existing public listener. The raw target is the first server in Velocity's
  static `try` list.
- Vanilla Transfer cooldown: four seconds after successful `PostLoginEvent`
  handling, scheduled on the frontend connection event loop.
- Raw bootstrap owners:
  `TransferTunnelLoginSessionHandler` consumes one `ServerLoginPacket`, and
  `RawTunnelForwardHandler` owns byte forwarding after codec removal.

### 3. Contracts

- Velocity generates and retains the proxy RSA-1024 key pair, but Minecraft's
  client generates the AES secret `K` exactly once.
- After accepted `PreLoginEvent`, resolve the first static forced-host/`try`
  target and open its backend login before sending a frontend Server Hello. Keep
  the provisional player unregistered until the target authenticates it.
- When the backend target sends Server Hello, preserve its server ID, challenge,
  authentication flag, and original SPKI. Raise the backend public-key decode
  limit above the observed 294-byte RSA-2048 SPKI; do not apply that inbound
  limit to the outbound decorated key.
- Encode a JCA-parseable proxy SPKI whose RSA modulus/exponent match Velocity's
  private key and whose AlgorithmIdentifier OCTET STRING contains only protocol
  exact magic/version, target-key length, and target SPKI.
- A Vanilla or declined Mod client returns `RSA_proxy(K1)` plus
  `RSA_proxy(originalChallenge)`. After decrypting that valid response, install
  frontend AES, close the provisional backend, complete a short Login and enter
  Configuration. After successful PostLogin handling, wait four seconds and
  send Transfer to the same public gateway address.
- The two target connections normally share one source IP. The fixed delay
  covers Paper's default 4000-millisecond connection throttle. Run the delayed
  action on the frontend event loop and suppress it when the frontend is closed.
  Do not block a thread or add timer state, retries, or a delay configuration.
- A supported Mod returns `RSA_proxy(K)` plus
  `RSA_proxy(FPPACK || 0x01 || originalChallenge)`. Validate the complete ACK
  and original challenge before continuing.
- For a valid Mod response, construct `RSA_target(K)` plus
  `RSA_target(originalChallenge)`, write that standard response to the target,
  and enable backend AES only after the write. Frontend and backend use the same
  key bytes with independent cipher state.
- Do not call Mojang session services from Velocity and never receive an access
  token. The real client performs the sole target session join.
- Target Login Success is authoritative for UUID, username, properties, and the
  Minecraft 26.2 session ID. Pause backend reads until frontend Login Success is
  acknowledged, asynchronous `PostLoginEvent` handling has completed, and client
  settings are available. Resume the same backend in the existing configuration
  handler only after both PostLogin completion and settings have arrived.
- Do not invoke the normal second `connectToInitialServer` path. Dynamic initial
  server redirection and later online-backend switching are outside this relay
  scope.
- Handle the second `TRANSFER` handshake on the existing public listener before
  Velocity's ordinary Transfer rejection. Resolve only the first static `try`
  server and accept one standard Login Start packet.
- Send the target a replacement handshake with intent `LOGIN`. Preserve the
  client protocol version, host, and port, then send the unchanged Login Start
  fields. Do not accept a client-selected target.
- After the replacement handshake and Login Start are written, remove Minecraft
  framing and packet codecs from both legs. Forward all later bytes in both
  directions with Netty backpressure. The client and target alone know the
  second connection's AES key.
- Before raw handoff, report a stable Login error and log the complete diagnostic
  exception. After handoff, close both channels on a write or channel failure;
  do not inject a Velocity packet into opaque traffic.
- `K` is used for packet encryption/decryption and proxying. It is not an
  authentication credential or a substitute for Mojang authentication.
- Keep connection-proof injection in the Velocity plugin, not the core patch.
  Native `PostLoginEvent` identifies an accepted relay because its original backend
  already exists at that point. Registration completes on the connection EventLoop.
- Shadow state keeps the same `ConnectedPlayer`, `connectedServer`, backend
  connection, cipher state, codecs, and `BackendPlaySessionHandler`. It does not
  create another connection, copy AES secret `K`, or move protocol state.
- Before backend teardown, Velocity publishes and waits for `DisconnectEvent`.
  Cancellation unregisters the old `Player` but keeps the backend and resumes reads;
  ordinary disconnect remains unchanged.
- After the frontend closes, the existing backend handler stays installed. Plugin
  Packet Event listeners maintain protocol responses, player/entity/world state,
  and the 20 TPS service tick on the same connection EventLoop.
- `AutomationManager` stores one exact Velocity Player to Plugin `Player` mapping.
  Fresh login scans the authenticated UUID only during registration, removes the old
  exact mapping, and closes the old Plugin `Player` on its old EventLoop before saving
  the fresh mapping.
- A fresh login runs the same registration flow. It creates a new
  `ConnectedPlayer`, Plugin `Player`, `World`, `AutomationService`, input state, and
  scheduled actions. The old close callback cannot remove the new registration. There
  is no reset, reclaim, reattach, transfer, or ownership operation.
- Secrets are copied at thread boundaries, zeroed when discarded, never logged,
  and cleared on disconnect. Zero AES secret material and its copies. Public
  keys, challenges, acknowledgements, and response-classification bytes are
  public protocol metadata; release them by dropping the owning reference.
- The fixed Velocity checkout is reference source only. Patch application and the
  nested Velocity build occur in a disposable build checkout separate from
  `plugin/build/server/source/`.
- `releaseJar` must be repeatable on Windows: clear read-only attributes inside
  the disposable Git checkout, require its complete deletion, then recreate it
  as a worktree from the fixed source checkout. Never clean or modify the reference
  checkout.
- `runServer` uses `plugin/run/` as its working directory and deploys the current
  plugin jar to `plugin/run/plugins/` before launch.
- Reuse Velocity's existing translatable `ConnectionMessages` components for
  player-visible patch failures. The Mod-owned proof message uses the Mod's
  translation resources. Do not introduce hard-coded player-visible patch or
  proof text.
- Minecraft 26.2 uses official names; do not add a mappings dependency or Fabric API.
- Keep the Velocity patch minimal. Each changed file and each hunk must implement
  an approved protocol or lifecycle requirement. Remove unrelated formatting,
  import churn, duplicated logic, and speculative fallback paths.
- Keep production changes in the Velocity patch set. Do not add or change test
  source in a Velocity patch file.
- Keep all test source outside the Velocity patch set. A production change can
  use a separate numbered patch file instead of changing an existing patch.
- Group patch hunks by function. Keep all production hunks for one function in
  the same patch file, even when they change multiple Velocity classes.
- Put independent functions in separate patch files. Do not use one patch file
  as a chronological record of unrelated changes.
- Apply all patch files in ascending file-name order. Do not treat one named
  patch file as the complete patch boundary.
- Prefer newer Java language features and standard-library APIs when they make
  the patch code more concise, readable, or performant.
- Reuse an existing Velocity API or handler when it provides the required
  behavior. Do not replace a complete method or lifecycle handler when a narrow
  change can preserve the original flow.
- Do not add a helper, accessor, constructor, or state field unless a required
  patch path cannot use an existing Velocity surface.
- Do not add a second phase enum to `LoginSessionHandler`. One
  `TargetHello(publicKey, challenge)` record owns the pending Server Hello, and
  the response `ChannelFuture` owns the later plaintext-write boundary.
- Validate later handoffs with the active handler, successful response future,
  installed cipher pipeline, paused auto-read state, and incomplete connection
  result. A late callback must not enable encryption after ownership changes.
- Do not represent relay phases with independent boolean fields. Use the
  lifecycle objects that already own each asynchronous boundary.
- `AuthSessionHandler` selects one construction-time continuation:
  `InitialServer`, `Relay(player, backend, targetSessionId)`, or
  `Transfer(player)`. Use exhaustive variants instead of independent nullable
  fields and a transfer boolean. These variants select the action after Login;
  they are not temporal protocol phases or a destination-mapping layer.
- Combine successful `PostLoginEvent` completion and the first client settings
  packet with `CompletableFuture`. Run the combined connection mutation on the
  frontend event loop because a Plugin can complete its event future from
  another thread. Future completion is the one-shot guard; do not add a separate
  configuration-started boolean.
- After raw handoff, pass an accepted inbound `ByteBuf` directly to the peer
  write. The source handler no longer needs that reference, so the peer write
  takes ownership. Release locally only when input is rejected before transfer.
- Comments are part of the patch design. They must explain the relay to a reader
  who does not know the task history.
- Each changed class explains its place in the complete connection flow. It
  explains the input, state owner, result, and next handler.
- Each relay method explains why Velocity changes its normal order. It also
  explains the benefit of reusing the existing packet and lifecycle code.
- Inline comments explain cipher ordering, event-loop transfer, temporary-secret
  ownership, target identity, and cleanup. They also explain failure handling.
- Do not use one short comment as proof that a complex method is documented.
  Comments must connect the frontend and backend stages into one readable flow.
- Write comments in ASD-STE100 Simplified Technical English. STE controls the
  wording. It does not replace the technical explanation.
- A comment can cite an applicable task research file, including
  `.trellis/tasks/08-10-client-login-negotiation-research/research/paper-connection-throttle.md`.
  The local comment still states the verified behavior that the code uses.

### 4. Validation & Error Matrix

| Condition | Result |
| --- | --- |
| Backend public key exceeds the old 256-byte bound but is within the new verified bound | Decode and preserve it for the decorated carrier |
| Decrypted challenge equals the exact original challenge | Treat as Vanilla, install frontend AES, close the provisional backend, complete the short Login, and Transfer to the same gateway |
| Decrypted challenge equals exact `FPPACK || 0x01 || originalChallenge` | Continue the target login relay |
| Decrypted secret, challenge, envelope, or target key is invalid | Close the owning connection with a user-friendly error and retain diagnostic exceptions in logs |
| Target key response write fails | Close the in-flight connection without enabling backend AES |
| A backend relay event does not match its field, handler, pipeline, or future owner | Close the in-flight relay while that handler still owns it |
| A response write callback completes after handler ownership changes | Clear its temporary secret and do not enable backend AES |
| Target sends Login Success before a valid relayed key response | Close both pending legs as a protocol-state failure |
| PostLogin handling or client settings is still pending | Keep backend reads paused until both are complete |
| A second client settings packet completes the same future | Ignore the duplicate completion and resume the target only once |
| Vanilla or declined Mod PostLogin completes | Wait four seconds on the frontend event loop, then send Transfer if the frontend remains open |
| Second handshake has intent `TRANSFER` | Resolve the first static `try` target and install the raw Login bootstrap handler |
| Raw bootstrap receives Login Start | Send a `LOGIN` handshake and unchanged Login Start fields, then remove codecs and forward bytes |
| Raw bootstrap fails before handoff | Send a stable Login disconnect and close both legs; log a diagnostic failure with its complete `Throwable` |
| Either raw leg closes or a raw write fails after handoff | Close both channels without injecting a Minecraft packet |
| Re-running `releaseJar` with a previous disposable checkout present | Remove the complete generated worktree, then recreate, apply, and build from the Velocity patch set |
| PostLogin has the original relay backend | Register the exact Player service on its EventLoop |
| `/player shadow` has an active backend and false shadow state | Set shadow state, disconnect frontend, and keep the backend connection |
| `/player shadow` has no active backend or shadow state is already true | Return false and keep the current connection state |
| Shadow backend receives keepalive | Reply on the same backend connection and consume the packet |
| Shadow backend disconnects | Exact service tick observes inactive backend and closes the service |
| Fresh login replaces the same UUID before the old close callback | Keep the fresh registration because removal matches both UUID and value |
| Connection owning relay state ends | Clear target key/challenge and temporary `K` state |
| A patch hunk has no direct approved requirement | Remove the hunk from its patch file |
| The patch directory contains multiple patch files | Apply all patch files in ascending file-name order |
| One patch file contains independent functions | Split it into one patch file for each function |
| One function changes multiple Velocity classes | Keep those hunks in the same function patch |
| A patch file adds or changes test source | Reject the patch file until the test change is outside the patch set |

### 5. Good/Base/Bad Cases

- Good: a modded 26.2 client generates `K`, authenticates once using the target
  key, returns the acknowledged standard response to Velocity, reaches the
  target, and sees one connection proof message.
- Good: a Vanilla or declined Mod client completes the short encrypted login,
  waits through Paper's default throttle window, follows Transfer to the same
  listener, and reaches the fixed target through an opaque raw tunnel.
- Good: existing handler, write-future, pipeline, and connection ownership guard
  the relay, and one future barrier joins the two configuration prerequisites.
- Good: one numbered patch keeps the base relay change, and a later numbered
  patch adds an independent production change.
- Good: an accepted Mod player runs `/player shadow`, the frontend connection
  closes, and the same backend connection continues through keepalive packets.
- Good: the player uses a fresh login after the shadow backend closes. The new
  registration has new input state and scheduled actions.
- Base: the mod connects to an unpatched server; login bytes and behavior remain vanilla.
- Bad: a Vanilla client is dropped after classification instead of receiving
  Transfer, or Velocity performs a Mojang join on the client's behalf.
- Bad: the raw tunnel accepts a client-selected target, opens a second listener,
  or keeps packet decoders active after the handoff.
- Bad: client settings resume backend reads while an asynchronous PostLogin
  listener is still running.
- Bad: independent boolean fields describe relay phases or manually join
  PostLogin completion with client settings.
- Bad: the patch replaces a complete Velocity method, adds a parallel state
  machine, or adds a utility for behavior that an existing Velocity API provides.
- Bad: Transfer is sent immediately, a thread sleeps for the cooldown, or timer
  state and retry parsing duplicate the existing future and connection lifecycle.
- Bad: shadow creates another backend connection, copies `K`, swaps the play
  handler, or adds ownership and transfer state.
- Bad: a Velocity patch file adds a file under an upstream test source directory.
- Bad: the code has local comments, but a reader cannot follow the full relay from
  the target Hello to target configuration.

### 6. Tests Required

- Automated tests must invoke the patched production protocol logic with real
  proxy and target RSA key pairs. The exercised flow constructs and parses the
  decorated SPKI, decrypts and classifies standard Vanilla and Mod responses,
  reconstructs the target response, and lets the target private key recover the
  original AES key and challenge.
- Assertions may verify only outputs and state produced by that executed flow.
  Do not use source/patch text checks, private-field reflection, task-graph
  inspection, constant-only checks, or class-presence checks as relay coverage.
- Verify cipher ordering and relay lifecycle through the real connection flow,
  rather than duplicating lifecycle ownership in test-only code.
- Exercise the production raw entry with framed Handshake and Login Start input.
  Assert the rewritten intent, unchanged handshake and Login Start fields, exact
  bytes in both directions, close propagation, and target-connect failure.
- Do not add reflection or source-text tests for lifecycle fields or the future barrier.
  Focused compile and Checkstyle cover this behavior-preserving refactor.
- Do not add a dedicated unit test for the fixed delay or executor selection.
  Verify the observable interval and absence of Paper's throttle disconnect in
  the focused Vanilla live check.
- Command tests cover targetless grammar, `shadow` service routing, and one
  existing action routed by exact source `Player`.
- `AutomationManager` tests cover no-backend exclusion and same-UUID fresh login
  replacement with exact Player/service removal.
- `BackendPlaySessionHandler` tests cover the existing frontend branch and the
  continuation branch for keepalive, backend disconnect, and ordinary packet consume.
- Keep these tests outside `plugin/patch/*.patch`. No patch file can add or
  change upstream test source.
- Do not add dedicated tests for field shape, source text,
  i18n resources, or fixed implementation details.
- Build only the affected mod and pinned Velocity modules after narrow changes.
  Then run `:plugin:releaseJar` to apply and compile the Velocity patch set.
- `:plugin:test` owns the Java 21 runtime smoke. Its isolated class loader uses
  the final `velocity.jar` as its only application dependency. The smoke must
  decode Select Known Packs, round-trip a non-air Chunk Section, and execute the
  Lombok call used by MCProtocolLib. A class-presence or source-text assertion is
  not sufficient for this runtime gate.
- Live-test the Vanilla Transfer/raw fallback, accepted Mod online-backend
  connection and single proof message, declined Mod fallback, Escape behavior,
  and the Mod connection to an unpatched server.
- Do not repeat full-scope checks for an equivalent narrow edit.

### 7. Wrong vs Correct

#### Wrong

```text
git -C <upstream-checkout> apply plugin/patch/0001-login-relay.patch
```

This leaves the reference checkout dirty and makes direct edits indistinguishable
from patch contents.

#### Correct

```text
.\gradlew.bat :plugin:releaseJar
```

The task creates a separate build checkout.
It applies the Velocity patch set and leaves the reference checkout unchanged.

#### Wrong: Broad Patch

```text
Replace a complete login handler and duplicate its normal lifecycle.
```

#### Correct: Required Hunk

```text
Change only the branch that must relay the target Server Hello.
```

The required hunk preserves the existing Velocity behavior around the relay.

#### Wrong: Tests In A Velocity Patch

```text
plugin/patch/0002-continuation.patch adds proxy/src/test/**
```

#### Correct: Production-Only Patch Set

```text
plugin/patch/0001-login-relay.patch
plugin/patch/0002-automation-connection.patch
```

Each patch owns one function. The build applies the patch files in file-name
order. Test source stays outside the Velocity patch set.

#### Wrong: Independent Relay Flags

```java
private boolean relayRequestForwarded;
private boolean relayResponseForwarded;
private boolean relayLoginSucceeded;
```

These flags permit combinations that do not describe a valid protocol stage.

#### Correct: Existing Lifecycle Ownership

```java
if (backend.getActiveSessionHandler() != this
    || responseWrite == null
    || !responseWrite.isSuccess()) {
  logger.error("Cannot enable backend encryption: response write no longer owns the active relay");
  failRelay(reason);
  return;
}
```

The handler and write future already identify the valid owner and completed boundary.
Validation failures log their concrete condition; the cleanup helper does not take
a nullable exception placeholder. A real caught exception is passed to the logger
as a `Throwable`.

#### Wrong: Manual Asynchronous Gate

```java
if (postLoginDone && settingsReceived && !configurationStarted) {
  configurationStarted = true;
  resumeTarget();
}
```

#### Correct: One-Shot Future Barrier

```java
postLoginFuture.thenAcceptBothAsync(settingsFuture,
    (ignored, settings) -> resumeTarget(settings), frontend.eventLoop());
```

The first settings completion and successful PostLogin completion resume the
target once, regardless of their completion order, and the connection mutation
runs on its owning event loop.

#### Wrong: Comment Without Context

```java
// Enable encryption after the write.
backend.enableEncryption(secret);
```

#### Correct: Flow And Reason

```java
// The target reads this response before it enables AES. Wait for the write.
// This order keeps the first encrypted target packet readable by Velocity.
backend.enableEncryption(secret);
```

The correct comment explains both peers, the required order, and the benefit.
