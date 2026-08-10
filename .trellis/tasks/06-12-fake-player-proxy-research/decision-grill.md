# Decision Grill

This file records the remaining product and risk decisions that cannot be
settled from source inspection alone. Each question includes the recommended
answer, why it matters, and what changes if the user chooses differently.

These are not blockers for completing the research task. They are gates before
starting implementation tasks.

## 1. Upstream Server Policy

Decision: should MVP support only controlled/authorized upstream servers, or
attempt arbitrary public online-mode servers?

Recommended answer: controlled or explicitly authorized servers only.

Why: the proxy stores automation config, may store auth material, sends bot-like
packets, and can reconnect without a local client. That has server-policy,
anti-cheat, abuse, and account-risk implications outside Velocity.

If choosing arbitrary public servers:

- Add server-policy consent and audit copy.
- Add per-target automation allow/deny rules.
- Expect anti-cheat false positives and server disconnects.
- Do not claim compatibility with public servers.
- Add stronger rate limits and abuse-prevention defaults.

Implementation impact:

- `SecurityConfig.allow_arbitrary_public_servers` defaults false.
- `TargetServerConfig.authorizedOnly` defaults true.
- `OwnershipPolicy` must deny unknown target servers.

## 2. Minecraft Version Range

Decision: should MVP support one Java Edition minor, a range, or "latest only"?

Recommended answer: one current minor first, selected at child-task start.

Why: login/configuration/play states and serverbound packet shapes move across
Minecraft versions. MCProtocolLib packages and packet constructors can also
shift.

If choosing a range:

- Add `ProtocolVersionAdapter`.
- Add per-version packet emitter tests.
- Add integration matrix for at least the oldest and newest supported versions.
- Increase maintenance cost for every command.

Implementation impact:

- `protocol` package owns all MCProtocolLib version-specific code.
- `command` and `automation` packages must stay version-neutral.

## 3. Reconnect Auth Risk

Decision: should MVP store refresh-capable Microsoft auth material?

Recommended answer: no for MVP. Start with offline/owned forwarding and an
access-token-only spike for a test account.

Why: refresh-capable auth material is high-sensitivity account material. It
requires consent copy, encryption, revocation, audit, log redaction, and
operator policy review before being safe enough to ship.

If choosing refreshable auth in MVP:

- Implement `SecretStore` before online reconnect.
- Require operator-provided master key or external secret provider.
- Add revocation UI and admin command.
- Add tests proving no token appears in logs/status/audit/errors.

Implementation impact:

- `REFRESHABLE_MICROSOFT_AUTH` remains disabled unless `auth.allow_refreshable_microsoft_auth=true`.
- `NoopSecretStore` must disable persistent reconnect credentials.

## 4. Handoff Semantics

Decision: is "re-login shadow" acceptable for MVP, or is no-drop live takeover
required?

Recommended answer: re-login shadow for MVP.

Why: Velocity's backend connection lifecycle is tied to `ConnectedPlayer`.
Current internals tear down backend connections when the real client disconnects.
MCProtocolLib automation can prove product value without patching Velocity.

If choosing no-drop handoff:

- Create `velocity-handoff-patch-spike` before promising MVP.
- Expect Velocity fork/patch maintenance.
- Define packet ownership transfer and lifecycle rules.
- Re-review GPLv3 distribution obligations.

Implementation impact:

- MVP `shadow` starts a new `AutomationSession`.
- Patch spike must prove backend connection survives marked client
  disconnect before architecture changes.

## 5. Literal `/player` Alias

Decision: should the plugin register literal `/player` by default?

Recommended answer: keep `/fpp player` always available and make literal
`/player` opt-in.

Why: backend servers or plugins may already use `/player`. A proxy-level alias
can shadow expected backend behavior.

If choosing literal `/player` by default:

- Document command shadowing.
- Add config to disable it.
- Add tests that `/fpp player` remains available if `/player` is disabled.

Implementation impact:

- `proxy.command_aliases = ["fpp player"]` or equivalent safe default.
- Literal `player` alias is configured, not hard-coded.

## 6. Limbo Provider

Decision: LimboAPI, NanoLimbo, or custom limbo?

Recommended answer: LimboAPI first.

Why: it keeps the user experience inside Velocity and supports both login-time
and play-time limbo flows through a plugin API.

If choosing NanoLimbo:

- Add separate process/config lifecycle.
- Add Velocity server entry and forwarding-secret management.
- Define how consent/config state passes between Velocity and NanoLimbo.

If choosing custom limbo:

- Expect protocol/version maintenance work that distracts from the core
  automation value.

Implementation impact:

- `LimboService` interface is mandatory.
- `LimboApiService` is first implementation.
- `LimboUnavailableService` fails closed when provider is absent.

## 7. Persistence Timing

Decision: should SQLite/secret storage be built before MCProtocolLib offline
automation?

Recommended answer: no. Use in-memory storage through the offline spike, then
add SQLite/secret storage before online reconnect or persistent profiles.

Why: the offline spike should prove the protocol automation loop quickly. Secret
storage is required before sensitive auth material, not before packet emission.

If choosing persistence first:

- More robust restart behavior earlier.
- Slower path to proving the highest-risk protocol loop.

Implementation impact:

- `Storage` starts with `InMemoryStorage`.
- `SqliteStorage` becomes a dedicated child task.
- `AuthMaterialStore` cannot persist sensitive material until secret store
  exists.

## 8. Command Parity Standard

Decision: should MVP claim Carpet `/player` parity?

Recommended answer: no. Claim a Carpet-like, self-owned subset with explicit
deferred and unsupported behavior.

Why: Carpet runs inside the server and mutates `ServerPlayer`. This proxy sends
vanilla client packets. Some commands, especially `mount anything` and arbitrary
`spawn`, do not map cleanly.

If choosing full parity:

- Require cooperating upstream server plugin/mod or Velocity/backend patch.
- Add server-side mutation API.
- Expand license/security review.

Implementation impact:

- `carpet-command-mapping.md` is the source of truth.
- Parser may accept deferred commands, but runtime returns clear status.

## 9. Returning Player Policy

Decision: what happens when the real player logs back in while automation is
active?

Recommended answer: route to limbo reclaim prompt.

Why: two simultaneous sessions for the same account can collide with upstream
server login policy. The user needs a safe choice instead of automatic takeover.

Supported MVP choices:

- Stop automation and reclaim manually.
- Keep automation running and stay in limbo/status view.

Later choices:

- Observe mode.
- Seamless takeover if patch spike succeeds.

Implementation impact:

- `RECLAIM_PENDING` is a first-class state.
- `DisconnectEvent` and login events must update `PlayerContextRegistry`.

## 10. Legal/License Distribution

Decision: will the project distribute a patched Velocity server or bundled AGPL
components?

Recommended answer: do not patch/distribute Velocity in MVP. Treat LimboAPI as
a plugin dependency, not copied source.

Why: Velocity is GPLv3 and LimboAPI is AGPLv3. Distribution strategy matters.
The research can identify obligations but is not a substitute for legal review.

If distributing patched Velocity:

- Preserve source availability obligations.
- Keep patch diff small and documented.
- Add license review as a release gate.

Implementation impact:

- MVP stays as a plugin plus library dependencies.
- Patch spike is isolated and conditional.
