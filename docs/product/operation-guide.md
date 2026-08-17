# FakePlayerProxy Operation Guide

FakePlayerProxy runs an authenticated relay player on its original backend after
the player's frontend enters Shadow state. The Velocity plugin owns both command
roots locally; it does not create a second backend connection.

## Version Target

The runtime is compiled for one protocol target:

```text
Minecraft Java 26.2
Protocol version 776
MCProtocolLib org.geysermc.mcprotocollib:protocol:26.2-20260809.160751-16
Netty 4.2.17.Final
```

Use the patched Velocity artifact produced by this repository. It supplies
MCProtocolLib and its runtime dependencies to the plugin.

## Build

Make a Java 21 toolchain available to Gradle, then build the patched proxy and
plugin:

```powershell
.\gradlew.bat :plugin:releaseJar
```

Production artifacts are written to `plugin/build/server/release/`.

## Setup

1. Run the released patched Velocity server with the FakePlayerProxy plugin.
2. Keep Velocity and the target backend in online mode.
3. Connect with a FakePlayerProxy client and accept the relay request.
4. Wait until the original backend reaches play state before using Shadow.

The plugin data directory can contain one configuration file:

```text
plugins/fakeplayerproxy/ops.json
```

A missing file means that no players are operators. The console always retains
the `fakeplayerproxy.op` permission and can create the first entry. The file is a
JSON array; UUIDs authorize players and names support display and offline
revocation:

```json
[
  {
    "uuid": "00000000-0000-0000-0000-000000000000",
    "name": "PlayerName"
  }
]
```

Malformed content fails closed for players and is not replaced automatically.
Use an explicit console `/fpp op` command to replace it with a valid snapshot.

## Commands

The connected relay player can hand its own connection to automation:

```text
/player shadow
```

The frontend closes while the original backend remains active. An operator can
target an active automation player by authenticated name:

```text
/player as <player> shadow
```

Target lookup and completion use FakePlayerProxy's active automation registry,
so a Shadow player remains addressable after leaving Velocity's public player
list.

Manage operators with:

```text
/fpp op <online-authenticated-player>
/fpp deop <saved-player-name>
```

`/player as`, `/fpp op`, and `/fpp deop` require
`fakeplayerproxy.op`. Permission changes apply immediately and persist through
restart. `/player shadow` remains available to the exact command-source player
without that permission.

## Known Limits

- This command layer currently exposes only the `shadow` action. Later action
  work extends the same direct and targeted command branches.
- Automation is local to one proxy process.
- The target must still have an active original backend connection.
- Operator state is not imported from backend `ops.json` files or another
  permission plugin.
- Full Carpet `/player` parity remains out of scope.
