# Complete player command design analysis

## Confirmed boundary

- Implement every action that has a useful Minecraft 26.2 protocol path.
- Do not register `mount anything`.
- Do not register `spawn` in this task.
- Do not add placeholder executors for omitted commands.
- Add `mount <x> <y> <z>` as a project extension.

## Current repository state

`PlayerCommand` only builds `shadow`. It attaches the same built node under the
self root and the protected `as <player>` branch.

`AutomationService` contains action methods for most command families. Several
methods still call packet prototypes. `attack` only swings. `use` only sends a
main-hand item-use packet with sequence zero.

`World` tracks entity positions, bounds, movement kinds, and passenger links.
The entity map has no rideable query. `EntityTypeData.MovementKind` already
separates minecarts, boats, and horses.

`LivingEntity` applies several server attributes. It currently ignores
`ENTITY_INTERACTION_RANGE`. Minecraft 26.2 defines a default value of `3.0` and
adds `2.0` in Creative mode through the same attribute.

`Player` does not track inventory contents, the selected slot, interaction
sequences, active item use, or active block destruction. The fixed block table
also lacks mining speed data.

## Carpet mount behavior

Carpet searches the player bounds after an expansion of `(3, 1, 3)`. Normal
`mount` filters minecarts, boats, and `AbstractHorse` entities.

Carpet selects the closest entity position. It uses main-hand `mobInteract` for
a horse. It calls `startRiding(target, true, true)` for a boat or minecart.

`mount anything` removes the type filter and always calls forced `startRiding`.
The proxy cannot request this forced operation through the vanilla protocol.

## Protocol mount behavior

MCProtocolLib build 16 provides `ServerboundInteractPacket`. Its constructor
accepts an entity ID, a main-hand hit position, and the current sneak state.

The backend checks the target entity and its world border. It also checks the
player's entity interaction range. The exact player helper measures the eye
position against the target bounds.

The project must use the tracked `ENTITY_INTERACTION_RANGE` value for its early
check. It must not use a hard-coded Survival or Creative distance.

The server can still reject a valid packet. The command can report packet
submission, but it cannot report guaranteed riding.

## Required state by command family

| State or algorithm | Command users | Current gap |
| --- | --- | --- |
| Shared target resolution | Every action | Only `shadow` resolves a target |
| Ordered action schedule | `use`, `attack`, `jump`, `drop`, `dropStack`, `swapHands` | Current order runs attack before use |
| Interaction sequence | Block `use` and block `attack` | Every prototype uses sequence zero |
| Client raycast | `use` and `attack` | No block and entity raycast exists |
| Inventory state | `hotbar`, `jump`, `use`, `drop`, `dropStack`, `swapHands`, block `attack` | No container-zero state exists |
| Active use state | `use` and `stop` | No release state exists |
| Active block destruction | `attack` and `stop` | No start, abort, progress, or delay state exists |
| Entity interaction range | `mount`, entity `use`, entity `attack` | The attribute is ignored |
| Mining data | Continuous block `attack` | The fixed block table lacks destroy progress inputs |

## Language spec findings

The design does not add an action class for each command. Short Brigadier
executors stay inline in the command-tree sections.

One shared execution path is valid because every action needs the same target,
EventLoop, result, and translated failure handling. This is current reuse, not
possible future reuse.

The scheduler keeps the existing fastutil `Pair`. It does not restore the
removed two-field scheduler record.

Raycast alternatives need different packet behavior. A sealed hit contract is
valid under the language spec. Each hit value has three or more named fields.

The design uses the existing generic `Result`. It does not add another result
type or an error wrapper.
