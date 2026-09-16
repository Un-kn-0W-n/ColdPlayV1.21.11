package cn.timer.coldplay.client.mixin;

import cn.timer.coldplay.client.ClientCore;
import cn.timer.coldplay.client.manager.RotationManager;
import cn.timer.coldplay.client.module.impl.combat.WTap;
import cn.timer.coldplay.client.module.impl.movement.Sprint;
import cn.timer.coldplay.client.module.impl.movement.Velocity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
abstract class KeyboardInputMixin extends ClientInput {
    @ModifyArg(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Input;<init>(ZZZZZZZ)V"), index = 6)
    private boolean coldplay$sprint(boolean sprint) {
        return sprint || ClientCore.get().modules().get(Sprint.class).enabled()
                && Minecraft.getInstance().options.keyUp.isDown();
    }

    /** Velocity rides the jump key rather than the motion, so vanilla's own jump gate still decides. */
    @ModifyArg(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Input;<init>(ZZZZZZZ)V"), index = 4)
    private boolean coldplay$jump(boolean jump) {
        if (jump) {
            return true;
        }
        ClientCore core = ClientCore.get();
        Velocity velocity = core.initialized() ? core.modules().get(Velocity.class) : null;
        return velocity != null && velocity.wantsJump(Minecraft.getInstance().player);
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void coldplay$moveFix(CallbackInfo callback) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return;
        }

        Vec2 remapped = RotationManager.getInstance().remapMovement(moveVector, player.getYRot());
        if (remapped != null) {
            Input original = keyPresses;
            keyPresses = new Input(
                    remapped.y > 0.0F,
                    remapped.y < 0.0F,
                    remapped.x > 0.0F,
                    remapped.x < 0.0F,
                    original.jump(),
                    original.shift(),
                    original.sprint()
            );
            moveVector = remapped.normalized();
        }

        ClientCore core = ClientCore.get();
        WTap wTap = core.initialized() ? core.modules().get(WTap.class) : null;
        Input tapped = wTap == null ? keyPresses : wTap.applyInput(keyPresses);
        if (tapped != keyPresses) {
            keyPresses = tapped;
            moveVector = new Vec2(
                    tapped.left() == tapped.right() ? 0.0F : tapped.left() ? 1.0F : -1.0F,
                    tapped.forward() == tapped.backward() ? 0.0F : tapped.forward() ? 1.0F : -1.0F
            ).normalized();
        }
    }
}
