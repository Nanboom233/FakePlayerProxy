# Research: Server Hello envelope capacity

- Query: Can a modified Minecraft 26.2 Server Hello carry a protocol header, the target server's original RSA public key, and its original challenge, and can a standard ServerboundKeyPacket return the acknowledgement plus the client-generated AES secret `K`?
- Scope: internal (Minecraft 26.2 official-named generated sources, locally cached MCProtocolLib 26.2 sources, and pinned Velocity commit `843a47e2a38325309cd66133149fc9a984f76bb8`)
- Date: 2026-08-10

## Findings

### Files found

- `E:/Gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.2/minecraft-merged-deobf-26.2-sources.jar` - locally generated official-named Minecraft 26.2 sources.
- `build/tmp/mcprotocollib-sources.jar` - locally cached MCProtocolLib 26.2 sources used as a corroborating implementation.
- `build/velocity-patch-check-worktree` - local Velocity worktree at the exact pinned base commit; `git rev-parse HEAD` returned `843a47e2a38325309cd66133149fc9a984f76bb8`.
- `build/velocity-patch-check-worktree/proxy/src/main/java/com/velocitypowered/proxy/protocol/packet/EncryptionRequestPacket.java` - Velocity's ClientboundHello/EncryptionRequest codec.
- `build/velocity-patch-check-worktree/proxy/src/main/java/com/velocitypowered/proxy/protocol/packet/EncryptionResponsePacket.java` - Velocity's ServerboundKey/EncryptionResponse codec.
- `build/velocity-patch-check-worktree/proxy/src/main/java/com/velocitypowered/proxy/VelocityServer.java` - Velocity RSA key generation size.
- `build/velocity-patch-check-worktree/proxy/src/main/java/com/velocitypowered/proxy/crypto/EncryptionUtils.java` - Velocity RSA key generation and decryption path.
- `.trellis/tasks/06-12-fake-player-proxy-research/research/server-hello-marker.md` - prior finding about trailing fields and Vanilla's complete packet decoding rule.

### Exact Minecraft 26.2 packet codecs

Minecraft 26.2 `ClientboundHelloPacket` contains, in order, `serverId`, `publicKey`, `challenge`, and `shouldAuthenticate`. Its constructor calls `readUtf(20)`, then **parameterless** `readByteArray()` for both byte arrays, then `readBoolean()` (`...sources.jar!/net/minecraft/network/protocol/login/ClientboundHelloPacket.java:27`). Its writer uses `writeByteArray` for both arrays (`ClientboundHelloPacket.java:34`).

Minecraft 26.2 `ServerboundKeyPacket` contains two byte arrays, `keybytes` and `encryptedChallenge`. Its decoder uses parameterless `readByteArray()` for each and its writer uses `writeByteArray()` for each (`...sources.jar!/net/minecraft/network/protocol/login/ServerboundKeyPacket.java:24`, `:29`). The normal public constructor encrypts `secretKey.getEncoded()` and the entire supplied `challenge` separately (`ServerboundKeyPacket.java:19`).

In 26.2, parameterless `FriendlyByteBuf.readByteArray()` delegates to `readByteArray(input, input.readableBytes())`; the explicit overload only rejects a declared array length above that supplied maximum (`...sources.jar!/net/minecraft/network/FriendlyByteBuf.java:274`, `:296`). Therefore these four packet byte-array fields have **no field-specific fixed maximum in Mojang's 26.2 codec**. They remain bounded by the bytes left in the framed packet and by the connection's outer packet/frame limit. This is different from saying that arbitrary values are semantically usable.

MCProtocolLib 26.2 independently uses unparameterized `MinecraftTypes.readByteArray` for both request arrays (`build/tmp/mcprotocollib-sources.jar!/org/geysermc/mcprotocollib/protocol/packet/login/clientbound/ClientboundHelloPacket.java:25`) and both response arrays (`.../serverbound/ServerboundKeyPacket.java:37`). It also explicitly selects `RSA/ECB/PKCS1Padding` for RSA operations (`ServerboundKeyPacket.java:48`).

### Exact pinned Velocity codec bounds

At the pinned commit, `EncryptionRequestPacket.decode` applies these bounds for modern protocols:

- public key: `ProtocolUtils.readByteArray(buf, 256)`;
- verify token/challenge: `ProtocolUtils.readByteArray(buf, 16)`;
- `shouldAuthenticate` is read for 1.20.5 and newer.

Evidence: `build/velocity-patch-check-worktree/proxy/src/main/java/com/velocitypowered/proxy/protocol/packet/EncryptionRequestPacket.java:61-68`. Its encode path uses `writeByteArray` without enforcing those decode maxima (`EncryptionRequestPacket.java:77-84`). Thus Velocity can decode the target's ordinary request and can encode a larger modified challenge toward a Mojang client, but another unmodified Velocity decoder on that modified request path would reject a challenge above 16 bytes.

For a 26.2 `EncryptionResponsePacket`, Velocity decodes:

- encrypted shared secret: at most 128 wire bytes;
- encrypted verify token/challenge: at most 256 wire bytes.

Evidence: `build/velocity-patch-check-worktree/proxy/src/main/java/com/velocitypowered/proxy/protocol/packet/EncryptionResponsePacket.java:66-77`. These are **ciphertext byte-array bounds**, not RSA plaintext capacities.

### RSA and actual key/challenge sizes

Minecraft 26.2 generates 1024-bit RSA keys (`...sources.jar!/net/minecraft/util/Crypt.java:31-32`, `:68-72`) and a 128-bit AES secret (`Crypt.java:29-30`, `:58-62`). Velocity also explicitly calls `EncryptionUtils.createRsaKeyPair(1024)` (`build/velocity-patch-check-worktree/proxy/src/main/java/com/velocitypowered/proxy/VelocityServer.java:255`).

A 1024-bit RSA operation produces a 128-byte ciphertext. PKCS#1 v1.5 encryption requires 11 bytes of padding, so the maximum plaintext is:

```text
k = 1024 / 8 = 128 bytes
max plaintext = k - 11 = 117 bytes
ciphertext = k = 128 bytes
```

Mojang calls `Cipher.getInstance("RSA")` through `Crypt.encryptUsingKey` (`...sources.jar!/net/minecraft/util/Crypt.java:165-184`); the local MCProtocolLib implementation makes the effective transformation explicit as `RSA/ECB/PKCS1Padding` (`build/tmp/mcprotocollib-sources.jar!/org/geysermc/mcprotocollib/protocol/packet/login/serverbound/ServerboundKeyPacket.java:48-52`).

The encoded public key placed on the wire is X.509 SubjectPublicKeyInfo: Mojang parses it with `X509EncodedKeySpec` (`Crypt.java:145-150`), and Velocity sends `getPublic().getEncoded()`. A local Java provider probe generated five 1024-bit RSA keys and reported 162 encoded bytes for every key. For the locally evidenced Vanilla/Velocity 1024-bit generation path, use `P = 162` bytes. There is no captured packet from the user's particular target server in the repository, so a non-Vanilla target using a different RSA key size or encoding must be measured rather than assumed.

Vanilla 26.2 creates its challenge with `Ints.toByteArray(nextInt())`, hence `C = 4` bytes (`...sources.jar!/net/minecraft/server/network/ServerLoginPacketListenerImpl.java:64-69`). It sends the encoded public key and that challenge directly in `ClientboundHelloPacket` (`ServerLoginPacketListenerImpl.java:126-129`). Velocity's ordinary frontend implementation also historically uses a four-byte token, while the current project patch changes its own frontend marker token to 16; that patched frontend token is not evidence that an arbitrary target's challenge is 16 bytes.

For RSA-2048, if encountered outside the locally evidenced Vanilla/Velocity paths, ciphertext is 256 bytes and PKCS#1 v1.5 plaintext maximum is 245 bytes. Such a key's X.509 encoding was not captured locally, and the pinned Velocity request decoder's 256-byte public-key bound may itself be too small for a typical encoded RSA-2048 public key. This possibility does not change the 1024-bit conclusion.

### Request direction: modified Server Hello

Let:

- `H` = protocol header bytes, including any magic/version;
- `L` = any length/disambiguation bytes used by the envelope;
- `P = 162` = original target X.509 RSA-1024 public key;
- `C = 4` = original Vanilla 26.2 challenge.

The modified challenge length is:

```text
E_request = H + L + P + C = H + L + 166 bytes
```

With fixed-size parsing, `L = 0`, so even a zero-byte header is already 166 bytes. With VarInt lengths for the 162-byte key and 4-byte challenge, `L = 2 + 1 = 3`, so it is `H + 169`. For the existing project's six-byte magic plus one-byte version shape (`H = 7`), those examples are 173 and 176 bytes respectively. These are layout examples, not a requirement that the new protocol reuse that marker.

Wire capacity and RSA capacity give different answers:

1. **Wire codec:** Mojang's 26.2 client codec can decode this byte array because it has no field-specific fixed maximum. Velocity's normal *decode* bound of 16 cannot decode such a modified request, but its encode method does not enforce 16. On the direct Velocity-to-client leg described by the candidate, changing a Mojang codec bound is not required.
2. **Unmodified client / unchanged constructor path:** impossible. `ClientHandshakePacketListenerImpl.handleHello` obtains the received challenge and passes it unchanged to `new ServerboundKeyPacket(secretKey, publicKey, challenge)` (`...sources.jar!/net/minecraft/client/multiplayer/ClientHandshakePacketListenerImpl.java:121-127`). That constructor RSA-encrypts the entire challenge. `E_request >= 166 > 117`, so RSA/PKCS#1 v1.5 encryption fails before a response can be sent. Widening byte-array codec bounds cannot fix this cryptographic limit.
3. **Mod intercepts before construction:** potentially feasible. The Mod must parse/store the large envelope, then replace the constructor's challenge argument with a short acknowledgement plaintext `A <= 117`. Merely observing the special challenge while still passing the full received array to the constructor is not enough.

The replacement `publicKey` must remain a valid X.509 RSA public key because the Vanilla handler calls `packet.getPublicKey()` before constructing the response (`ClientHandshakePacketListenerImpl.java:121-127`), and `getPublicKey()` parses the bytes (`ClientboundHelloPacket.java:54-56`). An invalid marker substituted as the public-key bytes fails before Mod acknowledgement logic unless the Mod replaces that path too.

### Response direction: standard ServerboundKeyPacket

The minimum workable response should use the packet's two existing RSA ciphertext fields rather than redundantly nesting everything in one RSA plaintext:

```text
keybytes             = RSA_proxy(K)
encryptedChallenge   = RSA_proxy(H || L || C)
```

Here `K = 16` bytes and Vanilla target `C = 4`. Requirements are:

```text
16 <= 117                                      # K fits
H + L + 4 <= 117                              # acknowledgement fits
```

Both RSA-1024 results are exactly 128 wire bytes. The pinned Velocity decoder accepts 128 for `sharedSecret` and up to 256 for `verifyToken`, so **no codec-bound change is needed for this response layout**. After decrypting both with the proxy private key, Velocity knows `K` and the original challenge and can construct the ordinary upstream response:

```text
keybytes             = RSA_target(K)          # 128 wire bytes
encryptedChallenge   = RSA_target(C)          # 128 wire bytes
```

An optional redundant copy of `K` in the acknowledgement changes its plaintext to `H + L + C + 16`, requiring `H + L <= 97`. It can fit, but it is unnecessary because `K` already has the dedicated shared-secret field.

“Solved challenge” must be defined carefully:

- If it means the **plaintext original challenge/acknowledgement** that Velocity decrypts and then re-encrypts for the target, it is 4 bytes for Vanilla 26.2 and fits as above.
- If it means an already solved **RSA_target(C) ciphertext**, it is 128 bytes. It cannot be nested inside `RSA_proxy(H || RSA_target(C) [|| K])`, because 128 bytes alone exceeds the proxy RSA plaintext maximum of 117. It could physically occupy a raw byte-array field, but then it is not a normal proxy-encrypted challenge and the ordinary Velocity frontend handler cannot validate/decrypt it without a distinct patched packet interpretation.

### Compatibility and handler requirements

- **Unmodified Vanilla client:** it can decode the large modified challenge, but it then attempts to RSA-encrypt all of it and throws a protocol error because it exceeds 117 bytes. Therefore this construction is intentionally Mod-required; it has no graceful Vanilla fallback by codec behavior alone.
- **Modded client:** it must intercept before `ServerboundKeyPacket` construction (or replace that construction) and provide a short acknowledgement. It must still retain/use the generated 16-byte `K` for its connection encryption.
- **Unpatched Vanilla target server:** it cannot receive the FPP envelope directly. Its handler validates that decrypting the second field equals its original four-byte challenge, obtains `K` by decrypting the first field, and then enables encryption (`...sources.jar!/net/minecraft/server/network/ServerLoginPacketListenerImpl.java:170-186`). It works only if Velocity translates the Mod response into the two ordinary target-key ciphertexts shown above.
- **Authentication:** the Vanilla client computes and submits the session digest using the public key present in the modified Server Hello (`ClientHandshakePacketListenerImpl.java:121-133`). If that is the proxy key, target online-mode authentication is not automatically satisfied. A complete design must separately ensure the client joins the target session using the target server ID/public key and the same `K`, before Velocity forwards the reconstructed response. This is a handler/protocol requirement, not a byte capacity issue.
- **Velocity handler changes:** codec changes alone are insufficient. Velocity must recognize the special flow, decrypt/validate its short acknowledgement, retain `K`, reconstruct the target response under the target public key, and switch encryption independently on the frontend and backend legs at the correct write boundaries.

### Directional conclusion

| Direction | Fits wire fields? | Fits unchanged Vanilla RSA path? | Feasible condition |
|---|---:|---:|---|
| Modified Server Hello challenge `H + originalPublicKey + originalChallenge` | Yes for Mojang 26.2 direct decode; no through Velocity's 16-byte request decoder | No: at least 166 bytes exceeds 117-byte RSA plaintext maximum | Mod parses it and substitutes a short `<=117` acknowledgement before packet construction |
| Serverbound response with `RSA_proxy(K)` plus `RSA_proxy(H + originalChallenge)` | Yes: 128 + 128 ciphertext bytes fit Velocity's 128/256 field limits | Yes for the two individual plaintexts | Velocity decrypts, validates, and re-encrypts `K` and challenge separately for target |
| One nested response plaintext containing header + already-RSA-solved challenge + K | Wire array could be large enough | No: `H + 128 + 16 > 117` | Do not nest a target RSA ciphertext inside proxy RSA; translate from plaintext components instead |

## Compression Analysis

### Question and test method

This section tests only whether `protocol metadata + original RSA-1024 public key + original four-byte challenge` can be represented losslessly within the 117-byte RSA/PKCS#1 v1.5 plaintext limit. It does not change the conclusion that a Mod can instead replace the constructor argument with a short acknowledgement.

The local JDK 26 provider generated 100 fresh RSA-1024 key pairs with `KeyPairGenerator("RSA")`. Every `PublicKey.getEncoded()` result was a 162-byte X.509 SubjectPublicKeyInfo DER value and every unsigned modulus was 128 bytes. Each sample was compressed at Java `Deflater` level 9 as raw DEFLATE (`nowrap=true`) and zlib (`nowrap=false`), and with `GZIPOutputStream`. A raw/zlib dictionary test pre-shared only the fixed DER bytes before and after the modulus; it did not contain any modulus bytes. Thus the dictionary test is an optimistic implementation of “both sides already know the fixed ASN.1 structure.”

### Measurements

| Input / encoding | Input bytes | Samples | Compressed bytes (min-max) | Average |
|---|---:|---:|---:|---:|
| Complete X.509 DER, raw DEFLATE | 162 | 100 | 167 | 167.00 |
| Complete X.509 DER, zlib | 162 | 100 | 173 | 173.00 |
| Complete X.509 DER, gzip | 162 | 100 | 185 | 185.00 |
| Unsigned modulus only, raw DEFLATE | 128 | 100 | 133 | 133.00 |
| Unsigned modulus only, zlib | 128 | 100 | 139 | 139.00 |
| Unsigned modulus only, gzip | 128 | 100 | 151 | 151.00 |
| Complete DER with fixed-structure dictionary, raw DEFLATE | 162 | 100 | 142-145 | 143.30 |
| Complete DER with fixed-structure dictionary, zlib | 162 | 100 | 152-155 | 153.30 |

The ordinary compressors expanded every tested complete DER and bare modulus because the modulus is high-entropy data plus format overhead. The pre-shared dictionary removes much of the repetitive DER cost, but its best observed result, 142 bytes, is still 25 bytes above the entire RSA plaintext budget before adding a protocol marker or challenge.

An additional ten-key probe used an independently random four-byte challenge per key:

- raw DEFLATE of the optimistic fixed layout `1-byte protocol ID || 128-byte modulus || 4-byte challenge` (133 input bytes) was 138 bytes for all ten samples;
- adding the two-byte VarInt encoding of modulus length 128 made the input 135 bytes and raw DEFLATE output 140 bytes for all ten samples;
- raw DEFLATE of `1-byte protocol ID || 162-byte DER || 4-byte challenge` with the fixed-DER dictionary produced 148-150 bytes.

These observations are samples, not a proof that a particular hand-picked modulus can never compress below 117. They do show that newly generated keys behave as expected for cryptographic material and provide no practical compression margin.

### Representation comparison and minimum arithmetic

| Lossless representation | Key bytes before generic compression | Minimum envelope with 1-byte protocol ID + 4-byte challenge | Result versus 117 |
|---|---:|---:|---|
| Complete X.509 DER | 162 | 167 without a length; 169 with a two-byte VarInt length | At least 50 bytes too large even without length |
| DER `RSAPublicKey` containing modulus + exponent | about 140 for the locally generated keys | at least 145 | Too large; ASN.1 removal helps but cannot remove modulus entropy |
| Unsigned modulus (128) + exponent `65537` (3) | 131 | 136 without lengths | Too large |
| Fixed exponent `65537`, transmit unsigned modulus only | 128 | 133 without a length; 135 with its two-byte VarInt length | Too large even before compression framing |
| Pre-shared fixed DER structure, transmit only modulus | 128 | same 133/135-byte compact layout | Equivalent to fixed-exponent modulus-only for capacity purposes |

If the schema fixes the key representation length and omits its length field, a one-byte protocol ID and four-byte challenge leave at most `117 - 1 - 4 = 112` bytes for the key. If a self-describing representation that actually fits uses a one-byte length, it leaves at most 111 key bytes. Both are below the 128 bytes required just to carry the modulus in the straightforward lossless representation. Even deleting *all* protocol metadata and the challenge would leave a 128-byte modulus that is 11 bytes above the RSA plaintext limit.

A dictionary can reconstruct fixed DER tags, lengths, algorithm identifier, fixed exponent, and sign padding without transmitting them. It cannot reconstruct a previously unknown target modulus. A static dictionary therefore cannot close the 128-to-117-byte gap. A dictionary dynamically containing the target modulus would make the “compressed” payload small, but distributing that dictionary to the client is another lossless channel for the same key and does not solve this envelope.

### Worst-case and information-theoretic conclusion

No lossless compressor can guarantee that every input is shorter; otherwise its output space would have fewer distinct codewords than its input space. Here the guarantee is even less plausible when restricted to legal RSA keys rather than arbitrary 128-byte strings. By the prime number theorem, there are roughly `2^503` 512-bit primes, yielding on the order of `2^1005` distinct unordered products for RSA-1024 moduli (fixing `e = 65537` removes only a negligible fraction that are not coprime to `phi(n)`). A maximum 117-byte value provides only `2^936` fixed-length codewords, and the protocol ID/challenge reduce the available key code space further. Therefore no injective, lossless encoding can map every valid generated RSA-1024 public modulus into this budget.

The exact guarantee is:

- **Sample result:** none of the 100 generated keys approached 117 bytes under raw DEFLATE, zlib, gzip, or a fixed-DER dictionary; the best was 142 bytes before protocol/challenge overhead.
- **Arbitrary-key guarantee:** impossible. There may be exceptional moduli with repeated byte patterns that a compressor happens to shorten, but a protocol must accept the target key it receives, not only compressible keys. It cannot rely on such an accident.
- **Fixed exponent / fixed DER:** useful for reducing 162 bytes to the irreducible straightforward 128-byte modulus, but still insufficient.
- **Final feasibility:** `special protocol fields + arbitrary original RSA-1024 public key + original four-byte challenge` cannot be guaranteed to fit in a single RSA-1024/PKCS#1 v1.5 plaintext. The design must avoid putting the key inside that RSA plaintext, use another field/round trip/channel, use a pre-established key reference, or intercept the Vanilla constructor and send only a short acknowledgement as described above.

## Public-Key Field Carrier Analysis

### Candidate and local provider behavior

This candidate keeps `ClientboundHelloPacket.challenge` byte-for-byte equal to the target's challenge. The packet's `publicKey` byte array must still be accepted by the Minecraft client as an RSA public key whose matching private key Velocity owns, while a Mod must recover protocol metadata and the original target public key from that byte array.

Minecraft 26.2 `ClientboundHelloPacket.getPublicKey()` calls `Crypt.byteToPublicKey`, which constructs `X509EncodedKeySpec` and invokes `KeyFactory.getInstance("RSA").generatePublic(...)` (`...sources.jar!/net/minecraft/network/protocol/login/ClientboundHelloPacket.java:54-56`; `...sources.jar!/net/minecraft/util/Crypt.java:145-150`). The relevant acceptance criterion is therefore the actual JCA RSA provider used by the game, not merely whether a byte sequence can be described informally as ASN.1.

Minimal local probes generated a proxy RSA-1024 key pair, constructed X.509 SubjectPublicKeyInfo variants around its real modulus/exponent, passed the bytes through that same RSA `KeyFactory` call, and then verified `RSA/ECB/PKCS1Padding` encryption with the parsed public key and decryption with the original private key.

#### JDK 26 results

The active local JDK was Azul Zulu JDK 26. Its provider behaved as follows:

| Carrier location | Tested payload lengths | Result |
|---|---|---|
| Normal `rsaEncryption` AlgorithmIdentifier with `NULL` parameters | 0 | Accepted; 162-byte canonical proxy SPKI; RSA round trip passed |
| AlgorithmIdentifier parameters absent | 0 | Accepted; input 160 bytes, re-encoded canonically with `NULL` as 162 bytes; RSA round trip passed |
| AlgorithmIdentifier parameters replaced by an OCTET STRING | 0, 1, 4, 16, 64, 128, 256, 294, 512, 1024, 2048, 4096 | All accepted; non-empty parameter bytes were retained by `PublicKey.getEncoded()`; RSA round trip passed in the crypto probes |
| Bytes appended inside the public-key BIT STRING after the DER `RSAPublicKey` sequence | 1, 4, 16, 64, 128, 256 | All rejected with `InvalidKeySpecException: Invalid RSA public key` |
| Bytes appended after the complete outer SPKI DER object | 1, 4, 16, 64, 128, 162, 294, 512, 2048 | All rejected with `InvalidKeySpecException: Unable to decode key` |

The OCTET STRING carrier was also tested with the exact proposed contents:

```text
parameters OCTET STRING content = 1-byte protocol/version
                                || VarInt target-SPKI length
                                || target-SPKI bytes
```

For a generated RSA-1024 target, target SPKI was 162 bytes, parameter content was 165 bytes, and the decorated proxy SPKI was 330 bytes. For RSA-2048, those sizes were 294, 297, and 464 bytes. Both decorated keys were accepted, `parsedPublicKey.getEncoded()` retained the complete 330/464 bytes exactly, and RSA encryption/decryption with the proxy private key succeeded.

#### Required Java 25 confirmation

The project requires Java 25 for the Minecraft/Fabric client. The locally installed toolchain was JetBrains Runtime OpenJDK `25.0.4`, runtime build `25.0.4+1-b508.27`. The exact RSA-2048-target case was repeated under that Java 25 runtime:

```text
decorated input SPKI = 464 bytes
parsed getEncoded()  = 464 bytes
exact bytes retained = true
RSA encrypt/decrypt  = true
```

Thus the required local Java 25 client runtime and the local JDK 26 runtime agree on this carrier. This remains provider-specific behavior rather than a protocol guarantee across arbitrary JVMs.

### Standards and digest caveat

An OCTET STRING is a valid DER value syntactically, but arbitrary OCTET STRING parameters are not the canonical/standards-conforming parameters for the `rsaEncryption` algorithm identifier, which normally uses `NULL`. The result is therefore:

- **JCA/Minecraft criterion on the tested Java 25/26 providers:** accepted as an RSA public key and cryptographically functional;
- **strict interoperable SPKI criterion:** not a portable encoding and liable to rejection or canonicalization by another provider/library.

This distinction matters when saying the field is “legal.” The outer-suffix trick does not even meet the tested JCA criterion and must not be used. The AlgorithmIdentifier-parameter carrier meets the actual tested Minecraft/JCA criterion, but depends on SunRsaSign-compatible leniency.

The parsed decorated key retains its parameters in `getEncoded()`. Minecraft computes its session digest from that parsed key's encoded bytes (`ClientHandshakePacketListenerImpl.java:121-133`; `Crypt.java:78-81`). Velocity must therefore use the *same decorated public-key encoding* when calculating the frontend proxy digest. Calculating with the undecorated `serverKeyPair.getPublic().getEncoded()` would produce a different hash even though the modulus and private key match.

### Why BIT STRING and outer suffix carriers do not work

A legal proxy SPKI followed by `magic || target-key` in the packet byte array looked attractive because a Mod could split it at the known DER length. The JDK 26 provider rejected every tested outer suffix, including a single byte, so Vanilla `getPublicKey()` fails before login response construction. No tested suffix length worked.

Placing data after the two INTEGERs inside the BIT STRING's DER `RSAPublicKey` was also rejected from one byte onward. The RSA parser requires the inner key structure it consumes, not merely a BIT STRING with a valid prefix.

These are hard parser failures in the tested client path, not wire limits. Mojang's 26.2 byte-array codec can carry the bytes, but `KeyFactory` refuses them.

### Encoding data in the RSA modulus is not a viable alternative

A proxy RSA-1024 modulus exposes only 128 bytes. The original target key is 162 bytes as RSA-1024 SPKI or 294 bytes as RSA-2048 SPKI. Even assuming fixed DER and exponent `65537`, the target's raw modulus alone consumes the full 128 bytes for RSA-1024 or 256 bytes for RSA-2048, leaving no protocol identifier. Capacity therefore fails immediately for a proxy RSA-1024 carrier.

More importantly, Velocity must know a matching private key. Choosing an arbitrary encoded 1024-bit value as `n` and then obtaining the private key means factoring that value; this is not a generation strategy. Generating primes `p` and `q` first gives a known private key, but their product behaves pseudorandomly. Rejection-searching until the product matches hundreds of prescribed payload bits has expected work exponential in the number of fixed bits (approximately `2^b` trials for `b` constrained independent bits). Encoding an entire original modulus this way is computationally infeasible, not an engineering tradeoff.

Using a larger proxy modulus only moves the problem:

- a constrained-modulus search remains exponential and has no practical worst-case completion;
- fixing or biasing key material without a reviewed construction creates avoidable cryptographic risk;
- a proxy RSA-2048 response produces a 256-byte encrypted shared-secret field, while the pinned Velocity frontend `EncryptionResponsePacket` decoder accepts only 128 bytes for that field (`EncryptionResponsePacket.java:66-77`). Codec and handler changes would then also be required.

No modulus-steganography construction found in the local code provides efficient generation, recoverability by the Mod, a matching private key, and standard RSA security. It must not be treated as a feasible candidate.

### RSA-2048 target capacity and Velocity boundary

The AlgorithmIdentifier-parameter carrier itself handled a complete generated RSA-2048 target SPKI: 294 target bytes produced a 464-byte decorated proxy SPKI accepted by Java 25 and 26. The proxy key can remain RSA-1024, so the client-to-Velocity encrypted secret and acknowledgement remain 128-byte ciphertexts.

However, the pinned Velocity `EncryptionRequestPacket` decoder limits an incoming target public-key byte array to 256 bytes (`EncryptionRequestPacket.java:65`). A typical locally generated RSA-2048 X.509 SPKI is 294 bytes, so Velocity cannot even receive that target Server Hello without raising this decoder bound. After decoding it, Velocity can encode the 464-byte decorated key because the encode method has no corresponding check, and Mojang 26.2 has no field-specific public-key byte-array maximum. The reconstructed upstream RSA-2048 response contains two 256-byte ciphertexts; Mojang's 26.2 server packet codec is unbounded per field, but any intermediary's limits must be checked separately.

### Recommended minimum layout and Mod acknowledgement

For the tested Java 25/26 runtime, the minimum candidate is:

```text
ClientboundHelloPacket.publicKey = SPKI(
  proxy RSA-1024 modulus/exponent,
  rsaEncryption parameters = OCTET STRING(
    protocol/version || VarInt(targetPublicKey.length) || targetPublicKey
  )
)

ClientboundHelloPacket.challenge = originalTargetChallenge   # unchanged

Vanilla ServerboundKeyPacket:
  keybytes           = RSA_proxy(K)
  encryptedChallenge = RSA_proxy(originalTargetChallenge)

Mod ServerboundKeyPacket:
  keybytes           = RSA_proxy(K)
  encryptedChallenge = RSA_proxy(ACK || originalTargetChallenge)
```

The target public key is not repeated in the challenge, and the original challenge is not repeated in the SPKI parameters. With a four-byte Vanilla target challenge, the Mod acknowledgement plaintext length is `len(ACK) + 4` and must be at most 117 bytes, so `len(ACK) <= 113`. The existing six-byte `FPPACK` shape would make a ten-byte plaintext; adding a one-byte version would make eleven. Both are far below the RSA-1024 PKCS#1 v1.5 limit. `K` remains solely in the packet's dedicated shared-secret field.

Keeping the original challenge means an unmodified Vanilla client completes its normal path: it parses the decorated proxy key, performs `joinServer` for the **proxy digest**, generates `K`, and returns `RSA_proxy(K) + RSA_proxy(originalChallenge)`. That response is cryptographically valid. Therefore challenge preservation does not itself detect the Mod. Velocity must deliberately distinguish exact decrypted `originalChallenge` (Vanilla) from `ACK || originalChallenge` (Mod), enable frontend encryption using the recovered `K`, and send the Vanilla client a friendly encrypted “Mod required” disconnect.

The Mod must suppress/replace the Vanilla proxy `joinServer` operation. It extracts the original target key and performs the one required `joinServer` using the **original target server ID/public key and the same `K`**. It must not first join the decorated proxy digest and then also join the target; the target join is the unique authentication operation for the relayed online-mode login. Velocity then decrypts the Mod response, validates `ACK || originalChallenge`, and reconstructs `RSA_target(K) + RSA_target(originalTargetChallenge)` for the unmodified target.

### Recommendation

- **Recommended for the pinned client JVM only:** AlgorithmIdentifier OCTET STRING parameters carrying `protocol/version + target-key length + target SPKI`, original challenge unchanged, and a short `ACK || original challenge` in the Mod response. Gate the design explicitly to the tested Java provider behavior and add startup/integration tests because the encoding is non-canonical.
- **Not recommended:** SPKI outer suffix (strictly rejected in local JDK 26), BIT STRING inner suffix (rejected), or modulus steganography/constrained key search (insufficient RSA-1024 capacity and computationally infeasible).
- **Strict-provider fallback:** put an arbitrary envelope in the packet's publicKey byte array but have the Mod intercept before `getPublicKey()` and substitute/take over with a valid proxy key. This no longer satisfies “the complete field is JCA-parseable” and an unmodified Vanilla client fails before a friendly encrypted rejection.
- **Constraint-relaxing fallback:** carry data in an enlarged/split challenge and have the Mod replace it with a short acknowledgement before `ServerboundKeyPacket` construction, as analyzed above. This abandons the new requirement that challenge remain exactly the target value. A separate login-query round trip is another protocol design, not a hidden public-key encoding.

## External references

No new broad web research was performed. The PKCS#1 v1.5 `k - 11` limit is the standard constraint implemented by the locally evidenced Java RSA/PKCS1Padding path; all version-specific claims above come from local source caches and the pinned Velocity worktree.

## Related specs

- `.trellis/spec/backend/velocity-plugin.md` - pins the runtime protocol target to Minecraft 26.2 / protocol 776 and constrains MCProtocolLib imports.
- `.trellis/spec/frontend/fabric-client-mod.md` - Fabric client-mod contract relevant to the required constructor interception.

## Caveats / Not Found

- No captured Server Hello from the user's actual target server was found. The exact `P = 162`, `C = 4`, RSA-1024 arithmetic is exact for the locally evidenced Vanilla 26.2 and pinned Velocity generation paths, but a custom target must be measured.
- The candidate protocol header and its framing have not been specified. Capacity is therefore stated as formulas in `H` and `L`; examples using the existing seven-byte magic/version shape are illustrative.
- This analysis establishes field and RSA capacity, not end-to-end authentication correctness. The target `joinServer` digest and encryption state transitions still require an explicit design.
