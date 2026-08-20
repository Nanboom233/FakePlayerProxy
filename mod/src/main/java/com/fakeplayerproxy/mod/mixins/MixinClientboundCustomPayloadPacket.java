package com.fakeplayerproxy.mod.mixins;

import com.fakeplayerproxy.mod.packets.AutoReconnectPayload;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Adds the auto-reconnect request to the clientbound PLAY payload codec. */
@Mixin(ClientboundCustomPayloadPacket.class)
public abstract class MixinClientboundCustomPayloadPacket {
    @ModifyArg(
            method = "<clinit>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;codec(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload$FallbackProvider;Ljava/util/List;)Lnet/minecraft/network/codec/StreamCodec;",
                    ordinal = 0),
            index = 1)
    private static List<CustomPacketPayload.TypeAndCodec<? super FriendlyByteBuf, ?>> addRequestCodec(
            List<CustomPacketPayload.TypeAndCodec<? super FriendlyByteBuf, ?>> types) {
        types.add(new CustomPacketPayload.TypeAndCodec<>(
                AutoReconnectPayload.Request.TYPE,
                AutoReconnectPayload.Request.STREAM_CODEC));
        return types;
    }
}
