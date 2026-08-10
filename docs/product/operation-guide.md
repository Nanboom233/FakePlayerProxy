# FakePlayerProxy Operation Guide

This guide describes the current FakePlayerProxy runtime:

- a Velocity plugin can load and register `/fpp`;
- the plugin can own a small config file;
- a command can start an embedded MCProtocolLib offline-mode upstream client;
- the protocol dependency stays behind `com.fakeplayerproxy.protocol`.

## Version Target

This runtime is pinned to one Minecraft Java protocol target:

```text
Minecraft Java 26.2
Protocol version 776
MCProtocolLib artifact org.geysermc.mcprotocollib:protocol:26.2-20260709.110151-15
Netty runtime 4.2.17.Final
```

This is a compile-time target of the runtime jar. Changing
`fakeplayerproxy.properties` cannot change the Minecraft protocol version. Use a
matching `26.2` offline-mode upstream server for the runtime.

Current supported scope excludes online auth, LimboAPI, durable storage, and
full Carpet `/player` parity.

It does implement the first self-owned command and reconnect surface:

- `/player self shadow` starts the configured offline-mode upstream client.
- `/player self kill` disconnects that client.
- `/player self stop` clears movement/jump/sneak/sprint input state.
- `/player self look`, `turn`, `hotbar`, `move`, `jump`, `sneak`, and `sprint`
  send real MCProtocolLib packets after play state.
- `/player self attack`, `use`, `drop`, `dropStack`, `swapHands`, and
  `dismount` send protocol-level actions after play state. `attack` is a
  main-hand swing, `use` is main-hand item use, and `dismount` is a shift input
  pulse.
- `attack`, `use`, `drop`, `dropStack`, and `swapHands` accept Carpet-style
  `once`, `continuous`, and `interval <ticks>` modes.
- Unexpected upstream disconnects auto reconnect for `offline-controlled` mode.
- Online-mode reconnect remains disabled until explicit auth material and secret
  storage exist.

## Build

Make a JDK 25 toolchain available to Gradle. The plugin is compiled for Java
17, while the client mod is compiled for Java 25:

```bash
./gradlew build
```

The plugin and client mod jars are written to:

```text
plugin/build/libs/fake-player-proxy-0.1.0.jar
mod/build/libs/fakeplayerproxy-mod-0.1.0.jar
```

The jar includes MCProtocolLib runtime dependencies. Velocity API is compile-only
and is provided by the proxy.

## Local Upstream Server

Use a controlled local Minecraft Java `26.2` server for this runtime. The protocol
version must be `776`; other server versions are expected to fail or disconnect
because packet IDs and packet shapes are version-specific.

Set the upstream server to offline mode:

```properties
online-mode=false
server-port=25566
```

Start that server before connecting the upstream client.

## Velocity Setup

1. Copy `plugin/build/libs/fake-player-proxy-0.1.0.jar` into Velocity's `plugins/`
   directory.
2. Start Velocity once.
3. Edit the generated config if needed:

```text
plugins/fakeplayerproxy/fakeplayerproxy.properties
```

Default config:

```properties
proxy.targetHost=127.0.0.1
proxy.targetPort=25566
proxy.username=ProxyBot
proxy.reconnect.enabled=true
proxy.reconnect.maxAttempts=3
proxy.reconnect.delayMillis=1000
proxy.reconnect.authMode=offline-controlled
```

4. Restart Velocity after editing config.

## Commands

Show current runtime state:

```text
/fpp status
```

The status output includes the pinned protocol target:

```text
Protocol target: Minecraft Java 26.2 (protocol 776, MCProtocolLib org.geysermc.mcprotocollib:protocol:26.2-20260709.110151-15)
```

Connect with config defaults:

```text
/fpp connect
```

Connect with explicit target:

```text
/fpp connect 127.0.0.1 25566 ProxyBot
```

After the client reaches play state, send a minimal movement/rotation packet:

```text
/fpp look-north
```

Disconnect the upstream client:

```text
/fpp disconnect
```

Use the self-owned Carpet-style command surface:

```text
/player self shadow
/player self look north
/player self turn right
/player self hotbar 2
/player self move forward
/player self jump once
/player self jump interval 20
/player self sneak
/player self unsneak
/player self sprint
/player self unsprint
/player self attack once
/player self attack interval 20
/player self use once
/player self drop
/player self dropStack
/player self swapHands
/player self dismount
/player self stop
/player self kill
```

The same parser is available under `/fpp player ...`:

```text
/fpp player self shadow
```

Parsed but deferred command shapes return explicit messages instead of silently
doing nothing:

```text
/player self mount
/player self drop all
/player self dropStack offhand
```

Unsupported protocol-only behavior:

```text
/player self mount anything
```

## Expected Result

The upstream offline-mode server should show `ProxyBot` joining after
`/fpp connect`, then leaving after `/fpp disconnect`.

`/fpp look-north` is intentionally small: it sends one serverbound rotation
packet for protocol `776` after MCProtocolLib observes the play-state login
packet.

## Known Limits

- Only one upstream client is allowed per proxy process.
- The runtime uses offline-mode username login only.
- It does not preserve a real player's session or inventory state.
- Auto reconnect is implemented only for the offline-controlled runtime mode.
- Online reconnect with Microsoft/Minecraft auth material is not implemented.
- Full Carpet parity is not implemented. Entity, block, full inventory, and
  vehicle-aware behavior are deferred until the corresponding trackers exist.
- `attack` currently sends a main-hand swing only; entity attack and block
  breaking need target/world tracking.
- `use` currently sends main-hand item use only; block use, entity interaction,
  offhand fallback, and Carpet cooldown parity need target/inventory tracking.
- `drop`, `dropStack`, and `swapHands` send vanilla selected-slot packets but do
  not verify resulting inventory state yet.
- `mount` remains deferred; `mount anything` is unsupported in protocol-only
  mode.
