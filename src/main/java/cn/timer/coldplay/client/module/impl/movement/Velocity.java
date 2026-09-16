package cn.timer.coldplay.client.module.impl.movement;

import cn.timer.coldplay.client.module.Category;
import cn.timer.coldplay.client.module.Module;
import cn.timer.coldplay.client.setting.ModeSetting;
import cn.timer.coldplay.client.setting.NumberSetting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.concurrent.ThreadLocalRandom;

public final class Velocity extends Module {
    public static final String LEGIT = "Legit";

    private final ModeSetting mode = addSetting(new ModeSetting("Mode", LEGIT, LEGIT));
    private final NumberSetting chance = addSetting(new NumberSetting("Chance", 100.0, 1.0, 100.0, 1.0));

    public Velocity() {
        super("Velocity", "Jump resets the knockback you take", Category.MOVEMENT, GLFW.GLFW_KEY_UNKNOWN);
    }

    public boolean uses(String mode) {
        return enabled() && this.mode.get().equals(mode);
    }

    public boolean wantsJump(LocalPlayer player) {
        if (player == null || !uses(LEGIT)) {
            return false;
        }
        Vec3 motion = player.getDeltaMovement();
        return knockbackPending(player.onGround(), player.isSprinting(), player.isPassenger(),
                        player.hurtTime, motion.y)
                && boostOpposes(player.getYRot(), motion.x, motion.z)
                && activates(chance.get(), ThreadLocalRandom.current().nextDouble(100.0));
    }

    static boolean knockbackPending(boolean onGround, boolean sprinting, boolean passenger, int hurtTime,
                                    double upwardMotion) {
        return onGround && sprinting && !passenger && hurtTime > 0 && upwardMotion > 0.0;
    }

    static boolean boostOpposes(float yRot, double motionX, double motionZ) {
        float radians = yRot * Mth.DEG_TO_RAD;
        return -Mth.sin(radians) * motionX + Mth.cos(radians) * motionZ < 0.0;
    }

    static boolean activates(double chance, double sample) {
        return sample < chance;
    }
}
