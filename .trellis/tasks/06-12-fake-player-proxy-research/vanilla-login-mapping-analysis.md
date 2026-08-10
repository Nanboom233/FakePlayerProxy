# Vanilla Login Mapping Analysis

## Mapping Source

The analysis uses the official Mojang `1.21.6` server mapping file.

Mapping file:

`https://piston-data.mojang.com/v1/objects/94d453080a58875d3acc1a9a249809767c91ed40/server.txt`

The mapping names the login implementation as `net.minecraft.server.network.ServerLoginPacketListenerImpl`.

The client mapping names the client login implementation as `net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl`.

The client mapping names the network channel as `net.minecraft.network.Connection`.

## Vanilla Login Sequence

## Server-side key handling (official 1.21.6 bytecode)

The official mapping resolves `net.minecraft.server.network.ServerLoginPacketListenerImpl` to `avh` and `net.minecraft.network.protocol.login.ServerboundKeyPacket` to `akk`. Decompiling the official server jar shows the following exact order in `ServerLoginPacketListenerImpl.handleKey`:

1. Read the server's `KeyPair.getPrivate()`.
2. Call `ServerboundKeyPacket.isValidChallenge(challenge, privateKey)`; failure throws a protocol error.
3. Call `ServerboundKeyPacket.getSecretKey(privateKey)`, which invokes RSA decryption and constructs an AES `SecretKey`.
4. Compute the session hash from the server id, server public key, and the decrypted AES key.
5. Build AES/CFB8/NoPadding decrypt and encrypt ciphers with the AES key as IV, then install them with `Connection.setEncryptionKey`.
6. Continue authentication asynchronously; the server-side session service checks the computed hash with Mojang before accepting the profile.

`ServerboundKeyPacket` stores only RSA-encrypted byte arrays on the wire. Its `getSecretKey(privateKey)` calls `Crypt.decryptUsingKey(privateKey, encryptedSecret)`, and its challenge check decrypts the encrypted challenge with the same private key and compares bytes.

Therefore a packet-forwarding middleman that has neither the remote server process nor its RSA private key cannot recover the AES key from captured traffic. Once encryption is enabled, it can only forward ciphertext; it cannot decode or rewrite packets.

## Vanilla Client Call Chain

The client starts at `net.minecraft.client.gui.screens.ConnectScreen.startConnecting`.

`ConnectScreen.connect` starts a connector thread and resolves the server address.

The connector creates `net.minecraft.network.Connection` with clientbound packet flow.

The connector calls `Connection.connectToServer` and installs the client handshake listener.

The connector sends `ClientIntentionPacket` with `ClientIntent.LOGIN`.

The connector changes the connection protocol to LOGIN.

The connector sends `ServerboundHelloPacket` with the authenticated username and profile UUID.

The connection dispatches `ClientboundHelloPacket` to `ClientHandshakePacketListenerImpl.handleHello`.

`handleHello` creates the AES key with `Crypt.generateSecretKey`.

`handleHello` computes the server hash with `Crypt.digestData`.

When `shouldAuthenticate` is true, `handleHello` calls `authenticateServer`.

`authenticateServer` calls `MinecraftSessionService.joinServer` with the profile, access token, and server hash.

After a successful join, the client creates AES encrypt and decrypt ciphers.

The client creates `ServerboundKeyPacket` with the AES key, RSA public key, and challenge.

`setEncryption` sends the key packet and then calls `Connection.setEncryptionKey`.

`Connection.setEncryptionKey` adds `CipherDecoder` before `splitter`.

`Connection.setEncryptionKey` adds `CipherEncoder` before `prepender`.

The connection sets its encrypted flag to true.

The client then handles login compression, login finish, configuration acknowledgement, and play packets.

### 1. Handshake

`ClientIntentionPacket` contains the protocol version, host name, port, and `ClientIntent`.

The client sets `ClientIntent.LOGIN` for a login request.

### 2. Login hello

`ServerboundHelloPacket` contains the player name and a profile UUID.

`ServerLoginPacketListenerImpl.handleHello` accepts this packet only in the `HELLO` state.

In online mode, the server creates a new login challenge and sends `ClientboundHelloPacket`.

The packet contains the server ID, the RSA public key, the random challenge, and `shouldAuthenticate=true`.

In offline mode, the server creates an offline profile and skips encryption.

### 3. Session verification

The client creates a random AES shared key.

The client calculates a server ID hash from the server ID, AES key, and RSA public key.

The client calls Mojang session join with the profile, access token, and server ID hash.

The client sends `ServerboundKeyPacket`.

The packet contains the RSA-encrypted AES key and the RSA-encrypted challenge.

The server decrypts both values with its private key.

The server checks that the decrypted challenge equals its stored challenge.

The server enables AES encryption on the connection.

The server calls the Mojang session server `hasJoined` check with the username, server ID hash, and client address.

The server stores the returned authenticated `GameProfile`.

### 4. Login completion

The server checks duplicate profiles, bans, whitelist rules, and login events.

The server sends `ClientboundLoginFinishedPacket` with the authenticated profile.

The client acknowledges the login and enters configuration or play state.

## Reuse Analysis

### Packet reuse

The login packet sequence cannot be reused.

The server creates a new random challenge for each connection.

The AES key is new for each connection.

The server ID hash changes when the AES key changes.

The encrypted key packet only matches the server private key and the current challenge.

Replaying an old `ServerboundKeyPacket` fails challenge validation.

### Access token reuse

An access token can remain usable while its Minecraft token lifetime remains valid.

The client must still create a new AES key and call session join for each connection.

The client must not cache or replay a previous server ID hash.

The implementation must refresh an expired token before reconnect.

### Connection reuse

The current encrypted TCP connection can remain active after the local player connection ends.

This requires the proxy to own the same upstream connection and keep its protocol state alive.

A new TCP connection cannot reuse the old login transcript.

## Encryption Handling in the Relay

### Before encryption

The client and remote server exchange ordinary Minecraft packets.

Velocity can decode these packets or forward their bytes.

The remote `ClientboundHelloPacket` contains the remote RSA public key and the remote challenge.

The relay must forward this packet without changing either value.

### Key exchange

The client creates one AES key for the connection.

The client encrypts that AES key and the challenge with the remote RSA public key.

The relay forwards `ServerboundKeyPacket` without decrypting it.

The relay does not have the remote RSA private key.

The remote server decrypts the packet and enables AES encryption.

The client also enables AES encryption after it sends the key packet.

Both endpoints now use the same AES key and the same AES/CFB8 stream state.

### After encryption

The relay must forward raw encrypted bytes between the two TCP channels.

The relay must not decode packet IDs, packet lengths, compression, or payloads.

The relay must preserve byte order. TCP may merge or split individual writes.

Netty must apply backpressure in both directions.

The relay must close both channels when either channel fails.

### Why a normal MITM cannot inspect these packets

The client computes Mojang session join data from the remote public key.

The client also encrypts its AES key with the remote public key.

The relay cannot decrypt that AES key because it does not have the remote private key.

If the relay sends its own public key, the client computes a different session hash.

The remote server then rejects the session because the hash does not match its public key.

Therefore, the relay cannot both preserve vanilla online authentication and inspect encrypted play packets.

### Two possible data paths

#### Transparent path

Use the remote public key without changes.

Forward the encrypted bytes without changes.

This path supports arbitrary online-mode servers.

This path cannot support packet-level Velocity commands after encryption.

#### Terminating path

Give the client a proxy-generated RSA public key.

Decrypt the client AES key at Velocity.

Create a second AES key for the remote server.

Decrypt and re-encrypt every packet between both channels.

This path allows packet inspection and modification.

This path requires Velocity to obtain the player access token separately.

Velocity must call session join for the remote server hash with that token.

The vanilla client does not send its access token through the Minecraft connection.

## Proxy Forwarding Difference

Vanilla does not read Velocity modern forwarding data.

Paper adds a custom login query on `velocity:player_info` when Velocity support is enabled.

Paper verifies the HMAC secret, reads the forwarded profile, and then skips normal online authentication.

This behavior belongs to Paper and its proxy support code.

A vanilla server cannot accept this query without a server modification.

## Sources

- https://piston-meta.mojang.com/mc/game/version_manifest_v2.json
- https://piston-data.mojang.com/v1/objects/94d453080a58875d3acc1a9a249809767c91ed40/server.txt
- https://github.com/PaperMC/Paper/blob/ver/1.21.9/paper-server/patches/sources/net/minecraft/server/network/ServerLoginPacketListenerImpl.java.patch
- https://github.com/GeyserMC/MCProtocolLib/blob/master/protocol/src/main/java/org/geysermc/mcprotocollib/protocol/ClientListener.java
- https://github.com/GeyserMC/MCProtocolLib/blob/master/protocol/src/main/java/org/geysermc/mcprotocollib/auth/SessionService.java
