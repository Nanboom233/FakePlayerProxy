# Vanilla Transfer Fallback And Mod Consent Design

## Design Result

Use one decorated first Server Hello to choose between two paths.

- A Modded client pauses for consent. Acceptance resumes the current packet
  relay.
- A Vanilla client completes a short first login. Velocity transfers it back to
  the same public endpoint and changes the second connection into a raw tunnel.

The raw tunnel uses the existing Velocity listener. The `TRANSFER` handshake
intent selects the raw path. This removes the need for another listener, port,
token, cookie, or Plugin bridge.

## Connection Flow

### First Connection

```text
Client                 Patched Velocity                Fixed target
  | -- LOGIN handshake ------>|                              |
  | -- Login Start ---------->| -- LOGIN handshake/start -->|
  |                           | <- target Server Hello ------|
  | <- decorated Hello -------|                              |
  |                           |                              |
  | create K1 and ciphers     |                              |
  | Mod: wait for consent     |                              |
  | Vanilla: continue         |                              |
  | -- selected key response->|                              |
  |                           | classify response            |
```

Allow keeps the current Mod relay. A standard response contains the unchanged
target challenge and selects the Vanilla Transfer path.

### Vanilla Branch

```text
Client                 Patched Velocity                Fixed target
  | -- Vanilla response ----->|                              |
  |                           | close provisional target     X
  | <== frontend AES K1 =====>|                              |
  | <- Login Success ---------|                              |
  | -- Login Acknowledged --->|                              |
  |                           | wait 4 s after PostLogin     |
  | <- Transfer(same gateway)-|                              |
  X                           |                              |
  | -- TRANSFER handshake --->|                              |
  | -- Login Start ---------->| -- LOGIN handshake/start -->|
  |                           | <- exact target Hello -------|
  | <- exact target Hello ----|                              |
  | -- target key response ================================>|
  | <================ opaque target AES K2 =================>|
```

The first connection exists only to classify the client and issue Transfer.
The second connection owns the real target session.

### Mod Consent Branch

1. Vanilla calls `handleHello` once and changes its login state to
   `AUTHORIZING`.
2. Vanilla creates `K`, both ciphers, the proxy digest, and the original
   response values.
3. The login-listener Mixin decodes the target public key before Vanilla
   constructs the standard key response.
4. An empty decode result returns to the unchanged Vanilla method.
5. A present target key creates one prepared login with both choices.
6. The Mixin cancels the remaining method before authentication or packet send.
7. The game thread shows the consent screen.
8. Allow selects the relay choice. Decline selects the Vanilla choice.
9. The selected choice uses Vanilla's existing authentication and encryption
   helpers.

Both choices use the same `K` and the same ciphers. Escape closes the connection
without authentication or a key response.

## Velocity Boundaries

### Vanilla Classification

`InitialLoginSessionHandler` keeps the existing exact response classification.
For `VANILLA`, it performs these actions:

1. Enable frontend AES with `K1`.
2. Close the provisional backend.
3. Clear target challenge and key state.
4. Continue through `AuthSessionHandler` with the provisional login profile.
5. Select its `Transfer` continuation.

Do not add a Mojang lookup for the short gateway session. The online target
authenticates the second connection and owns the final profile.

### Transfer Point

`AuthSessionHandler` already owns Login Success and Login Acknowledged. Keep
the authenticated relay backend as the real Mod-path owner. Represent its three
construction-time continuations with one sealed value:

- `InitialServer` for ordinary initial-server selection;
- `Relay` for the existing player, authenticated backend, and target session ID;
- `Transfer` for the existing short-login player.

These variants replace the independent `existingPlayer`, `relayBackend`,
`relaySessionId`, and `transferAfterLogin` fields. They do not describe protocol
phases and do not add a destination mapper. Exhaustive pattern switches select
profile reuse, Login Acknowledged behavior, session ID forwarding, and cleanup.

When the `Transfer` continuation handles the Vanilla acknowledgement, set the
frontend protocol to Configuration and fire the existing `PostLoginEvent`. After
that future succeeds, use `CompletableFuture.delayedExecutor` with a four-second
delay and the frontend event loop to call the existing
`ConnectedPlayer.transferToHost` API with the original gateway host and port.
The provisional backend was closed before `AuthSessionHandler` received this
continuation, so delaying from the later PostLogin boundary guarantees at least
four seconds between the two target handshakes.

Keep the current closed-connection check inside the delayed action. Do not use
`Thread.sleep`, add a timer field, add a phase, retry a rejected connection, or
make the delay configurable. Do not start initial backend selection or add a
destination-mapping layer. See `research/paper-connection-throttle.md` for the
verified Paper timing and rejected-retry behavior.

### Raw Tunnel Bootstrap

`HandshakeSessionHandler` handles the second `TRANSFER` handshake before the
normal `accepts-transfers` rejection. It resolves the first static `try` server
and installs a small Login handler.

The Login handler accepts one `ServerLoginPacket`. This packet boundary is
safe because a normal client waits for Server Hello before it sends another
Login packet.

This boundary is required. Velocity's frame decoder removes the packet length
before the session handler sees Login Start. Decoding and writing this one
packet preserves a complete boundary before the pipeline becomes a raw tunnel.

The handler then performs these actions on the channel event loops:

1. Pause frontend reads.
2. Open one raw Netty connection to the fixed target.
3. Write a standard handshake with only the intent changed to `LOGIN`.
4. Write the received Login Start fields.
5. Flush both writes.
6. Remove Minecraft framing and packet codecs from both raw legs.
7. Install bidirectional byte-forwarding handlers.
8. Resume reads.

Pause target reads until the bridge is ready. This prevents an early target
Server Hello from entering an incomplete frontend pipeline.

The raw bridge writes each accepted inbound `ByteBuf` directly to its peer. A
`ChannelInboundHandlerAdapter` owns that reference, and the peer write becomes
its next owner. No source code reads the buffer after the write, so a retained
duplicate would add an object and reference-count operations without preserving
useful ownership. Rejected input is still released locally. The bridge observes
channel writability for backpressure and closes the peer when one channel closes
or a write fails.

The backend packet relay does not add a separate phase enum. One `TargetHello`
record owns the target public key and challenge together. The response-write
future owns the later plaintext write. The active handler, cipher pipeline,
channel auto-read state, and connection result future validate each handoff.

The target key, challenge, and acknowledgement are public protocol metadata.
Their lifecycle ends when the owning record or field is cleared; their arrays do
not need explicit wiping. AES `K` and every cross-thread copy remain explicitly
zeroed on all completion and failure paths.

### Failure Boundary

Before the raw handoff, use a normal Login disconnect with a stable message.
Log the complete exception in the owning catch path.

After the raw handoff, do not inject a Velocity packet. Close both channels and
let the client or target show the connection loss.

## Mod Boundaries

### Envelope Decode Result

`ServerHelloPacketEnvelope.decodeTargetPublicKey` returns
`Optional<PublicKey>`. A present value contains the decoded target public key.
An empty value means that the Mod cannot use the proxy protocol.

Both an unmarked key and malformed envelope data return an empty value. The
caller keeps Minecraft's original login path for both cases. The decoder does
not expose separate passthrough and invalid result types.

Expected malformed-input exceptions return an empty value. If later diagnosis
is useful, the owning boundary logs the complete `Throwable`. The exception
does not create a third protocol result.

### Mixin State Owner

Do not add `FakePlayerProxyConsentSession`. The
`ClientHandshakePacketListenerImpl` Mixin already owns the login connection and
navigation. It also creates the prepared login and the screen callbacks.

The prepared login is one immutable value. It contains the two digest and key
response choices, both ciphers, and the authentication flag. It is not a state
machine. It does not retain the raw `SecretKey` after both responses exist.

The screen callbacks capture the prepared login. The Mixin does not store the
Hello packet, a resume token, relay arguments, or a duplicate-Hello flag.
Vanilla's `AUTHORIZING` state rejects a second Hello while the screen is open.

### Key Response Boundary

Use one cancellable injection in
`MixinClientHandshakePacketListenerImpl`. Place it immediately before Vanilla
constructs `ServerboundKeyPacket`.

At this point, Vanilla has these live values:

- the generated `SecretKey`;
- the proxy public key and original challenge;
- the proxy digest;
- the decrypt and encrypt ciphers;
- the `shouldAuthenticate` flag.

The injection decodes the target public key:

1. Return to Vanilla when the decoder returns an empty value.
2. Create the prepared login when the decoder returns a target public key.
3. Cancel before Vanilla starts authentication or sends the key response.
4. Schedule the consent screen on the game thread.

The Vanilla choice keeps the proxy digest. Its key response uses `K`, the proxy
public key, and the original challenge.

The relay choice computes the target digest. Its key response uses the same
`K`, the proxy public key, and the `FPPACK` challenge.

The continuation selects one choice. It calls the existing
`authenticateServer` and `setEncryption` helpers. It does not repeat key
generation, cipher creation, session authentication, or encryption setup.

This exact-version local boundary is valid for Minecraft 26.2. It removes the
HEAD hook, method re-entry, two argument hooks, packet Mixin, and listener
bridge.

### Screen Owner

Put `FakePlayerProxyConsentScreen` under `com.fakeplayerproxy.mod.gui` and base
it on `ConfirmScreen`. Follow Minecraft's callback pattern instead of adding a
session wrapper. Its constructor receives:

```java
FakePlayerProxyConsentScreen(
    ConnectScreen connectionScreen,
    String connectionAddress,
    BooleanConsumer resultConsumer,
    Runnable onCancel)
```

The inherited `BooleanConsumer` handles Allow and Decline. The separate
`Runnable` lets Escape cancel the connection instead of behaving like Decline.
The screen stores only the temporarily replaced `ConnectScreen`, the cancel
action, and the warning component. It uses the address only while it constructs
the body. It does not own `Connection`, the multiplayer parent, packets, keys,
acknowledgements, or any protocol state.

Keeping the replaced `ConnectScreen` only for `tick()` follows Minecraft's own
connection-warning screens. It lets the normal connection tick, timeout, and
disconnect handling continue while consent is visible. The Mixin still owns
navigation and all protocol actions.

Use the native `ConfirmScreen` button construction and callback. Do not
override `addButtons`. Override only the behavior needed for additional warning
text, Decline initial focus, narration, connection ticking, and the distinct
Escape action.

### Approved Consent Screen

Use the standard full-screen Minecraft menu background. Keep the native
`ConfirmScreen` centered vertical layout. Do not add a custom panel, icon,
checkbox, countdown, or remembered decision.

Show these elements:

1. The title.
2. The proxy permission and its effect.
3. The current connection address.
4. The access-token boundary.
5. The Vanilla fallback used after rejection.
6. A red, bold trust warning.
7. One Allow button and one Decline button.

Use five translation keys backed by `zh_cn.json` and `en_us.json`:
`title`, one `body` with the connection-address placeholder, `warning`, `allow`,
and `decline`. Do not split the body into per-sentence keys or add Java constants
that only wrap these keys. Render the body as one continuous widget and let the
widget wrap it naturally. Keep the approved sentences and their visible order.

Two non-consent messages use separate keys because they are also user-visible:

- `fakeplayerproxy.disconnect.proxy_connection_failed`: `Unable to continue the
  proxy connection. Please try again.` / `无法继续代理连接，请重试。`
- `fakeplayerproxy.message.encryption_verified`: `[FakePlayerProxy] AES
  encryption/decryption verified.` / `[FakePlayerProxy] AES 加密/解密验证成功。`

These do not split the five consent-screen values. Do not add another key for a
log message or for each validation branch.

Chinese strings:

```text
Title: 允许代理连接？
Body: 该服务器请求代理你的连接。允许后，服务器可以解密、查看和修改本次连接中传输的游戏数据。服务器地址：%s。你的 Minecraft 访问令牌不会发送给服务器。拒绝后，客户端将使用标准 Minecraft 加密，服务器仅透明转发后续加密的 TCP 流量。
Warning: 请仅在信任的服务器上允许代理！
Allow: 允许
Decline: 拒绝
```

English strings:

```text
Title: Allow proxy connection?
Body: This server is requesting permission to proxy your connection. If allowed, the server can decrypt, inspect, and modify game data sent over this connection. Server address: %s. Your Minecraft access token is not sent to the server. If you decline, the client will use standard Minecraft encryption and the server will only transparently forward the encrypted TCP traffic.
Warning: Only allow proxying on servers you trust!
Allow: Allow
Decline: Decline
```

Put the current connection address directly into the localized body placeholder.

Render the warning text in red and bold. Do not add brackets or a style label.
Place Allow on the left and Decline on the right. This order matches the native
confirmation layout. Give Decline the initial keyboard focus. Enable both
actions immediately. Do not add a countdown.

Narration uses the visible content order. It reads the title, permission effect,
address, token boundary, fallback, warning, and focused action. Do not add a
custom panel or a second action.

### Screen Actions

The screen passes Allow or Decline to the Mixin through one `BooleanConsumer`.
The Mixin restores the replaced `ConnectScreen`, verifies that the current
connection is valid, and selects one choice from the prepared login.

Allow selects the target digest and the `FPPACK` key response. Decline selects
the proxy digest and the original-challenge key response.

The selected choice follows Minecraft's existing authentication branch. The
existing encryption helper sends one response and installs the prepared
ciphers.

Decline sends `RSA_proxy(K1)` in the standard response. It sends no `FPPACK` and
does not use the target key for the digest. Velocity classifies the original
challenge as Vanilla and continues through Transfer.

Escape invokes the separate cancel callback owned by the Mixin. The callback
disconnects with Minecraft's existing aborted-connection component and restores
the multiplayer parent. It starts no authentication and sends no key response.

If the remote endpoint closes, call the normal connection disconnection
handler. Do not replace a native timeout or authentication error with a Mod
message.

### Cleanup

The prepared login exists only in the screen callbacks. A selection replaces
the screen and completes one continuation. Escape or disconnect makes the
callbacks unreachable.

The prepared responses contain RSA ciphertext, not a raw AES byte array. Drop
their references after the callback. Do not add a separate cleanup state.

Failures that stop the login log one stable message with the complete
`Throwable` object. They disconnect with a stable user-facing component.
Malformed envelope data does not stop the login. Do not add a null-specific
logging branch, `Objects.requireNonNull`, an assertion, or a deliberately thrown
validation exception.

## Confirmed Review Corrections

### Mixin Metadata And Value Shape

`connection`, `parent`, and `serverData` are final Minecraft fields without an
equivalent public owner, so their shadows use `@Shadow @Final`. Do not shadow the
Minecraft singleton; use `Minecraft.getInstance()`. The shadowed encryption
method keeps the target parameter name `setKeyPacket`.

Capture the six target locals by name: `decryptCipher`, `encryptCipher`,
`digest`, `secretKey`, `publicKey`, and `challenge`. Do not use an ordinal while
these names are available. Do not add `@NotNull` to shadows, injector inputs,
captured locals, or override inputs whose target contract does not require it.
Minecraft's screen package is `@NullMarked`, so the consent screen's override
must spell its inherited non-null return as `public @NotNull Component` after it
moves into the unmarked project package. Project-owned constructors, records,
and helpers may retain an annotation when every owned caller guarantees the
value.

`LoginChoice` adds no behavior beyond carrying a digest and key packet, so remove
that dedicated record and apply the shared Java multiple-value guideline. Keep
`PreparedLogin`: it is the immutable owner that carries both choices, both
ciphers, and the authentication flag across the UI callback.

Keep one callback-boundary method named `continueLoginAfterConsent` and inline
the one-use authentication/encryption continuation into it. Keep
`openConsentScreen` because it is the substantial game-thread and screen-owner
boundary. Keep the injector name `prepareConsentChoices`; it states what the
handler does without a manual Mixin prefix.

### Failure Ownership

Each validation site logs its own concrete condition, including the failed
protocol field or lifecycle owner and safe observed bounds. Each catch site logs
the complete `Throwable`. A reused disconnect helper contains only the localized
disconnect action; it does not accept a nullable exception or a vague message.

In the Velocity patch, the same rule applies to `LoginSessionHandler`,
`InitialLoginSessionHandler`, and `TransferTunnelLoginSessionHandler`. Reuse
`ConnectionMessages.INTERNAL_SERVER_CONNECTION_ERROR` for the player-visible
patch failure. Do not keep hard-coded `RELAY_FAILURE` or `TUNNEL_FAILURE`
components.

`LoginSessionHandler` always closes the backend while it owns a relay failure;
an already completed result future only makes future completion idempotent. The
failure helper must not return before closing that backend.

### Existing Velocity Lifecycles

Join successful `PostLoginEvent` completion and the first settings packet with
`thenAcceptBothAsync` on the frontend event loop because a Plugin can complete
`PostLoginEvent` from another thread.

Narrow `RegisteredServer` to `VelocityRegisteredServer` at the caller with a
pattern match and the existing stable connection failure. The relay connection
method takes the concrete type; it does not throw `IllegalArgumentException` for
an ordinary unsupported implementation.

Use an enum singleton for stateless `AuthSessionHandler.InitialServer`. Keep the
sealed `Continuation`, `TargetHello`, and `ResponseKind` types because their
variants own different actions or protocol outcomes. Inline only the confirmed
one-use wrappers `startInitialServerSelection`, `removeFrontendCodecs`, and
`removeTargetCodecs`; retain reused helpers and substantial ownership
boundaries.

`RawTunnelForwardHandler` keeps direct `ByteBuf` transfer. The enclosing
`MinecraftConnection.channelRead` already releases an unknown buffer after
`handleUnknown`, so the pre-handoff handler must not release that same buffer a
second time.

## Compatibility

| Client and endpoint | Result |
| --- | --- |
| Vanilla 26.2 to patched Velocity | Wait four seconds after PostLogin, Transfer to the same endpoint, then raw tunnel to the fixed target |
| Modded 26.2 to patched Velocity, accepted | Current target-authenticated packet proxy |
| Modded 26.2 to patched Velocity, declined | Vanilla response, four-second wait, Transfer, then raw tunnel |
| Modded 26.2 consent screen, Escape | Close the current connection and return to multiplayer |
| Modded 26.2 to an unpatched server | Original Minecraft login |
| Normal Login or Status to Velocity | Existing Velocity path |

## Patch Surface

Change only these files and owners:

- `MixinClientHandshakePacketListenerImpl` for the single key-response-boundary
  injection, the prepared login, navigation, and the selected continuation.
- `com.fakeplayerproxy.mod.gui.FakePlayerProxyConsentScreen` for the approved UI,
  native result callback, connection-screen tick, and distinct Escape callback.
- `zh_cn.json` and `en_us.json` for the five approved translation keys.
- The same two language files for the one localized disconnect and one localized
  proof message.
- `InitialLoginSessionHandler` for direct Vanilla, Mod, and invalid branches.
- `AuthSessionHandler` for the exhaustive initial-server, relay, and Transfer
  continuations.
- `HandshakeSessionHandler` for the `TRANSFER` raw entry.
- One raw Login/bootstrap handler and one small byte-forwarding handler.
- Existing production classification tests and focused raw-handoff tests. Do
  not add a mapper-only destination test.

The modernization revision may also change the existing patch hunks for
`LoginSessionHandler`, `ClientConfigSessionHandler`, `ConnectedPlayer`, and
`FakePlayerProxyRelay`. `plugin/src` may change only the existing
`ServerPostConnectEvent` proof component from literal text to its translation
key. It must not change Plugin automation behavior, configuration, packet IDs,
or the protocol flow.

## Modern Java Use

- The Mod uses Java 25 records, `Optional`, and unnamed ignored variables. The
  Velocity patch uses Java 21 sealed types and pattern switches. It does not use
  preview syntax.
- `HexFormat` owns the RSA OID literal, range-based `Arrays.equals` owns prefix
  and acknowledgement comparison, and `Math.ceilDiv` owns byte-count rounding.
- The first settings packet and successful PostLogin completion use
  `thenAcceptBothAsync` with the frontend event loop. Other callbacks that touch
  a connection also use the owning event loop explicitly.
- The Transfer target list checks emptiness and then uses `getFirst`; it does not
  allocate a stream pipeline to read one configured element.
- Listener lambdas rely on target-type inference. Local `var` is limited to
  initializers whose method or constructor already states the type.
- Byte-array output streams are pre-sized only where the encoded upper bound is
  already present in the method. Do not add a sizing abstraction for one call.

Apart from translating the existing proof message, do not change the Plugin,
Velocity configuration schema, packet IDs, or target server.

## Tradeoffs

- The same listener removes deployment configuration. It requires a precise
  Login Start handoff boundary.
- Reconnecting the target creates a second short target TCP connection for
  Vanilla. It avoids pending-backend pairing and route state.
- The fixed four-second wait covers Paper's default connection throttle. It
  cannot discover a larger remote value, and an early rejected retry restarts
  the remote window.
- The raw branch preserves target authentication. It gives up all Velocity
  packet features after handoff.
- The local-capture injection depends on the exact Minecraft 26.2 method shape.
  It keeps `handleHello` single-entry and removes the separate bridge and state.
- The selected continuation repeats only Vanilla's short branch around its
  existing authentication and encryption helpers.

## Rollback

The Vanilla branch can return to the encrypted Mod-required rejection without
changing the Mod relay. The raw entry is isolated behind `TRANSFER` intent.

The consent correction changes one Mixin and the existing screen. Revert these
Mod changes together if the local boundary fails. No data or configuration
migration is required.
