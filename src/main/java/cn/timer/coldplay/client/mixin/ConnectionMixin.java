package cn.timer.coldplay.client.mixin;

import cn.timer.coldplay.client.ClientCore;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.local.LocalChannel;
import io.netty.handler.proxy.ProxyHandler;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
abstract class ConnectionMixin {
    @Shadow @Final private PacketFlow receiving;

    @Inject(method = "configurePacketHandler", at = @At("HEAD"))
    private void coldplay$addServerProxy(ChannelPipeline pipeline, CallbackInfo callbackInfo) {
        if (receiving != PacketFlow.CLIENTBOUND || pipeline.channel() instanceof LocalChannel
                || ClientCore.get().altManager() == null) {
            return;
        }
        ProxyHandler handler = ClientCore.get().altManager().serverProxyHandler();
        if (handler != null) {
            pipeline.addFirst("coldplay_server_proxy", handler);
        }
    }
}
