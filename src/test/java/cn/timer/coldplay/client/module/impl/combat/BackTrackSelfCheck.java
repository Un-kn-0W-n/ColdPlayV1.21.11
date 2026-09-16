package cn.timer.coldplay.client.module.impl.combat;

import cn.timer.coldplay.client.setting.BooleanSetting;
import cn.timer.coldplay.client.setting.ColorSetting;
import cn.timer.coldplay.client.setting.NumberSetting;
import cn.timer.coldplay.client.setting.Setting;
import cn.timer.coldplay.client.setting.SettingSelfCheck;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.TimeUnit;

public final class BackTrackSelfCheck {
    private BackTrackSelfCheck() {
    }

    public static void main(String[] args) {
        SettingSelfCheck.main(args);

        BackTrack backTrack = new BackTrack();
        NumberSetting delay = setting(backTrack, "Delay", NumberSetting.class);
        BooleanSetting render = setting(backTrack, "Render", BooleanSetting.class);
        ColorSetting color = setting(backTrack, "Color", ColorSetting.class);

        assert delay.get() == 200.0 && delay.minimum() == 50.0 && delay.maximum() == 1000.0
                && delay.increment() == 1.0 && delay.unit().equals("ms");
        assert render.get() && backTrack.isOwnerSetting(render);
        assert color.get() == 0xFFFF5555 && backTrack.ownerOf(color) == render
                && backTrack.settingKey(color).equals("Render.Color");

        delay.set(0.0);
        assert delay.get() == 50.0;
        delay.set(2000.0);
        assert delay.get() == 1000.0;

        long arrival = 1_000L;
        long now = arrival + TimeUnit.MILLISECONDS.toNanos(150L);
        assert !BackTrack.isDue(arrival, now, TimeUnit.MILLISECONDS.toNanos(200L));
        assert BackTrack.isDue(arrival, now, TimeUnit.MILLISECONDS.toNanos(100L));
        assert BackTrack.MAX_QUEUED_UPDATES == 256;
        assert !BackTrack.reachesSafetyLimit(254);
        assert BackTrack.reachesSafetyLimit(255);

        Vec3 from = Vec3.ZERO;
        Vec3 to = new Vec3(10.0, 4.0, -2.0);
        long started = 10_000L;
        assert close(BackTrack.interpolate(from, to, started, started - 1L), from);
        assert close(BackTrack.interpolate(from, to, started,
                started + TimeUnit.MILLISECONDS.toNanos(25L)), new Vec3(5.0, 2.0, -1.0));
        assert close(BackTrack.interpolate(from, to, started,
                started + TimeUnit.MILLISECONDS.toNanos(50L)), to);

        Vec3 synchronization = new Vec3(3.0, 2.0, 1.0);
        backTrack.capturePosition(synchronization, false);
        assert close(backTrack.renderPosition(System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(25L)), synchronization);
        Vec3 teleport = new Vec3(-4.0, 8.0, 12.0);
        backTrack.capturePosition(teleport, false);
        assert close(backTrack.renderPosition(System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(25L)), teleport);
    }

    private static <T extends Setting<?>> T setting(BackTrack backTrack, String name, Class<T> type) {
        return backTrack.settings().stream()
                .filter(setting -> setting.name().equals(name) && type.isInstance(setting))
                .map(type::cast)
                .findFirst()
                .orElseThrow();
    }

    private static boolean close(Vec3 first, Vec3 second) {
        return first.distanceToSqr(second) < 1.0E-12;
    }
}
