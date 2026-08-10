# Fabric Client Mod Contract

## 1. Scope / Trigger

- Scope: `mod/src/main/**` and focused tests under `mod/src/test/**`.
- Trigger: a Minecraft 26.2 client receives a modified Server Hello that declares
  FakePlayerProxy support.
- The mod is client-only, uses Java 25 and Fabric Loader 0.19.3, and has no
  Fabric API dependency or mod entrypoint.
- Connections to servers without the FakePlayerProxy Server Hello extension must
  retain Minecraft's original login behavior.

## 2. Structure

- Main state owner: `com.fakeplayerproxy.mod.FakePlayerProxyMod`.
- Packet helpers belong under `com.fakeplayerproxy.mod.packets`.
- Minecraft hooks belong under `com.fakeplayerproxy.mod.mixins` and every hook
  must be declared in `fakeplayerproxy-mod.mixins.json`.
- Use Sponge Mixin for Minecraft integration. Do not introduce Fabric API merely
  to register packets or listeners.
- The primary hook is the Minecraft 26.2
  `ClientHandshakePacketListenerImpl.handleHello` path. Packet-envelope helpers
  remain under `packets`; hooks remain under `mixins`.
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
- For a supported Hello, suppress the automatic session join against the
  decorated proxy key. Perform the sole `joinServer` operation with the original
  target server ID/public key and the same `K`, only when
  `shouldAuthenticate` is true.
- Keep the response as a standard `ServerboundKeyPacket`. Encrypt `K` with the
  proxy RSA public key and replace only the challenge plaintext with
  `FPPACK || 0x01 || originalChallenge` before Minecraft encrypts it with the
  same proxy key.
- Do not reset or independently install frontend encryption. Minecraft retains
  ownership of enabling AES after the key packet send completes.
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
- Use JetBrains `@NotNull` only when a parameter or return value cannot be null.
  Code can dereference that value without a null check.
- JetBrains `@NotNull` is a Java type annotation. Put an array-reference
  annotation on the array dimension, as in `byte @NotNull []`. Do not put it on
  the primitive element type as in `@NotNull byte[]`.
- Treat each reference without `@NotNull` as possibly null. Check it before
  dereference when the local flow can receive null.
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
| Decorated SPKI envelope is null, malformed, unsupported, or contains an invalid target key | Return or disconnect at the owning boundary without throwing from Mod code |
| Supported Hello and `shouldAuthenticate == true` | Run exactly one target-key session join and send the acknowledged standard response |
| Supported Hello and `shouldAuthenticate == false` | Run no session join and send the acknowledged standard response |
| `ACK || version || challenge` exceeds RSA-1024's 117-byte plaintext limit | Disconnect with a stable user-facing protocol error; do not attempt RSA construction |
| Expected exception is fully handled and has no diagnostic value | It may be deliberately ignored; no log is required solely because a catch exists |
| Codec, scheduling, protocol, or cryptographic work throws and needs later diagnosis | Log the complete `Throwable`; clear temporary secrets and disconnect with a stable user-friendly component |
| Connection owning temporary secret state ends | Zero and clear that state |

## 5. Good / Base / Bad Cases

- Good: a supported client extracts the target key, lets Minecraft generate `K`,
  performs one target session join, and returns the acknowledged standard key
  packet encrypted to Velocity's proxy key.
- Base: the mod connects to an unpatched server with unchanged vanilla login
  bytes and behavior.
- Bad: the mod first joins the proxy digest and then performs a second target
  join, sends `K` separately, or resets Minecraft's frontend cipher state.
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
- Assertions may verify only the observable result of production logic that the
  test actually executed. Do not treat constants, source text, class presence,
  Mixin metadata, removed-class absence, or private fields reached by reflection
  as functional coverage.
- Verify the Mixin's join-key selection and standard key-packet behavior through
  the real supported-server login, not a reflection or source-shape test.
- Build only `:mod:test` and `:mod:build` for mod changes.
- For cross-layer changes, live-check one supported login through the online-mode
  backend and one ordinary login to an unpatched server.
- Do not add a logging test framework, broad Mixin tests, or duplicate Fabric
  Loader's Minecraft version rejection.

## 7. Wrong vs Correct

### Wrong: Null Validation

```java
Objects.requireNonNull(value);
throw new IllegalArgumentException("bad packet");
```

### Correct: Null Validation

```java
boolean install(byte @NotNull [] value) {
    return value.length == 16;
}
```

The strong annotation states that `value` cannot be null. The method can use it
without a null check.

```java
boolean install(byte[] value) {
    return value != null && value.length == 16;
}
```

The unannotated reference can be null. The method checks it before use.

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
    LOGGER.error("FakePlayerProxy packet handling failed", exception);
    connection.disconnect(Component.literal(
            "Unable to continue the proxy connection. Please try again."));
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
 * Keeps the standard key packet for the proxy connection.
 *
 * <p>The previous hook used the target key only for the Mojang session digest.
 * This hook adds the Mod acknowledgement to the original challenge. Velocity
 * can now detect Mod support and recover the same client-generated AES key.
 */
challenge = acknowledgement;
```

The correct comment connects this hook to the previous hook and the server action.
