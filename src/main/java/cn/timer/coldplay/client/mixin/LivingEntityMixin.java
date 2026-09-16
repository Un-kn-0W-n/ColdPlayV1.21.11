package cn.timer.coldplay.client.mixin;

import cn.timer.coldplay.client.manager.RotationManager;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
abstract class LivingEntityMixin {
    @Unique
    private boolean coldplay$travelSpoofed;
    @Unique
    private float coldplay$travelYaw;
    @Unique
    private float coldplay$travelPitch;

    @Inject(method = "travel", at = @At("HEAD"))
    private void coldplay$beginTravel(Vec3 input, CallbackInfo callback) {
        LivingEntity entity = (LivingEntity) (Object) this;
        RotationManager rotations = RotationManager.getInstance();
        if (entity != Minecraft.getInstance().player || !rotations.isActive()) {
            return;
        }

        coldplay$travelYaw = entity.getYRot();
        coldplay$travelPitch = entity.getXRot();
        entity.setYRot(rotations.getServerYaw());
        entity.setXRot(rotations.getServerPitch());
        coldplay$travelSpoofed = true;
    }

    @Inject(method = "travel", at = @At("RETURN"))
    private void coldplay$endTravel(Vec3 input, CallbackInfo callback) {
        if (!coldplay$travelSpoofed) {
            return;
        }

        LivingEntity entity = (LivingEntity) (Object) this;
        entity.setYRot(coldplay$travelYaw);
        entity.setXRot(coldplay$travelPitch);
        coldplay$travelSpoofed = false;
    }
}
