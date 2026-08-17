# Velocity Permission Research

## Scope

This research uses the project's fixed Velocity source.

- Repository: <https://github.com/PaperMC/Velocity>
- Commit: `843a47e2a38325309cd66133149fc9a984f76bb8`
- API: Velocity 3.4.0

The current Velocity 4.0.0 API was also checked on 2026-08-16. Its official
[permission package](https://jd.papermc.io/velocity/4.0.0/com/velocitypowered/api/permission/package-summary.html)
still exposes only the same permission building blocks. It does not add a grant
or persistence API.

## Result

Velocity does not have a built-in Minecraft OP concept.
It has no proxy OP list and no `Player.isOp()` method.

Velocity models authorization with permission strings.
Each `CommandSource` implements `PermissionSubject`.
Plugins call `hasPermission("permission.node")` or inspect a `Tristate` value.

The official [Command API](https://docs.papermc.io/velocity/dev/command-api/)
uses `invocation.source().hasPermission(...)` for command access.
The official [PermissionSubject API](https://jd.papermc.io/velocity/3.4.0/com/velocitypowered/api/permission/PermissionSubject.html)
defines `TRUE`, `FALSE`, and `UNDEFINED` permission results.

## Default Behavior

The fixed Velocity source gives players `ALWAYS_UNDEFINED` permissions.
`PermissionSubject.hasPermission()` converts `UNDEFINED` to `false`.
Thus, a player gets no restricted permission without a permission plugin.

The console starts with `ALWAYS_TRUE`.
Thus, the console has all permission nodes by default.

Velocity fires `PermissionsSetupEvent` once for each player and the console.
A permission plugin can replace the permission provider for either source.
The console can therefore lose a permission if a provider explicitly denies it.

## Backend OP State

A backend Minecraft server owns its OP list and permission levels.
Velocity does not read the backend `ops.json` file.
Velocity also does not copy backend OP status into its permission function.

The proxy can host multiple backend servers with different OP lists.
An automatic OP mapping would therefore have no single correct source.

Do not use backend OP status for `/player as` authorization.
Use a Velocity permission node instead.

## API Completeness

Velocity's permission API is a query and provider integration layer. It provides:

- `PermissionSubject` for querying a permission string.
- `Tristate` results: `TRUE`, `FALSE`, and `UNDEFINED`.
- `PermissionProvider` and `PermissionFunction` for supplying query results.
- `PermissionsSetupEvent` for selecting one provider when a subject starts.
- Brigadier `.requires(...)` integration for command visibility and execution.

The official
[PermissionsSetupEvent API](https://jd.papermc.io/velocity/3.4.0/com/velocitypowered/api/event/permission/PermissionsSetupEvent.html)
states that setup runs once per subject. The official
[PermissionFunction API](https://jd.papermc.io/velocity/3.4.0/com/velocitypowered/api/permission/PermissionFunction.html)
defines the query function and its three default results.

Velocity core does not provide:

- a user, group, role, or inheritance store;
- grant and revoke operations;
- persistence for plugin permissions;
- wildcard or context policy;
- a management command API for changing another plugin's permission data.

`PermissionsSetupEvent` runs once for each subject. Its mutable state holds one
provider. A plugin can wrap the provider that is present when its listener runs,
but another listener can replace that wrapper later. Provider composition is
therefore sensitive to event order. Registering a provider for one FPP flag
would also put FPP into the global permission path for every queried node.

The one-time setup does not force permission values to stay static. A provider
can return a function that reads live state. It still owns that update mechanism.

## Implementation Comparison

| Property | Direct local check | FPP provider wrapper | External permission provider |
| --- | --- | --- | --- |
| `/fpp op` can grant access | Yes | Yes | No portable write API |
| Command uses `hasPermission` | No | Yes | Yes |
| Extra plugin required | No | No | Yes |
| Stored data | FPP UUID set | FPP UUID set | Permission plugin data |
| Other permission nodes | Unchanged | Delegated | Owned by external provider |
| Groups and contexts | No | No | Provider-specific |
| Main risk | Bypasses standard API | Provider listener ordering | External dependency |

The selected design is the FPP provider wrapper. It combines a minimal local
`ops.json` store with the standard `PermissionSubject` query path. FPP owns only
`fakeplayerproxy.op`. It returns a local result for that exact node and delegates
every other node to the provider already present in `PermissionsSetupEvent`.

Register the wrapper at `PostOrder.LAST` so established permission plugins can
select their provider first. This reduces, but cannot eliminate, event-order
risk because another `LAST` listener registered later can still replace it.

## Command Permission Placement

`SimpleCommand.hasPermission()` controls the complete root command. It cannot
express a different rule for only one nested branch. Brigadier can place an
authorization predicate on the `as` literal without changing the `shadow`
branch.

The `as` requirement checks `source.hasPermission("fakeplayerproxy.op")` before
target lookup.
This order avoids target discovery through errors or suggestions.

## Target Lookup Constraint

The fixed Velocity patch unregisters a player after frontend logout.
It keeps the backend only when the plugin cancels `DisconnectEvent`.
Therefore, `ProxyServer.getPlayer(name)` cannot find a shadow player later.

`AutomationManager` currently keys its map by the exact Velocity player object.
It needs a stable lookup for `as <player>`.

Keep the existing exact-player map. Scan its active values for case-insensitive
command lookup and suggestions. The task does not need a second index or a map
key migration.

Do not make the command scan Velocity's public online-player list.
That list excludes the main target after `shadow` succeeds.

## Security Notes

The `as` permission grants control over another authenticated game connection.
This includes destructive actions when later child tasks add them.

The permission check must cover execution and suggestions.
The action layer must still validate the target session state.
The manager must reject stale entries after replacement or backend closure.

## Brigadier Permission Behavior

Velocity forwards a Brigadier command when its root requirement returns false.
A false requirement on a nested node does not forward the complete command.
Velocity handles that input as a local syntax failure.

The Brigadier method name is `.requires(Predicate<S>)`, not `.require()`.
Velocity's official
[Command API example](https://docs.papermc.io/velocity/dev/command-api/)
uses `.requires(source -> source.hasPermission("test.permission"))` and calls it
the intended place for permission checks. The fixed Velocity source also uses
child `.requires(...)` predicates for the built-in `/velocity` subcommands.

The fixed source tests distinguish placement explicitly:

- `testForwardsAndDoesNotExecuteImpermissibleAlias` verifies that a rejected
  root alias forwards.
- `testHandlesAndDoesNotExecuteWithImpermissibleNonAliasLevelNode` verifies that
  a rejected child remains locally handled.

The current `PlayerCommand` inherits `InvocableCommand.hasPermission()`, whose
default is `true`. Therefore, root-level rejection would be a behavior change.

Do not add a root requirement to `/player` or `/fpp` because both roots must stay
local. Use a nested requirement for `fakeplayerproxy.op`.
Use a normal `.requires(...)` predicate so suggestions use the same rule.

The command placement is:

```text
/player                    no requirement
/player as                 requires fakeplayerproxy.op
/fpp                       no requirement
/fpp op                    requires fakeplayerproxy.op
/fpp deop                  requires fakeplayerproxy.op
```

This keeps current `/player` ownership unchanged. Velocity filters denied child
nodes from the command graph and suggestions. If a root requirement returns
false, Velocity treats the alias as unknown and can forward it. If a child
requirement returns false after an accepted root, Velocity handles the input as
a local syntax failure instead.

## Minimal Operator Commands

The smallest reversible command surface is:

```text
/fpp op <player>       grant `/player as`
/fpp deop <player>     revoke `/player as`
```

Both commands use the same Brigadier `.requires(...)` check as `/player as`.
Thus, the console and every current FPP operator can update the trust set. Store
UUIDs, not names. A display name can be retained only for operator messages.
Persist a complete validated snapshot with an atomic replace so a failed write
does not partially change the ACL.

## Action Tree Reuse

Build each action node once and attach the same `CommandNode` instance below the
`/player` root and the `as <player>` argument. Brigadier nodes have child maps
but no parent reference, so sharing the node does not copy its executor or
descendants.

Shared action execution reads the command context to determine whether the
`player` argument is present. It resolves that named automation target when
present and otherwise uses the existing exact-source lookup. Target completion
uses one helper that reads current manager names for every request. Short
executors may remain inline; later complexity can justify extraction without
changing the shared-node design. No redirect, internal anchor, wrapper source,
or second action builder is needed.
