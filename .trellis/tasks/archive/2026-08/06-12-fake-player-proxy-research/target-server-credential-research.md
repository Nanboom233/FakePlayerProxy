# Target Server Credential and Forwarding Research

## Target Probe

Date: 2026-08-08.

Target: `mc.ourworld.vip:25565`.

DNS resolves to `116.235.43.160` and TCP port `25565` accepts connections.

The status response reports `Paper 26.2`, protocol `776`, with `21` of `69` player slots used.

A protocol `776` login probe sent `ServerboundHelloPacket` fields for a username and UUID.
The server returned a login encryption request with a 2048-bit RSA public key and a verify token.

This response proves that the target expects the online-mode login encryption flow.
The probe did not send a real access token or session join request.

## Velocity Plugin Boundary

Velocity exposes login events, server selection events, and `Player.createConnectionRequest(RegisteredServer)`.
These APIs route an existing proxy player to a registered backend.

Velocity does not expose a plugin API for a headless backend client.
The backend connection owns a `ConnectedPlayer` instance.

Velocity source handles a backend `EncryptionRequestPacket` with:

```java
throw new IllegalStateException("Backend server is online-mode!");
```

Therefore, the stock Velocity backend path cannot log into this target as an online-mode client.

## Credential Acceptance Models

### Native Velocity backend

This model supports a backend that runs offline mode and trusts a forwarding protocol.

It does not support the target observed above because the target sends an encryption request.

### Modern forwarding

Velocity sends a login plugin message on `velocity:player_info`.
The payload contains the player address, UUID, name, profile properties, and an HMAC-SHA256 signature.

The backend must run a compatible Paper, Fabric, Forge, or proxy-support plugin.
The backend must use the same forwarding secret.

Modern forwarding is not a generic credential format for arbitrary servers.

### Legacy forwarding

Velocity can append the address, player IP, undashed UUID, and profile properties to the handshake host.

Paper or Spigot must enable BungeeCord forwarding to accept this data.
The protocol is not safe on an exposed backend and does not solve online-mode encryption.

### BungeeGuard

BungeeGuard adds a shared token to the legacy forwarding properties.
The backend must install and configure BungeeGuard.

This model still requires an offline-mode backend.

### PROXY protocol

PROXY protocol can carry the source network address to a server or load balancer.
It does not carry a Minecraft UUID, profile, access token, or session join proof.

It cannot solve this target login requirement.

### Independent online-mode protocol client

An independent client must complete the target login flow as the real player.
The client must hold a valid Microsoft-to-Minecraft account session.

MCProtocolLib already models the client login flow and calls Mojang session join when required.
The project must add an auth component that supplies the profile UUID, username, and access token.

The target server then authenticates the independent client as a normal Minecraft client.

MCProtocolLib source confirms this sequence. `ClientListener` reads the server hello packet,
calculates the server ID hash, calls `SessionService.joinServer`, and sends the encrypted key packet.

MinecraftAuth source confirms device-code login, token refresh, token serialization, and token restoration.

## Credential Transfer Options

1. Use a user-approved Microsoft device-code flow.
2. Store a refresh token in an encrypted secret store.
3. Refresh the Minecraft access token before each reconnect.
4. Pass only a short-lived access token to the protocol client.
5. Redact tokens from logs, status output, exceptions, and audit records.

Do not copy the local Minecraft launcher token from disk.
Do not send an access token through Velocity forwarding or a backend plugin message.
Do not accept a username without the matching authenticated session material.

## Required Implementation Change

The project must treat `mc.ourworld.vip` as an online-mode upstream target.
The Velocity plugin can keep the real player session and command surface.
The protocol component must create the upstream client session.

The next code boundary should be:

```text
Velocity Player
  -> AuthService: authenticated profile and token
  -> UpstreamClient: online-mode login and session join
  -> mc.ourworld.vip:25565
```

The current `McProtocolLibUpstreamClient` uses username-only offline login.
It cannot connect to the target until it accepts authenticated `AuthMaterial`.

## Sources

- https://github.com/PaperMC/Velocity/blob/dev/3.0.0/proxy/src/main/java/com/velocitypowered/proxy/connection/backend/LoginSessionHandler.java
- https://github.com/PaperMC/Velocity/blob/dev/3.0.0/proxy/src/main/java/com/velocitypowered/proxy/connection/PlayerDataForwarding.java
- https://docs.papermc.io/velocity/player-information-forwarding/
- https://github.com/GeyserMC/MCProtocolLib/blob/master/protocol/src/main/java/org/geysermc/mcprotocollib/protocol/packet/login/serverbound/ServerboundHelloPacket.java
- https://github.com/GeyserMC/MCProtocolLib/blob/master/protocol/src/main/java/org/geysermc/mcprotocollib/protocol/ClientListener.java
- https://github.com/GeyserMC/MCProtocolLib/blob/master/protocol/src/main/java/org/geysermc/mcprotocollib/auth/SessionService.java
- https://github.com/RaphiMC/MinecraftAuth/blob/main/README.md
- https://minecraft.wiki/w/Java_Edition_protocol/Packets
- https://c4k3.github.io/wiki.vg/Protocol_Encryption.html
