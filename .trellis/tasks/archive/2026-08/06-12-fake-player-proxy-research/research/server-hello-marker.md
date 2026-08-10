# Minecraft 26.2 Server Hello Extension Research

Checked on 2026-08-09.

## Velocity Packet Path

Velocity represents the Minecraft Server Hello packet with
`EncryptionRequestPacket`.

The packet already contains:

- `serverId`
- `publicKey`
- `verifyToken`
- `shouldAuthenticate`

Velocity allows a `verifyToken` of up to 16 bytes. The current login handler
creates four random bytes and checks the returned token.

Sources:

- `https://github.com/PaperMC/Velocity/blob/dev/3.0.0/proxy/src/main/java/com/velocitypowered/proxy/protocol/packet/EncryptionRequestPacket.java`
- `https://github.com/PaperMC/Velocity/blob/dev/3.0.0/proxy/src/main/java/com/velocitypowered/proxy/connection/client/InitialLoginSessionHandler.java`

## Extra Field Compatibility

Minecraft 26.2 `PacketDecoder` throws an `IOException` when bytes remain after
packet decoding. A new trailing Server Hello field breaks an unmodified client.

Velocity applies the same complete-read rule in `MinecraftDecoder`.

Sources:

- Minecraft 26.2 client class `net.minecraft.network.PacketDecoder`
- `https://github.com/PaperMC/Velocity/blob/dev/3.0.0/proxy/src/main/java/com/velocitypowered/proxy/protocol/netty/MinecraftDecoder.java`

## Minecraft 26.2 Client Path

Minecraft 26.2 uses these classes:

- `net.minecraft.network.protocol.login.ClientboundHelloPacket`
- `net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl`
- `net.minecraft.network.protocol.login.ServerboundKeyPacket`
- `net.minecraft.util.Crypt`

`ClientHandshakePacketListenerImpl.handleHello(...)` calls
`Crypt.generateSecretKey()`. Vanilla Minecraft creates a 128-bit AES key and
uses it for the login key response and connection ciphers.

The mod needs no Fabric API dependency to modify this path. Fabric Loader
provides the Mixin runtime.

## Remaining Evidence Required

- Verify the exact Mixin point for decoding a modified Server Hello field.
- Verify how server-generated AES replaces or bypasses vanilla client-side key
  generation without changing unsupported-server behavior.
- Verify the field's confidentiality properties before encryption is installed.
- Verify the corresponding Velocity encode, support-detection, rejection, and
  cipher-installation order at the pinned commit.
