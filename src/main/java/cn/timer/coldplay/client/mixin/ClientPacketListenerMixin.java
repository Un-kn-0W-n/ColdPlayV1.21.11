package cn.timer.coldplay.client.mixin;

import cn.timer.coldplay.client.manager.RotationManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerMixin {
    @Inject(method = "handleMovePlayer", at = @At("TAIL"))
    private void coldplay$positionCorrection(ClientboundPlayerPositionPacket packet, CallbackInfo callback) {
        RotationManager.getInstance().resetAfterServerCorrection(Minecraft.getInstance().player);
    }

    @Inject(method = "handleRotatePlayer", at = @At("TAIL"))
    private void coldplay$rotationCorrection(ClientboundPlayerRotationPacket packet, CallbackInfo callback) {
        RotationManager.getInstance().resetAfterServerCorrection(Minecraft.getInstance().player);
    }
}
