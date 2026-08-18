# Implement complete Carpet player command system

## Goal

Implement every Carpet `/player` action that the proxy can perform through the
Minecraft 26.2 protocol. Omit server-only command branches instead of
registering executors that only return errors.

## Background

- This is a child of `08-16-complete-carpet-player-commands`.
- The command inventory is pinned to Carpet commit
  `21993f2585d6714ced9fc86b1f6d3721cf4c60ab`. Carpet source is authoritative.
- The plugin currently exposes one shared action subtree under the self form
  and `/player as <player>`. The complete implementation must reuse action
  nodes without duplicating command grammar.
- Existing packet/action prototypes are not evidence of Carpet parity. Each
  command must be evaluated against tracked state and vanilla protocol limits.

## Requirements

- Register `stop`, `use`, `jump`, `attack`, `drop`, `dropStack`, `swapHands`,
  `hotbar`, `kill`, `shadow`, `mount`, and `dismount`.
- Register `sneak`, `unsneak`, `sprint`, `unsprint`, `look`, `turn`, and
  `move` with their source-verified subforms.
- Cover each source-verified Carpet player action that has a protocol
  implementation.
- Preserve the existing `/player` self form and `/player as <player>` operator
  form. Attach shadow-only action nodes only to the target form.
- Keep `/player as` protected by the existing `fakeplayerproxy.op` permission.
- Match Carpet scheduling semantics for `once`, `continuous`, and
  `interval <ticks>`, including replacement by action type and use-before-attack
  ordering.
- Use a boolean Vanilla local prediction for the same-tick use and attack
  decision.
- Keep Carpet's three-tick item-use cooldown. Use Vanilla behavior only to
  calculate the local action result.
- Advance tracked world state before scheduled actions for every in-game
  connection. Keep local player movement and movement packets shadow-only.
- Apply the real client's carried-item selection to the local inventory model.
- Make continuous item use keep one active use instead of sending a use packet
  on each action tick.
- Reconcile active use from backend living metadata and real-client slot
  changes.
- Restart continuous use after the backend stops the old item or a main-hand
  slot change selects a new valid item.
- While continuous use owns the action, cancel only the attached Vanilla
  frontend's `RELEASE_USE_ITEM` packet. Keep backend living metadata visible
  and authoritative.
- Make entity attack use current interpolated entity positions. One-shot and
  interval attacks send one attack. Continuous attack sends once for a new
  entity target and does not repeat for the same target.
- Convert stored move and jump input into Vanilla local movement while shadow
  owns player movement.
- Apply local input inside the matching Vanilla air, water, lava, and flight
  travel branch. Do not add land movement speed before environment selection.
- Allow `jump`, `move`, `sprint`, and `unsprint` only when the resolved target
  is in shadow state.
- Suggest only current shadow targets for `/player as <player>`.
- Treat sprint as intent. Send sprint start and stop only when effective
  forward movement changes the actual sprint state.
- Hold dismount input across a server tick before restoring current input.
- Allow `kill` only when the selected target is in shadow state.
- Return failure from local prediction when a Vanilla interaction behavior is
  not modeled. Do not add an unknown result state.
- Return failure for behavior that needs untracked block-entity, data-pack,
  backend-plugin, or other server-only state.
- Register `/player mount` and `/player mount <x> <y> <z>`.
- Support absolute, `~` relative, and `^` local position syntax.
- Resolve relative and local positions from the target fake player.
- Use the target fake player's feet position as the `^` anchor.
- Make bare `mount` select the nearest tracked Carpet rideable.
- Make coordinate `mount` select the rideable nearest the supplied position.
- Reject coordinate `mount` when the selected rideable is outside the target
  player's current entity interaction range.
- Do not register `mount anything`. Do not add an error executor for it.
- Defer every `spawn` branch. A later task will define different spawn
  semantics. Do not add a placeholder or error executor in this task.
- State protocol parity limits without adding command branches for behavior
  that the proxy cannot perform.
- Reuse or complete existing action infrastructure where it is correct, and
  remove superseded prototypes rather than retaining duplicate paths.
- Make every Java file touched by this task comply with the project language
  spec. Remove redundant suppressions, indexed multi-value results, one-use
  thin wrappers, and explicit trivial field accessors identified by review.
- Remove programmer-assertion validation from all production Java code. This
  includes `requireNonNull`, Java `assert`, and `Preconditions.check*`. Use
  project-owned `@NotNull` contracts for internally guaranteed values and
  explicit `if` guards for runtime boundaries. Production code can throw, but
  its owner must catch and handle each exception before it escapes the
  operational boundary. Test code is outside this restriction.
- Do not start implementation until the command boundary, design, and
  implementation plan have been reviewed and approved.

## Acceptance Criteria

- [ ] Every registered command has an observable protocol implementation.
- [ ] Every ordinary action and source-verified subform exists under both
      supported command paths.
- [ ] Shadow-only actions exist only under `/player as <player>`.
- [ ] `mount anything` and every `spawn` branch are absent from the tree.
- [ ] Both `mount` forms select the specified rideable and enforce interaction
      range before packet output.
- [ ] Coordinate parsing matches Minecraft 26.2 position rules.
- [ ] Scheduled use keeps Carpet's three-tick cooldown while local prediction
      follows the modeled Vanilla result.
- [ ] A one-shot attack can hit a moving tracked entity before shadow starts.
- [ ] Continuous use starts one held use and does not send repeated use packets.
- [ ] Switching from active main-hand use to food lets continuous use start the
      food with one new request.
- [ ] Backend active-use metadata suppresses repeated use until the backend
      clears that state.
- [ ] An attached Vanilla frontend with its use key released cannot cancel a
      scheduled continuous use. Releases outside continuous use still reach
      the backend.
- [ ] Shadow move and jump input changes the local position and emitted movement.
- [ ] The same forward input moves less in water than on land.
- [ ] Water jump input changes vertical movement through the fluid path.
- [ ] `jump`, `move`, `sprint`, and `unsprint` reject a non-shadow target.
- [ ] `/player as <player>` suggestions contain only current shadow targets.
- [ ] The approved language-spec review findings are resolved without removing
      retained capability fields or changing runtime behavior.
- [ ] No production Java source contains a forbidden programmer assertion.
- [ ] `requireNonNullElse` or `requireNonNullElseGet` appears only with a
      guaranteed non-null fallback.
- [ ] Every explicit production exception is caught and handled by its owner
      before it escapes an event-loop, packet, callback, or plugin boundary.
- [ ] Sprint packets occur only on effective forward-movement state changes.
- [ ] Dismount input remains pressed across a server tick and then restores the
      current input state.
- [ ] `kill` rejects a non-shadow target without removing or closing it.
- [ ] `design.md` describes shared Brigadier organization, action ownership,
      required trackers, and failure behavior without duplicated command trees.
- [ ] `implement.md` contains only approved implementation work and focused
      verification proportional to each changed behavior.
- [ ] Implementation receives separate explicit approval before task start.

## Product Boundary

This task remains protocol-only. It implements all feasible actions. It omits
commands that have no useful protocol implementation.

It does not take movement ownership from an attached real client. The
shadow-only command set is `jump`, `move`, `sprint`, `unsprint`, and `kill`.

## Research

- `research/command-inventory.md`
- `research/coordinate-syntax.md`
- `research/design-analysis.md`
- `research/local-action-prediction.md`
- `research/vanilla-local-prediction-gap.md`
- `research/implementation-architecture.md`
- `research/nonshadow-movement-divergence.md`
- `research/continuous-use-and-input-travel.md`
- Parent research:
  `../08-16-complete-carpet-player-commands/research/carpet-player-command-feasibility.md`
