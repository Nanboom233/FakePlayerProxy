package com.fakeplayerproxy.mod.mixins;

import com.fakeplayerproxy.mod.packets.AutoReconnectPayload;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Adds the auto-reconnect response to the serverbound PLAY payload codec. */
@Mixin(ServerboundCustomPayloadPacket.class)
public abstract class MixinServerboundCustomPayloadPacket {
    @ModifyArg(
            method = "<clinit>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;codec(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload$FallbackProvider;Ljava/util/List;)Lnet/minecraft/network/codec/StreamCodec;"),
            index = 1)
    private static List<CustomPacketPayload.TypeAndCodec<? super FriendlyByteBuf, ?>> addResponseCodec(
            List<CustomPacketPayload.TypeAndCodec<? super FriendlyByteBuf, ?>> types) {
        types.add(new CustomPacketPayload.TypeAndCodec<>(
                AutoReconnectPayload.Response.TYPE,
                AutoReconnectPayload.Response.STREAM_CODEC));
        return types;
    }
}
