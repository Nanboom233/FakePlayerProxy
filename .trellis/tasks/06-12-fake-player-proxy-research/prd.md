# FakePlayerProxy Direct Remote Login Relay

## Goal

Keep the repository split into `plugin/` and `mod/`, with only a minimal
Velocity patch under `plugin/patch/`. A supported Minecraft 26.2 client must be
able to connect through patched Velocity to an ordinary online-mode target while
Velocity can decrypt and proxy the resulting protected packet stream.

This task defines a direct target-login relay using the two existing login
packets. The client still generates the AES secret `K`; no custom login packet
or second authentication transaction is introduced. Proxy task commands,
queues, reconnect behavior, and automation semantics are not part of this
design.

## Confirmed Facts

- Minecraft 26.2 uses protocol 776 and Java 25.
- Fabric Loader 0.19.3 supports Minecraft 26.2.
- Fabric Loom provides `1.17-SNAPSHOT` for Minecraft 26.2.
- Velocity represents Server Hello as `EncryptionRequestPacket` with server ID,
  public key, verify token, and `shouldAuthenticate` fields.
- Minecraft 26.2 parses the Server Hello public-key bytes through JCA
  `KeyFactory("RSA")` and constructs the ordinary `ServerboundKeyPacket` from a
  client-generated AES key, the parsed RSA key, and the received challenge.
- The target `mc.ourworld.vip:25565` uses a 2048-bit RSA key whose typical X.509
  SPKI is 294 bytes. The pinned Velocity decoder's 256-byte public-key limit is
  therefore too small for that backend request.
- Java 25.0.4 and Java 26 locally accept and preserve an OCTET STRING in the
  `rsaEncryption` AlgorithmIdentifier parameters of an RSA SPKI, and RSA
  encryption/decryption succeeds with the corresponding ordinary private key.
- All 23 released Minecraft versions from 1.20 through 26.2 retain the required
  Server Hello, client-generated AES, session-hash, RSA response, and
  send-before-encryption mechanism. Their protocol versions span 763 through
  776.
- Mojang metadata divides that range into Java 17 for 1.20-1.20.4, Java 21 for
  1.20.5-1.21.11, and Java 25 for 26.1-26.2. A representative 464-byte carrier
  passed local SunRsaSign/SunJCE probes on all three Java majors and on Mojang's
  installed Microsoft OpenJDK 25 runtime. The selected multi-byte magic adds six
  bytes and remains within the researched field/provider capacity.
- This decorated SPKI is operationally compatible with the tested JCA provider,
  but its non-NULL `rsaEncryption` parameters are not standards-canonical.
- Stock Velocity rejects an online-mode backend in `LoginSessionHandler`.

## Requirements

### R1: Project Structure

- `plugin/` contains the Velocity plugin and `plugin/patch/`.
- `plugin/patch/` contains a unified diff against one exact Velocity commit, not
  a complete Velocity source copy.
- Patch application occurs only in `plugin/build/server/source/`.
- `mod/` contains the client-only Fabric mod.
- The root build includes both subprojects.
- The plugin uses Java 17 and MCProtocolLib
  `org.geysermc.mcprotocollib:protocol:26.2-20260709.110151-15`.
- The plugin excludes MCProtocolLib's Netty `4.2.1.Final` dependency and uses
  Netty `4.2.17.Final`.
- IntelliJ IDEA exposes only `server/releaseJar` and `server/runServer`;
  `runServer` executes in `plugin/run/`.

### R2: Server Hello And Support Detection

- After `PreLoginEvent` accepts the client Hello, patched Velocity resolves the
  first static target from the existing forced-host/`try` configuration and
  opens that backend login before sending a frontend Server Hello.
- The target's online-mode Server Hello is the source of the server ID, original
  public key, challenge, and `shouldAuthenticate` value. An offline target that
  skips Server Hello is unsupported by this relay path.
- Patched Velocity replaces the target public-key field with a valid proxy RSA
  SPKI. Its `rsaEncryption` AlgorithmIdentifier parameters contain an OCTET
  STRING envelope encoded as
  `FPPMOD || 0x01 || VarInt(targetKeyLength) || targetSPKI`.
- The proxy SPKI's RSA modulus and exponent belong to a key pair generated and
  retained by Velocity; the parameter envelope contains no secret.
- The target server ID, challenge, and `shouldAuthenticate` fields remain
  unchanged in the forwarded Server Hello.
- A vanilla client parses the decorated key and returns the ordinary
  `RSA_proxy(K)` and `RSA_proxy(originalChallenge)` fields.
- A supported mod returns `RSA_proxy(K)` and
  `RSA_proxy(FPPACK || 0x01 || originalChallenge)` using the same standard
  packet.
- Velocity distinguishes the exact original challenge from the acknowledged
  form after decryption. For a vanilla response, it installs frontend AES with
  the recovered `K` before sending an encrypted, observable mod-required
  disconnect.

### R3: Direct Remote Authentication Forwarding

- The mod extracts the original target SPKI from the decorated proxy SPKI and
  computes the sole Mojang session join from the target server ID, target public
  key, and client-generated `K`.
- The mod uses the proxy RSA public key only to encrypt the standard response to
  Velocity. It does not perform a proxy session join before the target join.
- After validating the acknowledgement, Velocity decrypts `K`, constructs
  `RSA_target(K)` and `RSA_target(originalChallenge)`, and sends the ordinary
  response to the unmodified target.
- Velocity enables frontend AES only after receiving the client response and
  enables backend AES only after writing the target response. Both legs use the
  same key bytes with independent cipher state.
- `K` enables packet encryption/decryption and proxying; it is not an
  authentication credential or a substitute for Mojang authentication.
- Target Login Success supplies the authoritative UUID, username, properties,
  and Minecraft 26.2 session ID used for frontend Login Success and the final
  Velocity player profile. Velocity does not register the player before that
  target authentication succeeds.
- Pause the backend after target Login Success until the frontend acknowledges
  Login Success and sends client settings, then resume the existing backend in
  configuration state instead of opening a second connection.

### R4: Client Mod Structure

```text
com.fakeplayerproxy.mod.FakePlayerProxyMod
com.fakeplayerproxy.mod.packets.ServerHelloPacketEnvelope
com.fakeplayerproxy.mod.mixins.MixinClientHandshakePacketListenerImpl
```

- Use Sponge Mixin for every Minecraft hook.
- Do not depend on Fabric API and do not add a mod entrypoint.
- Connections to unsupported servers retain the original Minecraft behavior.
- Minecraft remains pinned to 26.2; Fabric Loader owns incompatible-version
  rejection.

### R5: Secret And Error Handling

- Never persist or log the AES secret, access token, or profile credentials.
- Clear temporary secret state when its owning connection ends.
- Do not retain `K` in static Mod state or send it through a custom payload.
- Use `@NotNull` only when a client boundary value cannot be null. Code can use
  that value without a null check.
- Treat each client reference without `@NotNull` as possibly null. Check it
  before use when the local flow can receive null.
- Do not use `Objects.requireNonNull`, assertions, or deliberately thrown
  validation exceptions in client code.
- Exceptions that need diagnosis are logged with the original `Throwable`.
  Player-facing errors remain concise and user-friendly. Expected exceptions may
  be intentionally ignored when their outcome is completely handled and has no
  diagnostic value.

### R6: Code And Patch Discipline

- Keep the Velocity patch to the smallest set of files and hunks that implements
  the approved relay.
- Map each patch hunk to a stated protocol or lifecycle requirement. Remove a
  hunk when no such requirement exists.
- Use existing Velocity crypto, connection, and lifecycle APIs where they provide
  the required operation. Do not duplicate those operations in relay code.
- Represent the linear backend relay with one enum state and exact predecessor
  checks. Do not encode mutually exclusive phases with independent booleans.
- Use `CompletableFuture` composition for independent asynchronous prerequisites.
  Let the first future completion provide one-shot behavior instead of a manual
  started flag.
- Treat comments as technical documentation for the implementation. Use class,
  method, and inline comments to describe the complete relay flow.
- Explain each decision, its reason, its benefit, its state owner, and its next
  handler. Explain error handling and cipher ordering at their code locations.
- Write comments in ASD-STE100 Simplified Technical English. STE controls the
  wording but does not replace the technical content.
- Comments can cite the applicable research document. The local comment must
  still state the verified result that the code uses.
- Focused tests must execute production protocol logic with real keys and bytes.
  Structural assertions do not count as behavior tests.

## Out Of Scope

- A complete Velocity fork
- Proxy task packets or task behavior
- Task queues, reconnect logic, or automation changes
- Fabric API or Fabric Networking API
- Client UI
- Additional encryption layers not required by the verified protocol
- Access-token storage or transfer to Velocity
- Broad Velocity refactoring
- Minecraft builds other than 26.2
- Dynamic `PlayerChooseInitialServerEvent` or `ServerPreConnectEvent` redirection
  during the pre-authentication target selection
- Switching or failing over to another online-mode backend without reconnecting
  through a new frontend login
- Offline-mode backend login through the decorated-SPKI relay

## Backward Compatibility Evidence

- Minecraft 1.20-1.20.4 use the three-field Hello and always authenticate;
  Minecraft 1.20.5-26.2 add and preserve `shouldAuthenticate`.
- All inspected client codecs use parameterless byte-array reads for public key
  and challenge. The tested 464-byte carrier and selected approximately
  470-byte multi-byte-magic carrier fit every release at the packet layer.
- Java 17, 21, and 25 directly parsed and preserved the exact carrier and
  completed RSA/PKCS#1 v1.5 encryption/decryption. Compatibility is limited to
  the tested OpenJDK `SunRsaSign`/`SunJCE` behavior, not arbitrary providers.
- The protocol mechanism is backward-compatible, but mappings, listener
  structure, Java bytecode targets, and Fabric binaries differ. This evidence
  does not establish one compiled Mod jar for the whole range.

## Acceptance Criteria

- `plugin/`, `plugin/patch/`, and `mod/` remain present without a Velocity source
  copy in the repository.
- The final patch applies to the pinned Velocity commit from the disposable build
  directory.
- A vanilla 26.2 client receives an observable mod-required rejection.
- A modded 26.2 client reaches the configured online-mode backend through
  patched Velocity.
- The configured initial target is connected before the frontend Server Hello;
  the same backend connection continues through target authentication and
  configuration without a second login.
- Authentication is directly relayed; the mod performs exactly one session join
  using the target public key.
- Velocity can decrypt and proxy the protected stream using the client-generated
  `K` returned through the ordinary `ServerboundKeyPacket`.
- The decorated proxy SPKI remains parseable and usable by the supported client
  JCA runtime, and Velocity owns its corresponding private key.
- The plugin sends one visible post-connect verification message.
- Velocity registers the player only after target Login Success and preserves
  the target-authenticated profile and Minecraft 26.2 session ID.
- The modded client still connects normally to an unpatched server.
- Focused automated tests execute the production SPKI and RSA transformation
  with real keys and validate the resulting bytes. Structural, reflection,
  source-text, and constant-only assertions do not count as protocol tests.
- Every file and hunk in the final Velocity patch has a direct approved reason.
  The patch contains no duplicated Velocity behavior or unrelated code churn.
- Validation remains targeted to affected modules and four live checks; no
  repeated full-scope audit is required.

## Planning State

`design.md` records the selected Java-provider-compatible SPKI carrier and the
completed backward compatibility matrix. Implementation is approved. The current
Velocity patch remains a candidate until the minimal-patch review is complete.

## Implementation Scope

- Implementation remains limited to Minecraft 26.2 and Java 25.
- Minecraft 1.20-26.1 compatibility remains research evidence for the protocol
  design. Do not add multi-version builds, version-specific source sets, or
  earlier-version Fabric artifacts in this task.
