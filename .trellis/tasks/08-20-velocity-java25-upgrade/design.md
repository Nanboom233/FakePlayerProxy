# Java 25 Upgrade Design

## Baseline

The approved Velocity commit is the direct child of the current base. It changes
only the Java toolchain, Shadow plugin, and Gradle wrapper.

The project keeps the outer Gradle wrapper at its current version. The patched
Velocity source keeps the versions from its approved commit.

## Build Boundary

| Owner | Change |
| --- | --- |
| `plugin/patch/velocity-base.properties` | Pin `c1cd71a4bc4bb011bd93e3570765a5870a29ebf6`. |
| Plugin build | Use Java 25 toolchain and `--release 25`. |
| Mod build | Add Java 25 toolchain and keep `--release 25`. |
| Plugin tools build | Use Kotlin JVM toolchain 25. |
| Patched Velocity | Inherit Java 25 from the approved upstream commit. |
| Tests and run tasks | Use Java 25 without preview flags. |

Java 25 class files require Java 25 or a newer runtime.

## Patch Boundary

`0001-login-relay.patch` has no direct conflict with the new base.

`0002-automation-extension.patch` has no direct conflict with the new base.

`0003-login-session.patch` changes `gradle/libs.versions.toml`. Regenerate that
hunk against Shadow 9.5.1. Keep the authlib 9.0.75 addition.

Do not move behavior between patch files. One product feature keeps one patch
owner.

## Final Java Features

Use final Java 25 features where the current code has a direct replacement:

- Future state APIs for completed reconnect futures.
- Pattern switch for closed type alternatives.
- Record patterns for project-owned records.
- Sequenced collection operations for first and reverse access.
- Virtual threads for blocking work with explicit ownership.
- Unnamed variables and patterns for unused bindings.
- Markdown documentation comments for project-owned Java documentation.
- Flexible constructor bodies where arguments need local preparation.
- Capacity-aware map and set factories when an exact expected size exists.

Pattern switches over external nullable values must keep the current null path.
Virtual-thread work must return to the owning EventLoop before connection changes.

## Rejected Features

Do not use String Templates. JDK 23 removed the feature after its preview rounds.
The project will wait for a redesigned replacement.

Do not use Java 25 preview features in this task.

## Validation Boundary

Compile the unmodernized Java 25 baseline first. Then apply source changes in
small groups. Run focused checks after each build-boundary or concurrency group.
