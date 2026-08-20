# Research: Velocity shadow reconnect lifecycle

- Query: Trace the current plugin and pinned Velocity lifecycle from Shadow takeover through backend disconnect; identify surviving and destroyed state, how a fresh backend login could be initiated for the same player without a frontend, the Velocity API versus patch surface, current relay-secret/auth ownership, and EventLoop constraints.
- Scope: internal
- Date: 2026-08-19

## Findings

### Files found

- `plugin/src/main/java/com/fakeplayerproxy/FakePlayerProxyPlugin.java` - registers automation at native PostLogin, cancels logout for Shadow, and consumes synchronous decoded packet events.
- `plugin/src/main/java/com/fakeplayerproxy/automation/AutomationManager.java` - owns the exact-Velocity-player map, tick scheduling, backend-loss cleanup, and same-UUID fresh-login replacement.
- `plugin/src/main/java/com/fakeplayerproxy/automation/AutomationService.java` - owns Shadow state, scheduled actions, configuration replies, keepalive replies, and close behavior.
- `plugin/src/main/java/com/fakeplayerproxy/world/player/Player.java` - anchors plugin state to one `ConnectedPlayer`, derives frontend/backend connections, and owns the per-player World and AutomationService.
- `plugin/patch/0001-login-relay.patch` - owns the one-time frontend/target online-mode authentication relay and its transient AES-secret lifecycle.
- `plugin/patch/0002-automation-extension.patch` - adds cancellable logout, backend continuation after frontend close, direct backend packet send/routing, synchronous packet events, and closed-frontend configuration guards.
- `plugin/patch/velocity-base.properties` - pins upstream Velocity commit `843a47e2a38325309cd66133149fc9a984f76bb8`.
- `plugin/build/server/source/proxy/src/main/java/com/velocitypowered/proxy/connection/**` - generated pinned upstream source checkout, useful for exact unchanged Velocity behavior; patch files remain the canonical project-owned changes.
- `plugin/src/test/java/com/fakeplayerproxy/automation/AutomationManagerTest.java` - proves the supported same-UUID path is a fresh frontend login with a new ConnectedPlayer/service, not reuse of the old service.
- `.trellis/spec/backend/velocity-plugin.md` - current runtime contract explicitly says Shadow does not reconnect or copy secrets and that a fresh login creates fresh automation state.

### Current lifecycle

#### 1. Initial authenticated connection and registration

1. The initial relay creates a provisional `ConnectedPlayer`, associates it with the frontend connection, and starts the configured backend before normal frontend authentication (`plugin/patch/0001-login-relay.patch:2493-2529`, `:2615-2625`).
2. The target Server Hello is accepted only while the frontend's active handler is `InitialLoginSessionHandler`; any later ordinary backend connection retains stock Velocity's `Backend server is online-mode!` failure (`plugin/patch/0001-login-relay.patch:117-148`). This is the central reason a normal server-switch API cannot authenticate a fresh online-mode backend after the frontend has gone.
3. Target Login Success is paused until the frontend owns the target-authenticated profile and PostLogin/client settings finish (`plugin/patch/0001-login-relay.patch:224-309`, `:491-561`).
4. The plugin registers only at `PostLoginEvent`, and registration is an awaited continuation (`plugin/src/main/java/com/fakeplayerproxy/FakePlayerProxyPlugin.java:174-185`). `AutomationManager.register` creates a new plugin `Player`, moves work to that player's frontend EventLoop, requires an active original backend, schedules a 50 ms tick on that loop, then inserts the exact Velocity `Player` key (`plugin/src/main/java/com/fakeplayerproxy/automation/AutomationManager.java:30-67`, `:106-154`).
5. The plugin `Player` owns a new `World` and `AutomationService`, but retains the exact Velocity player as its connection identity (`plugin/src/main/java/com/fakeplayerproxy/world/player/Player.java:154-166`). It derives the backend dynamically from `ConnectedPlayer.getConnectionInFlightOrConnectedServer()` and considers it available only while its channel is active (`plugin/src/main/java/com/fakeplayerproxy/world/player/Player.java:175-186`).

#### 2. Shadow takeover and frontend close

1. `AutomationService.shadow()` marshals to `owner.eventLoop()`, checks the active backend and world readiness, sets `shadow = true`, clears/normalizes client input through `prepareShadow`, and calls the normal Velocity `Player.disconnect` API (`plugin/src/main/java/com/fakeplayerproxy/automation/AutomationService.java:223-250`; `plugin/src/main/java/com/fakeplayerproxy/world/player/Player.java:501-507`).
2. When `DisconnectEvent` fires, the plugin looks up the exact automation player and executes cancellation on that same EventLoop; only the exact service currently in Shadow cancels (`plugin/src/main/java/com/fakeplayerproxy/FakePlayerProxyPlugin.java:187-205`).
3. The patch's `DisconnectEvent.cancel()` is only a monotonic boolean meaning "cancel actual logout while allowing frontend close" (`plugin/patch/0002-automation-extension.patch:174-200`).
4. Patched `ConnectedPlayer.teardown()` always closes an in-flight connection, pauses the current backend's reads, fires DisconnectEvent, and then returns to the frontend EventLoop. It **always unregisters the ConnectedPlayer from Velocity's global player registry**, even when cancelled. Cancellation instead marks the current `VelocityServerConnection.logoutCancelled = true` and resumes backend reads; non-cancellation closes the backend (`plugin/patch/0002-automation-extension.patch:2358-2413`).
5. `VelocityServerConnection.isActive()` is patched to accept either an active frontend player or `logoutCancelled`, which keeps the existing backend play handler from treating the connection as obsolete (`plugin/patch/0002-automation-extension.patch:1981-1999`). This flag belongs to the existing server connection, not the ConnectedPlayer and not future connections.

#### 3. What survives while the original backend is alive

- The plugin `AutomationManager.players` entry, plugin `Player`, `World`, inventory/entity/calculation state, `AutomationService`, Shadow flag, cookies, pending chat acknowledgements, action schedule, and tick task all survive because the exact `ConnectedPlayer` remains strongly reachable from the map (`plugin/src/main/java/com/fakeplayerproxy/automation/AutomationManager.java:23-24`; `plugin/src/main/java/com/fakeplayerproxy/automation/AutomationService.java:46-65`).
- The `ConnectedPlayer` object, its profile/settings/active frontend session handler object, `connectedServer` reference, and frontend `MinecraftConnection` object survive as Java objects. The frontend channel itself is closed and the player is absent from `ProxyServer#getPlayer(s)` because teardown unregistered it (`plugin/patch/0002-automation-extension.patch:2395-2405`).
- The existing `VelocityServerConnection`, backend `MinecraftConnection`, backend play/config handler, encrypted Netty pipeline, pending-ping map, and backend membership survive until that backend closes. The `logoutCancelled` bit is what permits backend `beforeHandle()` to remain active (`plugin/patch/0002-automation-extension.patch:1820-1829`, `:1987-1999`).
- The periodic automation task remains scheduled on the **closed frontend channel's EventLoop**, not the backend EventLoop (`plugin/src/main/java/com/fakeplayerproxy/automation/AutomationManager.java:121-125`; `plugin/src/main/java/com/fakeplayerproxy/world/player/Player.java:168-190`). Closing a channel does not itself destroy its group EventLoop, so this currently works, but the lifetime is borrowed from Velocity/Netty rather than owned by the plugin.
- Clientbound packet events are decoded and synchronously dispatched on the connection pipeline before normal forwarding; the plugin's `@Subscribe(async = false)` handlers update local state even though later writes to the closed frontend go nowhere (`plugin/patch/0002-automation-extension.patch:2868-2933`, `:3869-3904`; `plugin/src/main/java/com/fakeplayerproxy/FakePlayerProxyPlugin.java:251-260`).
- Shadow keepalive/config/action responses are sent directly to the backend. `AutomationService.respond` explicitly re-dispatches onto the backend EventLoop and rechecks Shadow/closed/channel-active state (`plugin/src/main/java/com/fakeplayerproxy/automation/AutomationService.java:386-398`). `MinecraftConnection.sendPacket` similarly marshals onto its own EventLoop; non-bypass routing invokes the retained frontend session handler as a logical protocol handler, not as a network write (`plugin/patch/0002-automation-extension.patch:1183-1235`).
- Shadow ticks continue passive world state, scheduled actions, local movement, and Client Tick End while the service and backend remain active (`plugin/src/main/java/com/fakeplayerproxy/automation/AutomationService.java:346-384`).

#### 4. Backend disconnect and cleanup

1. Netty channel inactivity invokes the active backend handler's `disconnected()` (`plugin/build/server/source/proxy/src/main/java/com/velocitypowered/proxy/connection/MinecraftConnection.java:128-139`). The unchanged pinned `BackendPlaySessionHandler.disconnected()` removes the ConnectedPlayer from that RegisteredServer; for an unexpected close it attempts failover or disconnects the already-closed frontend, but does not clear `ConnectedPlayer.connectedServer` (`plugin/build/server/source/proxy/src/main/java/com/velocitypowered/proxy/connection/backend/BackendPlaySessionHandler.java:495-508`).
2. On the next 50 ms automation tick, `Player.backendConnection()` returns null because the backend channel is inactive. The manager removes the exact map entry and closes the service (`plugin/src/main/java/com/fakeplayerproxy/automation/AutomationManager.java:202-218`). A tick exception follows the same remove/service-close/backend-close path (`:219-237`).
3. `AutomationService.close()` clears Shadow, scheduled actions/cooldown, resets local input/inventory/interaction state, and cancels the tick task (`plugin/src/main/java/com/fakeplayerproxy/automation/AutomationService.java:518-531`; `plugin/src/main/java/com/fakeplayerproxy/world/player/Player.java:625-629`). It does **not** explicitly clear the `World`. The plugin Player/World become unreachable from AutomationManager, but may remain reachable temporarily through stale Velocity connection/handler references until GC.
4. The stale `ConnectedPlayer.connectedServer` reference is not reset by backend disconnect. The generated pinned source shows `VelocityServerConnection.disconnect()` nulls its own `connection`, but it does not update the player's `connectedServer` (`plugin/build/server/source/proxy/src/main/java/com/velocitypowered/proxy/connection/backend/VelocityServerConnection.java:243-251`). Therefore the old ConnectedPlayer is not immediately reusable through the public same-target request API.
5. The currently supported recovery path is a genuinely fresh frontend login. Registration scans map entries by UUID, removes an older exact-player entry, closes its service/backend, waits for backend close, then installs a newly constructed Player/service (`plugin/src/main/java/com/fakeplayerproxy/automation/AutomationManager.java:69-125`). The regression calls this `freshLoginClosesOnlyTheOldService` and asserts distinct services (`plugin/src/test/java/com/fakeplayerproxy/automation/AutomationManagerTest.java:44-64`). No world, input, schedule, cookie, or session state is copied.

### Relay secret and authentication ownership

- The Mod's initial login uses the target public key/challenge carried through a decorated proxy SPKI. Public routing metadata is owned transiently by `LoginSessionHandler.TargetHello`; it is cleared after response construction/failure (`plugin/patch/0001-login-relay.patch:104-108`, `:573-611`, `:3971-3984`).
- `InitialLoginSessionHandler` decrypts the client-generated 16-byte AES secret with Velocity's private key, enables frontend encryption, clones the secret once for the backend EventLoop, and zeroes the frontend copy in `finally` (`plugin/patch/0001-login-relay.patch:2380-2405`, `:2436-2485`).
- The backend `LoginSessionHandler` uses that temporary copy to encrypt a standard response for the target, waits for the plaintext write, enables independent backend AES, and zeroes the copy on every completion path (`plugin/patch/0001-login-relay.patch:358-488`). Only cipher state remains in the frontend/backend Netty pipelines. There is no retained raw `K`, target challenge, client access token, refresh token, or reusable join proof in plugin state.
- The relay is intentionally initial-login-only: its online-mode branch requires `InitialLoginSessionHandler` (`plugin/patch/0001-login-relay.patch:117-148`). A later `ConnectionRequestBuilder` creates an ordinary `LoginSessionHandler`, whose online-mode Server Hello branch throws. This is a deliberate ownership boundary, not merely a missing plugin call.
- Reusing the old AES key would not by itself authorize a new online-mode backend login. A fresh Server Hello supplies a new target key/challenge and requires a new session-server join authorization bound to that login digest. The current proxy owns neither the account bearer credential nor a delegated reconnect proof. The credential/proof feasibility is being researched separately under this task; this lifecycle report establishes that no such material exists in current runtime state.

### Can existing Velocity APIs initiate a fresh connection?

The socket/request machinery exists, but it is insufficient end to end.

1. `Player.createConnectionRequest(RegisteredServer).connect()` is the public API entry (`plugin/build/server/source/proxy/src/main/java/com/velocitypowered/proxy/connection/client/ConnectedPlayer.java:592-600`). Its initial checks and completion stages execute on the retained frontend EventLoop (`:1442-1496`). `VelocityServerConnection.connect()` also bootstraps the new backend on `proxyPlayer.getConnection().eventLoop()` and installs `LoginSessionHandler` (`plugin/build/server/source/proxy/src/main/java/com/velocitypowered/proxy/connection/backend/VelocityServerConnection.java:93-131`). Thus a closed frontend channel does not prevent socket creation as long as the shared EventLoop is still alive.
2. The API first rejects the same target as `ALREADY_CONNECTED` whenever `connectedServer` is non-null and names the target; it does not test whether that backend channel is dead (`plugin/build/server/source/proxy/src/main/java/com/velocitypowered/proxy/connection/client/ConnectedPlayer.java:1442-1453`; the same unchanged check appears at `plugin/patch/0002-automation-extension.patch:2842-2851`). The stale pointer must be detached before an automatic same-target attempt.
3. Even after detaching it, a newly created `VelocityServerConnection` starts with `logoutCancelled = false`. Its `isActive()` therefore rejects the inactive frontend player, so continuation ownership must be propagated to the new server connection before backend packets reach `beforeHandle()` (`plugin/patch/0002-automation-extension.patch:1824-1829`, `:1987-1999`).
4. Online-mode authentication then fails at the initial-login-only guard described above. There is no public Velocity API that supplies a Mojang session join or injects a completed authenticated backend login for an offline frontend.
5. Later configuration/play code is still frontend-coupled. Existing patches already guard several configuration writes when the frontend channel is inactive and allow configuration switching when the target is `logoutCancelled` (`plugin/patch/0002-automation-extension.patch:2111-2126`, `:2744-2773`). `BackendPlaySessionHandler` still captures the retained `ClientPlaySessionHandler` and requires that handler at construction (`plugin/patch/0002-automation-extension.patch:1459-1469`). These retained handlers can be used as logical routing owners, but every reconnect path must audit remaining frontend writes/events rather than assume public connection request completion implies headless safety.

### Minimum API versus patch surface for same-ConnectedPlayer reconnect

#### Reusable existing APIs/code

- `Player.createConnectionRequest(target).connect()` can own server selection, `ServerPreConnectEvent`, result handling, backend bootstrap, and most ordinary Velocity state transitions after lifecycle blockers are fixed.
- The plugin's synchronous `ClientboundPacketEvent` listeners and `MinecraftConnection.sendPacket` can continue to own local state reconstruction and headless replies for CONFIG/PLAY.
- `AutomationManager` must keep the exact plugin `Player`, `World`, and `AutomationService`. The service freezes action intent during reconnect. It clears and rebuilds backend-derived state before actions resume.

#### Required Velocity patch points

1. **Atomic dead-backend reconnect lifecycle on the owning EventLoop.** Add a narrow patched operation on `ConnectedPlayer` that verifies the expected old `VelocityServerConnection`, clears `connectedServer` and in-flight state, and creates the new backend without racing a fresh frontend login. Calling public `createConnectionRequest` alone cannot pass the stale same-target check.
2. **Continuation ownership for the new backend.** Propagate the headless Shadow continuation state from the old connection to the new `VelocityServerConnection` before `LoginSessionHandler.beforeHandle` observes `proxyPlayer.isActive() == false`. Do not treat `logoutCancelled` as a general player flag. It belongs to one server connection.
3. **A new authenticated backend-login branch.** Patch `LoginSessionHandler.handle(EncryptionRequestPacket)` with a narrowly scoped reconnect authorization provider/proof. It must generate a fresh AES key, answer the new target challenge, perform or consume a valid session-server join authorization, enable backend encryption after the plaintext response write, and erase transient secret bytes. The existing initial relay cannot be called because its frontend `InitialLoginSessionHandler`, active client response, and one-shot target Hello ownership no longer exist.
4. **Headless CONFIG/PLAY audit.** Ensure Login Success, ConfigSessionHandler, connection-result completion, `setConnectedServer`, ServerPostConnect/transfer events, and BackendPlaySessionHandler activation do not require a writable frontend. Existing closed-frontend guards cover configuration switching on the retained backend, not demonstrably every fresh-connect branch.
5. **Backend disconnect handoff.** Reconnect must be initiated before current `AutomationManager.tick` removes/closes the only service, or the manager must replace that entry with an explicit reconnecting state. Today backend loss unconditionally removes the map entry on the next tick. Retry/credential expiry must eventually run the existing close path.

#### Plugin changes required after the host patch exists

- Store reconnect authorization on the exact retained player lifecycle. Clear it through the same terminal path that closes the retained service.
- Make `/player kill` disable auto-reconnect before it closes the Shadow backend. Clear the token and cancel all retry work before the close event can request another connection.
- Classify the backend disconnect before the handler discards useful context. Transition the exact player entry from Shadow-active to reconnecting on its owning EventLoop. Suppress ordinary removal while one attempt is active. Reuse the same Player/service after the new backend reaches ready PLAY.
- Keep commands/lookups explicit about reconnecting versus active. Current `isActive` means service open plus active backend only (`plugin/src/main/java/com/fakeplayerproxy/automation/AutomationManager.java:198-200`).
- Preserve the current same-UUID fresh-login replacement rule so a real client login wins deterministically and closes any reconnect attempt (`plugin/src/main/java/com/fakeplayerproxy/automation/AutomationManager.java:69-104`).

### EventLoop constraints

- Plugin Player/action state is documented and implemented as frontend-EventLoop-confined. Registration, disconnect cancellation, command actions, and the 20 TPS task all marshal/check `owner.eventLoop()` (`plugin/src/main/java/com/fakeplayerproxy/automation/AutomationManager.java:36-64`, `:121-125`; `plugin/src/main/java/com/fakeplayerproxy/automation/AutomationService.java:223-237`, `:495-507`).
- Backend packet writes must occur on the backend EventLoop. `sendPacket` and `respond` marshal there (`plugin/patch/0002-automation-extension.patch:1194-1205`; `plugin/src/main/java/com/fakeplayerproxy/automation/AutomationService.java:386-398`).
- The initial relay explicitly crosses from frontend to backend by cloning the secret, scheduling on the backend loop, and zeroing both owners' copies (`plugin/patch/0001-login-relay.patch:2436-2485`). A reconnect credential handoff needs the same explicit ownership, but long-lived credentials should not be copied per retry.
- `ConnectedPlayer.teardown` applies DisconnectEvent completion back on the frontend EventLoop before registry/backend mutation (`plugin/patch/0002-automation-extension.patch:2388-2413`). A reconnect transition must serialize with that completion and with a concurrent genuine login; it must not mutate `connectedServer`, `connectionInFlight`, or AutomationManager state from an arbitrary scheduler thread.
- The retained frontend EventLoop is a workable current executor but a fragile lifetime anchor. A robust design should either prove Velocity's worker group outlives every shadow/reconnect attempt or migrate automation ownership to a deliberately retained backend/server EventLoop with an explicit handoff. Merely calling `oldFrontend.eventLoop().execute` after arbitrary delay can reject execution during proxy shutdown.

### Feasibility verdict

A fresh backend socket for the retained ConnectedPlayer is mechanically feasible, but a **fresh authenticated online-mode backend session is not feasible with current public Velocity APIs or current retained state**. It requires both new authorization material and additional pinned Velocity patches for stale-server detachment, continuation ownership, headless online login, and fresh CONFIG/PLAY completion. The existing relay AES secret and initial join are deliberately one-shot and erased; they cannot be repurposed as reconnect authorization.

The required state model keeps the exact plugin `Player`, `World`, and `AutomationService`. It freezes user intent and clears backend-derived state. New CONFIG and PLAY packets rebuild that state before automation resumes. A real same-UUID frontend login still replaces and closes this retained lifecycle.

See `automation-service-reconnect-continuity.md` for the field-level state map and complete transition chain.

## Related specs

- `.trellis/spec/backend/velocity-plugin.md:98-107` - AutomationManager exact-player ownership and EventLoop confinement; no runtime reconnect configuration.
- `.trellis/spec/backend/velocity-plugin.md:107-108` - current contract explicitly says Shadow never creates a second backend connection, reconnects, or copies connection secrets.
- `.trellis/spec/backend/velocity-plugin.md:139-147` - accepted PostLogin registration and Shadow DisconnectEvent cancellation contract.
- `.trellis/spec/backend/velocity-plugin.md:795-808` - validation matrix: backend loss causes exact service removal/close; fresh same-UUID login creates a fresh service.
- `.trellis/spec/backend/velocity-plugin.md:872-882` - required lifecycle tests include fresh-login replacement and continuation behavior, but not automatic reconnect.

## External references

- No external sources were required for this code-lifecycle trace. The host behavior is pinned to Velocity commit `843a47e2a38325309cd66133149fc9a984f76bb8` by `plugin/patch/velocity-base.properties:1`.
- The separate task research on Minecraft session authorization should be treated as the authority for token/proof scope; this report only establishes the current code's ownership and absence of reusable authorization.

## Caveats / Not Found

- The checked-in patch does not add a production test that drives an actual backend channel close and then attempts a same-ConnectedPlayer reconnect. Current tests cover cancellation shape, packet dispatch, Shadow replies, and same-UUID **fresh frontend** replacement, not auto-reconnect.
- `plugin/build/server/source/**` is generated pinned upstream source and may be recreated by Gradle. Citations to it identify unchanged base behavior; project-owned deltas are cited to `plugin/patch/*.patch`.
- No current code clears `ConnectedPlayer.connectedServer` on unexpected backend disconnect, transfers `logoutCancelled` to a reconnected backend, retains an access token or join proof, or exposes a headless authenticated reconnect API.
- Whether a narrower delegated proof can replace a bearer token is outside this lifecycle trace and remains dependent on the parallel authorization research.
