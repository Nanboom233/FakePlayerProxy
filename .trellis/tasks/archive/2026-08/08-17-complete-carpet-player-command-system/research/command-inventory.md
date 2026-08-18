# Carpet `/player` command inventory

## Evidence

- Inspected: 2026-08-16
- Carpet branch: `master`
- Carpet commit: `21993f2585d6714ced9fc86b1f6d3721cf4c60ab`
- Command source: <https://github.com/gnembon/fabric-carpet/blob/21993f2585d6714ced9fc86b1f6d3721cf4c60ab/src/main/java/carpet/commands/PlayerCommand.java>
- Action semantics: <https://github.com/gnembon/fabric-carpet/blob/21993f2585d6714ced9fc86b1f6d3721cf4c60ab/src/main/java/carpet/helpers/EntityPlayerActionPack.java>

The source defines 20 first-level actions. The older wiki is not the inventory
authority.

## Complete grammar

```text
/player <player> stop
/player <player> use [once|continuous|interval <ticks>=1..]
/player <player> jump [once|continuous|interval <ticks>=1..]
/player <player> attack [once|continuous|interval <ticks>=1..]
/player <player> drop [once|continuous|interval <ticks>=1..]
/player <player> drop <all|mainhand|offhand|slot=0..40>
/player <player> dropStack [once|continuous|interval <ticks>=1..]
/player <player> dropStack <all|mainhand|offhand|slot=0..40>
/player <player> swapHands [once|continuous|interval <ticks>=1..]
/player <player> hotbar <slot=1..9>
/player <player> kill
/player <player> shadow
/player <player> mount [anything]
/player <player> dismount
/player <player> sneak
/player <player> unsneak
/player <player> sprint
/player <player> unsprint
/player <player> look <north|south|east|west|up|down>
/player <player> look at <x> <y> <z>
/player <player> look <yaw> <pitch>
/player <player> turn <left|right|back>
/player <player> turn <yaw-delta> <pitch-delta>
/player <player> move
/player <player> move <forward|backward|left|right>
/player <player> spawn
/player <player> spawn in <gamemode>
/player <player> spawn at <x> <y> <z>
/player <player> spawn at <x> <y> <z> facing <yaw> <pitch>
/player <player> spawn at <x> <y> <z> facing <yaw> <pitch> in <dimension>
/player <player> spawn at <x> <y> <z> facing <yaw> <pitch> in <dimension> in <gamemode>
```

## Semantics relevant to the command tree

- A scheduled action without a mode means `once`.
- Carpet stores one schedule per action type. A new action of the same type
  replaces the previous one.
- `continuous` runs every tick. `interval N` runs every N ticks.
- Use is evaluated before attack. If use initially fails and attack succeeds,
  use is retried after attack.

## Feasibility grouping

### Direct client-equivalent operations

The direct group contains `shadow`, `kill`, `hotbar`, `sneak`, `unsneak`,
`sprint`, and `unsprint`. It also contains `look`, `look at`, `turn`, `move`,
and ordinary `dismount`. These actions need the existing connection and limited
state reconciliation.

### Implementable after explicit tracking or algorithms

This group contains `stop`, scheduled `jump`, `swapHands`, `drop`, `dropStack`,
`attack`, and `use`. These actions require complete action state, inventory
tracking, raycasting, digging state, sequences, cooldowns, and cleanup. Existing
packet prototypes are not sufficient.

### Best effort or impossible without server authority

- `mount` expands the player's bounds by `(3, 1, 3)`. It searches this area for
  minecarts, boats, and `AbstractHorse` entities. It excludes the player and the
  current vehicle. It then selects the closest candidate. A horse receives a
  main-hand `mobInteract`. A minecart or boat is mounted through
  `player.startRiding(closest, true, true)`.
- `mount anything` uses the same search area and closest-candidate selection.
  It removes the type filter and the horse special case. It then calls
  `player.startRiding(closest, true, true)` for the selected entity.
- Therefore even ordinary Carpet `mount` has forced server-side semantics for
  boats and minecarts. A protocol-only client can send the corresponding
  interaction request, but cannot reproduce Carpet's guaranteed force flag.
- `spawn` and all variants: Carpet creates arbitrary server-side players. One
  authenticated proxy connection cannot reproduce that operation.

The detailed per-family evidence remains in the parent feasibility research.
this file is the discussion baseline for the new child task.
