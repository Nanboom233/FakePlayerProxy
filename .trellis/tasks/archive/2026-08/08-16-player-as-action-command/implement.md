# Player As Action Command Implementation Plan

## Steps

1. Keep the existing exact-player `AutomationManager` map.
   Add case-insensitive name lookup and an immutable name snapshot by scanning
   active values.

2. Update `AutomationManagerTest` with focused coverage for case-insensitive
   lookup of an active shadow player and exclusion of inactive entries.

3. Replace `PlayerCommand implements SimpleCommand` with a Brigadier factory.
   Build the `player` root and authorized `as <player>` branch.
   Reference one suggestion helper from the target argument. Have the helper
   read the manager's current active names for every suggestion request.

4. Build the `shadow` action node once and attach the same node instance below
   `/player` and `as <player>`. Preserve existing execution and
   error behavior. Use a short `// Shadow` section and an inline builder.

5. Centralize the target-selection rule in the shared action execution: read the
   parsed `player` argument when present, otherwise use the existing exact-source
   lookup. Keep short execution blocks inline; extract a helper only if the
   resulting implementation has enough complexity or reuse to justify it. Do
   not use a redirect, internal action anchor, or command-source wrapper.

6. Remove the deprecated `ProxyConfig`, `ReconnectConfig`, loader,
   `ProtocolTarget`, bundled properties, and their focused tests after
   confirming no remaining consumers.

7. Add `com.fakeplayerproxy.config.PermissionProvider` as the single owner of
   the operator `Map<UUID, String>`, atomic `ops.json` persistence, executor,
   and Velocity provider integration. Parse and validate file entries once
   while loading. Do not create `OperatorConfig`, an operator entry record, or
   a second candidate-validation method. Wrap the provider selected by
   `PermissionsSetupEvent` at `PostOrder.LAST`, resolve only
   `fakeplayerproxy.op`, and delegate every other permission node.

8. Replace `FppCommand implements SimpleCommand` with a Brigadier factory.
   Add only `op <player>` and `deop <player>` branches.
   Apply the `fakeplayerproxy.op` `.requires(...)` predicate to both.
   Keep the `/fpp` root local and hide both branches from unauthorized sources.
   Remove all old branches, array slicing, and manual suggestion parsing.

9. Update `FakePlayerProxyPlugin` command and event registration.
   Inject the action manager into `/player` and the new authorization
   configuration service into `/fpp` without coupling the command trees.
   Register the permission provider listener before accepting players.

10. Replace `PlayerCommandTest` with focused Brigadier behavior tests for the
    unchanged self shadow form, authorized target shadow, denied `as`, missing
    targets, and live target suggestions.

11. Replace `FppCommandTest` with behavior tests for authorized `op` and `deop`,
    denied branch visibility, persistence across reload, and rejected malformed
    or failed writes.

12. Add a compact `PermissionProviderTest` covering loading, persistence, and
    the permission matrix without testing removed intermediary types.

13. Add translatable command messages to plugin language resources.
   Remove stale usage text from command code and product documentation.

14. Update the Velocity plugin specification and operation guide.
    Record local ownership of both command roots, nested authorization,
    authorization configuration, and removal of the old alias and configuration.

15. Fix console translation. Register the plugin UTF-8 resource bundles with one
    Adventure translation store during plugin initialization. Remove the same
    source during shutdown. Include command messages and the shadow disconnect
    reason. Keep their call sites as translatable components.

16. Fix stale in-game suggestions after `/fpp op` and `/fpp deop`. Add
    `Player.refreshCommands()` to the patched Velocity API. Retain the untouched
    backend command root. Rebuild the client tree through Velocity's injector and
    `PlayerAvailableCommandsEvent` on the frontend EventLoop. Use a revision to
    stop an older asynchronous rebuild from replacing a newer tree.

17. Call `refreshCommands()` only after `PermissionProvider` has persisted and
    published a successful operator change. Refresh only the affected connected
    player. Keep the existing focused command and graph-copy tests.

18. Remove the incorrect bare-root workaround. Remove the inline root executors
    from `/player` and `/fpp`. Remove the two plugin tests that assert their
    synthetic return value.

19. Fix interception and logging in the shared `CommandHandler.runCommand`.
    Before execution, confirm that Velocity owns the command root and the player
    can use it. Log immediately with `{} issued server command: /{}`. Execute the
    command locally, then consume it regardless of the execution result. Never
    forward a confirmed interception. Preserve forwarding for an unregistered or
    unusable root. Do not add a logging configuration, command-name allowlist,
    plugin event listener, protocol-specific branch, call-site change, or new test.

20. Consolidate the Velocity changes. Apply `0001`, the current `0002`, and the
    approved refresh and logging changes in a temporary pinned Velocity worktree.
    Regenerate `0002-automation-extension.patch` against the tree after `0001`.
    Delete `0003-command-tree-refresh.patch` and
    `0004-command-execution-log.patch`. Restore `plugin/build.gradle.kts` and the
    patch README to the two-patch list.

## Validation

Run narrow checks after implementation:

```powershell
.\gradlew.bat :plugin:test --tests com.fakeplayerproxy.command.FppCommandTest --tests com.fakeplayerproxy.command.PlayerCommandTest
# In the disposable patched Velocity worktree:
.\gradlew.bat :velocity-proxy:compileJava
.\gradlew.bat :velocity-proxy:test --tests com.velocitypowered.proxy.command.CommandGraphInjectorCopyTests
git diff --check
```

Inspect the regenerated `0002` to confirm that it contains both approved fixes.
Confirm that the interception path uses no logging configuration or command-root
allowlist. Run the diff check in the applied Velocity worktree because the outer
repository diff treats valid U80 patch context prefixes as trailing whitespace.
