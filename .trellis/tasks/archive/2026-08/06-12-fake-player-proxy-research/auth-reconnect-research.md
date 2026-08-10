# Auth And Auto Reconnect Implementation Research

This document deepens the Auto Reconnect research to code-implementation level.
It focuses on explicit auth material, online-mode reconnect, storage risk, and
failure classification.

Sources checked on 2026-06-12:

- MCProtocolLib `SessionService.java`: https://github.com/GeyserMC/MCProtocolLib/blob/master/protocol/src/main/java/org/geysermc/mcprotocollib/auth/SessionService.java
- MCProtocolLib `ClientListener.java`: https://github.com/GeyserMC/MCProtocolLib/blob/master/protocol/src/main/java/org/geysermc/mcprotocollib/protocol/ClientListener.java
- MCProtocolLib `MinecraftProtocol.java`: https://github.com/GeyserMC/MCProtocolLib/blob/master/protocol/src/main/java/org/geysermc/mcprotocollib/protocol/MinecraftProtocol.java
- MCProtocolLib auth example: https://github.com/GeyserMC/MCProtocolLib/blob/master/example/src/main/java/org/geysermc/mcprotocollib/auth/example/MinecraftAuthTest.java
- MinecraftAuth README: https://github.com/RaphiMC/MinecraftAuth
- MinecraftAuth `JavaAuthManager.java`: https://github.com/RaphiMC/MinecraftAuth/blob/main/src/main/java/net/raphimc/minecraftauth/java/JavaAuthManager.java
- MinecraftAuth `Holder.java`: https://github.com/RaphiMC/MinecraftAuth/blob/main/src/main/java/net/raphimc/minecraftauth/util/holder/Holder.java
- MinecraftAuth `DeviceCodeMsaAuthService.java`: https://github.com/RaphiMC/MinecraftAuth/blob/main/src/main/java/net/raphimc/minecraftauth/msa/service/impl/DeviceCodeMsaAuthService.java

License note:

- MCProtocolLib is MIT.
- MinecraftAuth is LGPLv3. MCProtocolLib depends on MinecraftAuth for auth
  examples/dependency wiring. Direct use or redistribution of MinecraftAuth must
  preserve LGPLv3 obligations and should be reviewed before release packaging.

## Online-Mode Login Flow In MCProtocolLib

Implementation chain from source:

1. Build `MinecraftProtocol(profile, accessToken)`.
2. `MinecraftProtocol.newClientSession` stores `MinecraftConstants.PROFILE_KEY`
   and `MinecraftConstants.ACCESS_TOKEN_KEY` on the session.
3. `ClientListener.connected` sends the handshake/login-intention packet and
   `ServerboundHelloPacket(profile.getName(), profile.getId())`.
4. On `ClientboundHelloPacket`, `ClientListener` reads profile/access token.
5. If the server requires authentication and either profile or token is absent,
   it throws `UnexpectedEncryptionException`.
6. `ClientListener` generates the shared AES key.
7. `SessionService.getServerId(serverId, publicKey, secretKey)` computes the
   server hash.
8. If authentication is required, `SessionService.joinServer(profile,
   accessToken, serverHash)` posts to the Mojang sessionserver join endpoint.
9. Client sends `ServerboundKeyPacket`, enables encryption, processes login
   compression, acknowledges login success, enters configuration, then game.
10. MCProtocolLib can automatically answer keepalive packets when
    `AUTOMATIC_KEEP_ALIVE_MANAGEMENT` is enabled.

Implication:

- The proxy does not need to reimplement encryption/sessionserver join.
- The proxy must provide the right `GameProfile` and Minecraft access token at
  connect/reconnect time.
- For online-mode reconnect, "session key" must mean auth material that can
  produce or already contains a valid Minecraft access token plus profile. A
  vague user-supplied key is not enough unless its semantics are defined.

## Auth Modes

| Mode | What is stored | Can reconnect? | Online-mode? | MVP status |
| --- | --- | --- | --- | --- |
| `OFFLINE_OR_OWNED_FORWARDING` | username/profile config only | Yes, against offline/owned targets | No | First MVP/spike |
| `ACCESS_TOKEN_ONLY` | UUID, username, Minecraft access token, expiry | Only while token remains valid | Yes | Test-account spike |
| `REFRESHABLE_MICROSOFT_AUTH` | Encrypted serialized `JavaAuthManager` state or encrypted refresh-capable material | Yes until refresh revoked/fails | Yes | Deferred/gated |
| `USER_SUPPLIED_SESSION_KEY` | Undefined | Undefined | Undefined | Do not implement until semantics are specified |

Recommended implementation:

- Implement `OFFLINE_OR_OWNED_FORWARDING` first.
- Implement `ACCESS_TOKEN_ONLY` next for a dedicated test account.
- Keep `REFRESHABLE_MICROSOFT_AUTH` disabled unless `SecretStore` is configured
  and explicit consent is recorded.
- Treat `USER_SUPPLIED_SESSION_KEY` as an alias for a future concrete auth mode,
  not as a magic bypass.

## Access-Token-Only Flow

This mode is useful for a narrow spike because it avoids storing refresh
material.

Required input:

- UUID.
- Current username.
- Minecraft access token.
- Expiry instant.

Implementation:

```java
public record AccessTokenAuthMaterial(
    UUID uuid,
    String username,
    String accessToken,
    Instant expiresAt) implements AuthMaterial {}

public final class AccessTokenAuthResolver implements AuthResolver {
  public FppResult<ResolvedAuth> resolve(AccessTokenAuthMaterial material, Instant now) {
    if (!now.isBefore(material.expiresAt().minusSeconds(30))) {
      return err("auth.access_token.expired");
    }
    return ok(new ResolvedAuth(
        new GameProfile(material.uuid(), material.username()),
        material.accessToken()));
  }
}
```

Reconnect behavior:

- If token is unexpired, reconnect can attempt a new online-mode login.
- If token is expired, fail closed with `AUTH_REFRESH_REQUIRED`.
- Do not try to refresh because no refresh material exists.

Security:

- Never show the access token in status, audit, logs, exceptions, or command
  output.
- Store access-token-only material only if the user explicitly opts in. Prefer
  memory-only for the spike.

## Refreshable Microsoft Auth Flow

MinecraftAuth supports device code login, JSON serialization, loading saved
state, lazy token refresh through `Holder`, and change listeners for persistence.

Observed source behavior:

- `JavaAuthManager.create(httpClient)` creates a Java auth manager builder.
- Device code login uses `DeviceCodeMsaAuthService`.
- Default device-code timeout is 300 seconds.
- `DeviceCodeMsaAuthService` requests a device code, invokes a callback with
  verification URI/code data, then polls until token acquisition or timeout.
- `JavaAuthManager.toJson(authManager)` serializes manager state.
- `JavaAuthManager.fromJson(httpClient, json)` restores manager state.
- `Holder.getUpToDate()` refreshes expired/missing values and throws if refresh
  fails.
- Change listeners fire when token holders change, so encrypted persisted state
  can be updated after refresh.

Implementation boundary:

```java
public interface MicrosoftAuthService {
  CompletableFuture<FppResult<DeviceCodePrompt>> beginDeviceCodeLogin(PlayerKey owner);
  CompletableFuture<FppResult<AuthMaterialRef>> completeDeviceCodeLogin(PlayerKey owner, DeviceCodeFlowId flowId);
  CompletableFuture<FppResult<ResolvedAuth>> resolve(AuthMaterialRef ref);
  CompletableFuture<FppResult<Void>> revoke(PlayerKey owner, AuthMaterialRef ref);
}

public record DeviceCodePrompt(
    DeviceCodeFlowId flowId,
    URI verificationUri,
    URI directVerificationUri,
    String userCode,
    Instant expiresAt,
    Duration pollingInterval) {}

public record ResolvedAuth(GameProfile profile, String minecraftAccessToken) {}
```

Threading:

- Do not block Velocity event threads while waiting for device-code completion.
- Run device-code polling on a dedicated bounded executor.
- Store pending auth flows in memory and expire them.
- Surface the prompt through limbo/config UI.

Persistence:

- Store encrypted serialized `JavaAuthManager` JSON as the refresh-capable
  state. It may contain MSA refresh tokens, device ids/keys, XBL tokens,
  Minecraft tokens, profile, and certificates.
- Attach a change listener after load/login. On token refresh, re-encrypt and
  save the updated JSON.
- If no `SecretStore` is available, disable this mode.

Resolution:

```java
JavaAuthManager manager = JavaAuthManager.fromJson(httpClient, decryptedJson);
manager.getChangeListeners().add(() -> saveEncrypted(JavaAuthManager.toJson(manager)));
MinecraftProfile profile = manager.getMinecraftProfile().getUpToDate();
MinecraftToken token = manager.getMinecraftToken().getUpToDate();
ResolvedAuth resolved = new ResolvedAuth(
    new GameProfile(profile.getId(), profile.getName()),
    token.getToken());
```

Failure handling:

- `TimeoutException` during device code login: prompt expired, user can retry.
- Missing refresh token: user must sign in again.
- Refresh network failure: transient unless repeated beyond retry policy.
- Refresh invalid/revoked: permanent until user re-authenticates.
- Minecraft profile missing: permanent or account-entitlement issue.

## Auth Material Store

Use references in normal persistence tables, not raw secrets.

```java
public record AuthMaterialRef(UUID ownerUuid, AuthMode mode, String ref) {}

public interface AuthMaterialStore {
  FppResult<AuthMaterialRef> save(PlayerKey owner, AuthMode mode, SensitiveAuthPayload payload);
  FppResult<SensitiveAuthPayload> load(PlayerKey owner, AuthMaterialRef ref);
  FppResult<Void> revoke(PlayerKey owner, AuthMaterialRef ref);
}

public interface SecretStore {
  boolean isAvailable();
  SecretRef put(PlayerKey owner, byte[] plaintext);
  byte[] get(PlayerKey owner, SecretRef ref);
  void delete(PlayerKey owner, SecretRef ref);
}
```

Storage rules:

- `auth_material_refs` table stores owner, mode, opaque ref, created/expiry,
  revoked timestamp.
- Secret payload lives in `SecretStore`.
- SQLite rows never contain raw token bodies unless the selected `SecretStore`
  intentionally uses encrypted SQLite blobs.
- Revocation deletes secret payload and marks the ref revoked.

## Reconnect Controller

Reconnect is an automation-session feature, not an auth feature.

Inputs:

- last disconnect type/reason;
- current automation state;
- target server config;
- auth mode and material ref;
- reconnect policy;
- attempt count;
- clock.

Contract:

```java
public interface ReconnectController {
  FppResult<ReconnectDecision> onDisconnect(AutomationSessionSnapshot session, DisconnectInfo info);
  FppResult<ResolvedAuth> resolveAuthForAttempt(AutomationProfile profile);
}

public sealed interface ReconnectDecision
    permits RetryAfter, StopReconnect, FailReconnect {}
```

Decision rules:

- If reconnect disabled: stop.
- If max attempts reached: fail.
- If owner revoked auth: fail.
- If auth material is expired and non-refreshable: fail.
- If server disconnect reason is clearly policy/auth ban/whitelist failure:
  fail until user action.
- If disconnect is network/server unavailable: retry with backoff.
- If MCProtocolLib follows a server transfer and policy allows transfers:
  treat transfer as continuity, not a reconnect attempt.

Backoff:

```text
delay = min(maxDelay, initialDelay * backoffMultiplier^(attempt - 1))
```

Add jitter only if many sessions reconnect to the same target.

## Failure Taxonomy

Use stable error codes. They become audit/status/test assertions.

| Code | Retry? | User action? | Notes |
| --- | --- | --- | --- |
| `auth.required` | No | Configure auth | Online-mode target without token/profile. |
| `auth.access_token.expired` | No | Re-auth or refreshable mode | Access-token-only cannot refresh. |
| `auth.refresh_failed_transient` | Yes | Maybe later | Network/service issue. |
| `auth.refresh_failed_permanent` | No | Re-auth | Invalid/revoked refresh material. |
| `auth.device_code_timeout` | No | Retry login | Device code expired or user did not complete. |
| `auth.profile_missing` | No | Account check | No Java profile/entitlement. |
| `auth.revoked` | No | Re-enable auth | User/operator revoked ref. |
| `session.join_failed_transient` | Yes | Maybe later | Sessionserver network/service issue. |
| `session.join_failed_permanent` | No | Check account/server | Bad token/profile, banned, or denied. |
| `upstream.network_disconnect` | Yes | No | Retry according to policy. |
| `upstream.kicked_policy` | No | Check target policy | Ban, whitelist, anti-cheat, automation denied. |
| `upstream.version_mismatch` | No | Change target/version | Protocol mismatch. |
| `automation.max_attempts_reached` | No | Manual reset | Backoff exhausted. |

## Consent Copy Requirements

Before storing reconnect-capable material, the limbo/config UI must state:

- The proxy can log into configured upstream servers as the user's Minecraft
  account while the local client is offline.
- Tokens may allow reconnects until expiry or revocation.
- Refresh-capable auth may allow long-lived reconnect until revoked or failed.
- The user can revoke stored auth material.
- Server rules still apply; automation may be disallowed by target servers.
- Operators can disable automation or revoke credentials.

Consent must be versioned. Updating this copy invalidates prior consent for
refreshable auth.

## Test Plan

Unit tests:

- `AccessTokenAuthResolver` rejects expired tokens with stable error code.
- `MicrosoftAuthService` does not run blocking flows on Velocity event thread.
- `AuthMaterialStore` never serializes raw tokens to audit/status objects.
- `ReconnectController` classifies retryable vs permanent errors.
- Backoff respects max attempts and max delay.
- Revoked auth refs fail closed.

Integration tests:

- Controlled online-mode server accepts a test account access token.
- Server restart triggers reconnect when token is valid.
- Expired access-token-only material fails without retry loop.
- Refreshable auth flow is skipped/disabled when `SecretStore` unavailable.
- Device code timeout produces `auth.device_code_timeout`.
- No raw access token, refresh token, MSA token, or serialized auth manager JSON
  appears in logs.

Manual/security review:

- Confirm release packaging obligations for MinecraftAuth LGPLv3 if directly
  bundled.
- Confirm operator policy for storing refresh-capable material.
- Confirm target-server authorization policy before enabling online reconnect.
