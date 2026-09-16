package cn.timer.coldplay.client.mixin;

import cn.timer.coldplay.client.ClientCore;
import cn.timer.coldplay.client.module.impl.visuals.FullBright;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(LightTexture.class)
abstract class LightTextureMixin {
    @Redirect(
            method = "updateLightTexture",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/OptionInstance;get()Ljava/lang/Object;", ordinal = 2)
    )
    private Object coldplay$gamma(OptionInstance<?> option) {
        FullBright fullBright = fullBright();
        return fullBright != null && fullBright.uses(FullBright.GAMMA) ? fullBright.gamma().get() : option.get();
    }

    @Redirect(
            method = "updateLightTexture",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;hasEffect(Lnet/minecraft/core/Holder;)Z", ordinal = 0)
    )
    private boolean coldplay$nightVisionBranch(LocalPlayer player, Holder<MobEffect> effect) {
        FullBright fullBright = fullBright();
        return fullBright != null && fullBright.uses(FullBright.NIGHT_VISION) || player.hasEffect(effect);
    }

    @Redirect(
            method = "updateLightTexture",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;getNightVisionScale(Lnet/minecraft/world/entity/LivingEntity;F)F")
    )
    private float coldplay$nightVisionScale(LivingEntity entity, float tickDelta) {
        FullBright fullBright = fullBright();
        return fullBright != null && fullBright.uses(FullBright.NIGHT_VISION)
                ? 1.0F
                : GameRenderer.getNightVisionScale(entity, tickDelta);
    }

    private static FullBright fullBright() {
        ClientCore core = ClientCore.get();
        return core.initialized() ? core.modules().get(FullBright.class) : null;
    }
}
