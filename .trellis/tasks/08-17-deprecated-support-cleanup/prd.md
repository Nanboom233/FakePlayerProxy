# Remove Deprecated Support Abstractions

## Goal

Remove the unused patch property and replace the redundant `ProxyError` and
`ProxyResult` pair. Keep each remaining file and return type tied to a current
operation.

## Background

- `plugin/patch/README.md` does not follow the `output` writing rules.
- `plugin/build.gradle.kts` reads `commit` from
  `plugin/patch/velocity-base.properties`.
- No code reads the `repository` property.
- The current compile classpath has no generic `Result`, `Either`, or `Try`.
- Velocity `CommandResult` describes command routing and cannot store domain data.
- `ProxyError` and `ProxyResult` serve unrelated synchronous and asynchronous operations.

## Requirements

1. Rewrite `plugin/patch/README.md` with short, active, single-purpose sentences.
2. Keep `velocity-base.properties` because the build reads its fixed commit.
3. Remove the unused `repository` property from `velocity-base.properties`.
4. Put the Velocity repository URL in the README because the build code does not show it.
5. Replace `ProxyResult` with a generic sealed `Result<T, E>`.
6. Define the two variants as nested single-value records named `Success` and
   `Failure` inside `Result.java`.
7. Delete `ProxyError`. Use the generic error value directly.
8. Use the current safe error text directly as the `String` error value.
9. Use `Optional` inside a successful revoke result for an expected missing name.
10. Use direct record patterns where callers inspect a result variant.
11. Rename the utility package from `com.fakeplayerproxy.util` to
    `com.fakeplayerproxy.utils`.
12. Move `PermissionProvider` from `com.fakeplayerproxy.config` to
    `com.fakeplayerproxy.utils` with the result types.
13. Move the existing provider test to the matching package and update imports.
14. Delete the uncalled `AutomationManager.closeBackend()` method and its private
    `missing()` helper instead of converting them to the new result type.
15. Update affected imports and existing assertions. Update the single
    `ProxyResult` example in the Velocity plugin specification. Do not add
    factories, helpers, or new tests.
16. Replace `AutomationService.ScheduledActionState` with the plugin module's
    existing `it.unimi.dsi.fastutil.Pair<Integer, Integer>`. Preserve the current
    first-send and repeat timing. Do not use an array or add another state type.
17. Remove `AutomationService.unavailable(...)` and construct its two short
    `Result.Failure` values inline.

## Acceptance Criteria

- [ ] The patch README follows the `output` rules and identifies the Velocity source repository.
- [ ] `velocity-base.properties` contains only the fixed commit used by the build.
- [ ] The build still checks out and validates the fixed Velocity commit.
- [ ] `ProxyError` and `ProxyResult` do not exist or appear in source and specifications.
- [ ] `Result.java` contains the sealed interface and its nested `Success` and
      `Failure` records.
- [ ] Result consumers use record patterns without access helpers.
- [ ] Automation failures carry their current safe message as `String`.
- [ ] Operator parse and persistence failures carry their current safe message as `String`.
- [ ] A missing revoke target uses `Success(Optional.empty())`.
- [ ] Operator load failures keep their diagnostic message and fail closed.
- [ ] `PermissionProvider` and its existing test use `com.fakeplayerproxy.utils`.
- [ ] No production or test source imports `com.fakeplayerproxy.config.PermissionProvider`.
- [ ] No production or test source declares or imports `com.fakeplayerproxy.util`.
- [ ] `AutomationManager.closeBackend()` and its private `missing()` helper no longer exist.
- [ ] `AutomationService` has no `ScheduledActionState` type or `unavailable(...)` helper.
- [ ] Scheduled actions keep their current immediate first send and repeat interval.
- [ ] Existing focused tests pass after their assertions use the new return contracts.

## Out Of Scope

- Adding a functional programming library.
- Changing command behavior, permission rules, action behavior, or persistence format.
- Adding an exception hierarchy, result factory, result helper, or test category.
