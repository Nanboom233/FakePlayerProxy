# Auto-reconnect implementation plan

## Preconditions

- The user approved this plan. Keep implementation inside its listed scope.
- Read the complete Java and Velocity plugin specifications before editing. Do
  not rely on truncated context injection.
- Create `plugin/patch/0003-login-session.patch` after the existing patches.
- Limit existing-patch edits to the approved ownership correction: `0001`
  starts Transfer directly on the connection EventLoop, `0003` alone owns the
  common four-second backend wait, and `0002` preserves its functional changes
  without EOF-only churn.
- Keep authorization handling in `AuthManager`.
- Keep retry timing and automation gating in `AutomationService`.
- Keep terminal policy and terminal lifecycle logs in `AutomationManager`.
- Keep backend queuing, same-target reconnect, online-mode authentication,
  source packet identity, and headless CONFIG in `0003`.
- Keep consent UI and access-token extraction in the Mod.
- Preserve unrelated working-tree changes.
- Do not add a configuration file, service, provider, scheduler, or custom
  result type.

## Ordered work

### 1. Update the shared packet event identity

Add `ClientboundPacketEvent`, `PacketEventHandler`, and `MinecraftDecoder`
changes to `plugin/patch/0003-login-session.patch`.

Add the exact source `ServerConnection` to `ClientboundPacketEvent`.
Pass the existing `VelocityServerConnection` association from the decoder to
the event. Expose only an exact source comparison operation. Keep the
serverbound event unchanged.

Move the existing configuration and packet-state handlers from
`FakePlayerProxyPlugin` to `utils/EventHandler`. Compare the event source with
the current backend before each clientbound state update.

Update the existing `PacketEventTest`. Verify that the event accepts the exact
source and rejects another source. Do not add a new test class, reflection, or
source-text checks.

### 2. Add the common backend priority list

Add the `BackendChannelInitializer` change to `0003`.

Add private nested priority-list and outbound-gate classes. Use one shared map
keyed by the resolved remote IP and port. Use high and low FIFO lists.

Use `PendingWriteQueue` for pending packet objects and promises. Detect low
priority from the associated `VelocityServerConnection.isLogoutCancelled()`.

Release one channel every four seconds. Remove a closed waiting channel and
fail all pending writes. Preserve the four-second slot after a released channel
closes.

Remove the separate delayed executor from `AuthSessionHandler.startTransfer()`.

Add one focused backend gate test. Verify high-before-low selection, FIFO order,
message order, and waiting-channel close cleanup in the same test class.

### 3. Add the token-backed backend Login path

Add the pinned Velocity version catalog and proxy build changes to `0003`.
Add authlib 9.0.75 as one proxy runtime dependency.

Edit `VelocityServer` to own one `MinecraftSessionService`. Create it through
`YggdrasilAuthenticationService.createOffline(Proxy.NO_PROXY)`. Expose the
backend join operation instead of a session-service getter.

Add the `ConnectedPlayer.ConnectionRequestBuilderImpl` and
`VelocityServerConnection` changes to `0003`.

Add one patched `ConnectedPlayer` operation for a dead Shadow same-target
reconnect. Reuse the existing builder and connection future. Permit the
same target only when the old Shadow backend is inactive and no connection is
in flight.

Pass one cloned token byte array into the new backend
`VelocityServerConnection`. Set `logoutCancelled=true` before connect. Add one
take operation that transfers and clears this temporary token.

Add the `LoginSessionHandler.handle(EncryptionRequestPacket)` change to `0003`.
Keep the current initial relay branch unchanged. Add the reconnect branch only
when the server connection supplies a token.

Generate a fresh AES key and exact server digest. Run authlib session join
outside the EventLoop. Return to the backend EventLoop before packet writes.
Verify the active handler and connection future before each state change.

Write the encrypted key response before encryption starts. Clear the temporary
token bytes and AES bytes on each success or failure path. Complete the existing
connection future exceptionally for authlib and cryptographic failures.

Add one focused Login handler test with a mocked session service. Verify the
profile UUID, token, digest, response ordering, encryption ordering, and
terminal versus retryable exception propagation. Keep these cases in one test
class.

### 4. Complete headless CONFIG

Add the `LoginSessionHandler` and `ConfigSessionHandler` changes to `0003`.

After reconnect Login Success, install CONFIG without waiting for the closed
frontend handler.

For a previously applied resource-pack hash, send Accepted, Downloaded, and
Successful. Decline a new optional pack. Let `AutomationManager` clear
authorization and close a new required pack.

Apply resource-pack removal to the retained resource-pack handler. Do not write
that removal to the closed frontend.

On Finished Update, set the backend codecs to PLAY. Write Finished Update and
install the existing `TransitionSessionHandler`. Do not duplicate transition
attachment.

Add focused CONFIG cases to the Login handler test or one existing suitable
backend test. Cover an applied pack, an optional pack, and headless Finished
Update. Cover a required pack in `AutomationManagerTest`. Do not test private
field shape.

### 5. Add plugin consent and reconnect state

Edit `FakePlayerProxyPlugin`, `FppCommand`, `AutomationManager`,
`AutomationService`, and Plugin `Player`. Add `utils/EventHandler` and
`utils/AuthManager`.

Register and unregister `fakeplayerproxy:auto_reconnect_v1` in
`FakePlayerProxyPlugin`. Register `EventHandler`, `AuthManager`,
`AutomationManager`, and the existing `PermissionProvider` there. Keep only
plugin initialization and shutdown in the main class.

Move the existing configuration, post-connect, and packet-state event methods
to `EventHandler`. Move their existing `withPlayer` flow with them. Use the
manager source overload for clientbound events. Do not add a listener base
class.

Move PostLogin and Disconnect ownership to `AutomationManager`. Add its terminal
packet handlers there.

Define the channel identifier, consent request, disable operation, and
plugin-message handler in `AuthManager`. Mark its plugin-message event handled
before source validation. Decode the short payload at that event boundary.

Edit the `FppCommand` constructor call in `FakePlayerProxyPlugin`. Pass the
`AuthManager`. Add the player-only static Brigadier branch.

Call `AuthManager` from the inline `on` and `off` execution blocks. Let
`AuthManager` resolve the exact service, check both connections, and send the
request. Add no reconnect-channel branch.

Add one `autoReconnect` boolean, token, attempt number, retry time, and reconnect
future to `AutomationService`. A valid non-empty token response sets the boolean
to true. Disable and terminal paths set it to false. Add no pending state,
feature-state enum, reconnect phase, or other reconnect-state field.

Change the retained service tick to require an active backend, `inGame`, and
`playerLoaded` before action timing advances. Submit an immediate first attempt,
then use 10, 10, 30, 30, 60, 60, and repeated 300-second delays. Poll the one
stored future on the retained EventLoop.

Make the service tick return the unwrapped reconnect failure cause when one
exists. Return null when no failed reconnect completes. Do not add a terminal
flag or result type.

Change `AutomationManager.tick(...)` to keep an eligible reconnecting Shadow
when its backend is absent. Keep the exact map entry and existing tick task.

Add one terminal manager operation. Remove the exact map entry before service
close. Reuse it for `/player kill`, terminal packet events, and a terminal
failure cause.

Remove input clearing from Player backend-session and spawn resets. Keep normal
configuration behavior by clearing input explicitly in the normal service
path. Keep scheduled actions unchanged during reconnect CONFIG and PLAY.

Update `runAction(...)` to require an active backend, `inGame`, and
`playerLoaded`. Keep `/player kill` available while the service waits or
connects. Keep reconnecting Shadow names in the existing target suggestion
list.

Update existing `AutomationServiceTest`, `AutomationManagerTest`,
`FppCommandTest`, `PlayerCommandTest`, and `PlayerTest` only where their current
owner changes. Cover repeated consent, disable cleanup, retry delays,
action freeze, ready resume, retained map ownership, kill, and input intent.

Do not add tests for constants, field shape, source text, or translation-key
presence.

### 6. Add terminal packet policy and logs

Add the synchronous terminal clientbound handlers to `AutomationManager`.

Handle common and Login Disconnect packets. Match only the three approved root
translation keys. Treat all other Disconnect packets as retryable.

Handle required resource-pack push, every Code of Conduct packet, and every
Transfer packet as terminal. Verify the exact source backend before cleanup.

Return an unwrapped failed future cause from `AutomationService` without
classification. Classify it in `AutomationManager`. Clear the token for the
four approved credential and account exceptions. Retry service unavailability,
HTTP 429, and unknown authentication failures.

Add consent logs to `AuthManager`. Add retry and terminal logs to
`AutomationManager`. Use fixed categories. Do not pass credential-bearing
exception messages or HTTP bodies to the logger.

Add one focused `AuthManagerTest` for payload source rejection,
handled-before-parse behavior, empty data, malformed data, and valid token
enablement. Add terminal packet cases to the existing
`AutomationManagerTest`. Do not add a test only for moving existing handlers to
`EventHandler`.

### 7. Add the client payload and consent screen

Add `AutoReconnectPayload` under the existing mod `packets` package. Use one
final class for both directions. Add bounded request and response codecs.

Add the two custom-payload codec Mixins and `MixinClientPacketListener`.
Register them in `fakeplayerproxy-mod.mixins.json`.

Add `AutoReconnectConsentScreen` under the existing Mod `gui` package. Extend
Vanilla `ConfirmScreen`. Keep translated text and the boolean choice callback
in this class. Do not put the connection, payload, access token, or previous
screen in this class.

Let `MixinClientPacketListener` capture the previous screen and install the new
screen. Allow reads and sends the current Minecraft access token. Decline and
Escape send an empty token. Restore the previous screen after the response.
Add no nonce, countdown, or saved decision.

Add the approved English and Simplified Chinese consent title, body, and red
warning. Do not add a server address. Add only enabled and disabled command
text. Reuse the existing Allow and Decline button keys.

Add one payload codec test. Cover the empty request, allow response, decline
response, token bound, malformed UTF-8, and trailing bytes in that class.
Do not add a screen-shape test.

### 8. Update specifications and operator documentation

Edit `.trellis/spec/frontend/fabric-client-mod.md`. Add the PLAY payload,
explicit token consent, codec Mixin, no-time-limit request, and no-persistence
contract.

Edit `.trellis/spec/backend/velocity-plugin.md`. Replace the no-reconnect
invariant. Add the derived readiness rules, authlib join, priority list, source
connection identity, terminal policy, and cleanup order.

Edit `docs/product/operation-guide.md`. Document command use, token custody,
retry delays, stop conditions, and log categories. Do not expose protocol
internals as user instructions.

### 9. Regenerate and verify the Velocity patch

Apply the ownership-corrected `0001` and EOF-clean `0002` to a disposable
pinned Velocity source.
Implement the Velocity changes there. Generate
`plugin/patch/0003-login-session.patch` from only this task's changes. Update
the patch README. Add the explicit `0003` include to `plugin/build.gradle.kts`.
Keep the sorted Grgit apply path and build the patched Velocity JAR.

Do not stop an existing Velocity process. If its JAR is locked, use the current
project workaround that excludes host assembly for plugin-only tests.

Run focused patch tests for packet identity, priority gating, Login auth, CONFIG,
and the existing Transfer tunnel.

Run focused plugin tests for `AutomationServiceTest`, `AutomationManagerTest`,
`FppCommandTest`, `PlayerCommandTest`, `PlayerTest`, and `AuthManagerTest`.

Run the mod payload codec test and compile the mod.

Run `git diff --check` on the task files and all implementation files. Inspect
`0003` for unrelated changes, generated source, token fixtures, or credential
text.

### 10. Repair the live headless CONFIG failure

Change the Known Packs response to the direct backend CONFIG encoding path.
Do not change normal frontend-routed packet encoding.

Contain response write failures inside the owning EventLoop. Log the player,
backend, packet operation, attempt number, and failure. Do not log credentials.

Remove the production `requireNonNull` added by `0003`. Review each changed
Java file against `.trellis/spec/language/java.md`. Resolve each IDEA report at
weak warning level or higher.

Remove the two new pure field getters from the patch design. Do not add Lombok
to Velocity for these accessors. Let `VelocityServer` expose the backend session
join operation instead of its session-service field. Let
`ClientboundPacketEvent` compare one candidate connection with its source.
After that comparison succeeds, the Plugin uses the player's current backend.

Add one focused encoder case to an existing suitable test class. Keep the
frontend in PLAY and the backend in CONFIG. Send
`ServerboundSelectKnownPacks` through `MinecraftConnection.sendPacket()` and
verify that CONFIG encoding succeeds. Do not add a new test class.

Update the existing automation test for the EventLoop failure boundary. Make a
response write fail and verify that the failure does not escape the EventLoop.
Do not add a test for a field, getter, constant, source text, or log wording.

Run the focused Mod build and affected Plugin tests. Run the focused patched
Velocity tests. Then run one live sequence: enable, consent, shadow, backend
loss, reconnect, Known Packs, ready PLAY, and resumed automation.

## Risk points

- A session join must never block an EventLoop.
- Backend encryption must start only after the plaintext response write.
- An old backend packet must not update reconnected backend state.
- A terminal path must remove the manager entry before it cancels the tick.
- A reconnect must not advance scheduled action delays before ready PLAY.
- The backend priority gate must preserve every message and promise.
- No token copy may enter a log, task artifact, fixture, or generated patch
  comment.

## Execution gate

The user approved this design and plan. Run implementation and review in the
listed order.
