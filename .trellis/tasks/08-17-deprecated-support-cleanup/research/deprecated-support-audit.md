# Deprecated Support Audit

## Patch Metadata

`plugin/build.gradle.kts` loads `plugin/patch/velocity-base.properties`.
It reads the `commit` property and rejects a source checkout at another commit.
It also creates the disposable worktree at that commit.

No code reads the `repository` property. The build receives an existing source
checkout at `plugin/build/server/source`. The README must identify that source as
`https://github.com/PaperMC/Velocity.git`.

## Result Type Search

The `plugin` compile classpath contains MCProtocolLib, Adventure, Gson, Netty,
Guice, SLF4J, Lombok, and their transitive libraries.

The resolved classpath contains no generic `Result`, `Either`, `Try`, or
`Outcome`. Velocity supplies `CommandResult`, but that type controls command
routing. It does not carry a generic success value or domain failure.

Adding a library only to replace two small project classes would add more design
than it removes. Java 21 sealed types, records, and pattern matching can provide
the required generic result without a dependency.

`Result.java` can contain both variants as nested records. Members declared in an
interface are public and static, so this layout keeps one result source file and
does not reduce caller access. The exact declaration and exhaustive record switch
compile with the project's Java 21 toolchain.

## Replacement Contracts

- Synchronous packet and action operations return `Result<Void, String>`.
- `PermissionProvider.load()` returns `Result<Void, String>`.
- `PermissionProvider.grant()` returns `CompletableFuture<Result<String, String>>`.
- `PermissionProvider.revoke()` returns
  `CompletableFuture<Result<Optional<String>, String>>`.
- `Success(Optional.empty())` means that the saved operator name does not exist.
- `Failure(String)` carries the safe text that `ProxyError` carries now.
- Callers use record patterns only when they inspect a variant.

`AutomationManager.closeBackend()` has no caller. Remove it with its private
failure helper instead of changing its return type.

## Package Evidence

The project currently uses `com.fakeplayerproxy.util` for `ProxyError` and
`ProxyResult`. The approved package name is `com.fakeplayerproxy.utils`. Put the
replacement result types there. Move `PermissionProvider` from
`com.fakeplayerproxy.config` into the same renamed package. Do not retain the
singular `util` package.

`PermissionProviderTest` currently shares the `config` package because it calls
the provider's package-private executor constructor. Move that existing test to
the `utils` package with the provider. Update imports in the plugin and command
tests. No compatibility wrapper is required because these types are internal to
this plugin project.

## Existing Pair Type

The plugin module already uses `it.unimi.dsi.fastutil.Pair` in `Decoder` and
`World`. Use `Pair<Integer, Integer>` for the scheduled action period and
remaining ticks. This follows the Java language spec and requires no dependency
change.
