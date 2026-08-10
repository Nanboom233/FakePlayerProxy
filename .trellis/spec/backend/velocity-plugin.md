# Velocity Plugin Contract

## Scenario: FakePlayerProxy Runtime

### 1. Scope / Trigger

- Trigger: the project now has a Velocity plugin command surface, a generated
  config file, an embedded upstream protocol client, and a pinned Minecraft
  protocol target.
- Scope: `plugin/src/main/java/com/fakeplayerproxy/**`,
  `plugin/src/main/resources/**`, `plugin/src/test/java/com/fakeplayerproxy/**`, and
  `docs/product/operation-guide.md`.
- This runtime contract must not imply online auth, limbo,
  persistence, or full Carpet `/player` parity.

### 2. Signatures

- Velocity plugin main:
  `com.fakeplayerproxy.FakePlayerProxyPlugin`.
- Commands:
  - `/fpp status`
  - `/fpp connect [host] [port] [username]`
  - `/fpp disconnect`
  - `/fpp look-north`
  - `/fpp player self <action>`
  - `/player self <action>`
- Config file:
  `plugins/fakeplayerproxy/fakeplayerproxy.properties`.
- Config keys:
  - `proxy.targetHost`
  - `proxy.targetPort`
  - `proxy.username`
  - `proxy.reconnect.enabled`
  - `proxy.reconnect.maxAttempts`
  - `proxy.reconnect.delayMillis`
  - `proxy.reconnect.authMode`
- Protocol target:
  - Minecraft Java `26.2`
  - protocol version `776`
  - dependency `org.geysermc.mcprotocollib:protocol:26.2-20260709.110151-15`
  - Netty runtime `4.2.17.Final`

### 3. Contracts

- `command` parses user-facing command arguments and renders safe messages.
- `config` owns defaults, file loading, and validation from Java
  `Properties`.
- `automation` owns connection state and exposes only protocol-neutral request/state
  objects.
- `protocol` is the only package allowed to import MCProtocolLib types.
- `ProtocolTarget` is the single source of truth for the runtime's pinned
  Minecraft version and may be read by commands/docs/tests without importing
  MCProtocolLib.
- Pin MCProtocolLib build 15 because it supports Java 17. Exclude its Netty
  `4.2.1.Final` dependency and use Netty `4.2.17.Final`.
- The runtime allows one upstream client per proxy process.
- The protocol version is compile-time pinned. Do not add a runtime
  `minecraftVersion` config key unless the protocol client can actually switch
  codec versions.
- Auto reconnect is implemented only for `offline-controlled`; online reconnect
  requires auth material and secret storage before it can be enabled.
- `/player` is self-only. Accept `self` and the executing player's own username;
  reject every other target.
- `attack`, `use`, `drop`, `dropStack`, and `swapHands` support default/`once`,
  `continuous`, and `interval <ticks>` modes through the automation action scheduler.
- Scheduled actions pause while the upstream client is not play-ready and resume
  after offline-controlled auto reconnect. Manual `stop`, `kill`, and shutdown
  cancel scheduled actions.
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

### 4. Validation & Error Matrix

| Condition | Result |
| --- | --- |
| Missing config file | Create it from bundled defaults |
| Missing required config key | `config_missing_value` |
| Non-numeric port | `config_invalid_port` or `command_invalid_port` |
| Port outside `1..65535` | `config_invalid_value` or `command_invalid_target` |
| Username outside `[A-Za-z0-9_]{3,16}` | typed config/command error |
| Connect while a upstream client is active | `automation_connection_active` |
| Disconnect while idle | `automation_connection_missing` |
| Look-north before play state | `automation_not_play_ready` |
| `/player <other> ...` | `player_not_self` |
| `/player self hotbar 10` | `player_invalid_hotbar` |
| `/player self attack interval 0` | `player_invalid_interval` |
| `/player self drop all` | `DEFERRED` command response |
| `proxy.reconnect.authMode` is online/unknown while enabled | `config_invalid_value` |
| MCProtocolLib startup exception | `automation_connect_start_failed` and safe log |
| Protocol send failure | `protocol_send_failed` or `automation_action_failed` |

### 5. Good/Base/Bad Cases

- Good: run a local offline-mode Minecraft Java `26.2` server on `25566`, then
  run `/fpp connect 127.0.0.1 25566 ProxyBot`; `/fpp status` reaches
  `CONNECTED` with `playReady=true`.
- Good: after play state, run `/player self attack interval 20`; one main-hand
  swing is sent immediately, then future swings repeat every 20 Minecraft ticks
  until `/player self stop`, `/player self kill`, or proxy shutdown.
- Base: run `/fpp connect` with no arguments; defaults are loaded from
  `fakeplayerproxy.properties` or bundled defaults.
- Base: run `/player self drop`; the selected item drop packet is sent once.
- Bad: run against a non-`26.2` upstream server. The runtime may disconnect during
  login or packet handling because packet IDs and shapes are version-specific.
- Bad: run `/player self drop all`; the command returns deferred because full
  inventory traversal and destructive slot drops require an inventory tracker
  and product-level confirmation flow.

### 6. Tests Required

- Config loader:
  - defaults load when no user file exists;
  - user config overrides bundled defaults;
  - invalid port returns a typed error.
- Command parsing:
  - empty connect args use defaults;
  - explicit host/port/username override defaults;
  - invalid or extra args return typed errors.
- State service:
  - active connection rejects duplicate connect;
  - play-ready callback enables look-north;
  - disconnect callback returns to idle;
  - startup failure sets failed and permits retry;
  - unexpected disconnect schedules offline auto reconnect;
  - intentional disconnect does not auto reconnect.
- Carpet command parser:
  - self/own-name accepted and other targets rejected;
  - `look`, `turn`, `hotbar`, `move`, `jump`, `sneak`, `sprint`, `attack`,
    `use`, `drop`, `dropStack`, `swapHands`, and `dismount` parsed;
  - `attack`, `use`, `drop`, `dropStack`, and `swapHands` action modes parsed;
  - full-inventory, entity-target, block-target, and vehicle-sensitive commands
    marked deferred;
  - `mount anything` marked unsupported.
- State service:
  - simple Carpet packet actions call the protocol client;
  - interval actions repeat until `stopActions`;
  - scheduled actions are canceled by manual stop/kill/shutdown.
- Protocol target:
  - `ProtocolTarget` pins Minecraft Java `26.2`, protocol `776`, and
    MCProtocolLib `26.2-20260709.110151-15`.
  - The resolved runtime uses Netty `4.2.17.Final`.
- Boundary:
  - grep for MCProtocolLib imports; concrete MCProtocolLib types should appear
    only under `plugin/src/main/java/com/fakeplayerproxy/protocol`.

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

## Scenario: Velocity Server Hello and Direct Authentication Relay

### 1. Scope / Trigger

- Trigger: `plugin/patch/` and the client-only `mod/` jointly extend the login
  flow through a modified Server Hello.
- Scope: patched online-mode Velocity, a Minecraft 26.2 Fabric client, and an
  online-mode target server.
- Remote authentication is relayed through the ordinary login packets. The
  client-generated AES secret lets Velocity decrypt and proxy the protected
  frontend and backend streams.

### 2. Signatures

- Connection proof message: `[FakePlayerProxy] AES encryption/decryption verified.`
  as one green clientbound system chat message.
- Plugin hook: `FakePlayerProxyPlugin.onServerPostConnect(ServerPostConnectEvent)`.
- IntelliJ IDEA exposes only `server/releaseJar` and `server/runServer`, invoking
  `:plugin:releaseJar` and `:plugin:runServer` respectively.
- Server Hello carrier: a proxy RSA-1024 SPKI with the original target SPKI in an
  OCTET STRING AlgorithmIdentifier parameter encoded as
  `FPPMOD || 0x01 || VarInt(targetKeyLength) || targetSPKI`; original target
  server ID, challenge, and `shouldAuthenticate` remain unchanged.
- Mod acknowledgement plaintext:
  `FPPACK || 0x01 || originalTargetChallenge`.

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
- A vanilla client returns `RSA_proxy(K)` plus
  `RSA_proxy(originalChallenge)`. After decrypting that valid response, install
  frontend AES before sending the observable encrypted mod-required disconnect.
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
- `K` is used for packet encryption/decryption and proxying. It is not an
  authentication credential or a substitute for Mojang authentication.
- Keep connection-proof injection in the Velocity plugin, not the core patch.
  `ServerPostConnectEvent` is emitted after the backend join has completed; its
  subscriber sends the proof once to that event's player.
- Secrets are copied at thread boundaries, zeroed when discarded, never logged,
  and cleared on disconnect.
- The fixed Velocity checkout is reference source only. Patch application and the
  nested Velocity build occur in disposable `plugin/build/server/source/`.
- `releaseJar` must be repeatable on Windows: clear read-only attributes inside
  the disposable Git checkout, require its complete deletion, then recreate it
  before cloning. Never clean or modify a reference checkout.
- `runServer` uses `plugin/run/` as its working directory and deploys the current
  plugin jar to `plugin/run/plugins/` before launch.
- Minecraft 26.2 uses official names; do not add a mappings dependency or Fabric API.
- Keep the Velocity patch minimal. Each changed file and each hunk must implement
  an approved protocol or lifecycle requirement. Remove unrelated formatting,
  import churn, duplicated logic, and speculative fallback paths.
- Reuse an existing Velocity API or handler when it provides the required
  behavior. Do not replace a complete method or lifecycle handler when a narrow
  change can preserve the original flow.
- Do not add a helper, accessor, constructor, or state field unless a required
  patch path cannot use an existing Velocity surface.
- `LoginSessionHandler` owns one `RelayState` for the ordered backend relay:
  `AWAITING_HELLO`, `HELLO_FORWARDED`, `RESPONSE_WRITE_PENDING`,
  `BACKEND_ENCRYPTED`, `LOGIN_SUCCEEDED`, and `CONFIGURATION_RESUMED`.
- Each backend relay event accepts only its exact predecessor. Any failure moves
  the handler to terminal `FAILED`. A late callback must not enable encryption.
- Do not represent this ordered relay with independent boolean fields. Separate
  flags permit impossible combinations and hide the valid transition order.
- Combine successful `PostLoginEvent` completion and the first client settings
  packet with `CompletableFuture`. Future completion is the one-shot guard.
  Do not add a separate configuration-started boolean.
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
- A comment can cite the applicable research file under
  `.trellis/tasks/06-12-fake-player-proxy-research/research/`. The local comment
  still states the verified behavior that the code uses.

### 4. Validation & Error Matrix

| Condition | Result |
| --- | --- |
| Backend public key exceeds the old 256-byte bound but is within the new verified bound | Decode and preserve it for the decorated carrier |
| Decrypted challenge equals the exact original challenge | Treat as Vanilla, install frontend AES, then send the specified encrypted rejection |
| Decrypted challenge equals exact `FPPACK || 0x01 || originalChallenge` | Continue the target login relay |
| Decrypted secret, challenge, envelope, or target key is invalid | Close the owning connection with a user-friendly error and retain diagnostic exceptions in logs |
| Target key response write fails | Close the in-flight connection without enabling backend AES |
| A backend relay event does not match the current `RelayState` | Enter `FAILED` and close the in-flight relay |
| A response write callback completes after `FAILED` | Clear its temporary secret and do not enable backend AES |
| Target sends Login Success before a valid relayed key response | Close both pending legs as a protocol-state failure |
| PostLogin handling or client settings is still pending | Keep backend reads paused until both are complete |
| A second client settings packet completes the same future | Ignore the duplicate completion and resume the target only once |
| Re-running `releaseJar` with a previous disposable checkout present | Remove the complete generated checkout, then clone, apply, and build from the stored patch |
| `ServerPostConnectEvent` in the plugin | Send the connection proof once to the connected player |
| Connection owning relay state ends | Clear target key/challenge and temporary `K` state |
| A patch hunk has no direct approved requirement | Remove the hunk from the stored patch |

### 5. Good/Base/Bad Cases

- Good: a modded 26.2 client generates `K`, authenticates once using the target
  key, returns the acknowledged standard response to Velocity, reaches the
  target, and sees one connection proof message.
- Good: one enum exposes every valid backend relay transition, and one future
  barrier joins the two configuration prerequisites.
- Base: the mod connects to an unpatched server; login bytes and behavior remain vanilla.
- Bad: a vanilla client is dropped before frontend AES is installed and cannot
  observe the rejection, or Velocity performs a Mojang join on the client's
  behalf.
- Bad: client settings resume backend reads while an asynchronous PostLogin
  listener is still running.
- Bad: independent boolean fields describe relay phases or manually join
  PostLogin completion with client settings.
- Bad: the patch replaces a complete Velocity method, adds a parallel state
  machine, or adds a utility for behavior that an existing Velocity API provides.
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
  rather than duplicating the state machine in test-only code.
- Do not add reflection or source-text tests for the enum or future barrier.
  Focused compile and Checkstyle cover this behavior-preserving refactor.
- Build only the affected mod and pinned Velocity modules after narrow changes,
  then run `:plugin:releaseJar` to apply and compile the stored patch in the
  disposable server source directory.
- Live-test the Vanilla rejection, the supported online-backend connection and
  single proof message, and the Mod connection to an unpatched server.
- Do not repeat full-scope checks for an equivalent narrow edit.

### 7. Wrong vs Correct

#### Wrong

```text
git -C <developer-velocity-checkout> apply plugin/patch/0001-server-hello-marker.patch
```

This leaves the reference checkout dirty and makes direct edits indistinguishable
from patch contents.

#### Correct

```text
.\gradlew.bat :plugin:releaseJar
```

The task recreates a disposable checkout under `plugin/build/server/source/`,
applies only the stored patch, and leaves the reference checkout unchanged.

#### Wrong: Broad Patch

```text
Replace a complete login handler and duplicate its normal lifecycle.
```

#### Correct: Required Hunk

```text
Change only the branch that must relay the target Server Hello.
```

The required hunk preserves the existing Velocity behavior around the relay.

#### Wrong: Independent Relay Flags

```java
private boolean relayRequestForwarded;
private boolean relayResponseForwarded;
private boolean relayLoginSucceeded;
```

These flags permit combinations that do not describe a valid protocol stage.

#### Correct: One Ordered Relay State

```java
private RelayState relayState = RelayState.AWAITING_HELLO;

if (relayState != RelayState.HELLO_FORWARDED) {
  failRelay(reason, null);
  return;
}
relayState = RelayState.RESPONSE_WRITE_PENDING;
```

One state value documents the valid predecessor and the next stage.

#### Wrong: Manual Asynchronous Gate

```java
if (postLoginDone && settingsReceived && !configurationStarted) {
  configurationStarted = true;
  resumeTarget();
}
```

#### Correct: One-Shot Future Barrier

```java
postLoginFuture.thenCombine(settingsFuture, (ignored, settings) -> settings)
    .thenAccept(this::resumeTarget);
```

The first settings completion and successful PostLogin completion resume the
target once, regardless of their completion order.

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
