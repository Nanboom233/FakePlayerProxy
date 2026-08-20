# Upgrade Velocity Base And Adopt Java 25

## Goal

Update the pinned Velocity base to the approved Java 25 commit. Then modernize
project-owned Java code with final Java 25 features.

## Requirements

1. Pin Velocity to `c1cd71a4bc4bb011bd93e3570765a5870a29ebf6`.
2. Preserve the approved behavior of all existing Velocity patches.
3. Regenerate only patch hunks that the new Velocity base changes.
4. Use Java 25 for Plugin, Mod, Plugin tools, patched Velocity, and tests.
5. Use only final Java features. Do not enable preview compilation or runtime flags.
6. Apply the approved Future, pattern switch, record pattern, sequenced collection,
   virtual thread, unnamed variable, Markdown comment, flexible constructor, and
   capacity-aware collection changes.
7. Preserve EventLoop ownership, packet order, null behavior, and error handling.
8. Give every virtual-thread executor an explicit owner and cleanup path.
9. Keep String Templates out of the codebase. JDK 23 removed the feature, so the
   project will wait for a redesigned replacement.
10. Compile the Java 25 baseline before applying source modernization.

## Acceptance Criteria

- [ ] `velocity-base.properties` contains the approved commit.
- [ ] Velocity uses Java 25, Shadow 9.5.1, and its approved Gradle version.
- [ ] Plugin, Mod, and Plugin tools use an explicit Java 25 toolchain.
- [ ] No build or runtime task uses `--enable-preview`.
- [ ] `0001`, `0002`, and `0003` apply in order to the new base.
- [ ] `0003` preserves Shadow 9.5.1 while adding authlib 9.0.75.
- [ ] All approved final-feature changes compile without behavior changes.
- [ ] String Template syntax is absent.
- [ ] Focused Velocity, Plugin, Mod, and tools checks pass.
- [ ] The patched Velocity host starts on Java 25 without preview flags.

## Out Of Scope

- Java 25 preview features.
- Primitive patterns, Stable Values, and Structured Concurrency.
- A floating Velocity branch or unpinned remote HEAD.
- New product behavior or protocol changes.
