# Research: Vanilla frontend use-release conflict

- Query: Why does `use continuous` still fail to eat after active-use metadata support?
- Scope: internal
- Date: 2026-08-18

## Findings

### Files found

- `plugin/src/main/java/com/fakeplayerproxy/FakePlayerProxyPlugin.java` routes packet events into the tracked player.
- `plugin/src/main/java/com/fakeplayerproxy/world/player/Player.java` predicts use and stores `activeUseHand`.
- `plugin/src/main/java/com/fakeplayerproxy/automation/AutomationService.java` schedules continuous use and owns its cooldown.
- `plugin/src/main/java/com/fakeplayerproxy/world/entity/LivingEntity.java` routes the generated living-flags metadata field.
- `plugin/src/main/java/com/fakeplayerproxy/world/data/EntityTypeData.java` stores the living-flags metadata ID.
- `plugin/src/main/java/com/fakeplayerproxy/world/data/Decoder.java` reads ten generated metadata IDs.
- `plugin/tools/minecraft-data-generator-26.2.patch` reads `DATA_LIVING_ENTITY_FLAGS` from Minecraft 26.2.
- `plugin/tools/GenResources.kt` validates and writes the living-flags metadata ID.
- `plugin/patch/0002-automation-extension.patch` defines synchronous packet events before normal forwarding.
- `plugin/src/test/java/com/fakeplayerproxy/world/player/PlayerTest.java` tests local use state without a Vanilla frontend.
- `plugin/src/test/java/com/fakeplayerproxy/automation/AutomationServiceTest.java` tests scheduler calls without frontend feedback.
- Archived Minecraft 26.2 sources show the client and server active-use rules.
- The pinned Minecraft 26.2 merged JAR supplies the missing `Minecraft` and `MultiPlayerGameMode` client methods.
- `build/tmp/mcprotocollib-sources.jar` supplies the pinned MCProtocolLib packet layouts.

### Confirmed packet event behavior

The Velocity patch decodes a registered packet before normal packet handling. It runs packet listeners synchronously.
It forwards the original packet when no listener cancels or replaces it.
A cancelled serverbound event returns `null` and stops packet processing.
See `plugin/patch/0002-automation-extension.patch:2905` and `plugin/patch/0002-automation-extension.patch:3867`.

`FakePlayerProxyPlugin` currently registers only seven serverbound packet types.
For use state, it registers only `ServerboundSetCarriedItemPacket` at
`plugin/src/main/java/com/fakeplayerproxy/FakePlayerProxyPlugin.java:745`.
It does not register `ServerboundPlayerActionPacket` or `ServerboundUseItemPacket`.
Thus, a real-client `RELEASE_USE_ITEM` packet passes through the proxy unchanged.

The clientbound entity-data listener updates the tracked entity at
`plugin/src/main/java/com/fakeplayerproxy/FakePlayerProxyPlugin.java:607`.
It does not replace the packet.
Velocity therefore also forwards the same metadata packet to the real client.

### Vanilla start and held-use sequence

Vanilla sends one `ServerboundUseItemPacket` when `Minecraft.startUseItem()` reaches generic item use.
`MultiPlayerGameMode.useItem()` sends this packet before local item prediction.
The packet contains hand, sequence, yaw, and pitch in the pinned MCProtocolLib source.

A successful consumable use calls `LivingEntity.startUsingItem()` on the server.
That method sets living flag bit zero and sets bit one for the off hand.
See the archived `LivingEntity.java:3466`.

The server sends dirty tracked data to the player through `ClientboundSetEntityDataPacket`.
See the archived `ServerEntity.java:333`.
The client assigns these values in the archived `ClientPacketListener.java:633`.

The local player treats living flags as authoritative.
Bit zero starts or stops local use, and bit one selects the hand.
See the archived `LocalPlayer.java:579`.
Holding the use key sends no repeated use packet while local use remains active.

### Vanilla release sequence

On each client keybind tick, Vanilla checks local active use first.
If the use key is up, it calls `MultiPlayerGameMode.releaseUsingItem()`.
That method sends one `ServerboundPlayerActionPacket` with `RELEASE_USE_ITEM`.
It uses position zero and direction down, then releases local use.

The server handles this action by calling `ServerPlayer.releaseUsingItem()`.
See the archived `ServerGamePacketListenerImpl.java:1248` and line 1292.
`LivingEntity.releaseUsingItem()` runs release behavior and always calls `stopUsingItem()`.
See the archived `LivingEntity.java:3570`.
The stop clears living flag bit zero and causes another metadata update.

The release packet is valid even when the use key did not start the active use.
The real client only needs active-use metadata and an unpressed use key.

### Vanilla selected-slot sequence

Vanilla sends `ServerboundSetCarriedItemPacket` when its selected hotbar slot differs from the last sent slot.
This is the `MultiPlayerGameMode.ensureHasSentCarriedItem()` behavior in the pinned merged JAR.

The proxy observes that packet before forwarding it.
`Player.selectedSlot()` clears predicted main-hand use when the slot changes.
See `plugin/src/main/java/com/fakeplayerproxy/world/player/Player.java:224`.

The server independently stops main-hand use before it applies the new slot.
See the archived `ServerGamePacketListenerImpl.java:1453`.
This path sends no separate release packet.
The resulting living-flags update later confirms the stopped use.

`SWAP_ITEM_WITH_OFFHAND` also stops server use.
See the archived `ServerGamePacketListenerImpl.java:1271`.
The proxy has no matching serverbound state listener for this action.

### Deterministic conflict in the current code

The proxy sends `ServerboundUseItemPacket` at
`plugin/src/main/java/com/fakeplayerproxy/world/player/Player.java:407`.
For a positive consumable duration, it immediately sets `activeUseHand` at line 420.
Later calls return success without another use packet while this field remains set.

Backend living metadata writes the same field at
`plugin/src/main/java/com/fakeplayerproxy/world/player/Player.java:429`.
The generated metadata path is complete.
The generator reads the accessor at `plugin/tools/minecraft-data-generator-26.2.patch:260`.
The resource writer validates it at `plugin/tools/GenResources.kt:193`.
The decoder passes it into `EntityTypeData` at `plugin/src/main/java/com/fakeplayerproxy/world/data/Decoder.java:383`.
`LivingEntity.applyMetadata()` routes it at
`plugin/src/main/java/com/fakeplayerproxy/world/entity/LivingEntity.java:237`.

This correct metadata path creates a frontend feedback loop:

1. Continuous use sends a use-item packet to the backend.
2. The backend starts food use and sends living flag bit zero.
3. The proxy records active use and forwards the metadata to the real client.
4. The real client enters local use state.
5. If its use key is up, its next keybind tick sends `RELEASE_USE_ITEM`.
6. The proxy has no listener for this packet, so Velocity forwards it unchanged.
7. The backend releases the food and clears living flag bit zero.
8. Continuous use later sends another use request and repeats the loop.

This conflict is deterministic for an attached frontend with an unpressed use key.
It is not a race in metadata tracking.
The frontend cancels each proxy-started use before the food consume duration completes.

The scheduler makes the loop slower but does not stop it.
After a successful call, it sets a three-tick cooldown at
`plugin/src/main/java/com/fakeplayerproxy/automation/AutomationService.java:452`.
Continuous mode becomes due each service tick at line 413.
After metadata clears use, the scheduler waits for this cooldown before another `Player.use()` call.
Food still never gets one uninterrupted consume duration.

### Packet timeline when the player switches to food

| Step | Direction | Packet or action | Result |
| --- | --- | --- | --- |
| 1 | Frontend to backend | `ServerboundSetCarriedItemPacket` | Proxy clears local main-hand use. Backend stops old main-hand use and selects food. |
| 2 | Backend to frontend | `ClientboundSetEntityDataPacket`, bit zero clear | Proxy and real client clear old active use. |
| 3 | Proxy to backend | `ServerboundUseItemPacket` | Backend starts food use. |
| 4 | Backend to frontend | `ClientboundSetEntityDataPacket`, bit zero set | Proxy and real client enter active use. |
| 5 | Frontend to backend | `ServerboundPlayerActionPacket(RELEASE_USE_ITEM)` | Current proxy forwards it. Backend cancels food use. |
| 6 | Backend to frontend | `ClientboundSetEntityDataPacket`, bit zero clear | Proxy and real client clear active use. |
| 7 | Proxy to backend | A later `ServerboundUseItemPacket` | The same cycle starts again. |

If the real client holds its use key, step 5 does not occur.
This explains why the failure depends on frontend key state.
Shadow mode has no attached frontend, so it cannot produce this release packet.

### Server-side cancellation conditions

The checked Minecraft 26.2 server code stops or releases use in these relevant cases:

- `RELEASE_USE_ITEM` calls `releaseUsingItem()`.
- A different selected slot stops main-hand use before the slot changes.
- `SWAP_ITEM_WITH_OFFHAND` stops use after the swap.
- A held item identity change during the living tick stops use.
- Item completion stops use after `finishUsingItem()`.
- Other gameplay events can call `stopUsingItem()`, such as death or state changes.

The proxy should keep backend metadata authoritative for all server-originated stops.
It should not hide the metadata-clear transition from its local model.

### Minimum repair boundary

The smallest repair uses the existing serverbound cancellation API.
It does not change Minecraft data, the binary, `Player.use()`, or living metadata routing.

Add one synchronous `ServerboundPlayerActionPacket` listener in `FakePlayerProxyPlugin`.
Cancel only `RELEASE_USE_ITEM` while the service has a continuous `USE` schedule.
Allow every other player action and every release outside continuous use.

Expose one narrow EventLoop-owned query from `AutomationService` for continuous-use ownership.
The query can test whether `scheduledActions.get(ScheduledAction.USE)` has the continuous period value `-1`.
Do not infer ownership from `activeUseHand` alone.
That field can also represent real-client use and backend state.

This boundary preserves these required transitions:

- A real-client slot change still reaches the backend and stops old main-hand use.
- A real-client hand swap still reaches the backend and stops use.
- Backend metadata can still stop use after completion or interruption.
- `stop` and schedule replacement still send the proxy-owned release directly to the backend.
- One-shot and interval use keep their existing inactive cleanup.
- Shadow behavior does not change because no frontend release exists.

The proxy sends its cleanup packet directly through the backend connection.
It does not enter the frontend decoder or the new cancellation listener.
Therefore, the listener cannot cancel `Player.inactiveUse()` at
`plugin/src/main/java/com/fakeplayerproxy/world/player/Player.java:435`.

Do not cancel every frontend release whenever `activeUseHand` is non-null.
That would suppress normal release behavior outside continuous automation.
Do not remove living-use metadata from the forwarded clientbound packet.
That would hide authoritative player state and require packet-list mutation.

### Focused verification gap

Current `PlayerTest` cases call `Player.use()` and `applyMetadata()` directly.
See `plugin/src/test/java/com/fakeplayerproxy/world/player/PlayerTest.java:119` and line 137.
They do not simulate a real client that sends a release after metadata starts use.

Current `AutomationServiceTest` verifies that held continuous use avoids repeated service calls.
See `plugin/src/test/java/com/fakeplayerproxy/automation/AutomationServiceTest.java:174`.
It mocks `Player` and has no frontend packet event.

A focused repair test must cover the missing boundary:

- Continuous `USE` makes the service ownership query true.
- Replacement, `stop`, configuration reset, and close make it false.
- The plugin cancels only frontend `RELEASE_USE_ITEM` while that query is true.
- It does not cancel digging, drop, swap, or release outside continuous use.

The existing Velocity packet-event tests already own generic cancellation behavior.
The repair does not require a Velocity patch change.

## External references

- Minecraft artifact: `com.mojang:minecraft:26.2`, official names, local merged JAR.
- MCProtocolLib artifact: `org.geysermc.mcprotocollib:protocol:26.2-20260809.160751-16`.
- Protocol target: Minecraft Java 26.2, protocol 776.
- Minecraft client evidence: `Minecraft.startUseItem()`, `Minecraft.handleKeybinds()`, and `MultiPlayerGameMode.releaseUsingItem()` from the pinned merged JAR.
- Minecraft server evidence: archived official-name 26.2 sources under the prior automation task research evidence.

## Related specs

- `.trellis/spec/backend/velocity-plugin.md` pins Minecraft 26.2, MCProtocolLib build 16, and EventLoop ownership.
- `.trellis/spec/language/java.md` applies to any later Java repair.
- `.trellis/tasks/08-17-complete-carpet-player-command-system/prd.md` requires held continuous use and slot-change recovery.
- `.trellis/tasks/08-17-complete-carpet-player-command-system/design.md` makes backend living flags authoritative.
- `.trellis/tasks/08-17-complete-carpet-player-command-system/implement.md` limits the repair to existing action infrastructure.

## Caveats / Not Found

- The archived Vanilla evidence does not include `Minecraft.java` or `MultiPlayerGameMode.java` source files.
- Their findings come from `javap -c -p` against the pinned local Minecraft 26.2 merged JAR.
- This research did not run a live client capture.
- This research did not change production code or tests.
- Role isolation prohibited reading `implement.jsonl` and `check.jsonl`.
- The configured `ace-tool` and `fast-context` MCP tools were unavailable in this agent session.
