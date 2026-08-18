# Research: Continuous use and local input travel defects

## Scope

This report covers the failed continuous food use and incorrect shadow
movement in Minecraft Java 26.2. It uses repository code and archived Vanilla
sources only.

## Continuous use root cause

Vanilla `ServerGamePacketListenerImpl.handleSetCarriedItem` calls
`stopUsingItem()` when the selected main-hand slot changes during main-hand
use. It then applies the new selected slot.

The plugin handler updates only `PlayerInventory.selectedSlot`. It does not
clear `Player.activeUseHand`. The backend and local model then disagree.

`Player.use` returns success immediately while `activeUseHand` is non-null. It
does not send another `ServerboundUseItemPacket`. After a slot change, the
backend has stopped use, but the proxy suppresses the request for the new
food.

The same local state can remain after the backend finishes an item. Tracking
only a hand cannot represent the selected item or authoritative backend use
state.

## Continuous use design boundary

Minecraft publishes active use through `LivingEntity.DATA_LIVING_ENTITY_FLAGS`.
Bit zero means active use. Bit one selects the off hand. The fixed entity
metadata schema already records related entity and living metadata IDs, but it
does not record this accessor.

The correct model adds the living-flags metadata ID to the existing generated
entity schema. `LivingEntity` applies the flags. `Player` reconciles its
existing active-use hand from the authoritative flag and hand.

A real-client main-hand slot change clears local main-hand use immediately.
It sends no release packet because Vanilla has already stopped backend use.
The next continuous action tick can send one use request for the new item.

When backend metadata clears active use after consumption or interruption, the
next continuous action tick can start the next valid use. While metadata keeps
active use set, later ticks send no repeated use packet.

## Movement root cause

`Player.applyMovementInput` currently rotates the input and adds full effective
movement speed directly to velocity. `LivingEntity.travel` then selects air,
water, lava, or gliding and applies drag to that velocity.

Vanilla passes an input vector into `LivingEntity.travel`. Each environment
branch applies its own input acceleration before movement and drag.

- Ground input uses friction-influenced movement speed.
- Air input uses flying speed.
- Water input starts at `0.02` and applies water movement efficiency.
- Lava input starts at `0.02`.
- Water sprint changes horizontal slowdown.

The plugin applies land movement speed before the water branch. Water drag
still runs, but it acts on an excessive starting velocity. This causes the
reported missing water slowdown.

## Movement design boundary

`Player` must create the modified local input vector and jump intent. It must
not add horizontal velocity.

`LivingEntity.travel` must accept the input vector. Its air, water, lava, and
flight branches must apply the matching Vanilla acceleration before existing
movement, collision, current, gravity, and drag logic.

The repair reuses the existing travel branches. It adds no parallel movement
engine and no movement helper class.

The required input factors are the Vanilla `0.98` input multiplier,
square-input adjustment, current sneak scaling, sprint state, fluid
acceleration, and water jump or descend input. Existing fixed block friction,
speed factor, fluid current, collision, and water movement efficiency remain
authoritative.

## Focused validation boundary

Use existing test classes only.

- A continuous use test changes from an active main-hand item to food and
  verifies one new use request.
- A continuous use test applies backend use metadata and verifies that active
  use suppresses repeated packets until the metadata clears.
- A movement test compares the same forward input on land and in water.
- A movement test verifies water jump input changes vertical movement.

These cases cover the failed state transitions. They do not add a test for
every block or fluid state.
