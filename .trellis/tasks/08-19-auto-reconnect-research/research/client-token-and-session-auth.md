# Research: Client token and session authentication

- Query: What exact client credential and session proof are required to reconnect a Minecraft 26.2 client identity to an online-mode backend after the frontend client is unavailable, and can a narrower proof replace transfer of a bearer token?
- Scope: mixed
- Date: 2026-08-19

## Findings

### Executive result

Minecraft 26.2 online-mode login requires a successful Mojang session-server
`join` operation for the digest of the specific backend login attempt. In the
pinned client, that operation sends three values:

1. the Minecraft-services access token (`accessToken`),
2. the selected Java profile UUID (`selectedProfile`), and
3. the login digest (`serverId`).

The access token is the only secret authorization credential in that request,
but it is not literally sufficient by itself: the profile UUID and digest are
also required. The UUID is profile metadata already known to Velocity. The
digest must be computed from the future backend Hello and a client/proxy-created
AES secret.

There is no standard transferable "join proof" returned to the client. The
`join` POST creates server-side authorization state which the backend checks
with `hasJoined`. Therefore, if the frontend client will be unavailable when a
fresh backend Hello arrives, the standard Minecraft 26.2 protocol offers no
narrow server-bound credential that can be prepared earlier. The component
performing reconnect must either:

- hold a still-valid Minecraft-services bearer token and call `join` on demand
- ask an available client-side join oracle to call `join` on demand
- change the backend trust/authentication model (for example, trusted proxy
  forwarding instead of an independent online-mode login).

For this task's requirement that the client can be unavailable and the backend
remains independently online-mode, token custody at Velocity (or at an external
broker that merely relocates the same custody) is necessary. This is technically
feasible but materially expands the proxy's security boundary.

### Credential layers are distinct

| Material | Issuer / consumer | Purpose | Needed by session `join`? | Suitable reconnect material? |
| --- | --- | --- | --- | --- |
| Microsoft OAuth access token | Microsoft identity platform / Xbox authentication resource | Upstream Microsoft-account API authorization | No | No. It has the wrong audience and resource. |
| Microsoft OAuth refresh token | Microsoft identity platform | Obtain later Microsoft access tokens. The applicable flow must request `offline_access`. | No | No. It is broader and longer-lived than needed. The running client does not expose it. |
| Xbox user token and XSTS token | Xbox services | Upstream Xbox identity proof used to obtain a Minecraft-services token | No | No. Sessionserver `join` does not accept it. |
| Minecraft-services access token | Minecraft services / Mojang session server | Bearer authorization for the selected Minecraft identity | **Yes** | Yes while valid, but broad and reusable rather than backend-bound |
| Selected Java profile | Minecraft profile UUID and name | Identifies which owned Java profile joins | **UUID required** | Yes, but it is identifier metadata, not a credential |
| Session-server join authorization | Mojang session server / online-mode backend | Associates a profile with one login digest so `hasJoined` succeeds | Created by the `join` POST | No transferable artifact is returned |
| AES shared secret | Generated for the login / client and backend | Encrypts the Minecraft TCP stream and contributes to the digest | Indirectly | No. It is fresh connection state, not account authorization. |
| Backend RSA public key | Backend Hello / client | Encrypts the AES secret and contributes to the digest | Indirectly | No. It is a public connection input, not account authorization. |
| Backend challenge | Backend Hello / client | Proves the key response belongs to the active Hello | Not included in the digest | No. It is a fresh connection challenge, not account authorization. |

Microsoft documents OAuth access tokens as bearer tokens for their target
resource and describes `expires_in`, granted `scope`, and refresh tokens as
separate response fields. It also states that a refresh token is returned only
when `offline_access` is requested and warns clients not to parse or depend on
tokens issued for APIs they do not own. Those semantics explain why a Microsoft
token cannot be substituted for the Minecraft-services token passed by the
launcher to the game. Primary reference:
[Microsoft identity platform OAuth authorization-code flow](https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-auth-code-flow).

The Minecraft-specific exchange chain is not exposed by the pinned game API.
The game starts with the final Minecraft access token and profile already
injected by the launcher. `User` stores only name, UUID, access-token string,
XUID, and client ID. It has no Microsoft token, Xbox token, refresh token,
formal scopes, or expiry field
(`minecraft-merged-deobf-26.2-sources.jar!/net/minecraft/client/User.java:10`).
The official 26.2 launcher metadata also declares the launch arguments
`--uuid` and `--accessToken`, and pins `com.mojang:authlib:9.0.75`:
`E:/Gradle/caches/fabric-loom/26.2/mojang_minecraft_info.json:1`.

### Exact pinned join contract

#### Join failure classification

Authlib 9.0.75 keeps credential rejection separate from service failure.

`YggdrasilMinecraftSessionService.joinServer()` converts
`MinecraftClientException` through `toAuthenticationException()`.

`InvalidCredentialsException`, `UserBannedException`,
`ForcedUsernameChangeException`, and `InsufficientPrivilegesException`
identify credential or account rejection. Auto-reconnect cannot repair these
results with the same token.

`AuthenticationUnavailableException` identifies network failure or an HTTP
5xx response. It does not prove that the token is invalid.

`MinecraftClientHttpException` preserves the HTTP status and `Retry-After`.
HTTP 429 is a rate limit. It must remain retryable.

Other authentication failures do not prove credential rejection. The retry
policy keeps the token unless a later result supplies a terminal identity.

The Mod pins Minecraft 26.2 in `mod/build.gradle.kts:22`. Official Minecraft
26.2 source calls:

```java
sessionService().joinServer(
    minecraft.getUser().getProfileId(),
    minecraft.getUser().getAccessToken(),
    digest);
```

See
`minecraft-merged-deobf-26.2-sources.jar!/net/minecraft/client/multiplayer/ClientHandshakePacketListenerImpl.java:158`.
The source artifact is local at
`E:/Gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.2/minecraft-merged-deobf-26.2-sources.jar`.

Official Mojang authlib 9.0.75 constructs exactly
`JoinMinecraftServerRequest(authenticationToken, profileId, serverId)` and
POSTs it to the session environment's `/session/minecraft/join` endpoint
(`authlib-9.0.75-sources.jar!/com/mojang/authlib/yggdrasil/YggdrasilMinecraftSessionService.java:71-81`).
The serialized request fields are exactly `accessToken`, `selectedProfile`, and
`serverId`
(`authlib-9.0.75-sources.jar!/com/mojang/authlib/yggdrasil/request/JoinMinecraftServerRequest.java:7-13`).
Production resolves to `https://sessionserver.mojang.com`
(`authlib-9.0.75-sources.jar!/com/mojang/authlib/yggdrasil/YggdrasilEnvironment.java:9-13`).
The source artifact is local at
`E:/Gradle/caches/modules-2/files-2.1/com.mojang/authlib/9.0.75/e05c23003b9bcc0be095928ab43606a0a0723532/authlib-9.0.75-sources.jar`;
the official binary URL pinned by Minecraft metadata is
[authlib 9.0.75](https://libraries.minecraft.net/com/mojang/authlib/9.0.75/authlib-9.0.75.jar).

The official production endpoints also currently enforce these separate trust
boundaries: Minecraft login-with-Xbox rejects a request without its
`identityToken`, the Minecraft profile endpoint requires bearer authorization,
and sessionserver `join` rejects an unauthenticated/empty request. Endpoint
references:
[Minecraft Xbox exchange](https://api.minecraftservices.com/authentication/login_with_xbox),
[Minecraft profile](https://api.minecraftservices.com/minecraft/profile), and
[session join](https://sessionserver.mojang.com/session/minecraft/join).

### What the login digest binds

Minecraft 26.2 creates a fresh 128-bit AES key, reads the Hello RSA public key,
and computes the signed hexadecimal SHA-1 digest from:

```text
server-id bytes || AES shared-secret bytes || RSA public-key SPKI bytes
```

See
`minecraft-merged-deobf-26.2-sources.jar!/net/minecraft/client/multiplayer/ClientHandshakePacketListenerImpl.java:121-127`
and `...!/net/minecraft/util/Crypt.java:58-89`.
The standard key response separately encrypts the AES secret and Hello
challenge with the RSA public key
(`...!/net/minecraft/network/protocol/login/ServerboundKeyPacket.java:16-31`).
Thus:

- the join authorization is account/profile-bound through the bearer token and
  `selectedProfile`.
- it is login/server-bound through the digest's server ID, public key, and AES
  secret.
- it is not directly challenge-bound, although the encrypted challenge proves
  that the key response answers the active Hello.
- a future reconnect normally has a different AES secret and therefore a
  different digest even if the backend process reuses its RSA key.

The server hash cannot be authorized before the future connection inputs and
chosen AES secret exist. Deliberately reusing an old AES secret would not turn
the old authorization into a general delegation mechanism, would violate the
normal fresh-login construction, and would still depend on the future Hello's
server ID/public key and the session server's authorization state.

### Current FakePlayerProxy behavior

The current Mixin computes a target digest from the target public key and the
same live AES key at
`mod/src/main/java/com/fakeplayerproxy/mod/mixins/MixinClientHandshakePacketListenerImpl.java:124-128`.
After consent, it invokes Minecraft's existing `authenticateServer` only when
the target says authentication is required
(`MixinClientHandshakePacketListenerImpl.java:249-267`). It never receives or
sends a Microsoft/Xbox token and it does not send the Minecraft token to
Velocity. The current UI explicitly promises that the access token is not sent
(`mod/src/main/resources/assets/fakeplayerproxy-mod/lang/en_us.json:3`).

The relay RSA key only lets Velocity decrypt and re-encrypt the protected
streams. The project specification explicitly says it is not an authentication
credential or substitute for Mojang authentication
(`.trellis/spec/frontend/fabric-client-mod.md:88-90`). The spec also currently
forbids persisting or logging AES secrets, access tokens, and profile
credentials (`fabric-client-mod.md:97-98`).

The Mod can technically read the already-loaded Minecraft token through
`Minecraft.getInstance().getUser().getAccessToken()`, because vanilla uses the
same accessor during `join`. However, the current `User` shape provides no
trusted expiry instant and no refresh capability. A proposed payload could send
the token and UUID, but it could not safely promise a fixed reconnect window
without separately obtaining issuer-derived expiry information.

### Expiry, scope, and account binding

- **Account/profile binding:** authlib submits both bearer token and selected
  profile UUID. Velocity must retain and verify the UUID associated with the
  authenticated frontend player. It must never accept an arbitrary profile ID
  supplied independently of that connection.
- **Expiry:** neither Minecraft 26.2 `User` nor authlib's join interface exposes
  expiry. Do not infer expiry by decoding an opaque token. Treat any fixed
  duration as unverified unless the launcher/client supplies issuer-derived
  `expires_in`/expiry metadata. A session-service credential or account
  rejection permanently invalidates the retained material. A service outage,
  rate limit, or unknown failure remains retryable.
- **Scope:** the Minecraft token is not represented in the pinned client as a
  Microsoft OAuth token with a locally inspectable `scope` field. Its observed
  authority includes creating session joins for the selected profile. It is a
  reusable bearer for arbitrary future server digests during its validity, not
  a token scoped to this proxy, backend, or command.
- **Refresh:** no refresh token or token-exchange API is available through the
  running game `User`. Transferring a Microsoft refresh token would be a much
  larger credential transfer and is neither necessary nor justified for this
  feature. The proxy cannot know the token expiry before the session service
  rejects it.

### Can a narrower proof replace token transfer?

Not under the standard Minecraft 26.2 online-mode protocol when the client may
be unavailable later:

1. A completed `join` produces no client-held signed assertion. Authlib expects
   an empty successful response, and the backend independently calls
   `hasJoined` with the login digest.
2. A pre-authorized digest is tied to connection-specific cryptographic input.
   it is not a wildcard delegation for later digests.
3. A proxy-minted one-shot token is not understood by Mojang sessionserver or an
   unmodified online-mode backend.
4. Keeping the token at a remote helper and asking it to call `join` can narrow
   what Velocity itself stores, but the helper still holds bearer authority. It
   changes custody and availability, not the Minecraft credential model.
5. A client-side join oracle is the genuinely narrower design because the
   bearer stays client-side and the client sees only the requested digest. It
   fails the stated case once that client is unavailable.

Consequently, the product choice is binary: accept in-memory bearer-token
custody at the trusted Velocity process for the retained player session, or
relax one requirement. The alternative requires client availability or a
different backend authentication model.

### Minimum security consequences if token custody is accepted

- Consent text must change: the current promise that the Minecraft access token
  is not sent would become false.
- Transfer only the final Minecraft-services access token. Velocity already
  owns the selected UUID for the authenticated player. Never transfer Microsoft
  OAuth, Xbox, XSTS, or refresh credentials.
- Bind retained material to the already authenticated Velocity player, the
  specific frontend connection, explicit consent, and auto-reconnect
  enablement. Do not bind it to one backend address. Do not persist it.
- Keep it only in memory. Never place it in logs, exception text, metrics,
  config, database, command output, heap diagnostics, or reconnect traces.
- Clear it on explicit disablement, Shadow exit, player replacement, credential
  rejection, or final reconnect termination. Normal Shadow frontend disconnect
  does not clear it.
- Apply the approved retry delays and common backend priority list. The token
  must not become a generic API or arbitrary-digest join oracle available to
  plugins or commands.
- Use the existing encrypted authenticated frontend connection as the delivery
  channel. The relay's ability to decrypt that channel means Velocity is already
  the intended recipient, but it does not reduce bearer-token sensitivity.

## Files Found

- `mod/build.gradle.kts:22` - pins the client to Minecraft 26.2.
- `mod/src/main/java/com/fakeplayerproxy/mod/mixins/MixinClientHandshakePacketListenerImpl.java:121` - prepares target digest/key response and invokes vanilla authentication after consent.
- `mod/src/main/java/com/fakeplayerproxy/mod/packets/ServerHelloPacketEnvelope.java:111` - extracts the target RSA public key from the proxy carrier.
- `mod/src/main/resources/assets/fakeplayerproxy-mod/lang/en_us.json:3` - current user-facing promise that no Minecraft access token is sent.
- `.trellis/spec/frontend/fabric-client-mod.md:82` - current session-join and secret-handling contract.
- `E:/Gradle/caches/fabric-loom/26.2/mojang_minecraft_info.json:1` - official 26.2 launch arguments and authlib 9.0.75 pin.
- `E:/Gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.2/minecraft-merged-deobf-26.2-sources.jar` - official-named Minecraft 26.2 source used to trace login cryptography and `User` state.
- `E:/Gradle/caches/modules-2/files-2.1/com.mojang/authlib/9.0.75/e05c23003b9bcc0be095928ab43606a0a0723532/authlib-9.0.75-sources.jar` - Mojang authlib request schema and production session endpoints.

## Related Specs

- `.trellis/spec/frontend/fabric-client-mod.md` - relay/session-auth boundary, token non-disclosure, and secret cleanup requirements.
- `.trellis/spec/language/java.md` - relevant if a later task introduces client-side credential value objects or payload handling.
- `.trellis/spec/backend/velocity-plugin.md` - relevant to any later Velocity lifecycle and payload implementation.

## External References

- [Microsoft OAuth 2.0 authorization-code flow](https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-auth-code-flow) - bearer/access/refresh token, expiry, scope, `offline_access`, and opaque-token guidance.
- [Official Minecraft 26.2 metadata index](https://piston-meta.mojang.com/mc/game/version_manifest_v2.json) - authoritative distribution metadata entry point.
- [Official Mojang authlib 9.0.75 binary](https://libraries.minecraft.net/com/mojang/authlib/9.0.75/authlib-9.0.75.jar) - implementation pinned by Minecraft 26.2.
- [Mojang session join endpoint](https://sessionserver.mojang.com/session/minecraft/join) - production endpoint selected by authlib.
- [Minecraft services Xbox exchange endpoint](https://api.minecraftservices.com/authentication/login_with_xbox) - upstream exchange boundary, distinct from session join.
- [Minecraft services profile endpoint](https://api.minecraftservices.com/minecraft/profile) - bearer-protected selected-profile service boundary.

## Caveats / Not Found

- Mojang does not expose a public, versioned protocol document in the inspected
  distribution that promises a fixed Minecraft access-token lifetime, formal
  scope list, join-state lifetime, or reusable delegated session assertion.
  This report therefore does not invent numeric TTLs or claim a formal OAuth
  scope for the Minecraft token.
- The successful Minecraft Xbox-exchange response was not exercised because no
  account credential was used. The distinction between upstream identity
  tokens and the final Minecraft token is established by the official endpoint
  boundary and by the fact that the shipped game receives only the final token.
- The local decompiled/source-cache paths are build inputs, not repository
  files. Their versions and hashes are anchored by the official cached 26.2
  metadata and the repository's Minecraft pin.
- A backend implementation could add a proprietary delegated-auth scheme, but
  that would no longer be the standard Minecraft 26.2 online-mode session flow
  requested here and would require coordinated backend changes.
