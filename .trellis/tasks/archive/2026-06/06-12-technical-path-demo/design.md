# Technical Design

## Architecture

The demo uses a single Gradle Java project:

```text
src/main/java/com/fakeplayerproxy/
  FakePlayerProxyPlugin.java
  command/
  config/
  demo/
  protocol/
  util/
src/main/resources/
  velocity-plugin.json
  fakeplayerproxy-default.properties
src/test/java/com/fakeplayerproxy/
docs/demo/technical-path-demo.md
```

The Velocity plugin layer owns lifecycle and commands. The protocol layer owns
MCProtocolLib classes. The demo layer owns demo session state and bridges admin
commands to the protocol client.

The demo is pinned to Minecraft Java `26.1` / protocol `775`, provided by
`org.geysermc.mcprotocollib:protocol:26.1-1`. This is a compile-time demo target,
not a runtime configuration field.

## Boundaries

- `command`: parses `/fpp` subcommands and renders safe status messages.
- `config`: loads demo defaults from `fakeplayerproxy.properties`, then falls
  back to bundled defaults.
- `demo`: keeps one demo connection at a time for the whole plugin instance.
- `demo`: owns reconnect state, action scheduling, input state, and
  protocol-neutral action methods.
- `demo`: exposes `DemoProtocolTarget` so commands and docs can report the
  pinned Minecraft version without importing MCProtocolLib classes.
- `protocol`: wraps MCProtocolLib. Other packages must not import
  MCProtocolLib packet classes.
- `protocol`: sends packet-level actions for look, input, hotbar, main-hand
  swing/use, selected-slot drop/dropStack, and swapHands.

## Demo Flow

1. Velocity starts the plugin.
2. Plugin loads config and registers `/fpp`.
3. Operator runs `/fpp status`.
4. Operator confirms the status output says Minecraft Java `26.1` /
   protocol `775`.
5. Operator runs `/fpp demo connect 127.0.0.1 25566 DemoBot` against a matching
   offline-mode upstream server.
6. `DemoConnectionService` creates a `McProtocolLibDemoClient`.
7. Client uses `MinecraftProtocol(username)` for offline-mode login.
8. Client attaches listeners for connect, disconnect, packet receive, and play
   state signals.
9. Operator runs `/fpp demo disconnect` to close the upstream session.

## State Model

```text
IDLE -> CONNECTING -> CONNECTED -> DISCONNECTING -> IDLE
IDLE -> CONNECTING -> FAILED -> IDLE
CONNECTED -> RECONNECTING -> CONNECTING -> CONNECTED
CONNECTED -> FAILED -> IDLE
```

Only one demo client is allowed. Concurrent connect attempts return a typed
error.

## Action Model

The self-owned Carpet-style command surface is implemented as command parsing
plus protocol-neutral service methods. The command layer never imports
MCProtocolLib packets.

Supported packet-level actions:

- `attack`: main-hand swing packet.
- `use`: main-hand use item packet with the last known yaw/pitch.
- `drop`: selected item drop action packet.
- `dropStack`: selected stack drop action packet.
- `swapHands`: swap hands action packet.
- `dismount`: one shift-input pulse.

Repeatable actions use `DemoActionMode`:

- default / `once`: send one action immediately;
- `continuous`: send once immediately and repeat every one Minecraft tick;
- `interval <ticks>`: send once immediately and repeat every configured tick
  interval.

Manual `stop`, `kill`, and proxy shutdown cancel scheduled actions. Unexpected
disconnects keep the scheduled action configuration while offline-controlled
auto reconnect is pending; the scheduled task pauses while play state is not
ready and resumes after reconnect.

Deferred behavior remains explicit for actions that require missing trackers:
entity attack, block breaking, block/entity use, offhand fallback, full
inventory slot operations, `mount`, and vehicle-aware behavior.

## Error Handling

Use small typed result classes for internal service calls:

```java
DemoResult<T>
DemoError(code, safeMessage)
```

Do not throw for normal command errors such as "already connected" or invalid
port. Exceptions from MCProtocolLib are captured, redacted, logged, and surfaced
as safe messages.

## Configuration

Use Java `Properties` for this demo to avoid adding a TOML dependency before the
larger config task.

```properties
demo.targetHost=127.0.0.1
demo.targetPort=25566
demo.username=DemoBot
```

## Validation

Automated:

- config loader tests;
- state transition tests;
- command argument parsing tests;
- reconnect tests;
- Carpet action parsing and action scheduler tests;
- build/test compile checks.

Manual:

- run Velocity with the built jar;
- run local offline-mode upstream server;
- invoke `/fpp demo connect`;
- observe plugin logs and upstream join/disconnect.

## Future Migration

This demo should be easy to replace with the parent blueprint:

- `DemoConnectionService` becomes `AutomationManager`.
- `McProtocolLibDemoClient` becomes `McProtocolLibUpstreamClient`.
- demo state becomes `AutomationState`.
- `/fpp demo` becomes `/player self shadow` and `/fpp status`.
