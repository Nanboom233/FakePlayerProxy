# Carpet `/player` Command Inventory And Feasibility

## Evidence Baseline

- Inspected on: 2026-08-16
- Carpet branch: `master`
- Carpet commit: `21993f2585d6714ced9fc86b1f6d3721cf4c60ab`
- Command source: <https://github.com/gnembon/fabric-carpet/blob/21993f2585d6714ced9fc86b1f6d3721cf4c60ab/src/main/java/carpet/commands/PlayerCommand.java>
- Action semantics: <https://github.com/gnembon/fabric-carpet/blob/21993f2585d6714ced9fc86b1f6d3721cf4c60ab/src/main/java/carpet/helpers/EntityPlayerActionPack.java>
- Fake-player lifecycle: <https://github.com/gnembon/fabric-carpet/blob/21993f2585d6714ced9fc86b1f6d3721cf4c60ab/src/main/java/carpet/patches/EntityPlayerMPFake.java>

The source, rather than the incomplete wiki page, is the command inventory.
There are 20 first-level actions below. No additional first-level player action
was found relative to the 2026-06-12 project research.

## Complete Grammar

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

Bare scheduled actions mean `once`. Carpet stores one action per action type;
starting the same type stops and replaces the old action. `continuous` executes
each tick, while `interval N` executes every N ticks. The source also gives use
priority over attack and retries use after a successful attack when the first
use attempt failed.

## Feasibility Scale

- **A - direct**: current tracked state and vanilla client packets are enough.
- **B - implementable foundation**: vanilla packets can reproduce the user
  outcome, but this repository must first add a named tracker or algorithm.
- **C - best effort only**: a normal client can request the outcome, but Carpet
  can force server state that the client cannot guarantee.
- **D - impossible protocol-only**: requires server-side authority or another
  authenticated connection/account.

## Command Analysis

| Command family | Grade | Current state | Required work / parity limit |
| --- | --- | --- | --- |
| `shadow` | A | Implemented and publicly routed | Keep the existing same-backend handoff semantics. Carpet copies a server player into a fake player; this project deliberately retains the authenticated backend connection instead. |
| `kill` | A | Manager close exists; command is not routed | Define it as closing the owned automation session. This is the product-equivalent lifecycle result, not an in-world suicide. |
| `stop` | B | Clears scheduler and input only | Add active-use release and active-dig abort ownership. Carpet also clears movement, sneak, and sprint. |
| `hotbar` | A | Packet method exists but is unexposed | Route and track the selected slot; reconcile `ClientboundSetHeldSlotPacket`. |
| `sneak`, `unsneak` | A | Packet methods exist but are unexposed | Route them and preserve orthogonal input flags. Sneak and sprint remain mutually exclusive. |
| `sprint`, `unsprint` | A | Input and command packets exist but are unexposed | Route and test 26.2 server behavior; preserve other input flags. |
| `look` directions / rotation | A | Rotation method exists but is unexposed | Add grammar, Carpet direction constants, pitch clamp, relative-coordinate policy, and tests. |
| `look at` | A | Position and eye height are tracked | Compute yaw/pitch from the tracked eye position. Reject non-finite or unavailable coordinates. |
| `turn` | A | Delta method exists but is unexposed | Add named and numeric grammar; normalize yaw and clamp pitch as Carpet does. |
| `move` and directions | A | Input method exists but is unexposed | Bare `move` must clear both axes plus sneak/sprint. Direction changes preserve the other axis for diagonal movement. |
| `jump` modes | B | Pulse/hold/interval prototypes exist | Integrate action scheduling with the physics tick. `once` also attempts elytra start while airborne; exact support needs chest-slot/inventory state and the elytra command packet. |
| `swapHands` modes | A/B | Packet and scheduler prototype exist | The packet is direct. Add inventory/equipment reconciliation before claiming stateful parity after server rejection or plugin cancellation. |
| selected `drop` / `dropStack` modes | A/B | Direct action packets and scheduler exist | Packets are direct, but destructive continuous/interval actions need server-result reconciliation, rate limits, and clear ownership. |
| slot/mainhand/offhand/all `drop*` | B | No inventory tracker | Track container 0 contents, state ID, carried item, slot updates, and acknowledgements; execute valid throw click sequences. Carpet removes slots server-side, so atomic all-slot behavior cannot be guaranteed. |
| entity `attack` once/interval | B | Current placeholder only swings | Add client-equivalent entity/block raycast with reach and occlusion, then send `ServerboundAttackPacket` plus swing. Track entity removal and attack cooldown where applicable. |
| block `attack` continuous | B | Not implemented | Add target raycast, sequence IDs, START/ABORT/STOP dig lifecycle, block/tool destroy progress, game mode, effects/attributes, target changes, and five-tick post-break delay. |
| `use` modes | B | Current placeholder sends generic main-hand use | Add raycast and Carpet order: block/entity/item, main hand then offhand, three-tick use cooldown, using-item state, release cleanup, interaction hit position, and sequence IDs. |
| `mount` | C | Entity/passenger/vehicle tracking exists; no request | Interact with the closest tracked minecart, boat, or horse within Carpet's search box. A normal client cannot force `startRiding`; server rules can reject it, so exact Carpet parity is impossible without backend cooperation. |
| `dismount` | A/B | Shift pulse prototype exists | Passenger state is tracked. Validate the 26.2 dismount input lifecycle and do not corrupt persistent sneak state. |
| `mount anything` | D | Not implemented | Carpet calls forced server-side `startRiding`. Vanilla protocol has no general force-mount packet. Requires a cooperating backend mod/plugin. |
| `spawn` variants | D | Not exposed | Carpet creates an arbitrary server-side fake `ServerPlayer`. The proxy owns one already-authenticated account and cannot create arbitrary identities or connections without credentials. A backend component or a separately designed account pool is required. |

## Repository Gaps And Refactor Targets

1. `PlayerCommand` exposes only `shadow`, while the operation guide documents a
   much broader self-target grammar. The parser and documentation currently
   disagree.
2. `AutomationService` contains multiple `@SuppressWarnings("unused")` action
   entry points because the old exact-shadow command never routes them. These
   should become real command handlers or be removed; suppressions must not be
   the final architecture.
3. `Player.attack()` only swings. It is not an attack implementation.
4. `Player.use()` always sends a main-hand generic-use packet with sequence 0.
   It does not implement Carpet block/entity/hand selection.
5. The generic scheduler repeats `attack` each tick for `continuous`; Carpet
   continuous attack does not repeatedly hit entity targets and instead uses
   the held action primarily for block breaking.
6. `stopActions()` does not currently release a held item or abort an active
   block break because those states are not modeled.
7. Entity, block, passenger, and vehicle state already have substantial
   infrastructure. Inventory/container tracking and a shared client raycast are
   the main missing state owners; they should be explicit modules rather than
   more fields added ad hoc to the command class.

## Architectural Conclusion

All ordinary client actions are implementable to client-equivalent behavior on
Minecraft 26.2, although attack/use/full inventory commands require significant
new state tracking. Exact Carpet parity is categorically unavailable for
`spawn`, `mount anything`, and the force-success aspect of `mount` because
Carpet mutates authoritative server objects. The product must choose between:

1. protocol-only complete command coverage, with explicit unsupported results
   for server-only operations; or
2. a new cooperating backend component, which changes deployment, trust,
   compatibility, and testing scope.
