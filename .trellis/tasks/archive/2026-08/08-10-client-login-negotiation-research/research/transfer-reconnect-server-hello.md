# Research: Transfer to a new login connection

> This report records evidence and evaluated options. The current `prd.md` and
> `design.md` own product requirements and replace earlier product decisions.

- Query: Can a server Transfer give an unmodified Minecraft 26.2 client a fresh Server Hello while keeping a controlled proxy path?
- Scope: Minecraft 26.2, the pinned Velocity source, the current relay patch, and three second-endpoint designs.
- Date: 2026-08-10

## Verdict

**Conditionally feasible with a new connection and a fixed raw tunnel.**

Transfer removes the Track A listener blocker. Minecraft closes the first
connection. It creates a new `Connection` and a new
`ClientHandshakePacketListenerImpl`. The new listener starts in `CONNECTING`.
It can process one target Server Hello normally.

Transfer does not make a normal Velocity packet proxy viable for Vanilla. The
second AES key belongs to the client and target. A middle endpoint that does not
know that key can forward bytes only.

A direct target connection is the smallest data path. It requires the target to
set `accepts-transfers=true`. That setting is false by default.

A fixed raw tunnel can support an unchanged online target. The tunnel consumes
the second handshake, changes the intent from `TRANSFER` to `LOGIN`, and then
forwards opaque bytes. It must route only to a configured target.

The current Mod path can remain separate. A Vanilla response can authenticate
to the first proxy and receive Transfer. An accepted Mod response can continue
on the current packet-proxy relay.

## Evidence Target

The Minecraft evidence uses these local Loom artifacts:

- Binary SHA-256: `1463A746E967BAA2393530DEA69B0DA3C46838935B2A63C38843C1325F2BDEEB`
- Source SHA-256: `E1AAA82F91A79407D2828D2057F29F4C0009A559CC713B248AB71D04372B8DA3`
- Binary: `E:/Gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.2/minecraft-merged-deobf-26.2.jar`
- Source: `E:/Gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.2/minecraft-merged-deobf-26.2-sources.jar`

The generated source uses official names. The matching bytecode is the runtime
authority.

The Velocity evidence uses commit
`843a47e2a38325309cd66133149fc9a984f76bb8`. The generated checkout is under
`plugin/build/server/source/`. The stored project patch changes that checkout.

## Terms

- `gateway` is the first patched Velocity connection.
- `second endpoint` is the address in the Transfer packet.
- `target` is the ordinary online-mode Minecraft server.
- `K1` is the AES key for the first gateway connection.
- `K2` is the AES key for the new target login.
- `packet proxy` terminates AES and owns Minecraft packet state.
- `raw tunnel` forwards opaque TCP bytes after a small plaintext bootstrap.
- `pending backend` is the target connection that supplied the decorated first Server Hello.

## Exact Client Transfer Flow

Minecraft registers `ClientboundTransferPacket` in Configuration and Play. It
does not register that packet in Login.

- Configuration registration is at `ConfigurationProtocols.java:60`.
- Play registration is at `GameProtocols.java:262`.
- The packet contains only `host` and `port` at `ClientboundTransferPacket.java:8-27`.

The earliest protocol-safe send point is Configuration. The gateway must first
complete Login and receive the client Login Acknowledged packet. It does not
need to complete Configuration or enter Play.

`ClientCommonPacketListenerImpl.handleTransfer` performs this sequence at
`ClientCommonPacketListenerImpl.java:307-326`:

1. Set `isTransferring` to true.
2. move packet work to the game thread.
3. reject a single-player source.
4. disconnect the old connection with `disconnect.transfer`.
5. set the old connection to read-only.
6. process the old disconnection immediately.
7. create a `ServerAddress` from the packet host and port.
8. create `TransferState` from cookies and selected client state.
9. call `ConnectScreen.startConnecting`.

`TransferState` contains the cookie map, seen players, and the insecure-chat
warning state. See `TransferState.java:10-11`.

`ConnectScreen` uses a transfer-specific failure title. It then creates a new
`Connection` at `ConnectScreen.java:58-82` and `:124`.

The new connection gets a new `ClientHandshakePacketListenerImpl` at
`ConnectScreen.java:141-159`. That listener initializes its state to
`CONNECTING` at `ClientHandshakePacketListenerImpl.java:74`.

The second connection has a new Netty pipeline. It has fresh framing handlers
and no AES handlers. The fixed handler names from Track A do not conflict with
handlers on the closed first connection.

`ConnectScreen` passes `transferState != null` into the connection initializer.
`Connection.initiateServerboundPlayConnection` maps that value to
`ClientIntent.TRANSFER` at `Connection.java:250-254`.

The client then sends a normal `ServerboundHelloPacket`. The second endpoint
can return one normal Server Hello. The new listener accepts it from
`CONNECTING` and creates `K2`.

## Minimum First-Connection Sequence

The hybrid gateway needs this first sequence:

```text
Vanilla client                  patched gateway             pending target
      |                               |                           |
      | -- LOGIN handshake + Hello ->| -- LOGIN handshake ------>|
      |                               | -- Hello ---------------->|
      |                               | <- target Server Hello ---|
      | <- decorated Server Hello ----|                           |
      |    join gateway digest        |                           |
      | -- standard key response ---->|                           |
      |    enable frontend AES(K1)    |                           |
      |                               | close pending backend     |
      | <- Login Finished ------------|                           |
      | -- Login Acknowledged -------->|                           |
      | <- Transfer(tunnel host,port)-|                           |
      | close first connection        |                           |
```

The first gateway cannot send Transfer during Login. The client would not have
a Transfer packet codec in that state.

The first gateway must also distinguish Vanilla from the Mod. The current relay
already does this after RSA decryption.

`FakePlayerProxyRelay.classifyResponse` returns `VANILLA` when the response
equals the unchanged target challenge. See
`FakePlayerProxyRelay.java:118-140`.

The current handler enables frontend AES and then rejects that result. See
`InitialLoginSessionHandler.java:200-222`. A later design can replace only this
Vanilla branch with a valid first Login completion and Transfer.

The first Login must reach Configuration. The protocol does not require the
gateway to query Mojang before it sends Login Finished. Standard online-mode
Velocity does make that query. A redirect-only gateway can use a different
first-login policy because the online target verifies the second login.

The Mod returns the acknowledgement. That branch can keep the pending backend
and the current packet-proxy flow.

## Second Handshake Intent

The new client connection sends intent `TRANSFER`, whose protocol value is `3`.
Velocity declares that value at `StateRegistry.java:881-883`.

Velocity maps `LOGIN` and `TRANSFER` to its Login state. It rejects a Transfer
intent when `advanced.accepts-transfers` is false. See
`HandshakeSessionHandler.java:89-121`.

Velocity sets `accepts-transfers` to false by default. See
`default-velocity.toml:162-164` and `VelocityConfiguration.java:815`.

Minecraft dedicated servers have the same default. The server rejects intent
`TRANSFER` when `MinecraftServer.acceptsTransfers()` is false. See
`ServerHandshakePacketListenerImpl.java:40-48`.

`DedicatedServerProperties` reads `accepts-transfers` with default `false` at
`DedicatedServerProperties.java:131`.

When a target accepts Transfer, it enters the normal Login protocol. It passes a
`transferred=true` flag into `ServerLoginPacketListenerImpl`. Its online-mode
Server Hello and session authentication remain normal.

## Endpoint Comparison

| Second endpoint | Target change | AES owner after login | Velocity packet features | Verdict |
| --- | --- | --- | --- | --- |
| Direct target | Set `accepts-transfers=true` | client and target | none | feasible when target allows Transfer |
| Fixed raw tunnel | none when tunnel rewrites intent | client and target | none after handoff | recommended Vanilla path |
| Normal Velocity packet proxy | enable `accepts-transfers` | client and second Velocity | frontend only | not enough for an ordinary online target |

### Direct target

The gateway sends the target host and port in Transfer. The client connects
directly. The target sees the real client source address.

The target must accept intent `TRANSFER`. An unchanged default configuration
rejects the connection before Login.

After acceptance, the target sends its own Server Hello. The client creates
`K2`, performs the target session join, and sends the target key response. The
target owns the matching private key and learns `K2`.

Velocity leaves the path. It cannot provide commands, packet inspection,
packet injection, proof messages, server switching, or later proxy tasks.

### Fixed raw tunnel

The gateway transfers the client to a fixed tunnel host and port. The tunnel
accepts the new TCP connection.

The tunnel must consume the client intention packet. It must send a replacement
intention packet with `LOGIN` to an unchanged target. It then forwards the
client Hello.

The target returns its exact Server Hello. The tunnel forwards that packet
without changing its public key, challenge, server ID, or authentication flag.

After the tunnel forwards that Server Hello, it can become a raw byte relay.
The client key response is still plaintext transport data with RSA-encrypted
fields. The tunnel forwards it unchanged.

The target enables AES with `K2`. The client enables AES with the same key after
the response write. Later Login, Configuration, and Play bytes stay opaque to
the tunnel.

The tunnel does not need target AES, packet IDs, compression state, or registry
state after this handoff. It only owns TCP lifetime and backpressure.

The target sees the tunnel source address. A target that enforces proxy-source
matching can reject this path.

### Normal Velocity packet proxy

A second Velocity endpoint can accept intent `TRANSFER` when its setting is
true. It treats that connection as a new Login connection.

If Velocity sends its own Server Hello, it learns the frontend `K2`. That key
does not authenticate or decrypt a different online target connection.

Stock Velocity rejects an online-mode backend Server Hello. The current patch
solves that case only when the Mod changes the response and the session digest.

An unmodified client on the second connection has the same limitation as the
first connection. Sending it through the same policy again can create a
Transfer loop.

A Velocity listener can implement the fixed raw tunnel. That listener is not a
normal Velocity packet proxy after handoff.

## AES And Session Join Ownership

The hybrid Vanilla path uses two independent logins.

On the first connection, Vanilla sees the decorated proxy key as a valid RSA
key. It creates `K1` and joins the digest for that decorated gateway Hello. The
gateway decrypts `K1` with its matching private key.

If the gateway verifies that join, it must finish the check before Transfer.
The second join can then replace the first client-side join record without
racing the gateway check. A redirect-only policy can skip the gateway check,
but the client still made the first join call.

On the second connection, the target sends a new Server Hello. Vanilla creates
`K2` and joins the target digest. The target verifies that digest.

`K1` and its cipher pipeline end with the first connection. The second
connection never reuses `K1`.

`K2` belongs only to the client and target for direct and raw-tunnel paths. The
raw tunnel does not need or receive it.

The source proves both independent client login paths. A live authenticated
probe must still confirm two sequential Mojang joins with a real account.

## Compression And Packet Boundaries

The second connection starts without compression and AES. The tunnel can parse
the initial handshake and client Hello with normal Login framing.

The tunnel should stop packet decoding after it forwards the target Server
Hello. It can then copy bytes in both directions.

The target can send Login Compression after it verifies the key response. That
packet is inside the target AES stream. The raw tunnel forwards it without
tracking the compression boundary.

This handoff avoids the duplicate cipher-handler problem. The tunnel never
installs Minecraft cipher handlers.

## Raw Tunnel Backend Choices

### Close the pending backend and reconnect

This is the minimum design.

The first proxy needs a pending target connection before it knows the client
type. The target Hello supplies the carrier data for the Mod branch.

When the response is Vanilla, the gateway closes that pending backend. It then
finishes the gateway login and transfers the client.

The tunnel opens a new target connection after the client arrives. It sends the
rewritten Login handshake and the client's Hello. The target creates a fresh
challenge.

Vanilla ignored the embedded target key on the first connection. Therefore the
closed target Hello does not bind the second Vanilla login.

This option creates two short target TCP attempts for a Vanilla client. It has
simple ownership and cleanup.

### Retain and pair the pending backend

This option is conditionally feasible but is not minimal.

The gateway can keep the target connection after it receives the target Hello.
The target waits for its key response.

The gateway then transfers Vanilla with a single-use route token. The tunnel
consumes the second client handshake and Hello. It pairs that client connection
with the waiting backend.

The tunnel sends the saved target Hello to the new client. It then starts raw
relay. It must not send the second client Hello to the target because the target
already received one.

The username and profile ID on both Hello packets must match. The token must
bind one client, one pending backend, and one target.

This option saves one target connection. It requires channel detachment,
cross-listener ownership transfer, timeout coordination, and race-free cleanup.

The target Login timeout starts before the first gateway login finishes. The
target allows 600 login ticks in Minecraft 26.2. The client and Velocity default
read timeouts are also 30 seconds.

The retained option has a smaller failure budget. It also creates more resource
retention and denial-of-service risk.

Use the reconnect option first. Reconsider pairing only after measured
connection cost justifies the extra state.

## Routing And Open-Proxy Control

The raw tunnel must not accept a client-selected target address.

For one configured target, use one fixed listener that always connects to that
target. This is the smallest safe route.

For multiple targets, use a server-side route map. A route token must be random,
short-lived, single-use, and bound to a configured target.

Minecraft carries stored cookies into `TransferState`. The second login listener
can answer a Login cookie request. See
`ClientHandshakePacketListenerImpl.java:95` and `:245-247`.

A cookie route requires the tunnel to implement a small Login bootstrap. It
must request the cookie, consume the response, resolve the server-side route,
and only then connect to the configured target.

Do not put an arbitrary host and port inside the token. The map must hold the
target server-side.

Do not rely only on the transferred hostname as a token. The client resolves
DNS and can follow an SRV redirect before it builds the second intention packet.
The handshake hostname can therefore differ from the Transfer packet hostname.

Bind a token to the first profile ID when the gateway authenticated it. IP
binding can help, but it must account for NAT and address changes.

## Failure And Timeout Behavior

The old connection is already closed when the second connection starts. The
client cannot fall back to it.

If DNS resolution fails, `ConnectScreen` shows an unknown-host disconnect
screen. If the TCP connection fails, it shows the transfer-specific failure
title with a generic connection reason.

The new Minecraft connection installs a 30-second read timeout. The pending
target Login listener also limits Login to 600 ticks.

Velocity defaults to a 5-second outbound connection timeout and a 30-second
read timeout. See `default-velocity.toml:127-131`.

Velocity also defaults to a 3-second login rate limit. Its handshake path runs
the IP limiter for Login and Transfer. A Transfer back into the same normal
Velocity instance can hit that limit.

A dedicated raw listener should use its own bounded admission policy. A normal
Velocity endpoint needs a specific Transfer exemption or a compatible rate
limit setting.

The tunnel must close both channels when either side closes. It must cap pending
routes, pending bytes, and pending time.

The tunnel must remove a token on success, timeout, disconnect, or failed target
connect. A repeated token must fail closed.

Minecraft has no cross-server Transfer hop counter. A server can transfer the
client again. The gateway must use a one-shot route state to stop accidental
loops.

DNS changes can send a later connection to another tunnel node. Route state
must be shared across those nodes or the Transfer address must remain node
specific.

## Hybrid Vanilla And Mod Branch

One decorated first Server Hello can support both branches.

```text
                         decrypted response challenge
                                      |
                    +-----------------+-----------------+
                    |                                   |
              exact challenge                    FPPACK + challenge
                    |                                   |
                 Vanilla                                Mod
                    |                                   |
       authenticate first gateway             wait for user consent
                    |                                   |
          enter Configuration                  accepted response only
                    |                                   |
       Transfer to fixed tunnel            keep current packet proxy
                    |                                   |
        fresh target Server Hello           reuse K on held backend
```

The Vanilla branch changes the current explicit rejection. It does not change
how Vanilla constructs its first key response.

The Mod branch must show consent before Minecraft creates and sends its key.
Track B defines that gate. A rejected Mod connection closes without Transfer.

The raw tunnel branch loses packet-level Velocity features after Transfer. The
Mod branch keeps those features because Velocity knows the relayed key.

## Rejected Alternatives

### Send Transfer during Login

The client has no Login codec for this packet. The first connection must enter
Configuration or Play.

### Transfer directly to an unchanged target

The target rejects the `TRANSFER` intent because its setting defaults to false.

### Forward intent `TRANSFER` through a raw tunnel unchanged

This still requires the target setting. Rewrite only the initial intention to
`LOGIN` when the target must remain unchanged.

### Transfer back to normal Velocity and keep packet proxy behavior

The second Velocity does not learn the target connection key. Stock Velocity
still rejects the online backend. The current relay still needs the Mod.

### Use an arbitrary target from the client handshake

This creates an unauthenticated open proxy. Use a fixed route or a server-side
allowlist mapping.

### Retain every pending backend by default

This adds channel handoff and timeout state before measurement shows a need.
The reconnect path is smaller and easier to close correctly.

## Minimum Runtime Probe

Use one real Minecraft 26.2 Vanilla client, one gateway, one fixed tunnel, and
one controlled online-mode target.

1. Let the pending target send its Server Hello to the gateway.
2. Send the decorated Server Hello to the Vanilla client.
3. Confirm that the response classifies as `VANILLA`.
4. Finish first gateway authentication and frontend AES with `K1`.
5. Enter Configuration and send one Transfer packet.
6. Capture the old connection close.
7. Capture a new TCP connection and intention value `3`.
8. Rewrite only that intention to `LOGIN` for the target.
9. Forward the second client Hello.
10. Forward the exact target Server Hello to the client.
11. Confirm a second Mojang join for the target digest.
12. Forward the target key response unchanged.
13. Switch the tunnel to raw bidirectional copying.
14. Confirm the target reaches Play.
15. Send a target system message and confirm that the client receives it.
16. Confirm that the gateway cannot decode second-connection packets.

The probe must observe packet and cipher boundaries. A source-text assertion is
not a substitute for this result.

Run two focused failure cases:

1. Use a target with `accepts-transfers=false` and no intent rewrite. Confirm its explicit rejection.
2. Reuse or expire a route token. Confirm that the tunnel does not connect to any target.

## Open Product Decisions

1. Decide whether the Vanilla branch may lose all packet-level Velocity features after Transfer.
2. Decide whether the target can enable `accepts-transfers`.
3. If the target stays unchanged, approve a fixed raw tunnel and one handshake-intent rewrite.
4. Decide whether initial scope has one fixed target or multiple token-routed targets.
5. Decide whether the first gateway must finish online authentication before Transfer.
6. Decide whether two sequential Mojang joins are acceptable.
7. Decide whether the reconnect option is sufficient for the first implementation.
8. Decide how the client reports a failed Transfer after the old connection has closed.

## Related Files

- `.trellis/tasks/08-10-client-login-negotiation-research/prd.md`
- `.trellis/tasks/08-10-client-login-negotiation-research/research/vanilla-double-server-hello.md`
- `.trellis/tasks/08-10-client-login-negotiation-research/research/mod-consent-before-key.md`
- `plugin/patch/0001-server-hello-marker.patch`
- `mod/src/main/java/com/fakeplayerproxy/mod/mixins/MixinClientHandshakePacketListenerImpl.java`
- `mod/src/main/java/com/fakeplayerproxy/mod/packets/ServerHelloPacketEnvelope.java`

## Caveats

- No Transfer tunnel implementation exists in this task.
- No live Vanilla Transfer probe ran in this task.
- The two sequential Mojang joins need a real account probe.
- The pending-backend pairing option is a source-based design inference.
- The normal Velocity verdict applies to the pinned source and an online target.
- This research does not approve implementation.
