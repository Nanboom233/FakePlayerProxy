# Deprecated Support Cleanup Plan

## Steps

1. Rewrite the existing content in `plugin/patch/README.md` under the `output`
   rules. Add the Velocity repository URL because it will no longer exist in the
   properties file. Do not add a new documentation section or build requirement.

2. Remove the unused `repository` line from
   `plugin/patch/velocity-base.properties`. Keep the `commit` line and the current
   Gradle loading path.

3. Replace the old utility package with
   `plugin/src/main/java/com/fakeplayerproxy/utils/Result.java`. Define
   `Success<T, E>` and `Failure<T, E>` as nested records inside the sealed
   `Result<T, E>` interface. Do not add separate variant files, factory methods,
   or accessor helpers. Do not leave a singular `util` package.

4. Move
   `plugin/src/main/java/com/fakeplayerproxy/config/PermissionProvider.java` to
   `plugin/src/main/java/com/fakeplayerproxy/utils/PermissionProvider.java`.
   Move
   `plugin/src/test/java/com/fakeplayerproxy/config/PermissionProviderTest.java`
   to `plugin/src/test/java/com/fakeplayerproxy/utils/PermissionProviderTest.java`
   so it can still use the package-private executor constructor. Update affected
   imports. Do not leave a forwarding class in `config` or `util`.

5. Change `world/player/Player.java` and `automation/AutomationService.java` to
   return `Result<Void, String>`. Return the first failure from composite actions.
   Use direct record patterns only where a caller must inspect the variant.

6. Remove the uncalled `AutomationManager.closeBackend()` method and its private
   `missing()` helper from
   `plugin/src/main/java/com/fakeplayerproxy/automation/AutomationManager.java`.
   This removal is required because both methods otherwise retain the deleted
   result types. Do not remove other automation methods.

7. Change `PermissionProvider.load()` and its private save operation to
   `Result<Void, String>`. Put the existing safe error text directly in `Failure`.
   Keep the current parsing, publication order, and atomic replacement behavior.
   Do not add a precheck method or layer.

8. Change `PermissionProvider.grant()` to
   `CompletableFuture<Result<String, String>>`. Preserve the stored name on
   success and the current safe persistence message on failure.

9. Change `PermissionProvider.revoke()` to
   `CompletableFuture<Result<Optional<String>, String>>`. Use an empty optional
   for an absent name. Preserve the current safe persistence message in `Failure`.

10. Update `FakePlayerProxyPlugin` and `FppCommand` with direct record patterns
    over `Result.Success` and `Result.Failure`. Handle absent revoke names from
    the successful `Optional`. Preserve translated messages, logs, and
    command-tree refresh. Remove the obsolete `FppCommand.render(...)` helper.

11. Delete
    `plugin/src/main/java/com/fakeplayerproxy/util/ProxyError.java` and
    `plugin/src/main/java/com/fakeplayerproxy/util/ProxyResult.java`. Update the
    single `ProxyResult` example and the `PermissionProvider` package ownership
    statement in `.trellis/spec/backend/velocity-plugin.md`.

12. Update only the existing assertions and imports affected by the new result
    shape and package paths. Do not add a result test or another test class.

13. In `AutomationService.java`, replace `ScheduledActionState` with the existing
    `it.unimi.dsi.fastutil.Pair<Integer, Integer>`. Use `left()` for the period
    and `right()` for remaining ticks. Do not use an array or add a replacement
    custom type or dependency. Preserve the immediate first action and existing
    repeat countdown. Remove `unavailable(...)` and inline its two
    `Result.Failure` constructions. Do not add or expand tests.

## File Boundary

Change only these product and specification paths:

- `plugin/patch/README.md`
- `plugin/patch/velocity-base.properties`
- `plugin/src/main/java/com/fakeplayerproxy/FakePlayerProxyPlugin.java`
- `plugin/src/main/java/com/fakeplayerproxy/automation/AutomationManager.java`
- `plugin/src/main/java/com/fakeplayerproxy/automation/AutomationService.java`
- `plugin/src/main/java/com/fakeplayerproxy/command/FppCommand.java`
- `plugin/src/main/java/com/fakeplayerproxy/command/PlayerCommand.java`
- `plugin/src/main/java/com/fakeplayerproxy/config/PermissionProvider.java` (move)
- `plugin/src/main/java/com/fakeplayerproxy/util/ProxyError.java` (delete)
- `plugin/src/main/java/com/fakeplayerproxy/util/ProxyResult.java` (delete)
- `plugin/src/main/java/com/fakeplayerproxy/utils/PermissionProvider.java` (move target)
- `plugin/src/main/java/com/fakeplayerproxy/utils/Result.java` (add)
- `plugin/src/main/java/com/fakeplayerproxy/world/player/Player.java`
- `plugin/src/test/java/com/fakeplayerproxy/automation/AutomationServiceTest.java`
- `plugin/src/test/java/com/fakeplayerproxy/command/FppCommandTest.java`
- `plugin/src/test/java/com/fakeplayerproxy/command/PlayerCommandTest.java`
- `plugin/src/test/java/com/fakeplayerproxy/config/PermissionProviderTest.java` (move)
- `plugin/src/test/java/com/fakeplayerproxy/utils/PermissionProviderTest.java` (move target)
- `.trellis/spec/backend/velocity-plugin.md`

## Validation

Run one focused command. It compiles all affected main and test sources, then
runs only the three existing tests that exercise changed result behavior.

```powershell
.\gradlew.bat :plugin:test --tests com.fakeplayerproxy.automation.AutomationServiceTest --tests com.fakeplayerproxy.utils.PermissionProviderTest --tests com.fakeplayerproxy.command.FppCommandTest
```

Check only the changed product and specification paths for whitespace errors.

```powershell
git diff --check -- plugin/src plugin/patch/README.md plugin/patch/velocity-base.properties .trellis/spec/backend/velocity-plugin.md
```

Do not run or add an `AutomationManager` result test because the removed method
has no caller.
