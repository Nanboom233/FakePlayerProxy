# Java 25 Upgrade Plan

## 1. Move The Velocity Base

1. Pin `velocity-base.properties` to `c1cd71a4`.
2. Apply the current patch set to a disposable source tree without commits.
3. Regenerate only the `0003` version-catalog hunk.
4. Preserve Shadow 9.5.1 and authlib 9.0.75.

## 2. Align Build Toolchains

1. Set Plugin toolchain and release to Java 25.
2. Add the Java 25 Mod toolchain.
3. Set the Plugin tools Kotlin JVM toolchain to 25.
4. Remove every preview compiler, test, and runtime flag.
5. Compile the unchanged Java 25 baseline.

## 3. Apply Final Language Features

1. Apply unnamed variables and patterns.
2. Apply pattern switches and record patterns.
3. Apply Future state APIs.
4. Apply sequenced collection operations.
5. Apply capacity-aware collection factories.
6. Apply flexible constructor bodies.
7. Convert project-owned Javadoc to Markdown comments.

Preserve null behavior, packet order, and existing failure propagation.

## 4. Apply Concurrency Changes

1. Move serialized configuration writes to a virtual-thread executor.
2. Give that executor one explicit lifecycle owner.
3. Move blocking authlib session join to an owned virtual-thread executor.
4. Return all connection changes to the backend EventLoop.

## 5. Verify

1. Run Velocity patch application and host assembly.
2. Run the focused patched API and proxy tests.
3. Compile Plugin production and test source.
4. Run only affected Plugin test classes.
5. Compile and test the Mod.
6. Compile Plugin tools without running the data generator.
7. Start the patched Velocity host on Java 25 without preview flags.
8. Run scoped `git diff --check` and patch ownership checks.

## Rejected

Do not implement String Templates. JDK 23 removed the feature. Wait for a
redesigned replacement.
