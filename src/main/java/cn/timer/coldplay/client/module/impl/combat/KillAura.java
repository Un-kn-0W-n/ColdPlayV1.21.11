package cn.timer.coldplay.client.module.impl.combat;

import cn.timer.coldplay.client.manager.RotationManager;
import cn.timer.coldplay.client.module.Category;
import cn.timer.coldplay.client.module.Module;
import cn.timer.coldplay.client.setting.BooleanSetting;
import cn.timer.coldplay.client.setting.NumberSetting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.Npc;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.Comparator;
import java.util.concurrent.ThreadLocalRandom;

public final class KillAura extends Module {
    private static final int ROTATION_PRIORITY = 100;
    private static final double TURN_RATE = 180.0;

    private final BooleanSetting players = addSetting(new BooleanSetting("Players", true));
    private final BooleanSetting mobs = addSetting(new BooleanSetting("Mobs", false));
    private final BooleanSetting animals = addSetting(new BooleanSetting("Animals", false));
    private final BooleanSetting invisible = addSetting(new BooleanSetting("Invisible", false));
    private final BooleanSetting npc = addSetting(new BooleanSetting("NPC", false));
    private final NumberSetting range = addSetting(new NumberSetting("Range", 4.0, 1.0, 6.0, 0.1));
    private final NumberSetting fov = addSetting(new NumberSetting("FOV", 90.0, 10.0, 360.0, 1.0));
    private final NumberSetting minCps = addSetting(new NumberSetting("Min CPS", 8.0, 1.0, 20.0, 1.0));
    private final NumberSetting maxCps = addSetting(new NumberSetting("Max CPS", 12.0, 1.0, 20.0, 1.0));

    private LivingEntity target;
    private long nextAttackAt;

    public KillAura() {
        super("KillAura", "Silently attacks the nearest valid target", Category.COMBAT, GLFW.GLFW_KEY_UNKNOWN);
    }

    @Override
    protected void onEnable() {
        target = null;
        nextAttackAt = System.nanoTime();
    }

    @Override
    protected void onDisable() {
        target = null;
    }

    @Override
    protected void onRender(DeltaTracker ignored) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        target = null;
        if (player == null || minecraft.level == null || minecraft.gameMode == null || minecraft.screen != null
                || !minecraft.isWindowActive() || !minecraft.mouseHandler.isMouseGrabbed()) {
            return;
        }

        Vec3 eyes = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        target = minecraft.level.getEntitiesOfClass(
                        LivingEntity.class,
                        player.getBoundingBox().inflate(range.get()),
                        entity -> validTarget(player, entity) && player.closerThan(entity, range.get())
                                && withinFov(look, entity.getBoundingBox().getCenter().subtract(eyes), fov.get()))
                .stream()
                .min(Comparator.comparingDouble(player::distanceToSqr))
                .orElse(null);
        if (target == null) {
            return;
        }

        Vec3 direction = target.getBoundingBox().getCenter().subtract(eyes);
        Vec2 rotation = rotationTo(direction);
        RotationManager rotations = RotationManager.getInstance();
        rotations.request(this, rotation.y, rotation.x, ROTATION_PRIORITY, TURN_RATE);
    }

    public void preTick(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        LivingEntity victim = target;
        if (player == null || minecraft.gameMode == null || victim == null || minecraft.screen != null
                || !minecraft.isWindowActive() || !minecraft.mouseHandler.isMouseGrabbed()
                || !validTarget(player, victim) || !player.closerThan(victim, range.get())) {
            return;
        }

        RotationManager rotations = RotationManager.getInstance();
        EntityHitResult hit = rotations.getEntityHitResult();
        long now = System.nanoTime();
        if (rotations.owns(this) && hit != null && hit.getEntity() == victim
                && now - nextAttackAt >= 0L && !player.isUsingItem()) {
            minecraft.gameMode.attack(player, victim);
            player.swing(InteractionHand.MAIN_HAND);
            nextAttackAt = now + clickDelayMillis(minCps.get(), maxCps.get(),
                    ThreadLocalRandom.current().nextDouble()) * 1_000_000L;
        }
    }

    private boolean validTarget(LocalPlayer player, LivingEntity entity) {
        if (entity == player || !EntitySelector.LIVING_ENTITY_STILL_ALIVE.test(entity)
                || !EntitySelector.NO_CREATIVE_OR_SPECTATOR.test(entity) || !entity.isAttackable()
                || (!invisible.get() && entity.isInvisibleTo(player)) || AntiBot.get().isBot(entity)) {
            return false;
        }
        if (entity instanceof Player) {
            return players.get();
        }
        if (entity instanceof Enemy) {
            return mobs.get();
        }
        if (entity instanceof Animal) {
            return animals.get();
        }
        return entity instanceof Npc && npc.get();
    }

    static Vec2 rotationTo(Vec3 direction) {
        return new Vec2(
                (float) -Mth.atan2(direction.y, direction.horizontalDistance()) * Mth.RAD_TO_DEG,
                (float) Mth.atan2(direction.z, direction.x) * Mth.RAD_TO_DEG - 90.0F);
    }

    static boolean withinFov(Vec3 look, Vec3 direction, double fov) {
        return fov >= 360.0
                || look.dot(direction.normalize()) + 1.0E-7 >= Math.cos(Math.toRadians(fov * 0.5));
    }

    static long clickDelayMillis(double minCps, double maxCps, double sample) {
        double low = Math.min(minCps, maxCps);
        double high = Math.max(minCps, maxCps);
        double cps = low + (high - low) * Math.clamp(sample, 0.0, 1.0);
        return Math.max(1L, Math.round(1000.0 / cps));
    }
}
