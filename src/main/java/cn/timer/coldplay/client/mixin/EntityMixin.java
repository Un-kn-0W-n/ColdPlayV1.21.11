package cn.timer.coldplay.client.mixin;

import cn.timer.coldplay.client.module.impl.combat.HitBoxes;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Entity.class)
abstract class EntityMixin {
    /** The crosshair pick inflates each hitbox by this radius, so this is the hitbox it hits. */
    @ModifyReturnValue(method = "getPickRadius", at = @At("RETURN"))
    private float coldplay$expandHitbox(float original) {
        return original + HitBoxes.get().expansion((Entity) (Object) this);
    }
}
