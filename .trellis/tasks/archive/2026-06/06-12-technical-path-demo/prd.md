# Technical path demo

## Goal

Implement the first runnable technical-path demo for FakePlayerProxy:

- a Velocity plugin skeleton that loads and exposes an `/fpp` admin command;
- a small config model for demo target host/port/username;
- an embedded MCProtocolLib offline-mode upstream client that can be started and
  stopped from the plugin;
- minimal packet/action support proving the path from Velocity command to
  protocol-client behavior.

This task is not the full MVP. It is the narrow demo that proves the chosen
architecture can be implemented in this repository.

## User Value

The user can run a local Velocity plugin build and verify that the project has a
working technical foundation: Velocity plugin lifecycle plus a JVM protocol
client controlled by plugin commands.

## Confirmed Inputs

- Parent research task: `.trellis/tasks/06-12-fake-player-proxy-research`.
- Chosen first technical path: Velocity plugin plus MCProtocolLib client, not a
  Velocity patch.
- Demo target: controlled/offline upstream server only.
- Minecraft protocol target: Minecraft Java `26.1`, protocol version `775`,
  using `org.geysermc.mcprotocollib:protocol:26.1-1`.
- Online auth, limbo, durable persistence, and full Carpet parity are explicitly
  outside this demo.
- User follow-up moved offline-controlled auto reconnect and a self-owned
  Carpet-style `/player` subset into this demo's required implementation scope.

## Requirements

- Set up a Gradle Java project that can build a Velocity plugin jar.
- Provide `velocity-plugin.json`.
- Implement `FakePlayerProxyPlugin` with `ProxyInitializeEvent` registration.
- Implement `/fpp status`, `/fpp demo connect [host] [port] [username]`, and
  `/fpp demo disconnect`.
- Implement a `DemoUpstreamClient` abstraction and an MCProtocolLib-backed
  implementation.
- Support offline-mode login using username-only `MinecraftProtocol`.
- Track demo connection state and expose it through status output.
- Implement one or more safe demo actions after play state when practical:
  `look north`, `jump`, or `hotbar`.
- Implement offline-controlled auto reconnect for unexpected disconnects.
- Implement self-only `/player` and `/fpp player` commands for `shadow`, `stop`,
  `kill`, `look`, `turn`, `hotbar`, `move`, `jump`, `sneak`, `unsneak`,
  `sprint`, `unsprint`, `attack`, `use`, `drop`, `dropStack`, `swapHands`, and
  `dismount`.
- Support Carpet-style `once`, `continuous`, and `interval <ticks>` modes for
  simple repeatable actions where a vanilla protocol packet is available.
- Include unit tests for config loading, command parsing helpers, and demo state
  transitions. Protocol integration may be manual if no upstream server fixture
  is added in this task.
- Document how to run the demo against a local offline-mode server.
- Make the pinned Minecraft version/protocol visible in docs and `/fpp status`.

## Out Of Scope

- LimboAPI integration.
- Microsoft/Minecraft online auth.
- Online-mode auto reconnect without explicit auth material and secret storage.
- Durable SQLite/secret storage.
- Full Carpet parity for entity-target, block-target, full-inventory, and
  vehicle-aware commands.
- Velocity core patching.
- Real-client handoff or no-drop session transfer.

## Acceptance Criteria

- [x] `./gradlew test` passes.
- [x] `./gradlew build` produces a plugin jar.
- [x] The plugin registers `/fpp status` and demo commands.
- [x] The plugin registers self-only `/player` and `/fpp player` commands.
- [x] Offline-controlled unexpected disconnects schedule auto reconnect, while
  manual disconnect/shutdown do not.
- [x] Self-owned Carpet-style actions send real protocol packets for simple
  packet-level actions and return explicit deferred/unsupported messages for
  target/entity/inventory/vehicle actions that need trackers.
- [x] `DemoUpstreamClient` can be invoked from plugin code without leaking
  MCProtocolLib packet classes outside the protocol/demo boundary.
- [x] A local operator can follow `docs/demo/technical-path-demo.md` to run the
  plugin and attempt an offline-mode upstream connection.
- [x] The demo explicitly states Minecraft Java `26.1` / protocol `775` as the
  only supported upstream target for this artifact.
- [x] Secrets/tokens are not introduced in this demo.
- [x] The code path is compatible with future replacement by the full
  `UpstreamClient`/automation state machine described in the parent research.
