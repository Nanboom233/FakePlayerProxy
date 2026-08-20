# Auto-reconnect surface design

Date: 2026-08-19

## Result

The feature uses the existing plugin, mod, and Velocity patch boundaries. It
does not add a configuration file, a reconnect service, or a third top-level
Velocity patch.

The client consent is separate from the login relay consent. The existing relay
screen promises that the proxy does not receive the access token and can save a
decision. Neither behavior is valid for auto-reconnect.

## Command tree

`FppCommand` adds this branch:

```text
/fpp auto-reconnect on
/fpp auto-reconnect off
```

The `auto-reconnect` literal requires a Velocity `Player`. It has no operator
permission. A player can authorize or clear only the token for that same player
connection.

The `on` and `off` literals are static Brigadier children. They need no
suggestion provider.

## Command interaction

### On

1. The command passes the source player to `AuthManager`.
2. `AuthManager` verifies the active automation player and both connections.
3. `AuthManager` clears an earlier token and invalidates an earlier request.
4. `AuthManager` sets `autoReconnect=false` and clears the old token.
5. `AuthManager` sends an empty request on the project channel.
6. The client opens `AutoReconnectConsentScreen` on the game thread.
7. Allow sends the response with the token.
8. Decline sends a response without token bytes.
9. Escape has the same result as Decline.
10. `AuthManager` marks the channel handled before it parses any bytes.
11. `AuthManager` verifies the exact player, bounds, and exact end of payload.
12. A well-formed Allow response contains a non-empty token and
    immediately enables auto-reconnect.
13. Decline leaves auto-reconnect disabled and sends no player text.

The proxy does not test the token or track its expiry at consent time. The next
actual reconnect performs the first session-service check.

Every `on` command starts a new consent and clears an earlier token. The design
does not correlate a response with a request.

The plugin cannot prove that the mod is present before it sends the request.
Velocity `Player.sendPluginMessage()` reports a write, not client support. A
client without the codec discards the payload, so auto-reconnect stays disabled.

### Off

`off` is idempotent. It sets `autoReconnect=false` and clears the token. It
does not close the active backend.

The command returns the same disabled message when the feature was already off.
This avoids an extra state-only response branch.

### Shadow and reconnect

The frontend cannot issue `/fpp` after Shadow closes it. `/player kill` remains
the operator path for a reconnecting Shadow.

Both command branches require the active frontend and backend. They cannot run
during Shadow reconnect, so they need no reconnect-channel branch.

The plugin sends no player message after the frontend closes. It records later
retry and terminal transitions in the proxy log.

## Payload design

Both directions use `fakeplayerproxy:auto_reconnect_v1`. The channel ID owns
the protocol version. Direction identifies the payload shape, so the wire needs
no version or message-type field.

The clientbound request has no data.

The serverbound response contains:

1. bounded UTF-8 Minecraft access token

A zero token length means Decline. A positive token length means Allow. Extra
bytes make the response invalid.

The v1 channel uses these limits:

- access token is at most 8192 UTF-8 bytes
- the decoder rejects malformed UTF-8 and trailing bytes

These limits stay below Minecraft's 32,767-byte serverbound payload limit. The
decoder checks a length before it allocates or copies the value.

`AuthManager` marks every recognized channel message handled before it checks
the source or parses bytes. It rejects a backend source. This stops a backend
from requesting the token through the project channel.

`AuthManager` then accepts only a `Player` source. It requires the exact current
Velocity player instance. Malformed and stale responses remain handled. Token
bytes can never forward to the backend.

## Client integration

The mod keeps its current no-Fabric-API boundary. Minecraft discards unknown
payload bytes unless the packet codec knows the project type. Two narrow codec
Mixins add the request and response codecs to the PLAY packet codec lists.

`MixinClientPacketListener` consumes the decoded request before Vanilla logs it
as unknown. It schedules one screen on the game thread. Its callback sends one
response. It keeps no static request state.

`MixinClientPacketListener` creates `AutoReconnectConsentScreen`. The screen
extends Vanilla `ConfirmScreen` and owns its text and boolean choice callback.
The Mixin callback sends one response and restores the previous screen. Escape
selects Decline. The screen has no timer or saved decision.

## File structure

### Mod files

| File | Change |
| --- | --- |
| `mod/src/main/java/com/fakeplayerproxy/mod/packets/AutoReconnectPayload.java` | Contain the direction-specific request and response payloads plus codecs. |
| `mod/src/main/java/com/fakeplayerproxy/mod/gui/AutoReconnectConsentScreen.java` | Own the consent text and boolean choice UI. |
| `mod/src/main/java/com/fakeplayerproxy/mod/mixins/MixinClientboundCustomPayloadPacket.java` | Add the request codec to the clientbound PLAY codec list. |
| `mod/src/main/java/com/fakeplayerproxy/mod/mixins/MixinServerboundCustomPayloadPacket.java` | Add the response codec to the serverbound PLAY codec list. |
| `mod/src/main/java/com/fakeplayerproxy/mod/mixins/MixinClientPacketListener.java` | Open the consent screen and send one response. |
| `mod/src/main/resources/fakeplayerproxy-mod.mixins.json` | Register the three new Mixins. |
| `mod/src/main/resources/assets/fakeplayerproxy-mod/lang/en_us.json` | Add consent and command translations. |
| `mod/src/main/resources/assets/fakeplayerproxy-mod/lang/zh_cn.json` | Add matching Simplified Chinese translations. |

No change to `mod/build.gradle.kts` or `fabric.mod.json` is required. Do not add
Fabric API or a client entrypoint.

### Plugin files

| File | Change |
| --- | --- |
| `plugin/src/main/java/com/fakeplayerproxy/FakePlayerProxyPlugin.java` | Initialize and close the plugin. Construct and register all owners. |
| `plugin/src/main/java/com/fakeplayerproxy/utils/EventHandler.java` | Own existing configuration events and synchronous packet-to-state forwarding. |
| `plugin/src/main/java/com/fakeplayerproxy/utils/AuthManager.java` | Own the channel identifier, consent request, disable operation, bounded response decoder, and authorization result. |
| `plugin/src/main/java/com/fakeplayerproxy/command/FppCommand.java` | Add the player-only `auto-reconnect on|off` branch. |
| `plugin/src/main/java/com/fakeplayerproxy/automation/AutomationManager.java` | Own player lifecycle, terminal packet and authentication policy, reconnecting entries, and exact cleanup. |
| `plugin/src/main/java/com/fakeplayerproxy/automation/AutomationService.java` | Own `autoReconnect`, token data, attempt number, retry time, reconnect future, and action gating. Expose failures without policy. |
| `plugin/src/main/java/com/fakeplayerproxy/world/player/Player.java` | Reset backend-session state without clearing saved input intent. |
| `.trellis/spec/frontend/fabric-client-mod.md` | Add the PLAY payload and one-time token consent contract while keeping the no-Fabric-API rule. |
| `.trellis/spec/backend/velocity-plugin.md` | Replace the no-reconnect invariant with the approved retained-service lifecycle. |

`FakePlayerProxyPlugin` contains only `ProxyInitializeEvent` and
`ProxyShutdownEvent`. It registers `EventHandler`, `AuthManager`,
`AutomationManager`, and `PermissionProvider`.

`AutomationManager.get(player, source)` provides the shared exact source query.
`EventHandler` and `AutomationManager` use it. Do not add a listener base class
or protocol package.

`AutomationService` remains the reconnect state owner. It stores one
`autoReconnect` boolean. Do not add pending state, a feature-state enum, an
`AutoReconnectService`, token provider, retry scheduler, or reconnect request
record.

A valid non-empty token response stores the token and sets `autoReconnect=true`.
Disable and terminal paths clear the token and set it to false. Backend reconnect
progress still derives from the backend, future, `inGame`, and `playerLoaded`.

### Velocity patch files

Create `plugin/patch/0003-login-session.patch` after the existing `0001` and
`0002` patches. The ownership correction removes Transfer's private delay from
`0001`, leaving `0003` as the sole owner of the common four-second backend wait.
The `0002` functional changes stay intact while EOF-only churn is removed.

`plugin/build.gradle.kts` currently includes only `0001` and `0002`. Add one
explicit `0003` include. Keep its sorted Grgit apply path unchanged.

The patch changes these pinned Velocity source areas:

| Source area | Ownership |
| --- | --- |
| `gradle/libs.versions.toml` and `proxy/build.gradle.kts` | Add the pinned authlib 9.0.75 runtime dependency. |
| `VelocityServer` | Own one authlib Minecraft session service for reconnect joins. |
| `BackendChannelInitializer` | Install the common outbound wait gate. Its private nested classes own the per-channel gate and shared priority list. |
| `ConnectedPlayer` | Atomically detach the dead backend and start one retained Shadow reconnect. |
| `VelocityServerConnection` | Carry Shadow continuation state and one temporary token copy through connect. |
| `LoginSessionHandler` | Perform the fresh AES, digest, session join, and key response. |
| `ConfigSessionHandler` | Keep CONFIG active without a writable frontend. |
| `AuthSessionHandler` | Remove the old isolated four-second Transfer delay. |
| `ClientboundPacketEvent`, `PacketEventHandler`, and `MinecraftDecoder` | Carry the exact source backend so the plugin rejects stale packets. |

Update `plugin/patch/README.md` with the new patch purpose and order. Do not edit
generated source as a committed artifact.

Reuse `TransitionSessionHandler` for reconnected backend attachment. Reuse the existing
patched `BackendPlaySessionHandler`. Change only the shared packet event path to
add source connection identity.

## Translation design

The mod contains the command translation keys because this branch accepts only
a player source. The plugin sends translatable components and adds no duplicate
bundle entries.

The client consent adds these mod-only keys:

- `fakeplayerproxy.auto_reconnect.consent.title`
- `fakeplayerproxy.auto_reconnect.consent.body`
- `fakeplayerproxy.auto_reconnect.consent.warning`

The buttons reuse `fakeplayerproxy.consent.allow` and
`fakeplayerproxy.consent.decline`. Their meaning does not change.

### English command text

| Key suffix | Text |
| --- | --- |
| `auto_reconnect_enabled` | `Auto-reconnect is enabled.` |
| `auto_reconnect_disabled` | `Auto-reconnect is disabled.` |

### Simplified Chinese command text

| Key suffix | Text |
| --- | --- |
| `auto_reconnect_enabled` | `自动重连已启用。` |
| `auto_reconnect_disabled` | `自动重连已关闭。` |

### English consent text

Title:

`Allow access token use?`

Body:

`FakePlayerProxy needs your Minecraft access token to enable auto-reconnect. If you select Allow, it can use the token to reconnect as your account.`

`Your token is not saved to disk. FakePlayerProxy clears it when auto-reconnect stops.`

Red warning:

`Only use this feature on a server that you trust!`

Buttons:

- `Allow`
- `Decline`

### Simplified Chinese consent text

Title:

`授权使用访问令牌？`

Body:

`FakePlayerProxy 需要使用你的 Minecraft 访问令牌来启用自动重连。点击“允许”后，它可以使用该令牌，以你的账号身份自动重连。`

`你的令牌不会保存到硬盘。自动重连停止后，FakePlayerProxy 会清除令牌。`

Red warning:

`只在你信任的服务器上使用此功能！`

Buttons:

- `允许`
- `拒绝`

## Text boundary

Do not reuse the existing consent title, body, warning, or checkbox keys. Those
keys describe login relay consent. Reuse only the generic Allow and Decline
button keys.

Do not show token content, backend socket address, exception text, or retry
internals in player-facing text. Decline and invalid responses send no player
text.
