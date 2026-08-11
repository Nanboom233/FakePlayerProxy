# Vanilla Transfer Fallback And Mod Consent

## Goal

Support Vanilla and Modded Minecraft 26.2 clients through one patched Velocity
gateway.

A Vanilla client must complete the first login, reconnect through Transfer, and
reach one fixed online-mode target through a raw tunnel. An accepted Modded
client must keep the current packet-proxy relay. The Mod may let Minecraft
generate the AES secret `K`, ciphers, and Vanilla digest before consent. It must
ask before target session authentication or any key packet is sent.

## Confirmed Evidence

- An unmodified client cannot process two Server Hello packets on one login
  connection. The login state guard fails before a second AES key exists.
- Transfer creates a new connection, a new login listener, and a new packet
  pipeline. The second listener starts in `CONNECTING`.
- Minecraft accepts Transfer in Configuration and Play. It does not accept
  Transfer in Login.
- A transferred client sends handshake intent `TRANSFER`. An unchanged target
  rejects this intent unless the tunnel changes it to `LOGIN`.
- A raw tunnel cannot inspect packets after target encryption starts. The client
  and target alone know the second AES key.
- Immediately before Vanilla constructs `ServerboundKeyPacket`, its
  `handleHello` method has already entered `AUTHORIZING` and prepared `K`, both
  ciphers, the proxy digest, proxy public key, and original challenge. It has not
  called the session service or sent a packet. This is the consent boundary.
- Because Vanilla enters `AUTHORIZING` before this boundary, its existing state
  guard rejects a second Server Hello. The Mod needs no duplicate-Hello state.
- Paper's default `settings.connection-throttle` is `4000` milliseconds and is
  keyed by the target-visible source IP. The provisional and raw target
  connections therefore share one throttle window in the current deployment.
- A rejected early retry refreshes Paper's timestamp. Closing the provisional
  backend does not clear it.
- The reports under `research/` contain the source, bytecode, and runtime
  evidence for these results.

## Requirements

### R1: Keep One Protocol Split

- Keep the current decorated Server Hello carrier and response format.
- Treat the exact unchanged target challenge response as Vanilla.
- Treat the exact `FPPACK` response as Mod support.
- Let Velocity reject malformed key responses with its existing stable error
  path.
- Do not add a custom capability packet or a second Server Hello.

### R2: Ask Before Authentication Or The Key Response

- Let Vanilla enter `handleHello` once and generate `K`, ciphers, and the
  Vanilla digest. Intercept immediately before the standard key packet is
  constructed and before the authentication/send branch begins.
- Do not call `handleHello` again. Do not add a resume packet, packet-dispatch
  Mixin, listener bridge, or duplicate-Hello state.
- Show one consent screen on the game thread.
- Use localized `zh_cn` and `en_us` text for the approved title, explanation,
  fallback, access-token notice, trust warning, and buttons.
- Show the current connection address directly as `服务器地址：%s` /
  `Server address: %s`.
- State that the Minecraft access token is not sent to the server.
- Place `允许` / `Allow` on the left and `拒绝` / `Decline` on the right. Give
  the decline button initial keyboard focus and keep both buttons immediately
  active.
- Render the trust warning in red and bold. Narrate the screen in the same
  semantic order in which its content is shown.
- Prepare a Vanilla choice from the already computed proxy digest, original
  challenge, proxy key, and `K`.
- Prepare a relay choice from the target digest, `FPPACK` challenge, the same
  proxy key, and the same `K`.
- Let acceptance continue with the relay choice. Let rejection continue with
  the Vanilla choice.
- A rejected request must use the proxy key for the digest and the original
  challenge for the standard key response. It must not send `FPPACK`.
- The rejected Vanilla response still sends `RSA_proxy(K1)`. This standard
  response is required to encrypt the short frontend login before Transfer.
- Let Velocity classify the rejected request as Vanilla and continue through
  the Transfer raw-tunnel branch.
- Let Escape cancel the connection without authenticating a session or sending
  a key response. Return directly to the multiplayer screen.
- If the connection closes while consent is open, discard the prepared login.
  Do not authenticate or send either prepared response.
- Let the consent screen tick the connection while it is visible.
- Use existing connection timeouts. Do not add a second consent timer.
- If envelope decoding returns no target public key, keep Minecraft's original
  login path. This rule covers unmarked and malformed envelope data.

### R3: Transfer A Vanilla Client

- After Vanilla classification, enable the first frontend AES stream and close
  the provisional backend connection.
- Use Velocity's existing login handlers to send Login Success and enter
  Configuration.
- Use the provisional login identity only for this short transfer session. The
  fixed online target remains authoritative for the final session.
- After Login Acknowledged and successful `PostLoginEvent` handling, wait a full
  four seconds before sending Transfer to the same public gateway address. The
  provisional backend is already closed before this wait begins.
- Schedule the delay on the frontend connection's event loop. Do not block a
  thread, add timer state, or send Transfer after the frontend has closed.
- Do not run normal initial-server selection for this transfer session.
- Keep four seconds fixed for this task. Do not add another gateway
  authentication policy or configuration option.

### R4: Use The Existing Velocity Listener As The Raw Tunnel

- Let the Velocity Patch own the raw tunnel and its lifecycle.
- Use the existing public listener. Do not bind another port.
- Route a handshake with intent `TRANSFER` into the fixed raw path.
- Resolve only the first server in Velocity's static `try` list.
- Accept one standard Login Start packet before the raw handoff.
- Send the target a replacement handshake with intent `LOGIN`.
- Preserve the handshake protocol version, host, and port.
- Send the unchanged Login Start fields after the replacement handshake.
- After these two writes, forward all later bytes in both directions without
  packet decoding, AES handling, compression handling, or packet injection.
- Close both channels when either channel closes or the bootstrap fails.
- Use the existing connection timeout, read timeout, and Netty backpressure
  behavior.
- Never accept a client-selected target address.

### R5: Keep The Current Modded Relay

- After consent, keep the existing target authentication and AES reuse flow.
- Keep Velocity as the packet proxy for the accepted Modded client.
- Keep ordinary Mod connections to unpatched servers unchanged.

### R6: Keep The Change Small

- Store all Velocity changes only in `plugin/patch/0001-server-hello-marker.patch`.
- Apply the patch only to the disposable source under `plugin/build/server/`.
- Do not change the Plugin for the raw tunnel.
- Put `FakePlayerProxyConsentScreen` under `com.fakeplayerproxy.mod.gui` and do
  not add a consent-session wrapper.
- Let the login-listener Mixin create one immutable prepared login. Capture it
  in the screen callbacks with the connection and multiplayer parent actions.
- Use only `MixinClientHandshakePacketListenerImpl`. Do not add a packet Mixin,
  bridge interface, consent-session wrapper, or persistent packet field.
- The prepared login groups both digest and key-response choices, both ciphers,
  and the authentication flag. It is not a protocol state machine.
- Let the screen store only presentation, the replaced `ConnectScreen` needed
  for ticking, and callback references.
- Do not add a consent state enum or wrapper methods around screen display and
  connection address.
- Use descriptive injector handler names without a manual
  `fakePlayerProxy$` prefix. Injector handlers rely on Mixin's implicit
  uniqueness; only ordinary Mixin fields and helper methods use `@Unique`.
- Reuse Velocity's handshake, login, Transfer, bootstrap, timeout, and channel
  APIs where they fit.
- Add only state and helpers required for consent or raw channel ownership.
- Add comments that explain the complete flow, ownership, packet boundary,
  cipher boundary, and cleanup reason.
- Log diagnostic exceptions with the complete `Throwable`.
- Log validation failures with the exact failed operation, field, or lifecycle
  condition. Do not pass `null` through an exception parameter to represent a
  non-exception failure.
- Show stable localized user-facing errors without internal exception text.

### R7: Modernize The Owned Code Without Changing The Protocol

- Keep the packet format, consent choices, target selection, cipher ordering,
  Transfer behavior, and raw-tunnel behavior unchanged.
- Use Java 25 features in the Mod and Java 21 features in the Velocity patch
  only when they remove invalid states, repeated control flow, allocation, or
  reference-count work.
- Return the decoded target public key as `Optional<PublicKey>`. Return an empty
  value for unmarked or malformed envelope data. Do not expose separate
  passthrough and invalid result types.
- Represent the three `AuthSessionHandler` construction modes with exhaustive
  variants rather than independent nullable fields and a boolean. These variants
  select the continuation after login; they are not protocol phases.
- Group the target public key and challenge under one pending-Hello value. Keep
  the existing response future as the later plaintext-write owner; do not add a
  relay phase enum.
- Let Netty writes take ownership of accepted raw `ByteBuf` values directly.
  Do not create a duplicate buffer when the source handler no longer needs the
  original reference.
- Use standard-library range comparison, hexadecimal decoding, ceiling division,
  sequenced-collection access, and `CompletableFuture` combinators where they
  directly replace hand-written logic.
- Zero only AES secret material and its copies. Public keys, public challenges,
  acknowledgements, and decrypted response classification data are public
  protocol values; clear their owner references without redundant array wiping.
- Apply local type inference only when the initializer makes the type clear.
  Do not reformat or modernize unchanged upstream Velocity code.

### R8: Apply The Confirmed Review Corrections

- Make Mixin declarations match the Minecraft target metadata. A shadow of a
  final target field uses `@Final`; a shadow method keeps the target parameter
  name; and `@Local` captures use the available target local-variable names.
- Do not add project nullability contracts to foreign shadows, injector inputs,
  local captures, or override inputs whose target contract does not require
  them. An override must preserve the target's effective annotations, including
  package defaults such as `@NullMarked`; spell that inherited contract
  explicitly when the project package has a different default.
- Remove the redundant two-value login-choice class and the one-use login
  continuation split. Keep the immutable prepared-login value because it owns
  both choices and the shared ciphers across the screen callback.
- Keep meaningful protocol and lifecycle types whose variants have different
  behavior. Do not replace `ResponseKind`, `TargetHello`, or the exhaustive
  login continuation solely to reduce the type count.
- Run connection-mutating asynchronous continuations on the connection's event
  loop. A failure path must close its owned connection even when a result future
  has already completed.
- Use existing translatable Velocity errors and Mod language resources for all
  task-owned user-visible messages. Do not migrate unrelated existing Plugin
  command text in this task.
- Keep all Velocity corrections in the stored patch. The disposable applied
  source is validation output, not a second source of truth.

## Acceptance Criteria

- [ ] A Vanilla 26.2 client returns the unchanged challenge and is not rejected
      for missing the Mod.
- [ ] The first Vanilla connection reaches Configuration and receives one
      Transfer packet for the same gateway address.
- [ ] Velocity waits a full four seconds after successful PostLogin handling
      before it sends Transfer; the provisional backend was closed before this
      delay, so the second target handshake cannot enter Paper's default
      4000-millisecond throttle window.
- [ ] The second connection uses intent `TRANSFER`, while the target receives
      intent `LOGIN` with the other handshake fields unchanged.
- [ ] The target receives the same Login Start fields as the second client sent.
- [ ] The target Server Hello and every later byte cross the raw tunnel without
      packet reconstruction.
- [ ] The Vanilla client completes target online authentication and reaches Play.
- [ ] The raw tunnel connects only to the first static `try` target.
- [ ] A normal Login or Status connection does not enter the raw tunnel.
- [ ] A valid Mod carrier shows one consent screen before any key response.
- [ ] The consent screen uses the approved localized copy, shows the current
      connection address and access-token notice, renders the trust warning in red and
      bold, places Allow before Decline, initially focuses Decline, and exposes
      the same content order to narration.
- [ ] Accepting once sends one normal key response and keeps the current Modded
      packet-proxy flow.
- [ ] Rejecting sends one standard Vanilla response with the original challenge
      and no `FPPACK`.
- [ ] A rejected Mod request reaches the same Transfer raw tunnel as a Vanilla
      client.
- [ ] Pressing Escape sends no key response, closes the current connection, and
      returns directly to the multiplayer screen.
- [ ] The Mod still connects normally to an unpatched server.
- [ ] Existing automated tests execute production envelope, relay, and tunnel
      logic. They do not use source-text checks, private-field reflection, or
      constant-only checks.
- [ ] Envelope decoding returns a target public key or an empty value. An empty
      value keeps the original Minecraft login path.
- [ ] Velocity login-continuation branches are exhaustive at compile time and
      do not permit the previous partial nullable combinations.
- [ ] Raw forwarding transfers the received buffer directly and preserves the
      existing byte-for-byte and close-propagation behavior.
- [ ] Only AES secret arrays are explicitly zeroed; public relay metadata is
      released by dropping its owning reference.
- [ ] The reviewed Mixin bindings match the target fields, method parameter, and
      named local variables without adding foreign nullability contracts.
- [ ] Task-owned disconnect and proof messages are localized, while validation
      logs identify the exact failed protocol or lifecycle condition.
- [ ] Relay and tunnel failure paths close the connection they still own and do
      not use a nullable exception argument for ordinary validation failures.
- [ ] The stored patch applies to the pinned Velocity commit and builds through
      `:plugin:releaseJar`.

## Out Of Scope

- Two Server Hello packets on one connection
- More than one raw-tunnel target
- Route tokens, cookies, or client-selected destinations
- Retaining or pairing the first provisional backend
- A second listener or a new tunnel port
- Direct Transfer to a target that enables `accepts-transfers`
- Packet inspection, commands, proof injection, or server switching after the
  Vanilla raw handoff
- New proxy tasks, reconnect automation, or target-session retention
- A local consent deadline
- Support for another Minecraft version
- Broad Velocity refactoring or broad test review
- Modernizing untouched upstream Velocity code outside the stored patch hunks
- UI, reflection, source-shape, field-layout, or abstraction-only tests
- Migrating pre-existing automation command or configuration messages to new
  translation keys
- Discovering or configuring a target server's connection-throttle value
- Automatic retry after a target connection-throttle rejection

## Risks And Deferred Work

- The client performs sequential gateway and target session joins. A real
  account test must verify the complete sequence.
- The tunnel handoff must not lose bytes that arrive with the second Login Start.
  A focused production-path test must cover this boundary.
- A failed second connection cannot return to the first connection because
  Transfer already closed it.
- The raw tunnel loses Velocity packet limits and packet policy after handoff.
- Intent `TRANSFER` is the only raw-path selector. A client can invoke this path
  directly, but the path can connect only to the fixed configured target.
- Four seconds covers Paper's default throttle. A target configured above 4000
  milliseconds can still reject the second connection; deployment coordination
  or disabling the target throttle is deferred.
- A manual attempt made before the target window expires refreshes Paper's
  timestamp and restarts the full wait.
- The post-key-generation consent continuation still needs a real Minecraft
  26.2 client test.
