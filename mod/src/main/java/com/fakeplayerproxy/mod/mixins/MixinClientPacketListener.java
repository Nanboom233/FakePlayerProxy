package com.fakeplayerproxy.mod.mixins;

import com.fakeplayerproxy.mod.gui.AutoReconnectConsentScreen;
import com.fakeplayerproxy.mod.packets.AutoReconnectPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Shows explicit consent before the client sends its Minecraft access token. */
@Mixin(ClientPacketListener.class)
public abstract class MixinClientPacketListener {
    @Inject(method = "handleCustomPayload", at = @At("HEAD"), cancellable = true)
    private void handleAutoReconnectRequest(CustomPacketPayload payload, CallbackInfo callbackInfo) {
        if (!(payload instanceof AutoReconnectPayload.Request)) {
            return;
        }
        callbackInfo.cancel();

        Minecraft minecraft = Minecraft.getInstance();
        Screen previous = minecraft.gui.screen();
        minecraft.gui.setScreen(new AutoReconnectConsentScreen(allow -> {
            String token = allow ? minecraft.getUser().getAccessToken() : "";
            var connection = minecraft.getConnection();
            if (connection != null) {
                connection.send(new ServerboundCustomPayloadPacket(
                        new AutoReconnectPayload.Response(token)));
            }
            minecraft.gui.setScreen(previous);
        }));
    }
}
