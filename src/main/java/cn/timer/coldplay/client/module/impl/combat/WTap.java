package cn.timer.coldplay.client.module.impl.combat;

import cn.timer.coldplay.client.module.Category;
import cn.timer.coldplay.client.module.Module;
import cn.timer.coldplay.client.setting.BooleanSetting;
import cn.timer.coldplay.client.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
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
    private static final int IDLE = 0;
    private static final int INTERRUPT = 1;
    private static final int RESTORE = 2;

    private final NumberSetting chance = addSetting(new NumberSetting("Chance", 100.0, 1.0, 100.0, 1.0));
    private final BooleanSetting sTap = addOwnerSetting(new BooleanSetting("STap", false));
    private final NumberSetting distance = addChildSetting(sTap,
            new NumberSetting("Distance", 3.0, 0.0, 6.0, 0.1));

    private int phase;
    private boolean backTap;
    private LocalPlayer sequencePlayer;
    private ClientLevel sequenceLevel;

    public WTap() {
        super("WTap", "Briefly resets movement after a sprint attack", Category.COMBAT,
                GLFW.GLFW_KEY_UNKNOWN);
    }

    public InteractionResult onAttack(Player player, Level level, InteractionHand hand, Entity entity,
                                      @Nullable EntityHitResult hitResult) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer localPlayer = minecraft.player;
        if (!enabled() || phase != IDLE || player != localPlayer || level != minecraft.level
                || hand != InteractionHand.MAIN_HAND || !canMove(minecraft, localPlayer)
                || !localPlayer.isSprinting() || !(entity instanceof LivingEntity target)
                || !validTarget(localPlayer, target)) {
            return InteractionResult.PASS;
        }
        if (!activates(chance.get(), ThreadLocalRandom.current().nextDouble(100.0))) {
            return InteractionResult.PASS;
        }

        backTap = sTap.get() && withinDistance(localPlayer.distanceToSqr(target), distance.get());
        sequencePlayer = localPlayer;
        sequenceLevel = minecraft.level;
        phase = INTERRUPT;
        return InteractionResult.PASS;
    }

    public void tick(Minecraft minecraft) {
        if (phase != IDLE && !validSequence(minecraft)) {
            clear();
        }
    }

    public Input applyInput(Minecraft minecraft, Input input) {
        if (phase == IDLE) {
            return input;
        }
        if (!validSequence(minecraft)) {
            clear();
            return input;
        }
        if (phase == RESTORE) {
            clear();
            return input;
        }

        phase = RESTORE;
        return tapInput(input, backTap);
    }

    @Override
    protected void onEnable() {
        clear();
    }

    @Override
    protected void onDisable() {
        clear();
    }

    private boolean validSequence(Minecraft minecraft) {
        return enabled() && minecraft.player == sequencePlayer && minecraft.level == sequenceLevel
                && canMove(minecraft, sequencePlayer);
    }

    private static boolean canMove(Minecraft minecraft, LocalPlayer player) {
        return player != null && minecraft.level != null && minecraft.gameMode != null
                && player.isAlive() && !player.isSpectator() && !player.isSleeping() && !player.isPassenger()
                && minecraft.screen == null && minecraft.isWindowActive()
                && minecraft.mouseHandler.isMouseGrabbed();
    }

    static boolean validTarget(LocalPlayer player, LivingEntity target) {
        return target != player && !target.isRemoved()
                && EntitySelector.LIVING_ENTITY_STILL_ALIVE.test(target)
                && EntitySelector.NO_CREATIVE_OR_SPECTATOR.test(target)
                && target.isPickable() && target.isAttackable() && player.canAttack(target)
                && (!(target instanceof Player other) || player.canHarmPlayer(other))
                && AntiBot.get().canTarget(target);
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

    private void clear() {
        phase = IDLE;
        backTap = false;
        sequencePlayer = null;
        sequenceLevel = null;
    }
}
