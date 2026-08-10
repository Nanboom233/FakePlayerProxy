# Fabric 26.2 Build Facts

Checked on 2026-08-10.

## Official Fabric Metadata

- Fabric marks Minecraft 26.2 as stable.
- The latest stable Fabric Loader entry for 26.2 is `0.19.3`.
- The official Fabric example mod `26.2` branch uses Java 25.
- The example uses `fabric-api:0.156.0+26.2`.
- The example uses `fabric-loom:1.17-SNAPSHOT`.
- The example does not declare a Yarn mappings dependency.
- Minecraft 26.2 ships with official names and has no mappings artifact.

Sources:

- `https://meta.fabricmc.net/v2/versions/game`
- `https://meta.fabricmc.net/v2/versions/loader/26.2`
- `https://github.com/FabricMC/fabric-example-mod/tree/26.2`
- `https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml`

## Official Minecraft Metadata

Minecraft 26.2 requires Java 25. Mojang declares the runtime component as
`java-runtime-epsilon`.

Source:

- `https://piston-meta.mojang.com/mc/game/version_manifest_v2.json`

## MCProtocolLib Status

The OpenCollab release repository does not publish a stable MCProtocolLib 26.2
artifact. The snapshot repository contains several `26.2-SNAPSHOT` builds.

The dynamic snapshot now resolves to build 16. Its Gradle module metadata
requires Java 21. Build 15 reports Java 17 and contains the protocol 776 codec.
The Java 17 plugin therefore pins
`org.geysermc.mcprotocollib:protocol:26.2-20260709.110151-15`.

Build 15 declares Netty `4.2.1.Final`. The plugin excludes that dependency and
uses Netty `4.2.17.Final`. Netty lists `4.2.17.Final` as a release in the 4.2
line. This version includes the security fixes released after `4.2.1.Final`.

Sources:

- `https://repo.opencollab.dev/maven-releases/org/geysermc/mcprotocollib/protocol/maven-metadata.xml`
- `https://repo.opencollab.dev/maven-snapshots/org/geysermc/mcprotocollib/protocol/maven-metadata.xml`
- `https://repo.opencollab.dev/maven-snapshots/org/geysermc/mcprotocollib/protocol/26.2-SNAPSHOT/protocol-26.2-20260709.110151-15.module`
- `https://repo.opencollab.dev/maven-snapshots/org/geysermc/mcprotocollib/protocol/26.2-SNAPSHOT/protocol-26.2-20260809.160751-16.module`
- `https://github.com/GeyserMC/MCProtocolLib/blob/feature/26.2/protocol/src/main/java/org/geysermc/mcprotocollib/protocol/codec/MinecraftCodec.java`
- `https://github.com/netty/netty/releases/tag/netty-4.2.17.Final`
- `https://repo1.maven.org/maven2/io/netty/netty-bom/maven-metadata.xml`

## Design Effect

The `mod/` project can target Minecraft 26.2 with the official Fabric versions.
It must not call `officialMojangMappings()` because no mappings artifact exists.
The `plugin/` project must use the exact Java 17 build 15 artifact. A dynamic
snapshot can move the plugin to a Java 21 artifact without a source change. The
build must also replace the old transitive Netty version with `4.2.17.Final`.

## Networking API Result

The selected SPKI relay does not use Fabric networking APIs. It uses the
standard Server Hello and key-response packets. The Mod therefore has no Fabric
API dependency and needs no custom-payload compatibility guard.

Source:

- `https://github.com/FabricMC/fabric/tree/26.2/fabric-networking-api-v1`
