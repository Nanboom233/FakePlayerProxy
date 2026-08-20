# Auto-reconnect feasibility summary

Date: 2026-08-19

## Verdict

The proposed feature is technically feasible, but it is not a plugin-only
change and the proposed kick policy cannot be implemented reliably for an
arbitrary backend.

An online-mode reconnect after the frontend client is unavailable requires the
proxy to hold the player's current Minecraft-services access token in memory.
The initial relay AES key and session join cannot be reused. A new backend
connection supplies a new Hello and requires a new AES key, server digest,
session-server join request, and encrypted key response.

The feature therefore changes two current product invariants:

- Velocity currently never receives the Minecraft access token.
- Shadow currently retains one existing backend connection and cannot reconnect
  that backend.

Both the Fabric and Velocity specifications must be revised deliberately before
implementation.

## Exact authorization boundary

The session join request requires:

1. the Minecraft-services access token
2. the selected Java profile UUID
3. the digest for the new backend login.

The UUID is already known from the authenticated Velocity player. The digest
can only be computed after the future backend Hello and a fresh AES key exist.
The session server returns no transferable signed proof. Therefore a proof
cannot be requested once and cached before Shadow disconnects the frontend.

The running client exposes the final Minecraft access token, but it does not
expose a refresh token or an authoritative expiry instant. The selected policy
uses no independent retention limit. It keeps the token in memory until an
approved terminal condition or session-service credential rejection occurs.

The normal Shadow frontend disconnect keeps the retained authorization active.
It must not clear the token. Cleanup applies to explicit disablement,
non-Shadow disconnect, Shadow kill, a new same-UUID frontend login, proxy
shutdown, authentication rejection, or final automation removal.

## Payload feasibility

A post-login custom payload is feasible. Velocity can register a dedicated
channel, send a request to the exact command-source player, and synchronously
consume the response from that exact Player source. The handler must mark a
recognized response as handled before parsing so credential bytes are never
forwarded to the backend.

The selected protocol uses an empty request and bounded token response. The
proxy binds the response to the exact authenticated Player instance. It does not
accept a client-supplied identity or correlate the response with a request.
Consent must be new and explicit because the current relay prompt promises that
the access token is not sent to the server.

Every `/fpp auto-reconnect on` command starts a new consent request. The client
must not save or automatically approve the decision. A decline keeps the
feature disabled and sends no access token. The request has no time limit.

## Velocity lifecycle gap

The current Shadow path keeps the original `ConnectedPlayer`, plugin state,
closed frontend connection object, and original backend connection alive. When
that backend closes, the next automation tick removes the service.

The public connection request API can open a socket on the retained EventLoop,
but it cannot complete this reconnect:

- the dead `connectedServer` reference causes same-target `ALREADY_CONNECTED`
- continuation ownership exists only on the old `VelocityServerConnection`
- the online-mode login branch only accepts the initial live frontend relay
- fresh configuration/play completion is not fully audited for a closed
  frontend
- the automation manager closes the service before a reconnect controller can
  own the transition.

A later design therefore needs a narrow Velocity patch for atomic dead-backend
detach, continuation ownership on the new backend, token-backed online-mode
login, and headless configuration/play completion.

The plugin must preserve the exact `Player`, `World`, and `AutomationService`.
It freezes scheduled actions and input intent. It clears backend-derived state
and rebuilds that state from the new CONFIG and PLAY packets.

Automation resumes only after the new initial position and current chunk are
available and the service sends `ServerboundPlayerLoadedPacket`. This readiness
point also resets the retry delay sequence.

## Shared backend priority list

Patch `BackendChannelInitializer`. Add one outbound gate to each backend
channel. Do not change the login callers.

Normal backend connections and raw Transfer both use this initializer. A later
Shadow reconnect through `VelocityServerConnection` uses it too. The gate does
not need a login-path type.

The gate stores all writes in Netty `PendingWriteQueue` while its channel waits.
The priority list groups channels by resolved remote socket address. It releases
one channel every four seconds.

Each address has one high FIFO list and one low FIFO list. Real-player Login
channels use the high list. Shadow auto-reconnect channels use the low list.

The gate detects a Shadow continuation through the existing
`VelocityServerConnection.isLogoutCancelled()` state. All other channels use
high priority. No login-path type or caller argument is necessary.

Priority changes the next waiting channel only. It does not preempt an active
four-second slot.

Channel close removes the waiting gate. Netty fails the stored promises and
releases their messages. No player-specific cancellation model is necessary.

Remove the fixed delay from `AuthSessionHandler.startTransfer()` after this
common gate owns the interval.

See `backend-connect-wait-list.md` for the complete boundary.

## Headless CONFIG boundary

The retained service can answer Known Packs, cookie, KeepAlive, Ping, and
configuration-finish packets. Existing packet listeners can rebuild registry,
tag, feature, world, player, and inventory state.

The retained Velocity resource-pack handler can acknowledge a pack that the
same client session already applied. A new optional pack can be declined. A new
required pack cannot be applied after the frontend closes. A new required pack
disables auto-reconnect and clears the token.

Every Code of Conduct request disables auto-reconnect and clears the token. The
proxy does not track or reuse user acceptance.

A backend Transfer packet targets the closed frontend and does not switch the
retained Shadow by itself. It disables auto-reconnect and clears the token.

Third-party CONFIG plugin protocols are outside this task.

## Kick classification limit

Minecraft and Velocity expose a disconnect `Component`, not an authenticated
cause enum.

Vanilla 26.2 uses recognizable translation keys for duplicate login, profile
ban, and IP ban. Exact translation-key inspection is useful as a hard-stop
diagnostic for the pinned vanilla implementation. It is not a reliable policy
boundary for arbitrary servers because plugins and forks can replace, imitate,
or localize the component.

The selected rule retries every kick except recognized duplicate-login and ban
components. The proxy can implement this rule only through best-effort component
matching. It can retry whitelist rejection, protocol mismatch, administrative
kick, maintenance, or another permanent failure.

The research compared three product policies:

| Policy | Result |
| --- | --- |
| Transport allowlist | Retry only locally known connection failures when no backend disconnect packet was received. Reliable, but a graceful server restart that sends a kick packet is terminal. |
| Best-effort denylist | Retry other disconnect packets after hard-stopping recognized vanilla duplicate/ban keys. Matches the initial behavior more closely, but cannot guarantee correct classification. |
| Cooperative typed cause | A trusted backend integration supplies a versioned retryable cause. Reliable and supports graceful restart, but requires backend cooperation. |

## Minimum security boundary

- Transfer only the final Minecraft-services token, never Microsoft, Xbox,
  XSTS, refresh, launcher, or serialized authentication state.
- Bind it to the exact authenticated player, profile UUID, `autoReconnect`, and
  auto-reconnect session. Do not bind it to one backend address.
- Retain it only in proxy memory. Do not persist, log, hash for diagnostics, or
  expose it through a general plugin API.
- Allow only one reconnect attempt or one waiting reconnect channel at a time.
  A real same-UUID login always replaces the reconnecting Shadow.
- Treat profile mismatch, authlib credential rejection, and explicit
  disablement as terminal. Reject and log malformed or unexpected payloads
  without replacing the current authorization.
- Best-effort overwrite byte/character buffers when ownership ends, while
  acknowledging that Java and consuming APIs can create non-zeroizable copies.

## Selected retry policy

The first reconnect attempt starts immediately after backend loss. Later
attempts use this delay sequence:

`10s, 10s, 30s, 30s, 60s, 60s, 300s, 300s, ...`

The 300-second delay repeats without an attempt limit. Ready PLAY resets the
sequence for a later independent backend loss.

The retained automation tick makes one attempt eligible. The eligible channel
then enters the low-priority backend list. The next delay starts only after
that channel leaves the list and its login attempt fails.

Duplicate-login and server-ban keys disable auto-reconnect. Disabling the
feature clears the held access token.

`/player kill` also disables the feature before it closes the Shadow backend.
The resulting backend close must not enter the retry policy.

Authlib credential and account rejection exceptions also disable the feature.
`AuthenticationUnavailableException`, HTTP 429, and unknown authentication
failures remain retryable. These results do not prove that the token is invalid.

## Detailed research

- `client-token-and-session-auth.md`
- `velocity-shadow-reconnect-lifecycle.md`
- `kick-classification-and-token-security.md`
- `backend-connect-wait-list.md`
- `retry-and-priority-policy.md`
- `automation-service-reconnect-continuity.md`
- `auto-reconnect-logging.md`
