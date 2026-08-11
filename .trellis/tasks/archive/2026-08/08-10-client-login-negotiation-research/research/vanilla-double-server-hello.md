# Research: Vanilla double Server Hello on one login connection

- Query: Can an unmodified Minecraft 26.2 client process two Server Hello packets on one frontend login connection through Velocity?
- Scope: Mixed. This report covers the official Minecraft 26.2 client, the pinned Velocity source and patch, Netty pipeline behavior, and a focused local runtime probe.
- Date: 2026-08-10

## Verdict

**Not feasible for an unmodified Minecraft 26.2 client.**

The Minecraft packet codec can decode a second Server Hello while the connection is in the Login protocol. The login listener then rejects that packet. The listener has a one-shot state transition. The first Server Hello changes the listener state from `CONNECTING` to `AUTHORIZING`. The first key response changes it to `ENCRYPTING`. A second Server Hello tries to change the state to `AUTHORIZING` again. That transition throws an `IllegalStateException`.

A second independent blocker exists after that state check. Minecraft installs its cipher handlers with the fixed names `decrypt` and `encrypt`. Velocity installs its cipher handlers with the fixed names `cipher-decoder` and `cipher-encoder`. Both implementations use `addBefore`. Netty rejects a second handler with the same name. Neither implementation replaces the first cipher.

The design is conditionally feasible only after a client change and a Velocity core change. That result does not meet the unmodified client requirement.

For an unmodified client, the minimum transparent design uses one target Server Hello. Velocity must forward the target key response and then become a byte relay. A packet proxy cannot continue unless it learns the target AES key.

## Evidence Status

### Verified facts

- The official Minecraft 26.2 client has the one-shot listener state.
- The Login packet codec registers Server Hello for the complete Login protocol.
- The first key response write completes before Minecraft installs the first AES cipher.
- The fixed handler names prevent a second cipher installation in Minecraft and Velocity.
- The current Velocity patch uses one decorated Server Hello. It does not send two Server Hello packets.
- The current Velocity patch terminates encryption on both legs. It remains a packet proxy.

### Runtime results

- The local official client JAR SHA-1 is `2dc72797acbc1b63fc16a11c4ac393605f453754`.
- The local Mojang version manifest has the same SHA-1 and size `39193383`.
- `javap` on that exact JAR confirmed the listener state check and cipher installation calls.
- A Netty `EmbeddedChannel` probe rejected the second Minecraft handler name.
- The same probe rejected the second Velocity handler name.
- The probe gave the same Minecraft result on Netty `4.2.15.Final` and `4.2.17.Final`.
- The failed add left the first handlers in the pipeline.
- An explicit Netty `replace` operation kept the handler position and changed the handler.

### Inferences

- A custom client and a custom Velocity key-switch method can change keys at the second key response boundary.
- A robust switch needs a read barrier. It must also preserve any unread bytes.
- A byte relay can carry the target encrypted stream without knowing the target AES key.
- The current Velocity packet pipeline cannot change into that relay without a new core tunnel path.

## Files Found

- `.trellis/tasks/08-10-client-login-negotiation-research/prd.md` - defines both Server Hello exchanges and the required cipher analysis.
- `.trellis/tasks/archive/2026-08/06-12-fake-player-proxy-research/vanilla-login-mapping-analysis.md` - gives the prior single-Hello client and server mapping.
- `.trellis/tasks/archive/2026-08/06-12-fake-player-proxy-research/auth-reconnect-research.md` - gives the prior authentication and session join context.
- `.trellis/tasks/archive/2026-08/06-12-fake-player-proxy-research/research/server-hello-envelope-capacity.md` - gives the prior Server Hello field and RSA limits.
- `.trellis/tasks/archive/2026-08/06-12-fake-player-proxy-research/research/minecraft-1.20-26.2-spki-carrier-compatibility.md` - gives the prior 26.2 packet family evidence.
- `plugin/patch/0001-server-hello-marker.patch` - is the stored Velocity patch.
- `plugin/patch/velocity-base.properties` - pins Velocity commit `843a47e2a38325309cd66133149fc9a984f76bb8`.
- `plugin/build/server/source/` - is the generated patched Velocity checkout at that commit.
- `plugin/build/server/source/proxy/src/main/java/com/velocitypowered/proxy/connection/client/InitialLoginSessionHandler.java` - sends the one decorated frontend Server Hello.
- `plugin/build/server/source/proxy/src/main/java/com/velocitypowered/proxy/connection/backend/LoginSessionHandler.java` - writes the reconstructed backend key response and enables backend AES.
- `plugin/build/server/source/proxy/src/main/java/com/velocitypowered/proxy/connection/MinecraftConnection.java` - owns Velocity framing, compression, protocol state, and cipher handlers.
- `plugin/build/server/source/proxy/src/main/java/com/velocitypowered/proxy/network/Connections.java` - defines the fixed Velocity pipeline names.
- `E:/Gradle/caches/fabric-loom/26.2/minecraft-client.jar` - is the exact official Minecraft 26.2 client JAR.
- `E:/Gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.2/minecraft-merged-deobf-26.2-sources.jar` - contains official-named generated source from the 26.2 artifact.
- `E:/Gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.2/minecraft-merged-deobf-26.2.jar` - contains the matching official-named bytecode.
- `E:/Gradle/caches/modules-2/files-2.1/io.netty/` - contains the Netty `4.2.15.Final` and `4.2.17.Final` runtime artifacts used by the probe.

All Minecraft source citations below refer to the 26.2 source archive listed above. All Velocity source citations refer to the generated patched checkout.

## Terms

- `H1` is the first Server Hello from Velocity.
- `H2` is the second Server Hello from the target.
- `R1` is the first client key response.
- `R2` is the second client key response.
- `K1` is the AES key that the client creates for `H1`.
- `K2` is the AES key that the client would create for `H2`.
- `P1` and `C1` are the public key and challenge in `H1`.
- `P2` and `C2` are the target public key and target challenge in `H2`.
- `A1` and `A2` are the `shouldAuthenticate` flags in `H1` and `H2`.
- `frontend` is the client-to-Velocity connection.
- `backend` is the Velocity-to-target connection.
- `byte relay` forwards opaque TCP bytes.
- `packet proxy` decrypts, frames, decodes, tracks, encodes, and encrypts packets.

## Minecraft 26.2 Listener State

### Packet codec state

`LoginProtocols.CLIENTBOUND_TEMPLATE` registers `CLIENTBOUND_HELLO` as a valid Login packet. See the 26.2 source at `net/minecraft/network/protocol/login/LoginProtocols.java:22-31`.

The client keeps the Login inbound protocol and the same `ClientHandshakePacketListenerImpl` until Login Finished. Login Finished installs the Configuration protocol at `ClientHandshakePacketListenerImpl.java:173-202`.

This means that the packet codec can decode `H2` before Login Finished. The codec has no Server Hello count. `ClientboundHelloPacket.handle` calls `listener.handleHello(this)` at `ClientboundHelloPacket.java:41-48`.

### One-shot listener guard

The listener starts with this state:

```text
state = CONNECTING
```

See `ClientHandshakePacketListenerImpl.java:74`.

Every Server Hello starts with this call:

```text
switchState(AUTHORIZING)
```

See `ClientHandshakePacketListenerImpl.java:101-114`.

The state table has these allowed transitions:

```text
CONNECTING  -> AUTHORIZING
AUTHORIZING -> ENCRYPTING
ENCRYPTING  -> JOINING
CONNECTING  -> JOINING
```

See `ClientHandshakePacketListenerImpl.java:256-269`.

`AUTHORIZING` accepts only `CONNECTING`. The state method throws when the old state is not in the allowed set. See `ClientHandshakePacketListenerImpl.java:101-108`.

The first call to `handleHello` changes the state to `AUTHORIZING`. The first call to `setEncryption` changes it to `ENCRYPTING`. See `ClientHandshakePacketListenerImpl.java:113-153`.

No timing changes this result.

- If `H2` arrives while the first Mojang join runs, the old state is `AUTHORIZING`.
- If `H2` arrives after `R1` starts, the old state is `ENCRYPTING`.
- If both packets arrive in one network read, packet dispatch still handles `H1` first. `H1` changes the atomic state before `H2` enters the listener.

All three cases reject the second transition to `AUTHORIZING`.

### Exact failure point

The second call fails before these operations:

- `Crypt.generateSecretKey()`
- `packet.getPublicKey()`
- target digest calculation
- `new ServerboundKeyPacket(...)`
- `joinServer(...)`
- the second key response write
- the second cipher installation

Those operations start after the state change at `ClientHandshakePacketListenerImpl.java:121-158`.

The official client bytecode has the same order. `javap` showed `switchState(AUTHORIZING)` at bytecode offsets `1..4`. `Crypt.generateSecretKey()` starts at offset `7`.

## Mojang Session Joins

### Actual unmodified path

The unmodified client performs at most one Mojang session join.

- If `A1` is true, it performs one join for `H1`.
- If `A1` is false, it performs no join for `H1`.
- It performs no join for `H2` because the listener rejects `H2` first.

The first digest is:

```text
D1 = signed-hex(SHA-1(serverId1 || K1 || DER(P1)))
```

`Crypt.digestData` uses the server ID, AES key bytes, and encoded public key in that order. See `Crypt.java:78-93`. The listener changes the digest to a signed `BigInteger` hex string. See `ClientHandshakePacketListenerImpl.java:121-134`.

The join call uses the profile ID, access token, and `D1`. See `ClientHandshakePacketListenerImpl.java:156-159`.

### Counterfactual path after removal of the state guard

If a client change allowed `H2`, it would create a new `K2`. It would calculate this digest:

```text
D2 = signed-hex(SHA-1(serverId2 || K2 || DER(P2)))
```

If `A2` is true, it would call `joinServer` with `D2`. If both flags are true, the client code would make two join calls. If `A1` is false, it would make only the target join.

This counterfactual result follows from the same method body. It is not a runtime result. This research did not send two live joins to Mojang. The unmodified listener cannot reach the second call.

Setting `A1` to false avoids the first join. It does not remove the listener guard. It does not remove the cipher blocker.

## Key Response Write Boundaries

### First key response

Minecraft builds `R1` as:

```text
R1.keybytes           = RSA_P1(K1)
R1.encryptedChallenge = RSA_P1(C1)
```

See `ServerboundKeyPacket.java:19-31`.

Minecraft sends `R1` with a `PacketSendListener`. The callback installs AES after the write future completes. See `ClientHandshakePacketListenerImpl.java:151-153` and `PacketSendListener.java:13-19`.

Therefore, `R1` is plaintext at the Minecraft transport layer. The RSA fields remain encrypted.

Velocity reads plaintext `R1`. Velocity can decrypt `K1` when `P1` belongs to Velocity. Velocity then installs the frontend `K1` cipher.

### Counterfactual second key response

If the client reached the second response, it would build:

```text
R2.keybytes           = RSA_P2(K2)
R2.encryptedChallenge = RSA_P2(C2)
```

The first `encrypt` handler is active during the `R2` write. The callback for `R2` runs only after that write completes. Therefore, the wire carries the complete framed `R2` under `K1`.

This is the required boundary:

```text
... K1 ciphertext ... | end of framed R2 | ... K2 ciphertext ...
```

Source inspection proves the send-then-install order. It does not make the stock second install valid.

## Cipher Handler Blocker

### Minecraft pipeline

Minecraft creates these framing handlers:

```text
splitter  -> packet decoder
prepender -> packet encoder
```

See `Connection.java:459-469`.

The first AES installation runs these operations:

```text
addBefore("splitter", "decrypt", CipherDecoder(K1))
addBefore("prepender", "encrypt", CipherEncoder(K1))
```

See `Connection.java:514-516`.

The same method would run for `K2`. It uses the same names. It does not test for an existing handler. It does not use `replace`.

The second decoder add throws first. The method does not reach the second encoder add. The first `decrypt` and `encrypt` handlers remain active.

### Velocity pipeline

Velocity defines these fixed names:

```text
cipher-decoder
cipher-encoder
frame-decoder
frame-encoder
minecraft-decoder
minecraft-encoder
```

See `Connections.java:25-36`.

`MinecraftConnection.enableEncryption` runs these operations:

```text
addBefore(FRAME_DECODER, CIPHER_DECODER, MinecraftCipherDecoder(key))
addBefore(FRAME_ENCODER, CIPHER_ENCODER, MinecraftCipherEncoder(key))
```

See `MinecraftConnection.java:595-609`.

A second call fails on `CIPHER_DECODER`. Velocity has no key replacement method.

### Focused Netty runtime probe

The probe used `EmbeddedChannel`. It added the framing names and one cipher pair. It then repeated the first `addBefore` call.

Netty `4.2.17.Final` returned:

```text
client-second-decoder=java.lang.IllegalArgumentException: Duplicate handler name: decrypt
after-client-failure=[decrypt, splitter, encrypt, prepender, ...]
velocity-second-decoder=java.lang.IllegalArgumentException: Duplicate handler name: cipher-decoder
after-velocity-failure=[decrypt, cipher-decoder, splitter, encrypt, cipher-encoder, prepender, ...]
```

Netty `4.2.15.Final` returned:

```text
java.lang.IllegalArgumentException: Duplicate handler name: decrypt
[decrypt, splitter, encrypt, prepender, ...]
```

An explicit `replace("decrypt", "decrypt", newHandler)` succeeded in the same `4.2.17.Final` probe.

The generated Velocity JAR embeds Netty `4.2.15.Final`. The generated plugin JAR embeds Netty `4.2.17.Final`. Class-loader order can select one copy at runtime. Both tested versions have the same duplicate-name result.

## Exact Packet And Cipher Sequence

### Actual unmodified sequence

| Step | Direction | Packet or action | Frontend wire state | Result |
| --- | --- | --- | --- | --- |
| 1 | Client to Velocity | Handshake and Login Start | Plaintext | Login protocol starts. |
| 2 | Velocity to client | `H1(S1, P1, C1, A1)` | Plaintext | Listener changes `CONNECTING -> AUTHORIZING`. |
| 3 | Client to Mojang | Optional join with `D1` | HTTPS outside the frontend | This occurs only when `A1` is true. |
| 4 | Client to Velocity | `R1(RSA_P1(K1), RSA_P1(C1))` | Plaintext Minecraft transport | The write completes before AES install. |
| 5 | Both frontend endpoints | Install AES/CFB8 with `K1` | Boundary after `R1` | First encryption starts. |
| 6 | Velocity to client | `H2(S2, P2, C2, A2)` | AES/CFB8 with `K1` | Login codec decodes `H2`. |
| 7 | Client listener | Try `ENCRYPTING -> AUTHORIZING` | No new write | The state method throws. The flow stops. |

No `K2`, `D2`, second join, or `R2` exists in this actual sequence.

### Required counterfactual sequence

This sequence shows the minimum boundary behavior after a client change. It is not stock behavior.

| Step | Direction | Packet or action | Wire state | Owner |
| --- | --- | --- | --- | --- |
| 1 | Target to Velocity | `H2(S2, P2, C2, A2)` | Plaintext backend | Target owns `P2` and `C2`. |
| 2 | Velocity to client | Forward exact `H2` | Frontend `K1` | Velocity encodes the Login packet. |
| 3 | Client to Mojang | Join with `D2` | HTTPS | Client owns the access token. |
| 4 | Client to Velocity | Exact `R2` | Frontend `K1` | Old cipher must protect this complete write. |
| 5 | Velocity | Decode `R2` | End of frontend `K1` frame | Velocity does not know `K2`. |
| 6 | Velocity to target | Forward exact `R2` | Plaintext backend | The RSA response fields do not change. |
| 7 | Client and target | Start AES/CFB8 with `K2` | Boundary after `R2` | Client and target own `K2`. |
| 8 | Velocity | Start byte relay | Opaque `K2` ciphertext | Velocity gives up packet ownership. |

The current Minecraft and Velocity pipelines cannot perform steps 5 to 8. They need explicit replacement or tunnel code.

## Can Velocity Forward The Second Response Unchanged

**Yes, but only in the counterfactual flow.**

This requires `H2` to contain the exact target server ID, public key, challenge, and authentication flag. The client then creates both RSA fields for `P2`. Velocity uses `K1` to decode `R2`. The required custom handoff then stops the frontend `K1` transport layer. The packet fields remain unchanged.

Velocity can encode those same fields on the plaintext backend. The backend write must complete before the target sends `K2` ciphertext.

Velocity cannot decrypt `RSA_P2(K2)`. It does not have the target private key. It therefore cannot call `backend.enableEncryption(K2)`.

If Velocity replaces `P2` with its own key, this unchanged-forwarding result no longer applies. Velocity must reconstruct the target response. Vanilla also computes its session digest with the replacement key. The target does not accept that digest.

The current Mod protocol solves that problem with client logic. It does not solve it for Vanilla.

## Can Velocity Change Its Frontend Cipher At The Same Boundary

The current code cannot do this.

If Velocity knew `K2`, a new replace method could change the decoder after it consumes `R2`. It could change the encoder before it sends the next frontend packet. The method must run on the frontend event loop.

This change also needs a read barrier. A cipher decoder runs before the frame decoder. One network buffer can contain more than one encrypted frame. The old cipher can process all bytes in that buffer before the packet handler sees `R2`. A robust switch must not let post-boundary bytes enter the old cipher.

Vanilla does not normally send another Login packet until the server replies. This reduces the coalescing risk. It is not a general key-switch guarantee.

For the unchanged target response, Velocity does not know `K2`. It must stop cipher termination instead of changing its cipher to `K2`.

## Byte Relay And Packet Proxy

### Byte relay

A byte relay forwards opaque TCP bytes after a defined boundary.

It does not do these operations:

- AES decryption or encryption
- VarInt frame decode or encode
- packet ID decode or encode
- compression decode or encode
- Login, Configuration, or Play state tracking
- packet inspection or packet injection

The client and target own `K2`. They also own frame state, compression state, and protocol state.

### Packet proxy

A packet proxy terminates each encrypted leg. It performs all framing, codec, compression, and state operations.

The current Velocity connection is a packet proxy. Its cipher decoder is before its frame decoder. Its cipher encoder is after frame construction in the outbound flow. See `ServerChannelInitializer.java:59-75`, `BackendChannelInitializer.java:51-63`, and `MinecraftConnection.java:595-607`.

Minecraft uses the same ownership order:

```text
inbound:  decrypt -> frame split -> decompress -> packet decode
outbound: packet encode -> compress -> frame length -> encrypt
```

See `Connection.java:459-469`, `Connection.java:514-516`, and `Connection.java:541-560`.

The encrypted stream includes the frame length. Velocity cannot keep packet framing while it treats `K2` as opaque data.

### Compression ownership

The minimum double-Hello design must not start frontend compression after `R1`. The target can send Set Compression only after `R2`.

In byte relay mode, the target Set Compression packet stays inside the `K2` stream. The client handles it. Velocity does not set a threshold.

In packet proxy mode, Velocity must decode Set Compression and configure each leg. That requires `K2`.

The current patched backend handler owns target compression at `LoginSessionHandler.java:186-189`. The current frontend auth handler owns frontend compression at `AuthSessionHandler.java:198-201`. A byte relay must bypass both paths.

## Current Velocity Patch

The stored patch and generated source implement one decorated Server Hello.

The generated `InitialLoginSessionHandler` receives the target Hello. It replaces only the public key bytes. It then sends that same packet to the client. See `InitialLoginSessionHandler.java:333-366`.

The handler decrypts the Mod response and obtains the client AES key. It enables frontend AES at `InitialLoginSessionHandler.java:197-227`.

The backend handler reconstructs the target RSA response. It writes that response first. It enables backend AES in the write listener. See `LoginSessionHandler.java:291-328`.

This design knows the AES key on both legs. It keeps Velocity as a packet proxy.

The handler classifies a normal Vanilla response and disconnects it. See `InitialLoginSessionHandler.java:211-222`.

The patch has no second frontend Server Hello. It has no cipher replacement. It has no byte relay transition.

## Minimum Mechanism

### Minimum mechanism for an unmodified client

Use one target Server Hello.

1. Velocity opens the target backend before it sends a frontend Server Hello.
2. Velocity forwards the exact target Server Hello to the client.
3. The client creates `K2` and performs the sole target session join.
4. Velocity forwards the exact target key response to the target.
5. Velocity pauses reads on both channels before the target can send encrypted data.
6. Velocity removes or bypasses all Minecraft packet handlers on both channels.
7. Velocity hands all unread bytes to a raw bridge.
8. Velocity resumes reads and forwards opaque bytes in both directions.

This is a new Velocity core tunnel. A plugin API hook is not sufficient.

This mechanism loses packet-proxy features after the handoff. Velocity cannot inspect chat, inject proof messages, track Configuration, run packet commands, switch servers, or keep the target session after the client disconnects.

### Minimum mechanism if two Server Hello packets remain a requirement

Change the client.

The client change must do all these operations:

1. Add an explicit two-round login state.
2. Define whether the second cipher replaces or stacks on the first cipher.
3. Send `R2` through `K1` before the key change.
4. Replace both cipher handlers atomically after that write.
5. Reset AES/CFB8 state with the `K2` bytes as the IV.
6. Reject a third Server Hello.
7. Define one or two Mojang joins through the two authentication flags.

Velocity also needs a boundary-safe cipher or byte relay transition. This is not a Vanilla design.

## Rejected Alternatives

### Send both Server Hello packets quickly

Rejected. The first packet changes the atomic listener state before the second packet enters `handleHello`.

### Wait until the first key response completes

Rejected. The listener state is then `ENCRYPTING`. `AUTHORIZING` does not accept that state.

### Set `A1` to false

Rejected as a complete fix. This avoids the first Mojang join. It does not reset listener state. It does not change cipher installation.

### Install AES twice with the current methods

Rejected. Netty rejects the duplicate handler name. Both local Netty versions gave the same result.

### Use unique names and stack both ciphers

Rejected for Vanilla. Minecraft uses fixed names. A stack also changes the wire contract. It would require client and Velocity changes.

### Forward `R2` and keep the Velocity packet pipeline

Rejected. Velocity cannot decrypt `K2`. The frame length is also encrypted.

### Recover `K2` from `R2`

Rejected. `K2` is encrypted with the target public key. Velocity does not have the target private key.

### Replace the target public key with a Velocity key

Rejected for an unmodified client. The client joins the digest for the replacement key. The target expects its own key. Response fields also need reconstruction.

### Keep the first compression state during the handoff

Rejected. A target can choose a different threshold. A byte relay must leave compression end to end.

### Call a packet proxy a pure proxy

Rejected as ambiguous. A packet proxy terminates encryption and owns packet state. A byte relay does not.

## Failure Modes And Security Risks

- A second Server Hello raises a client listener state error.
- A forced second cipher install raises a Netty duplicate-name error.
- A late key switch can decrypt post-boundary bytes with the wrong key.
- An early key switch can encrypt `R2` with `K2`. Velocity then cannot read it with `K1`.
- A missed unread buffer can lose or duplicate encrypted bytes during a byte relay handoff.
- A packet proxy without `K2` reads random data as a frame length.
- Compression ownership on both Velocity and the endpoints corrupts the stream.
- A byte relay removes Velocity packet validation and packet rate controls after handoff.
- A byte relay prevents Velocity from enforcing packet-level policy after handoff.
- A public-key replacement without a client join change breaks target authentication.
- A live two-join experiment can change external session state. This research did not run that experiment.

## Open Product Decisions

- Decide whether a byte relay is acceptable after login encryption.
- Decide whether Velocity must keep packet inspection and command features.
- Decide whether the Mod remains mandatory for a packet proxy.
- Decide whether one exact target Server Hello can replace the double-Hello idea.
- Decide whether a new Velocity core tunnel is acceptable.
- Decide which disconnect and cleanup behavior applies when a tunnel handoff fails.

None of these decisions makes two Server Hello packets work with an unmodified 26.2 client.

## External References

- Mojang 26.2 client artifact: `https://piston-data.mojang.com/v1/objects/2dc72797acbc1b63fc16a11c4ac393605f453754/client.jar`
- Pinned Velocity source commit: `https://github.com/PaperMC/Velocity/tree/843a47e2a38325309cd66133149fc9a984f76bb8`
- Netty `4.2.15.Final` transport artifact: `https://repo1.maven.org/maven2/io/netty/netty-transport/4.2.15.Final/netty-transport-4.2.15.Final.jar`
- Netty `4.2.17.Final` transport artifact: `https://repo1.maven.org/maven2/io/netty/netty-transport/4.2.17.Final/netty-transport-4.2.17.Final.jar`
- Netty pipeline source: `https://github.com/netty/netty/blob/netty-4.2.17.Final/transport/src/main/java/io/netty/channel/DefaultChannelPipeline.java`

These are primary artifacts or primary project source.

## Related Specs

- `.trellis/spec/backend/velocity-plugin.md` - defines the current patched Velocity login relay and packet-proxy contract.
- `.trellis/spec/frontend/fabric-client-mod.md` - defines the current client Mod login hooks.
- `.trellis/spec/guides/cross-layer-thinking-guide.md` - requires explicit ownership across the client, proxy, and target boundaries.

## Caveats / Not Found

- No full client-to-target double-Hello run was made. The official client state guard stops the flow before a second key exists.
- No live second Mojang join was made. The result of two accepted join requests is outside the reachable Vanilla flow.
- The byte relay handoff is an inferred minimum design. The current code does not implement it.
- The probe tested pipeline name behavior. It did not test native Velocity cipher providers.
- The generated Velocity server JAR contains Netty `4.2.15.Final`. The plugin JAR contains Netty `4.2.17.Final`. Both versions reject the duplicate names.
- The verdict is specific to Minecraft Java 26.2 and the pinned Velocity source.
