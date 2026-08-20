# Automation service reconnect continuity

Date: 2026-08-19

## Result

Auto-reconnect must preserve the exact plugin `Player`, `World`, and
`AutomationService` objects.

The old research recommendation to create a new service was incorrect. A new
service would lose scheduled actions, input intent, cookies, Shadow ownership,
and the existing tick task.

Object continuity does not make old backend state valid. The service must clear
all backend-derived state and rebuild it from the new CONFIG and PLAY packets.

## Existing object chain

`AutomationManager.players` maps the exact Velocity `Player` to one plugin
`Player`. The plugin `Player` owns one final `World` and one final
`AutomationService`.

Shadow teardown keeps the same `ConnectedPlayer` object. A reconnect can attach
a new `VelocityServerConnection` to that object.

`Player.backendConnection()` does not store one backend. It reads
`ConnectedPlayer.getConnectionInFlightOrConnectedServer()` on each call. The
same plugin `Player` therefore sees the reconnected backend without a new
plugin object.

The reconnected backend uses the same closed frontend EventLoop. Velocity also
uses this EventLoop for `VelocityServerConnection.connect()`. Packet updates,
retry transitions, commands, and automation remain serialized on one owner.

`ClientboundPacketEvent` resolves the `ConnectedPlayer` through the backend
connection association. `FakePlayerProxyPlugin.withPlayer()` then gets the
existing plugin `Player` from `AutomationManager`. New backend packets can
therefore update the existing `World` and service.

## Current destructive paths

`AutomationManager.tick()` removes the player when
`Player.backendConnection()` becomes null. It then calls
`AutomationService.close()`.

`close()` clears Shadow, scheduled actions, input, inventory, interaction state,
and the tick task. This path must not run for a retryable backend loss.

`AutomationService.startConfiguration()` clears scheduled actions and calls
`Player.resetForConfiguration()`. That player reset clears movement input and
all local backend state.

`AutomationService.enterGame()` also clears scheduled actions. A reconnect
backend sends a new Login packet, so the current event listener would erase the
retained action plan.

`AutomationManager.isActive()` requires an active backend. Target lookup and
suggestions therefore hide a reconnecting Shadow. This also makes
`/player kill` unavailable when the backend is absent.

## State ownership

### Preserve without change

- the exact Velocity `ConnectedPlayer`
- the exact plugin `Player`, `World`, and `AutomationService` objects
- the scheduled tick task
- the Shadow state
- the `autoReconnect` boolean and access token
- the cookie map
- the scheduled action type, period, and remaining delay
- movement, jump, sneak, and sprint intent

Scheduled action delays do not advance while the backend is unavailable or not
ready. The reconnect wait does not consume interval ticks.

The service must also keep the exact remaining delay for each action. It must
not recreate the action map from action types and periods.

### Reset before reconnect CONFIG

- `inGame`, `initialPosition`, and `playerLoaded`
- pending configuration-switch and configuration-finish flags
- offered and selected Known Packs
- pending chat signatures
- continuous-use cooldown
- client tick accumulator
- active use hand and active block destruction
- the continuous attack target
- delayed input release
- actual sprint state and outbound movement baselines

These fields describe one backend protocol session. They cannot cross into the
reconnected session.

### Rebuild from the reconnected backend

- registry and tag data
- dimension and biome data
- chunks, blocks, block entities, and moving pistons
- entity IDs, entities, passengers, and vehicle links
- world border and tick rate
- player entity ID, position, rotation, velocity, and collision flags
- inventory, selected slot, open menu, and cursor item
- health, food, game mode, abilities, attributes, effects, and cooldowns
- enabled features and interaction ranges

`World.clear()` already removes the old registry, tag, chunk, entity, border,
and tick state. `Player.initializeGame()` and existing packet listeners already
rebuild the PLAY state.

The reconnect path needs a reset operation that keeps action and input intent.
It must not call the current close path.

## State transition contract

| Condition | Backend | Automation | Local state |
| --- | --- | --- | --- |
| Ready PLAY | Active and ready | Runs | Authoritative |
| Backend loss | Closed | Freezes immediately | Old backend state becomes invalid |
| Retry wait | Absent | Keeps actions and input unchanged | Backend state stays clear |
| Login | In flight | Stays frozen | Session fields reset |
| CONFIG | Active | Stays frozen | Registry and feature state rebuilds |
| PLAY synchronization | Active | Stays frozen | World and player state rebuilds |
| Ready PLAY | Active and ready | Resumes on the next tick | New backend state is authoritative |
| Terminal close | Closed | Stops and clears | All retained state clears |

The service needs no reconnect phase. Existing state gives each required
condition:

- A missing backend and a null reconnect future mean retry wait.
- A non-null reconnect future means one backend reconnect is in progress.
- Existing `inGame` and `playerLoaded` fields identify usable PLAY state.
- Automation runs only with an active backend, `inGame`, and `playerLoaded`.

The reconnect future stays assigned through CONFIG and PLAY synchronization.
It marks the paths that must preserve scheduled actions and input intent.

The attempt number also identifies the active attempt. An old callback must
match this number before it changes the retained service. This check stops a
late failed attempt from closing or replacing a newer backend.

## Required lifecycle

### 1. Backend loss

The synchronous backend packet event sees a Disconnect packet before the
backend handler closes the channel. A terminal classification immediately uses
the existing terminal close path.

A nonterminal Disconnect packet needs no stored classification. Its later
channel close follows the same retry path as a transport failure.

For a retryable loss, the manager keeps its exact map entry and tick task. The
service enters reconnect wait and clears only backend-derived state.

The existing tick task must remain active. Its tick handles retry eligibility
while automation is frozen. This avoids a second scheduler owner.

For a terminal result, the existing close path disables auto-reconnect, clears
the token, removes the map entry, and closes the service.

### 2. Retry wait

The manager does not call the normal automation tick without a backend. It
keeps the retained service on the owner EventLoop.

Scheduled actions and input intent remain frozen. New backend actions return
the existing automation-unavailable result.

`runAction()` must require ready PLAY. Its current `inGame` and backend checks
are not sufficient during PLAY synchronization.

`/player kill` remains available. Target lookup must include an open
reconnecting Shadow even when it has no backend.

The target argument appears before the action in the Brigadier grammar. One
target suggestion list therefore serves `kill` and every other action. The
reconnecting player must remain in that list. Other actions can remain visible,
but they fail until PLAY is ready. Duplicating the grammar only to hide them
would add redundant command trees.

### 3. New backend Login

The patched reconnect operation atomically detaches the expected dead backend
and installs one new `VelocityServerConnection` as the in-flight
connection.

The new backend carries `logoutCancelled=true` before it receives packets.
This keeps the connection active while the frontend channel remains closed. It
also gives the backend priority list its low-priority signal.

The new backend performs one fresh session-server join with the retained token.
Only one connection attempt can wait or run for the player.

### 4. Replacement CONFIG

The same packet listeners update the retained service and `World`.

The CONFIG start path must not call the current `startConfiguration()` method
unchanged. That method clears `scheduledActions` and `Player.inputState`. The
reconnect path must reset the backend session while it keeps those two values.

Known Packs use the existing Shadow response. Cookie requests use the retained
cookie map. KeepAlive and Ping packets use existing direct backend responses.

Registry, tag, feature, and configuration packets rebuild the cleared local
state. The service keeps scheduled actions frozen throughout CONFIG.

The current Velocity resource-pack handler can acknowledge an exact pack that
the retained client session already applied. It cannot apply a new pack after
the frontend closes. A new optional pack can receive `DECLINED` without waiting
for the closed frontend.

A new required pack needs client work and cannot succeed headlessly. It disables
auto-reconnect and clears the token.

Every Code of Conduct request disables auto-reconnect and clears the token. The
proxy does not track or reuse a user's earlier acceptance.

A backend Transfer packet targets the closed frontend and does not create a
proxy-side server switch. It disables auto-reconnect and clears the token.

Third-party CONFIG plugin protocols are outside this task.

### 5. Replacement PLAY synchronization

The Login packet must reset and initialize the retained plugin `Player` without
clearing the saved input intent or scheduled actions.

The PLAY entry path must not call the current `enterGame()` method unchanged.
That method clears `scheduledActions`. The reconnect future marks this path, so
the method can preserve actions without a new state field.

The existing packet listeners then install the authoritative position,
inventory, entities, chunks, attributes, and other PLAY state.

The service must not run scheduled actions after the Login packet alone. It
first waits for the initial position and current chunk.

The service then sends `ServerboundPlayerLoadedPacket`. It marks the new PLAY
state ready after that write. The next service tick reapplies movement input and
starts the frozen action schedule.

The current `tick()` order is not sufficient. It calls `passiveTick()` and
`runScheduledActions()` before it checks `playerLoaded`. The reconnect path must
check ready PLAY before both calls. Otherwise an action can run or lose one
remaining tick while chunks and inventory are incomplete.

On the first ready tick, the service uses the preserved input intent. It sends
new movement and sprint state only after the reconnected backend is ready. A
continuous use action sends a new use start packet. A continuous attack action
performs a new raycast against the rebuilt world.

The service does not preserve entity IDs, a use hand, a destroy position, or an
actual sprint edge. These values belong to the old backend. The saved action
intent recreates them from new backend state.

This readiness point also resets the retry delay sequence. A socket connect,
Login Success, or Join Game without a usable local PLAY state does not reset the
sequence.

### 6. Later cleanup

`/player kill`, a fresh same-UUID login, a terminal kick, an authlib credential
rejection, plugin shutdown, or controller failure uses one terminal close path.

That path clears the next retry due time and closes a waiting or active
reconnect channel. It clears the token, removes the exact map entry, and
closes the retained service.

`/fpp auto-reconnect off` requires the active frontend and backend. It sets
`autoReconnect=false` and clears the token. It cannot run during Shadow
reconnect.

A fresh same-UUID real login keeps the existing replacement rule. The new
frontend player wins. It does not inherit the old service, actions, cookies, or
token.

## Packet update chain

1. The backend Disconnect event immediately handles a terminal classification.
2. A retryable backend close changes the retained service to retry wait.
3. The manager keeps the exact map entry and tick task.
4. The retained tick makes one reconnect attempt eligible.
5. The common backend priority list releases the reconnect channel.
6. The new backend performs a new session join with the retained token.
7. The new backend attaches to the same Velocity `ConnectedPlayer`.
8. CONFIG packet events clear and rebuild backend-session state.
9. The Login packet initializes the same plugin `Player` and `World`.
10. PLAY packet events rebuild position, chunks, inventory, and entities.
11. The service sends `ServerboundPlayerLoadedPacket` after position and chunk
    readiness.
12. The next tick resumes the saved input and scheduled actions.

Every asynchronous callback must resolve the exact retained player and attempt
number. A callback from an old backend must not update the reconnected session.

Each backend packet event must also match its source connection against the
current reconnected backend. Resolving only the retained player is not
enough because an old and a new backend can refer to the same player.

## Required code boundaries

- `AutomationManager` must keep reconnecting entries instead of closing them on
  the first null backend.
- `AutomationService` must own the reconnect future and freeze action timing
  until existing readiness fields allow execution.
- `Player` needs a backend-session reset that preserves input intent.
- `AutomationManager` must close terminal backend Disconnect events before the
  backend channel closes.
- `PlayerCommand` lookup and suggestions must retain reconnecting Shadow
  targets so `kill` remains reachable.
- The Velocity patch must atomically replace the dead backend on the same
  `ConnectedPlayer`.
- The Velocity CONFIG patch must avoid waiting for a closed frontend when a
  response can be produced or rejected locally.

## Repository evidence

- `plugin/src/main/java/com/fakeplayerproxy/automation/AutomationManager.java:202`
  currently closes the service after backend loss.
- `plugin/src/main/java/com/fakeplayerproxy/automation/AutomationService.java:46`
  owns the scheduled actions.
- `plugin/src/main/java/com/fakeplayerproxy/automation/AutomationService.java:518`
  defines the destructive close path.
- `plugin/src/main/java/com/fakeplayerproxy/world/player/Player.java:175`
  resolves the backend dynamically.
- `plugin/src/main/java/com/fakeplayerproxy/world/player/Player.java:578`
  initializes a new PLAY session.
- `plugin/src/main/java/com/fakeplayerproxy/world/world/World.java:779`
  clears backend-derived world state.
- `plugin/src/main/java/com/fakeplayerproxy/FakePlayerProxyPlugin.java:321`
  updates the retained player from the Login packet.
- `plugin/patch/0002-automation-extension.patch:3869` routes backend packets to
  the exact Velocity player.
- `plugin/build/server/source/proxy/src/main/java/com/velocitypowered/proxy/connection/backend/TransitionSessionHandler.java:142`
  installs the reconnected backend on the retained `ConnectedPlayer`.
