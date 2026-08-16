# Add Cloth Config and Mod Menu support

## Goal

Let players manage saved per-server consent decisions through a client
configuration screen. Expose the same screen class through Mod Menu.

## Confirmed Facts

- The client targets Minecraft 26.2, Java 25, and Fabric Loader 0.19.3.
- `ConsentStore` uses `fakeplayerproxy/consent_store.toml`.
- The store keeps one boolean for each exact server address.
- A missing address opens the existing consent prompt.
- Saved decisions affect the next matching connection without a client restart.
- Existing TOML files must remain compatible.
- Cloth Config `26.2.155` and Mod Menu `20.0.1` support Minecraft 26.2.

## Requirements

- Add Cloth Config `26.2.155` as a required client dependency.
- Add Mod Menu `20.0.1` as an optional compile dependency.
- Point the `modmenu` entrypoint to the configuration screen class.
- Put the configuration screen under `com.fakeplayerproxy.mod.gui`.
- Show every saved address and its Allow or Decline decision.
- Let the player add, edit, and delete decisions.
- Use one row for the address field and decision control.
- Set new rows to Decline.
- Save changes only after the player selects Save.
- Reject blank addresses and exact duplicate addresses.
- Omit blank TOML keys from the map returned by `read()`.
- Remove a key from the returned map when the file defines it more than once.
- Keep the source file unchanged during `read()`.
- Keep `read()`, `write(String, boolean)`, and `delete(String)` as the decision
  operations on `ConsentStore`.
- Keep the existing UTF-8 TOML format and file replacement behavior.
- Log each store failure with its complete cause.
- Show all user-facing text through translation keys.
- Keep the existing protocol, login flow, consent prompt, and remember option.

## Out of Scope

- Agent-owned Minecraft client launch support or runtime verification.
- A development runtime dependency or run configuration for Mod Menu.
- A separate Mod Menu integration class.
- A separate row model, validator, wrapper screen, or retry screen.
- Address normalization, wildcard matching, search, import, or bulk actions.
- Changes to the Velocity plugin, protocol, cryptography, or patches.

## Acceptance Criteria

### Agent Verification

- [ ] `:mod:build` passes with the required Cloth Config dependency.
- [ ] `fabric.mod.json` declares Cloth Config as required and Mod Menu as
  optional.
- [ ] The `modmenu` entrypoint is
  `com.fakeplayerproxy.mod.gui.FakePlayerProxyConfigScreen`.
- [ ] Focused `ConsentStore` tests cover each changed store behavior once.
- [ ] Existing TOML files load without migration or data loss.
- [ ] `read()` omits blank keys and keeps valid entries in file order.
- [ ] `read()` removes every occurrence of a duplicate key from its result.
- [ ] These handled key conditions do not rewrite the source file.

### User Verification

- [ ] Mod Menu opens the configuration screen when Mod Menu is installed.
- [ ] The client still loads when Mod Menu is absent.
- [ ] The screen shows all saved addresses and decisions.
- [ ] Save applies valid additions, edits, and deletions.
- [ ] Cancel leaves the store unchanged.
- [ ] Blank or duplicate addresses prevent Save and show translated errors.
- [ ] A store read failure shows a translated error and preserves the file.
- [ ] The next matching login uses the saved decision.
