# FakePlayerProxy Connection Design

## Design State

The Java-provider-compatible decorated-SPKI carrier is selected for the current
Minecraft 26.2 design. Backward research confirms that the protocol mechanism is
conditionally usable across all 23 releases from Minecraft 1.20 through 26.2.
That compatibility does not make one compiled Fabric artifact binary-compatible
with every release.

## Architecture Boundaries

- `plugin/` owns the Velocity plugin, the minimal unified patch under
  `plugin/patch/`, and isolated server build/run tasks.
- `mod/` owns the Minecraft 26.2 client-only Fabric mod.
- The mod uses Sponge Mixin without Fabric API or a mod entrypoint.
- The repository does not contain a complete Velocity source copy.
- Patch application occurs in `plugin/build/server/source/`; the server runs from
  `plugin/run/`.

## Settled Protocol Invariants

- Minecraft's client generates the AES secret `K` once, as in the ordinary
  online-mode login path.
- Velocity owns a proxy RSA key pair. The public key remains a JCA-parseable RSA
  SPKI, while its AlgorithmIdentifier parameters carry only public relay
  metadata and the original target public key.
- The target server ID, challenge, and `shouldAuthenticate` value remain
  unchanged in the relayed Server Hello.
- The client response remains the ordinary two-field `ServerboundKeyPacket`; no
  custom login packet carries `K` or credentials.
- The Mod performs one Mojang `joinServer` operation, using the original target
  public key and the same `K`. It does not first authenticate against Velocity's
  decorated proxy key.
- Velocity decrypts the standard response with its proxy private key, then
  constructs the target-key response. The same `K` enables independent frontend
  and backend cipher streams so Velocity can proxy packets.
- An unsupported server follows the original Minecraft client path; installing
  the mod must not alter that connection.
- A vanilla client is allowed to parse the public key and return a normal key
  response. Velocity distinguishes it from the Mod acknowledgement, installs
  frontend encryption, and sends an observable encrypted rejection.

## Server Hello Contract

The selected Minecraft 26.2 representation is:

```text
ClientboundHelloPacket.serverId = originalTargetServerId

ClientboundHelloPacket.publicKey = SPKI(
  subjectPublicKey = RSA(proxyModulus, proxyExponent),
  algorithm = rsaEncryption,
  parameters = OCTET STRING(
    "FPPMOD" || 0x01 || VarInt(targetPublicKey.length) || targetPublicKey
  )
)

ClientboundHelloPacket.challenge = originalTargetChallenge
ClientboundHelloPacket.shouldAuthenticate = originalTargetShouldAuthenticate
```

The proxy modulus/exponent have a matching private key retained by Velocity.
The OCTET STRING contains no AES key, token, profile credential, or other
secret. The original challenge is not duplicated in the SPKI envelope.

The envelope is non-canonical for the `rsaEncryption` OID because standards use
NULL parameters. It is accepted, retained byte-for-byte, and cryptographically
usable by the locally tested Java 25.0.4 and Java 26 SunRsaSign-compatible
providers. The accepted compatibility target is operational JCA support rather
than cross-provider standards conformance.

## Key Response Contract

For target challenge `C`, a vanilla client follows its normal path:

```text
keybytes             = RSA_proxy(K)
encryptedChallenge   = RSA_proxy(C)
```

The Mod detects the decorated SPKI, uses the embedded target public key for the
session join, and changes only the challenge plaintext used to construct the
standard response:

```text
keybytes             = RSA_proxy(K)
encryptedChallenge   = RSA_proxy("FPPACK" || 0x01 || C)
```

With the current RSA-1024 proxy key, each ciphertext is 128 bytes. For the
observed four-byte target challenge, the acknowledgement plaintext is far below
the 117-byte PKCS#1 v1.5 limit. More generally, the fixed acknowledgement plus
the target challenge must remain within that limit.

Velocity treats exact `C` as a vanilla response and the exact acknowledged form
as Mod support. It does not use an exception as control flow. To make a vanilla
rejection observable, Velocity first installs frontend encryption with the
decrypted `K`, then sends a concise encrypted disconnect.

## Connection Flow

```text
Modded client                 Patched Velocity                 Online target
     |                               |                               |
     | -- Client Hello ------------>| -- target Client Hello ------>|
     |                               |<-- target Server Hello -------|
     |<-- decorated proxy Hello -----|                               |
     |  generate K                   |                               |
     |  join(target id/key, K)       |                               |
     |-- Key(RSAproxy(K), ACK+C) --->|                               |
     |                               |-- Key(RSAtarget(K), C) ------>|
     |  enable frontend AES(K)       |   enable backend AES(K)       |
     |<================ packet proxying with independent cipher state =============>|
```

The target authenticates the single session join performed by the real client.
Velocity never receives or uses the client's access token.

## Velocity Login Lifecycle

Velocity normally chooses and connects the initial backend only after frontend
Login Success. This relay reverses that dependency because the target public key
and challenge must reach the client in its only Server Hello.

After `PreLoginEvent` succeeds, `InitialLoginSessionHandler` creates an internal,
unregistered `ConnectedPlayer` from the client Hello and resolves the first
static forced-host/`try` target. It opens one `VelocityServerConnection` without
firing post-authentication initial-server redirection events. The provisional
player is not registered and is not visible as an authenticated player.

`LoginSessionHandler` forwards the target Encryption Request to the active
frontend login handler as the decorated Server Hello. After a valid Mod response,
the frontend enables AES and the backend handler writes the reconstructed target
response before enabling backend AES.

When the target returns Login Success, the backend pauses reads and passes the
authoritative profile and target session ID to the frontend. `AuthSessionHandler`
then reuses the provisional player, runs the ordinary profile, permission, login,
registration, and PostLogin stages, and sends frontend Login Success using the
target-authenticated identity/session. It does not call the normal
`connectToInitialServer` path.

After the client acknowledges Login Success and supplies client settings,
`ClientConfigSessionHandler` resumes that same backend connection: send backend
Login Acknowledged, install `ConfigSessionHandler`, forward client settings, and
re-enable backend reads. Existing configuration/play transition code then owns
the rest of the connection and emits `ServerPostConnectEvent` normally.

`LoginSessionHandler` owns the backend relay as one linear state sequence:

```text
AWAITING_HELLO
  -> HELLO_FORWARDED
  -> RESPONSE_WRITE_PENDING
  -> BACKEND_ENCRYPTED
  -> LOGIN_SUCCEEDED
  -> CONFIGURATION_RESUMED
```

Each backend event accepts only its exact predecessor. A failure moves the
handler to terminal `FAILED`, so a late write callback cannot enable AES.

`ClientConfigSessionHandler` does not add another state machine. It combines the
successful `PostLoginEvent` future with a future completed by the first client
settings packet. This barrier resumes the target once in either arrival order.

This scope deliberately uses static forced-host/`try` selection before the
player is authenticated. Dynamic initial-server redirection and later online
backend switching would require another authentication negotiation and are not
part of this task.

## Failure And Cleanup

- A target that does not send an online-mode Encryption Request is rejected as
  unsupported; do not fall back to the old two-stage/custom-payload path.
- Exact decrypted target challenge means Vanilla. Close the provisional backend,
  enable frontend AES with `K`, and send the encrypted Mod-required disconnect.
- Malformed carrier, ACK, RSA data, target profile, or state transition closes
  both pending legs. Expected protocol rejection uses result branches rather
  than deliberately thrown exceptions.
- Crypto, scheduling, or channel failures that require diagnosis retain the full
  `Throwable` in logs while the player receives a stable concise message.
- `K` is held only until each cipher is installed; temporary byte copies are
  zeroed. No static Mod secret or connection-global plaintext secret store is
  introduced.

## Client Boundary

The Java package shape remains:

```text
com.fakeplayerproxy.mod.FakePlayerProxyMod
com.fakeplayerproxy.mod.packets.ServerHelloPacketEnvelope
com.fakeplayerproxy.mod.mixins.MixinClientHandshakePacketListenerImpl
```

`FakePlayerProxyMod` has no entrypoint or static connection secret. The envelope
helper owns strict recognition/extraction and acknowledgement construction. The
single Mixin changes only the target-key digest argument and standard key-packet
challenge argument for a recognized envelope.

Use `@NotNull` only when a client-owned boundary value cannot be null. Code can
use that value without a null check. Treat each unannotated client reference as
possibly null. Check it before use when the local flow can receive null. The
client does not use `Objects.requireNonNull`, assertions, or deliberately thrown
validation exceptions. A diagnostic failure logs the complete `Throwable`. A
player sees a separate user-friendly message. An expected, completely handled
exception may be ignored when it has no diagnostic value.

## Plugin Connection Proof

`FakePlayerProxyPlugin` sends one post-connect system message after the backend
join succeeds. This proof belongs to the plugin and does not add probe logic to
the Velocity core patch.

## Compatibility Matrix

| Server | Client | Required result |
| --- | --- | --- |
| Patched Velocity + online backend | Modded 26.2 | Direct authentication forwarding and normal packet proxying |
| Patched Velocity | Vanilla 26.2 | Normal RSA response followed by an encrypted mod-required rejection |
| Unpatched server | Modded 26.2 | Original Minecraft login behavior |

### Backward Protocol Families

| Minecraft releases | Protocols | Java | Relevant boundary | Result |
| --- | --- | --- | --- | --- |
| 1.20-1.20.4 | 763-765 | 17 | Three-field Hello; unconditional session join | Conditional yes |
| 1.20.5-1.21.11 | 766-774 | 21 | Adds `shouldAuthenticate`; StreamCodec family | Conditional yes |
| 26.1-26.2 | 775-776 | 25 | Same four-field contract; official names | Conditional yes |

Every inspected client uses parameterless byte-array reads for the Server Hello
public key and challenge. The tested 464-byte carrier and selected approximately
470-byte multi-byte-magic carrier therefore fit at the packet layer. The AES-128
generation, RSA parsing, session digest inputs,
`ServerboundKeyPacket(SecretKey, PublicKey, byte[])`, and send-then-enable order
remain semantically stable.

The exact carrier was directly tested with Java 17, 21, and 25
SunRsaSign/SunJCE. Mojang's installed Microsoft OpenJDK 25 runtime was also
tested directly. Exact Mojang gamma/delta binaries were unavailable, so Java 17
and 21 compatibility is bounded to the same tested OpenJDK provider names.

Mixin structure still changes at 1.20.2 login state handling, 1.20.5's
`shouldAuthenticate` branch/private encryption helper, 1.21.2's authentication
executor shape, and 26.1's official-name build. Supporting those releases would
require version-family builds or separated source sets; it is not part of the
current 26.2 artifact. The backward matrix remains research evidence only.

## Implementation Constraints

- Treat the current 10-file Velocity diff as a candidate, not as the accepted
  patch. Review each hunk against the approved connection flow. Remove any hunk
  that does not have a direct protocol or lifecycle reason.
- Prefer a narrow branch change over a complete method replacement. Reuse the
  existing Velocity crypto and lifecycle surfaces before adding relay helpers,
  accessors, constructors, or state.
- Use comments as the implementation narrative. Class comments describe the
  complete relay stages and the owner of each stage.
- Method comments explain inputs, decisions, results, benefits, and the next
  handler. Inline comments explain ordering, ownership, and failure handling.
- Use ASD-STE100 Simplified Technical English. STE controls the wording but does
  not reduce the required technical explanation.
- A comment can cite the related research document. The local comment still
  states the verified provider or protocol result.
- Raise the pinned Velocity backend Server Hello public-key decode bound above
  294 bytes. This is required to receive the observed RSA-2048 target key and is
  independent of the approximately 470-byte outbound decorated key, whose encode
  path and client codecs already accept it.
- Keep the frontend proxy key RSA-1024 so both standard response ciphertexts
  remain 128 bytes and fit existing frontend response bounds.
