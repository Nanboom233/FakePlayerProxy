# Auto-reconnect

## Goal

Design FakePlayerProxy auto-reconnect for a Shadow backend. Use session-scoped
authorization that a connected mod client explicitly grants.

This child task must establish the protocol, lifecycle, security,
disconnect-classification, and implementation boundaries before production
code changes start.

## Background

- A player connects through Velocity with the FakePlayerProxy client mod.
- `/fpp auto-reconnect on` enables the feature for that connection.
- Velocity sends a custom payload requesting the authorization material needed
  for a later backend login.
- The client must explicitly consent before returning any credential or proof.
- If the shadow backend is disconnected, Velocity may reconnect it while the
  proxy holds the access token.
- Duplicate-login and server-ban disconnects must not trigger reconnection.

## Requirements

1. Identify the exact credential or proof required for an online-mode backend
   join in the project's pinned Minecraft protocol. Distinguish Microsoft OAuth
   tokens, Minecraft services access tokens, and session-server join proofs.
2. Determine whether the current client can obtain and provide the required
   authorization, including its scope, lifetime, account binding, and server
   binding.
3. Evaluate whether transferring a bearer token to Velocity is necessary and
   acceptable, or whether a narrower delegated proof can support reconnecting
   after the frontend client is no longer available.
4. Trace the current Velocity patch and plugin lifecycle from shadow takeover
   through backend disconnect. Identify the state that survives, the state that
   must be rebuilt, and the APIs or patch points required to start a new backend
   login for the same player.
5. Determine which disconnect causes can be classified reliably. Specifically
   verify whether duplicate-login and ban disconnects have stable protocol
   identities rather than server-defined or localized text.
6. Define the lifecycle for enablement, consent, credential retention, retry
   timing, session-service rejection, disablement, shadow exit, and cleanup.
7. Verify the custom-payload direction, registration timing, size limits, and
   trust boundary needed for the request and response.
8. Record feasibility and blockers before implementing the feature.
9. Define a patch for the common backend channel output boundary with one
   priority list. The priority list must delay backend Login writes instead of
   rejecting them. Callers must not identify their login path or implement
   separate throttle logic.
10. Trace the complete `AutomationManager`, `AutomationService`, `Player`, and
    `World` lifecycle across backend loss, retry wait, Login, CONFIG, PLAY, and
    resumed automation. Define which state stays, pauses, resets, or rebuilds.
11. Preserve the exact plugin `Player` and `AutomationService` during reconnect.
    Do not replace them after the reconnected backend reaches ready PLAY.
12. Keep `/player kill` available during reconnect. Keep other backend actions
    unavailable until the new PLAY state is ready.
13. Log each important auto-reconnect state change. Logs must identify the
    player, backend, attempt number, event category, result, and next delay
    when applicable. Logs must never contain a token or credential payload.
14. Define the exact plugin, mod, resource, and Velocity patch file ownership.
    Do not add a configuration file or a separate reconnect service.
15. Define the complete `/fpp auto-reconnect on|off` interaction, including
    repeated commands and asynchronous completion.
16. Define every new player-facing English and Simplified Chinese translation.
    The token consent text must state the token use, account authority, memory
    storage, and cleanup condition. Show a red trust warning. Do not show a
    server or proxy address.
17. Put the auto-reconnect consent screen in one class under the Mod `gui`
    package. Keep packet handling and credential access outside that class.
18. Encode each headless CONFIG response with the backend CONFIG registry.
    The retained frontend can remain in PLAY during this operation.
19. Catch and log each reconnect packet failure at its EventLoop owner.
    No reconnect packet exception can escape to Netty task handling.
20. Verify the complete live reconnect path through Known Packs and ready PLAY.

## Acceptance Criteria

- [x] Research cites the pinned repository code and authoritative protocol or
      library sources.
- [x] The report names the exact authorization material required for reconnect
      and explains whether an access token alone is sufficient.
- [x] The report maps the current shadow/backend lifecycle and the minimum
      Velocity/plugin changes required for a fresh backend login.
- [x] The report explains which kick causes can and cannot be classified
      reliably, including duplicate-login and ban cases.
- [x] The report provides a security and lifecycle assessment for holding the
      authorization in proxy memory.
- [x] The report gives a clear feasibility verdict and records all resolved
      product decisions.
- [x] The report identifies the common backend pipeline patch point and shows
      how it preserves write order, cancellation, and timeout behavior.
- [x] The report defines the exact retry delays and their reset behavior.
- [x] The report defines real-player and auto-reconnect priority without a new
      login-path type.
- [x] The report defines the headless CONFIG policy for new required resource
      packs, Code of Conduct, and Transfer.
- [x] The report defines the required operational logs and forbidden sensitive
      log content.
- [x] The report defines the implementation file structure and ownership.
- [x] The report defines the complete command and client consent interaction.
- [x] The report defines the exact English and Simplified Chinese text set.
- [x] The Mod uses a separate auto-reconnect consent screen class under `gui`.
- [x] A headless Known Packs response uses the backend CONFIG registry while
      the retained frontend remains in PLAY.
- [x] A reconnect packet failure stays inside its EventLoop owner and produces
      an operational log without credential data.
- [ ] A live reconnect passes Known Packs, reaches ready PLAY, and resumes the
      retained automation service.

## Out of Scope

- Persisting access tokens or refresh tokens across proxy/player sessions.
- Supporting clients without the FakePlayerProxy mod.
- General-purpose account credential storage.
- Handling third-party CONFIG plugin protocols.

## Confirmed Decisions

- Patch the common `BackendChannelInitializer` output boundary.
- Use one priority list for backend Login writes.
- Do not add login-path types or caller-specific throttle branches.
- Use Netty `PendingWriteQueue` to preserve pending writes and promises.
- Remove the isolated four-second Transfer delay after the common priority list
  owns that interval.
- Treat only recognized duplicate-login and server-ban kicks as commands to
  disable auto-reconnect.
- Disabling auto-reconnect clears the held Minecraft access token.
- `/player kill` disables auto-reconnect before it closes the Shadow backend.
  It clears the token and cancels all reconnect work.
- Retry immediately after backend loss. After each failed attempt, wait 10,
  10, 30, 30, 60, 60, and then 300 seconds for every later attempt.
- Do not set an attempt limit. Stop when the feature is disabled or the token
  can no longer authorize a login.
- Treat authlib credential and account rejection exceptions as proof that the
  held authorization cannot complete Login.
- Keep retrying after `AuthenticationUnavailableException`, HTTP 429, or an
  unknown authentication failure.
- Give real-player backend Login channels the highest waiting priority.
- Give Shadow auto-reconnect channels the lower waiting priority.
- Bind authorization to the authenticated player session and the enabled
  auto-reconnect state. Do not bind it to one backend address.
- Keep the authorization active across normal backend server switches in the
  same player session.
- Every `/fpp auto-reconnect on` request must ask the client for consent again.
- Do not save the consent decision. A decline keeps auto-reconnect disabled and
  stores no access token.
- Auto-reconnect starts only after Velocity receives a well-formed response with
  a non-empty access token.
- Do not put a nonce, request ID, or pending state in the payload or service.
- Store one `autoReconnect` boolean in `AutomationService`. A valid non-empty
  token response sets it to true. Disable and terminal paths set it to false.
- Derive backend reconnect progress from the backend connection, future,
  `inGame`, and `playerLoaded`.
- Show player text only when auto-reconnect becomes enabled or disabled. A
  decline or invalid response writes no player text.
- The proxy does not calculate or track token expiry. Session-service rejection
  applies the approved terminal or retry policy.
- Preserve the exact plugin `Player` and `AutomationService` while reconnecting.
- Freeze automation during reconnect. Rebuild backend-derived state before
  automation resumes.
- Keep reconnect state in the retained `AutomationService`. Do not add a
  separate reconnect service.
- Derive reconnect readiness from the backend connection, reconnect future,
  `inGame`, and `playerLoaded`. Do not add a reconnect phase.
- Use the retained automation tick to check the next retry due time. Do not add
  a second retry scheduler.
- Reset the retry sequence only after ready PLAY. Login Success and Join Game
  are not ready states.
- A new required resource pack disables auto-reconnect and clears the token.
- Every Code of Conduct request disables auto-reconnect and clears the token.
  Do not track or reuse a user's earlier acceptance.
- A backend Transfer packet disables auto-reconnect and clears the token.
- A new optional resource pack can be declined without disabling
  auto-reconnect.
- Do not add special handling for third-party CONFIG plugin messages.
- Put auto-reconnect's common backend priority gate in
  `plugin/patch/0003-login-session.patch`. As an ownership correction,
  `0001-login-relay.patch` starts Transfer on the connection EventLoop without
  its former private delay, while `0003` alone owns the four-second wait.
  `0002-automation-extension.patch` keeps its functional changes but contains no
  EOF-only hunk and preserves each source file's original EOF newline state.
- Keep `FakePlayerProxyPlugin` limited to plugin initialization and shutdown.
- Move all state forwarding to `utils/EventHandler`.
- Put consent request and response ownership in `utils/AuthManager`.
- Put the auto-reconnect consent screen in
  `com.fakeplayerproxy.mod.gui.AutoReconnectConsentScreen`.
- Let the screen own its text and boolean choice only. Keep token access,
  packet send, and previous-screen restoration in `MixinClientPacketListener`.
- Put player lifecycle, terminal packet policy, authentication failure policy,
  and exact cleanup in `AutomationManager`.
- Do not impose an independent token retention limit. Keep the token until
  auto-reconnect is disabled, a terminal condition occurs, or the session
  service rejects the credential or account.


