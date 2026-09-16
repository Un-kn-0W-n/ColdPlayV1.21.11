package cn.timer.coldplay.client.module.impl.combat;

import cn.timer.coldplay.client.alt.AltManagerSelfCheck;
import cn.timer.coldplay.client.manager.RotationManagerSelfCheck;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

public final class KillAuraSelfCheck {
    private KillAuraSelfCheck() {
    }

    public static void main(String[] args) {
        RotationManagerSelfCheck.main(args);
        AntiBotSelfCheck.main(args);
        AimAssistSelfCheck.main(args);
        WTapSelfCheck.main(args);
        BackTrackSelfCheck.main(args);
        AltManagerSelfCheck.main(args);

        Vec2 south = KillAura.rotationTo(new Vec3(0.0, 0.0, 1.0));
        assert close(south.x, 0.0F) && close(south.y, 0.0F);
        Vec2 above = KillAura.rotationTo(new Vec3(0.0, 1.0, 0.0));
        assert close(above.x, -90.0F);

        Vec3 forward = new Vec3(0.0, 0.0, 1.0);
        assert KillAura.withinFov(forward, new Vec3(1.0, 0.0, 1.0), 90.0);
        assert !KillAura.withinFov(forward, new Vec3(1.0, 0.0, 0.0), 90.0);
        assert KillAura.withinFov(forward, new Vec3(0.0, 0.0, -1.0), 360.0);

        assert KillAura.clickDelayMillis(8.0, 12.0, 0.0) == 125L;
        assert KillAura.clickDelayMillis(8.0, 12.0, 1.0) == 83L;
        assert KillAura.clickDelayMillis(12.0, 8.0, 0.0) == 125L;
    }

    private static boolean close(float first, float second) {
        return Math.abs(first - second) < 1.0E-5F;
    }
}
