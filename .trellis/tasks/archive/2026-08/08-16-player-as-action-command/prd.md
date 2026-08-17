# Player As Action Command

## Goal

Change `PlayerCommand` from `SimpleCommand` to Brigadier without changing the
behavior of its existing `/player shadow` command. Add the custom nested command
form `/player as <player> <action>` for an authorized operator.

Rewrite `/fpp` as the plugin configuration entry. Its first configuration is a
minimal operator list managed through `/fpp op` and `/fpp deop`. None of the
current `/fpp` content remains.

This child task defines the shared command and target layers.
Later child tasks add each Carpet action to the `<action>` branch.

## Background

- Velocity has no built-in OP list or `isOp()` API.
- Velocity uses permission nodes through `PermissionSubject`, but its core API
  only queries permissions and installs a provider.
- A player has `UNDEFINED` permissions without a permission provider.
- `hasPermission()` converts `UNDEFINED` to `false`.
- The console gets all permissions by default.
- A permission plugin can replace player and console permission functions.
- Velocity has no portable grant, revoke, user store, group, or persistence API.
- Backend OP state does not automatically reach Velocity.
- A shadow player leaves Velocity's public player registry after frontend logout.
- `AutomationManager` keeps the shadow player in its private exact-player map.
- The current manager cannot find a shadow player by name or UUID.

## Requirements

1. Replace `PlayerCommand` and `FppCommand` with Brigadier command factories.
   Keep both roots local, consume the bare `/player` and `/fpp` invocations,
   and put no permission requirement on either root.
2. Preserve the behavior, errors, and suggestions of `/player shadow` while
   adding `/player as <player> <action>` below the same root.
3. Apply `.requires(source ->
   source.hasPermission("fakeplayerproxy.op"))` to `/player as`, `/fpp op`, and
   `/fpp deop`. Do not derive this permission from backend OP state.
4. Build each complete player action node once and attach the same node below
   `/player` and `/player as <player>`. Do not duplicate the action tree or use
   a redirect, internal anchor, or wrapped command source.
5. In shared action execution, use the parsed `player` argument when present and
   otherwise preserve the existing exact-source lookup. Continue dispatching
   target actions through the automation service and target event loop.
6. Keep the current exact-player `AutomationManager` map. Resolve names by
   scanning active values without case differences; do not rely on Velocity's
   public player registry or add another index.
7. Provide target completion through one helper referenced by the Brigadier
   argument. Read current active manager names for every request. Protected
   suggestions must remain hidden behind the `as` requirement.
8. Keep command construction segmented with short action comments. Short
   execution blocks may remain inline; extract code when actual complexity or
   reuse justifies it.
9. Rewrite `/fpp` as the local plugin configuration root with only
   `op <player>` and `deop <player>`. Remove `status`, `connect`, `disconnect`,
   `look-north`, and `player`.
10. Store operator entries in `<plugin-data-directory>/ops.json` as a JSON array
    of `{ "uuid": <uuid>, "name": <name> }`. Authorize by UUID; retain the name
    for display and offline revocation.
11. Resolve `op` against an online authenticated player. Allow `deop` to remove
    a saved operator by name. Persist a complete snapshot atomically and publish
    it to memory only after the file update succeeds.
12. Treat a missing operator file as an empty set. Reject malformed content
    without granting player access or automatically replacing the file.
13. Add `com.fakeplayerproxy.config.PermissionProvider` through
    `PermissionsSetupEvent` at `PostOrder.LAST`. The same class owns the minimal
    `Map<UUID, String>` operator state, `ops.json` persistence, and Velocity
    provider behavior. Do not add a separate config holder, two-field operator
    record, or a second validation pass over already parsed state. Return `TRUE`
    for the console and stored UUIDs, `FALSE` for other players, and delegate
    every other node to the previously selected provider.
14. Remove `ProxyConfig`, `ReconnectConfig`, the old loader, bundled properties,
    `ProtocolTarget`, their focused tests, and stale command documentation. No
    migration or compatibility layer is required.
15. Register the plugin translations with Adventure's global translator so
    Velocity can render command results and the shadow disconnect reason for the
    console. Keep the same translatable components for players and do not add a
    hard-coded console-only message path.
16. After a successful operator grant or revoke publishes the new permission
    snapshot, refresh the affected connected player's advertised Brigadier tree.
    The refresh must preserve backend commands and reapply Velocity's normal
    `.requires(...)` filtering; reconnecting must not be required.
17. Patch the shared Velocity player `CommandHandler` so a registered and usable
    Velocity command root confirms interception before execution. Log the command
    immediately with Vanilla's `<player> issued server command: /<command>`
    content. After interception, consume the command regardless of its execution
    result and never forward it to the backend. Do not use a command-name
    allowlist. Do not add or change a logging configuration.
18. Keep all Velocity automation runtime changes in
    `0002-automation-extension.patch`. Merge the command-tree refresh and command
    logging changes into `0002`. Remove `0003` and `0004`.

## Acceptance Criteria

- [ ] `/player` and `/fpp` use Brigadier, remain proxy-local, and expose no root
      permission requirement.
- [ ] Bare `/player` and `/fpp` invocations are intercepted by Velocity and never
      forwarded to the backend, without a root executor workaround.
- [ ] Existing `/player shadow` behavior remains unchanged.
- [ ] An authorized source can use `/player as <player> shadow`; an unauthorized
      source cannot enter or receive suggestions for `as`, `op`, or `deop`.
- [ ] Direct and `as` forms share one complete action node, and `as` is not
      recursive.
- [ ] Active shadow players remain discoverable by case-insensitive name after
      leaving Velocity's public player registry; suggestions reflect live names.
- [ ] `/fpp op` grants and `/fpp deop` revokes `fakeplayerproxy.op` immediately,
      including for connected subjects, and the result survives restart.
- [ ] The console always receives the FPP permission, unlisted players do not,
      and unrelated permission nodes retain the prior provider's result.
- [ ] Failed or malformed persistence does not publish unintended player access
      or destroy the last valid file.
- [ ] Only the approved `ops.json` schema and `/fpp op|deop` configuration
      surface remain; all deprecated configuration and command branches are gone.
- [ ] `PermissionProvider` directly owns operator storage and permission queries;
      no `OperatorConfig`, operator value record, or `ProtocolTarget` remains.
- [ ] Console command results render translated text instead of raw
      `fakeplayerproxy.command.*` keys.
- [ ] The Velocity disconnect log renders the shadow reason instead of the raw
      `fakeplayerproxy.disconnect.shadow` key.
- [ ] Granting or revoking an online player updates the client's visibility and
      suggestions for `/fpp op|deop` and `/player as` without reconnecting.
- [ ] Every player command that Velocity handles appears in the Velocity log as
      `<username> issued server command: /<command>`, without a command-name
      allowlist. Forwarded commands do not use this log path.
- [ ] After Velocity confirms interception, no execution return value or failure
      can forward that command to the backend.
- [ ] The patch set contains only `0001-login-relay.patch` and
      `0002-automation-extension.patch`; `0002` contains the command-tree refresh
      and command logging changes.

## Out of Scope

- Reading `ops.json` from a backend server.
- Synchronizing backend permission levels with Velocity.
- Groups, inheritance, contexts, wildcards, and general-purpose permissions.
- Adding action implementations beyond the shared nested command layer.
- Controlling an automation player on another proxy instance.
- Reintroducing the old target, username, reconnect, status, or disconnect
  configuration and command behavior.

## Research

See `research/velocity-permissions.md`.
