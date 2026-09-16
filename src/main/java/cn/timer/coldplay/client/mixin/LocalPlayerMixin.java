package cn.timer.coldplay.client.mixin;

import cn.timer.coldplay.client.manager.RotationManager;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(LocalPlayer.class)
abstract class LocalPlayerMixin {
    @Redirect(
            method = {"sendPosition", "tick"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getYRot()F")
    )
    private float coldplay$serverYaw(LocalPlayer player) {
        RotationManager rotations = RotationManager.getInstance();
        return rotations.isActive() ? rotations.getServerYaw() : player.getYRot();
    }

    @Redirect(
            method = {"sendPosition", "tick"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getXRot()F")
    )
    private float coldplay$serverPitch(LocalPlayer player) {
        RotationManager rotations = RotationManager.getInstance();
        return rotations.isActive() ? rotations.getServerPitch() : player.getXRot();
    }
}
