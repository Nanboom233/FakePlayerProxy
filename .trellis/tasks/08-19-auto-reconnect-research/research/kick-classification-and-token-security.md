# Research: Kick Classification and In-Memory Token Security

- Query: In the pinned Minecraft 26.2 / Velocity stack, can duplicate-login and
  server-ban kicks be classified without localized text, and what is the minimum
  safe contract for retaining a reconnect bearer token and exchanging it over a
  custom payload?
- Scope: mixed
- Date: 2026-08-19

## Findings

### Product decision after research

The product uses a best-effort denylist for backend kick packets. This decision
supersedes the conservative allowlist recommendation below.

Only a recognized vanilla duplicate-login, profile-ban, or IP-ban key disables
auto-reconnect. Every other backend kick remains retryable. A transport failure
also remains retryable.

A terminal disable during reconnect clears the held Minecraft access token. It
also cancels the pending retry and removes a waiting reconnect channel.

The reconnect policy has no attempt limit. It retries immediately after backend
loss. Failed attempts use delays of 10, 10, 30, 30, 60, 60, and then 300
seconds for every later attempt.

An authlib credential or account rejection is not a backend kick. The
controller disables auto-reconnect after `InvalidCredentialsException`,
`UserBannedException`, `ForcedUsernameChangeException`, or
`InsufficientPrivilegesException`.

`AuthenticationUnavailableException` represents a network failure or HTTP 5xx
response. It remains retryable. HTTP 429 and unknown authentication failures
also remain retryable because they do not prove that the token is invalid.

### Files found

- `plugin/patch/velocity-base.properties:1` pins Velocity commit
  `843a47e2a38325309cd66133149fc9a984f76bb8`.
- `mod/build.gradle.kts:15` pins the client and protocol target to Minecraft
  `26.2`.
- `plugin/build/server/source/proxy/src/main/java/com/velocitypowered/proxy/protocol/packet/DisconnectPacket.java:31`
  models a disconnect as a protocol state plus one Adventure component. It has
  no cause code or enum.
- `plugin/build/server/source/api/src/main/java/com/velocitypowered/api/event/player/KickedFromServerEvent.java:31`
  exposes server, optional original `Component`, and whether the kick happened
  during a server connection. It does not add semantic cause information.
- `plugin/build/server/source/api/src/main/java/com/velocitypowered/api/event/connection/DisconnectEvent.java:57`
  has a `LoginStatus`, including `CONFLICTING_LOGIN`, but this describes the
  frontend proxy login lifecycle. It is not the cause of a shadow backend's
  disconnect.
- `plugin/build/server/source/proxy/src/main/java/com/velocitypowered/proxy/connection/backend/BackendPlaySessionHandler.java:173`
  turns a backend play disconnect packet into `handleConnectionException(...)`.
- `plugin/build/server/source/proxy/src/main/java/com/velocitypowered/proxy/connection/backend/LoginSessionHandler.java:131`
  returns a backend login disconnect as `SERVER_DISCONNECTED`, again preserving
  only the component.
- `plugin/build/server/source/proxy/src/main/java/com/velocitypowered/proxy/connection/client/ConnectedPlayer.java:718`
  serializes the component to text only for logging, then fires
  `KickedFromServerEvent` with the original component at line 777.
- `plugin/patch/0002-automation-extension.patch:178` adds cancellation to
  `DisconnectEvent`, but adds no disconnect-cause field.
- `plugin/src/main/java/com/fakeplayerproxy/FakePlayerProxyPlugin.java:174`
  registers automation after post-login and, at lines 187-205, retains the
  backend when a shadow frontend disconnect is cancelled.
- `plugin/src/main/java/com/fakeplayerproxy/automation/AutomationManager.java:198`
  considers an automation player inactive as soon as its backend is null. Its
  tick removes and closes the service at lines 202-216. A reconnect controller
  must therefore intercept the backend-loss transition before this cleanup or
  replace it with an explicit reconnecting state.
- `mod/src/main/resources/assets/fakeplayerproxy-mod/lang/en_us.json:3` currently
  promises that the Minecraft access token is not sent to the server. Reusing
  the existing relay consent for token delegation would contradict that promise.
- `plugin/build/server/source/api/src/main/java/com/velocitypowered/api/event/connection/PluginMessageEvent.java:23`
  accepts messages from either a `Player` or `ServerConnection`. Its default is
  forwarding at line 51. `handled()` suppresses forwarding at lines 123-128.
- `plugin/build/server/source/api/src/main/java/com/velocitypowered/api/proxy/messages/ChannelRegistrar.java:16`
  requires registration before the proxy can intercept a channel.
- `plugin/build/server/source/proxy/src/main/java/com/velocitypowered/proxy/protocol/packet/PluginMessagePacket.java:56`
  defaults serverbound payloads to 32,767 bytes and clientbound payloads to
  1,048,576 bytes. The credential protocol should impose a much smaller bound.

### What the wire/API can classify

The pinned wire format and Velocity API have no stable semantic kick identity.
They carry a chat `Component`. A component can be translatable, literal,
nested, localized later, constructed by vanilla, constructed by a server fork,
or supplied by a plugin. Matching rendered text is therefore invalid, and even
matching a translation key is only a statement about component shape, not an
authenticated cause.

The official Minecraft 26.2 server artifact was resolved through Mojang's
version manifest and inspected from the SHA-1-verified server binary
(`823e2250d24b3ddac457a60c92a6a941943fcd6a`):

- Vanilla duplicate replacement uses the root translation key
  `multiplayer.disconnect.duplicate_login` (`PlayerList` initializes
  `DUPLICATE_LOGIN_DISCONNECT_MESSAGE` with this key and uses it when
  `disconnectAllPlayersWithProfile` closes the prior session).
- Vanilla profile bans use root key `multiplayer.disconnect.banned.reason`.
  IP bans use `multiplayer.disconnect.banned_ip.reason`. Expiring bans append
  `multiplayer.disconnect.banned.expiration` or
  `multiplayer.disconnect.banned_ip.expiration`.
- `BanListEntry.getReasonMessage()` wraps an administrator-provided reason as a
  literal component, or uses `multiplayer.disconnect.banned.reason.default` if
  absent. Consequently the nested reason is deliberately arbitrary text, while
  the outer vanilla key is structurally stable for this exact binary.

This supports non-localized **vanilla diagnostics** by walking the Adventure
component tree and examining translatable keys. It does not support reliable
policy classification for arbitrary online-mode backends: a plugin can emit the
same key for another cause, replace a vanilla ban with a literal/custom
component, or disconnect for another permanent reason. `CONFLICTING_LOGIN` is
not a substitute because it concerns Velocity's frontend registry, not the
backend packet.

### Denylist versus conservative allowlist

| Policy | Behavior | Safety assessment |
| --- | --- | --- |
| Deny duplicate and ban keys, retry every other reason | Stops exact vanilla duplicate/profile-ban/IP-ban component shapes, but retries whitelist rejection, server-full, incompatible version/mods, maintenance, administrative custom kicks, protocol violations, and plugin-defined permanent failures. | Not safe. It is open-ended and can create a reconnect loop or evade an operator's deliberate kick. |
| Allow only locally observed transient failures | Retry only when the proxy itself knows no semantic server rejection was received, for example connect timeout, refused/reset connection before a valid disconnect component, or an explicitly classified transient transport exception. Treat every backend disconnect packet as terminal by default. | Recommended baseline. Closed set, reviewable, and does not infer server intent from text. |
| Cooperative typed backend signal | A trusted FPP backend integration emits a versioned, authenticated/connection-bound cause enum with an explicit retryable bit. | Only route that can safely broaden the allowlist beyond local transport failures. It is a new protocol and cannot be inferred from vanilla components. |

Duplicate and ban translation keys should still be recognized structurally for
logging and an explicit hard stop when present. They must not be the only
denylist. A backend-supplied disconnect packet, including an absent/unrecognized
reason, is terminal unless a separately trusted typed contract says it is
retryable.

### In-memory bearer-token assessment

RFC 6750 defines bearer possession as sufficient to use a token and requires it
to be protected in storage and transport. A Minecraft services access token held
by Velocity therefore expands the trust boundary: compromise of the proxy
process, heap dump, debugger, crash dump, malicious plugin, or credential log
can permit account impersonation until the token expires. Keeping it only in
memory reduces persistence exposure but does not make it non-secret or
server-bound. Java `String` values cannot be reliably zeroized. Prefer a private
byte/char container where the consuming API permits, overwrite it on disposal,
and accept that runtime copies may remain.

Minimum acceptable record ownership is one authorization record per exact
authenticated player connection incarnation, containing only:

- access token and authenticated account UUID/profile identity
- active attempt number and authenticated player session identity
- the `autoReconnect` boolean, retry counters, and the next retry due time.

Do not retain Microsoft refresh tokens, authorization codes, passwords, or the
token on disk. Do not log payload bytes, token hashes/prefixes, Authorization
headers, consent envelopes, heap representations, or exceptions that embed the
credential. Restrict record access to the owning player EventLoop/controller.
Do not expose it through a general plugin API.

The proxy cannot safely infer validity merely by decoding token contents.
The selected product policy sets no independent retention limit. A
session-service credential or account rejection is authoritative and terminal.

### Consent contract

Token delegation needs a new, explicit, non-persistent consent separate from
the existing relay consent. The prompt must identify the connected proxy,
state that the proxy receives a Minecraft access token capable of acting as the
account, state that it may reconnect the backend after the visible client has
disconnected, state that retention and retries have no fixed time or attempt
limit, and offer a clear decline. The current saved relay decision cannot
authorize this because the current text promises the opposite.

Consent is connection-scoped and feature-scoped: `/fpp auto-reconnect on`
creates one request. Decline, disable, or a new frontend connection requires a
new request. The client must not remember or automatically approve the
decision. Every enable command must show a new consent request.

### Retry and cleanup

Selected lifecycle:

1. Accept `on` only while an authenticated frontend and active original backend
   exist. Set `autoReconnect=false`, clear the token, and send one empty request.
2. On a valid non-empty response from that exact player, store the token and set
   `autoReconnect=true`.
3. After Shadow backend loss, retry immediately. Later failures use the approved
   10, 10, 30, 30, 60, 60, and repeated 300-second delays.
4. Ready PLAY resets retry state. Login Success and Join Game do not reset it.
5. Clear authorization after a recognized duplicate-login or ban kick, a
   session-service credential or account rejection, identity mismatch, consent
   violation, or approved CONFIG terminal case. Reject and log malformed or
   unexpected payloads without replacing current authorization.
6. Also clear on Shadow exit or kill, same-UUID replacement, plugin shutdown,
   controller exception, and final automation removal. Cancel reconnect work
   before erasing the record.
7. `/fpp auto-reconnect off` sets `autoReconnect=false` and clears the token
   without closing the backend. Consent decline sends no player text.

A terminal session-service rejection proves that the held token cannot complete
the required session join.

### Minimum custom-payload trust contract

- Register a dedicated namespaced channel during proxy initialization, before
  sending the request. Unregister it at shutdown.
- Use proxy-to-client request and client-to-proxy response only. In
  `PluginMessageEvent`, require `source instanceof Player`, require the exact
  owning `Player` object, and reject a `ServerConnection` source. This prevents
  the backend from spoofing a response.
- Mark every recognized channel message `handled()` before parsing/returning so
  credentials are never forwarded to the backend. PaperMC's official Velocity
  guide explicitly warns that failing to mark handled can allow clients to
  impersonate the proxy to backend servers. The inverse forwarding leak is also
  unacceptable for a credential response.
- Use a versioned channel with an empty request, bounded token length, and exact
  end-of-message validation. Bind it to the exact authenticated Velocity player
  instead of accepting a client-supplied UUID. Reject malformed length, invalid
  UTF-8, trailing bytes, and oversized values.
- Do not use the wire maximum as the application maximum. The pinned Velocity
  serverbound ceiling is 32,767 bytes. Define a small protocol-specific maximum
  sufficient for the envelope/token and reject before allocating/copying large
  values.
- The payload is not proof of an independently trusted mod merely because it
  uses the channel name. Trust derives from the exact authenticated frontend
  connection and explicit local user consent. If other client mods can send
  arbitrary payloads, they execute with the user's client privileges. The
  prompt remains the decisive authorization boundary.

The selected product boundary does not correlate a response with an earlier
request. A valid non-empty response from the exact player enables the feature.
- The channel does not provide end-to-end confidentiality from Velocity. That
  is intentional because Velocity consumes the token. It must still travel only
  over the already encrypted authenticated frontend session and never through
  backend forwarding.

### External references

- Mojang official version manifest (26.2 metadata and authoritative artifact
  URL): https://piston-meta.mojang.com/mc/game/version_manifest_v2.json
- Mojang official 26.2 version metadata:
  https://piston-meta.mojang.com/v1/packages/c75d82e7fa6eca5a043dab0c6cf77cb8317644f4/26.2.json
- Mojang official 26.2 server artifact used for the vanilla bytecode findings
  (SHA-1 `823e2250d24b3ddac457a60c92a6a941943fcd6a`):
  https://piston-data.mojang.com/v1/objects/823e2250d24b3ddac457a60c92a6a941943fcd6a/server.jar
- Pinned Velocity `KickedFromServerEvent` source:
  https://github.com/PaperMC/Velocity/blob/843a47e2a38325309cd66133149fc9a984f76bb8/api/src/main/java/com/velocitypowered/api/event/player/KickedFromServerEvent.java
- Pinned Velocity disconnect packet source:
  https://github.com/PaperMC/Velocity/blob/843a47e2a38325309cd66133149fc9a984f76bb8/proxy/src/main/java/com/velocitypowered/proxy/protocol/packet/DisconnectPacket.java
- Pinned Velocity plugin-message event source:
  https://github.com/PaperMC/Velocity/blob/843a47e2a38325309cd66133149fc9a984f76bb8/api/src/main/java/com/velocitypowered/api/event/connection/PluginMessageEvent.java
- PaperMC Velocity plugin messaging guide (channel registration, source checks,
  and `handled()` security warning):
  https://docs.papermc.io/velocity/dev/plugin-messaging/
- IETF OAuth 2.0 Bearer Token Usage, RFC 6750 (bearer possession and
  storage/transport threat model): https://www.rfc-editor.org/rfc/rfc6750.html
- IETF OAuth 2.0 Security Best Current Practice, RFC 9700 (current OAuth threat
  model and least-privilege/short-lived credential direction):
  https://www.rfc-editor.org/rfc/rfc9700.html
- Microsoft identity-platform access-token guidance (tokens are credentials,
  lifetime/validation belongs to the resource):
  https://learn.microsoft.com/en-us/entra/identity-platform/access-tokens

### Related specs

- `.trellis/spec/backend/velocity-plugin.md`: current runtime contract says
  Shadow never reconnects or copies connection secrets. A later implementation
  must deliberately revise this contract through the spec workflow.
- `.trellis/spec/language/java.md:224`: protocol/external inputs require explicit
  runtime guards and existing failure handling.
- `.trellis/spec/language/java.md:328`: all consent, status, and disconnect text
  must use i18n.
- `.trellis/spec/guides/cross-layer-thinking-guide.md`: the feature crosses mod
  consent, payload protocol, proxy lifecycle, backend authentication, and
  operator behavior, so the contract must be traced end to end.

## Caveats / Not Found

- No cause enum, signed cause, or server-defined stable identifier exists in the
  pinned disconnect packet or `KickedFromServerEvent`.
- Vanilla translation keys are implementation evidence for the exact official
  26.2 binary, not a protocol guarantee and not proof that an arbitrary backend
  actually performed a ban/duplicate-login action.
- This report does not establish the separate Minecraft session-server join
  credential flow or whether a narrower delegated proof can replace the bearer
  token. The sibling authorization research topic owns that question.
- The product has fixed consent frequency, the kick denylist, retry timing, and
  player-session scope. The consent text must disclose the token transfer and
  reconnect behavior described above.
- The repository currently has no FPP plugin-message channel or token store, so
  all custom-payload requirements are a proposed minimum contract, not existing
  behavior.
