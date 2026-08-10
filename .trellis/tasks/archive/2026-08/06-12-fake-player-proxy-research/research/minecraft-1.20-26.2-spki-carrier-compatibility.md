# Research: Minecraft 1.20-26.2 SPKI carrier compatibility

- Query: Is the decorated RSA-1024 SPKI carrier compatible with every released Minecraft Java version from 1.20 through 26.2, and what per-version runtime/protocol/Mixin adaptations are required?
- Scope: mixed (local generated sources and pinned Velocity source; Mojang official manifests, mappings, and client jars; local Java runtime probes; IETF PKIX/PKCS standards)
- Date: 2026-08-10

## Candidate under test

The candidate keeps the target `serverId`, `challenge`, and, where present, `shouldAuthenticate`. It replaces only the Server Hello public-key bytes:

```text
publicKey = X.509 SubjectPublicKeyInfo(
  algorithm = rsaEncryption,
  parameters = OCTET STRING(magic || version || length || originalTargetSpki),
  subjectPublicKey = proxy RSA-1024 public key
)
```

Velocity owns the matching proxy private key. The unmodified client path produces:

```text
RSA_proxy(K) || RSA_proxy(C)
```

The Mod extracts the original target key, suppresses the proxy session join, performs the only required `joinServer` against the original target key using `K`, and produces:

```text
RSA_proxy(K) || RSA_proxy(ACK || version || C)
```

where `C` is the target challenge. Velocity decrypts and distinguishes the two responses, rejects Vanilla after enabling frontend encryption, and reconstructs `RSA_target(K) || RSA_target(C)` for the target.

## Concise conclusion

The **Minecraft packet and crypto mechanism is compatible across every release from 1.20 through 26.2**, subject to three explicit conditions:

1. The client JVM's default RSA provider must retain the non-`NULL` `rsaEncryption` parameters. This was empirically true with `SunRsaSign` on locally available Java 17, 21, and 25 runtimes, including the installed Mojang `java-runtime-epsilon` Microsoft OpenJDK 25. It is not standards-portable to arbitrary JCA providers.
2. The Mod must intercept the automatic session join and challenge argument. Vanilla otherwise joins using the decorated proxy key and returns the unmodified challenge. The Mod must join only the target when `shouldAuthenticate` is true and return `ACK || version || C` as the challenge plaintext.
3. A 2048-bit target's typical 294-byte SPKI exceeds the pinned Velocity backend `publicKey <= 256` decode limit. That backend decode bound must be raised; it is separate from sending the decorated key to the client.

This does **not** imply one compiled Fabric jar supports all releases. Minecraft/Fabric binaries, mappings, Java bytecode targets, and some listener structure differ; version-family builds or carefully separated source sets remain necessary.

## Evidence method

### Artifacts

- Mojang version enumeration and per-version metadata: `https://piston-meta.mojang.com/mc/game/version_manifest_v2.json` and each release's package JSON referenced by that manifest.
- For every release listed below, the official client jar and official client mappings (when published) were resolved from its package JSON. Every downloaded/copied jar and mappings file was checked against the SHA-1 in that JSON before inspection; there were zero mismatches.
- Protocol numbers came from the `protocol_version` property in each verified client jar's embedded `version.json`, not from a third-party version table.
- Java runtime major/component came from each verified release package JSON's `javaVersion.majorVersion` and `javaVersion.component`.
- For obfuscated 1.20-1.21.11 clients, official Mojang mappings identified the relevant classes. `javap -c -p -s` and Vineflower 1.12 were applied to the verified official class bytecode. Releases 26.1-26.2 ship official names and were inspected directly.
- Locally generated official-named 26.2 source: `E:/Gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.2/minecraft-merged-deobf-26.2-sources.jar`.
- Pinned Velocity source: `build/velocity-patch-check-worktree`, verified at commit `843a47e2a38325309cd66133149fc9a984f76bb8`.

Temporary official artifacts and generated probe classes were kept outside the repository and removed after the findings were persisted here.

## Authoritative release-family matrix

Rows group patch releases only where the inspected protocol-relevant packet/listener/crypto contract is the same. Different protocol numbers remain separate even where the crypto mechanism is unchanged.

| Releases | Protocol | Java metadata | Hello contract | Mechanism result | Minimum adaptation / blocker |
|---|---:|---|---|---|---|
| 1.20, 1.20.1 | 763 | 17 / `java-runtime-gamma` | `serverId, publicKey, challenge`; no auth boolean | Conditional yes | Join always runs; redirect it to target join. Listener sends key packet then enables AES in send callback. |
| 1.20.2 | 764 | 17 / `java-runtime-gamma` | Same three fields | Conditional yes | Login state handling changed from 1.20.1, but crypto targets/descriptors remain. |
| 1.20.3, 1.20.4 | 765 | 17 / `java-runtime-gamma` | Same three fields | Conditional yes | Same candidate behavior as 1.20.2. |
| 1.20.5, 1.20.6 | 766 | 21 / `java-runtime-delta` | Adds trailing `shouldAuthenticate` boolean | Conditional yes | First boolean/StreamCodec family. Target join only when the preserved flag is true; helper `setEncryption` owns send-then-enable ordering. |
| 1.21, 1.21.1 | 767 | 21 / `java-runtime-delta` | Four fields including auth boolean | Conditional yes | Same wire/crypto contract as 1.20.5-1.20.6; rebuild/remap for this binary family. |
| 1.21.2, 1.21.3 | 768 | 21 / `java-runtime-delta` | Same four fields | Conditional yes | Auth task dispatch changes from `submit` to `execute`; no crypto or packet-capacity change. |
| 1.21.4 | 769 | 21 / `java-runtime-delta` | Same four fields | Conditional yes | No carrier-specific blocker. |
| 1.21.5 | 770 | 21 / `java-runtime-delta` | Same four fields | Conditional yes | No carrier-specific blocker. |
| 1.21.6 | 771 | 21 / `java-runtime-delta` | Same four fields | Conditional yes | No carrier-specific blocker. |
| 1.21.7, 1.21.8 | 772 | 21 / `java-runtime-delta` | Same four fields | Conditional yes | No carrier-specific blocker. |
| 1.21.9, 1.21.10 | 773 | 21 / `java-runtime-delta` | Same four fields | Conditional yes | No carrier-specific blocker. |
| 1.21.11 | 774 | 21 / `java-runtime-delta` | Same four fields | Conditional yes | No carrier-specific blocker. |
| 26.1, 26.1.1, 26.1.2 | 775 | 25 / `java-runtime-epsilon` | Same four fields, official class names in artifact | Conditional yes | Java 25 build/runtime; no Mojang mappings artifact is needed. |
| 26.2 | 776 | 25 / `java-runtime-epsilon` | Same four fields, official class names in artifact | Conditional yes | Same mechanism as 26.1 family; pinned project target. |

There are 23 releases in scope. The backward boundary is exact: `1.20.5` is where both the Java 21 runtime requirement and trailing `shouldAuthenticate` field appear. The next Java boundary is `26.1`, which moves to Java 25 without changing this login crypto contract.

## Verified packet and client behavior

### Server Hello fields and codecs

For every 1.20-1.20.4 artifact, the public constructor descriptor is:

```text
(String serverId, byte[] publicKey, byte[] challenge)
```

The decode constructor reads `readUtf(20)`, then two calls to parameterless `readByteArray()`. The writer writes the same three fields in that order.

For every 1.20.5-26.2 artifact, the constructor adds a final boolean:

```text
(String serverId, byte[] publicKey, byte[] challenge, boolean shouldAuthenticate)
```

Decode/write order is `readUtf(20)`, two parameterless byte arrays, then boolean. The 26.2 official-named source shows this directly in `ClientboundHelloPacket.java:27-38`. The verified bytecode for all earlier artifacts has the same family-specific descriptors and call order.

No inspected `ClientboundHelloPacket` supplies an integer maximum to either byte-array read. Parameterless `FriendlyByteBuf.readByteArray()` bounds the declared length only by packet bytes remaining; 26.2 shows the implementation at `FriendlyByteBuf.java:274-305`, and the earlier bytecode follows the same overload path. Therefore a 464-byte decorated public key has no client-side field-bound problem in any family.

Starting in 1.20.5 the packet uses a `StreamCodec`; earlier families use their older packet registration path. This is a codec-construction/Mixin compatibility difference, not a different field encoding.

### Raw versus decorated key availability

All families store the received public-key bytes privately and expose a public-key accessor that calls the shared `Crypt.byteToPublicKey(byte[])` path. There is no public raw-byte getter in the inspected packet classes.

The Mod has two choices:

- parse the carrier from `packet.getPublicKey().getEncoded()`, which worked because all tested `SunRsaSign` runtimes retained the decorated encoding byte-for-byte; or
- use a version-remapped field accessor Mixin to read the raw array, which avoids depending on re-encoding retention but creates another per-version binary target.

The first choice is the smaller version surface but remains provider-specific.

### JCA, AES, hash, response, and ordering

The inspected `Crypt` implementation is semantically unchanged across every artifact:

- AES key generation uses `KeyGenerator.getInstance("AES")` and `init(128)`;
- RSA key parsing uses `new X509EncodedKeySpec(bytes)` and `KeyFactory.getInstance("RSA").generatePublic(...)`;
- Minecraft's own RSA key generation is 1024 bits;
- RSA encryption asks for `Cipher.getInstance(key.getAlgorithm())`, which is `RSA` for the parsed key;
- connection ciphers use `AES/CFB8/NoPadding` with `K` as IV.

Every inspected `ClientHandshakePacketListenerImpl.handleHello` performs the following operations in this order:

1. generate 128-bit AES `K`;
2. parse the received/decorated public key;
3. compute the SHA-1 session digest from `serverId`, `K`, and `publicKey.getEncoded()`;
4. build inbound/outbound AES ciphers;
5. obtain the received challenge;
6. construct `ServerboundKeyPacket(K, publicKey, challenge)`, which RSA-encrypts `K` and the complete challenge separately;
7. perform `joinServer` (always before 1.20.5, conditional on `shouldAuthenticate` from 1.20.5 onward);
8. send the key packet and enable AES only in the packet-send completion callback.

`ServerboundKeyPacket` has two parameterless byte-array reads/writes and the constructor descriptor `(SecretKey, PublicKey, byte[])` in all 23 artifacts. The second constructor argument supplied by Vanilla is the parsed decorated proxy key; the third is exactly the received target challenge.

This ordering gives the Mod the required interception points, but also means it must replace/suppress the normal join rather than run a second join afterward.

### `shouldAuthenticate` semantics

- 1.20-1.20.4 have no boolean and always call the session service before sending the key response.
- 1.20.5-26.2 preserve the server boolean. `true` performs the session join asynchronously and then sends the key response; `false` skips the join and sends immediately.

The candidate must mirror this: perform the target join exactly once only when the target flag is present and true. For a false flag, there should be no proxy join and no target join.

All verified Mojang server-side login listener bytecode generates `C` as `Ints.toByteArray(nextInt())`, hence four bytes, and uses the three-field or four-field Hello constructor matching the boundary above.

## Java runtime/provider matrix

The exact 464-byte probe used:

- proxy key: generated RSA-1024 pair;
- original target key: generated RSA-2048 SPKI, 294 bytes;
- parameter content: one-byte protocol/version + two-byte VarInt length 294 + 294-byte target SPKI = 297 bytes;
- complete decorated proxy SPKI: 464 bytes.

Each run performed `KeyFactory.getInstance("RSA")`, parsed `X509EncodedKeySpec`, compared `getEncoded()` byte-for-byte, checked algorithm/format, encrypted a 16-byte `K` with `RSA/ECB/PKCS1Padding`, decrypted with the matching proxy private key, and checked ciphertext length.

| Runtime path | Runtime/vendor | KeyFactory / Cipher provider | 464-byte parse and preservation | RSA result |
|---|---|---|---|---|
| `C:/Program Files/Java/jdk-17` | Oracle OpenJDK `17+35-2724` | `SunRsaSign` / `SunJCE` | accepted; `getEncoded()` 464 and identical; `RSA`, `X.509` | decrypt passed; ciphertext 128 bytes |
| `C:/Program Files/Java/jbr-21` | JetBrains OpenJDK `21.0.8+1-b1038.68` | `SunRsaSign` / `SunJCE` | accepted and identical | decrypt passed; ciphertext 128 bytes |
| `C:/Program Files/Java/jbr-25` | JetBrains OpenJDK `25.0.4+1-b508.27` | `SunRsaSign` / `SunJCE` | accepted and identical | decrypt passed; ciphertext 128 bytes |
| `<minecraft-runtime>/java-runtime-epsilon` | Mojang-installed Microsoft OpenJDK `25.0.1+8-LTS` | `SunRsaSign` / `SunJCE` | accepted and identical | decrypt passed; ciphertext 128 bytes |

All relevant Java majors were locally available and tested, so no major-version result is based only on provider-source inference. The exact installed Mojang gamma/delta component binaries were not present; Java 17/21 evidence comes from other OpenJDK distributions using the same named providers. Compatibility claims are deliberately limited to the tested `SunRsaSign`/`SunJCE` behavior, not arbitrary JCA providers.

### RSA constraints and standards conformance

The tested Java security properties disable `RSA keySize < 1024` for certificate paths, not RSA-1024 itself. The login operation is direct `KeyFactory`/`Cipher`, not TLS or certificate-path validation. RSA-1024 generation, parsing, encryption, and decryption all actually succeeded in every probe. No tested runtime rejected the key due to size or its non-`NULL` parameters.

The carrier is nevertheless not standards-conforming `rsaEncryption` SPKI. RFC 3279 section 2.3.1 states that the parameters for this AlgorithmIdentifier must have ASN.1 type `NULL`: `https://www.rfc-editor.org/rfc/rfc3279#section-2.3.1`. An OCTET STRING is accepted leniently by the tested OpenJDK provider but may be rejected or canonicalized elsewhere. This is the reason every matrix result is “Conditional yes,” not unconditional protocol portability.

## Capacity on every family

### Decorated public key

- Minimal RSA-2048-target carrier tested: 464 bytes.
- Minimal RSA-1024-target carrier measured previously: 330 bytes.
- A practical multi-byte magic increases these by only the additional header bytes.
- Every client packet codec in scope uses the no-explicit-maximum byte-array read, so these sizes fit.
- `KeyFactory` acceptance, not packet framing, is the meaningful client bound. Java 17/21/25 accepted the exact 464-byte value; Java 26 had additionally accepted parameter payloads through 4096 bytes in the prior focused probe.

### Mod acknowledgement

For Mojang targets, `C` is four bytes. With the existing six-byte `FPPACK` marker plus one version byte:

```text
ACK || version || C = 6 + 1 + 4 = 11 bytes plaintext
```

RSA-1024 PKCS#1 v1.5 permits 117 plaintext bytes and produces 128 ciphertext bytes. RFC 8017 section 7.2.1 states `mLen <= k - 11`: `https://www.rfc-editor.org/rfc/rfc8017#section-7.2.1`. Eleven bytes fits in every family.

Even using pinned Velocity's maximum accepted target challenge of 16 bytes gives `6 + 1 + 16 = 23`, still well below 117. The ServerboundKey packet codecs themselves use parameterless byte arrays in every family, and the pinned Velocity frontend decoder accepts 128 encrypted-secret bytes and up to 256 encrypted-challenge bytes. Both proxy RSA-1024 ciphertexts are exactly 128 bytes.

### Vanilla response and rejection

Because `C` is preserved, Vanilla parses the decorated proxy key, computes/joins the digest containing that decorated key (when authentication is enabled), and returns a cryptographically valid `RSA_proxy(K) + RSA_proxy(C)`. Challenge preservation alone cannot identify the Mod.

Velocity must interpret decrypted `C` as Vanilla and decrypted `ACK || version || C` as Mod. For Vanilla, it can recover `K`, enable frontend AES at the normal send boundary, and issue a friendly encrypted “Mod required” disconnect. For the Mod, it validates the ACK and reconstructs the target-key response.

The Mod must suppress the automatic join that uses the decorated proxy key. It performs the sole `joinServer` using the preserved target `serverId`, original target public key, and the same `K`. Doing both joins is incorrect. When `shouldAuthenticate` is false, it performs neither.

## Pinned Velocity boundary

The pinned `EncryptionRequestPacket.decode` uses:

```text
publicKey  <= 256 bytes
challenge <= 16 bytes
```

at `build/velocity-patch-check-worktree/proxy/src/main/java/com/velocitypowered/proxy/protocol/packet/EncryptionRequestPacket.java:61-68`. A generated RSA-2048 target SPKI is 294 bytes and is rejected while Velocity decodes the **backend target's original Hello**. This bound must be raised for RSA-2048 targets.

Do not confuse that with the **outbound decorated key**. `EncryptionRequestPacket.encode` calls `writeByteArray` without applying the decode maximum (`EncryptionRequestPacket.java:77-84`), and all clients in scope accept the 464-byte field. Proxy RSA-1024 must remain the frontend key unless the Velocity response decoder's 128-byte encrypted-secret bound is also redesigned.

## Mixin and build adaptations

### Stable semantic targets

The following official-named targets/descriptors exist throughout the range:

- `ClientHandshakePacketListenerImpl.handleHello(ClientboundHelloPacket)`;
- `Crypt.generateSecretKey(): SecretKey`;
- `ServerboundKeyPacket.<init>(SecretKey, PublicKey, byte[])` with challenge at argument index 2;
- `ClientboundHelloPacket.getPublicKey()` and `getChallenge()`;
- the session-service `joinServer` call reached from the login listener.

This supports the same conceptual hooks: inspect/extract decorated key, capture `K`, redirect the join to the target digest, and replace the challenge constructor argument with `ACK || version || C`.

### Version-specific structure

- **1.20-1.20.1:** three-field Hello, unconditional join, and key-packet send/encryption callback inline in `handleHello`.
- **1.20.2-1.20.4:** still three fields/unconditional join, but login state transitions changed. Injection assumptions based on exact local-variable ordinals or a specific status update are not portable backward to 1.20.
- **1.20.5 onward:** four-field Hello and a private `setEncryption` helper; the join is inside a `shouldAuthenticate` branch. Packet codec initialization also changes to the StreamCodec family.
- **1.21.2 onward:** the authentication executor call changes from a returned-future `submit` shape to `execute`; hooks should target the session join or method contract, not executor bytecode.
- **26.1 onward:** official names are present directly and the build requires Java 25; there is no mappings dependency to reuse from 1.21.

Core constructor and crypto call descriptors are stable, but local capture shape, obfuscated owners, Fabric Loader/API compatibility, class-file version, and Minecraft binary APIs are not. A single source design is plausible; a single compiled Fabric artifact across 1.20-26.2 is not established by this protocol research.

## Artifact integrity appendix

Verified official client SHA-1 values used for protocol/source inspection:

```text
1.20     e575a48efda46cf88111ba05b624ef90c520eef1
1.20.1   0c3ec587af28e5a785c0b4a7b8a30f9a8f78f838
1.20.2   82d1974e75fc984c5ed4b038e764e50958ac61a0
1.20.3   b178a327a96f2cf1c9f98a45e5588d654a3e4369
1.20.4   fd19469fed4a4b4c15b2d5133985f0e3e7816a8a
1.20.5   c6b92b2374a629f20802bb284f98a4ee790e950a
1.20.6   05b6f1c6b46a29d6ea82b4e0d42190e42402030f
1.21     0e9a07b9bb3390602f977073aa12884a4ce12431
1.21.1   30c73b1c5da787909b2f73340419fdf13b9def88
1.21.2   c7ac2d0d86f4ca416cab9064ff8a281852ad0c7b
1.21.3   6f67d19b4467240639cb2c368ffd4b94ba889705
1.21.4   a7e5a6024bfd3cd614625aa05629adf760020304
1.21.5   b88808bbb3da8d9f453694b5d8f74a3396f1a533
1.21.6   740a125b83dd3447feaa3c5e891ead7fbb21ae28
1.21.7   a2db1ea98c37b2d00c83f6867fb8bb581a593e07
1.21.8   a19d9badbea944a4369fd0059e53bf7286597576
1.21.9   ce92fd8d1b2460c41ceda07ae7b3fe863a80d045
1.21.10  d3bdf582a7fa723ce199f3665588dcfe6bf9aca8
1.21.11  ba2df812c2d12e0219c489c4cd9a5e1f0760f5bd
26.1     191771837687b766537a8c4607cb6fad79c533a1
26.1.1   377031a9e733ba8ab4d355959a8f6fb8eb707556
26.1.2   4e618f09a0c649dde3fdf829df443ce0b8831e65
26.2     2dc72797acbc1b63fc16a11c4ac393605f453754
```

## Caveats / Not Found

- Exact Mojang-installed `java-runtime-gamma` and `java-runtime-delta` binaries were not present locally. Java 17 and 21 were tested using other OpenJDK distributions with the same `SunRsaSign`/`SunJCE` provider names; the exact Mojang-installed epsilon binary was tested directly.
- The OCTET STRING parameters violate the RFC 3279 canonical requirement. A non-OpenJDK client runtime/provider can reject them even though its Minecraft version is in the compatible packet family.
- A real target may use a public-key encoding or challenge size different from Mojang's generated 162/294-byte SPKIs and four-byte challenge. Measure it; for the pinned Velocity path the independently enforced backend limits remain 256 and 16 until patched.
- This research establishes protocol and provider feasibility. It does not prove that one Fabric codebase can compile unchanged against all listed Minecraft/Fabric releases, nor does it select a multi-version build architecture.
