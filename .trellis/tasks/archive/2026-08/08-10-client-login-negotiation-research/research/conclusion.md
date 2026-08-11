# Research: Three-track client login negotiation conclusion

- Query: Do the three login reports agree, and what must the user decide before technical design?
- Scope: internal
- Date: 2026-08-10

## Approved Planning Correction

The user rejected the HEAD cancellation and `handleHello` re-entry design. The
approved boundary occurs after Minecraft creates `K` and both ciphers. It occurs
before session authentication and the key response send.

At this boundary, the proxy key, original challenge, proxy digest, `K`, and both
ciphers are live. One local-capture injection can prepare both consent choices.
The task `prd.md` and `design.md` define the approved flow.

Envelope decoding has two results. A decoded target key starts consent. An
empty result keeps Minecraft's original login path. Both unmarked and malformed
envelope data produce the empty result.

Use the earlier Mod report only for source order and bytecode evidence. Do not
use its HEAD re-entry recommendation.

## Findings

### Files Found

- `.trellis/tasks/08-10-client-login-negotiation-research/prd.md` - defines all three research tracks and keeps implementation out of scope.
- `.trellis/tasks/08-10-client-login-negotiation-research/research/vanilla-double-server-hello.md` - proves the stock client and current Velocity cannot complete two Server Hello exchanges.
- `.trellis/tasks/08-10-client-login-negotiation-research/research/mod-consent-before-key.md` - defines the consent gate and its open runtime questions.
- `.trellis/tasks/08-10-client-login-negotiation-research/research/transfer-reconnect-server-hello.md` - proves that Transfer creates a fresh login connection and compares three second endpoints.
- `mod/src/main/java/com/fakeplayerproxy/mod/mixins/MixinClientHandshakePacketListenerImpl.java` - owns the current Server Hello hooks.
- `mod/src/main/java/com/fakeplayerproxy/mod/packets/ServerHelloPacketEnvelope.java` - validates the marker and extracts the target key.
- `plugin/build/server/source/proxy/src/main/java/com/velocitypowered/proxy/connection/client/InitialLoginSessionHandler.java` - handles the frontend key response.
- `plugin/build/server/source/proxy/src/main/java/com/velocitypowered/proxy/connection/backend/LoginSessionHandler.java` - relays the target Server Hello and target key response.
- `plugin/build/server/source/proxy/src/main/java/com/velocitypowered/proxy/connection/MinecraftConnection.java` - installs the fixed-name Velocity cipher handlers.

### Common Terms

- `Server Hello` means Minecraft `ClientboundHelloPacket`. Velocity calls the same packet `EncryptionRequestPacket`.
- `key response` means Minecraft `ServerboundKeyPacket`. Velocity calls the same packet `EncryptionResponsePacket`.
- `K` means the one client-generated AES secret in the Mod branch.
- `K1` and `K2` mean the first and second AES secrets in Tracks A and C. Track A puts them on one attempted connection. Track C puts them on two connections.
- `transport plaintext` can still contain RSA-encrypted fields.
- `packet proxy` terminates AES and owns packet state.
- `byte relay` forwards opaque TCP bytes and does not own packet state.

These terms make the three reports consistent. The source names also match the current handlers at `InitialLoginSessionHandler.java:188` and `LoginSessionHandler.java:110`.

### State And Cipher Audit

The reports agree on the state sequence and the cipher boundary.

For the stock two-Server-Hello flow, the first Server Hello changes `CONNECTING` to `AUTHORIZING`. The first key response starts the change from `AUTHORIZING` to `ENCRYPTING`. The client writes that response as transport plaintext. It enables AES with `K1` only after the write completes. A second Server Hello then arrives under `K1`. The listener tries to change `ENCRYPTING` to `AUTHORIZING`. It throws before it creates `K2` or a second key response. See `vanilla-double-server-hello.md:300-314`.

For Mod consent, Vanilla first changes its state to `AUTHORIZING`. It creates
`K`, both ciphers, and the proxy digest. The approved injection then pauses the
method before authentication and the key response send. Vanilla's state guard
rejects a second Server Hello while consent is open.

The Mod flow therefore uses the first-exchange boundary from the Vanilla report. It does not use the rejected second-exchange boundary. The current Velocity cipher method also uses fixed handler names and `addBefore` at `MinecraftConnection.java:595-607`.

Transfer does not reuse that connection. It closes the first connection. It creates a new `Connection`, a new listener in `CONNECTING`, and a fresh Netty pipeline. The second connection can accept one target Server Hello and install one new cipher pair. This result is consistent with Track A because the handler names are unique inside each pipeline. See `transfer-reconnect-server-hello.md:58-104`.

### Security Audit

The security claims agree.

- The marker proves only that a Server Hello has the FakePlayerProxy protocol shape.
- The marker does not authenticate the server, proxy, or target.
- The socket peer identifies a transport endpoint. It does not identify an operator.
- The Mojang target digest authenticates the client session to the target. It does not authenticate the target to the client.
- `K` protects packet transport. It is not an authentication credential.
- Consent gives the user a choice. Consent does not make the marker trusted.

See `mod-consent-before-key.md:423-476`, `vanilla-double-server-hello.md:480-507`, and `.trellis/spec/backend/velocity-plugin.md:234-245`.

### Direct Verdicts

**Track A:** Two Server Hello packets are not feasible for an unmodified Minecraft 26.2 client with the pinned Velocity source. The listener state guard is the first blocker. The fixed-name cipher handlers are an independent blocker. A custom client and a Velocity core change could make a different protocol. That result does not satisfy the Vanilla requirement. See `vanilla-double-server-hello.md:7-17`.

**Track B:** Consent before authentication and the key response send is feasible
for the Minecraft 26.2 Mod. The approved flow uses one local-capture injection
before `ServerboundKeyPacket` construction. It does not call `handleHello`
again. A real client login test must verify this boundary.

**Track C:** Transfer is conditionally feasible for an unmodified Minecraft 26.2 client. The recommended Vanilla path uses a new connection and a fixed raw tunnel. Transfer removes the listener and duplicate-handler blockers. It does not let a normal Velocity packet proxy learn the target AES key. See `transfer-reconnect-server-hello.md:7-29`.

### Effect On The Protocol Plan

Do not use two Server Hello packets on one connection for unmodified Vanilla support. Use one decorated first Server Hello to select one of two branches.

### Exact Hybrid Branches

**Vanilla branch:** The client creates `K1` for the decorated gateway Server Hello. It joins the gateway digest. Its key response contains the exact target challenge. The gateway classifies the response as `VANILLA`. In the minimum design, it closes the pending target connection. It completes a valid first Login, sends Login Finished, receives Login Acknowledged, and enters Configuration. Standard online-mode Velocity verifies the first join. A redirect-only policy can skip that check. The gateway then sends Transfer to a fixed tunnel. The client closes the first connection and opens a second connection with intent `TRANSFER`. The tunnel rewrites only that intent to `LOGIN`. It opens a new target connection and forwards the second client Hello. It forwards the exact target Server Hello. It then becomes a raw byte relay. The client creates `K2`, joins the target digest, and sends the exact target key response. Only the client and target learn `K2`. See `transfer-reconnect-server-hello.md:106-147` and `:396-425`.

**Mod branch:** Vanilla creates one `K`, both ciphers, and the proxy digest. The
Mod pauses before authentication and the key response send.

Allow selects the target digest and the `FPPACK` response. The gateway keeps the
pending backend and remains a packet proxy.

Decline selects the proxy digest and original-challenge response. The gateway
classifies that response as Vanilla. It then uses the Transfer raw-tunnel path.

Escape sends no key response. It closes the current connection and returns to
the multiplayer screen.

The current `prd.md` replaces the original negative-response and HEAD re-entry
recommendations. The research still supplies the call-order and Transfer
evidence.

The Vanilla branch loses packet-level Velocity features after Transfer. The Mod branch keeps them. No researched branch gives an unmodified Vanilla client and target-key packet ownership to Velocity.

### Three Endpoint Tradeoffs

| Second endpoint | Required change | `K2` owner | Velocity features | Verdict |
| --- | --- | --- | --- | --- |
| Direct target | Target sets `accepts-transfers=true` | Client and target | None | Smallest data path. The target sees the client address. |
| Fixed raw tunnel | Tunnel rewrites `TRANSFER` to `LOGIN` | Client and target | None after handoff | Recommended Vanilla path. The target can stay unchanged. The target sees the tunnel address. |
| Normal Velocity packet proxy | Second Velocity accepts Transfer | Client and second Velocity on the frontend only | Frontend packet ownership only | Not sufficient for an ordinary online target without the Mod. It can also create a Transfer loop. |

See `transfer-reconnect-server-hello.md:167-232`.

### Verified Limits

- Minecraft registers Transfer in Configuration and Play. It does not register Transfer in Login.
- The earliest legal Transfer point is Configuration after Login Acknowledged. The gateway does not need to complete Configuration.
- Transfer closes the old connection. The client cannot fall back to it after a second-connection failure.
- The Transfer packet contains only a host and port. The client keeps cookies, seen-player state, and its insecure-chat warning in local `TransferState`.
- The second connection sends intent value `3`. Velocity and a dedicated target reject this intent by default.
- A new connection has a new listener in `CONNECTING`. It also has fresh framing handlers and no AES handlers.
- A raw tunnel must consume the second handshake. It must use a fixed target or a server-side route map. A client-selected target creates an open proxy.
- The raw tunnel can stop packet decoding after it forwards the exact target Server Hello. It then owns only TCP lifetime and backpressure.
- The Vanilla client makes two sequential session joins. The first uses `K1` and the gateway digest. The second uses `K2` and the target digest. The gateway can choose whether to verify the first join.
- Default limits include a 30-second client read timeout, a 30-second Velocity read timeout, a 5-second Velocity outbound connect timeout, a 3-second Velocity login rate limit, and a 600-tick target Login limit.

These are source and bytecode results for Minecraft 26.2 and the pinned Velocity source. The complete Transfer path still needs a runtime probe. See `transfer-reconnect-server-hello.md:58-165`, `:234-271`, and `:326-389`.

This section records research evidence. The current `prd.md` selects the fixed
raw tunnel and consent behavior. The current `implement.md` owns all required
validation.

### Historical Probe Inventory

These probes compare the researched options. They are not additional task
requirements. Do not run them unless the current `implement.md` requires them.

**Vanilla Transfer probe:** Use one real Minecraft 26.2 Vanilla client, one gateway, one fixed tunnel, and one controlled online target. Confirm `VANILLA` classification and gateway AES with `K1`. Send Transfer from Configuration. Confirm the old connection closes. Confirm the new connection uses intent `3`. Rewrite only that intent. Forward one exact target Server Hello and the exact target key response. Confirm the second target join, raw relay, and Play. Send one target system message and confirm that the client receives it. Confirm that the gateway cannot decode second-connection packets. See `transfer-reconnect-server-hello.md:453-476`.

**Transfer failure probes:** The research compared rejection without intent
rewrite and a route-token design. The approved task uses neither path. See
`transfer-reconnect-server-hello.md:478-481`.

**Mod consent probe:** Send one valid decorated Server Hello to a real Minecraft
26.2 Mod client. Keep consent pending. Confirm that no key response leaves the
client.

Select Allow once. Confirm that exactly one Mod response leaves the client.
Confirm that AES starts after the write completes. Confirm that the target
reaches Play.

Select Decline once. Confirm that one Vanilla response enters the Transfer
raw-tunnel branch.

Open consent again and press Escape. Confirm that no key response leaves.
Confirm that the client returns to the multiplayer screen.

Track A needs no larger probe for its current verdict. Its state blocker and handler-name blocker already have source, bytecode, and focused runtime evidence.

### Product Decisions After Research

The current `prd.md` resolves the product decisions that this research found.
It is the authority for client support, the fixed raw tunnel, routing, consent,
and error behavior. In particular, Decline uses the Vanilla Transfer branch.
Escape closes the current connection and returns to the multiplayer screen.

### External References

- Minecraft 26.2 client artifact, protocol 776 - identified and checked in the reports.
- Velocity commit `843a47e2a38325309cd66133149fc9a984f76bb8` - the pinned proxy source for the reports.
- Netty `4.2.15.Final` and `4.2.17.Final` - the tested handler-name behavior in Track A.
- Sponge Mixin cancellable injection contract - the control boundary used by Track B.
- Minecraft and Velocity Transfer settings - the local 26.2 and pinned source define intent `3` and default `accepts-transfers=false`.

The reports contain the primary URLs and artifact hashes. This conclusion adds no new external claim.

### Related Specs

- `.trellis/spec/frontend/fabric-client-mod.md` - owns the standard packet, target join, and Minecraft-owned AES contracts.
- `.trellis/spec/backend/velocity-plugin.md` - owns the current decorated Server Hello and packet-proxy contracts.
- `.trellis/spec/guides/cross-layer-thinking-guide.md` - requires explicit ownership at the client, Velocity, and target boundaries.

## Caveats / Not Found

- No contradiction was found among the three reports for packet names, state changes, cipher boundaries, Transfer behavior, or security claims.
- No consent implementation exists. The recommended Mod flow still needs the runtime probe above.
- No Transfer tunnel implementation or live Vanilla Transfer probe exists.
- Two sequential Mojang joins still need a real-account probe.
- Retaining and pairing the pending backend is a source-based inference. Reconnect is the minimum reported design.
- The Track A verdict is specific to Minecraft 26.2 and the pinned Velocity source.
- The useful consent period depends on client, Velocity, and target timeout settings. It is not a fixed 30-second product promise.
- This conclusion does not change the PRD, any source report, product code, patch, or specs.
