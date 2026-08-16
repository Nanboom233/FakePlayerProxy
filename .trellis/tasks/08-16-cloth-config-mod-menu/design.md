# Cloth Config and Mod Menu Design

## Scope

This task changes the Fabric client mod and its frontend specification. It does
not change the relay protocol or the Velocity project.

## Files

| File | Change |
| --- | --- |
| `settings.gradle.kts` | Add the Shedaniel and Terraformers repositories |
| `mod/build.gradle.kts` | Add Cloth Config and Mod Menu compile dependencies |
| `mod/src/main/resources/fabric.mod.json` | Add dependency metadata and the `modmenu` entrypoint |
| `mod/src/main/java/com/fakeplayerproxy/mod/FakePlayerProxyMod.java` | Correct the logger-owner entrypoint comment |
| `mod/src/main/java/com/fakeplayerproxy/mod/config/ConsentStore.java` | Add the three decision operations |
| `mod/src/main/java/com/fakeplayerproxy/mod/gui/FakePlayerProxyConfigScreen.java` | Own the screen, Mod Menu factory, and nested row types |
| `mod/src/main/java/com/fakeplayerproxy/mod/mixins/MixinClientHandshakePacketListenerImpl.java` | Use the new store operations |
| `mod/src/main/resources/assets/fakeplayerproxy-mod/lang/en_us.json` | Add English configuration text |
| `mod/src/main/resources/assets/fakeplayerproxy-mod/lang/zh_cn.json` | Add Simplified Chinese configuration text |
| `mod/src/test/java/com/fakeplayerproxy/mod/config/ConsentStoreTest.java` | Test changed store behavior |
| `.trellis/spec/frontend/fabric-client-mod.md` | Record the new client contracts |

## Dependencies

Add Cloth Config Fabric `26.2.155` as `implementation`. This official-names
Minecraft 26.2 Loom project does not create a `modImplementation`
configuration, and the published 26.2 artifact is already usable on the
project's standard compile and runtime classpaths.

Add Mod Menu `20.0.1` as non-transitive `compileOnly`. This project likewise
does not create `modCompileOnly`; standard `compileOnly` keeps the API out of
the runtime classpath and output jar.

Do not add Mod Menu to a runtime configuration. Do not add a Gradle run
property or run configuration.

Declare `cloth-config` as `>=26.2.155 <26.3` in `depends`. Declare `modmenu` as
`>=20.0.1 <21` in `suggests`.

Add only the `modmenu` entrypoint. Point it to
`com.fakeplayerproxy.mod.gui.FakePlayerProxyConfigScreen`.

Fabric Loader stores a new-style entrypoint definition without loading its
class and instantiates it only when a consumer requests that entrypoint key.
Therefore the direct screen entrypoint does not link `ModMenuApi` when Mod Menu
is absent.

Do not add a direct Fabric API dependency. Cloth Config keeps its published
transitive dependencies.

## ConsentStore

Keep `fromFabricConfig()` and the constructor used by tests. Replace the
decision operations with:

```java
Map<String, Boolean> read() throws IOException
void write(String serverAddress, boolean allow) throws IOException
void delete(String serverAddress) throws IOException
```

`read()` returns all entries in file order. An absent file returns an empty
map.

After unescaping a key, omit it when `isBlank()` is true.

Track duplicate exact keys in a local set. On the second occurrence, remove the
key from the result and add it to the set. Ignore later occurrences of that
key.

These two key conditions do not throw. `read()` does not rewrite the source
file. Other syntax and escape errors still throw `IOException`.

A later successful `write()` or `delete()` serializes only the filtered map.
That later operation can remove the skipped keys from the file.

`write()` adds or replaces one address. `delete()` removes one address. Both
operations preserve all other entries.

Reuse the current parser and file writer. Keep quoted keys, UTF-8, the temporary
file, the atomic move, and its fallback.

The login Mixin calls `read().get(serverAddress)`. The remember callback calls
`write(serverAddress, allow)`.

If the login path cannot read the store, it logs the exception and opens the
existing consent prompt. It does not replace the file.

Use `FakePlayerProxyMod.LOGGER` at each caller boundary. Pass the caught
exception to the logger.

## Screen and Mod Menu Entrypoint

Create `FakePlayerProxyConfigScreen` under the `gui` package. The class
implements `ModMenuApi` and contains no screen state between openings.

Keep a public no-argument constructor for entrypoint creation.

`getModConfigScreenFactory()` returns `FakePlayerProxyConfigScreen::create`.
The static `create(Screen parent)` method builds a new Cloth Config screen.

The same class contains the private list entry and cell types. Use
`Pair<String, Boolean>` for each row.

Each row contains one `EditBox` and one decision button. The button shows Allow
or Decline. New rows use an empty address and `false`.

Use Cloth Config list controls for add and delete actions. Use only the required
`AbstractListListEntry` hooks.

## Validation and Save

Reject an address when `isBlank()` is true. Reject duplicate exact strings.
Do not trim, normalize, resolve, or change the stored address.

The list error supplier checks duplicates. The cell error supplier checks its
address. Cloth Config disables Save while an error exists.

The list save consumer copies the current rows to one local collection. The
saving runnable compares that collection with the initial ordered map.

Call `delete()` for removed addresses. Call `write()` for new addresses and
changed decisions.

Cancel and discard do not call the saving runnable. They do not change the
store.

If the initial read fails, log the exception. Return an `AlertScreen` with a
translated message and an action that returns to the parent.

If a save operation fails, log the exception and return from that operation.
Do not throw through Cloth Config.

The three-operation store API cannot make a multi-row Save transactional. A
later failure does not undo an earlier successful operation.

## Localization

Add these keys to both language files:

- `fakeplayerproxy.config.title`
- `fakeplayerproxy.config.entries`
- `fakeplayerproxy.config.server_address`
- `fakeplayerproxy.config.address_blank`
- `fakeplayerproxy.config.address_duplicate`
- `fakeplayerproxy.config.store_read_failed`

Reuse `fakeplayerproxy.consent.allow` and
`fakeplayerproxy.consent.decline`. Do not change the consent prompt text.

## Tests

Test changed store behavior through the production operations. Include the
absent-file, blank-key, and duplicate-key results.

Do not add tests for translations, annotations, class presence, metadata text,
or private UI types. The Java specification assigns those checks to compilation
and review.

## Verification Boundary

The agent runs `:mod:build` and `git diff --check`.

The user owns runtime verification after handoff. The PRD lists the observable
runtime acceptance criteria.

## Compatibility and Rollback

Existing TOML files need no migration. Existing boolean meanings and login
behavior do not change.

Cloth Config marks the composite list API as internal. Pin the dependency to
the 26.2 version line and rely on compilation for API drift.

Rollback removes the screen, entrypoint, dependencies, and store API changes.
The existing TOML file remains valid.
