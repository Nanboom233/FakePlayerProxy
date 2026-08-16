# Research: Cloth Config 26.2 and Mod Menu

- Query: Add one editable consent list and expose its screen through Mod Menu.
- Date: 2026-08-16

## Repository Evidence

- `mod/build.gradle.kts` has no configuration-screen dependencies.
- `fabric.mod.json` has no entrypoints or library dependencies.
- `ConsentStore` owns TOML parsing and atomic replacement.
- The frontend specification puts client screens under `com.fakeplayerproxy.mod.gui`.
- The Java specification requires translation keys for all visible text.
- The Java specification rejects source-shape and private-field tests.

## Dependency Contract

Use the standard Gradle configurations that this official-names Minecraft 26.2
Loom project exposes:

```kotlin
implementation("me.shedaniel.cloth:cloth-config-fabric:26.2.155")
compileOnly("com.terraformersmc:modmenu:20.0.1") {
    isTransitive = false
}
```

This project does not create `modImplementation` or `modCompileOnly`. The
published 26.2 libraries compile against the project's official names, so the
standard configurations build successfully. `implementation` supplies Cloth
Config to the development runtime without bundling it into this mod's jar;
Fabric metadata still requires the separately installed `cloth-config` mod.
Non-transitive `compileOnly` supplies only the Mod Menu API for compilation and
keeps Mod Menu out of both the runtime classpath and output jar.

Cloth Config `26.2.155` and Mod Menu `20.0.1` target Minecraft 26.2.

Keep Cloth Config's published transitive classpath. Do not add a direct Fabric
API dependency.

Declare `cloth-config` as `>=26.2.155 <26.3` in `depends`.

Declare `modmenu` as `>=20.0.1 <21` in `suggests`.

Do not add a development runtime dependency for Mod Menu.

## Mod Menu Contract

Use only the `modmenu` entrypoint. Point it to
`com.fakeplayerproxy.mod.gui.FakePlayerProxyConfigScreen`.

The screen class implements `ModMenuApi`.

The entrypoint class needs a public no-argument constructor.

The required override is
`ConfigScreenFactory<?> getModConfigScreenFactory()`.

The factory returns `FakePlayerProxyConfigScreen::create`.

Fabric Loader 0.19.3 registers a new-style entrypoint by its definition string.
It creates the entrypoint class lazily only after a consumer requests the
`modmenu` key. With Mod Menu absent, no owner requests that key, so the direct
entrypoint class is not linked and its optional `ModMenuApi` interface is not
resolved.

Sources:

- [ModMenuApi.java](https://github.com/TerraformersMC/ModMenu/blob/26.2/src/main/java/com/terraformersmc/modmenu/api/ModMenuApi.java#L43)
- [ConfigScreenFactory.java](https://github.com/TerraformersMC/ModMenu/blob/26.2/src/main/java/com/terraformersmc/modmenu/api/ConfigScreenFactory.java#L6-L7)

## Composite Row Contract

Cloth Config has no public builder for a list cell with two values.

Use a private `AbstractListListEntry` implementation. Use
`Pair<String, Boolean>` as its value.

Each private cell owns one `EditBox` and one decision button.

Implement only the required value, error, height, render, child, narration, and
focus hooks.

Use the inherited add and delete controls.

Sources:

- [ConfigEntryBuilder.java](https://github.com/shedaniel/cloth-config/blob/v26.2/common/src/main/java/me/shedaniel/clothconfig2/api/ConfigEntryBuilder.java#L56-L90)
- [AbstractListListEntry.java](https://github.com/shedaniel/cloth-config/blob/v26.2/common/src/main/java/me/shedaniel/clothconfig2/gui/entries/AbstractListListEntry.java#L43-L69)
- [BaseListEntry.java](https://github.com/shedaniel/cloth-config/blob/v26.2/common/src/main/java/me/shedaniel/clothconfig2/gui/entries/BaseListEntry.java#L160-L179)

## Validation and Save Order

Use `isBlank()` for blank input. Compare the original strings for duplicates.

Attach blank errors to cells. Attach duplicate errors to the list.

Cloth Config disables Save when an entry has an error.

Cloth Config saves entries before it runs the screen saving runnable.

The list consumer copies the current rows. The saving runnable compares those
rows with the initial ordered map.

Call `delete()` for removed addresses. Call `write()` for new or changed
addresses.

Stop the remaining operations after the first store failure. Log the complete
exception.

Cancel does not run the saving runnable.

Sources:

- [ClothConfigScreen.java](https://github.com/shedaniel/cloth-config/blob/v26.2/common/src/main/java/me/shedaniel/clothconfig2/gui/ClothConfigScreen.java#L139-L150)
- [AbstractConfigScreen.java](https://github.com/shedaniel/cloth-config/blob/v26.2/common/src/main/java/me/shedaniel/clothconfig2/gui/AbstractConfigScreen.java#L161-L170)
- [Cloth Config saving guide](https://shedaniel.gitbook.io/cloth-config/using-cloth-config/saving-the-config)

## Store Key Conditions

The current entry pattern accepts an empty quoted key. `isBlank()` must check
the unescaped key.

The current `LinkedHashMap.put()` behavior keeps the last duplicate value. The
new behavior removes the key after its second occurrence.

A local duplicate set prevents a later occurrence from adding the key again.

The read operation does not rewrite the source file for either condition.

A later write operation serializes the filtered map and can remove those keys.

## Read Failure

Read the store before building editable entries.

Minecraft 26.2 provides `AlertScreen(Runnable, Component, Component)`.

Return an `AlertScreen` after a read failure. Its action returns to the parent
screen.

Do not create an empty editable list after a read failure.

## Risks

Cloth Config marks the composite list API as internal. Pin Cloth Config to the
26.2 version line.

The three store operations cannot provide a transactional multi-row Save.

## Related Specifications

- `.trellis/spec/frontend/index.md`
- `.trellis/spec/frontend/fabric-client-mod.md`
- `.trellis/spec/language/java.md`
- `.trellis/spec/tooling/tool-selection.md`
