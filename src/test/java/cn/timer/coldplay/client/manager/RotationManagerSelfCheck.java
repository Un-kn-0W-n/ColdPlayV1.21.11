package cn.timer.coldplay.client.manager;

import cn.timer.coldplay.client.module.impl.visuals.HudSelfCheck;
import net.minecraft.world.phys.Vec2;

public final class RotationManagerSelfCheck {
    private RotationManagerSelfCheck() {
    }

    public static void main(String[] args) {
        HudSelfCheck.main(args);

        float quantum = RotationManager.sensitivityQuantum(0.5);
        assert quantum > 0.0F;
        assert close(RotationManager.snapToQuantum(2.4F * quantum, quantum), 2.0F * quantum);
        assert close(RotationManager.snapToQuantum(-2.6F * quantum, quantum), -3.0F * quantum);
        assert close(RotationManager.yawDelta(-179.0F, 179.0F), 2.0F);
        assert RotationManager.clampPitch(100.0F) == 90.0F;
        assert RotationManager.clampPitch(-100.0F) == -90.0F;

        Vec2 forward = new Vec2(0.0F, 1.0F);
        MoveFix.reset();
        assert vector(MoveFix.remap(forward, 0.0F, 90.0F), 1.0F, 0.0F);
        MoveFix.reset();
        assert vector(MoveFix.remap(forward, 0.0F, 180.0F), 0.0F, -1.0F);
        MoveFix.reset();
        assert vector(MoveFix.remap(new Vec2(1.0F, 1.0F).normalized(), 0.0F, 90.0F),
                1.0F, -1.0F);
        assert MoveFix.remap(Vec2.ZERO, 0.0F, 90.0F) == null;

        MoveFix.reset();
        assert vector(MoveFix.remap(forward, 0.0F, 22.0F), 0.0F, 1.0F);
        assert vector(MoveFix.remap(forward, 0.0F, 23.0F), 0.0F, 1.0F);
        assert vector(MoveFix.remap(forward, 0.0F, 35.0F), 1.0F, 1.0F);
    }

    private static boolean vector(Vec2 vector, float x, float y) {
        return vector != null && vector.x == x && vector.y == y;
    }

    private static boolean close(float first, float second) {
        return Math.abs(first - second) < 1.0E-6F;
    }
}
