package cn.timer.coldplay.client.module.impl.combat;

import cn.timer.coldplay.client.manager.RotationManager;

public final class AimAssistSelfCheck {
    private AimAssistSelfCheck() {
    }

    public static void main(String[] args) {
        float wrapped = AimAssist.yawStep(-179.0F, 179.0F, 5.0, 60.0, 0.05);
        assert close(wrapped, AimAssist.smoothedStep(2.0F, 5.0, 60.0, 0.05));
        assert close(converge(20), converge(120));
        assert AimAssist.smoothedStep(180.0F, 10.0, 120.0, 0.05) == 6.0F;

        assert AimAssist.shouldSwitch(20.0, 9.0, 0.50);
        assert !AimAssist.shouldSwitch(20.0, 11.0, 0.50);
        assert AimAssist.shouldSwitch(20.0, 17.0, 0.90);

        AimAssist assist = new AimAssist();
        float quantum = 0.2F;
        float correction = 0.0F;
        for (int frame = 0; frame < 20; frame++) {
            float snapped = assist.quantizeYaw(0.02F, 2.0F - correction, quantum);
            assert close(snapped, RotationManager.snapToQuantum(snapped, quantum));
            correction += snapped;
        }
        assert correction > 0.0F && correction <= 2.0F;
    }

    private static float converge(int fps) {
        float error = 1.0F;
        for (int frame = 0; frame < fps; frame++) {
            error -= AimAssist.smoothedStep(error, 2.0, 1_000.0, 1.0 / fps);
        }
        return error;
    }

    private static boolean close(float first, float second) {
        return Math.abs(first - second) < 1.0E-5F;
    }
}
