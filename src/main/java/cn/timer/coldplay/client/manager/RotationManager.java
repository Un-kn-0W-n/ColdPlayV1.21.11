package cn.timer.coldplay.client.manager;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

public final class RotationManager {
    private static final RotationManager INSTANCE = new RotationManager();

    private LocalPlayer trackedPlayer;
    private float serverYaw;
    private float serverPitch;
    private boolean active;

    private Object requestOwner;
    private float requestYaw;
    private float requestPitch;
    private int requestPriority;
    private double requestTurnRate;
    private boolean hasRequest;

    private Object lastOwner;
    private int lastPriority = Integer.MIN_VALUE;

    private RotationManager() {
    }

    public static RotationManager getInstance() {
        return INSTANCE;
    }

    public void request(Object owner, float yaw, float pitch, int priority, double turnRate) {
        Objects.requireNonNull(owner, "owner");
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)
                || !Double.isFinite(turnRate) || turnRate < 0.0) {
            throw new IllegalArgumentException("Rotation and turn rate must be finite; turn rate cannot be negative");
        }
        if (hasRequest && priority <= requestPriority) {
            return;
        }

        requestOwner = owner;
        requestYaw = yaw;
        requestPitch = pitch;
        requestPriority = priority;
        requestTurnRate = turnRate;
        hasRequest = true;
    }

    public void onRender(DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        track(player);

        if (!hasRequest || player == null || minecraft.level == null || minecraft.screen != null
                || !minecraft.isWindowActive() || !minecraft.mouseHandler.isMouseGrabbed()) {
            release(player);
            return;
        }

        if (!active) {
            serverYaw = player.getYRot();
            serverPitch = player.getXRot();
            active = true;
        }

        float maxStep = (float) Math.min(requestTurnRate
                * Mth.clamp(deltaTracker.getRealtimeDeltaTicks(), 0.0F, 10.0F), Float.MAX_VALUE);
        float quantum = currentQuantum(minecraft, player);
        serverYaw += snapToQuantum(Mth.clamp(yawDelta(requestYaw, serverYaw), -maxStep, maxStep), quantum);
        serverPitch = clampPitch(serverPitch + snapToQuantum(
                Mth.clamp(clampPitch(requestPitch) - serverPitch, -maxStep, maxStep), quantum));

        if (requestOwner != lastOwner) {
            MoveFix.reset();
        }
        lastOwner = requestOwner;
        lastPriority = requestPriority;
        requestOwner = null;
        hasRequest = false;
    }

    public void resetAfterServerCorrection(LocalPlayer player) {
        trackedPlayer = player;
        if (player != null) {
            serverYaw = player.getYRot();
            serverPitch = player.getXRot();
        }
        clear();
    }

    public boolean isActive() {
        return active;
    }

    public boolean owns(Object owner) {
        return active && lastOwner == owner;
    }

    public boolean isBusyAbove(int priority) {
        return active && lastPriority > priority;
    }

    public float getServerYaw() {
        return serverYaw;
    }

    public float getServerPitch() {
        return serverPitch;
    }

    public Vec3 getServerLookVector() {
        return Vec3.directionFromRotation(serverPitch, serverYaw);
    }

    public HitResult getHitResult() {
        return active ? Minecraft.getInstance().hitResult : null;
    }

    public BlockHitResult getBlockHitResult() {
        HitResult hit = getHitResult();
        return hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK ? block : null;
    }

    public EntityHitResult getEntityHitResult() {
        HitResult hit = getHitResult();
        return hit instanceof EntityHitResult entity ? entity : null;
    }

    public Vec2 remapMovement(Vec2 movement, float cameraYaw) {
        if (!active) {
            MoveFix.reset();
            return null;
        }
        return MoveFix.remap(movement, cameraYaw, serverYaw);
    }

    private void track(LocalPlayer player) {
        if (trackedPlayer == player) {
            return;
        }
        trackedPlayer = player;
        active = false;
        lastOwner = null;
        lastPriority = Integer.MIN_VALUE;
        MoveFix.reset();
        if (player != null) {
            serverYaw = player.getYRot();
            serverPitch = player.getXRot();
        }
    }

    private void release(LocalPlayer player) {
        if (active && player != null) {
            float turns = Math.round((serverYaw - player.getYRot()) / 360.0F) * 360.0F;
            if (turns != 0.0F) {
                player.setYRot(player.getYRot() + turns);
                player.yRotO += turns;
            }
        }
        clear();
    }

    private void clear() {
        active = false;
        requestOwner = null;
        hasRequest = false;
        lastOwner = null;
        lastPriority = Integer.MIN_VALUE;
        MoveFix.reset();
    }

    public static float currentQuantum(Minecraft minecraft, LocalPlayer player) {
        boolean scoped = !minecraft.options.smoothCamera
                && minecraft.options.getCameraType().isFirstPerson() && player.isScoping();
        return sensitivityQuantum(minecraft.options.sensitivity().get(), scoped ? 0.15F : 1.2F);
    }

    static float sensitivityQuantum(double sensitivity) {
        return sensitivityQuantum(sensitivity, 1.2F);
    }

    private static float sensitivityQuantum(double sensitivity, float scale) {
        float base = (float) (sensitivity * 0.6 + 0.2);
        return base * base * base * scale;
    }

    public static float snapToQuantum(float delta, float quantum) {
        return quantum > 0.0F ? Math.round(delta / quantum) * quantum : delta;
    }

    static float yawDelta(float target, float current) {
        return Mth.wrapDegrees(target - current);
    }

    static float clampPitch(float pitch) {
        return Mth.clamp(pitch, -90.0F, 90.0F);
    }
}
