# Research: Permission command refresh and console i18n

- Query: Find the smallest safe change to the pinned Velocity source that resends a player's Brigadier command tree after an FPP operator permission change while preserving backend commands and re-evaluating `.requires()`. Explain why plugin translatable keys are not rendered for the console and define the smallest correct plugin-side i18n setup.
- Scope: mixed
- Date: 2026-08-17

## Findings

### Files found

- `plugin/patch/velocity-base.properties` - pins Velocity commit `843a47e2a38325309cd66133149fc9a984f76bb8`.
- `plugin/build/server/source/proxy/src/main/java/com/velocitypowered/proxy/connection/backend/BackendPlaySessionHandler.java` - receives the backend command packet, mutates its root by injecting proxy commands, fires `PlayerAvailableCommandsEvent`, and sends it to the frontend.
- `plugin/build/server/source/proxy/src/main/java/com/velocitypowered/proxy/command/CommandGraphInjector.java` - copies proxy nodes into a destination tree and filters every copied node with its Brigadier requirement.
- `plugin/build/server/source/proxy/src/main/java/com/velocitypowered/proxy/protocol/packet/AvailableCommandsPacket.java` - owns command-tree decode/encode and currently has only a decoded-root getter, with no constructor/setter for a newly composed root.
- `plugin/build/server/source/api/src/main/java/com/velocitypowered/api/proxy/Player.java` - public plugin-facing player API; it currently has no command-tree refresh method.
- `plugin/build/server/source/proxy/src/main/java/com/velocitypowered/proxy/connection/client/ConnectedPlayer.java` - concrete player implementation and owner of the frontend connection/EventLoop.
- `plugin/src/main/java/com/fakeplayerproxy/config/PermissionProvider.java` - publishes the new immutable operator snapshot only after atomic persistence.
- `plugin/src/main/java/com/fakeplayerproxy/command/FppCommand.java` - completes `op`/`deop` asynchronously and currently sends translatable components without refreshing the affected player's client command tree.
- `plugin/src/main/java/com/fakeplayerproxy/command/PlayerCommand.java` - correctly protects `as` with `.requires(source -> source.hasPermission(...))`.
- `plugin/build/server/source/proxy/src/main/java/com/velocitypowered/proxy/console/VelocityConsole.java` - passes components directly to Adventure's component logger.
- `plugin/build/server/source/proxy/src/main/java/com/velocitypowered/proxy/util/TranslatableMapper.java` - console flattening resolves only keys known to Adventure's `GlobalTranslator`, otherwise prints the component fallback or raw key.
- `plugin/build/server/source/proxy/src/main/java/com/velocitypowered/proxy/connection/client/ConnectedPlayer.java` - player messages are rendered through `GlobalTranslator` for the player's locale before packet encoding.
- `mod/src/main/resources/assets/fakeplayerproxy-mod/lang/en_us.json` and `zh_cn.json` - define FPP command keys only on the modded Minecraft client; these are not Adventure translation sources in the proxy JVM.

### Current command-tree behavior

The pinned backend handler takes the backend-owned root and injects proxy nodes into that same object (`BackendPlaySessionHandler.java:360-376`). This is sufficient for the initial backend packet, but there is no retained pristine backend tree and no public refresh entry point.

`CommandGraphInjector.inject(...)` is the authoritative filtering path. It first checks root aliases with `node.canUse(source)` (`CommandGraphInjector.java:76-85`), then recursively checks child nodes with `node.canUse(source)` (`CommandGraphInjector.java:108-138`). Its `addAlias` replaces a same-named destination root (`CommandGraphInjector.java:143-145`). Consequently, a refresh must run this injector again against the current player. Copying the previously sent tree, manually adding `as`/`op`/`deop`, or making the client suggest those literals would retain stale protected nodes and bypass the `.requires()` contract.

### Minimal safe Velocity API and fields

Add one narrow public method to the pinned API:

```java
// api ... proxy/Player.java
void refreshCommands();
```

Implement it in `ConnectedPlayer` as an EventLoop handoff, not as packet construction. It should schedule against `connection.eventLoop()` and ask the active `BackendPlaySessionHandler` to refresh. If there is no active backend play handler, the player is closed, or no backend command tree has arrived, it is a no-op. This keeps plugin code free of proxy internals.

In `BackendPlaySessionHandler`, retain:

```java
private @Nullable RootCommandNode<CommandSource> backendCommandRoot;
private long commandTreeRevision;
```

The retained root must be the backend-decoded tree before proxy injection and before `PlayerAvailableCommandsEvent`. Never expose or mutate that retained object. Every initial send and refresh builds a fresh root from it. A small extension to `CommandGraphInjector` should support copying from an explicit origin root into an explicit destination root using the existing identity-map copy logic. The refresh flow is:

```text
backend AvailableCommandsPacket
  -> move handling to frontend connection EventLoop
  -> retain untouched backend root
  -> fresh RootCommandNode
  -> copy backend root into fresh root
  -> proxy injector.inject(fresh root, player) using current permissions
  -> PlayerAvailableCommandsEvent(player, fresh root)
  -> write a new AvailableCommandsPacket(fresh root)
```

`AvailableCommandsPacket` therefore needs only a constructor (or package-visible factory) that accepts a non-null `RootCommandNode<CommandSource>`. Do not cache the outgoing packet/tree: the proxy injector replaces colliding roots and event listeners may mutate the event root, so that object is not a pristine backend source for later refreshes.

All state changes and revisions should occur on the frontend connection EventLoop. Increment `commandTreeRevision` for each build; in the asynchronous event completion callback, write only when its captured revision is still current and the connection is open. This prevents a slower old `PlayerAvailableCommandsEvent` completion from overwriting a newer permission refresh. The existing callback already returns to `playerConnection.eventLoop()` (`BackendPlaySessionHandler.java:374-380`); the revision check is the minimal additional ordering guard.

This API should mean “rebuild from the latest backend tree and resend”, not “send proxy commands”. That preserves unrelated backend literals and redirects, preserves the `PlayerAvailableCommandsEvent` extension point, and applies current requirements to every proxy node.

### Plugin call site after permission changes

`PermissionProvider.save(...)` already moves the temporary file atomically and only then publishes `operators = Map.copyOf(candidate)` (`PermissionProvider.java:163-184`). Refresh only after a successful `grant` or `revoke` future. Calling before success can expose a command branch whose persisted authorization did not commit.

- `op`: the command already holds the authenticated target `Player`; on successful completion call `player.refreshCommands()` and then render the result.
- `deop`: the successful result contains the saved name. Resolve a currently connected player with that name and call `refreshCommands()` if present; offline revocation needs no packet.

The command executor continues to use `.requires(...)` exactly as it does now (`FppCommand.java:35-56`, `PlayerCommand.java:69-71`). The refresh merely causes Velocity's injector to evaluate those predicates again. Server-side execution already evaluates Brigadier requirements dynamically; refresh is required for the client's visible tree and suggestions.

### Why console translatable keys do not render

The FPP command components use keys such as `fakeplayerproxy.command.operator_added` (`FppCommand.java:85-99`). Those keys currently exist only in Minecraft client resource JSON under the Fabric mod. The proxy JVM never registers those JSON files with Adventure.

Player output can appear translated because Minecraft can receive an unresolved translatable component and look it up in the mod resource pack. The console has no Minecraft language manager. `VelocityConsole.sendMessage` passes the component to the component logger (`VelocityConsole.java:71-74`), whose translatable flattener checks `GlobalTranslator`. If the key is unknown, it emits the component fallback or the raw key (`TranslatableMapper.java:34-51`). Therefore the console printing the key is expected, not a logger bug.

### Minimal correct plugin-side i18n

Create one plugin-owned Adventure `TranslationRegistry`, keep it as a plugin field, and register it with `GlobalTranslator` during `ProxyInitializeEvent`. Load UTF-8 Java resource bundles from plugin resources, for example:

```text
plugin/src/main/resources/com/fakeplayerproxy/i18n/messages.properties
plugin/src/main/resources/com/fakeplayerproxy/i18n/messages_zh_CN.properties
```

Register both bundles with `TranslationRegistry.registerAll(locale, bundle, true)`, set `Locale.US` as the registry default, then call `GlobalTranslator.translator().addSource(registry)`. Remove the same source during `ProxyShutdownEvent` to avoid retaining a plugin source across lifecycle/reload boundaries.

The bundle message syntax is Java `MessageFormat`, so component arguments use `{0}`, not Minecraft JSON's `%s`. Example:

```properties
fakeplayerproxy.command.operator_added={0} is now a FakePlayerProxy operator.
```

Keep `Component.translatable(key, Component.text(name))` at call sites. With the registry installed, `ConnectedPlayer.translateMessage` renders through `GlobalTranslator` using the player's effective locale (`ConnectedPlayer.java:404-429`), and the console flattener resolves the same key using the process locale. This gives one plugin-side source for all command receivers. The existing mod JSON may remain for client-only UI and disconnect messages, but command correctness must not depend on it.

### Tests to add

- Velocity proxy test: backend-only literals survive both grant and revoke refreshes.
- Velocity proxy test: protected proxy children are absent before grant, present after grant, and absent after revoke; assertions must observe the serialized/event tree produced by `.requires()`, not a hand-built tree.
- Velocity proxy test: an older asynchronous `PlayerAvailableCommandsEvent` result cannot overwrite a newer revision.
- Plugin command test: successful online `op` refreshes exactly that player; failed persistence does not refresh.
- Plugin command test: successful `deop` refreshes the matching connected player and tolerates an offline target.
- Plugin i18n test: registered English and Chinese bundles render an argument-bearing command message through `GlobalTranslator`; unknown keys retain normal Adventure fallback behavior.

## External references

- Adventure localization documentation: https://docs.advntr.dev/localization.html
- Adventure `TranslationRegistry` API: https://jd.advntr.dev/api/latest/net/kyori/adventure/translation/TranslationRegistry.html
- Adventure `GlobalTranslator` API: https://jd.advntr.dev/api/latest/net/kyori/adventure/translation/GlobalTranslator.html
- Java UTF-8 property resource bundles (JEP 226): https://openjdk.org/jeps/226

## Related specs

- `.trellis/spec/backend/velocity-plugin.md` requires local proxy roots, child-level permission requirements, live operator changes, and translated command messages.
- `.trellis/spec/language/java.md` defines the project Java and i18n conventions.
- `.trellis/tasks/08-16-player-as-action-command/prd.md` requires immediate permission changes for connected subjects and protected suggestions.
- `.trellis/tasks/08-16-player-as-action-command/design.md` requires `.requires(...)` to remain the authorization and visibility boundary.
- `.trellis/tasks/08-16-player-as-action-command/implement.md` identifies permission-provider ordering and translatable command resources as implementation risks.

## Caveats / Not Found

- The pinned public Velocity API has no existing refresh/send-command-tree method, so a small API plus proxy implementation patch is necessary.
- The exact method name is project-owned because this is a pinned fork; `refreshCommands()` is preferred because it describes rebuilding from current backend state and permissions rather than sending an arbitrary tree.
- A player that has already entered Shadow may no longer have a writable frontend connection. Refresh must be a no-op in that state; server-side `.requires()` still protects execution.
- Locale fallback should be specified by the plugin registry. Velocity's console uses the JVM/default closest locale, while connected players use their effective locale.
