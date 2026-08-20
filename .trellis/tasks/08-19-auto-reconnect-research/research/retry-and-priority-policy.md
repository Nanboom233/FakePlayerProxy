# Auto-reconnect retry and priority policy

Date: 2026-08-19

## Enablement

Every `/fpp auto-reconnect on` command starts a new client consent request. The
client does not save the consent decision.

A decline keeps auto-reconnect disabled. The proxy stores no access token for
that request. The request has no time limit.

## Retry sequence

Backend loss starts attempt 1 immediately.

| Failed attempt | Delay before the next attempt |
| --- | --- |
| 1 | 10 seconds |
| 2 | 10 seconds |
| 3 | 30 seconds |
| 4 | 30 seconds |
| 5 | 60 seconds |
| 6 | 60 seconds |
| 7 and later | 300 seconds |

The policy has no attempt limit. Only one next retry due time, waiting channel,
or active reconnect attempt can exist for one player.

The retained automation tick checks the next retry due time. The backend
priority list can delay an eligible attempt. The retry counter advances only
after the priority list releases the channel and the login attempt fails.

Ready PLAY resets the retry sequence. The service reaches ready PLAY after it
rebuilds local state and sends `ServerboundPlayerLoadedPacket`. A later backend
loss therefore starts with another immediate attempt.

## Stop conditions

Only recognized duplicate-login, profile-ban, and IP-ban kick components
actively disable auto-reconnect.

Every other backend kick remains retryable. Every transport failure remains
retryable.

A terminal disable during reconnect clears the Minecraft access token and next
retry due time. It closes any waiting or active reconnect channel.

`/fpp auto-reconnect off` requires the active frontend and backend. It only
sets `autoReconnect=false` and clears the token.

`/player kill` disables auto-reconnect before it closes the Shadow backend. It
clears the token and the next retry due time. It removes any waiting reconnect
channel. The backend close caused by `kill` must not start a new retry.

An authentication rejection from the session service also disables
auto-reconnect. This event is not a backend kick.

Authlib reports a credential or account rejection through
`InvalidCredentialsException`, `UserBannedException`,
`ForcedUsernameChangeException`, or `InsufficientPrivilegesException`. These
results prove that the held authorization cannot complete the new Login.

`AuthenticationUnavailableException` does not disable auto-reconnect. Authlib
uses it for network failure and HTTP 5xx responses. The retry policy handles
that failure.

HTTP 429 also keeps auto-reconnect enabled. The controller treats rate limiting
as a failed attempt and uses the approved retry sequence.

An unknown authentication failure does not clear the token. The controller
records the failure and retries because it cannot prove a credential rejection.

## Backend priority

The common backend output gate owns one priority list for each resolved backend
address.

Each priority list contains two FIFO lists:

- The high list contains real-player Login channels.
- The low list contains Shadow auto-reconnect channels.

The priority list always selects the high list first. This rule can delay a
Shadow reconnect while real players continue to log in.

Priority does not interrupt an active four-second slot. It only selects the
next waiting channel.

## Existing-state detection

The gate does not need a new login-path type. It inspects the existing
`MinecraftConnection` association on the first outbound write.

An associated `VelocityServerConnection` with `isLogoutCancelled()==true` is a
Shadow continuation. It uses low priority.

All other channels use high priority. This default includes normal login,
provisional relay, and raw Transfer paths.
