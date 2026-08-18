# Complete Carpet Player Commands

## Goal

Expose each Carpet `/player` action that FakePlayerProxy can implement through
the Minecraft protocol. Preserve Carpet action scheduling semantics where the
proxy can observe the required client state.

The task also removes or restructures dormant prototype code and updates product
documentation so the public command contract matches the implementation.

## Background

- Carpet `master` commit `21993f2585d6714ced9fc86b1f6d3721cf4c60ab`
  was inspected on 2026-08-16. Its command tree contains 20 first-level actions.
- The earlier 2026-06-12 command mapping remains broadly accurate, but it was an
  MVP plan and deliberately deferred several command families.
- The public proxy command currently accepts only targetless `/player shadow`
  (`plugin/src/main/java/com/fakeplayerproxy/command/PlayerCommand.java:31`).
- `AutomationService` and `world.player.Player` retain unexposed prototype
  methods for several actions. Some are only packet placeholders: attack sends
  a swing without an attack target, and use sends only generic main-hand item
  use (`plugin/src/main/java/com/fakeplayerproxy/world/player/Player.java:201`).
- The runtime already tracks player transforms, chunks, block states, entities,
  equipment metadata, passenger graphs, and vehicle motion. It does not track
  player inventory/container state, interaction sequence IDs, or client-style
  raycast results.
- `docs/product/operation-guide.md` describes a broader `/player self ...`
  grammar than the current command implementation exposes.

## Requirements

1. Define one authoritative, source-verified command grammar for all Carpet
   first-level actions and their parameter variants.
2. Preserve the project's self-owned automation security boundary.
3. Match Carpet's one-action-per-type scheduler and `once`, `continuous`, and
   `interval <ticks>` modes for `use`, `jump`, `attack`, `drop`, `dropStack`,
   and `swapHands`.
4. Match Carpet's cross-action behavior. Successful use suppresses attack for
   that tick. A successful attack can retry a failed use. `stop` releases use
   and dig state. It also clears movement, sneak, and sprint.
5. Implement target selection, inventory synchronization, sequence ownership,
   and cleanup state before claiming full behavior for commands that require
   them.
6. Produce stable, translated failures for missing tracked state, invalid
   arguments, and inactive automation sessions.
7. Omit server-only branches. Do not register an executor that only reports an
   unsupported operation.
8. Audit existing command/action code. Keep and refactor code that belongs to
   the final command architecture. Remove obsolete MVP-only branches,
   suppressions, placeholders, and documentation claims that no longer match.
9. Keep Carpet player actions exclusively under `/player`. Rewrite `/fpp` as
   the plugin configuration namespace. Do not retain a `/fpp player` alias.
10. Add parser, scheduler, state, packet, cleanup, and controlled-server coverage
   appropriate to each implemented command family.

## Acceptance Criteria

- [ ] A checked-in command matrix lists every Carpet command form from the
      pinned upstream source. It maps each form to proxy behavior.
- [ ] Every accepted action routes to an observable implementation. No command
      reports success after only sending a non-equivalent placeholder packet.
- [ ] Action mode replacement, tick cadence, use/attack ordering, and cleanup
      match Carpet's action-pack behavior.
- [ ] Direct control commands cover `stop`, `kill`, `hotbar`, `dismount`,
      `sneak`, `unsneak`, `sprint`, `unsprint`, `look`, `turn`, and `move`.
- [ ] Scheduled commands cover every supported mode of `use`, `jump`, `attack`,
      `drop`, `dropStack`, and `swapHands`.
- [ ] Target-aware attack/use and selected-slot inventory operations are backed
      by authoritative tracked state and protocol acknowledgements.
- [ ] Commands without a useful protocol implementation are absent from the
      Brigadier tree.
- [ ] Obsolete prototype/dead code and stale documentation are removed or
      refactored, with no unused-action suppressions left as architecture.
- [ ] The old `/fpp` action and status branches are absent. `/fpp` exposes only
      current plugin configuration.
- [ ] Focused unit tests and the existing plugin/runtime verification suite pass.

## Out of Scope

- Supporting Minecraft protocol versions other than the project's pinned 26.2
  target.
- Controlling another user's authenticated automation session.
- Copying Carpet implementation code. Carpet is behavioral reference only.

## Child Tasks

| Child task | Scope | Dependency |
| --- | --- | --- |
| `08-16-player-as-action-command` | Add `/player as <player> <action>`, permission checks, and stable automation target lookup. | This command layer must exist before later action children expose remote actions. |
| `08-17-complete-carpet-player-command-system` | Implement all feasible action branches and omit server-only branches. | The shared self and target command structure must remain intact. |

## Product Boundary

- The implementation remains protocol-only.
- `mount anything` is absent.
- A later task will define new `spawn` semantics.

## Research

See `research/carpet-player-command-feasibility.md`.
