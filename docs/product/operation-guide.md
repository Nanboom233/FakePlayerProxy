# FakePlayerProxy Operation Guide

FakePlayerProxy runs an authenticated relay player on its original backend after
the player's frontend enters Shadow state. The Velocity plugin owns both command
roots locally. With explicit token consent, a Shadow can replace a lost backend
connection to the same registered server.

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

The connected relay player can control its own automation connection. Operators
can place the same action after `/player as <player>`:

```text
/player stop
/player use [once|continuous|interval <ticks>]
/player jump [once|continuous|interval <ticks>]
/player attack [once|continuous|interval <ticks>]
/player drop [once|continuous|interval <ticks>|all|mainhand|offhand|<slot>]
/player dropStack [once|continuous|interval <ticks>|all|mainhand|offhand|<slot>]
/player swapHands [once|continuous|interval <ticks>]
/player hotbar <slot>
/player kill
/player shadow
/player mount [<x> <y> <z>]
/player dismount
/player sneak
/player unsneak
/player sprint
/player unsprint
/player look <north|south|east|west|up|down>
/player look <yaw> <pitch>
/player look at <x> <y> <z>
/player turn <left|right|back>
/player turn <yaw-delta> <pitch-delta>
/player move [forward|backward|left|right]
```

`shadow` closes the frontend while the original backend remains active. A mount
or look position accepts absolute coordinates, `~` relative coordinates, and
`^` local coordinates resolved from the target fake player.

An operator targets an active automation player by authenticated name:

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

Enable or disable connection-scoped Shadow auto-reconnect before entering Shadow:

```text
/fpp auto-reconnect on
/fpp auto-reconnect off
```

`on` always opens a new client consent screen. If allowed, the proxy holds the
Minecraft access token only in memory so it can authorize a fresh online-mode
backend Login after the frontend closes. There is no fixed retention time or
retry limit. `off` and `/player kill` clear the token.

The first reconnect attempt is immediate. Later failed attempts wait 10, 10,
30, 30, 60, 60, and then 300 seconds repeatedly. Duplicate-login, profile-ban,
IP-ban, required resource-pack, Code of Conduct, backend Transfer, and account
credential rejection stop auto-reconnect. Other backend and transport failures retry.
Proxy logs record consent, attempts, ready PLAY, terminal policy, and cleanup;
they never contain token data.

## Known Limits

- Automation is local to one proxy process.
- Auto-reconnect returns only to the Shadow's same registered backend.
- Operator state is not imported from backend `ops.json` files or another
  permission plugin.
- Server-only Carpet branches, including `spawn` and `mount anything`, are not
  registered. Local interaction prediction intentionally declines untracked
  block, entity, item-subclass, data-pack, and backend-plugin behavior.
