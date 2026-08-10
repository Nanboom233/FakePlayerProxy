# Carpet `/player` Command Mapping

This document maps Carpet mod's `/player` command surface to the proposed
Velocity-plugin plus MCProtocolLib automation architecture.

The source of truth for Carpet behavior is the current `master` branch of
`gnembon/fabric-carpet`, checked on 2026-06-12:

- `PlayerCommand.java`: https://raw.githubusercontent.com/gnembon/fabric-carpet/master/src/main/java/carpet/commands/PlayerCommand.java
- `EntityPlayerActionPack.java`: https://raw.githubusercontent.com/gnembon/fabric-carpet/master/src/main/java/carpet/helpers/EntityPlayerActionPack.java
- `EntityPlayerMPFake.java`: https://raw.githubusercontent.com/gnembon/fabric-carpet/master/src/main/java/carpet/patches/EntityPlayerMPFake.java
- Wiki command page: https://github.com/gnembon/fabric-carpet/wiki/Commands

The wiki is useful for user-facing intent, but it is incomplete. The command
tree and action semantics below are based on source.

## Product Boundary

Carpet is a server-side mod. It can create and mutate `ServerPlayer` instances
directly. This proxy project is different: it can only act like a Minecraft
client unless Velocity is patched or the upstream server runs a cooperating
plugin/mod.

Therefore the proxy command must be stricter than Carpet:

- Accept only `/player self ...` or `/player <executing-user-name> ...`.
- Deny controlling arbitrary names, including fake-player names owned by other
  users.
- Treat `shadow` as "start a proxy-owned upstream automation session", not as a
  same-tick server-side fake-player replacement.
- Keep a `/fpp player ...` alias available so operators can disable literal
  `/player` if it conflicts with backend commands.

## Carpet Action Model To Reproduce

`EntityPlayerActionPack` stores an `EnumMap<ActionType, Action>`.

`Action` modes:

| Carpet syntax | Source behavior | Proxy scheduler equivalent |
| --- | --- | --- |
| no mode | Same as `once` | One scheduled execution |
| `once` | `limit=1`, `interval=1`, not continuous | One scheduled execution |
| `continuous` | Unlimited, every tick, continuous flag set | Tick every 50 ms until stopped |
| `interval <ticks>` | Unlimited, execute every N ticks | Tick on a per-action interval |

`stop` calls `stopAll()`: clear every action, release inactive action state,
clear movement, clear sneak, and clear sprint.

Proxy implementation implication:

- Implement a per-session `ActionScheduler`, not direct command-to-single-packet
  forwarding.
- Keep one active action per action type, replacing the previous action of that
  type when a new one starts.
- `stop` must cancel block breaking and item use side effects, not only clear
  Java objects.

## Recommended Proxy Grammar

MVP parser should accept Carpet-compatible shapes where they make sense:

```text
/player <self|own-name> shadow
/player <self|own-name> stop
/player <self|own-name> kill
/player <self|own-name> attack [once|continuous|interval <ticks>]
/player <self|own-name> use [once|continuous|interval <ticks>]
/player <self|own-name> jump [once|continuous|interval <ticks>]
/player <self|own-name> drop [once|continuous|interval <ticks>]
/player <self|own-name> dropStack [once|continuous|interval <ticks>]
/player <self|own-name> swapHands [once|continuous|interval <ticks>]
/player <self|own-name> drop <all|mainhand|offhand|slot>
/player <self|own-name> dropStack <all|mainhand|offhand|slot>
/player <self|own-name> hotbar <1-9>
/player <self|own-name> mount [anything]
/player <self|own-name> dismount
/player <self|own-name> sneak
/player <self|own-name> unsneak
/player <self|own-name> sprint
/player <self|own-name> unsprint
/player <self|own-name> look <north|south|east|west|up|down>
/player <self|own-name> look at <x> <y> <z>
/player <self|own-name> look <yaw> <pitch>
/player <self|own-name> turn <left|right|back>
/player <self|own-name> turn <yaw-delta> <pitch-delta>
/player <self|own-name> move
/player <self|own-name> move <forward|backward|left|right>
```

`spawn` is intentionally omitted from the MVP grammar. See the command matrix.

## MCProtocolLib Packet Surface

Current MCProtocolLib source confirms these relevant packet/data classes:

| Behavior | MCProtocolLib class |
| --- | --- |
| Position and rotation | `serverbound.player.ServerboundMovePlayerPosRotPacket` |
| Rotation only | `serverbound.player.ServerboundMovePlayerRotPacket` |
| Position only | `serverbound.player.ServerboundMovePlayerPosPacket` |
| Input flags | `serverbound.level.ServerboundPlayerInputPacket` |
| Entity attack | `serverbound.player.ServerboundAttackPacket` |
| Entity interact | `serverbound.player.ServerboundInteractPacket` |
| Generic item use | `serverbound.player.ServerboundUseItemPacket` |
| Block item use | `serverbound.player.ServerboundUseItemOnPacket` |
| Swing hand | `serverbound.player.ServerboundSwingPacket` |
| Drop/dig/swap action | `serverbound.player.ServerboundPlayerActionPacket` |
| Held hotbar slot | `serverbound.player.ServerboundSetCarriedItemPacket` |
| Sprint state | `serverbound.player.ServerboundPlayerCommandPacket` with `PlayerState` |
| Inventory click/drop | `serverbound.inventory.ServerboundContainerClickPacket` |
| Boat paddles | `serverbound.level.ServerboundPaddleBoatPacket` |

Important current-package correction: `ServerboundPlayerInputPacket` is under
`packet.ingame.serverbound.level`, not `packet.ingame.serverbound.player`.

## Command Matrix

Status meanings:

- `MVP`: implement in the first feature chain.
- `MVP-partial`: parse and implement the safe subset, with explicit runtime
  errors for missing target/world/inventory state.
- `Later`: defer until world, inventory, or vehicle tracking is reliable.
- `Unsupported-protocol`: not equivalent through vanilla client packets.

| Carpet command | Carpet source semantics | Proxy command/action | Protocol mapping | Status | Notes/tests |
| --- | --- | --- | --- | --- | --- |
| `shadow` | Rejects fake players and singleplayer owner; disconnects the real player as duplicate login; creates `EntityPlayerMPFake`; copies health, position, game mode, client info, action pack, model data, flying state. | Start `AutomationSession` for the executing user's configured target. Move real client to limbo or disconnect after upstream automation reaches play state. | State-machine event plus MCProtocolLib login. No single packet equivalent. | MVP | Must document "re-login shadow", not seamless same-connection handoff. Test returning user reclaim. |
| `stop` | `stopAll()`: stops all action types, clears actions, clears sneak/sprint/movement. | Clear action scheduler, clear movement flags, release use/dig state. Keep upstream connected. | Input packet with all flags false; abort dig via `PlayerAction.CANCEL_DIGGING` when needed; release item via `PlayerAction.RELEASE_USE_ITEM` when needed. | MVP | Unit-test that stop clears every action type and movement state. |
| `kill` | Allowed only for fake players; calls fake-player kill/disconnect flow. | Stop/remove proxy automation session for self. | Close MCProtocolLib session with audit reason. | MVP | Use "disconnect automation" semantics, not in-game suicide. Deny if no owned automation session exists. |
| `spawn` | Creates a new fake player name; optional position, facing, dimension, gamemode; checks duplicate, profile, ban, whitelist, offline-player setting. | Do not expose in MVP. Possible later meaning: start automation from saved config without a live handoff. | MCProtocolLib login to target server, but no server-side arbitrary fake creation. | Later | Avoid promising Carpet parity. It conflicts with self-only account ownership. |
| `attack` / `attack once` | Starts `ActionType.ATTACK` once. Raycasts target. Entity target attacks and swings only when not continuous. Block target starts/continues block breaking. | One attack action against current raycast target or explicit tracked target. | Entity: `ServerboundAttackPacket(entityId)` plus `ServerboundSwingPacket(MAIN_HAND)`. Block: `ServerboundPlayerActionPacket(START_DIGGING/FINISH_DIGGING/CANCEL_DIGGING)` plus swing. | MVP-partial | Entity attack requires entity tracker and reach validation. Block breaking requires sequence ids, block state, current block damage, and abort cleanup. |
| `attack continuous` | Holds attack. Especially meaningful for block breaking; source does not repeatedly entity-attack in continuous mode. | Hold attack in scheduler. | Repeated block-dig action sequence; entity behavior should not spam attacks unless explicitly configured later. | Later | Requires block progress model. Test by mining a controlled block. |
| `attack interval <ticks>` | Executes non-continuous attack every N ticks. | Periodic attack. | Same as once, scheduled. | MVP-partial | Requires cooldown/backoff so entity spam is not worse than vanilla client behavior. |
| `use` / `use once` | Raycasts. Tries block use, entity interaction, then item use for each hand. Applies a 3 tick item-use cooldown and keeps using-item state. | Right-click equivalent with main-hand first. Offhand fallback after inventory/hand tracking exists. | Block: `ServerboundUseItemOnPacket`; entity: `ServerboundInteractPacket`; item: `ServerboundUseItemPacket`; swing when needed. | MVP-partial | Need target tracker, sequence id, hand state, yaw/pitch. Explicitly surface "no target" failures. |
| `use continuous` | Holds right-click/use, respecting item-use cooldown and using-item state. | Continuous use scheduler. | Repeated use packets plus release on stop when applicable. | MVP-partial | Good MVP for eating/holding use on controlled servers after hand state is tracked. |
| `use interval <ticks>` | Executes use every N ticks. | Periodic use. | Same packet choices as once. | MVP-partial | Test against controlled block and generic held item. |
| `jump` / `jump once` | Once: if on ground jump; else try elytra start. Continuous: set jumping true until inactive tick clears. | Pulse or hold jump input. | `ServerboundPlayerInputPacket(... jump=true ...)`, plus normal movement packets. Elytra may require `ServerboundPlayerCommandPacket(START_ELYTRA_FLYING)`. | MVP | Start with ground jump only. Elytra is later/version-specific. |
| `jump continuous` | Holds jump flag. | Hold jump input until stopped. | Input flag. | MVP | Test swimming/climbing separately if supported later. |
| `jump interval <ticks>` | Pulses jump every N ticks. | Scheduled jump pulses. | Input flag pulse. | MVP | Scheduler test should verify one-tick pulse behavior. |
| `drop` / `drop once` | Drops one selected item via `player.drop(false)`. | Drop selected item. | `ServerboundPlayerActionPacket(PlayerAction.DROP_ITEM, ...)`. | Later | Easy packet, but defer until inventory tracker can reconcile server updates. |
| `drop continuous` / `drop interval` | Repeats selected-item drop. | Scheduled selected-item drop. | Same as selected drop. | Later | Needs rate limits to avoid destructive mistakes. |
| `drop all` | Server-side removes one item from every inventory slot. | Not equivalent in vanilla client without container/inventory simulation. | Many inventory clicks or unsupported depending inventory state. | Later | Requires inventory tracker and safe confirmation. |
| `drop mainhand` | Drops from selected hotbar slot. | Same as selected item drop. | `PlayerAction.DROP_ITEM`. | Later | Can become MVP after inventory tracker is in place. |
| `drop offhand` | Server-side removes one item from offhand slot 40. | Offhand slot drop. | Inventory click sequence likely needed. | Later | Not a single vanilla "drop offhand" action. |
| `drop <slot>` | Server-side removes one item from inventory slot 0-40. | Slot drop. | `ServerboundContainerClickPacket` with inventory state. | Later | Needs `stateId`, carried item, changed slots. |
| `dropStack` / `dropStack once` | Drops selected stack via `player.drop(true)`. | Drop selected stack. | `ServerboundPlayerActionPacket(PlayerAction.DROP_ITEM_STACK, ...)`. | Later | Same deferral as `drop`. |
| `dropStack all/mainhand/offhand/<slot>` | Server-side removes whole stacks from selected/all slots. | Slot/whole inventory drop. | Container clicks or unsupported depending slot. | Later | Requires destructive-action confirmation in limbo/config. |
| `swapHands` / modes | Swaps main hand and off hand. | Swap hands once or scheduled. | `ServerboundPlayerActionPacket(PlayerAction.SWAP_HANDS, ...)`. | Later | Packet is simple, but inventory tracker should verify result. |
| `hotbar <1-9>` | Sets selected slot to `slot - 1` and sends held-slot update to client. | Select hotbar slot. | `ServerboundSetCarriedItemPacket(slot - 1)`. | MVP | Validate 1-9 only and update local inventory tracker. |
| `mount` | Finds closest nearby minecart, boat, or horse-like entity and rides/interacts with it. | Interact with nearest tracked rideable entity. | `ServerboundInteractPacket(entityId, MAIN_HAND, hitPos, sneaking)`; boat control later uses paddle packet. | Later | Needs entity tracker, nearest filter, hit position, and server-specific mount rules. |
| `mount anything` | Force rides any nearby entity server-side. | No vanilla-client equivalent. | None reliable. | Unsupported-protocol | Only possible with cooperating upstream plugin/mod or Velocity/backend patch that can mutate server state. |
| `dismount` | Server-side `stopRiding()`. | Dismount current vehicle. | Usually shift input via `ServerboundPlayerInputPacket(shift=true)` while riding; vehicle-specific behavior may vary. | Later | Needs vehicle state tracker. |
| `sneak` | Sets sneaking true, clears sprint if both true. | Hold shift/sneak input. | `ServerboundPlayerInputPacket(shift=true, sprint=false, ...)`. | MVP | In current MCProtocolLib, sneak is input flag, not `PlayerState`. |
| `unsneak` | Sets sneaking false. | Clear shift input. | `ServerboundPlayerInputPacket(shift=false, ...)`. | MVP | Preserve other movement flags. |
| `sprint` | Sets sprint true, clears sneak if both true. | Hold sprint state/input. | `ServerboundPlayerInputPacket(sprint=true, shift=false, ...)`; optionally `ServerboundPlayerCommandPacket(START_SPRINTING)` for version/server behavior. | MVP | Build a version adapter so tests decide whether command packet is needed. |
| `unsprint` | Sets sprint false. | Clear sprint. | `ServerboundPlayerInputPacket(sprint=false, ...)`; optionally `ServerboundPlayerCommandPacket(STOP_SPRINTING)`. | MVP | Preserve movement flags. |
| `look north/south/east/west/up/down` | Sets yaw/pitch to fixed directions. North 180, south 0, east -90, west 90, up pitch -90, down pitch 90. | Set rotation. | `ServerboundMovePlayerRotPacket` or `ServerboundMovePlayerPosRotPacket` with tracked position. | MVP | Verify yaw convention against upstream version. |
| `look at <position>` | Server-side `lookAt(EYES, position)`. | Compute yaw/pitch from eye position to target coordinate. | Rotation/posrot packet. | MVP | Needs current position and eye height. |
| `look <rotation>` | Sets yaw/pitch with clamp for pitch. | Set numeric yaw/pitch. | Rotation/posrot packet. | MVP | Proxy syntax should use explicit `<yaw> <pitch>` to avoid Brigadier relative-rotation ambiguity initially. |
| `turn left/right/back` | Relative yaw -90, +90, +180. | Add delta to tracked rotation. | Rotation/posrot packet. | MVP | Depends only on current rotation tracker. |
| `turn <rotation>` | Relative yaw/pitch. | Add numeric delta. | Rotation/posrot packet. | MVP | Clamp pitch to -90..90. |
| `move` | Stops movement and also clears sneak/sprint. | Clear movement, sneak, and sprint flags. | `ServerboundPlayerInputPacket(all movement false, shift=false, sprint=false)`, plus movement tick loop stops. | MVP | Match Carpet behavior, not just movement-axis behavior. |
| `move forward` | Sets forward axis to +1. Does not clear strafe. | Set forward flag, clear backward flag. Preserve strafe, jump, sneak, sprint. | Input flags plus periodic movement packets. | MVP | Allows diagonal movement with a later `move left/right`. |
| `move backward` | Sets forward axis to -1. | Set backward flag, clear forward flag. | Input flags plus periodic movement packets. | MVP | Same. |
| `move left` | Sets strafe axis to +1. | Set left flag, clear right flag. | Input flags plus periodic movement packets. | MVP | Verify left/right sign against MCProtocolLib flag semantics in integration test. |
| `move right` | Sets strafe axis to -1. | Set right flag, clear left flag. | Input flags plus periodic movement packets. | MVP | Same. |

## MVP Command Acceptance

The first implementation chain should accept the following as testable MVP
scope:

- Full parser and ownership policy for all command names in the matrix.
- MVP runtime implementation for `shadow`, `stop`, `kill`, `hotbar`, `move`,
  `look`, `turn`, `jump`, `sneak`, `unsneak`, `sprint`, and `unsprint`.
- MVP-partial runtime implementation for `attack` and `use`: support the
  scheduler and safe target-aware once/interval forms on controlled servers,
  with clear errors when target/world state is missing.
- Parse but return explicit "not implemented yet" for inventory and vehicle
  commands: `drop`, `dropStack`, `swapHands`, `mount`, `dismount`.
- Do not expose `spawn` until the product chooses what self-only "spawn" means.
- Return "unsupported by protocol-only automation" for `mount anything`.

## Runtime State Required By Command Family

| State/tracker | Required by |
| --- | --- |
| Automation session lifecycle | `shadow`, `kill`, all commands |
| Player position/rotation/on-ground | `look`, `turn`, `move`, `jump`, `attack`, `use` |
| Input flag state | `move`, `jump`, `sneak`, `sprint` |
| Entity tracker | `attack` entity, `use` entity, `mount`, `dismount` |
| Block/raycast tracker | `attack` block, `use` block, `look at` |
| Inventory and selected hotbar slot | `hotbar`, `use`, `drop`, `dropStack`, `swapHands` |
| Sequence/state ids | `use` on block, digging, inventory clicks |
| Vehicle state | `mount`, `dismount`, boat movement |
| Auth material | `shadow`, reconnect after upstream disconnect |

## Test Cases To Carry Forward

Parser and policy:

- `/player self shadow` is accepted for the executing user.
- `/player <own-name> shadow` is accepted case-insensitively.
- `/player <other-name> stop` is denied for every role until an admin feature
  is explicitly designed.
- Literal `/player` can be disabled while `/fpp player` remains available.

Scheduler:

- Issuing `attack interval 5` replaces a previous `attack continuous`.
- `stop` clears attack, use, jump, move, sneak, and sprint state.
- `move forward` then `move left` yields diagonal input.
- Bare `move` clears both axes and clears sprint/sneak.

Protocol smoke tests on a controlled server:

- `hotbar 2` changes selected held slot.
- `look north`, `turn right`, and `look at x y z` update rotation correctly.
- `jump once` produces one jump pulse.
- `sneak` and `unsneak` toggle shift state without corrupting movement flags.
- `sprint` and `unsprint` toggle sprint behavior on the chosen Minecraft
  version.
- `attack once` against a tracked entity sends attack plus swing.
- `use once` with no target sends generic item use; with a block target sends
  use-on-block with a valid sequence id.

Unsupported/deferred behavior tests:

- `mount anything` returns a clear unsupported error in protocol-only mode.
- `drop all`, `dropStack all`, and slot drops are blocked until inventory
  tracking exists.
- `spawn` returns a scope error unless later product design assigns self-only
  semantics.
