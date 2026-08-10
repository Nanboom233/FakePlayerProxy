# Research Notes

## Search Method

- Used the required `web-search` flow: grok-search configuration check, search planning, web search, source inspection, and targeted fetches.
- Used GitHub CLI as an additional verification path for public repository metadata and raw README/source files.

## Local Context

- Current branch: `master`.
- Current repository state before task edits: clean.
- Recent commit: `f2b5f7e chore: init commit.`
- No application source files were found outside Trellis-managed files, so product and technical answers must come from external upstream research and future implementation decisions.

## Velocity Findings

Sources:

- https://docs.papermc.io/velocity/dev/event-api/
- https://docs.papermc.io/velocity/dev/pitfalls
- https://docs.papermc.io/velocity/configuration
- https://docs.papermc.io/velocity/getting-started
- https://jd.papermc.io/velocity/3.5.0/com/velocitypowered/api/event/connection/PreLoginEvent.html
- https://jd.papermc.io/velocity/3.5.0/com/velocitypowered/api/event/connection/LoginEvent.html
- https://jd.papermc.io/velocity/3.5.0/com/velocitypowered/api/event/player/ServerPreConnectEvent.html
- https://jd.papermc.io/velocity/3.5.0/com/velocitypowered/api/proxy/Player.html
- https://jd.papermc.io/velocity/3.5.0/com/velocitypowered/api/proxy/server/RegisteredServer.html
- https://github.com/PaperMC/Velocity

Observed facts:

- Velocity plugins can hook login and connection routing with events such as `PreLoginEvent`, `LoginEvent`, and `ServerPreConnectEvent`.
- Events can be asynchronous, but only some events await completion before Velocity continues the connection flow.
- Velocity plugin initialization must wait for `ProxyInitializeEvent`; API use in the plugin constructor is unsafe.
- `Player` has `createConnectionRequest(RegisteredServer)`, `spoofChatInput(String)`, cookies, and transfer methods.
- `RegisteredServer` has `getPlayersConnected()`, `ping(...)`, and inherited plugin-message/audience methods, but no public API for a plugin to create a backend connection without a `Player`.
- Velocity's normal backend model expects the proxy to authenticate users and forward them to owned servers, typically with backend `online-mode=false` plus forwarding protection.

Initial interpretation:

- A pure Velocity plugin can likely handle the human-facing proxy, limbo routing, consent/config, command parsing, and online-player control.
- A pure Velocity plugin is unlikely to be sufficient by itself for "continue as a Minecraft client after the real player disconnects from the proxy", unless the automated session is implemented as a separate protocol client component outside the public Velocity API.
- Minimal patch candidates, if needed, are likely around session handoff, packet bridge ownership, or access to Velocity internals rather than ordinary event handling.

Code-level follow-up:

- `VelocityServerConnection` is constructed around a `ConnectedPlayer` and derives protocol version, virtual host, forwarding data, username, UUID, identified key, and event loop from that player.
- `VelocityServerConnection.isActive()` requires the player to be active.
- `ConnectedPlayer.teardown()` disconnects the current backend connection and in-flight connection.
- `ClientPlaySessionHandler.disconnected()` calls `player.teardown()`.
- `BackendPlaySessionHandler` requires a backing `ClientPlaySessionHandler` and writes backend packets to the player's `MinecraftConnection`.
- `LoginSessionHandler.handle(EncryptionRequestPacket)` throws an online-mode backend error.
- Conclusion: stock Velocity is not a headless upstream-client runtime. It is a player-centric proxy to controlled backends.

## Minecraft Auth / Auto Reconnect Findings

Sources:

- https://minecraft.wiki/w/Java_Edition_protocol/Packets
- https://c4k3.github.io/wiki.vg/Protocol_Encryption.html

Observed facts:

- The Java protocol login flow includes handshake, login start, encryption request, client auth if enabled, encryption response, server auth if enabled, encryption enablement, optional compression, login success, and login acknowledgement.
- For online-mode servers, encryption is mandatory.
- The client and server both interact with `sessionserver.mojang.com` for online-mode authentication.
- The sessionserver join step binds the authenticated profile/access token to a per-connection server hash derived from the encryption shared secret and server public key.

Initial interpretation:

- Auto Reconnect against online-mode upstream servers needs the proxy-side automated client to repeat the full login/encryption/sessionserver flow.
- If the player disconnects their local client, the proxy cannot rely on the local client to complete future join calls.
- A no-credential baseline can support "keep current connection alive after handoff" if the proxy already owns the active upstream connection, but reconnect after a network/server drop probably needs opt-in delegated auth material or a separately established session.
- The research must distinguish "keep alive after player disconnects from proxy" from "re-login after upstream disconnect", because they have different auth requirements.

Code-level follow-up:

- MCProtocolLib `SessionService` has `joinServer(profile, authenticationToken, serverId)`.
- MCProtocolLib `ClientListener` handles `ClientboundHelloPacket`, computes the server id, calls `joinServer` when authentication is required, sends the encrypted key packet, handles compression, and moves through login/configuration states.
- A protocol-client implementation does not need to reimplement the low-level online-mode join flow from scratch, but it still needs valid profile/token material.

## Limbo Findings

Sources:

- https://github.com/Elytrium/LimboAPI
- https://github.com/Nan1t/NanoLimbo

Observed facts:

- LimboAPI describes itself as an API providing virtual server features to Velocity.
- LimboAPI supports sending players to a limbo server during the login process through `LoginLimboRegisterEvent`.
- LimboAPI supports using `LimboFactory` to send players to limbo during play.
- NanoLimbo is a lightweight Java/Netty limbo server intended to send/process a minimum number of packets.
- NanoLimbo can provide information through chat or boss bar and supports Velocity modern forwarding with the Velocity forwarding secret.

Initial interpretation:

- LimboAPI is the most natural plugin-first MVP candidate because it keeps the consent/config experience inside Velocity.
- NanoLimbo is a fallback if virtual limbo is too limiting or if a standalone limbo process is operationally cleaner.
- A custom limbo implementation should be avoided until LimboAPI/NanoLimbo limitations are proven.

Code-level follow-up:

- LimboAPI exposes API packages for `Limbo`, `LimboFactory`, `LimboPlayer`, virtual worlds/chunks, prepared packets, packet factories, and `LoginLimboRegisterEvent`.
- NanoLimbo exposes configuration and protocol packets for login/configuration/play plus chat and boss bar support, but requires a separate server process.

## Carpet `/player` Findings

Sources:

- https://github.com/gnembon/fabric-carpet/wiki/Commands
- https://raw.githubusercontent.com/gnembon/fabric-carpet/master/src/main/java/carpet/commands/PlayerCommand.java
- https://raw.githubusercontent.com/gnembon/fabric-carpet/master/src/main/java/carpet/helpers/EntityPlayerActionPack.java
- https://raw.githubusercontent.com/gnembon/fabric-carpet/master/src/main/java/carpet/patches/EntityPlayerMPFake.java

Observed facts:

- The Carpet wiki's `/player` section is incomplete and should not be the only source for command parity.
- Carpet `/player` creates fake players that act almost exactly like real players.
- Source-registered commands include `spawn`, `attack`, `use`, `mount`, `dismount`, `drop`, `dropStack`, `jump`, `kill`, `look`, `move`, `shadow`, `sneak`, `unsneak`, `sprint`, `unsprint`, `stop`, `swapHands`, `turn`, and `hotbar`.
- `shadow` replaces the real player with a fake player on a server and continues scheduled actions; the wiki notes this only works on servers, not singleplayer.
- Source confirms default action commands are equivalent to `once`; `continuous` and `interval <ticks>` are implemented by `EntityPlayerActionPack.Action`.
- `EntityPlayerActionPack` keeps one action per `ActionType` and processes actions every tick.
- `stop` clears all actions and also clears movement, sneaking, and sprinting.
- `move` without a direction stops movement; directional `move` sets one axis and can combine forward/backward with left/right.
- `mount anything` force-rides arbitrary entities server-side, which a vanilla client/protocol bot cannot generally do.

Initial interpretation:

- The closest conceptual fit for this project is Carpet's `shadow`, but implemented at the proxy/client-protocol layer rather than inside a modded backend server.
- MVP should support `shadow`, `stop`, `kill`, `hotbar`, `move`, `look`, `turn`, `jump`, `sneak/unsneak`, and `sprint/unsprint`.
- `attack` and `use` should be MVP-partial: implement scheduler and safe target-aware forms first, but do not promise full Carpet parity for block breaking, hand fallback, cooldowns, or exact raycast behavior until world/inventory trackers exist.
- Commands requiring exact inventory/container semantics (`drop`, `dropStack`, `swapHands`) and vehicle/entity selection (`mount`, `dismount`) should be deferred.
- `spawn` should be out of MVP until the product defines self-only semantics; Carpet's arbitrary fake-player creation does not map cleanly to account-owned proxy automation.
- `mount anything` should be marked unsupported in protocol-only mode.

Code-level follow-up:

- MCProtocolLib exposes serverbound packet classes corresponding to movement, position/rotation, input flags, attack, interact, use item, use item on block, swing arm, player action, and held slot changes.
- In the current MCProtocolLib source, `ServerboundPlayerInputPacket` is under `packet.ingame.serverbound.level`, not `packet.ingame.serverbound.player`.
- In the current MCProtocolLib source, sneak/shift is represented in `ServerboundPlayerInputPacket`, while sprint also appears in input flags and sprint state has separate `PlayerState` entries. Version-specific validation is required.
- Detailed mapping and tests are in `carpet-command-mapping.md`.

## MCProtocolLib Findings

Sources:

- https://github.com/GeyserMC/MCProtocolLib
- `protocol/src/main/java/org/geysermc/mcprotocollib/protocol/MinecraftProtocol.java`
- `protocol/src/main/java/org/geysermc/mcprotocollib/protocol/ClientListener.java`
- `protocol/src/main/java/org/geysermc/mcprotocollib/auth/SessionService.java`
- serverbound packet classes under `protocol/src/main/java/org/geysermc/mcprotocollib/protocol/packet/ingame/serverbound`
- https://github.com/RaphiMC/MinecraftAuth

Observed facts:

- MCProtocolLib is MIT licensed.
- It is intended for custom bots, clients, and servers.
- `MinecraftProtocol` accepts a `GameProfile` and access token for authenticated login or a username/offline profile for offline mode.
- `ClientListener` handles login encryption, sessionserver join, compression, login finished, and configuration state.
- Packet classes exist for core MVP automation actions.
- MCProtocolLib uses MinecraftAuth in its auth example/dependency wiring for Microsoft/Minecraft token acquisition.
- MinecraftAuth provides Java auth manager flows including device code, token lifecycle management, JSON serialization/deserialization, lazy refresh through holders, and change listeners for persistence.

Initial interpretation:

- MCProtocolLib is the preferred JVM implementation dependency for the upstream automated client.
- It should be wrapped behind an internal interface to isolate future library changes.
- Online-mode reconnect still requires auth material policy, storage, consent, and revocation design.
- Refresh-capable auth should be behind a separate `MicrosoftAuthService` and encrypted `SecretStore`; access-token-only remains the safer first online-mode spike.
- Detailed auth/reconnect implementation notes are in `auth-reconnect-research.md`.

## License Notes

Checked with GitHub CLI:

- PaperMC/Velocity: GPLv3.
- Elytrium/LimboAPI: AGPLv3.
- Nan1t/NanoLimbo: GPLv3.
- gnembon/fabric-carpet: MIT.
- RaphiMC/MinecraftAuth: LGPLv3.

Initial interpretation:

- Calling LimboAPI as a plugin dependency is different from copying/modifying its code. Any direct reuse or distribution strategy needs a license review.
- Patching Velocity or distributing a derived Velocity server must account for GPLv3 obligations.
- Carpet source can be referenced for behavior with fewer license constraints, but direct code reuse still needs attribution and compatibility review.
- Directly depending on or bundling MinecraftAuth requires LGPLv3 packaging/release review.

## Initial Risk Register

- Auth/session storage risk: reconnect may require sensitive delegated auth material.
- Server policy risk: arbitrary public server automation may violate rules or trigger anti-cheat systems.
- API boundary risk: Velocity public API is likely insufficient for headless upstream sessions.
- Protocol drift risk: Minecraft protocol versions change frequently, especially around login/configuration/play packets.
- Account conflict risk: handling a returning real player while a proxy-controlled session is still online must be specified.
- Security risk: automation commands must be self-owned only and protected against other users controlling another account.
- Operational risk: long-running automated sessions need rate limiting, backoff, reconnect caps, observability, and kill switches.

## Recommended Next Research Steps

1. Decide the target upstream-server policy: owned/authorized only versus arbitrary public servers.
2. Choose the Minecraft version range for MVP.
3. Deep-dive Velocity internals to locate the smallest patch point if plugin plus sidecar cannot bridge sessions cleanly.
4. Prototype-free design: define the session state machine for online player, limbo, shadowed automation, reconnecting, stopped, and reclaimed states.
5. Define credential modes and storage requirements before any implementation task starts.
