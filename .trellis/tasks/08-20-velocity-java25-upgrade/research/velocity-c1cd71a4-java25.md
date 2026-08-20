# Research: Velocity c1cd71a4 And Java 25

- Date: 2026-08-20
- Current Velocity base: `843a47e2a38325309cd66133149fc9a984f76bb8`
- Approved Velocity base: `c1cd71a4bc4bb011bd93e3570765a5870a29ebf6`

## Commit Result

The approved commit is the direct child of the current base. Its title is
`Toolchain and gradle bump (Java 25+)`.

It changes three files:

- `build.gradle.kts` changes the Java toolchain from 21 to 25.
- `gradle/libs.versions.toml` changes Shadow from 9.3.1 to 9.5.1.
- `gradle/wrapper/gradle-wrapper.properties` changes Gradle from 9.3.0 to 9.6.1.

The commit changes no Velocity Java source, public API, dependency API, or test
source.

## Build Result

Velocity configures Java 25 in the root `subprojects` block. It does not set
`--release` or `--enable-preview`.

Main and test source use the Java 25 toolchain. The produced class-file version
is 69. Production therefore requires Java 25 or a newer runtime.

FakePlayerProxy must align all consumers of the patched Velocity host:

- Plugin toolchain and release become 25.
- Mod adds an explicit Java 25 toolchain.
- Plugin tools use Kotlin JVM toolchain 25.
- Test and run tasks use Java 25 without preview flags.

## Patch Result

`0001` does not overlap the three upstream changes.

`0002` does not overlap the three upstream changes.

`0003` modifies the version catalog against Shadow 9.3.1. Regenerate that hunk
against Shadow 9.5.1. Its authlib and Java source changes remain valid.

## Final Java 25 Candidates

### Future State APIs

Use `Future.state()`, `resultNow()`, and `exceptionNow()` in
`AutomationService`. Do not treat `CompletionException` as a record. Use a type
pattern and `getCause()`.

### Pattern Switch And Record Patterns

Candidates include `AuthManager`, `PermissionProvider`, `AutomationManager`,
`Entity`, `Player`, result dispatch paths, and project-owned patch additions.

A switch over an external nullable value needs an explicit `case null` or an
equivalent guard. Preserve early returns and packet order.

### Sequenced Collections

Use `getFirst()` in `DecoderTest`. Use `reversed()` for the reverse movement
iteration in `World`. Do not mutate the source list during view iteration.

### Virtual Threads

Use a virtual-thread factory for serialized configuration writes. Keep one
single-thread executor and close it through the existing provider lifecycle.

Run blocking authlib session join on an explicitly owned virtual-thread
executor. Return to the backend EventLoop before packet writes or state changes.

### Unnamed Variables And Patterns

JDK 22 finalized unnamed variables and patterns. Java 25 needs no preview flag.
Candidates exist in the Mod, Plugin, Plugin tests, patch tests, and all three
Velocity patches.

### Markdown Documentation Comments

JDK 23 finalized Markdown documentation comments. Project-owned Java comments
can use `///`. In patch files, convert only comments added by this project.

### Flexible Constructor Bodies

JDK 25 finalized flexible constructor bodies. Consent and configuration screens
can prepare superclass arguments before `super(...)`. Preserve argument values
and evaluation order.

### Capacity-Aware Collections

Use `HashMap.newHashMap(size)` and `HashSet.newHashSet(size)` where the input
already supplies an expected element count. Candidates exist in `Decoder`,
`FakePlayerProxyConfigScreen`, `World`, and `AutomationManager`.

Keep copy constructors when the code copies an existing collection.

## Preview Findings

Primitive patterns could simplify boxed metadata dispatch. Stable Values could
change `Decoder.instance` initialization. Both are Java 25 preview features and
are outside this task.

Structured Concurrency has no current matching fan-out and join boundary.

## Rejected Feature

String Templates were removed from JDK 23 after their JDK 21 and JDK 22 preview
rounds. JEP 465 was withdrawn. FakePlayerProxy will keep ordinary concatenation,
text blocks, and builders. The project will wait for a redesigned replacement.

## Migration Order

1. Update the planning and spec baseline to Java 25.
2. Move the Velocity pin to `c1cd71a4`.
3. Regenerate the conflicting `0003` catalog hunk.
4. Align Plugin, Mod, and tools with Java 25.
5. Compile the unchanged baseline without preview.
6. Apply final feature changes in narrow groups.
7. Apply virtual-thread changes as a separate group.
8. Run focused checks and one Java 25 runtime smoke.

## References

- Local Velocity commit `c1cd71a4bc4bb011bd93e3570765a5870a29ebf6`
- OpenJDK JDK 25 release page: https://openjdk.org/projects/jdk/25/
- JEP 456: https://openjdk.org/jeps/456
- JEP 465: https://openjdk.org/jeps/465
- JEP 467: https://openjdk.org/jeps/467
- JEP 513: https://openjdk.org/jeps/513
