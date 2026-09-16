package cn.timer.coldplay.client.manager;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

final class MoveFix {
    // ponytail: prediction calibration knob; tune only if live boundary flapping is observed.
    private static final double HYSTERESIS = 0.06;

    private static int previousStrafe;
    private static int previousForward;

    private MoveFix() {
    }

    static void reset() {
        previousStrafe = 0;
        previousForward = 0;
    }

    static Vec2 remap(Vec2 movement, float cameraYaw, float serverYaw) {
        if (movement.lengthSquared() == 0.0F || cameraYaw == serverYaw) {
            reset();
            return null;
        }

        Vec3 intended = worldDirection(movement.x, movement.y, cameraYaw);
        int bestStrafe = 0;
        int bestForward = 0;
        double bestDot = -Double.MAX_VALUE;

        for (int strafe = -1; strafe <= 1; strafe++) {
            for (int forward = -1; forward <= 1; forward++) {
                if (strafe == 0 && forward == 0) {
                    continue;
                }
                double dot = intended.dot(worldDirection(strafe, forward, serverYaw));
                if (dot > bestDot) {
                    bestDot = dot;
                    bestStrafe = strafe;
                    bestForward = forward;
                }
            }
        }

        if (previousStrafe != 0 || previousForward != 0) {
            double previousDot = intended.dot(worldDirection(previousStrafe, previousForward, serverYaw));
            if (bestDot - previousDot < HYSTERESIS) {
                bestStrafe = previousStrafe;
                bestForward = previousForward;
            }
        }

        previousStrafe = bestStrafe;
        previousForward = bestForward;
        return new Vec2(bestStrafe, bestForward);
    }

    private static Vec3 worldDirection(float strafe, float forward, float yaw) {
        return new Vec3(strafe, 0.0, forward).normalize().yRot(-yaw * Mth.DEG_TO_RAD);
    }
}
