# Cloth Config and Mod Menu Execution Plan

## 1. Add Dependencies and Metadata

1. Add the Shedaniel repository to `settings.gradle.kts`.
2. Add the Terraformers repository to `settings.gradle.kts`.
3. Add Cloth Config `26.2.155` to `implementation`; this official-names Loom
   project does not create `modImplementation`.
4. Add Mod Menu `20.0.1` to non-transitive `compileOnly`; this project does not
   create `modCompileOnly`.
5. Add the required Cloth Config metadata to `fabric.mod.json`.
6. Add the optional Mod Menu metadata to `fabric.mod.json`.
7. Point the `modmenu` entrypoint to
   `com.fakeplayerproxy.mod.gui.FakePlayerProxyConfigScreen`.
8. Update the `FakePlayerProxyMod` comment. State that it has no ordinary
   initialization entrypoint.

## 2. Change ConsentStore

1. Replace `find()` with `read()`.
2. Replace `remember()` with `write(String, boolean)`.
3. Add `delete(String)`.
4. Preserve file order in the returned map.
5. Omit each unescaped key for which `isBlank()` is true.
6. Track duplicate keys in one local set.
7. Remove a key from the result on its second occurrence.
8. Ignore each later occurrence of a duplicate key.
9. Keep `read()` free of file writes for these handled conditions.
10. Reuse the current parser and file writer.
11. Preserve quoting, escaping, UTF-8, and temporary-file replacement.
12. Update the login Mixin to call `read()` and `write()`.
13. Keep the consent prompt path after a login-store read failure.
14. Log the complete exception at each caller boundary.

## 3. Add the Screen and Mod Menu Entrypoint

1. Create
   `mod/src/main/java/com/fakeplayerproxy/mod/gui/FakePlayerProxyConfigScreen.java`.
2. Make the class implement `ModMenuApi`.
3. Keep a public no-argument constructor.
4. Add `create(Screen parent)`.
5. Return `FakePlayerProxyConfigScreen::create` from the Mod Menu factory.
6. Read the initial decisions before building the screen.
7. Return a translated `AlertScreen` when the read fails.
8. Build one Cloth Config category for saved decisions.
9. Use `Pair<String, Boolean>` for each row.
10. Add the private list entry and cell types.
11. Put the address field and decision button on one row.
12. Use the inherited add and delete controls.
13. Set a new row to an empty address and Decline.
14. Reject blank addresses and exact duplicates.
15. Copy valid rows through the list save consumer.
16. Compare saved rows with the initial map in the saving runnable.
17. Delete removed addresses.
18. Write new addresses and changed decisions.
19. Log the first save failure and stop the remaining save operations.

## 4. Add Localized Text

1. Add the six configuration keys from `design.md` to `en_us.json`.
2. Add the same keys to `zh_cn.json`.
3. Reuse the existing Allow and Decline keys.
4. Keep the existing consent text unchanged.

## 5. Update Focused Tests

1. Test that an absent file returns an empty map.
2. Test ordered reads with both decisions and an escaped address.
3. Test add and replace behavior with preservation of other entries.
4. Test delete behavior with preservation of other entries.
5. Test that `write()` rejects malformed input and preserves the source file.
6. Test that `read()` omits a blank key and preserves valid entries and source.
7. Test that `read()` removes all duplicate-key occurrences and preserves
   valid entries and source.

Do not add source-shape, metadata-text, translation, annotation, or private UI
tests.

## 6. Update the Frontend Specification

1. Allow only the custom `modmenu` entrypoint in the frontend specification.
2. Record the required Cloth Config and optional Mod Menu dependencies.
3. Replace the old store operations with `read()`, `write()`, and `delete()`.
4. Record the blank-key and duplicate-key read contracts.
5. Record the screen package, validation, Save, and Cancel contracts.
6. Record the agent and user verification boundary.

## 7. Run Automated Checks

Run each command once:

```powershell
.\gradlew.bat :mod:build
git diff --check
```

Report the runtime acceptance criteria as pending user verification.
