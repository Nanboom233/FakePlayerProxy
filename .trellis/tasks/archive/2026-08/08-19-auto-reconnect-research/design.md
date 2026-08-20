# Auto-reconnect design

## Goal

Allow an authorized Shadow player to replace a lost online-mode backend
connection. Preserve the existing automation objects and action intent.

The feature must not persist credentials. It must not add a reconnect service,
configuration file, login-path type, or second retry scheduler.

## Fixed product behavior

- `/fpp auto-reconnect on` asks the connected client for its Minecraft access
  token.
- A well-formed response with a non-empty token immediately enables
  auto-reconnect.
- The consent request has no time limit.
- The proxy does not calculate or track token expiry.
- `/fpp auto-reconnect off` clears the token and disables future reconnect.
- `/player kill` clears the token before it closes the Shadow backend.
- A Shadow backend loss starts an immediate reconnect attempt.
- Later failures wait 10, 10, 30, 30, 60, 60, and then 300 seconds.
- The 300-second delay repeats without an attempt limit.
- Real-player backend Login channels have priority over Shadow reconnect
  channels.
- Duplicate login, profile ban, IP ban, required resource pack, Code of
  Conduct, Transfer, and credential rejection stop auto-reconnect.
- All other backend kicks and transport failures remain retryable.

## Ownership boundary

The plugin layer owns player policy and retained player state. The Velocity patch
owns connection capabilities that the public API cannot provide. The Mod owns
user consent and client credential access.

### Plugin ownership

| Owner | Responsibility |
| --- | --- |
| `FakePlayerProxyPlugin` | Construct owners, register commands and listeners, register the payload channel, and release plugin resources. |
| `EventHandler` | Own existing configuration events and synchronous packet-to-local-state updates. |
| `AuthManager` | Own the consent request, channel identifier, bounded response decoder, and authorization result. |
| `FppCommand` | Add the player-only `auto-reconnect on|off` branch and send command results. |
| `AutomationManager` | Own player lifecycle events, terminal packet policy, authentication failure policy, exact map retention, and terminal removal. |
| `AutomationService` | Hold auto-reconnect state and token data, own retry timing, call the patched reconnect operation, expose failed future causes, and freeze automation. |
| Plugin `Player` | Reset backend-session state while preserving input intent. |
| Plugin `World` | Use the existing clear and packet-rebuild path. It needs no source change. |

`FakePlayerProxyPlugin` only owns plugin initialization and shutdown. It creates
and registers the other owners. It contains no player, packet, authorization,
or terminal event handler.

`EventHandler` receives every existing state-forwarding handler currently stored
in `FakePlayerProxyPlugin`. This includes configuration events and synchronous
clientbound and serverbound packet updates. It also includes the existing
post-connect state update. This move changes ownership only.

`AuthManager` defines the shared channel identifier. `FppCommand` calls it to
start or disable consent. `AuthManager` consumes each response and updates the
exact `AutomationService`.

`AutomationManager` receives PostLogin, Disconnect, terminal backend packets,
and completed authentication failures. It selects retry or exact terminal
cleanup. `EventHandler` uses its source-aware player query before state updates.

`AuthManager` owns authorization and its logs. `AutomationService` owns retry
timing and action gating. `AutomationManager` owns terminal policy and its
logs. The Velocity patch owns backend channel gating, online-mode cryptography,
and headless Velocity handlers.

Do not add an `AutoReconnectService`, credential provider, retry task,
additional payload-handler class, or reconnect request type.

### Velocity patch ownership

| Owner | Responsibility |
| --- | --- |
| `VelocityServer` | Own one authlib `MinecraftSessionService` and expose the backend join operation. |
| `ClientboundPacketEvent`, `PacketEventHandler`, and `MinecraftDecoder` | Store the exact source backend and compare a Plugin candidate with it. |
| `BackendChannelInitializer` | Queue all backend Login writes and select high or low priority. |
| `ConnectedPlayer` | Atomically detach one dead Shadow backend and start a same-target reconnect. |
| `VelocityServerConnection` | Carry continuation state and one temporary token copy into Login. |
| `LoginSessionHandler` | Perform authlib join, key response, and backend encryption. |
| `ConfigSessionHandler` | Complete CONFIG without a writable frontend. |
| `AuthSessionHandler` | Remove the old separate Transfer delay. |

The patch reports connection results and typed failures through the existing
connection future. It does not select retry delays or terminal product policy.
It does not retain the Plugin token after Login consumes its temporary copy.

### Mod ownership

The Mod decodes the request and shows `AutoReconnectConsentScreen`. The screen
extends Vanilla `ConfirmScreen` and returns one boolean choice. The Mixin reads
the client access token after consent and sends one response. The Mod stores no
token, retry state, or consent decision.

### Cross-boundary data

The Plugin passes the expected old `VelocityServerConnection` and one token
copy into the patched reconnect operation. The patch selects that connection's
registered server and returns the existing connection future.

Clientbound packet events compare the exact source backend with the Plugin's
current backend. The Plugin rejects stale events before it changes retained
state. The event does not expose a source getter.

Do not add a reconnect DTO, callback interface, provider interface, or custom
result type at this boundary.

## Service state

`AutomationService` adds only this state:

- one `autoReconnect` boolean
- one access-token byte array
- one attempt number
- one next-attempt time from `System.nanoTime()`
- one reconnect future

`autoReconnect` is the only feature state. A valid non-empty token response
stores the token and sets it to true. Disable and terminal paths clear the token
and set it to false. Do not add pending state or a feature-state enum.

The connection future uses Velocity's existing `ConnectionRequestBuilder.Result`.
Do not add a custom result class.

The service derives reconnect state from existing data:

- A missing backend and a null future mean retry wait.
- A non-null future means one backend reconnect is in progress.
- Existing `inGame` and `playerLoaded` fields identify usable PLAY state.
- Automation runs only with an active backend, `inGame`, and `playerLoaded`.

The reconnect future stays assigned until usable PLAY or failure. It also marks
reconnect CONFIG and PLAY, so those paths preserve actions and input. Do not add
a reconnect phase enum or another reconnect-state field.

The attempt number identifies the reconnect future. The service stores only one
future, so it needs no generation field.

## Consent command

`AuthManager` defines the shared
`fakeplayerproxy:auto_reconnect_v1` channel identifier. `FppCommand` calls
`AuthManager` and does not encode or send payload data.

`FppCommand` receives `AuthManager`. The new branch accepts only a Velocity
Player:

```text
/fpp auto-reconnect on
/fpp auto-reconnect off
```

The branch has no operator permission. A player can change only the token for
that exact player.

`on` uses this flow:

1. `FppCommand` passes the command-source player to `AuthManager`.
2. `AuthManager` resolves the exact Plugin Player from `AutomationManager`.
3. `AuthManager` requires an open service, active frontend, and active backend.
4. `AuthManager` clears the old token and sets `autoReconnect=false`.
5. `AuthManager` sends an empty request to the command-source player.

A second `on` repeats the same operation and sends a new request.

`off` calls one idempotent `AuthManager` operation. That operation sets
`autoReconnect=false` and clears the token. It sends the disabled message and
does not close the active backend.

Both command branches require the active frontend and backend. They cannot run
during Shadow reconnect, so they need no reconnect-channel branch.

## Payload contract

Both directions use `fakeplayerproxy:auto_reconnect_v1`. The channel ID owns
the protocol version. Direction owns the message shape.

The clientbound request has no data.

The serverbound response contains:

1. one VarInt token length
2. the UTF-8 token bytes

A zero token length means Decline. A positive length means Allow. The token
limit is 8192 UTF-8 bytes.

The decoder rejects a negative length, oversized value, malformed UTF-8, or
trailing byte. It checks the length before allocation.

`AuthManager` marks the reserved `PluginMessageEvent` handled before any source
check or parse. It rejects and logs a backend source. This prevents a backend
from opening the consent screen or receiving response bytes.

The handler accepts only an exact Velocity Player owned by `AutomationManager`.
It marshals state changes to that player's retained EventLoop. It does not
correlate a response with an earlier request.

A valid positive response stores the token, sets `autoReconnect=true`, and sends
the enabled message. A zero-length or malformed response does not enable the
feature and sends no player text. Every recognized response stays handled and
never reaches a backend.

## Client design

The mod keeps its no-Fabric-API boundary. It adds no client entrypoint.

`AutoReconnectPayload` is one final payload class. Its request has no field.
Its response contains only the bounded token.

Two codec Mixins add the same payload type to the clientbound and serverbound
PLAY codec lists. Minecraft otherwise converts the message to an unknown
payload and discards its bytes.

`MixinClientPacketListener` handles the decoded request on the game thread. It
captures the previous screen and installs `AutoReconnectConsentScreen`.

`AutoReconnectConsentScreen` extends Vanilla `ConfirmScreen`. It owns the
translated title, body, red warning, button text, and boolean choice callback.
It does not own a server address, connection, payload, access token, or
previous screen.

The Mixin callback reads
`Minecraft.getInstance().getUser().getAccessToken()` after Allow and sends it.
Decline and Escape return `false`, so the callback sends an empty token. The
callback restores the captured previous screen after it sends one response.

The callback closes the confirmation screen after it sends one response. It
does not save consent or add a timer.

The mod defines all player-facing translation keys. The player-only command
does not need duplicate plugin bundle entries.

## Backend packet identity

The current `ClientboundPacketEvent` identifies only a Player. That identity is
not sufficient during reconnect. The old and reconnected backends use the same
Player.

The existing event adds the exact source `ServerConnection`.
`MinecraftDecoder` already has the `VelocityServerConnection` association.
It passes that association through `PacketEventHandler` into the event.

Every plugin clientbound handler compares the event source with the current
in-flight or connected backend before it updates local state. It ignores and
logs stale terminal packets. It ignores other stale state packets without a
per-packet log.

The change uses the existing packet event path. It adds no branch to
`BackendPlaySessionHandler`.

## Backend loss

The synchronous packet event examines Login and common Disconnect packets.
It matches only these root translation keys:

- `multiplayer.disconnect.duplicate_login`
- `multiplayer.disconnect.banned.reason`
- `multiplayer.disconnect.banned_ip.reason`

The handler does not match rendered or nested text. A recognized key uses the
terminal cleanup path before the backend channel closes.

Other Disconnect packets need no saved cause. Their later channel close uses
the same retry path as a transport failure.

On the next manager tick, a missing backend follows this rule:

- If the service is not Shadow, remove and close it.
- If `autoReconnect` is false, remove and close it.
- If `autoReconnect` is true, keep the exact map entry and tick task.

The service clears backend-derived Player and World state. It keeps the exact
Plugin Player, World, AutomationService, cookie map, scheduled actions, action
delays, and input intent.

`Player.resetForConfiguration()` and the spawn reset stop clearing input intent.
Normal configuration paths clear input explicitly before those resets. This
keeps one input owner and avoids a duplicate saved-input field.

## Reconnect attempt

The retained automation tick owns retry timing. It does not advance scheduled
action delays without an active backend, `inGame`, and `playerLoaded`.

When the retry time arrives, the service increments the attempt number. It
clones the token once and calls the patched `ConnectedPlayer` reconnect
operation on the retained EventLoop. It stores the returned future.

The service polls that future on later retained ticks. The future never mutates
plugin state from its completion thread. A failed future returns its unwrapped
cause to `AutomationManager` without classification.

The service applies the next retry delay as the default mechanism.
`AutomationManager` checks whether the cause is terminal. A terminal cause uses
the one terminal operation and discards the scheduled retry. Do not add a
terminal flag or result type.

The patched operation validates these conditions atomically:

- the current server connection belongs to this player
- the old connection has Shadow continuation state
- the old backend channel is inactive
- no other backend connection is in flight

It selects the old registered server, permits that exact dead same-target
reconnect, and installs one new `VelocityServerConnection` as in flight. The
new backend has `logoutCancelled=true` before its first packet.

The new backend connection holds one token copy until `LoginSessionHandler` consumes
it. The Plugin service keeps the original token for later attempts.

### Retained Shadow reconnect

Auto-reconnect keeps the logical Shadow player and creates a new backend
connection to the same registered server.

The exact Velocity `ConnectedPlayer`, Plugin `Player`, `World`,
`AutomationService`, map entry, and tick task remain the same. The patched
operation detaches the dead backend and installs the new backend as the
connection in flight. CONFIG and PLAY packets rebuild backend-derived state on
the retained objects.

### Real-player replacement

A fresh real client with the same UUID does replace the retained Shadow
lifecycle. `AutomationManager` removes the old map entry, sets
`autoReconnect=false`, clears its token, and closes its service and backend. It then
registers a new Plugin `Player` and `AutomationService` for the new
`ConnectedPlayer`. No Shadow automation state transfers to the real player.

## Backend online-mode Login

The Velocity patch adds authlib 9.0.75 to the pinned version catalog and proxy
runtime. `VelocityServer` owns one offline-created
`MinecraftSessionService`. Offline creation skips service-key download but
keeps the production session join endpoint.

`LoginSessionHandler.handle(EncryptionRequestPacket)` keeps the existing relay
path. It uses the new path only when the `VelocityServerConnection` owns a
reconnect token.

The reconnect path performs these steps:

1. Parse the backend RSA public key.
2. Generate a fresh 128-bit AES key.
3. Compute the signed SHA-1 digest from server ID, AES key, and RSA key.
4. Convert the one-time token copy to the String required by authlib.
5. Clear the one-time byte-array copy.
6. Call `MinecraftSessionService.joinServer()` outside every EventLoop.
7. Return to the backend EventLoop.
8. Verify the active handler, connection, and incomplete result future.
9. Encrypt the AES key and backend challenge with the backend RSA key.
10. Write the plaintext key response.
11. Enable backend encryption only after that write succeeds.
12. Clear the AES bytes on every exit path.

The handler completes the existing connection future exceptionally when
authentication or cryptographic work fails. It does not add a result hierarchy.

These authlib exceptions are terminal:

- `InvalidCredentialsException`
- `UserBannedException`
- `ForcedUsernameChangeException`
- `InsufficientPrivilegesException`

`AuthenticationUnavailableException`, HTTP 429, and unknown authentication
failures are retryable. `AutomationService` unwraps completion exceptions and
returns the cause. `AutomationManager` classifies the cause.

## Backend Login priority

`BackendChannelInitializer` adds one outbound gate after
`MINECRAFT_ENCODER`. The gate stores packet objects before encoding.

The initializer owns private nested gate and priority-list classes. It adds no
new source file.

Each resolved remote IP and port owns one high FIFO list and one low FIFO list.
The first channel writes immediately. Each later channel waits for a four-second
slot.

The gate inspects the `MinecraftConnection` association on its first write. A
`VelocityServerConnection` with `logoutCancelled=true` uses low priority. Every
other connection uses high priority.

The gate uses Netty `PendingWriteQueue`. Release writes all messages in their
original order and preserves each `ChannelPromise`. Channel close fails and
releases every pending message.

The high list always wins the next free slot. It does not interrupt an active
slot. Continuous real-player traffic can delay a Shadow reconnect.

Remove the old four-second delay from `AuthSessionHandler.startTransfer()`.
The common gate now owns that limit for normal Login, relay, Transfer, and
Shadow reconnect paths.

## Headless CONFIG and PLAY

After Login Success, the reconnecting Shadow sends Login Acknowledged and installs
`ConfigSessionHandler`. It does not wait for a closed frontend configuration
handler.

The retained frontend stays in PLAY during headless CONFIG. A CONFIG response
must use the backend CONFIG registry. It must not use the retained frontend
state to select its registry.

`ServerboundSelectKnownPacks` is a CONFIG response. `AutomationService` sends it
through the direct backend encoding path. Normal frontend-routed responses keep
the existing frontend-state path.

The backend EventLoop owns each response write. The write operation catches and
logs a runtime failure before it leaves that EventLoop. The log identifies the
player, backend, packet operation, and reconnect attempt. It contains no token
or credential data.

The patch does not add a session-service getter. `VelocityServer` keeps the
service private and exposes the backend session join operation. The patch also
does not expose the packet-event source. `ClientboundPacketEvent` compares a
candidate connection with its stored source. The Plugin uses the current
backend only after this comparison succeeds.

`ConfigSessionHandler` applies this resource-pack policy:

- If the same hash is already applied, reply Accepted, Downloaded, and
  Successful.
- If a new pack is optional, reply Declined.

`AutomationManager` terminates a new required pack before
`ConfigSessionHandler` processes it. Its terminal operation clears the
authorization and closes the backend connection.

A resource-pack removal updates the retained resource-pack handler without a
frontend write.

`AutomationManager` applies terminal cleanup to every Code of Conduct packet
and Transfer packet. Do not save Code of Conduct acceptance. Do not follow
Transfer while the frontend is closed.

On Finished Update, `ConfigSessionHandler` changes the backend codec to PLAY,
writes Finished Update, and installs the existing `TransitionSessionHandler`.
It does not wait for `ClientConfigSessionHandler`.

The existing transition attaches the new backend to the same ConnectedPlayer.
The existing packet events rebuild the same Plugin Player and World.

`AutomationService.startConfiguration()` preserves scheduled actions and input
when the reconnect future is non-null.

`AutomationService.enterGame()` preserves scheduled actions during reconnect.
The Login packet initializes the same Plugin Player.

The service waits for the initial position and current chunk. It then sends
`ServerboundPlayerLoadedPacket`.

That write clears the reconnect future and resets the retry sequence. Existing
`inGame` and `playerLoaded` fields then allow automation. The next tick reapplies
input and starts the unchanged action delays.

## Terminal cleanup

One `AutomationManager` terminal operation removes the exact map entry before
it closes the service. It prevents a canceled tick task from leaving a map
entry.

The terminal operation follows this order:

1. Set `autoReconnect=false`.
2. Cancel or close the active backend reconnect.
3. Clear the retry time and connection future.
4. Overwrite and clear the token byte array.
5. Remove the exact manager entry.
6. Close the service and backend connection.

These paths use that operation:

- `/player kill`
- recognized duplicate-login or ban Disconnect
- required resource pack
- Code of Conduct
- Transfer
- terminal authlib rejection
- fresh same-UUID real login replacement
- plugin shutdown
- unrecoverable controller failure

`/fpp auto-reconnect off` sets `autoReconnect=false` and clears the token. It keeps
the active non-Shadow automation service and backend.

## Logging

`AuthManager` logs these INFO transitions:

- consent requested, accepted, or declined
- explicit disable

`AutomationManager` logs these INFO transitions:

- Shadow activation with auto-reconnect enabled
- backend loss and the selected delay
- reconnect attempt submission
- ready PLAY and automation resume
- real-player replacement or shutdown cleanup

`AuthManager` logs malformed, stale, and rejected authorization responses at
WARN. `AutomationManager` logs retry failures and terminal policy decisions at
WARN. It logs state corruption, attachment failure, and cleanup failure at
ERROR.

Each applicable log contains player name, UUID, backend name, attempt number,
event category, result category, and next delay.

No log contains token bytes, token length, token hash, payload bytes,
Authorization data, join body, or an HTTP response body.

## File ownership

### Mod

- `mod/src/main/java/com/fakeplayerproxy/mod/packets/AutoReconnectPayload.java`
- `mod/src/main/java/com/fakeplayerproxy/mod/gui/AutoReconnectConsentScreen.java`
- `mod/src/main/java/com/fakeplayerproxy/mod/mixins/MixinClientboundCustomPayloadPacket.java`
- `mod/src/main/java/com/fakeplayerproxy/mod/mixins/MixinServerboundCustomPayloadPacket.java`
- `mod/src/main/java/com/fakeplayerproxy/mod/mixins/MixinClientPacketListener.java`
- `mod/src/main/resources/fakeplayerproxy-mod.mixins.json`
- `mod/src/main/resources/assets/fakeplayerproxy-mod/lang/en_us.json`
- `mod/src/main/resources/assets/fakeplayerproxy-mod/lang/zh_cn.json`

Do not change the mod build file or add Fabric API.

### Plugin

- `plugin/src/main/java/com/fakeplayerproxy/FakePlayerProxyPlugin.java`
- `plugin/src/main/java/com/fakeplayerproxy/utils/EventHandler.java`
- `plugin/src/main/java/com/fakeplayerproxy/utils/AuthManager.java`
- `plugin/src/main/java/com/fakeplayerproxy/command/FppCommand.java`
- `plugin/src/main/java/com/fakeplayerproxy/automation/AutomationManager.java`
- `plugin/src/main/java/com/fakeplayerproxy/automation/AutomationService.java`
- `plugin/src/main/java/com/fakeplayerproxy/world/player/Player.java`

Do not add a protocol package or plugin translation keys.

### Velocity patch

Create `plugin/patch/0003-login-session.patch`. Generate it after applying
`0001-login-relay.patch` and `0002-automation-extension.patch`. The approved
ownership correction removes Transfer's private delay from `0001`; `0003`
alone owns the common four-second backend wait:

- `gradle/libs.versions.toml`
- `proxy/build.gradle.kts`
- `VelocityServer`
- `BackendChannelInitializer`
- `ConnectedPlayer`
- `VelocityServerConnection`
- `LoginSessionHandler`
- `ConfigSessionHandler`
- `AuthSessionHandler`
- `ClientboundPacketEvent`
- `PacketEventHandler`
- `MinecraftDecoder`

Update `plugin/patch/README.md` with the new patch purpose and order. Outside
the approved Transfer-delay ownership and EOF-hygiene corrections, do not
rewrite `0001` or `0002`. Do not commit generated Velocity source.

Edit `plugin/build.gradle.kts` only to add the explicit `0003` include. Keep the
existing sorted Grgit apply path.

### Specifications and operations

- `.trellis/spec/frontend/fabric-client-mod.md`
- `.trellis/spec/backend/velocity-plugin.md`
- `docs/product/operation-guide.md`

The operation guide documents command use, token custody, stop conditions,
retry delays, and the relevant proxy logs.

## Compatibility and rollback

Clients without the mod discard the request. They send no token and cannot
enable the feature.

The payload v1 format has no negotiation. A future incompatible format must use
a new channel ID.

Removing the feature requires removal of the command branch, payload channel,
mod codecs, reconnect branch, CONFIG branch, and priority gate. The original
single-backend Shadow lifecycle then remains.

## Excluded work

- persistent token storage
- refresh-token acquisition
- clients without the mod
- backend plugin cooperation
- third-party CONFIG protocol handling
- general server failover
- a UI for reconnect status
- a retry or retention configuration
