# Deprecated Support Cleanup Design

## Patch Documentation

Rewrite the patch README as a short build contract. State the source repository,
the fixed commit input, the two build tasks, the disposable worktree, patch order,
and external test location.

Keep `velocity-base.properties` as the fixed commit input. Remove its unused
`repository` property. Do not duplicate the commit hash in the README.

## Result Shape

Use one public top-level type. Define both result variants inside the sealed
interface. Nested interface members are implicitly public and static.

```java
public sealed interface Result<T, E> permits Result.Success, Result.Failure {
    record Success<T, E>(T value) implements Result<T, E> {}
    record Failure<T, E>(E error) implements Result<T, E> {}
}
```

Do not add factory methods, state flags, optional accessors, or throwing accessors.
Callers use record patterns when they need a variant value. Use an exhaustive
switch only where both variants need separate behavior.

## Return Contracts

Automation methods use `Result<Void, String>`. A success carries `null` as the
`Void` value. A failure carries the current safe automation message directly.

`world.player.Player.send(...)` remains the lowest result owner. Composite methods
return the first failure or the final success. `AutomationService.runAction(...)`
keeps a function that returns the new result type.

`PermissionProvider.load()` returns `Result<Void, String>`. Failure carries the
same safe diagnostic text that `ProxyError.safeMessage()` carries now. The method
keeps its current publication order.

`PermissionProvider.grant()` returns
`CompletableFuture<Result<String, String>>`. Success carries the stored name.
Failure carries the current safe persistence message.

`PermissionProvider.revoke()` returns
`CompletableFuture<Result<Optional<String>, String>>`. A present value carries
the removed name. An empty value means no matching operator. Failure carries the
current safe persistence message.

`FakePlayerProxyPlugin` and `FppCommand` pattern-match `Success` and `Failure`.
Successful mutations keep their current command-tree refresh. Failures keep their
current log and translated response.

## Package Layout

Rename `com.fakeplayerproxy.util` to `com.fakeplayerproxy.utils`. Put `Result.java`
and `PermissionProvider` in the renamed package. `Result.java` owns the nested
`Success` and `Failure` records. Move
`PermissionProviderTest` from the `config` test package to `utils` so it retains
access to the package-private executor constructor. Update command, plugin, and
test imports. Do not leave a singular `util` package.

## Deletion Boundary

Delete `ProxyError.java` and `ProxyResult.java`. Add only `Result.java` for the
result abstraction under `utils`; do not add separate variant files. Move
`PermissionProvider.java`; do not retain a compatibility class in `config` or
`util`. Remove dead `AutomationManager.closeBackend()` and its failure helper.
Do not add a result factory or render helper.

Replace `AutomationService.ScheduledActionState` with
`it.unimi.dsi.fastutil.Pair<Integer, Integer>`, which the plugin module already
uses. Store the period in `left()` and the remaining ticks in `right()`. Keep the
current countdown behavior. Do not use an array and do not add a replacement
record, class, or dependency. Inline the two
`AutomationService.unavailable(...)` failure constructions and delete that
helper. Do not change the test set.

## Compatibility

Keep the `ops.json` format, atomic replacement, permission publication order,
command results, log detail, and automation behavior unchanged.
