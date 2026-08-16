# Fabric Client Mod Contract

## 1. Scope / Trigger

- Scope: `mod/src/main/**` and focused tests under `mod/src/test/**`.
- Trigger: a Minecraft 26.2 client receives a modified Server Hello that declares
  FakePlayerProxy support.
- The mod is client-only, uses Java 25 and Fabric Loader 0.19.3, and has no
  Fabric API dependency or mod entrypoint.
- Prefer newer Java language features and standard-library APIs when they make
  the code more concise, readable, or performant.
- Connections to servers without the FakePlayerProxy Server Hello extension must
  retain Minecraft's original login behavior.

## 2. Structure

- Shared logger owner: `com.fakeplayerproxy.mod.FakePlayerProxyMod`; it has no
  connection state or Fabric entrypoint.
- Packet helpers belong under `com.fakeplayerproxy.mod.packets`.
- Client screens belong under `com.fakeplayerproxy.mod.gui`.
- Minecraft hooks belong under `com.fakeplayerproxy.mod.mixins` and every hook
  must be declared in `fakeplayerproxy-mod.mixins.json`.
- Use Sponge Mixin for Minecraft integration. Do not introduce Fabric API merely
  to register packets or listeners.
- The primary hook is the Minecraft 26.2
  `ClientHandshakePacketListenerImpl.handleHello` path immediately before
  Minecraft constructs `ServerboundKeyPacket`. Use MixinExtras `@Local` to read
  the AES key, ciphers, digest, proxy key, and challenge already prepared by
  Minecraft. Packet-envelope helpers remain under `packets`; hooks remain under
  `mixins`.
- Do not add custom-payload codec Mixins for this login relay. The selected
  protocol uses only `ClientboundHelloPacket` and `ServerboundKeyPacket`.

## 3. Contracts

- Minecraft generates the 128-bit AES secret `K` through its ordinary login
  path. The Mod must not replace that key generator or send `K` in a separate
  FakePlayerProxy packet.
- A supported Server Hello keeps the target server ID, challenge, and
  `shouldAuthenticate` unchanged. Its public-key field is a JCA-parseable proxy
  RSA-1024 SPKI whose non-NULL `rsaEncryption` AlgorithmIdentifier parameter is
  an OCTET STRING containing
  `FPPMOD || 0x01 || VarInt(targetKeyLength) || targetSPKI`.
- Detect support only after the decorated key parses successfully and its
  envelope magic, version, length, and target key are valid. A normal RSA SPKI
  from an unpatched server leaves every Vanilla argument and action unchanged.
- `ServerHelloPacketEnvelope.decodeTargetPublicKey(PublicKey)` returns
  `Optional<PublicKey>`. A present value is the decoded target key. An empty
  value covers an ordinary key, malformed carrier data, or unavailable foreign
  input and keeps Minecraft's original login path.
- Let `handleHello` run once. Do not add HEAD re-entry, a resume packet, a packet
  Mixin, a bridge, persistent Hello fields, or a consent state machine.
- At the injection boundary, Minecraft has already created `K`, both ciphers,
  the proxy digest, the proxy key, and the original challenge. It has not called
  the session service or sent the key response.
- For a supported carrier, create one immutable prepared login. It contains a
  Vanilla choice, a relay choice, both ciphers, and the authentication flag.
  Capture it in the screen callbacks. Do not store this one-shot value on the
  Mixin or in a consent-session wrapper.
- A consent screen uses Minecraft's native callback pattern. It stores only the
  replaced `ConnectScreen` needed to keep connection ticks running, its separate
  Escape callback, and presentation components. It must not own `Connection`,
  the multiplayer parent, packets, keys, acknowledgements, or cryptographic
  state.
- Injector handler methods use descriptive names without a manual
  `fakePlayerProxy$` prefix. Mixin makes injector handlers implicitly unique, so
  do not annotate those handlers with `@Unique`. Ordinary Mixin fields and
  helper methods still use `@Unique` when needed.
- Cancel the remaining Vanilla method only after both prepared choices exist.
  Show the consent screen on the game thread before authentication or key send.
- Allow selects the target digest and the acknowledged response. Decline selects
  the already computed proxy digest and the response with the original
  challenge. Both responses are standard `ServerboundKeyPacket` values that
  encrypt the same client-generated `K` with the proxy RSA key.
- After a choice, use Minecraft's existing authentication and encryption
  helpers. Call the session service only when `shouldAuthenticate` is true.
  Allow authenticates with the target digest. Decline authenticates with the
  proxy digest. Do not reset a cipher or generate another AES key.
- Escape disconnects without authentication or key send and returns to the
  multiplayer parent. If the connection closes while consent is visible,
  discard the prepared login and do not continue either choice.
- The relay key exists so Velocity can decrypt and proxy both protected streams.
  It is not an authentication credential or a substitute for Mojang
  authentication.
- Protocol feasibility is verified for Minecraft 1.20-26.2 with compatible
  SunRsaSign runtimes, but this module remains a Minecraft 26.2 / Java 25 build.
  Do not claim one compiled Fabric jar supports earlier binary families.
- The selected protocol supports the initial configured online-mode backend
  only. It does not add an in-session custom-payload negotiation for later
  online-backend switching.
- Never persist or log AES secrets, access tokens, or profile credentials. Clear
  temporary secret state when its owning connection ends.
- Do not add a custom Minecraft version check; Fabric Loader owns incompatible
  version rejection.
- Comments are part of the implementation contract. They are not a formatting
  requirement.
- Comments must give a continuous explanation across the changed classes. A
  reader must understand the complete Server Hello and key-response flow.
- Each class comment explains its role, its input, its result, and its next owner.
  It also explains why the design uses standard Minecraft packets.
- Each Mixin hook comment explains the injection point, the changed argument, the
  reason for the change, and the behavior for an ordinary server.
- Inline comments explain protocol decisions, state ownership, cryptographic
  ordering, and failure handling. They state the reason and the benefit.
- Do not add comments that only restate a Java statement. Do not omit comments
  when a reader would need to reconstruct the design from several hooks.
- Write comments in ASD-STE100 Simplified Technical English. STE controls the
  wording. It does not replace the technical explanation.
- A provider-specific or version-specific comment can cite the applicable file
  under `.trellis/tasks/06-12-fake-player-proxy-research/research/`. The local
  comment still states the result that the code depends on.
- Follow the annotation-ownership rules in `../language/java.md`. Use
  `@NotNull` for declarations and inputs that this project owns and can
  guarantee, and to preserve an effective non-null foreign override contract.
  Do not add it to `@Shadow` fields, injector parameters, `@Local` captures, or
  Minecraft override inputs whose target contract does not require it. Check
  package defaults such as `@NullMarked`, not only the target method text.
- For a project-owned primitive-array reference, place a Java type-use
  annotation on the array dimension, as in `byte @NotNull []`.
- Do not use `Objects.requireNonNull`, Java `assert`, state assertions, or
  deliberately thrown validation exceptions in client mod code. A failure that
  escapes a Mixin or codec boundary can terminate the whole game client.
- Represent invalid packet input as a failure value when the selected packet
  design provides one. Do not throw deliberately from a Mixin or packet boundary
  merely to validate input.
- Exceptions required by Minecraft APIs may propagate only into an existing
  vanilla/framework catch boundary. Mod-owned asynchronous, packet, and
  cryptographic work catches expected exceptions at its owning boundary and
  disconnects cleanly when continuation is unsafe.
- Decide from the local catch semantics whether an exception needs handling or
  later diagnostics. An expected exception whose outcome is fully handled and
  needs no diagnostic record may be intentionally ignored; an `ignored`
  catch variable is valid in that case.
- When a caught exception represents a failure that needs later debugging, pass
  the `Throwable` object to the existing logger so the complete stack trace and
  cause chain are retained instead of recording only `exception.getMessage()`.
- When a failure is reported to the player, use a concise, user-friendly
  `Component`. Keep diagnostic details in the log rather than using raw
  exception text as the player-facing message.

## 4. Validation & Error Matrix

| Condition | Result |
| --- | --- |
| Server Hello has an ordinary RSA SPKI | Preserve vanilla login behavior and do not activate FakePlayerProxy state |
| Decorated SPKI is malformed, unsupported, or contains no usable target key | Return an empty decode result and keep Minecraft's original login path |
| Supported Hello and Allow with `shouldAuthenticate == true` | Run one target-digest session join and send the acknowledged standard response |
| Supported Hello and Allow with `shouldAuthenticate == false` | Run no session join and send the acknowledged standard response |
| Supported Hello and Decline | Use the proxy digest and send the original Vanilla response without `FPPACK` |
| Escape while consent is visible | Disconnect without authentication or key send and return to the multiplayer parent |
| Connection closes while consent is visible | Discard the prepared login and do not continue either choice |
| `ACK || version || challenge` exceeds RSA-1024's 117-byte plaintext limit | Disconnect with a stable localized protocol error; do not attempt RSA construction |
| Expected exception is fully handled and has no diagnostic value | It may be deliberately ignored; no log is required solely because a catch exists |
| Codec, scheduling, protocol, or cryptographic work throws and needs later diagnosis | Log the complete `Throwable`; clear temporary secrets and disconnect with a stable localized component |
| Connection owning temporary secret state ends | Zero and clear that state |

## 5. Good / Base / Bad Cases

- Good: a supported client lets Minecraft prepare one login, asks for consent,
  then uses the selected digest and standard response with the same `K` and
  ciphers.
- Base: the mod connects to an unpatched server with unchanged vanilla login
  bytes and behavior.
- Bad: the Mod calls `handleHello` again, stores a pending Hello, performs two
  session joins, sends `K` separately, or resets Minecraft's frontend cipher
  state.
- Bad: a comment repeats an assignment but does not explain the protocol reason
  for the Mixin change.
- Bad: each hook has a short comment, but no comment connects the hooks into one
  readable login flow.
- Bad: a failure that needs later diagnosis is reduced to its message or shown
  directly to the player. This does not require logging an expected exception
  that the local catch intentionally and completely handles.

## 6. Tests Required

- Automated tests must call the production envelope logic with real encoded RSA
  keys. Cover ordinary-SPKI passthrough, decorated-SPKI target-key extraction,
  acknowledgement construction/bounds, and malformed input through the public
  result path.
- Assert the target key or empty value returned by production parsing. Do not
  add source-shape or abstraction-shape tests for the result type.
- Assertions may verify only the observable result of production logic that the
  test actually executed. Do not treat constants, source text, class presence,
  Mixin metadata, removed-class absence, or private fields reached by reflection
  as functional coverage.
- Verify the Mixin's join-key selection and standard key-packet behavior through
  the real supported-server login, not a reflection or source-shape test.
- Run `:mod:build` for a narrow Mod change; it already includes the Mod tests.
- For cross-layer changes, live-check one supported login through the online-mode
  backend and one ordinary login to an unpatched server.
- Do not add a logging test framework, broad Mixin tests, or duplicate Fabric
  Loader's Minecraft version rejection.

## 7. Wrong vs Correct

### Wrong: Foreign Annotation Contract

```java
@Shadow @Final private @NotNull Minecraft minecraft;

private void prepare(
        @NotNull ClientboundHelloPacket packet,
        @Local @NotNull SecretKey secretKey) {
}
```

### Correct: Owned Annotation Contract

```java
@Shadow @Final private Connection connection;

private void prepare(
        ClientboundHelloPacket packet,
        @Local SecretKey secretKey) {
    var minecraft = Minecraft.getInstance();
}

private void continueLogin(@NotNull PreparedLogin preparedLogin) {
}
```

The public accessor replaces the unnecessary `Minecraft` shadow. The remaining
shadow mirrors the target field's final modifier. The Mixin keeps foreign input
contracts unchanged and uses `@NotNull` only for the project-owned helper
contract.

### Wrong: Exception Reporting

```java
catch (Exception exception) {
    LOGGER.error(exception.getMessage());
    connection.disconnect(Component.literal(exception.toString()));
}
```

### Correct: Exception Reporting

```java
catch (Exception exception) {
    LOGGER.error(
            "Cannot prepare the FakePlayerProxy key response: RSA construction failed",
            exception);
    connection.disconnect(Component.translatable(
            "fakeplayerproxy.disconnect.proxy_connection_failed"));
}
```

Passing the exception object preserves the full stack trace and cause chain.
The player receives an actionable message instead of internal implementation
details.

### Correct: Intentional Ignore

```java
try {
    return decodeBoundedPayload(input);
} catch (RuntimeException ignored) {
    return invalidPacket();
}
```

This catch fully converts an expected malformed-input failure into the packet's
invalid state. If that failure has no diagnostic value in this context, forcing
a log entry would only add noise.

### Wrong: Isolated Comment

```java
// Set the challenge to the acknowledgement.
challenge = acknowledgement;
```

This comment repeats one statement. It does not explain the flow or the reason.

### Correct: Connected Flow Comment

```java
/**
 * Stops a supported Hello before authentication or key send.
 *
 * <p>Minecraft has already generated one key, two ciphers, and the Vanilla
 * digest. An empty target-key result leaves the method unchanged. A supported
 * carrier prepares both consent choices from those same values before the
 * screen takes the user decision.
 */
prepareConsentChoices(...);
```

The correct comment connects the injection point, both branches, and the next
owner of the prepared login.

## ConsentStore

The consent owner is `com.fakeplayerproxy.mod.config.ConsentStore`.

The store uses `FabricLoader.getInstance().getConfigDir()` and the file
`fakeplayerproxy/consent_store.toml`. It stores one boolean per server address.

```java
Optional<Boolean> find(String serverAddress)
void remember(String serverAddress, boolean allow)
```

The store uses the JDK file API for the small TOML shape required by this
project. It does not use Gson, JSON, or a new TOML dependency.

The store does not define a decision enum. A missing key opens the consent
screen. A stored `true` allows the relay. A stored `false` declines it.

The Mod config boundary must not use `Objects.requireNonNull`, Java `assert`, or
deliberate validation exceptions. Invalid input and read failures return the
existing failure value and log the concrete condition.
