package com.fakeplayerproxy.mod;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * Provides the logger for the client Mixin and packet helper.
 *
 * <p>The Mod has no entrypoint and no global connection state. Minecraft creates
 * the login listener, and the Mixin changes only a recognized Server Hello.
 */
public final class FakePlayerProxyMod {
    public static final Logger LOGGER = LogUtils.getLogger();

    private FakePlayerProxyMod() {
    }
}
