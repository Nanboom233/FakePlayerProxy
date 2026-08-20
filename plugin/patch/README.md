# Velocity Patches

This directory contains production patches for the pinned Velocity source.

## File Names

Each file uses this form:

```text
<four-digit-sequence>-<product-feature>.patch
```

The four-digit sequence shows the application order. The product feature names
one large feature.

A large feature forms one delivery and review unit. Files, classes, methods,
and internal parts remain implementation details.

## Feature Layout

Each patch contains one large feature and its related internal parts. One patch
can therefore change several related classes.

Each behavior first appears in one patch. Later patches use that result instead
of repeating the behavior.

Each later patch starts from the result of the earlier patches. It can use an
earlier API or add a small extension for its feature.

Separate patch files hold unrelated features. Production patches contain no
test source, formatting churn, or EOF-only changes.

## Format And Application

Each file uses the standard Git unified diff format. Each header uses paths from
the Velocity repository root.

For example, a header can contain `a/proxy/...` and `b/api/...`.

`velocity-base.properties` names the Velocity base commit. The build checks that
`plugin/build/server/source/` uses this commit and remains clean.

The build creates a disposable clone at `plugin/build/server/work/`. Grgit
applies the patch files there in ascending file-name order.

The build removes the clone after the Gradle task ends. The pinned source
checkout stays unchanged.

`0002-automation-extension.patch` starts with the result from
`0001-login-relay.patch`. `0003-login-session.patch` starts with both earlier
results.

## Gradle Tasks

| Task | Purpose |
| --- | --- |
| `:plugin:assembleVelocityHost` | Applies the production patches and builds the Velocity JAR. |
| `:plugin:patchCheck` | Applies the patches, copies patch tests, and runs Velocity tests. |
| `:plugin:releaseJar` | Builds the Velocity JAR and the FakePlayerProxy plugin JAR. |
| `:plugin:runServer` | Builds the release files and runs Velocity from `plugin/run/`. |

## Patch Tests

Patch tests are in `plugin/patch/test/`. The production patches stay separate
because they contain deployable Velocity source.

`:plugin:patchCheck` copies these tests into the disposable clone. Other tasks
copy no patch tests.

## Patch Contents

### `0001-login-relay.patch`

This patch implements the modified Server Hello relay. It classifies the client
as a Mod client or a Vanilla client.

The Mod path relays target online-mode encryption. It also synchronizes
post-login and configuration state.

The Vanilla path completes a short first login. It then sends Transfer to the
same listener.

The second login uses an opaque raw tunnel to the fixed target. This patch does
not contain a separate four-second Transfer delay.

### `0002-automation-extension.patch`

This patch adds the Velocity extensions for FakePlayerProxy automation. It
groups the related parts of this feature in one patch.

It adds the packet event API and packet dispatch. It also adds Disconnect
cancellation and Shadow backend retention.

It adds the direct MCProtocolLib packet path for backend automation packets.
It keeps proxy commands local and writes their execution log.

It refreshes the command tree sent to the client. It also adds host dependencies
and runtime hooks for these features.

### `0003-login-session.patch`

This patch implements auto-reconnect as one large feature.

It records the exact backend source on clientbound packet events. It adds one
priority queue for backend Login channels.

The queue serves normal Login channels before Shadow reconnect channels. It
keeps FIFO order inside each priority and uses one four-second gate per address.

The queue owns the four-second delay that the Transfer path previously used.

The patch lets a Shadow player reconnect to the same target with an access
token. Authlib joins the target online-mode session.

It rebuilds headless CONFIG for the retained Shadow session without a writable
frontend. It also adds the dependencies that this feature uses.
