# Patched Velocity Runtime Dependency Debug

## Symptom

`plugin/run/logs/latest.log` showed backend Configuration decoding failing in
`MinecraftDecoder.dispatchPacketEvent` while constructing
`ClientboundSelectKnownPacks`:

```text
NoClassDefFoundError: it/unimi/dsi/fastutil/ints/IntList
```

## Root Cause

MCProtocolLib and its declared non-Netty transitive dependencies were inputs to
the Velocity Shadow task. The final Velocity JAR already contained Cloudburst
NBT/math, Gson, Adventure Gson serialization, MinecraftAuth, its HTTP/Gson
dependencies, and split fastutil map artifacts.

Velocity's own `proxy/build.gradle.kts` deliberately removed large portions of
fastutil from the Shadow output. Its exclusion list removed `IntList*`,
`IntArrayList`, and `Int2Int*`, all directly referenced by MCProtocolLib 26.2.
The failure was therefore Shadow filtering, not Gradle dependency resolution.

A complete `jdeps` comparison of MCProtocolLib's non-JDK references against the
assembled Velocity JAR found one additional omission: MCProtocolLib's
`ClientListener` directly invokes `lombok.Lombok.sneakyThrow`. Upstream does not
declare Lombok as a runtime dependency, so Patch 0002 supplies it explicitly as
`runtimeOnly`.

## Fix And Regression Boundary

`0002-automation-extension.patch` retains Velocity's fastutil trimming except
for the three MCProtocolLib-required families. Netty remains excluded from the
Plugin's MCProtocolLib dependency and remains owned by Velocity.

`plugin/patch/test/runtime/VelocityRuntimeSmoke.java` is compiled and executed
in a new JVM with only its compiled class plus the final release `velocity.jar`
on the classpath. It constructs and decodes `ClientboundSelectKnownPacks`, then
round-trips a non-air `ChunkSection`. This exercises the missing fastutil list
and int-map paths without relying on Plugin or Gradle test runtime dependencies.
The smoke also executes `lombok.Lombok.sneakyThrow`, covering the remaining
runtime dependency discovered by the bytecode audit. Netty remains managed by
Velocity and is not added or shaded separately.

## Verification

- A forced `:plugin:releaseJar --rerun-tasks` rebuild completed and ran the isolated
  smoke successfully. The resulting `velocity.jar` SHA-256 is
  `7FC8971AA2E92BEC5AC86E770A2A25FBE37D46555C328C8555C9D3DB51C4ECB4`.
- A complete `jdeps` audit found 916 distinct non-JDK classes referenced by the
  pinned MCProtocolLib JAR and zero missing from the rebuilt Velocity JAR.
- The Plugin release JAR contains no fastutil, MCProtocolLib, Netty, or Lombok
  classes; these dependencies remain owned by the patched host.
- Patch tests and all 43 Plugin tests passed.
- The rebuilt proxy started from `plugin/run` at 23:30, reached its `Done (3.68s)!`
  log line, and listened on port 25564 without a class loading error.
- The configured backend port `127.0.0.1:25566` was not listening during the
  final check, so a fresh real backend Configuration exchange could not be
  initiated automatically. The previous failing SelectKnownPacks constructor
  path is exercised directly by the isolated smoke instead.
