package cn.timer.coldplay.client.module.impl.combat;

import cn.timer.coldplay.client.manager.RotationManager;
import cn.timer.coldplay.client.module.Category;
import cn.timer.coldplay.client.module.Module;
import cn.timer.coldplay.client.setting.BooleanSetting;
import cn.timer.coldplay.client.setting.ModeSetting;
import cn.timer.coldplay.client.setting.NumberSetting;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.Npc;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public final class AimAssist extends Module {
    private static final String CHILL = "Chill";
    private static final String NORMAL = "Normal";
    private static final String AGGRESSIVE = "Aggressive";
    private static final double TURN_SCALE = 0.15;
    private static final EntityTypeTest<Entity, LivingEntity> LIVING_ENTITIES =
            EntityTypeTest.forClass(LivingEntity.class);

    private static final Profile CHILL_PROFILE = new Profile(2.0, 25.0, 0.50);
    private static final Profile NORMAL_PROFILE = new Profile(5.0, 60.0, 0.70);
    private static final Profile AGGRESSIVE_PROFILE = new Profile(10.0, 120.0, 0.90);

    private final ModeSetting assistance = addSetting(
            new ModeSetting("Assistance", NORMAL, CHILL, NORMAL, AGGRESSIVE));
    private final NumberSetting range = addSetting(new NumberSetting("Range", 4.5, 1.0, 12.0, 0.5));
    private final NumberSetting fov = addSetting(new NumberSetting("FOV", 45.0, 1.0, 180.0, 1.0));
    private final BooleanSetting players = addSetting(new BooleanSetting("Players", true));
    private final BooleanSetting mobs = addSetting(new BooleanSetting("Mobs", false));
    private final BooleanSetting animals = addSetting(new BooleanSetting("Animals", false));
    private final BooleanSetting invisibles = addSetting(new BooleanSetting("Invisibles", false));
    private final BooleanSetting npc = addSetting(new BooleanSetting("NPC", false));

    private final List<LivingEntity> candidates = new ArrayList<>();
    private LivingEntity target;
    private LocalPlayer trackedPlayer;
    private ClientLevel trackedLevel;
    private float yawRemainder;
    private float pitchRemainder;

    public AimAssist() {
        super("AimAssist", "Smoothly guides your aim toward valid targets", Category.COMBAT,
                GLFW.GLFW_KEY_UNKNOWN);
    }

    @Override
    protected void onDisable() {
        clear();
        trackedPlayer = null;
        trackedLevel = null;
    }

    @Override
    protected void onRender(DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;

        if (player != trackedPlayer || level != trackedLevel) {
            clear();
            trackedPlayer = player;
            trackedLevel = level;
        }
        if (player == null || level == null || !player.isAlive() || player.isSpectator()
                || minecraft.getCameraEntity() != player || minecraft.screen != null
                || !minecraft.isWindowActive() || !minecraft.mouseHandler.isMouseGrabbed()) {
            clear();
            return;
        }

        Camera camera = minecraft.gameRenderer.getMainCamera();
        if (!camera.isInitialized()) {
            clear();
            return;
        }

        Profile profile = profile();
        LivingEntity selected = selectTarget(minecraft, player, level, camera, profile.switchRatio());
        if (selected != target) {
            target = selected;
            clearRemainders();
        }
        if (target == null) {
            clearRemainders();
            return;
        }

        Vec3 direction = targetPoint(target, camera.getPartialTickTime()).subtract(camera.position());
        if (direction.lengthSqr() == 0.0) {
            clearRemainders();
            return;
        }

        Vec2 rotation = KillAura.rotationTo(direction);
        double seconds = Math.clamp(deltaTracker.getRealtimeDeltaTicks() * 0.05, 0.0, 0.1);
        float yawError = Mth.wrapDegrees(rotation.y - camera.yRot());
        float pitchError = Mth.clamp(rotation.x, -90.0F, 90.0F) - camera.xRot();
        float yawStep = yawStep(rotation.y, camera.yRot(), profile.response(), profile.turnRate(), seconds);
        float pitchStep = smoothedStep(pitchError, profile.response(), profile.turnRate(), seconds);
        float quantum = RotationManager.currentQuantum(minecraft, player);
        yawStep = quantizeYaw(yawStep, yawError, quantum);
        pitchStep = quantizePitch(pitchStep, pitchError, quantum);

        if (yawStep != 0.0F || pitchStep != 0.0F) {
            player.turn(yawStep / TURN_SCALE, pitchStep / TURN_SCALE);
        }
    }

    private LivingEntity selectTarget(Minecraft minecraft, LocalPlayer player, ClientLevel level,
                                      Camera camera, double switchRatio) {
        float partialTick = camera.getPartialTickTime();
        Vec3 playerPosition = player.getPosition(partialTick);
        Vec3 cameraPosition = camera.position();
        Vec3 look = Vec3.directionFromRotation(camera.xRot(), camera.yRot());
        double range = this.range.get();

        candidates.clear();
        level.getEntities(LIVING_ENTITIES, player.getBoundingBox().inflate(range),
                entity -> entity != player, candidates);

        LivingEntity current = target;
        double currentAngle = score(minecraft, player, current, playerPosition, cameraPosition, look,
                partialTick, range);
        if (!Double.isFinite(currentAngle)) {
            current = null;
        }

        LivingEntity best = current;
        double bestAngle = current == null ? Double.POSITIVE_INFINITY : currentAngle;
        for (LivingEntity candidate : candidates) {
            if (candidate == current) {
                continue;
            }
            double angle = score(minecraft, player, candidate, playerPosition, cameraPosition, look,
                    partialTick, range);
            if (angle < bestAngle) {
                best = candidate;
                bestAngle = angle;
            }
        }
        candidates.clear();

        return current != null && best != current && !shouldSwitch(currentAngle, bestAngle, switchRatio)
                ? current : best;
    }

    private double score(Minecraft minecraft, LocalPlayer player, LivingEntity candidate,
                         Vec3 playerPosition, Vec3 cameraPosition, Vec3 look,
                         float partialTick, double range) {
        if (!validTarget(minecraft, player, candidate)) {
            return Double.POSITIVE_INFINITY;
        }

        Vec3 position = candidate.getPosition(partialTick);
        if (playerPosition.distanceToSqr(position) > range * range) {
            return Double.POSITIVE_INFINITY;
        }

        Vec3 direction = targetPoint(candidate, partialTick).subtract(cameraPosition);
        if (direction.lengthSqr() == 0.0) {
            return Double.POSITIVE_INFINITY;
        }
        double dot = Math.clamp(look.dot(direction.normalize()), -1.0, 1.0);
        double angle = Math.toDegrees(Math.acos(dot));
        return angle <= fov.get() && player.hasLineOfSight(candidate)
                ? angle : Double.POSITIVE_INFINITY;
    }

    private boolean validTarget(Minecraft minecraft, LocalPlayer player, LivingEntity candidate) {
        if (candidate == null || candidate == player || candidate.isRemoved() || !candidate.isAlive()
                || !candidate.isPickable() || !candidate.isAttackable() || !player.canAttack(candidate)
                || candidate.isInvisible() && !invisibles.get()) {
            return false;
        }
        if (candidate instanceof Player other && !player.canHarmPlayer(other)) {
            return false;
        }
        if (AntiBot.get().isBot(candidate)) {
            return false;
        }
        if (candidate instanceof Player) {
            return players.get();
        }
        if (candidate instanceof Npc) {
            return npc.get();
        }
        if (candidate instanceof Enemy) {
            return mobs.get();
        }
        return candidate instanceof Animal && animals.get();
    }

    private Profile profile() {
        return switch (assistance.get()) {
            case CHILL -> CHILL_PROFILE;
            case AGGRESSIVE -> AGGRESSIVE_PROFILE;
            default -> NORMAL_PROFILE;
        };
    }

    private static Vec3 targetPoint(LivingEntity target, float partialTick) {
        return target.getPosition(partialTick).add(0.0, target.getBbHeight() * 0.5, 0.0);
    }

    static float yawStep(float target, float current, double response, double turnRate, double seconds) {
        return smoothedStep(Mth.wrapDegrees(target - current), response, turnRate, seconds);
    }

    static float smoothedStep(float error, double response, double turnRate, double seconds) {
        if (error == 0.0F || seconds <= 0.0) {
            return 0.0F;
        }
        double step = error * -Math.expm1(-response * seconds);
        double maximum = Math.min(turnRate * seconds, 6.0);
        return (float) Math.clamp(step, -maximum, maximum);
    }

    static boolean shouldSwitch(double currentAngle, double candidateAngle, double ratio) {
        return candidateAngle < currentAngle * ratio;
    }

    float quantizeYaw(float step, float error, float quantum) {
        return quantize(step, error, quantum, true);
    }

    private float quantizePitch(float step, float error, float quantum) {
        return quantize(step, error, quantum, false);
    }

    private float quantize(float step, float error, float quantum, boolean yaw) {
        float remainder = yaw ? yawRemainder : pitchRemainder;
        if (step == 0.0F || quantum <= 0.0F || Math.abs(error) < quantum
                || Math.signum(step) != Math.signum(error)) {
            setRemainder(yaw, 0.0F);
            return quantum > 0.0F ? 0.0F : step;
        }
        if (remainder != 0.0F && Math.signum(remainder) != Math.signum(step)) {
            remainder = 0.0F;
        }

        float requested = step + remainder;
        float snapped = RotationManager.snapToQuantum(requested, quantum);
        if (Math.abs(snapped) > Math.abs(error)) {
            snapped = Math.copySign((float) Math.floor(Math.abs(error) / quantum) * quantum, error);
        }
        setRemainder(yaw, requested - snapped);
        return snapped;
    }

    private void setRemainder(boolean yaw, float value) {
        if (yaw) {
            yawRemainder = value;
        } else {
            pitchRemainder = value;
        }
    }

    private void clear() {
        target = null;
        candidates.clear();
        clearRemainders();
    }

    private void clearRemainders() {
        yawRemainder = 0.0F;
        pitchRemainder = 0.0F;
    }

    private record Profile(double response, double turnRate, double switchRatio) {
    }
}
 
