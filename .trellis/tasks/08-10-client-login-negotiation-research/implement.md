# Final Review Correction Plan

The connection protocol, Vanilla Transfer fallback, raw tunnel, Mod consent UI,
and envelope format already exist. This pass fixes only the confirmed review
defects in the current Mod, Plugin proof message, and stored Velocity patch. It
does not add a protocol branch, state machine, wrapper, configuration option, or
test class.

The user approved the revised artifacts before product code changes resumed.

## 1. Correct The Mod Mixin Bindings

1. In `MixinClientHandshakePacketListenerImpl`, annotate the final Minecraft
   fields `connection`, `parent`, and `serverData` with `@Shadow @Final`.
2. Do not shadow the Minecraft singleton. Continue to use
   `Minecraft.getInstance()` where the public singleton is the correct owner.
3. Rename the first parameter of the shadowed `setEncryption` method to the
   target name `setKeyPacket`.
4. Capture the six `handleHello` locals by their target names:
   `decryptCipher`, `encryptCipher`, `digest`, `secretKey`, `publicKey`, and
   `challenge`. Remove ordinal/index capture while those names are available.
5. Do not put `@NotNull` on shadows, injector parameters, captured locals, or
   override inputs whose target contract does not require it. Preserve the
   effective `@NullMarked` return contract of Minecraft's screen package with
   `public @NotNull Component getNarrationMessage()`. Keep annotations on
   project-owned declarations when their project-owned callers guarantee the
   value.

These are binding and static-contract corrections. They do not create a
dedicated annotation or Mixin-shape test.

## 2. Simplify The Consent Continuation

1. Remove the redundant `LoginChoice` record. Apply the shared Java guideline
   for this simple two-value result; do not introduce another dedicated record
   or interface solely to carry the digest and key packet.
2. Keep `PreparedLogin`; it is the immutable callback value that owns both
   choices, both ciphers, and the authentication flag.
3. Keep the injector name `prepareConsentChoices`. It describes the handler's
   real action and needs no manual `fakePlayerProxy$` prefix.
4. Keep `openConsentScreen` as the game-thread and screen-owner boundary. It is
   large enough to improve the caller's readability and isolates a distinct
   responsibility.
5. Rename the selection callback to `continueLoginAfterConsent` and inline the
   one-use authentication/encryption helper into it. Do not split the same
   one-shot continuation across two methods.
6. Preserve the existing `authenticateServer` and `setEncryption` calls,
   generated AES key, ciphers, Allow/Decline choice, Escape behavior, and
   unpatched-server path.

Do not add re-entry, a stored Hello, a consent session, a state enum, a packet
Mixin, or another screen wrapper while simplifying this code.

## 3. Make Mod Failures Precise And Localized

1. Remove `failConsent(String, Throwable)` and every call that passes `null`.
2. At each validation failure, log the exact operation and observed condition.
   In particular, name the acknowledgement/challenge bound or the actual active
   screen type instead of logging only `Invalid` or `Unable`.
3. At each catch site, log the exact failed stage and pass the complete
   `Throwable` to the logger.
4. Reuse one no-argument disconnect helper only because several failure sites
   need the same localized disconnect action. It does not own diagnostic text.
5. Add `fakeplayerproxy.disconnect.proxy_connection_failed` to `en_us.json` and
   `zh_cn.json`, and use `Component.translatable` for the disconnect.
6. Keep malformed or unmarked envelope data on Minecraft's original login path;
   do not turn the optional decode result into a disconnection.
7. Update only comments affected by the simplified flow. Comments must explain
   the injection boundary, ownership, selection, authentication, and cleanup
   reason as one readable sequence.

Do not add per-branch translation keys or tests for log wording.

## 4. Correct Velocity Failure And Thread Ownership

All Velocity edits are made in `plugin/patch/0001-server-hello-marker.patch`.
The applied source under `plugin/build/server/source/` remains disposable.

1. In `LoginSessionHandler`, replace every `failRelay(..., null)` with a concrete
   validation log followed by a no-Throwable cleanup path. Catches log their
   complete exception before cleanup.
2. Make the validation logs identify the specific missing or mismatched owner:
   pending target Hello, frontend secret, response write, active handler, cipher
   pipeline, result future, target profile fields, or secret length.
3. Do not let `failRelay` return merely because the result future is already
   complete. Future completion is idempotent; the handler must still close the
   backend connection it owns.
4. In `InitialLoginSessionHandler`, remove the hard-coded `RELAY_FAILURE`
   component. Reuse `ConnectionMessages.INTERNAL_SERVER_CONNECTION_ERROR`, and
   log the exact response classification, secret length, handler, player,
   backend, or login-state mismatch at its owning branch.
5. In `TransferTunnelLoginSessionHandler`, remove the hard-coded
   `TUNNEL_FAILURE` and every `failBeforeHandoff(null)` call. Validation sites
   log their concrete reason, exception sites retain the `Throwable`, and one
   cleanup method closes both legs with the existing translatable Velocity
   message.
6. In `ClientConfigSessionHandler`, join successful `PostLoginEvent` completion
   and the first settings packet with `thenAcceptBothAsync` on the frontend event
   loop. A Plugin-completed future must not mutate the connection from an
   arbitrary Plugin thread. Log the concrete missing backend or handler mismatch
   before closing an invalid continuation.
7. Keep direct `ByteBuf` transfer in `RawTunnelForwardHandler`. Do not release an
   unknown pre-handoff buffer inside `handleUnknown`; `MinecraftConnection`
   already owns that release after the handler returns. Make raw-channel logs
   identify the failed side and write/close operation instead of reporting only
   a generic tunnel failure.

No new fallback branch, failure component, timeout, or lifecycle flag is added.

## 5. Remove Only Confirmed Patch Redundancy

1. Narrow `RegisteredServer` to `VelocityRegisteredServer` at the caller with a
   pattern match and the existing stable failure. Let
   `connectToInitialServerForRelay` take the concrete type; remove the expected-
   flow `IllegalArgumentException`.
2. Inline the one-use `AuthSessionHandler.startInitialServerSelection` body into
   its owning branch.
3. Inline `removeFrontendCodecs` and `removeTargetCodecs` into
   `installRawTunnel`; keep the reused `removeIfPresent` helper.
4. Represent stateless `AuthSessionHandler.InitialServer` as an enum singleton
   to avoid a per-login allocation.
5. Keep the sealed `Continuation`, `TargetHello`, and `ResponseKind`. Their
   variants own different lifecycle actions or protocol results, so replacing
   them with generic containers, nullable fields, or booleans would lose meaning.
6. Keep `FakePlayerProxyRelay`'s checked `GeneralSecurityException` boundary,
   but replace `Invalid relay public key` with diagnostics that name the proxy
   key type, absent target key, encoded length, or parse stage that actually
   failed.
7. Update `HandshakeSessionHandler`'s class documentation to include the
   `TRANSFER` raw-tunnel entry. Preserve the continuous flow comments in the
   other changed classes; do not reformat or rewrite untouched Velocity code.

## 6. Localize The Existing Proof Message

1. Change only `FakePlayerProxyPlugin.onServerPostConnect` from literal proof
   text to the translatable key `fakeplayerproxy.message.encryption_verified`.
2. Add the English and Chinese values to the Mod's existing language files and
   keep the green style and one-message behavior.
3. Do not change Plugin commands, automation text, raw-tunnel logic, or any
   other Plugin behavior.

## 7. Keep Existing Tests On Production Logic

1. Add no test class.
2. Keep `ServerHelloPacketEnvelopeTest` on the real production envelope parser
   with real encoded RSA keys. Adapt it only if a production signature changes.
3. Keep `FakePlayerProxyRelayTest` on real proxy/target RSA construction,
   response classification, target response recovery, and bounds.
4. Keep `FakePlayerProxyTransferTunnelTest` on the production framed handshake,
   Login Start handoff, raw bytes, close propagation, and target-connect failure.
5. Do not add tests for annotations, local names, shadow parameter names,
   value-container choice, helper inlining, enum/record shape, comments, log
   wording, translation-key shape, executor choice, private fields, source text,
   or class presence.

## 8. Delay Vanilla Transfer By Four Seconds

1. Change only the `AuthSessionHandler.startTransfer()` hunk in
   `plugin/patch/0001-server-hello-marker.patch` and its required `TimeUnit`
   import.
2. Keep the existing `PostLoginEvent` future chain. Run the current Transfer
   action through `CompletableFuture.delayedExecutor(4, TimeUnit.SECONDS,
   mcConnection.eventLoop())` after that event completes successfully.
3. Keep the existing `mcConnection.isClosed()` guard in the delayed action. A
   closed frontend produces no Transfer and needs no separate timer cleanup.
4. Add a human-readable comment that explains why both target connections share
   Paper's IP throttle, why the first backend is already closed, and why the full
   four-second wait is required. The comment may cite
   `research/paper-connection-throttle.md`, but it must state the reason locally.
5. Do not add a delay setting, state field, helper method, retry parser, blocking
   sleep, or a new error path. Vanilla clients and Mod clients that choose
   Decline already share this continuation.

## 9. Run Only Targeted Validation

After the Mod correction:

```powershell
.\gradlew.bat :mod:build
```

After the stored Velocity patch or Plugin proof changes:

```powershell
.\gradlew.bat :plugin:releaseJar
Push-Location plugin/build/server/source
.\gradlew.bat :velocity-proxy:test --tests "com.velocitypowered.proxy.protocol.util.FakePlayerProxyRelayTest" --tests "com.velocitypowered.proxy.connection.client.FakePlayerProxyTransferTunnelTest"
.\gradlew.bat :velocity-proxy:checkstyleMain
Pop-Location
```

The build recreates and applies the stored patch only under
`plugin/build/server/source/`. Do not create another worktree, edit the generated
source as a second implementation, run the full Velocity test suite, repeat a
full repository review, or run unrelated Plugin tests.

The delay uses an existing asynchronous production path and does not add a new
unit-test category. After rebuilding and restarting the patched server, perform
one focused Vanilla live retry and verify that Transfer occurs no sooner than
four seconds after successful PostLogin handling and that the target does not
return Paper's connection-throttle disconnect. Then continue the approved live
matrix once; do not repeat unaffected cases for this internal timing change.

Run the already approved live matrix once when all corrections are ready:
Vanilla Transfer/raw tunnel, Mod Allow, Mod Decline, Escape, and a Mod connection
to an unpatched server. Do not repeat the matrix for each internal refactor.

## Review Gate

- `prd.md`, `design.md`, and this plan preserve the existing packet carrier, ACK
  bytes, AES/cipher ordering, Transfer endpoint, raw tunnel, consent copy, screen
  behavior, and unpatched-server behavior.
- The task adds no proxy feature, configuration, state machine, wrapper, broad
  modernization, or new test category.
- The fixed delay changes only when the existing Vanilla/Decline Transfer is
  sent; it does not change classification, the Transfer endpoint, or raw bytes.
- The only Plugin behavior change is localization of the existing proof message.
- `implement.jsonl` and `check.jsonl` load the Mod, Velocity, Java-language, and
  research contracts without adding a validation inventory.
- The implementation stayed inside the approved patch, Mod, localization, and
  proof-message surfaces without adding another protocol branch or test category.

## Completion Evidence

- `:mod:build` passed after the Mod review corrections.
- `:plugin:releaseJar` recreated the disposable Velocity checkout, applied the
  stored patch, and built the release artifacts.
- `FakePlayerProxyRelayTest` and `FakePlayerProxyTransferTunnelTest` passed on
  the applied production code.
- `:velocity-proxy:checkstyleMain` passed.
- The user confirmed the rebuilt Vanilla Transfer/raw-tunnel connection passed
  live testing with the four-second cooldown and no remaining throttle failure.
