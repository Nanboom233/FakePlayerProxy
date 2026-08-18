# Complete Carpet player command design

## Product boundary

The plugin registers every source-verified Carpet action that has a useful
Minecraft 26.2 protocol operation.

It registers no `spawn` branch. It registers no `mount anything` branch. It
adds no executor that only reports unsupported behavior.

The following list shows the action grammar. The target form provides every
entry. The self form omits `jump`, `move`, `sprint`, `unsprint`, and `kill`.

```text
/player stop
/player use [once|continuous|interval <ticks>]
/player jump [once|continuous|interval <ticks>]
/player attack [once|continuous|interval <ticks>]
/player drop [once|continuous|interval <ticks>|all|mainhand|offhand|<slot>]
/player dropStack [once|continuous|interval <ticks>|all|mainhand|offhand|<slot>]
/player swapHands [once|continuous|interval <ticks>]
/player hotbar <slot>
/player kill
/player shadow
/player mount [<x> <y> <z>]
/player dismount
/player sneak
/player unsneak
/player sprint
/player unsprint
/player look <north|south|east|west|up|down>
/player look <yaw> <pitch>
/player look at <x> <y> <z>
/player turn <left|right|back>
/player turn <yaw-delta> <pitch-delta>
/player move [forward|backward|left|right]
```

The non-shadow action nodes also appear below `/player as <player>`. The `as`
node keeps `.requires(source -> source.hasPermission(fakeplayerproxy.op))`.
Shadow-only action nodes attach only below the target argument.

## Shadow-only command visibility

The current phase limits `jump`, `move`, `sprint`, `unsprint`, and `kill` to a
resolved shadow target. A service guard remains authoritative for execution.

The existing player suggestion helper returns only manager entries whose
automation service is shadow. The helper reads current state for each request.
It does not cache names or require command-tree refresh.

Brigadier `.requires()` receives only `CommandSource`. Shadow-only action
nodes therefore use `requiresWithContext` to inspect the parsed player name.
The service guard repeats the target-state check at execution time because the
state can change after parsing.

The target suggestion filter runs before Brigadier knows the action. It also
hides non-shadow names for `shadow` and other non-shadow actions. Manual input
of a non-shadow name remains valid for those actions. This is the approved
suggestion boundary.

The design adds no suggestion provider. It changes the existing player
suggestion helper and adds only the target-state lookup needed by the context
requirement.

## File structure

### New files

`world/data/ItemData.java` stores fixed item defaults used by generic use,
cooldown lookup, attack range, and mining.

`world/player/PlayerInventory.java` owns the 41 Carpet-visible inventory slots,
container-zero projection, cursor, selected slot, menu state, and default plus
patch component resolution.

`world/phys/InteractionHit.java` is a sealed interface. Its nested `BlockHit`
and `EntityHit` records carry different packet fields. It has no miss record.

### Existing fixed-data files

`plugin/tools/minecraft-data-generator-26.2.patch` exposes block outline,
hardness, correct-tool, and item defaults. It also exposes required features,
entity pickability, and entity pick radius from the pinned generator.

`plugin/tools/GenResources.kt` validates and writes those values into the one
existing binary. It deduplicates collision and outline shapes independently.

`minecraft-data.bin` remains the only generated runtime resource.

`Block.java` renames the current shape field to `collisionShapeId`. It adds
`outlineShapeId`, `destroySpeed`, and `requiresCorrectToolForDrops`.

`EntityTypeData.java` adds `pickable`, `pickRadius`, and the living-flags
metadata ID.

`Decoder.java` reads the new header, shape tables, item table, block fields,
and entity fields. Existing named item IDs remain available to movement code.

### Existing runtime files

`AABB.java` adds closest-point and segment-clip operations. Segment clipping
returns a fastutil pair of hit point and face.

`World.java` stores all received block tags. It adds the shared block and
entity raycast. It adds bare-mount and coordinate-mount selection.

`Player.java` owns `PlayerInventory`, interaction attributes, mining effects,
abilities, food, cooldowns, active use, active destruction, movement input,
sprint state, delayed input release, and the shared sequence. It replaces the
current action prototypes with stateful packet operations.

`LivingEntity.java` exposes its existing attribute calculation to `Player`.
It accepts normalized local input in `travel` and applies environment-specific
acceleration through the existing collision, gravity, fluid, and friction
paths. It tracks effective jump strength, jump boost, and authoritative living
use flags.

`AutomationService.java` owns only schedule state and action order. It keeps
the existing fastutil pair. It advances passive world state before scheduled
actions and keeps local player movement shadow-only.

`AutomationManager.java` adds exact-entry removal for `kill`. It rejects a
non-shadow target before removal. It removes and closes only the selected
shadow wrapper.

`PlayerCommand.java` owns all Brigadier nodes, position parsing, suggestions,
target resolution, EventLoop submission, and translated failure rendering.

`FakePlayerProxyPlugin.java` routes the new clientbound state packets and the
serverbound carried-item packet to the existing player object. The handlers
remain synchronous and mutate only connection EventLoop state.

The Velocity patch does not change. No new dependency is required.

### Changed file manifest

The production change set is limited to these paths:

- `plugin/tools/minecraft-data-generator-26.2.patch`
- `plugin/tools/GenResources.kt`
- `plugin/src/main/resources/minecraft-data/minecraft-data.bin`
- `plugin/src/main/java/com/fakeplayerproxy/world/data/Block.java`
- `plugin/src/main/java/com/fakeplayerproxy/world/data/EntityTypeData.java`
- `plugin/src/main/java/com/fakeplayerproxy/world/data/ItemData.java`
- `plugin/src/main/java/com/fakeplayerproxy/world/data/Decoder.java`
- `plugin/src/main/java/com/fakeplayerproxy/world/phys/AABB.java`
- `plugin/src/main/java/com/fakeplayerproxy/world/phys/InteractionHit.java`
- `plugin/src/main/java/com/fakeplayerproxy/world/world/World.java`
- `plugin/src/main/java/com/fakeplayerproxy/world/player/PlayerInventory.java`
- `plugin/src/main/java/com/fakeplayerproxy/world/player/Player.java`
- `plugin/src/main/java/com/fakeplayerproxy/world/entity/LivingEntity.java`
- `plugin/src/main/java/com/fakeplayerproxy/automation/AutomationService.java`
- `plugin/src/main/java/com/fakeplayerproxy/automation/AutomationManager.java`
- `plugin/src/main/java/com/fakeplayerproxy/command/PlayerCommand.java`
- `plugin/src/main/java/com/fakeplayerproxy/FakePlayerProxyPlugin.java`
- `plugin/src/main/resources/com/fakeplayerproxy/i18n/messages.properties`
- `plugin/src/main/resources/com/fakeplayerproxy/i18n/messages_zh_CN.properties`
- `.trellis/spec/backend/velocity-plugin.md`
- `docs/product/operation-guide.md`

The focused test change set is limited to these paths:

- `plugin/src/test/java/com/fakeplayerproxy/world/data/DecoderTest.java`
- `plugin/src/test/java/com/fakeplayerproxy/world/world/WorldTest.java`
- `plugin/src/test/java/com/fakeplayerproxy/world/player/PlayerTest.java`
- `plugin/src/test/java/com/fakeplayerproxy/automation/AutomationServiceTest.java`
- `plugin/src/test/java/com/fakeplayerproxy/automation/AutomationManagerTest.java`
- `plugin/src/test/java/com/fakeplayerproxy/command/PlayerCommandTest.java`

## Command construction

`PlayerCommand.create()` builds each action node once. It attaches each built
node to the self root and to the named target node.

The method uses visible comment sections for lifecycle actions, scheduled
actions, inventory actions, input actions, rotation actions, and mount.

Short executors remain inline. One reused `execute` helper accepts a
`Function<Player, Result<Void, String>>`. It performs these steps:

1. Resolve self or the named target.
2. Return a translated target failure when resolution fails.
3. Submit the supplied operation to the target EventLoop.
4. Execute the supplied operation on that EventLoop.
5. Render a returned translation key to the original source.

The helper returns command success after accepted submission. The queued body
uses the actual synchronous action result to send any translated failure to
the original source. Brigadier cannot wait for that EventLoop result, so its
integer return value reports accepted submission only. It does not claim that
the backend accepted or completed the action.

One `resolveTarget` helper returns `Result<Player, String>`. `execute` and the
short `shadow` executor reuse it. `shadow` keeps its existing asynchronous
disconnect completion because its lifecycle contract differs from ordinary
actions.

Ordinary `AutomationService` command methods require the owner EventLoop. The
command helper supplies that boundary. The current off-loop `runAction` queue
branch is removed so it cannot report success before execution. Scheduled
actions already run on the same EventLoop.

The existing player suggestion helper returns current shadow targets only.
One position suggestion helper serves `mount` and `look at`. There is no
`SuggestionProvider` class.

Brigadier owns numeric bounds:

- Interval ticks have minimum `1`.
- Hotbar slots are `1..9`.
- Drop slots are `0..40`.
- Pitch input is clamped only when applied.

Service methods do not repeat these command bounds.

## Position parsing

Velocity cannot serialize Minecraft's server-only `Vec3Argument`. `mount` and
`look at` use one standard greedy string argument named `position`.

One private resolver reads exactly three parts with Brigadier `StringReader`.
It returns `Result<Vector3d, String>`. The failure value is a translation key.
It adds no coordinate type.

A plain number is absolute. `~` uses the target player's axis value. `~number`
adds to that value. Absolute integer X and Z values add `0.5`. Absolute Y and
decimal X and Z values do not.

Local input requires three `^` parts in left, up, forward order. It cannot mix
with world coordinates. It uses the target feet position, yaw, and pitch. It
uses Minecraft's float trigonometry and negative forward-up cross product.

The resolver rejects incomplete input, trailing input, mixed local input,
invalid doubles, and non-finite output.

The position suggestion helper offers the target position, `~ ~ ~`, and
`^ ^ ^`. Partial local input receives only local remainders. Other partial
input receives only valid world remainders.

## Inventory state

`PlayerInventory` stores command slots `0..40`. It maps menu-zero slots as
follows:

- Inventory `0..8` maps to menu `36..44`.
- Inventory `9..35` maps to menu `9..35`.
- Inventory `36..39` maps to menu `8..5`.
- Inventory `40` maps to menu `45`.

It applies all eight inventory packet forms listed in
`research/implementation-architecture.md`. It ignores command-invisible body
armor and saddle inventory indices.

Before a container-zero throw click, it closes a current nonzero menu once.
`prepareShadow` also closes a current nonzero menu.

Throw clicks use `ContainerActionType.DROP_ITEM`. One-item drops use
`DROP_FROM_SELECTED`. Stack drops use `DROP_SELECTED_STACK`.

The click sends a null carried hash and an empty changed-slot map. The proxy
updates the simple known slot amount after submission. A server slot or content
packet replaces that prediction. It does not increment the server state ID.

Selected scheduled drops use `ServerboundPlayerActionPacket`. `mainhand` uses
the same direct packet. `offhand`, numeric slot, and all use menu-zero clicks.
All visits slots `40..0`.

`swapHands` sends the direct player action and swaps the predicted main-hand
and offhand values. `hotbar` updates the predicted selected slot after packet
submission. A forwarded `ServerboundSetCarriedItemPacket` also updates that
slot before later local prediction.

Login, respawn, configuration entry, and close reset inventory state. Respawn
also closes a current menu before the new player state begins.

## Interaction state

`Player` initializes these attributes from `AttributeType.Builtin.getDef()`:

- `BLOCK_INTERACTION_RANGE`
- `ENTITY_INTERACTION_RANGE`
- `BLOCK_BREAK_SPEED`
- `MINING_EFFICIENCY`
- `SUBMERGED_MINING_SPEED`

Its `updateAttributes` override calls the living implementation and then
updates these fields with the shared attribute calculation.

`Player` also tracks haste, conduit power, mining fatigue, food, invincibility,
infinite materials, flight permission, and flight state from existing protocol
packets.

The cooldown map is keyed by the packet's cooldown group. It decrements once
per client tick. An item's effective group comes from its `USE_COOLDOWN`
component or falls back to its registry key.

The shared sequence increments before each block-use or block-destroy request.
It resets on login, respawn, configuration entry, and close.

## Raycast

`World.raycast` receives the player and the effective active-item attack range.
It reproduces the Vanilla 26.2 selection order:

1. Start at the player's eye.
2. Clip fixed block outline boxes to the maximum range.
3. Use the first block hit as the entity distance ceiling.
4. Search pickable entities in the swept player box inflated by one.
5. Inflate each entity by its pick radius.
6. Select the closest hit.
7. Apply separate block and entity interaction ranges.

An active `ATTACK_RANGE` component uses its survival or creative min and max
range and hitbox margin. Otherwise the normal block and entity attributes
apply.

`BlockHit` stores position, face, hit point, distance, inside-block, and world
border values. `EntityHit` stores entity, hit point, and distance.

## Local use result

`Player.use` returns `Result<Boolean, String>`. The Boolean is the local
Vanilla prediction. Packet failure remains a `Result.Failure`.

The method follows this order:

1. Return `false` while block destruction is active.
2. Return `true` while backend metadata reports an item is active.
3. Try main hand before offhand.
4. Skip a disabled item or an item on cooldown.
5. Send entity interaction for an entity hit.
6. Reject a block hit outside the current world border.
7. Send block use for a block hit with a new sequence.
8. Send generic item use when the modeled target result is not successful.
9. Evaluate the base component behavior.

Entity interaction, block interaction, and item subclass overrides return
local `false`. They still send their matching Vanilla request.

The modeled base item behavior returns `true` for an allowed `CONSUMABLE`,
`BLOCKS_ATTACKS`, or `KINETIC_WEAPON`. Consumable food uses the tracked food
level and `canAlwaysEat`. A positive consume duration records the active hand.

Continuous use sends the first successful item-use request and predicts the
active hand until backend living metadata confirms or clears it. Later action
ticks return `true` while backend active use remains set. They do not send
another item-use request.

Vanilla stops main-hand use when the selected slot changes. The synchronous
serverbound carried-item handler clears matching local active use without
sending a release packet. The next continuous action tick sends one request
for the newly selected valid item.

Backend living flags remain authoritative for use completion and interruption.
When they clear active use, the next continuous action tick can start another
valid item. They update the existing active hand directly. The design adds no
second active-use state.

An attached Vanilla frontend starts local use when it receives the backend
living flag. If its physical use key is up, its next keybind tick sends
`ServerboundPlayerActionPacket(RELEASE_USE_ITEM)`. The synchronous serverbound
handler cancels only that action while the existing `USE` schedule is
continuous. `AutomationService` exposes one EventLoop-owned query for this
schedule ownership. It does not infer ownership from the active hand.

All other player actions and releases outside continuous use remain forwarded.
The plugin does not cancel or rewrite the clientbound living metadata. Slot
changes, hand swaps, backend completion, and backend interruption remain
authoritative. Proxy cleanup sends its release directly to the backend and does
not pass through the frontend event handler.

The scheduler owns Carpet's three-tick use cooldown. This is separate from
Vanilla cooldown groups. A scheduled use with remaining Carpet cooldown
decrements it and returns `true`.

Inactive use clears the Carpet cooldown. It sends `RELEASE_USE_ITEM` only when
active use exists. It then clears active use.

## Local attack result

`Player.attack` returns `Result<Boolean, String>`.

An entity hit returns `true`. Once and interval actions send
`ServerboundAttackPacket` with the entity ID and then send a main-hand swing.
A continuous action sends once when it first acquires an entity target. It does
not repeat for the same target. A target change permits one attack on the new
entity. A miss, block hit, or inactive cleanup clears the retained entity ID.

Every in-game service tick advances entity interpolation before raycast users
run. Non-shadow connections therefore keep current entity bounds without
enabling local player movement.

A block hit outside the current world border returns `false` without a destroy
request. Other block hits follow creative or survival destruction. Spectator
and adventure return `false` without a destroy request.

Creative sends start destroy, applies a five-tick delay, and returns `true`.

Survival tracks block position, face, selected item, progress, and five-tick
post-break delay. A target change aborts the old target. Start and stop consume
the shared sequence. Destroy progress uses the exact formula recorded in the
architecture research. A completed local progress returns `true` without
locally removing the block.

A miss or incomplete state returns `false`. Inactive attack aborts active block
destruction and clears progress.

## Scheduler

`AutomationService` keeps `EnumMap<ScheduledAction, Pair<Integer, Integer>>`.
The enum order is `USE`, `ATTACK`, `JUMP`, `DROP`, `DROP_STACK`, and
`SWAP_HANDS`.

The pair encoding is:

- `0` for one shot.
- `-1` for continuous.
- A positive value for interval.
- Right value for remaining ticks.

Scheduling never executes immediately. One shot runs on the next action tick.
An interval first runs after its full interval. A new schedule replaces the
same action type and runs the old action's inactive cleanup.

Each action tick performs these steps:

1. Advance passive world state for every in-game connection.
2. Decrement each remaining value.
3. Run inactive cleanup for every non-due action.
4. Run inactive cleanup before due one-shot and interval-one actions.
5. Execute due use before attack.
6. Skip attack after successful use.
7. Retry failed use after successful attack.
8. Run the remaining due actions in enum order.
9. Run local player movement only when shadow owns movement.
10. Remove completed one-shot actions and run their cleanup.

`jump` sets input for its due movement step. Ground jump applies effective jump
strength before the existing travel path. Sprint jump adds the Vanilla
horizontal impulse. Airborne one shot sends the fall-flying request when the
player is not climbable. Inactive jump clears the jump flag.

Movement maps forward, backward, left, and right into a Vanilla local input
vector. It cancels opposite directions and applies the input multiplier,
current sneak scaling, and square-input adjustment.

`Player` passes that vector and jump intent to `LivingEntity.travel`. It does
not add horizontal velocity before travel selects an environment.

The air branch applies friction-influenced ground speed or air speed. The water
and lava branches apply their `0.02` base acceleration before movement and
drag. Water also applies sprint slowdown and water movement efficiency. Fluid
jump or descend input enters the water branch.

Existing collision, block friction, speed factor, fluid current, gravity,
climbable, and drag code remains in the existing travel path. The repair adds
no second movement engine.

`stop` runs inactive cleanup for every scheduled action. It clears the map,
movement, sneak, sprint, active use, and active destruction.

## Mount

Bare mount searches entities intersecting the player box inflated by `(3, 1,
3)`. Coordinate mount searches all tracked entities by distance from the
resolved position.

Both forms accept minecart, boat, horse, and camel movement kinds. They exclude
the player and current vehicle. They compare squared center distance and break
ties with lower entity ID.

Coordinate mount checks strict distance from player eye to the selected bounds
against the current entity interaction range. It returns a translated error
without selecting another entity when the check fails.

The request uses main hand, current sneak state, and the closest point on the
selected bounds relative to the entity position. Passenger packets remain
authoritative.

## Direct actions

`kill` first requires the exact current manager entry to be in shadow state. A
non-shadow target returns failure without removal or close. A shadow target is
removed and closed on its EventLoop.

`dismount` sends shift input and delays restoration across a server tick. The
later release sends the current input state. Passenger packets remain
authoritative.

Sneak and sprint intent preserve unrelated flags and remain exclusive. Sprint
does not send a command packet when intent changes. The shadow movement tick
starts sprint only with effective forward movement, valid food or flight state,
and no blocking collision. It stops sprint when those conditions fail. Sprint
packets occur only on actual state changes.

Bare move clears movement, sneak, sprint intent, and actual sprint. Directional
move changes only the applicable movement axis.

Look directions use Carpet values. Up and down preserve yaw. Look numeric sets
yaw and pitch. Turn literals add `-90`, `90`, or `180`. Numeric turn adds both
provided deltas. Applied pitch is clamped.

`shadow` keeps its existing backend and disconnect behavior. It closes a
current nonzero menu before the frontend disconnect.

## Failures and text

Action failures carry translation keys in `Result.Failure`. `PlayerCommand`
creates the translated component. The action layer does not create UI text.

The command needs only these new failure meanings:

- Invalid position.
- No rideable target.
- Rideable outside interaction range.
- Kill target is not in shadow state.

Existing player-required, target-unavailable, and automation-unavailable keys
remain shared.

No success message is added. No configuration switch is added.

## Focused verification

The original command work extends its five existing test classes.

`DecoderTest` reads representative new block, item, and entity fields from the
committed binary.

`WorldTest` covers one occluded entity raycast, one visible entity raycast, one
bare mount selection, and one coordinate range rejection.

`PlayerTest` covers packet construction, inventory correction, one modeled
generic use, one unmodeled use, and one survival destroy lifecycle.

`AutomationServiceTest` covers delayed one shot, first interval timing,
continuous versus interval one, use-before-attack retry, and stop cleanup.

`PlayerCommandTest` covers one self action, one protected target action,
shadow-only player suggestions, one non-shadow target guard, the coordinate
parser, and absent deferred roots.

The repair uses only existing test classes. It adds focused cases for carried
slot use recovery, backend active-use metadata, land versus water input, water
jump, shadow-only command guards, and shadow target suggestions. It adds no
test for every block or fluid state and no new test class.

The frontend release repair extends the existing service test for the
continuous-use ownership edge. Review checks the short plugin event guard
directly. It does not add a plugin test class or duplicate the Velocity patch's
generic event tests.

The task adds no test for source layout, helper count, accessor shape,
translation file presence, every registry entry, or language rules.

## Language-spec compliance repair

`Player.activeAttackRange()` returns one private four-member value type with
named minimum, block, entity, and hitbox-margin accessors. It does not expose an
indexed array and does not add a two- or three-member record in place of the
module's existing pair types.

`AutomationService.runScheduledActions()` contains the short attack result
handling inline. It does not retain a one-use `scheduledAttack()` wrapper.

Retained capability fields remain present. Each necessary IDEA suppression
uses only one smallest-scope `//noinspection` and has an immediately preceding
English comment that states the false positive and the owner. Redundant
`@SuppressWarnings` annotations are removed. Decoder and inventory comments
identify the generator and Decoder validation boundary. Velocity EventLoop and
Brigadier callback comments identify their framework owner.

Trivial field accessors in touched `Decoder`, `LivingEntity`, and
`World.MovingPiston` code use Lombok getters. Fluent APIs retain their existing
method names. This repair does not change action behavior, command grammar,
generated data, or protocol state.

Production Java contains no programmer-assertion validation. Project-owned
constructors, setters, records, and methods use JetBrains `@NotNull` when every
caller guarantees the value. External, protocol, parsed-data, and mutable-state
boundaries retain runtime behavior through explicit `if` guards. Throwing is
allowed, but each exception is caught and handled at its owning parser, event,
packet, callback, or plugin lifecycle boundary. Test code can continue to use
assertion-style validation.

`requireNonNullElse` and `requireNonNullElseGet` remain allowed only as value
normalization with a guaranteed non-null fallback.

The plugin declares JetBrains annotations as `compileOnly`. The annotations do
not enter the plugin JAR. This conversion covers command, automation,
permission, entity, player, and world production sources. It does not add a
second validation layer or change existing failure results.

Valid throws in Decoder, fixed-data records, position parsing, and vehicle
dispatch remain. Their owners contain the failures. `AutomationManager`
contains registration, tick-task setup, and scheduled tick failures.
`PlayerCommand` contains action failures on the selected player's EventLoop.
The plugin's synchronous packet owner contains local state-update failures.
Registration refuses automation when fixed Decoder initialization fails.

The manager rejects a Velocity player that is not the patched
`ConnectedPlayer` implementation before it constructs the internal Player.
Protocol-derived nullable values are ignored at their packet or World entry
boundary. Internal constructors and setters then use project-owned `@NotNull`
contracts without runtime programmer assertions.
