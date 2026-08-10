# Implementation Plan

## Steps

1. Create Gradle project files.
   - `settings.gradle.kts`
   - `build.gradle.kts`
   - `gradle.properties`
   - Gradle wrapper if available or use installed Gradle if wrapper generation
     is practical.

2. Add plugin metadata/resources.
   - `src/main/resources/velocity-plugin.json`
   - `src/main/resources/fakeplayerproxy-default.properties`

3. Implement config and result utilities.
   - `config/DemoConfig.java`
   - `config/DemoConfigLoader.java`
   - `util/DemoResult.java`
   - `util/DemoError.java`
   - `demo/DemoProtocolTarget.java` for Minecraft Java `26.1` / protocol `775`

4. Implement demo state service.
   - `demo/DemoConnectionState.java`
   - `demo/DemoConnectionSnapshot.java`
   - `demo/DemoConnectionService.java`

5. Implement protocol wrapper.
   - `protocol/DemoUpstreamClient.java`
   - `protocol/McProtocolLibDemoClient.java`
   - Packet-level actions for main-hand swing/use, selected-item drop,
     selected-stack drop, and swapHands.

6. Implement Velocity plugin and command.
   - `FakePlayerProxyPlugin.java`
   - `command/FppCommand.java`
   - `command/PlayerCommand.java`
   - `command/PlayerCommandParser.java`
   - self-only `/player` and `/fpp player` action dispatch.

7. Implement offline reconnect and action scheduling.
   - `demo.reconnect.*` config fields.
   - unexpected disconnect auto reconnect for `offline-controlled`.
   - `once`, `continuous`, and `interval <ticks>` modes for simple packet
     actions.

8. Add tests.
   - config loader;
   - service state transitions;
   - command parsing helpers if separated.
   - reconnect behavior;
   - simple Carpet action parser/service behavior.

9. Add operator demo docs.
   - `docs/demo/technical-path-demo.md`
   - Explicitly state that the upstream server must match Minecraft Java `26.1`
     / protocol `775`.

10. Validate.
   - `./gradlew test`
   - `./gradlew build`
   - `python ./.trellis/scripts/task.py validate .trellis/tasks/06-12-technical-path-demo`
   - `git status --short --untracked-files=all`

## Dependency Verification Before Editing

Verify current Maven coordinates before hardcoding versions:

- Velocity API latest stable or documented current version.
- MCProtocolLib latest available artifact coordinate.
- Java toolchain supported by selected Velocity/MCProtocolLib versions.

## Risk Controls

- Keep online auth out of this task.
- Keep MCProtocolLib imports inside `protocol`.
- No long-lived background thread leaks; disconnect and shutdown service on proxy
  shutdown.
- No token/secret classes.
- One demo connection at a time.
- Do not claim full Carpet parity: target-aware attack/use, full inventory
  operations, mount, and vehicle-state semantics remain deferred until trackers
  exist.
