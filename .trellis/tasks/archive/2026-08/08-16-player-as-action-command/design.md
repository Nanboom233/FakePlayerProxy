# Player As Action Command Design

## Command Ownership

`PlayerCommand` becomes a command tree factory.
It creates one `BrigadierCommand` with the literal root `player`.

`FppCommand` becomes a second command tree factory.
It creates one `BrigadierCommand` with the literal root `fpp`.

The two roots have separate ownership and behavior. `/player` owns Carpet player
actions. `/fpp` owns plugin configuration.

## Initial Command Trees

```text
player                                           [always local]
├── shadow                                       [registered source player]
└── as                                            [fakeplayerproxy.op]
    └── <player>                                  [active automation names]
        └── shadow                                [same action node]

fpp
├── op <player>                                  [fakeplayerproxy.op]
└── deop <player>                                [fakeplayerproxy.op]
```

This child task preserves the existing `shadow` action.
Later children add action nodes through the same action-tree owner.
They construct each action once and attach the same node below both paths.

## Action Reuse

The command builds each action node once:

```text
action node: shadow
  ├── attached below /player
  └── attached below /player as <player>
```

Brigadier command nodes do not carry a parent reference, so one built action
node can belong to both child maps. The action definition and executor remain a
single object. Each built node is attached to both parent builders.

Target selection follows one rule:

```text
parsed `player` argument present -> AutomationManager.getByName(argument)
no `player` argument             -> AutomationManager.get(source player)
```

Thus, action code selects the target from the parsed context without duplicating
the action tree. The `as` literal exists only below the root, so it cannot be
recursive.

Target suggestions use one helper method. The method reads the manager's active
names when Brigadier requests suggestions instead of freezing them during
command registration. Short execution blocks can remain inline, and brief
comments separate action blocks:

```java
private CompletableFuture<Suggestions> suggestPlayers(
        CommandContext<CommandSource> context,
        SuggestionsBuilder builder) {
    automationManager.names().forEach(builder::suggest);
    return builder.buildFuture();
}

RequiredArgumentBuilder<CommandSource, String> targetArgument =
        argument("player", StringArgumentType.word())
                .suggests(this::suggestPlayers);

// Shadow
LiteralCommandNode<CommandSource> shadow = literal("shadow")
        .executes(context -> {
            // Resolve the parsed player argument when present; otherwise self.
            // Existing shadow behavior.
        })
        .build();

playerRoot.then(shadow);
targetArgument.then(shadow);
```

For example, a later `// Attack` block builds its complete grammar once before
attaching the local `attack` variable to both builders:

```text
attack
├── once
├── continuous
└── interval <ticks>
```

Because both paths hold the same `attack` node, they automatically expose:

```text
/player attack interval 10
/player as <player> attack interval 10
```

The `attack` builder appears once. Its executors select the target from the same
context rule, parse the mode, and invoke the target automation service. Whether
those executors stay inline or use a helper depends on their actual complexity;
the command structure does not require another action registry or second action
builder.

The task removes every old `/fpp` branch. It also removes the old proxy target,
username, and reconnect configuration model because those values are deprecated
and do not define the new configuration schema.

## Root Ownership And Requirements

The `/player` root has no `.requires(...)` predicate. The current
`SimpleCommand` owns `/player` for every source, so adding a root requirement
would change behavior by forwarding rejected commands to the backend.

The existing `shadow` executor keeps its exact-player and automation checks.
Only the new `as` literal uses `fakeplayerproxy.op`.

The `/fpp` command never forwards to a backend. It is a plugin-owned namespace.
The root remains locally visible. Protected nested nodes use requirements, so a
failed permission check cannot turn `/fpp` into a backend command.

The shared player `CommandHandler.runCommand` checks whether the command root is
registered by Velocity and usable by the player. This check confirms interception
before execution. It replaces the root executor workaround in `/player` and
`/fpp`.

After interception, the handler logs immediately and starts local execution. It
always returns the consumed packet result for that command. The local execution
result and an execution failure cannot switch the route back to the backend. An
unregistered or unusable root keeps Velocity's existing forwarding behavior. No
command-name allowlist is involved. The message body matches the Vanilla server
form:

```text
<username> issued server command: /<command>
```

The handler logs the command string passed to `runCommand`. The patch does not
add a setting or change an existing logging setting. Protocol-specific command
handlers require no routing or logging branch.

The Velocity patch set keeps feature ownership at the existing boundary.
`0001-login-relay.patch` owns login relay changes. The single
`0002-automation-extension.patch` owns automation, command-tree refresh, and
local player command logging. No `0003` or `0004` patch remains.

## Authorization

The `as`, `op`, and `deop` literals use this requirement:

```java
.requires(source -> source.hasPermission("fakeplayerproxy.op"))
```

The requirement hides protected branches and their suggestions from unauthorized
sources. Executors repeat no permission lookup. Brigadier has already admitted
the source to the branch.

Self actions do not require a permission node.
They can control only the source player's authenticated backend.

An FPP operator can grant or revoke other operators and can revoke itself. The
console remains a permanent recovery principal because the FPP provider always
returns `TRUE` for it. The design does not infer access from backend OP state.

`com.fakeplayerproxy.config.PermissionProvider` owns and builds the Velocity
permission-provider wrapper. A `PermissionsSetupEvent` listener at
`PostOrder.LAST` binds the provider already selected for that event. The same
class owns the live `Map<UUID, String>` and `ops.json` I/O; there is no separate
operator configuration object or entry record. Its permission function applies
this policy:

```text
fakeplayerproxy.op + console        -> TRUE
fakeplayerproxy.op + stored UUID    -> TRUE
fakeplayerproxy.op + other player   -> FALSE
every other permission node         -> delegate result
```

This makes `fakeplayerproxy.op` authoritative to FPP while retaining every other
permission result from an installed permission plugin. The returned function
reads the current immutable operator snapshot on each query. An `op` or `deop`
change therefore applies to connected subjects without another setup event.

## Automation Registry

`AutomationManager` keeps its existing exact Velocity-player map. This preserves
the current source identity and lifecycle behavior.

`getByName(name)` scans the active map values.
It compares the retained authenticated player name without case differences.

`names()` returns an immutable snapshot from the same map for suggestions.
It does not use `ProxyServer.getAllPlayers()`.

## Execution Flow

```text
CommandSource
  -> Brigadier local root
  -> protected child requirement when applicable
  -> target selection from command context
  -> shared action node
  -> AutomationManager lookup
  -> AutomationService action
  -> target EventLoop
  -> backend packet or lifecycle change
```

The command executor does not mutate target state directly.
`AutomationService` remains responsible for target EventLoop transfer.

## Errors And Messages

Brigadier handles missing literals and invalid argument shapes.
The existing self-action messages remain unchanged. The nested form adds only
the runtime message needed when its target cannot be resolved or is inactive.
The protected branch is checked before target lookup, so it does not reveal
target existence to an unauthorized source.

The plugin registers its server translation resources with Adventure's global
translator. Velocity renders globally registered translations against a player's
effective locale and the console locale. Commands and the shadow disconnect reason
therefore use translatable components without a literal console text branch.

## Command Tree Refresh

Velocity filters Brigadier nodes through `.requires(...)` when it injects proxy
commands into the backend command graph sent to a client. Changing the permission
function updates server-side authorization immediately, but it does not alter the
tree already cached by the client.

The fixed Velocity host therefore exposes a narrow player command-tree refresh.
It retains the current backend command graph, rebuilds the advertised graph through
the existing proxy-command injector and `PlayerAvailableCommandsEvent`, and writes
the new declaration on the frontend connection event loop. This preserves backend
commands and uses the same requirement filtering as the initial declaration.

After `ops.json` has been replaced and the immutable operator snapshot is
published, `/fpp op` or `/fpp deop` invokes that refresh for the affected player
when connected. Failed persistence does not refresh because authorization did not
change.

## Configuration Persistence

The old `ProxyConfig`, `ReconnectConfig`, property keys, default property
resource, and `ProtocolTarget` are removed. The build already pins the protocol
dependency, so `ProtocolTarget` has no remaining runtime responsibility. The FPP
operator UUID set gets a new schema and storage contract. There is no migration
because the removed fields have no meaning in the new model.

The file parser validates JSON shape while constructing the in-memory map.
Runtime mutations originate from authenticated players or existing map entries,
so saving does not repeat validation over already trusted state. A write failure
must retain the last valid in-memory and on-disk state. Configuration mutations
must not run on a Netty event loop when they do blocking file I/O.

The in-memory operator set backs `config.PermissionProvider`. A successful `op`
or `deop` operation publishes the complete new immutable set, so changes apply
without reconnecting.

The plugin stores the set in `<plugin-data-directory>/ops.json`:

```json
[
  {
    "uuid": "00000000-0000-0000-0000-000000000000",
    "name": "PlayerName"
  }
]
```

Only `uuid` participates in authorization. `name` supports messages and `deop`
when the player is offline. `op` resolves an online authenticated player
before it records the UUID. This avoids external profile lookup and ambiguous
offline-mode name conversion.

The update sequence is:

1. Copy the current immutable map and apply the requested mutation.
2. Write it to a sibling temporary file.
3. Replace `ops.json` atomically.
4. Publish the candidate as the active in-memory map.

If the write or replace fails, the command reports failure and keeps the prior
file and active snapshot. A missing file loads an empty set. A malformed file is
reported and never rewritten automatically. In that state the plugin fails
closed for player operators while the console remains able to diagnose and
replace the configuration through an explicit command.

## Compatibility

The task keeps `/player shadow` for the current self flow.
It adds `/player as <player> shadow` for remote control.
It does not provide a `/fpp player` alias.

`/player` and `/fpp` both remain local to the proxy. Unauthorized sources cannot
enter their protected child branches.
