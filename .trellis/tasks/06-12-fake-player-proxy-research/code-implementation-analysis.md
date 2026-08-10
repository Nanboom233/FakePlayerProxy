# Code Implementation Analysis

## Executive Conclusion

The requested product cannot be completed as a stock Velocity plugin alone.

The evidence is concrete:

- Velocity terminates the inbound player's Minecraft login and models backend servers as Velocity-controlled servers that are normally `online-mode=false` with forwarding.
- `LoginSessionHandler.handle(EncryptionRequestPacket)` throws for an online-mode backend server, so the normal Velocity backend path is not an arbitrary online-mode upstream client.
- `VelocityServerConnection` is constructed with a `ConnectedPlayer`, uses the player's Netty event loop to connect, and `isActive()` requires `proxyPlayer.isActive()`.
- `ClientPlaySessionHandler.disconnected()` calls `player.teardown()`.
- `ConnectedPlayer.teardown()` disconnects both `connectionInFlight` and `connectedServer`.
- `BackendPlaySessionHandler` expects a backing `ClientPlaySessionHandler` and writes clientbound packets to the player's inbound connection.

Therefore the implementation-grade recommendation is:

1. Build the product as a Velocity plugin for entry, limbo, consent, configuration, commands, permissions, storage, and observability.
2. Use a JVM protocol-client component, most likely MCProtocolLib, for upstream automated sessions.
3. Treat a seamless live packet-stream handoff as a separate patch/spike. MVP should implement "re-login shadow" for controlled/authorized targets, not claim zero-drop hot handoff.
4. Patch Velocity only if the accepted MVP requires preserving an existing live upstream backend connection after the inbound player disconnects.

## Feasibility Matrix

| Approach | Can host limbo/config? | Can normal Velocity proxy to owned backend? | Can connect automation to online-mode upstream? | Can continue after real client disconnects? | Patch required? | Verdict |
| --- | --- | --- | --- | --- | --- | --- |
| Stock Velocity plugin only | Yes | Yes | No, not through normal backend connection | No, backend tears down with player | No | Insufficient for core goal |
| Plugin + MCProtocolLib automated client | Yes | Yes | Yes, with delegated auth token/profile | Yes, for the separate automated client | No for re-login shadow | Recommended MVP path |
| Plugin + MCProtocolLib + minimal Velocity patch | Yes | Yes | Yes | Potentially yes for seamless handoff | Yes | Required only for lossless handoff |
| Standalone custom proxy/bot with Velocity frontend | Yes | Partial | Yes | Yes | Maybe no Velocity patch | More moving parts; keep as fallback |
| Fork Velocity into full upstream client proxy | Yes | Yes | Yes | Yes | Yes, broad | Too large for MVP |

## Recommended MVP Definition

The MVP should support controlled/authorized servers only and use "re-login shadow":

1. Player joins the Velocity proxy.
2. Player enters limbo and accepts consent/automation terms.
3. Player configures a target server and auth mode.
4. Player may play normally only on servers supported by the selected mode:
   - owned Velocity backend: normal Velocity connection;
   - external online-mode server: initially route through a controlled bridge only if the bridge spike is implemented.
5. Player runs `/player <own-name> shadow` or `/player self shadow`.
6. The plugin starts an `AutomationSession` backed by MCProtocolLib.
7. If online-mode reconnect/automation is enabled, the session uses the user's explicitly delegated Minecraft auth token/profile.
8. The real client is moved to limbo or disconnected after the automated session reaches `AUTOMATED_ACTIVE`.
9. Automation executes scheduled actions through packet emission.
10. If the upstream session disconnects, reconnect follows the configured reconnect policy.

This is not the same as a seamless hot handoff. It is implementable first and validates the product value without patching Velocity internals.

## Velocity Code Surfaces

### Public Plugin API

Use these for plugin-level behavior:

- `ProxyInitializeEvent`: initialize plugin services after Velocity API is safe.
- `PreLoginEvent`: block/allow early login and collect login-phase data.
- `LoginEvent`: validate profile and enforce per-player policy after authentication.
- `PostLoginEvent`: initialize runtime player context.
- `ServerPreConnectEvent`: redirect players to limbo/config target or block backend access until consent.
- `DisconnectEvent`: update runtime state when a real client disconnects.
- `PlayerChooseInitialServerEvent`: choose limbo/default server route.
- Velocity command API: register `/player` and plugin admin/status commands.
- `Player.createConnectionRequest(...)`: only for normal Velocity backend routing while the real player is online.
- `Player.spoofChatInput(...)`: can be useful for commands on the player connection, but it does not solve headless automation.

### Internal Classes That Explain the Gap

These are evidence and possible patch anchors, not plugin APIs:

- `com.velocitypowered.proxy.connection.client.ConnectedPlayer`
  - Owns inbound `MinecraftConnection`.
  - Stores `connectedServer` and `connectionInFlight`.
  - `createConnectionRequest(...)` creates an inner `ConnectionRequestBuilderImpl`.
  - `teardown()` disconnects both in-flight and connected backend connections.
- `com.velocitypowered.proxy.connection.backend.VelocityServerConnection`
  - Strongly owns `ConnectedPlayer proxyPlayer`.
  - `connect()` uses `server.createBootstrap(proxyPlayer.getConnection().eventLoop())`.
  - `startHandshake()` derives protocol version, virtual host, forwarding, login packet, username, UUID, and key from `proxyPlayer`.
  - `isActive()` requires `proxyPlayer.isActive()`.
- `com.velocitypowered.proxy.connection.client.ClientPlaySessionHandler`
  - Forwards generic serverbound packets from real player to backend.
  - `disconnected()` calls `player.teardown()`.
- `com.velocitypowered.proxy.connection.backend.BackendPlaySessionHandler`
  - Writes backend clientbound packets to the real player's `MinecraftConnection`.
  - Requires the player connection to already have `ClientPlaySessionHandler`.
- `com.velocitypowered.proxy.connection.backend.LoginSessionHandler`
  - Throws on backend `EncryptionRequestPacket`, confirming normal backends are not online-mode upstream clients.
- `com.velocitypowered.proxy.connection.MinecraftConnection`
  - Netty wrapper with active session handler, protocol state, compression, encryption, and raw channel operations.

### Minimal Patch Candidate

Only pursue after the plugin + protocol-client MVP proves insufficient.

Patch goal:

- Allow a controlled bridge session to decouple backend/upstream lifecycle from inbound `ConnectedPlayer`.

Potential patch shape:

- Add an internal abstraction like `ProxyControlledConnectionOwner` or `UpstreamSessionOwner`.
- Refactor `VelocityServerConnection` dependencies on `ConnectedPlayer` into a smaller owner interface:
  - username/UUID/profile properties;
  - protocol version;
  - virtual host/address;
  - identified key/profile key;
  - event loop or executor;
  - current client settings;
  - disconnect/error sink.
- Split `ClientPlaySessionHandler`/`BackendPlaySessionHandler` packet forwarding so a plugin-controlled owner can consume clientbound packets and emit serverbound packets without a live inbound player.
- Change teardown behavior so real inbound disconnect can transition to `AUTOMATED_ACTIVE` instead of closing the upstream connection.

Patch risk:

- High. Velocity's existing forwarding pipeline is intentionally player-centric. This patch is maintenance-heavy across Velocity and Minecraft protocol versions.

## Proposed Plugin Package Layout

Use a single Gradle multi-module or single-module Java project at first:

```text
src/main/java/com/fakeplayerproxy/
  FakePlayerProxyPlugin.java
  config/
    PluginConfig.java
    TargetServerConfig.java
    ReconnectConfig.java
    LimboConfig.java
    SecurityConfig.java
  command/
    PlayerCommand.java
    AdminCommand.java
    CommandParser.java
    ParsedPlayerAction.java
  limbo/
    LimboService.java
    LimboApiService.java
    ConsentFlow.java
    ConfigFlow.java
  player/
    PlayerContext.java
    PlayerContextRegistry.java
    ConsentRecord.java
  automation/
    AutomationManager.java
    AutomationSession.java
    AutomationState.java
    AutomationStateMachine.java
    ActionScheduler.java
    ActionPlan.java
    ReconnectController.java
  protocol/
    UpstreamClient.java
    McProtocolLibUpstreamClient.java
    UpstreamClientListener.java
    PacketEmitter.java
    WorldSnapshot.java
    PositionTracker.java
    InventoryTracker.java
  auth/
    AuthMode.java
    AuthMaterial.java
    AuthMaterialStore.java
    MicrosoftAuthService.java
    SessionJoinService.java
  persistence/
    Storage.java
    SqliteStorage.java
    JsonFileStorage.java
    SecretStore.java
  security/
    OwnershipPolicy.java
    PermissionPolicy.java
    RateLimiter.java
    AuditLog.java
  observability/
    SessionLogger.java
    Metrics.java
    HealthReporter.java
```

Keep MCProtocolLib behind `UpstreamClient`; do not leak MCProtocolLib packet classes into command or limbo packages.

## Core Domain Model

```java
record PlayerKey(UUID uuid, String username) {}

record TargetServerConfig(
    String id,
    String host,
    int port,
    boolean onlineMode,
    boolean authorizedOnly,
    Set<String> allowedPlayerUuids
) {}

record AutomationProfile(
    PlayerKey owner,
    String targetServerId,
    AuthMode authMode,
    ReconnectPolicy reconnectPolicy,
    Duration maxRuntime,
    List<PlayerActionSpec> startupActions
) {}

sealed interface PlayerActionSpec permits ShadowAction, StopAction, UseAction,
    AttackAction, MoveAction, LookAction, InputAction, DropAction {}

record AutomationSessionId(UUID value) {}

record AutomationSessionSnapshot(
    AutomationSessionId id,
    PlayerKey owner,
    String targetServerId,
    AutomationState state,
    Instant createdAt,
    Instant lastTransitionAt,
    Optional<String> lastDisconnectReason
) {}
```

## Automation State Machine

| State | Entered when | Allowed events | Exit state |
| --- | --- | --- | --- |
| `IDLE` | no automation exists | `shadow_requested` | `STARTING` |
| `STARTING` | command accepted and prechecks passed | `auth_ready`, `auth_failed`, `cancel` | `CONNECTING`, `FAILED`, `STOPPING` |
| `CONNECTING` | upstream protocol client starts | `login_success`, `login_failed`, `cancel` | `AUTOMATED_ACTIVE`, `FAILED`, `STOPPING` |
| `AUTOMATED_ACTIVE` | upstream play state reached | `action_added`, `upstream_disconnect`, `owner_returned`, `stop` | `AUTOMATED_ACTIVE`, `RECONNECTING`, `RECLAIM_PENDING`, `STOPPING` |
| `RECONNECTING` | reconnect policy allows retry | `retry_due`, `login_success`, `retry_exhausted`, `owner_returned`, `stop` | `CONNECTING`, `AUTOMATED_ACTIVE`, `FAILED`, `RECLAIM_PENDING`, `STOPPING` |
| `RECLAIM_PENDING` | real player returns while automation is alive | `reclaim`, `observe`, `stop`, `timeout` | `STOPPING`, `AUTOMATED_ACTIVE`, `STOPPING`, `AUTOMATED_ACTIVE` |
| `STOPPING` | stop/kill/reclaim begins | `upstream_closed`, `close_failed` | `STOPPED`, `FAILED` |
| `STOPPED` | upstream closed intentionally | `shadow_requested` | `STARTING` |
| `FAILED` | unrecoverable auth/protocol/server error | `reset`, `shadow_requested` | `IDLE`, `STARTING` |

State transitions must be serialized per player with a per-player lock or single-threaded actor to prevent two automation sessions for one account.

## Auth Modes

| Mode | Online-mode upstream? | Reconnect? | Stored secret? | Implementation note |
| --- | --- | --- | --- | --- |
| `OFFLINE_OR_OWNED_FORWARDING` | No | Yes | No Microsoft token | Only for controlled servers that accept offline/forwarded identity |
| `NO_RECONNECT` | Existing external session only | No | No | Can stop/keep current non-reconnect automation only if upstream connection already exists |
| `ACCESS_TOKEN_ONLY` | Yes | Until token invalid | Access token | MCProtocolLib can call sessionserver join; refresh may fail |
| `REFRESHABLE_MICROSOFT_AUTH` | Yes | Yes | refresh-capable auth material | Highest risk; requires explicit opt-in and encrypted storage |
| `USER_SUPPLIED_SESSION_KEY` | Unknown until defined | Maybe | user-provided | Needs exact semantics before implementation |

Recommendation:

- MVP should implement `OFFLINE_OR_OWNED_FORWARDING` first for protocol and command validation.
- Next implement `ACCESS_TOKEN_ONLY` with a dedicated test account.
- Defer refreshable Microsoft auth until secret storage, consent copy, and operator policy are reviewed.
- Treat `USER_SUPPLIED_SESSION_KEY` as undefined until the product specifies whether it means a Minecraft access token/profile, serialized auth manager state, or something else. Online-mode reconnect still needs material that can complete `SessionService.joinServer(...)`.
- See `auth-reconnect-research.md` for MCProtocolLib login flow, MinecraftAuth device-code/refresh behavior, auth material storage, reconnect decisions, and failure taxonomy.

## MCProtocolLib Integration

MCProtocolLib evidence:

- Repository: https://github.com/GeyserMC/MCProtocolLib
- License: MIT.
- README states it supports building custom bots, clients, or servers.
- `MinecraftProtocol(GameProfile, accessToken)` supports online login material.
- `ClientListener` handles encryption, compression, login finish, and calls `SessionService.joinServer(...)`.
- `SessionService` implements `joinServer(profile, authenticationToken, serverId)`.
- MinecraftAuth can provide Java Edition Microsoft auth flows, token lifecycle management, JSON serialization/deserialization, and lazy refresh via holder objects.
- Serverbound packet classes exist for movement, player input, attack, interact, use item, block action, swing, slot changes, and chat/commands.

Implementation wrapper:

```java
interface UpstreamClient {
  CompletableFuture<Void> connect(AutomationProfile profile, AuthMaterial auth);
  CompletableFuture<Void> disconnect(Component reason);
  CompletableFuture<Void> send(PlayerActionSpec action);
  AutomationSessionSnapshot snapshot();
  void addListener(UpstreamClientListener listener);
}
```

`McProtocolLibUpstreamClient` responsibilities:

- Build `MinecraftProtocol` from `GameProfile` and optional access token.
- Create `ClientSession` with host/port/proxy info.
- Attach session flags: `MinecraftConstants.SESSION_SERVICE_KEY`, auth proxy, timeouts.
- Listen for login/play packets to populate `WorldSnapshot`, `PositionTracker`, and `InventoryTracker`.
- Emit serverbound packets through `Session.send(packet)`.
- Translate disconnects into state-machine events.

## `/player` Command Mapping

Detailed Carpet-to-proxy command mapping is now documented in
`carpet-command-mapping.md`. Use that file as the source of truth for command
syntax, action scheduling, runtime state, packet mapping, MVP status, and tests.

Key conclusions:

- Carpet's `/player` is server-side and can mutate `ServerPlayer` directly; the
  proxy version can only send vanilla client packets unless a cooperating
  upstream plugin/mod or Velocity/backend patch exists.
- The proxy command must be self-owned only: `/player self ...` or
  `/player <own-name> ...`.
- Implement a per-session action scheduler that mirrors Carpet's `once`,
  `continuous`, and `interval <ticks>` modes.
- MVP runtime commands: `shadow`, `stop`, `kill`, `hotbar`, `move`, `look`,
  `turn`, `jump`, `sneak`, `unsneak`, `sprint`, and `unsprint`.
- MVP-partial runtime commands: `attack` and `use`, with clear errors when
  target/world/inventory state is not available.
- Later commands: inventory and vehicle-sensitive commands such as `drop`,
  `dropStack`, `swapHands`, `mount`, and `dismount`.
- Unsupported in protocol-only mode: `mount anything`, because Carpet force-rides
  arbitrary entities server-side.
- `spawn` is intentionally outside MVP until the product defines self-only
  semantics that do not conflict with account ownership.

## Limbo Implementation

Recommendation: LimboAPI first.

Use LimboAPI for:

- login-time holding area;
- play-time return/config state;
- consent copy display;
- current automation status;
- stop/reclaim choices for returning users.

Implementation services:

- `LimboService`: interface used by the rest of the plugin.
- `LimboApiService`: LimboAPI-backed implementation.
- `ConsentFlow`: renders consent text and records acceptance.
- `ConfigFlow`: guides auth mode, target server, reconnect policy, and startup actions.

NanoLimbo fallback:

- Use if LimboAPI cannot support the needed UI/packet/version surface.
- Costs an additional process/config and Velocity server entry.

## Persistence and Secret Storage

MVP persistence:

- `config.toml`: static operator config.
- `data/fakeplayerproxy.db`: SQLite for consent, profiles, sessions, audit logs.
- `secrets/`: optional encrypted auth material, keyed by `AuthMaterialRef`.

Minimum tables:

```sql
consent_records(owner_uuid, consent_version, accepted_at, remote_addr_hash)
target_servers(id, host, port, online_mode, authorized_only, enabled)
automation_profiles(owner_uuid, target_server_id, auth_mode, reconnect_policy_json, startup_actions_json)
automation_sessions(session_id, owner_uuid, target_server_id, state, created_at, updated_at, last_error)
audit_events(id, owner_uuid, event_type, session_id, details_json, created_at)
auth_material_refs(owner_uuid, auth_mode, ref, created_at, expires_at, revoked_at)
```

Secret handling requirements:

- Never log access tokens, refresh tokens, session keys, or full auth payloads.
- Require explicit consent before storing any reconnect-capable material.
- Support revocation from limbo and admin command.
- Prefer an operator-provided master key through environment variable or external file.
- If no master key is configured, disable persistent reconnect credentials.

## Configuration Draft

```toml
[proxy]
command_aliases = ["player"]
require_consent = true
consent_version = "2026-06-12"

[limbo]
provider = "limboapi"
status_interval_seconds = 5
returning_player_mode = "reclaim_prompt"

[automation]
default_max_runtime_minutes = 180
max_sessions_per_player = 1
max_sessions_total = 50
tick_interval_ms = 50
allow_arbitrary_public_servers = false

[auth]
allow_offline_owned = true
allow_access_token_only = false
allow_refreshable_microsoft_auth = false
secret_provider = "env_master_key"

[reconnect]
enabled_by_default = false
max_attempts = 10
initial_delay_seconds = 5
max_delay_seconds = 300
backoff_multiplier = 2.0

[[target_servers]]
id = "local-test"
host = "127.0.0.1"
port = 25566
online_mode = false
authorized_only = true
```

## Test Plan

Unit tests:

- Command parser accepts self/own-name and rejects other names.
- State machine transition table.
- Reconnect backoff and max-attempt behavior.
- Secret redaction in logs/audit serialization.
- Action scheduler interval/continuous cancellation.

Integration tests:

- Start Velocity with plugin and LimboAPI.
- Player without consent is routed to limbo.
- Consent creates a `ConsentRecord`.
- `/player self shadow` creates an `AutomationSession`.
- Offline-mode controlled upstream receives movement/use/attack packets.
- Stop/kill closes the upstream session.
- Real player return while automation is active enters reclaim flow.

Protocol tests:

- MCProtocolLib client can connect to a controlled offline-mode test server.
- MCProtocolLib client can connect to a controlled online-mode test server with a test account and access token.
- Reconnect backoff handles server shutdown/restart.
- Version-specific packet mapping is verified for the chosen Minecraft version.

Patch spike tests:

- Prove current Velocity tears down backend on client disconnect.
- Prototype a minimal patch that prevents teardown for a marked automation session.
- Prove packet ownership can transfer without corrupting compression/encryption/state.

## Recommended Trellis Task Chain

Parent: `fake-player-proxy-mvp`

Implementation-level details for each child task are in
`task-chain-implementation-blueprint.md`. Use that file when creating child
`prd.md`, `design.md`, and `implement.md` artifacts.

Children:

1. `bootstrap-velocity-plugin`
   - Gradle project, Velocity plugin metadata, config loading, logging, basic commands.
2. `limbo-consent-config`
   - LimboAPI integration, consent flow, config/status UI, return routing.
3. `self-player-command-surface`
   - `/player` parser, ownership enforcement, action model, permissions.
4. `automation-state-machine`
   - session registry, states, scheduler, audit logs, stop/kill/reclaim.
5. `mcprotocollib-offline-upstream-spike`
   - protocol client to controlled offline-mode server, packet emitters for MVP commands.
6. `online-auth-reconnect-spike`
   - access-token-only test account flow, sessionserver join, reconnect policy.
7. `persistence-secret-store`
   - SQLite persistence, secret provider, redaction, revocation.
8. `velocity-handoff-patch-spike`
   - only if seamless live handoff remains required after re-login shadow MVP.
9. `end-to-end-controlled-server-test`
   - full local Velocity + limbo + controlled upstream workflow.

Recommended first implementation task:

`bootstrap-velocity-plugin`, because every later task needs plugin lifecycle, config, logging, and command registration.

Recommended first risky spike:

`mcprotocollib-offline-upstream-spike`, because it validates the core automation loop without touching Microsoft auth or Velocity internals.

## Remaining Product Decisions

These cannot be answered from the repository:

1. Upstream policy: controlled/authorized only vs arbitrary public servers.
2. Minecraft version range: recommended one current stable minor first, then expand.
3. Auth risk: whether access-token-only and refreshable Microsoft auth are acceptable.
4. Handoff semantics: re-login shadow MVP vs seamless no-drop takeover.
5. Whether `/player` must be the literal proxy command name even if it shadows backend `/player`.

Recommended answers:

- Controlled/authorized only.
- One current stable Minecraft Java version for MVP.
- Start without persistent refreshable auth; allow a test-account access-token spike.
- Re-login shadow first; seamless takeover later.
- Register `/player`, but only when configured, and provide an alternate `/fpp player` admin-safe alias.
