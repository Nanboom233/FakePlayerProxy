package com.fakeplayerproxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Exercises MCProtocolLib through an isolated final Velocity release classpath. */
final class VelocityRuntimeSmokeTest {
    @Test
    void finalVelocityJarProvidesTheCompleteRuntime() throws Exception {
        Path velocityJar = Path.of(System.getProperty("fakeplayerproxy.velocityJar"));
        assertTrue(Files.isRegularFile(velocityJar));

        try (var loader = new URLClassLoader(
                new java.net.URL[]{velocityJar.toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            Class<?> byteBufClass = Class.forName("io.netty.buffer.ByteBuf", true, loader);
            Class<?> unpooledClass = Class.forName("io.netty.buffer.Unpooled", true, loader);
            Class<?> minecraftTypesClass = Class.forName(
                    "org.geysermc.mcprotocollib.protocol.codec.MinecraftTypes",
                    true,
                    loader);
            assertSame(loader, byteBufClass.getClassLoader());

            Object knownPacksData = unpooledClass.getMethod("buffer").invoke(null);
            try {
                minecraftTypesClass
                        .getMethod("writeVarInt", byteBufClass, int.class)
                        .invoke(null, knownPacksData, 0);
                Class<?> knownPacksClass = Class.forName(
                        "org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound."
                                + "ClientboundSelectKnownPacks",
                        true,
                        loader);
                Object packet = knownPacksClass
                        .getConstructor(byteBufClass)
                        .newInstance(knownPacksData);
                assertTrue(((List<?>) knownPacksClass.getMethod("getKnownPacks").invoke(packet)).isEmpty());
                assertFalse((boolean) byteBufClass.getMethod("isReadable").invoke(knownPacksData));
            } finally {
                byteBufClass.getMethod("release").invoke(knownPacksData);
            }

            Class<?> chunkSectionClass = Class.forName(
                    "org.geysermc.mcprotocollib.protocol.data.game.chunk.ChunkSection",
                    true,
                    loader);
            Object section = chunkSectionClass
                    .getConstructor(int.class, int.class, int.class, int.class)
                    .newInstance(0, 32366, 0, 1);
            chunkSectionClass
                    .getMethod("setBlock", int.class, int.class, int.class, int.class)
                    .invoke(section, 1, 2, 3, 7);
            Object chunkData = unpooledClass.getMethod("buffer").invoke(null);
            try {
                minecraftTypesClass
                        .getMethod("writeChunkSection", byteBufClass, chunkSectionClass)
                        .invoke(null, chunkData, section);
                Object decoded = minecraftTypesClass
                        .getMethod("readChunkSection", byteBufClass, int.class, int.class)
                        .invoke(null, chunkData, 32366, 1);
                assertEquals(
                        7,
                        chunkSectionClass
                                .getMethod("getBlock", int.class, int.class, int.class)
                                .invoke(decoded, 1, 2, 3));
                assertFalse((boolean) byteBufClass.getMethod("isReadable").invoke(chunkData));
            } finally {
                byteBufClass.getMethod("release").invoke(chunkData);
            }

            Class<?> lombokClass = Class.forName("lombok.Lombok", true, loader);
            RuntimeException marker = new RuntimeException("velocity runtime smoke");
            InvocationTargetException exception = assertThrows(
                    InvocationTargetException.class,
                    () -> lombokClass.getMethod("sneakyThrow", Throwable.class).invoke(null, marker));
            assertSame(marker, exception.getCause());
        }
    }
}
