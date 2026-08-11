# Research: Mod consent before key

> This report contains historical evidence and rejected options. The user
> rejected its HEAD cancellation and `handleHello` re-entry recommendation.
> Do not implement the recommendation in this report. The current `prd.md` and
> `design.md` define the approved consent boundary and user actions.

- Query: How can the Fabric Mod show consent after it validates the FakePlayerProxy envelope and before a key packet that contains `K` leaves the client?
- Scope: mixed
- Date: 2026-08-10

## Verdict

The consent gate is feasible for Minecraft 26.2.

Use the existing cancellable `handleHello` HEAD injection. Validate the full
envelope there. Cancel the first method call when the result is `SUPPORTED`.
Then use `Minecraft.execute` to show a `ConfirmScreen` subclass on the game
thread.

On acceptance, run the same `handleHello` method once with a one-shot bypass.
This is the smallest option. It keeps the current argument hooks. It also keeps
Minecraft in control of key generation, session join, packet send, and AES
activation.

This option gives a direct pre-send proof. The cancelled call cannot reach the
first Vanilla instruction. Minecraft does not create `K`. Minecraft does not
create `ServerboundKeyPacket`. Minecraft does not call `Connection.send`.
Only the accepted one-shot call can reach those operations.

The Mod must keep the `Connection` ticking while the consent screen replaces
`ConnectScreen`. This requirement is necessary for clean timeout and disconnect
handling.

## Files Found

- `.trellis/tasks/08-10-client-login-negotiation-research/task.json` defines the active research task.
- `.trellis/tasks/08-10-client-login-negotiation-research/prd.md` defines Track B and its acceptance criteria.
- `.trellis/workflow.md` requires persistent research and prohibits product changes in this phase.
- `.trellis/spec/frontend/fabric-client-mod.md` defines the Mod packet, key, error, and ownership contracts.
- `mod/src/main/java/com/fakeplayerproxy/mod/mixins/MixinClientHandshakePacketListenerImpl.java` contains the current 26.2 login hooks.
- `mod/src/main/java/com/fakeplayerproxy/mod/packets/ServerHelloPacketEnvelope.java` validates the decorated SPKI and builds the acknowledgement.
- `mod/src/main/java/com/fakeplayerproxy/mod/FakePlayerProxyMod.java` states that the current Mod has no global connection state.
- `mod/src/main/resources/fakeplayerproxy-mod.mixins.json` registers the client login Mixin.
- `plugin/patch/0001-server-hello-marker.patch` contains the stored Velocity relay patch.
- `build/velocity-patch-check-worktree` contains the pinned Velocity source at commit `843a47e2a38325309cd66133149fc9a984f76bb8`.
- `.trellis/tasks/archive/2026-08/06-12-fake-player-proxy-research/research/minecraft-1.20-26.2-spki-carrier-compatibility.md` gives the earlier provider and version evidence.
- `.trellis/tasks/archive/2026-08/06-12-fake-player-proxy-research/research/server-hello-envelope-capacity.md` gives the earlier packet and RSA capacity evidence.
- `.trellis/tasks/archive/2026-08/06-12-fake-player-proxy-research/research/server-hello-marker.md` gives the earlier marker design evidence.

## Evidence Target

The local Loom artifact is Minecraft 26.2 with official names.
Its embedded `version.json` reports protocol 776 and Java 25.

- Binary SHA-256: `1463A746E967BAA2393530DEA69B0DA3C46838935B2A63C38843C1325F2BDEEB`
- Source SHA-256: `E1AAA82F91A79407D2828D2057F29F4C0009A559CC713B248AB71D04372B8DA3`
- Binary: `E:/Gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.2/minecraft-merged-deobf-26.2.jar`
- Source: `E:/Gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.2/minecraft-merged-deobf-26.2-sources.jar`

The Velocity source uses Netty `4.2.15.Final`.
The stored project patch applies to the pinned Velocity commit above.

## Verified Facts

### Current Mod behavior

The current Mixin injects at cancellable `handleHello` HEAD
(`MixinClientHandshakePacketListenerImpl.java:56-58`). It parses the received
proxy key and calls `ServerHelloPacketEnvelope.inspect`
(`MixinClientHandshakePacketListenerImpl.java:62-65`).

The helper returns `PASSTHROUGH` before relay rules for an ordinary RSA SPKI.
It validates the OID, magic, version, bounded length, exact envelope length, and
embedded RSA key before it returns `SUPPORTED`
(`ServerHelloPacketEnvelope.java:54-114`).

The current hook disconnects and cancels a declared but invalid envelope
(`MixinClientHandshakePacketListenerImpl.java:69-85` and `:101-113`). It lets an
ordinary key continue without changed arguments
(`MixinClientHandshakePacketListenerImpl.java:66-68`).

For a supported envelope, the current hook stores the target public key and the
acknowledgement. It does not cancel the method
(`MixinClientHandshakePacketListenerImpl.java:74-93`). The two later hooks use
the target key only for `Crypt.digestData` and use the acknowledgement only for
the challenge argument of `ServerboundKeyPacket`
(`MixinClientHandshakePacketListenerImpl.java:122-152`).

The consent design must change this supported branch. It must cancel there.

### Exact packet thread and call order

Minecraft adds `Connection` as the Netty packet handler
(`Connection.java:450-456`). Netty calls `Connection.channelRead0` on the
channel event loop. That method calls the packet directly
(`Connection.java:144-164`).

`ClientboundHelloPacket.handle` directly calls
`ClientLoginPacketListener.handleHello`
(`ClientboundHelloPacket.java:44-46`). Neither this packet method nor the login
listener calls `PacketUtils.ensureRunningOnSameThread`.

The exact initial path is:

1. Netty decodes `ClientboundHelloPacket`.
2. The channel event-loop thread calls `Connection.channelRead0`.
3. `Connection.channelRead0` calls `Packet.handle`.
4. `ClientboundHelloPacket.handle` calls `ClientHandshakePacketListenerImpl.handleHello`.
5. The Mixin HEAD callback runs before line 114 of the target method.
6. The current hook parses and validates the envelope on the channel event-loop thread.

The Mixin must not wait for the user in this callback. A wait would block all
I/O for that channel.

### Vanilla work after HEAD

If the HEAD callback does not cancel, Minecraft performs this work in order:

1. It changes the listener state from `CONNECTING` to `AUTHORIZING`.
2. It generates the 128-bit AES secret `K`.
3. It parses the received proxy public key.
4. It computes the session digest.
5. It creates the inbound AES cipher.
6. It creates the outbound AES cipher.
7. It reads the challenge.
8. It constructs `ServerboundKeyPacket`.
9. It starts the Mojang join on `Util.ioPool` when `shouldAuthenticate` is true.
10. It calls `setEncryption` without a join when `shouldAuthenticate` is false.

These steps appear at
`ClientHandshakePacketListenerImpl.java:113-148`.

The packet constructor encrypts `K` and the challenge as two RSA values
(`ServerboundKeyPacket.java:18-21`). The join uses the profile ID, access token,
and computed digest (`ClientHandshakePacketListenerImpl.java:156-170`).

`setEncryption` sends the key packet first. Its successful send callback then
installs the two AES ciphers
(`ClientHandshakePacketListenerImpl.java:151-154`).

`Connection.send` runs the write on the channel event loop. It queues the write
there when the caller is on another thread
(`Connection.java:279-313`). The accepted continuation can therefore call the
public `handleHello` method from the game thread. The packet write still returns
to the channel event loop.

### Screen scheduling

`Minecraft` implements the event-loop `execute` method. A call from another
thread adds the task to a concurrent queue and unparks the game thread
(`BlockableEventLoop.java:43-50` and `:91-104`).

Use this sequence in the supported HEAD branch:

1. Store one pending request.
2. Call `CallbackInfo.cancel`.
3. Call `minecraft.execute` with a short screen task.
4. Return from the network callback.
5. Let the game thread call `minecraft.gui.setScreen`.

Do not call `Gui.setScreen` on the network thread. The 26.2 method reports an
off-thread call in development mode
(`Gui.java:222-225`). It also removes the old screen and initializes the new
screen (`Gui.java:227-259`).

`ConfirmScreen` already gives two buttons and maps Escape to the false callback
(`ConfirmScreen.java:74-76` and `:94-106`). A small subclass can add connection
ownership and an unresolved-removal callback.

### Connection ticking while the screen is open

`ConnectScreen.tick` calls `connection.tick` while the channel is open. It calls
`connection.handleDisconnection` after the channel closes
(`ConnectScreen.java:201-209`).

`Gui.setScreen` removes `ConnectScreen`. Minecraft then stops calling its
`tick` method. The consent screen must perform the same connection tick logic.
Without this logic, a remote close or read timeout can close the channel while
the consent screen remains visible.

The consent screen does not own the login state. It only keeps the connection
lifecycle active and sends one user decision to the listener owner.

### Read and login timeouts

The Minecraft client installs `ReadTimeoutHandler(30)` on a remote connection
(`Connection.java:426-439`). A Netty read timeout becomes the normal client
timeout disconnect (`Connection.java:105-115`).

The pinned Velocity frontend installs a configurable read timeout
(`build/velocity-patch-check-worktree/proxy/src/main/java/com/velocitypowered/proxy/network/ServerChannelInitializer.java:59-70`).
The pinned Velocity backend installs the same configurable timeout
(`build/velocity-patch-check-worktree/proxy/src/main/java/com/velocitypowered/proxy/network/BackendChannelInitializer.java:51-63`).
The default is 30000 milliseconds
(`build/velocity-patch-check-worktree/proxy/src/main/resources/default-velocity.toml:127-131`).

The stored patch sends the decorated target Hello and then waits for the
frontend key response (`plugin/patch/0001-server-hello-marker.patch:1035-1068`).
It does not add a consent timeout.

The Minecraft 26.2 target server also rejects a slow login after 600 ticks
(`ServerLoginPacketListenerImpl.java:49-52` and `:72-85`). It enters the `KEY`
state before it sends Server Hello
(`ServerLoginPacketListenerImpl.java:118-129`).

The default useful consent period is therefore less than approximately 30
seconds. Backend connection setup already uses part of that period. The first
close among the client, Velocity frontend, Velocity backend, and target server
ends the request.

This is an idle-read limit. It is not a fixed wall-clock consent promise.

## Byte-Level Probe Results

The probe used `javap -c -p` on the binary named in `Evidence Target`.
It did not modify the artifact.

The bytecode gave these results:

- `ClientboundHelloPacket.handle` invokes `ClientLoginPacketListener.handleHello` directly.
- `Connection.channelRead0` invokes the packet through `genericsFtw`.
- The login path contains no call to `PacketUtils.ensureRunningOnSameThread`.
- `handleHello` calls `Crypt.generateSecretKey` before every digest, cipher, packet, join, and send operation.
- `handleHello` constructs `ServerboundKeyPacket` before it schedules the Mojang join.
- `setEncryption` calls `Connection.send` with `PacketSendListener.thenRun`.
- `Connection.sendPacket` checks `eventLoop.inEventLoop` and otherwise calls `eventLoop.execute`.
- The remote connection bytecode constructs `ReadTimeoutHandler` with 30 seconds.

This probe confirms the source call order. It also confirms that the HEAD hook
runs on the Netty channel event-loop path for Minecraft 26.2.

No full game runtime probe was run. The consent code does not exist in this
research phase. The implementation phase must add an integrated login test.

## Inferences

The following conclusions combine the verified source paths above. They are not
results from a running consent implementation:

- A one-shot call from the game-thread callback can reuse `handleHello`. The
  target method has no game-thread guard. `Connection.send` moves its write to
  the channel event loop. The implementation still needs an integrated test.
- The consent screen must tick the connection. `ConnectScreen` is the only
  visible owner that does this before replacement. `Gui.setScreen` removes that
  owner when it installs the consent screen.
- The effective decision period is the shortest remaining timeout across four
  connections and listeners. No inspected code coordinates those timers.
- The socket peer is the only common display value that does not come from
  Server Hello metadata. It identifies a transport endpoint. It does not prove
  operator identity.
- A stable duplicate-packet disconnect is safer than a second prompt. Vanilla's
  own state transition already treats a second Hello as invalid.
- No thread waits while the user decides. The network callback returns after it
  queues the screen. The game thread continues to render and tick the screen.

## Recommended Control Flow

### Lifecycle owner

Keep the state on the Mixin instance for
`ClientHandshakePacketListenerImpl`. Minecraft creates one listener for the
login connection. The current Mod already uses this ownership model
(`FakePlayerProxyMod.java:6-16`).

Do not put pending consent in `FakePlayerProxyMod`. Global state can mix two
connections and can keep stale packet data after a disconnect.

Use one synchronized or atomic phase value. Suggested phases are:

| Phase | Meaning |
| --- | --- |
| `UNSEEN` | No Server Hello has reached this listener. |
| `VANILLA` | The first Hello was unmarked and follows Vanilla. |
| `WAITING` | One valid envelope waits for a decision. |
| `RESUME_ONCE` | Acceptance armed one exact re-entry. |
| `CONTINUING` | The accepted Vanilla call is running. |
| `DONE` | The accepted Vanilla call returned. |
| `TERMINAL` | Reject, close, disconnect, duplicate, or error ended the request. |

Track the first Hello even when it is unmarked. This prevents a marked second
Hello from opening a consent screen after Vanilla has already started login.

### Retained values

Retain only these values while phase is `WAITING`:

- The exact `ClientboundHelloPacket` reference.
- The validated target `PublicKey`.
- A private acknowledgement byte array.
- A unique request token.
- The previous `Screen` after the game-thread task verifies it.
- A display value derived from the local connection context.

The listener already owns `Minecraft` and `Connection`. The Mixin can shadow
the `minecraft` field and use its existing `connection` shadow.

Do not retain `K`, an access token, a digest, an AES cipher, or a key packet.
The recommended flow creates none of these values before acceptance.

### Initial valid Hello

Use the current full envelope inspection at HEAD. After the helper returns
`SUPPORTED`, validate the acknowledgement bound. Then perform these actions:

1. Change `UNSEEN` to `WAITING`.
2. Store the retained values.
3. Cancel the target method.
4. Queue the screen task with `minecraft.execute`.

The screen task must verify the request token and `connection.isConnected`.
It must clear the request when the connection closed before presentation.
It must catch screen construction or initialization failures. It must log the
complete exception, clear state, and disconnect with a stable component.

### Acceptance

The button callback runs on the game thread. It must perform one atomic
`WAITING` to `RESUME_ONCE` transition. A second callback must do nothing.

Mark the screen resolved before restoring the previous screen. This order makes
`Screen.removed` a no-op for the accepted path.

Verify that the connection is still open. Then call the same public
`handleHello` method with the exact retained packet. The HEAD hook must accept
only the `RESUME_ONCE` phase and the same packet identity. It changes the phase
to `CONTINUING`, installs the retained target key and acknowledgement for the
two existing `@ModifyArg` hooks, and returns without cancellation.

The transformed method now executes once. The two existing argument hooks run
in that same invocation. Clear the retained packet, target key,
acknowledgement, and screen reference in a `finally` block after the call
returns. Keep phase `DONE` so a later Hello cannot open a second screen.

Catch an exception that escapes the resumed call. Log the full exception. Move
to `TERMINAL`. Clear all retained values. Disconnect with a stable user-facing
component.

### Same `K` result

Acceptance starts one complete Vanilla invocation. That invocation creates one
`SecretKey` at line 121. The same local value enters the target digest at line
123, both AES ciphers at lines 124-125, and `ServerboundKeyPacket` at line 127.

The target-key argument hook changes only the digest public key. The
acknowledgement hook changes only the packet challenge. The response still
encrypts that one `K` with the proxy public key.

When `shouldAuthenticate` is true, the target digest join finishes before the
key packet send. When it is false, no join runs. Both paths use the normal
send-then-enable callback.

### Proof that `K` cannot leave before acceptance

The proof has four parts:

1. The first supported call is cancelled at HEAD.
2. The Mod does not create a key packet in the waiting path.
3. Only one accepted request token can arm the exact packet re-entry.
4. `Connection.send` appears only after the accepted Vanilla invocation reaches `setEncryption`.

Reject, close, disconnect, duplicate, and exception paths never arm the
re-entry. They cannot reach the key packet constructor or send call.

### Reject and close

Treat the No button and Escape as the same rejection. Change `WAITING` to
`TERMINAL`. Clear all retained values. Disconnect the connection. Do not call
`handleHello`.

Override `removed` in the consent screen. If an unresolved screen is replaced,
send the same reject action. Use the one-shot phase change to prevent a second
action when the callback itself replaces the screen.

The user can return to the previous screen after the disconnect. The normal
`ConnectScreen` tick can then process the disconnect details.

### Remote disconnect and timeout

The consent screen must call `connection.tick` while connected. It must call
`connection.handleDisconnection` after closure. The login listener then opens
the normal `DisconnectedScreen`
(`ClientHandshakePacketListenerImpl.java:207-214`).

Add a small cleanup hook to the listener `onDisconnect` path. It moves any
pending request to `TERMINAL` and clears retained values before Vanilla replaces
the screen. It must not start a second disconnect.

If the user clicks Yes after closure, the connection check fails. The owner
clears the request and does not resume login.

### Duplicate packet

Only the exact synthetic re-entry can pass after the first Hello.

For every other `ClientboundHelloPacket`, cancel the callback and disconnect
with a stable protocol error. Do not replace the first packet. Do not show a
second screen. Do not clear active continuation arguments until the accepted
call leaves its `finally` block.

This rule covers these cases:

- A duplicate arrives while the screen is open.
- A duplicate arrives after acceptance but before the session join finishes.
- A marked Hello follows an unmarked first Hello.
- An unmarked Hello follows a marked first Hello.
- A duplicate arrives after `DONE`.

Vanilla also has a one-shot state guard. Its `AUTHORIZING` transition accepts
only `CONNECTING` (`ClientHandshakePacketListenerImpl.java:101-109`). The Mod
rule gives a stable disconnect before this guard throws.

### Exception behavior

Use these boundaries:

- Keep the current Vanilla path for an ordinary key parse failure.
- Reject a declared invalid envelope without a deliberate throw.
- Catch and log a screen scheduling or screen initialization exception.
- Catch and log an exception from accepted re-entry.
- Let Vanilla map expected Mojang join failures to its existing components.
- Let `Connection` own packet write and cipher failures after successful resume.
- Clear retained values in every terminal path.

Pass each diagnostic `Throwable` to the logger. Do not show raw exception text
to the player.

## Identity and Security

### Identity that the screen can show

The envelope contains a target public key. It does not contain an authenticated
target name. The packet server ID, proxy key, target key, and challenge all come
from the connected server.

The screen can show the local connection context. The narrowest value is
`connection.getRemoteAddress`. This is the actual socket peer. It is not
protocol metadata.

For a normal user-selected connection, `ServerData.name` and `ServerData.ip`
also come from the local server entry
(`ServerData.java:22-43`). They are labels, not authenticated identities.
Transfer login reuses `ServerData`, so its saved address can describe the old
entry. The socket peer is the safer common value.

Do not present these items as an authenticated target identity:

- The Server Hello server ID.
- The target public key or its fingerprint.
- A target name added to a future envelope.
- The `FPPMOD` marker.
- The connected proxy key.

### Marker spoofing

Any server can create an RSA key pair. It can place `FPPMOD`, version 1, and an
arbitrary RSA target key in the accepted SPKI shape. The current validator
checks syntax and key usability. It checks no signature, trust anchor, pinned
key, or configured authorization.

The marker therefore means only this:

> This Server Hello uses the FakePlayerProxy protocol shape.

It does not prove these claims:

- The server runs the project patch.
- The server is trusted.
- The proxy owns or controls the claimed target.
- The target authorized the proxy.
- The selected address resolves to an expected operator.

The acknowledgement proves that a compatible client processed the challenge.
It does not authenticate the server to the client.

Use a security statement with this meaning:

> The server connection at `<socket peer>` requests FakePlayerProxy mode. The protocol marker does not verify the server or the target. Continue only if you trust this server address.

Do not use the words `verified proxy`, `trusted target`, or `authenticated
FakePlayerProxy` for this screen.

## Control-Flow Options

### Option A: cancel at HEAD and re-enter once

This is the recommended option.

Benefits:

- It gives the strongest pre-send proof.
- It creates no `K` before consent.
- It starts no Mojang join before consent.
- It reuses both current argument hooks.
- It reuses Vanilla error mapping and AES activation.
- It keeps unmarked first-Hello behavior unchanged.
- It stores no secret or cipher while the user decides.

Costs:

- It needs a strict one-shot bypass.
- It needs connection-scoped lifecycle state.
- The consent screen must tick the connection.
- The accepted call runs from the game-thread callback. Its send operation is
  still moved to the Netty event loop by `Connection.send`.

### Option B: let Vanilla run and gate `setEncryption`

The initial HEAD hook can validate the marker and schedule the screen without
cancelling. A cancellable injection at private `setEncryption` can retain the
completed key packet and AES ciphers. Acceptance can then call `setEncryption`
once with a bypass.

This option can prevent the packet from leaving before acceptance. It has larger
and less desirable effects:

- Vanilla creates `K` before the screen can appear.
- Vanilla creates the RSA key packet before the screen can appear.
- A required Mojang join can start before consent.
- The decision and `setEncryption` can arrive in either order.
- The Mod must retain a key packet and two live cipher objects.
- It needs another one-shot bypass in a private method.
- Rejection can race with join completion.

Reject this option. It meets the narrow packet condition but gives weaker
consent ordering and more state.

### Option C: replace the supported Vanilla flow

A cancellable HEAD hook can implement key generation, digest, session join,
packet construction, send, and AES activation in Mod code after acceptance.

Reject this option. It duplicates the 26.2 login method. It must also duplicate
state transitions and authentication error mapping. It creates a larger update
surface than one-shot re-entry.

## Minimum Viable Design

The minimum design has these parts:

1. Extend the current HEAD hook with a connection-scoped phase machine.
2. Cancel only a fully validated supported envelope.
3. Queue one `ConfirmScreen` subclass with `Minecraft.execute`.
4. Tick the retained `Connection` from that screen.
5. Resume the exact packet through one accepted `handleHello` re-entry.
6. Keep the two current `@ModifyArg` hooks.
7. Add idempotent cleanup for reject, removal, disconnect, duplicate, and error.
8. Show only local connection context and the marker warning.

An unmarked first Hello must return from HEAD without cancellation. It must use
the original digest key, challenge, packet constructor, join, send, and AES
callback. This is the required Vanilla preservation rule.

## Required Implementation Tests

The later implementation should add focused tests for these outcomes:

- An ordinary RSA Hello never opens the screen and reaches Vanilla once.
- A valid envelope opens one screen and sends no key packet before Yes.
- Yes resumes once and sends one standard key packet.
- The target digest and response use the same Vanilla-generated `K`.
- `shouldAuthenticate=false` performs no join.
- No, Escape, and unresolved screen removal send no key packet.
- Remote disconnect and client read timeout clear the pending request.
- A second Hello disconnects and cannot replace the first request.
- A double callback cannot resume twice.
- A screen error and a resume error clear state and disconnect cleanly.
- An accepted request after channel closure sends nothing.

The key assertion must observe the real outbound packet boundary. A reflection
test of private fields is not sufficient.

## Open Product Decisions

- Decide whether No returns directly to the previous screen or first shows a
  disconnect reason.
- Decide whether the Mod adds a local deadline shorter than the network limits.
  The protocol already fails near 30 seconds by default.
- Decide whether the screen shows only the socket peer or also shows the saved
  server label for a normal non-transfer connection.
- Decide the final localized text. It must keep the marker warning above.

## External References

- Mojang version manifest: `https://piston-meta.mojang.com/mc/game/version_manifest_v2.json`
- Pinned Velocity source: `https://github.com/PaperMC/Velocity/tree/843a47e2a38325309cd66133149fc9a984f76bb8`
- Netty 4.2 `ReadTimeoutHandler`: `https://netty.io/4.2/api/io/netty/handler/timeout/ReadTimeoutHandler.html`
- Netty 4.2 `EventLoop`: `https://netty.io/4.2/api/io/netty/channel/EventLoop.html`
- Sponge Mixin cancellable injector contract: `https://github.com/SpongePowered/Mixin/blob/master/src/main/java/org/spongepowered/asm/mixin/injection/Inject.java`
- Sponge Mixin `CallbackInfo.cancel`: `https://github.com/SpongePowered/Mixin/blob/master/src/main/java/org/spongepowered/asm/mixin/injection/callback/CallbackInfo.java`

The Netty API states that `ReadTimeoutHandler` raises a timeout when no data was
read in the configured period. The EventLoop API states that the event loop
handles I/O for its registered channel. These statements match the inspected
Minecraft and Velocity pipelines.

## Related Specs

- `.trellis/spec/frontend/fabric-client-mod.md`
- `.trellis/spec/guides/cross-layer-thinking-guide.md`

The Fabric spec requires Vanilla passthrough, one target join, standard login
packets, Minecraft-owned AES activation, connection-scoped cleanup, and stable
user-facing errors. Option A preserves those contracts.

## Caveats / Not Found

- No consent implementation exists, so no end-to-end screen runtime was possible.
- The one-shot re-entry design is a source and bytecode conclusion. It needs a real Fabric login test after implementation.
- The approximately 30-second period is version-specific and configuration-specific. Server tick delay can change the 600-tick wall time.
- `connection.getRemoteAddress` identifies the socket peer only. Minecraft login has no server-authentication trust chain.
- The saved server name is user-controlled local data. It is not proof of network identity.
- The non-NULL RSA AlgorithmIdentifier carrier remains specific to compatible JCA providers. The archived compatibility report contains that evidence.
- This report does not change the stored Velocity patch, Mod, PRD, or specs.
