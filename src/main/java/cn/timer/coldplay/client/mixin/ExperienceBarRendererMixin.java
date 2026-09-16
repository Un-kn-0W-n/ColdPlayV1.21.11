package cn.timer.coldplay.client.mixin;

import cn.timer.coldplay.client.hud.StatusBars;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.contextualbar.ExperienceBarRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Swaps the vanilla experience bar for the HUD's level number and thin rail. */
@Mixin(ExperienceBarRenderer.class)
abstract class ExperienceBarRendererMixin {
    @Inject(method = "renderBackground", at = @At("HEAD"), cancellable = true)
    private void coldplay$rail(GuiGraphics graphics, DeltaTracker deltaTracker, CallbackInfo callback) {
        if (StatusBars.active() && StatusBars.renderExperience(graphics, Minecraft.getInstance().player)) {
            callback.cancel();
        }
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void coldplay$skipForeground(GuiGraphics graphics, DeltaTracker deltaTracker, CallbackInfo callback) {
        if (StatusBars.active()) {
            callback.cancel();
        }
    }
}
