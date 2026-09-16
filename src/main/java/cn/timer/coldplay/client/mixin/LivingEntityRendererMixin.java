package cn.timer.coldplay.client.mixin;

import cn.timer.coldplay.client.ClientCore;
import cn.timer.coldplay.client.manager.RotationManager;
import cn.timer.coldplay.client.module.impl.combat.BackTrack;
import cn.timer.coldplay.client.module.impl.visuals.EntityESP;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.ARGB;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntityRenderer.class)
abstract class LivingEntityRendererMixin {
    @Shadow
    public abstract Identifier getTextureLocation(LivingEntityRenderState state);

    @Inject(
            method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
            at = @At("TAIL")
    )
    private void coldplay$renderRotation(LivingEntity entity, LivingEntityRenderState state,
                                         float tickDelta, CallbackInfo callback) {
        EntityESP entityEsp = ClientCore.get().entityEsp();
        if (entityEsp != null) {
            entityEsp.extractRenderState(entity, state);
        }

        RotationManager rotations = RotationManager.getInstance();
        if (entity != Minecraft.getInstance().player || !rotations.isActive()) {
            return;
        }

        float headYaw = Mth.wrapDegrees(rotations.getServerYaw() - state.bodyRot);
        state.yRot = state.isUpsideDown ? -headYaw : headYaw;
        state.xRot = state.isUpsideDown ? -rotations.getServerPitch() : rotations.getServerPitch();
    }

    @ModifyExpressionValue(
            method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/CameraRenderState;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;getRenderType(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;ZZZ)Lnet/minecraft/client/renderer/rendertype/RenderType;")
    )
    private RenderType coldplay$chamsMaterial(RenderType original, LivingEntityRenderState state) {
        EntityESP entityEsp = ClientCore.get().entityEsp();
        return entityEsp != null && entityEsp.usesChams() && state.getData(EntityESP.COLOR) != null
                && state.getData(BackTrack.REAL_COLOR) == null
                ? RenderTypes.textSeeThrough(getTextureLocation(state)) : original;
    }

    @ModifyExpressionValue(
            method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/CameraRenderState;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;getModelTint(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;)I")
    )
    private int coldplay$chamsTint(int original, LivingEntityRenderState state) {
        Integer backTrackColor = state.getData(BackTrack.REAL_COLOR);
        if (backTrackColor != null) {
            return ARGB.multiply(original, backTrackColor);
        }
        return original;
    }
}
