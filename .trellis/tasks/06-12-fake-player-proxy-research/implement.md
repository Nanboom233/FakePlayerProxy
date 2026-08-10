# FakePlayerProxy Implementation Plan

## Planning Gate

The decorated-SPKI protocol and its implementation are approved. Backward
compatibility research is complete and the build remains fixed to Minecraft 26.2
/ Java 25. The current Velocity patch is not accepted as final. It must pass the
minimal-patch review before another live client test.

## Planning Evidence Complete

- The 1.20-26.2 matrix remains protocol evidence; the build target is only
  Minecraft 26.2 / Java 25.
- Minecraft 26.2 client login source and the pinned Velocity frontend/backend,
  packet, configuration, and Netty state paths have been inspected.
- Frontend/backend specs and both JSONL manifests reference the selected SPKI
  research and client-generated-key contract.

## Settled Boundaries For The Proposed Implementation

- Keep `plugin/`, `plugin/patch/`, and `mod/`; do not add a Velocity source copy.
- Keep the Fabric mod client-only, Mixin-only, and free of Fabric API.
- Do not add a custom Minecraft version check.
- Do not add proxy task, queue, reconnect, or automation behavior.
- Keep the JCA-parseable proxy RSA SPKI and carry protocol/version plus the
  original target public key in its AlgorithmIdentifier parameter OCTET STRING.
- Preserve the original target challenge in Server Hello.
- Keep client-generated `K` in the ordinary `ServerboundKeyPacket`; do not add a
  separate FakePlayerProxy packet.
- Let the Mod perform exactly one session join with the original target public
  key, then acknowledge support as `FPPACK || version || originalChallenge` in
  the standard encrypted challenge field.
- Let Velocity decrypt with its proxy private key and reconstruct the ordinary
  target-key response before enabling independent AES streams with the same `K`.
- Let a vanilla client complete the normal RSA response, then send the rejection
  only after frontend encryption is installed.
- Preserve ordinary connections to unsupported servers.
- Keep the connection-proof chat message in the plugin.

## Ordered Implementation

### 0. Minimize The Candidate Velocity Patch

- Start from the pinned clean Velocity commit and the stored patch.
- Map each changed file and hunk to one approved protocol or lifecycle need.
- Search the pinned source for an existing API before retaining a relay helper,
  accessor, constructor, state field, or replaced method.
- Remove duplicated Velocity behavior, unrelated churn, and speculative fallback
  paths.
- Write comments as one connected implementation narrative. Explain the full
  flow across the frontend handler, backend handler, authentication, and config.
- Explain each decision, its reason, its benefit, its state owner, and its next
  handler. Explain error handling where each owner performs it.
- Use ASD-STE100 Simplified Technical English. Cite the related research file
  when provider evidence is required. State the verified result locally.
- Keep tests that execute the retained production logic with real keys and bytes.
  Remove structural checks and tests for deleted relay code.

### 1. Remove The Rejected Two-Stage Mod Path

- Delete `RemoteServerHelloPacket`, `RemoteServerKeyPacket`,
  `MixinClientCommonPacketListenerImpl`, and both custom-payload codec Mixins.
- Remove `fakeplayerproxy:remote_login`, static AES storage, disconnect cleanup
  for that storage, and their tests.
- Leave `FakePlayerProxyMod` without connection-global secret state and register
  only the login-listener Mixin in `fakeplayerproxy-mod.mixins.json`.

### 2. Implement The Minecraft 26.2 Login Hook

- Replace the challenge-marker helper with a strict Server Hello SPKI-envelope
  helper under `com.fakeplayerproxy.mod.packets`.
- Recognize only a JCA-parsed RSA key whose retained encoded SPKI contains the
  exact OCTET STRING envelope:
  `FPPMOD || version 1 || VarInt(targetKeyLength) || targetSPKI`, with no trailing
  bytes and a target-key bound matching the patched Velocity decoder.
- Parse the embedded target SPKI through Minecraft/JCA RSA utilities and build
  `FPPACK || version 1 || originalChallenge` only when its plaintext length is at
  most 117 bytes.
- In `MixinClientHandshakePacketListenerImpl`, inspect the envelope at
  `handleHello` entry. For an ordinary key, leave the method untouched.
- For a supported key, replace only the public-key argument passed to
  `Crypt.digestData` with the target public key. Keep the proxy key used by the
  ordinary `ServerboundKeyPacket` constructor and replace only its challenge
  argument with the acknowledgement. Minecraft continues to own AES generation,
  the single conditional `joinServer`, packet send, and send-callback cipher
  installation.
- Convert a recognized-but-invalid FPP envelope into a logged diagnostic plus a
  concise disconnect; do not let an exception escape the Mixin boundary.

### 3. Replace The Velocity Patch Protocol Core

- Rebuild `plugin/patch/0001-server-hello-marker.patch` from the pinned clean
  commit; the patch remains the only stored Velocity modification.
- Add one narrowly scoped protocol utility for strict envelope DER construction,
  exact Vanilla/Mod challenge classification, RSA encryption, and bounds. Do not
  add a generic ASN.1 framework or another encryption layer.
- In `EncryptionRequestPacket`, raise only the backend public-key decode bound
  from 256 to 512 bytes and expose the existing server ID and
  `shouldAuthenticate` values needed to create the forwarded request. Keep the
  challenge bound at 16.
- Keep/add the raw-array `EncryptionResponsePacket` constructor required to send
  `RSA_target(K)` and `RSA_target(C)`.
- Do not add custom plugin-message routing or plaintext AES storage to
  `MinecraftConnection`.

### 4. Preconnect The One Static Initial Backend

- After accepted `PreLoginEvent`, construct an unregistered provisional
  `ConnectedPlayer` and resolve the first target from existing forced-host/`try`
  configuration.
- Add a package-owned connection entrypoint that sets one
  `VelocityServerConnection` as in-flight and starts its handshake without
  exposing the provisional player through post-authentication server-selection
  events.
- In backend `LoginSessionHandler`, replace the online-mode exception with a
  callback to the active `InitialLoginSessionHandler`. Decorate the proxy public
  key while preserving the target server ID, challenge, and auth flag, then send
  that one Server Hello to the client.
- Reject an offline target, duplicate Encryption Request, closed/mismatched
  connection, or missing pending state with cleanup of both legs.

### 5. Transform The One Standard Key Response

- In `InitialLoginSessionHandler`, decrypt the two frontend response fields with
  Velocity's proxy private key and validate the recovered 16-byte `K`.
- If the recovered challenge equals the original target challenge, close the
  backend, install frontend AES, and send the encrypted Vanilla Mod-required
  disconnect.
- If it equals the exact acknowledgement, install frontend AES and hand a
  temporary copy of `K` to the backend event loop. Any other value is an
  encrypted protocol rejection.
- Parse the original target RSA key, construct
  `RSA_target(K) + RSA_target(originalChallenge)`, write the response, and enable
  backend AES only from the successful write callback. Zero temporary key copies
  on every completion path.
- Do not call Velocity's Mojang `hasJoined` endpoint. The target validates the
  Mod's single client-owned session join.

### 6. Rejoin The Normal Velocity Lifecycle

- On target Login Success, pause backend reads and pass the authoritative target
  profile and Minecraft 26.2 session ID to the frontend handler.
- Add the minimum `ConnectedPlayer` profile replacement and relay-aware
  `AuthSessionHandler` path so normal profile/permission/Login/PostLogin and
  registration stages run once using the authenticated identity.
- Send frontend Login Success with that identity and target session ID, but skip
  the normal second `connectToInitialServer` call.
- After frontend Login Acknowledged, enter `ClientConfigSessionHandler`. When the
  client settings packet arrives, send backend Login Acknowledged, install the
  existing `ConfigSessionHandler`, forward settings, and resume backend reads.
- Leave existing configuration/play transition and `ServerPostConnectEvent`
  behavior in control after this synchronization point.

### 7. Keep Build And Plugin Boundaries Stable

- Keep only `server/releaseJar` and `server/runServer`; continue applying the
  patch under `plugin/build/server/source/` and running from `plugin/run/`.
- Preserve the plugin-owned post-connect AES verification message and unrelated
  command/automation code.
- Do not add multi-version source sets, Fabric API, proxy tasks, reconnect logic,
  online-backend switching, or a Velocity source tree.

## Focused Tests

- Mod tests call the production envelope helper with real encoded RSA keys and
  cover ordinary-key passthrough, decorated target-key extraction, malformed
  envelope results, and acknowledgement construction/bounds.
- The patched Velocity test calls the production relay utility with real proxy
  and target RSA key pairs. It constructs the decorated SPKI, produces standard
  Vanilla and Mod key responses, decrypts and classifies them, reconstructs the
  target response, and decrypts that response with the target private key to
  recover the original `K` and challenge.
- Assertions only compare observable results produced by those executed paths.
  Do not inspect private fields, source or patch text, Mixin registration, class
  presence/absence, constants, Gradle task graphs, or a reference checkout as a
  substitute for testing relay behavior.
- Verify Mixin wiring, cipher ordering, login-state continuation, and cleanup in
  the real login checks. Do not duplicate those state machines in test-only
  implementations.
- Do not add a logging test framework, broad Mixin tests, or repeat full
  repository review.
- Existing plugin Runtime-scenario tests are outside this relay change. Preserve
  them, but do not add to or rerun them as relay validation unless a touched
  shared production path requires it.

## Risk And Rollback Points

- Highest risk: provisional-player/backend state must be closed on every early
  disconnect and must never register before target Login Success.
- Ordering risk: both peers enable AES immediately after their respective key
  responses; moving either cipher installation earlier or later corrupts the
  stream.
- Compatibility risk: the carrier depends on SunRsaSign accepting non-canonical
  RSA AlgorithmIdentifier parameters. A parse/preservation failure must end as
  Vanilla/no-ACK rejection, not change ordinary unpatched-server behavior.
- Rollback is one patch file plus the scoped Mod login files; generated Velocity
  source under `plugin/build/server/source/` remains disposable.

## Targeted Validation After Approval

Run only the affected checks while iterating:

```text
.\gradlew.bat :mod:test :mod:build
.\gradlew.bat :plugin:releaseJar
```

`releaseJar` applies the unified patch only under
`plugin/build/server/source/` and writes release artifacts under
`plugin/build/server/release/`. `server/runServer` starts Velocity from
`plugin/run/`.

The final live checks are:

1. Vanilla 26.2 receives the explicit mod-required rejection.
2. Modded 26.2 reaches the configured online-mode backend.
3. The client receives the plugin's single AES encryption/decryption proof
   message after backend connection.
4. Modded 26.2 connects to an unpatched server normally.

Do not repeat a full repository audit for an equivalent narrow edit.

## Current Implementation Status

- The Mod was reduced to the decorated-SPKI envelope helper and one login Mixin;
  the rejected custom-payload and static-secret path was removed.
- The stored Velocity patch applies to commit
  `843a47e2a38325309cd66133149fc9a984f76bb8` and `:plugin:releaseJar`
  succeeds repeatedly from a fresh disposable checkout on Windows.
- Mod production-envelope tests passed with four real-key cases. The patched
  Velocity production relay test passed with three real-key RSA relay cases;
  targeted Checkstyle, `shadowJar`, and patch-integrity checks passed.
- Trellis check fixed the PostLogin/settings resume gate, provisional-player
  cleanup, temporary-secret cleanup, and repeatable generated-checkout deletion.
- A Java 25 TCP probe completed the real protocol-776 Vanilla flow, enabled
  AES/CFB8, and decoded the encrypted `FakePlayerProxy Mod is required to
  connect.` rejection from the running patched Velocity server.
- The copied Mod jar was removed from the user's isolated 26.2 Fabric instance
  and moved to the Recycle Bin. No further client files will change without
  explicit user approval.
- The real modded login did not complete. Backend login attempts ended with
  connection resets and later connection throttling. The supported login, proof
  message, and unpatched-server check remain unverified.
- The current stored Velocity patch changes 10 files. It contains 1,043 added
  lines and 135 removed lines. The approved technical comments account for the
  increase from the reduced logic patch.
- The reduced patch preserves the original non-relay backend handler. It removes
  unrelated formatting changes, duplicate RSA parsing and decryption, response
  wrapper classes, and packet accessors that the relay did not need.
- The reduced patch passed the production real-key relay test, Velocity compile,
  Velocity Checkstyle, Mod test/build, and a fresh isolated `:plugin:releaseJar`.
  It remains under user review and is not the accepted final patch.
- The comment review did not change relay logic. The comments now connect the
  SPKI carrier, both login connections, state ownership, cipher order, target
  session ID, configuration gate, and cleanup path.
- After the comment review, `:mod:compileJava` passed. Velocity main compile,
  test compile, main Checkstyle, and test Checkstyle also passed.
- The server-flow review replaced four backend relay booleans with one ordered
  `RelayState`. It replaced the configuration-started boolean with a one-shot
  future barrier for PostLogin completion and client settings.
- Focused Velocity compile, the production relay test, main and test Checkstyle,
  patch apply/reverse checks, and diff checks passed after this refactor. No full
  build, release task, or live server ran.
- The inspection cleanup now follows the two-case null contract. Strong
  non-null values have no redundant guards. Unannotated values keep local guards
  when the flow can provide null.
- The final full-scope check clears the temporary backend AES copy when event-loop
  transfer is rejected or the backend closes before processing.
- The plugin pins MCProtocolLib build 15 for Java 17 and resolves Netty
  `4.2.17.Final`. The focused Mod tests, plugin tests, and plugin jar build pass.

## Minimal Patch Map

- `LoginSessionHandler`: owns the ordered backend relay state, writes the target
  key response, and resumes the paused target configuration.
- `InitialLoginSessionHandler`: starts the one provisional backend, forwards the
  modified Hello, handles the frontend key response, and accepts target identity.
- `AuthSessionHandler`: reuses the provisional player, preserves the target
  session ID, and skips the second initial-backend connection.
- `ClientConfigSessionHandler`: joins `PostLoginEvent` and client settings with
  a one-shot future barrier before it resumes the target.
- `ConnectedPlayer`: replaces the provisional profile and opens the internal
  pre-registration backend connection without post-login selection events.
- `EncryptionRequestPacket`: accepts the observed 294-byte target SPKI.
- `EncryptionResponsePacket`: constructs the standard outbound target response.
- `ServerLoginSuccessPacket`: exposes the target session ID.
- `FakePlayerProxyRelay`: constructs the decorated SPKI, classifies the response,
  and performs the RSA encryption operation that Velocity does not provide.
- `FakePlayerProxyRelayTest`: executes those production operations with real RSA
  keys and verifies the target plaintext.
