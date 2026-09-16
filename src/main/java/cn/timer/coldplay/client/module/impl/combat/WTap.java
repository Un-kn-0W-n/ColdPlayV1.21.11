package cn.timer.coldplay.client.module.impl.combat;

import cn.timer.coldplay.client.module.Category;
import cn.timer.coldplay.client.module.Module;
import cn.timer.coldplay.client.setting.BooleanSetting;
import cn.timer.coldplay.client.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.concurrent.ThreadLocalRandom;

public final class WTap extends Module {
    private enum Phase {
        IDLE,
        TAP,
        RESUME
    }

    private final NumberSetting chance = addSetting(new NumberSetting("Chance", 100.0, 1.0, 100.0, 1.0));
    private final BooleanSetting sTap = addOwnerSetting(new BooleanSetting("STap", false));
    private final NumberSetting distance = addChildSetting(sTap,
            new NumberSetting("Distance", 3.0, 0.0, 6.0, 0.1));

    private Phase phase = Phase.IDLE;
    private boolean backTap;

    public WTap() {
        super("WTap", "Briefly resets movement after a sprint attack", Category.COMBAT,
                GLFW.GLFW_KEY_UNKNOWN);
    }

    public InteractionResult onAttack(Player player, Level level, InteractionHand hand, Entity entity,
                                      @Nullable EntityHitResult hitResult) {
        LocalPlayer localPlayer = Minecraft.getInstance().player;
        if (!enabled() || phase != Phase.IDLE || player != localPlayer
                || hand != InteractionHand.MAIN_HAND || !localPlayer.isSprinting()) {
            return InteractionResult.PASS;
        }
        if (!activates(chance.get(), ThreadLocalRandom.current().nextDouble(100.0))) {
            return InteractionResult.PASS;
        }

        backTap = sTap.get() && withinDistance(localPlayer.distanceToSqr(entity), distance.get());
        phase = Phase.TAP;
        return InteractionResult.PASS;
    }

    public void tick(Minecraft minecraft) {
        if (minecraft.player == null) {
            clear();
        }
    }

    public Input applyInput(Input input) {
        switch (phase) {
            case TAP -> {
                phase = Phase.RESUME;
                return tapInput(input, backTap);
            }
            case RESUME -> {
                clear();
                return resumeInput(input);
            }
            default -> {
                return input;
            }
        }
    }

    @Override
    protected void onDisable() {
        clear();
    }

    static boolean validTarget(LocalPlayer player, LivingEntity target) {
        return target != player && target.isAlive()
                && EntitySelector.NO_CREATIVE_OR_SPECTATOR.test(target)
                && target.isPickable() && target.isAttackable() && player.canAttack(target)
                && (!(target instanceof Player other) || player.canHarmPlayer(other))
                && !AntiBot.get().isBot(target);
    }

    static boolean activates(double chance, double sample) {
        return sample < chance;
    }

    static boolean withinDistance(double squaredDistance, double distance) {
        return squaredDistance <= distance * distance;
    }

    static Input tapInput(Input input, boolean backTap) {
        return new Input(false, backTap || input.backward(), input.left(), input.right(), input.jump(),
                input.shift(), input.sprint());
    }

    static Input resumeInput(Input input) {
        return new Input(input.forward(), input.backward(), input.left(), input.right(), input.jump(),
                input.shift(), true);
    }

    private void clear() {
        phase = Phase.IDLE;
        backTap = false;
    }
}
