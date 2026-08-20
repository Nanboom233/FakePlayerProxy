# Auto-reconnect logging contract

Date: 2026-08-19

## Purpose

The logs must let an operator reconstruct one auto-reconnect lifecycle. They
must not expose the Minecraft access token.

Each lifecycle log includes the player name, player UUID, backend server name,
attempt number, and event category when those values exist.

## Required INFO events

`AuthManager` owns these events:

- the player starts an auto-reconnect consent request
- the client accepts consent and auto-reconnect becomes enabled
- the client declines consent
- an explicit command disables auto-reconnect

`AutomationManager` owns these events:

- Shadow activates with auto-reconnect enabled
- a backend loss schedules a reconnect attempt
- a reconnect attempt is submitted to the backend connection path
- the reconnected backend reaches ready PLAY
- a fresh same-UUID login replaces the reconnecting Shadow
- plugin shutdown clears an enabled auto-reconnect session

## Required WARN events

`AutomationManager` owns these events:

- a reconnect attempt fails and schedules another attempt
- a retryable backend kick starts reconnect wait
- a recognized duplicate-login or ban kick disables auto-reconnect
- the session service rejects the credential or account
- a new required resource pack disables auto-reconnect
- any Code of Conduct request disables auto-reconnect
- a backend Transfer packet disables auto-reconnect
- `/player kill` disables auto-reconnect

`AuthManager` owns these events:

- the client response has the wrong size or format
- a backend sends the reserved auto-reconnect channel

`AutomationManager` owns this event:

- a stale reconnect callback or stale backend packet is ignored

A failed-attempt log includes the failure category and next delay. It does not
include exception text when that text can contain a request or credential.

## Required ERROR events

- the reconnect state cannot satisfy a valid next condition
- the common backend priority list cannot release or fail pending writes
- the reconnected backend cannot attach atomically
- terminal cleanup cannot cancel or close reconnect work
- an unexpected controller exception forces terminal cleanup

An ERROR event includes the event category and attempt number. It can
include a stack trace only when the exception cannot contain token data.

## Forbidden log content

- the Minecraft access token
- any token prefix, suffix, hash, or length used as an identifier
- the custom payload bytes
- an Authorization header
- the session join request body
- a serialized credential object
- an exception or HTTP body that can contain these values

## Noise boundary

Do not log the fixed-rate automation tick. Do not log every queued packet. Log
only a reconnect state change, attempt result, security rejection, or cleanup
failure.

## Message form

Use `Auto-reconnect` as the fixed feature name in every log. Do not alternate
between reconnect, recovery, relogin, and retry as feature names.

Use these message forms:

```text
Auto-reconnect consent requested for <name> (<uuid>) on backend <backend>.
Auto-reconnect enabled for <name> (<uuid>) on backend <backend>.
Auto-reconnect attempt <attempt> for <name> (<uuid>) started on backend <backend>.
Auto-reconnect attempt <attempt> for <name> (<uuid>) failed during <event> on backend <backend>: <category>. Next attempt in <delay> seconds.
Auto-reconnect reached ready PLAY for <name> (<uuid>) on backend <backend> after attempt <attempt>. Automation resumed.
Auto-reconnect disabled for <name> (<uuid>) on backend <backend>: <category>.
```

The category is a fixed project value. Do not insert a raw token, payload,
request body, HTTP body, or exception message into the category position.
