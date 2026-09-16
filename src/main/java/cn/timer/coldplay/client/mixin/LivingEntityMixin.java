package cn.timer.coldplay.client.mixin;

import cn.timer.coldplay.client.manager.RotationManager;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
abstract class LivingEntityMixin {
    /**
     * Vanilla derives the body yaw ({@code yBodyRot}) from {@code getYRot()} in two places: the
     * movement-direction / swing branch of {@link LivingEntity#tick()} and the head-to-body clamp in
     * {@code tickHeadTurn}. The render mixin already draws the head at the server yaw, so feed the
     * server yaw into the body logic too, otherwise the body keeps facing the camera while the head
     * twists toward the target. The range-check loops after {@code tickHeadTurn} stay untouched so
     * {@code yRotO} remains camera-relative.
     */
    @ModifyExpressionValue(
            method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getYRot()F"),
            slice = @Slice(
                    from = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;aiStep()V"),
                    to = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;tickHeadTurn(F)V")),
            require = 2
    )
    private float coldplay$bodyTurnYaw(float original) {
        return coldplay$bodyYaw(original);
    }

    @ModifyExpressionValue(
            method = "tickHeadTurn(F)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getYRot()F")
    )
    private float coldplay$headTurnYaw(float original) {
        return coldplay$bodyYaw(original);
    }

    @Unique
    private float coldplay$bodyYaw(float original) {
        LivingEntity entity = (LivingEntity) (Object) this;
        RotationManager rotations = RotationManager.getInstance();
        return entity == Minecraft.getInstance().player && rotations.isActive()
                ? rotations.getServerYaw() : original;
    }

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
