# Task Chain Implementation Blueprint

This blueprint turns the research conclusion into an implementation-ready task
chain. It is still planning material. Do not start implementation from this
research task; create/start the child task approved by the user.

## Scope Guard

The implementation target is a Velocity plugin plus an embedded MCProtocolLib
upstream client. Stock Velocity plugin-only behavior is not sufficient for
headless upstream automation after the real client disconnects.

The MVP accepts "re-login shadow":

1. The real player configures and requests automation through Velocity.
2. The plugin starts a separate upstream Minecraft client for the same owner.
3. The real client is moved to limbo or disconnected after the automated client
   reaches play state.
4. Auto reconnect works only for auth modes that explicitly provide enough
   auth material.

The MVP does not promise zero-drop handoff of an existing Velocity backend
connection. That remains a conditional patch spike.

## Pre-Implementation Decisions

These decisions are user/operator choices and should be recorded before running
`task.py start` on the first implementation task.

| Decision | Recommended default | Why it matters |
| --- | --- | --- |
| Upstream policy | Controlled or explicitly authorized servers only | Avoids automation abuse, ToS risk, and anti-cheat incompatibility as baseline. |
| Minecraft target version | One current Java Edition minor first | Packet classes and login/configuration states move between versions. |
| Runtime Java version | Match selected Minecraft/Velocity requirement | Avoids building against a Java level unsupported by the server runtime. |
| Literal `/player` command | Configurable, disabled by default until consent flow exists | Avoids shadowing backend `/player` unexpectedly. |
| Reconnect auth | Offline/owned first; access-token-only spike second | Proves automation loop before storing sensitive refresh material. |
| Seamless handoff | Later patch spike only | Stock Velocity lifecycle closes backend connections with `ConnectedPlayer`. |

## Recommended Task Tree

Create these after the research task is accepted:

```powershell
$parent = python ./.trellis/scripts/task.py create "Fake Player Proxy MVP" --slug fake-player-proxy-mvp
python ./.trellis/scripts/task.py create "Bootstrap Velocity plugin" --slug bootstrap-velocity-plugin --parent $parent
python ./.trellis/scripts/task.py create "Limbo consent and config" --slug limbo-consent-config --parent $parent
python ./.trellis/scripts/task.py create "Self player command surface" --slug self-player-command-surface --parent $parent
python ./.trellis/scripts/task.py create "Automation state machine" --slug automation-state-machine --parent $parent
python ./.trellis/scripts/task.py create "MCProtocolLib offline upstream spike" --slug mcprotocollib-offline-upstream-spike --parent $parent
python ./.trellis/scripts/task.py create "Online auth reconnect spike" --slug online-auth-reconnect-spike --parent $parent
python ./.trellis/scripts/task.py create "Persistence and secret store" --slug persistence-secret-store --parent $parent
python ./.trellis/scripts/task.py create "End to end controlled server test" --slug end-to-end-controlled-server-test --parent $parent
python ./.trellis/scripts/task.py create "Velocity handoff patch spike" --slug velocity-handoff-patch-spike --parent $parent
```

Start only the child that owns the next independently verifiable deliverable.
The recommended first child is `bootstrap-velocity-plugin`. The recommended
first risky spike is `mcprotocollib-offline-upstream-spike`.

## Repository Shape

Use a single Gradle JVM project first. Split modules only after build,
packaging, or dependency isolation becomes painful.

```text
settings.gradle.kts
build.gradle.kts
gradle.properties
src/main/resources/velocity-plugin.json
src/main/resources/fakeplayerproxy-default.toml
src/main/java/com/fakeplayerproxy/
src/test/java/com/fakeplayerproxy/
src/integrationTest/java/com/fakeplayerproxy/
```

Initial dependency categories:

- `compileOnly`: Velocity API.
- `compileOnly` or provided plugin dependency: LimboAPI, if selected.
- `implementation`: MCProtocolLib behind `protocol.UpstreamClient`.
- `implementation`: TOML parser, SQLite JDBC or selected persistence layer,
  small migration helper if SQLite is used.
- `testImplementation`: JUnit 5, AssertJ or Truth, Mockito or hand-written fakes.

Do not copy LimboAPI, Velocity, Carpet, or MCProtocolLib source into the
project. Use APIs/libraries and cite behavior in docs/tests.

## Cross-Cutting Contracts

### Player Identity

Use UUID as the stable owner key. Username is display and self-command alias
only.

```java
public record PlayerKey(UUID uuid, String username) {
  public boolean matchesSelfToken(String token) {
    return "self".equalsIgnoreCase(token) || username.equalsIgnoreCase(token);
  }
}
```

### Result/Error Model

Avoid throwing from normal command/domain validation. Return typed results that
commands can render and audit can serialize.

```java
public sealed interface FppResult<T> permits FppResult.Ok, FppResult.Err {
  record Ok<T>(T value) implements FppResult<T> {}
  record Err<T>(FppError error) implements FppResult<T> {}
}

public record FppError(String code, String safeMessage, Map<String, String> details) {}
```

Never put tokens, refresh material, full host secrets, or session payloads in
`details`.

### Time And Threading

- Use an injectable `Clock` for persistence/audit tests.
- Serialize automation state transitions per player.
- Keep Velocity event handlers short; use async services for network/storage.
- Keep all scheduler intervals expressed in Minecraft ticks in domain objects,
  with a single conversion point to wall-clock duration.

### Packet Isolation

Only the `protocol` package may depend on MCProtocolLib packet classes.
Command, limbo, auth, persistence, and automation packages exchange domain
objects only.

## Domain Model Baseline

Create these domain types before wiring protocol packets:

```java
public record TargetServerId(String value) {}
public record AutomationSessionId(UUID value) {}

public record TargetServerConfig(
    TargetServerId id,
    String host,
    int port,
    boolean onlineMode,
    boolean authorizedOnly,
    Set<UUID> allowedPlayerUuids) {}

public enum AuthMode {
  OFFLINE_OR_OWNED_FORWARDING,
  ACCESS_TOKEN_ONLY,
  REFRESHABLE_MICROSOFT_AUTH
}

public record ReconnectPolicy(
    boolean enabled,
    int maxAttempts,
    Duration initialDelay,
    Duration maxDelay,
    double backoffMultiplier) {}

public record AutomationProfile(
    PlayerKey owner,
    TargetServerId targetServerId,
    AuthMode authMode,
    ReconnectPolicy reconnectPolicy,
    Duration maxRuntime,
    List<PlayerActionSpec> startupActions) {}
```

Action model:

```java
public enum ActionModeType { ONCE, CONTINUOUS, INTERVAL }

public record ActionMode(ActionModeType type, int intervalTicks) {
  public static ActionMode once() { return new ActionMode(ActionModeType.ONCE, 1); }
  public static ActionMode continuous() { return new ActionMode(ActionModeType.CONTINUOUS, 1); }
  public static ActionMode interval(int ticks) { return new ActionMode(ActionModeType.INTERVAL, ticks); }
}

public sealed interface PlayerActionSpec
    permits ShadowAction, StopAllAction, KillAutomationAction, AttackAction,
            UseAction, JumpAction, MoveAction, LookAction, TurnAction,
            HotbarAction, SneakAction, SprintAction, DeferredAction,
            UnsupportedAction {}

public record ShadowAction() implements PlayerActionSpec {}
public record StopAllAction() implements PlayerActionSpec {}
public record KillAutomationAction() implements PlayerActionSpec {}
public record AttackAction(ActionMode mode) implements PlayerActionSpec {}
public record UseAction(ActionMode mode) implements PlayerActionSpec {}
public record JumpAction(ActionMode mode) implements PlayerActionSpec {}
public record HotbarAction(int slotOneBased) implements PlayerActionSpec {}

public enum MoveDirection { STOP, FORWARD, BACKWARD, LEFT, RIGHT }
public record MoveAction(MoveDirection direction) implements PlayerActionSpec {}

public sealed interface LookTarget permits DirectionLookTarget, PointLookTarget, RotationLookTarget {}
public enum CardinalLook { NORTH, SOUTH, EAST, WEST, UP, DOWN }
public record DirectionLookTarget(CardinalLook direction) implements LookTarget {}
public record PointLookTarget(double x, double y, double z) implements LookTarget {}
public record RotationLookTarget(float yaw, float pitch) implements LookTarget {}
public record LookAction(LookTarget target) implements PlayerActionSpec {}
public record TurnAction(float yawDelta, float pitchDelta) implements PlayerActionSpec {}

public record SneakAction(boolean enabled) implements PlayerActionSpec {}
public record SprintAction(boolean enabled) implements PlayerActionSpec {}

public record DeferredAction(String command, String reason) implements PlayerActionSpec {}
public record UnsupportedAction(String command, String reason) implements PlayerActionSpec {}
```

## Child Task 1: `bootstrap-velocity-plugin`

Purpose: create the runnable plugin shell that every later task extends.

Files/classes:

```text
build.gradle.kts
settings.gradle.kts
src/main/resources/velocity-plugin.json
src/main/java/com/fakeplayerproxy/FakePlayerProxyPlugin.java
src/main/java/com/fakeplayerproxy/config/PluginConfig.java
src/main/java/com/fakeplayerproxy/config/ConfigLoader.java
src/main/java/com/fakeplayerproxy/config/TargetServerConfig.java
src/main/java/com/fakeplayerproxy/command/AdminCommand.java
src/main/java/com/fakeplayerproxy/observability/HealthReporter.java
src/test/java/com/fakeplayerproxy/config/ConfigLoaderTest.java
```

Implementation steps:

1. Create Gradle JVM project.
2. Add Velocity API as `compileOnly`.
3. Add `velocity-plugin.json` with plugin id, name, version, main class, and
   dependency declarations.
4. Implement constructor injection for `ProxyServer`, `Logger`, and data
   directory if using Velocity's plugin injection style.
5. Register `ProxyInitializeEvent` handler.
6. Load `fakeplayerproxy.toml`, falling back to default config resource.
7. Register `/fpp status` and `/fpp reload`.
8. Add startup log that prints plugin version, enabled command aliases, limbo
   provider mode, and target server count.

Important method contracts:

```java
public final class FakePlayerProxyPlugin {
  public void onProxyInitialization(ProxyInitializeEvent event);
}

public final class ConfigLoader {
  public PluginConfig loadOrCreate(Path dataDirectory);
  public PluginConfig reload(Path dataDirectory);
}
```

Validation:

- Unit test config defaults and invalid config errors.
- Local Velocity starts with plugin loaded.
- `/fpp status` reports config and no active automation sessions.
- Invalid config fails closed: automation disabled, plugin still reports the
  error through `/fpp status` if possible.

Rollback point:

- Revert plugin registration and dependency changes. No persistent schema yet.

## Child Task 2: `limbo-consent-config`

Purpose: hold users in limbo for consent/config and handle returning users while
automation exists.

Files/classes:

```text
src/main/java/com/fakeplayerproxy/limbo/LimboService.java
src/main/java/com/fakeplayerproxy/limbo/LimboApiService.java
src/main/java/com/fakeplayerproxy/limbo/LimboUnavailableService.java
src/main/java/com/fakeplayerproxy/limbo/ConsentFlow.java
src/main/java/com/fakeplayerproxy/limbo/ConfigFlow.java
src/main/java/com/fakeplayerproxy/player/PlayerContext.java
src/main/java/com/fakeplayerproxy/player/PlayerContextRegistry.java
src/main/java/com/fakeplayerproxy/player/ConsentRecord.java
src/main/java/com/fakeplayerproxy/persistence/Storage.java
src/main/java/com/fakeplayerproxy/persistence/InMemoryStorage.java
src/test/java/com/fakeplayerproxy/limbo/ConsentFlowTest.java
```

Interfaces:

```java
public interface LimboService {
  CompletableFuture<Void> sendToConsent(Player player, ConsentPrompt prompt);
  CompletableFuture<Void> sendToConfig(Player player, ConfigPrompt prompt);
  CompletableFuture<Void> sendToReclaim(Player player, AutomationSessionSnapshot session);
  boolean isAvailable();
}

public interface Storage {
  Optional<ConsentRecord> findConsent(PlayerKey owner, String consentVersion);
  void saveConsent(ConsentRecord record);
}
```

Velocity hooks:

- `PostLoginEvent`: create `PlayerContext`.
- `PlayerChooseInitialServerEvent` or `ServerPreConnectEvent`: route missing
  consent/config to limbo.
- `DisconnectEvent`: mark real client offline but do not kill automation.

Implementation notes:

- LimboAPI is preferred, but use `LimboService` so NanoLimbo or a disabled
  service can be substituted.
- If LimboAPI is missing at runtime, fail closed: users cannot enable automation.
- Consent must be versioned. Changing terms invalidates previous consent.
- Store remote address only as a hash if needed for audit.

Validation:

- Unconsented user is routed to consent flow.
- Accepted consent creates `ConsentRecord`.
- User can reach config after consent.
- Returning player with active automation is routed to reclaim prompt.
- Missing LimboAPI produces clear admin status and blocks automation.

## Child Task 3: `self-player-command-surface`

Purpose: implement the self-only `/player` and `/fpp player` command parser and
convert commands into domain actions. Do not send packets in this task.

Files/classes:

```text
src/main/java/com/fakeplayerproxy/command/PlayerCommand.java
src/main/java/com/fakeplayerproxy/command/CommandRegistrar.java
src/main/java/com/fakeplayerproxy/command/PlayerCommandParser.java
src/main/java/com/fakeplayerproxy/command/ParsedPlayerCommand.java
src/main/java/com/fakeplayerproxy/security/OwnershipPolicy.java
src/main/java/com/fakeplayerproxy/security/PermissionPolicy.java
src/main/java/com/fakeplayerproxy/security/RateLimiter.java
src/main/java/com/fakeplayerproxy/automation/action/*.java
src/test/java/com/fakeplayerproxy/command/PlayerCommandParserTest.java
src/test/java/com/fakeplayerproxy/security/OwnershipPolicyTest.java
```

Parser contract:

```java
public final class PlayerCommandParser {
  public FppResult<ParsedPlayerCommand> parse(PlayerKey executor, List<String> args);
}

public record ParsedPlayerCommand(PlayerKey owner, PlayerActionSpec action) {}
```

Command alias behavior:

- Always register `/fpp player`.
- Register literal `/player` only when `config.proxy.command_aliases` includes
  `player`.
- If literal `/player` is disabled, return no command at that alias rather than
  forwarding partial automation behavior.

Policy:

- Accept target token `self`.
- Accept target token equal to the executor's current username,
  case-insensitive.
- Deny every other token, including operator users, until admin-control design
  exists.
- Deny automation commands before consent.
- Apply per-player command rate limits to avoid packet floods and log spam.

Command coverage:

- Implement domain actions for every command in `carpet-command-mapping.md`.
- `spawn`: parse as `DeferredAction("spawn", "...")`.
- `drop`, `dropStack`, `swapHands`, `mount`, `dismount`: parse as
  `DeferredAction` until runtime support exists.
- `mount anything`: parse as `UnsupportedAction`.
- MVP actions return `Ok`.

Validation:

- Table-driven parser tests for every grammar entry in
  `carpet-command-mapping.md`.
- Ownership denial tests for other username, fake-looking names, case variants,
  empty target, and missing target.
- Default mode for `attack`, `use`, `jump`, `drop`, `dropStack`, `swapHands`
  is `once`.
- `interval 0` and negative interval are rejected.
- `hotbar` only accepts 1 through 9.
- `look` pitch is clamped or rejected according to the selected parser policy.

## Child Task 4: `automation-state-machine`

Purpose: implement sessions, state transitions, scheduler, reconnect controller
interfaces, audit events, and reclaim behavior without MCProtocolLib packets.

Files/classes:

```text
src/main/java/com/fakeplayerproxy/automation/AutomationManager.java
src/main/java/com/fakeplayerproxy/automation/AutomationSession.java
src/main/java/com/fakeplayerproxy/automation/AutomationState.java
src/main/java/com/fakeplayerproxy/automation/AutomationEvent.java
src/main/java/com/fakeplayerproxy/automation/AutomationStateMachine.java
src/main/java/com/fakeplayerproxy/automation/ActionScheduler.java
src/main/java/com/fakeplayerproxy/automation/ReconnectController.java
src/main/java/com/fakeplayerproxy/automation/AutomationSessionSnapshot.java
src/main/java/com/fakeplayerproxy/observability/AuditLog.java
src/test/java/com/fakeplayerproxy/automation/AutomationStateMachineTest.java
src/test/java/com/fakeplayerproxy/automation/ActionSchedulerTest.java
```

State enum:

```java
public enum AutomationState {
  IDLE,
  STARTING,
  CONNECTING,
  AUTOMATED_ACTIVE,
  RECONNECTING,
  RECLAIM_PENDING,
  STOPPING,
  STOPPED,
  FAILED
}
```

Manager contract:

```java
public interface AutomationManager {
  CompletableFuture<FppResult<AutomationSessionSnapshot>> requestShadow(PlayerKey owner);
  CompletableFuture<FppResult<AutomationSessionSnapshot>> submitAction(PlayerKey owner, PlayerActionSpec action);
  CompletableFuture<FppResult<AutomationSessionSnapshot>> stop(PlayerKey owner, StopReason reason);
  Optional<AutomationSessionSnapshot> snapshot(PlayerKey owner);
}
```

Scheduler behavior:

- Store one active scheduled action per action family.
- New action of same family replaces old action.
- `once` runs one tick and becomes complete.
- `continuous` runs every scheduler tick until stopped.
- `interval N` runs every N ticks until stopped.
- `StopAllAction` clears all families and invokes cleanup callbacks.

Reclaim behavior:

- When owner returns and a session is active, transition to `RECLAIM_PENDING`.
- Supported user choices: stop automation, keep automation and stay in limbo,
  or reconnect after stop. Observe mode is later unless a read-only spectator
  path is implemented.

Validation:

- Exhaustive transition table tests.
- Race test for two `shadow` requests for one owner.
- Scheduler tests for replacement, interval, continuous, stop cleanup.
- Reclaim tests for owner return during `AUTOMATED_ACTIVE` and `RECONNECTING`.
- Audit tests: every state transition emits a redacted event.

## Child Task 5: `mcprotocollib-offline-upstream-spike`

Purpose: prove the core automation loop against a controlled offline-mode
server before touching Microsoft auth or Velocity internals.

Files/classes:

```text
src/main/java/com/fakeplayerproxy/protocol/UpstreamClient.java
src/main/java/com/fakeplayerproxy/protocol/UpstreamClientListener.java
src/main/java/com/fakeplayerproxy/protocol/McProtocolLibUpstreamClient.java
src/main/java/com/fakeplayerproxy/protocol/McProtocolLibClientFactory.java
src/main/java/com/fakeplayerproxy/protocol/PacketEmitter.java
src/main/java/com/fakeplayerproxy/protocol/PositionTracker.java
src/main/java/com/fakeplayerproxy/protocol/RotationMath.java
src/main/java/com/fakeplayerproxy/protocol/InputState.java
src/main/java/com/fakeplayerproxy/protocol/WorldSnapshot.java
src/main/java/com/fakeplayerproxy/protocol/InventoryTracker.java
src/test/java/com/fakeplayerproxy/protocol/RotationMathTest.java
src/test/java/com/fakeplayerproxy/protocol/PacketEmitterTest.java
src/integrationTest/java/com/fakeplayerproxy/protocol/OfflineUpstreamIntegrationTest.java
```

Protocol boundary:

```java
public interface UpstreamClient {
  CompletableFuture<Void> connect(AutomationProfile profile, AuthMaterial authMaterial);
  CompletableFuture<Void> disconnect(String safeReason);
  CompletableFuture<Void> send(PlayerActionSpec action);
  AutomationSessionSnapshot snapshot();
  void addListener(UpstreamClientListener listener);
}
```

Packet emitter responsibilities:

- Convert `MoveAction` and current `InputState` to
  `ServerboundPlayerInputPacket`.
- Convert `LookAction` and `TurnAction` to move rotation or pos/rot packets.
- Convert `HotbarAction` to `ServerboundSetCarriedItemPacket(slot - 1)`.
- Convert `JumpAction` to input pulse/hold.
- Convert `SneakAction` and `SprintAction` to input flags, with optional
  sprint command packet behind a version adapter.
- Convert safe `AttackAction` entity target to `ServerboundAttackPacket` plus
  `ServerboundSwingPacket`.
- Convert safe `UseAction` to `ServerboundUseItemPacket` or
  `ServerboundUseItemOnPacket` only when required state exists.
- Convert stop cleanup to all-inputs-false and any known release/abort packet.

Trackers:

- `PositionTracker`: listens to position/teleport packets, stores x/y/z, yaw,
  pitch, onGround, entity id, dimension.
- `WorldSnapshot`: stores enough entity/block target data for MVP partial use
  and attack. It may start minimal and return "target unavailable".
- `InventoryTracker`: stores selected hotbar slot first; full inventory later.

Integration fixture:

- A controlled offline-mode Paper or vanilla-compatible server.
- A test world with one simple block target and one entity target if possible.
- Test account/profile name that is clearly not a real Mojang identity in
  offline mode.

Validation:

- MCProtocolLib client reaches play state.
- `shadow` starts an upstream session.
- `hotbar 2`, `look north`, `turn right`, `move forward`, `move`, `jump once`,
  `sneak`, `unsneak`, `sprint`, `unsprint`, `stop` produce expected server
  observations or packet-capture assertions.
- Target-unavailable `attack`/`use` returns a safe, user-visible error instead
  of sending nonsense packets.
- Upstream disconnect becomes an automation state-machine event.

## Child Task 6: `online-auth-reconnect-spike`

Purpose: prove online-mode login and reconnect with explicit auth material.

Detailed auth/reconnect research lives in `auth-reconnect-research.md`. Use it
as the source of truth for MCProtocolLib login flow, MinecraftAuth device-code
and refresh behavior, storage risk, reconnect decisions, and failure codes.

Files/classes:

```text
src/main/java/com/fakeplayerproxy/auth/AuthMode.java
src/main/java/com/fakeplayerproxy/auth/AuthMaterial.java
src/main/java/com/fakeplayerproxy/auth/AuthMaterialRef.java
src/main/java/com/fakeplayerproxy/auth/AuthMaterialStore.java
src/main/java/com/fakeplayerproxy/auth/MicrosoftAuthService.java
src/main/java/com/fakeplayerproxy/auth/SessionJoinService.java
src/main/java/com/fakeplayerproxy/automation/ReconnectController.java
src/test/java/com/fakeplayerproxy/auth/AuthRedactionTest.java
src/test/java/com/fakeplayerproxy/automation/ReconnectControllerTest.java
src/integrationTest/java/com/fakeplayerproxy/auth/OnlineReconnectIntegrationTest.java
```

Auth material model:

```java
public sealed interface AuthMaterial permits OfflineAuthMaterial, AccessTokenAuthMaterial, RefreshableAuthMaterial {
  AuthMode mode();
  Instant expiresAt();
  String redactedSummary();
}

public record OfflineAuthMaterial(String username) implements AuthMaterial {}
public record AccessTokenAuthMaterial(UUID uuid, String username, String accessToken, Instant expiresAt) implements AuthMaterial {}
public record RefreshableAuthMaterial(UUID uuid, String username, String encryptedRefreshRef, Instant expiresAt) implements AuthMaterial {}
```

MVP policy:

- `OFFLINE_OR_OWNED_FORWARDING`: enabled first.
- `ACCESS_TOKEN_ONLY`: enabled only in spike/test config.
- `REFRESHABLE_MICROSOFT_AUTH`: parsed/configured but disabled until secret
  storage and explicit operator approval exist.

Reconnect controller:

- Uses exponential backoff with max attempts.
- Stops immediately on permanent auth errors.
- Never retries with missing/expired non-refreshable material.
- Emits redacted audit events for every retry.

Validation:

- Online-mode controlled server accepts a test account with access token.
- Server restart causes reconnect when policy allows.
- Expired/missing access token fails closed.
- Logs and audit records never contain the access token.
- Reconnect can be disabled per profile.
- Failure codes match `auth-reconnect-research.md`.

## Child Task 7: `persistence-secret-store`

Purpose: replace in-memory storage with durable SQLite and optional encrypted
secret references.

Files/classes:

```text
src/main/java/com/fakeplayerproxy/persistence/SqliteStorage.java
src/main/java/com/fakeplayerproxy/persistence/SchemaMigrator.java
src/main/java/com/fakeplayerproxy/persistence/SecretStore.java
src/main/java/com/fakeplayerproxy/persistence/EncryptedFileSecretStore.java
src/main/java/com/fakeplayerproxy/persistence/NoopSecretStore.java
src/main/resources/db/migration/V001__initial_schema.sql
src/main/resources/db/migration/V002__auth_material_refs.sql
src/test/java/com/fakeplayerproxy/persistence/SqliteStorageTest.java
src/test/java/com/fakeplayerproxy/persistence/SecretStoreTest.java
```

Minimum tables:

```sql
consent_records(owner_uuid, consent_version, accepted_at, remote_addr_hash)
target_servers(id, host, port, online_mode, authorized_only, enabled)
automation_profiles(owner_uuid, target_server_id, auth_mode, reconnect_policy_json, startup_actions_json)
automation_sessions(session_id, owner_uuid, target_server_id, state, created_at, updated_at, last_error)
audit_events(id, owner_uuid, event_type, session_id, details_json, created_at)
auth_material_refs(owner_uuid, auth_mode, ref, created_at, expires_at, revoked_at)
```

Secret policy:

- If no master key or external secret provider is configured, use
  `NoopSecretStore` and disable persistent reconnect credentials.
- Do not store raw access tokens in SQLite tables.
- Revocation clears secret refs and blocks reconnect.
- Redaction is tested at serialization and logging boundaries.

Validation:

- Schema migrates from empty DB.
- Consent/profile/session/audit round trips.
- Revoked auth material is not returned.
- Secret values do not appear in logs, exceptions, audit JSON, or status output.

## Child Task 8: `end-to-end-controlled-server-test`

Purpose: prove the product path works as one operator-repeatable scenario.

Files/scripts:

```text
dev/e2e/README.md
dev/e2e/docker-compose.yml or scripts/start-local-stack.ps1
dev/e2e/velocity/velocity.toml
dev/e2e/upstream/server.properties
src/integrationTest/java/com/fakeplayerproxy/e2e/ControlledServerE2ETest.java
```

Scenario:

1. Start Velocity with plugin.
2. Start LimboAPI if used as a plugin dependency.
3. Start controlled offline-mode upstream.
4. Connect a test client or protocol test harness.
5. Accept consent and configure target.
6. Run `/player self shadow`.
7. Confirm upstream automation reaches active.
8. Disconnect real client.
9. Confirm automation continues an observable action.
10. Reconnect real client and enter reclaim prompt.
11. Stop automation and confirm upstream session closes.

Validation:

- A single command/script can start the local stack.
- Test output identifies server logs and plugin logs.
- The scenario is deterministic enough for CI or documented as manual if a real
  Minecraft client is required.

## Child Task 9: `velocity-handoff-patch-spike`

Purpose: only if user rejects re-login shadow and requires preserving the live
upstream connection.

Patch hypothesis:

- `VelocityServerConnection` and related handlers need a smaller owner
  abstraction than `ConnectedPlayer`.
- `ClientPlaySessionHandler.disconnected()` must be able to transition marked
  sessions to automation ownership instead of always calling teardown.
- `BackendPlaySessionHandler` must forward clientbound packets to an automation
  sink when no real inbound player exists.

Spike deliverables:

- A minimal patch branch or fork diff.
- A failing-then-passing test or reproducible script proving current teardown
  behavior and patched keepalive behavior.
- A maintenance risk write-up covering Velocity GPLv3 distribution obligations
  and Minecraft protocol churn.

Do not merge or ship this as part of MVP unless the product decision explicitly
changes.

## Readiness Checklist

The research task is implementation-ready only when all are true:

- `prd.md` states the product goal, constraints, acceptance criteria, and open
  decisions.
- `design.md` states the chosen architecture and rejects plugin-only MVP with
  evidence.
- `code-implementation-analysis.md` states package boundaries, state machine,
  auth modes, persistence, config, and test plan.
- `carpet-command-mapping.md` states command semantics, packet mapping, MVP
  status, runtime state, and tests.
- `auth-reconnect-research.md` states online auth/reconnect flows, storage
  requirements, and failure taxonomy.
- This blueprint states child-task files/classes/methods/validation.
- `implement.md` points to this blueprint.
- `implement.jsonl` and `check.jsonl` list the research/spec files a later
  implement/check agent must load.
- `task.py validate` passes.

## Source References

- Velocity docs and source references are listed in `research.md` and
  `code-implementation-analysis.md`.
- Carpet command behavior is detailed in `carpet-command-mapping.md`.
- MCProtocolLib packet/source references are detailed in `research.md`,
  `code-implementation-analysis.md`, and `carpet-command-mapping.md`.
