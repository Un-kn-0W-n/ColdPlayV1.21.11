package cn.timer.coldplay.client.mixin;

import cn.timer.coldplay.client.ClientCore;
import cn.timer.coldplay.client.manager.RotationManager;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
    @Unique
    private boolean coldplay$raycastSpoofed;
    @Unique
    private float coldplay$raycastYaw;
    @Unique
    private float coldplay$raycastPitch;
    @Unique
    private float coldplay$raycastPreviousYaw;
    @Unique
    private float coldplay$raycastPreviousPitch;

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void coldplay$render(DeltaTracker deltaTracker, CallbackInfo callback) {
        ClientCore.get().render(deltaTracker);
    }

    @Inject(method = "pick", at = @At("HEAD"))
    private void coldplay$beginRaycast(float tickDelta, CallbackInfo callback) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        RotationManager rotations = RotationManager.getInstance();
        if (!rotations.isActive() || player == null || minecraft.getCameraEntity() != player) {
            return;
        }

        coldplay$raycastYaw = player.getYRot();
        coldplay$raycastPitch = player.getXRot();
        coldplay$raycastPreviousYaw = player.yRotO;
        coldplay$raycastPreviousPitch = player.xRotO;
        player.setYRot(rotations.getServerYaw());
        player.setXRot(rotations.getServerPitch());
        player.yRotO = rotations.getServerYaw();
        player.xRotO = rotations.getServerPitch();
        coldplay$raycastSpoofed = true;
    }

    @Inject(method = "pick", at = @At("RETURN"))
    private void coldplay$endRaycast(float tickDelta, CallbackInfo callback) {
        if (!coldplay$raycastSpoofed) {
            return;
        }

        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.setYRot(coldplay$raycastYaw);
            player.setXRot(coldplay$raycastPitch);
            player.yRotO = coldplay$raycastPreviousYaw;
            player.xRotO = coldplay$raycastPreviousPitch;
        }
        coldplay$raycastSpoofed = false;
    }
}
