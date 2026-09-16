package cn.timer.coldplay.client.module.impl.combat;

import cn.timer.coldplay.client.setting.BooleanSetting;
import cn.timer.coldplay.client.setting.NumberSetting;
import cn.timer.coldplay.client.setting.Setting;
import net.minecraft.world.entity.player.Input;

public final class WTapSelfCheck {
    private WTapSelfCheck() {
    }

    public static void main(String[] args) {
        WTap wTap = new WTap();
        NumberSetting chance = setting(wTap, "Chance", NumberSetting.class);
        BooleanSetting sTap = setting(wTap, "STap", BooleanSetting.class);
        NumberSetting distance = setting(wTap, "Distance", NumberSetting.class);

        assert chance.get() == 100.0 && chance.minimum() == 1.0 && chance.maximum() == 100.0
                && chance.increment() == 1.0;
        assert !sTap.get() && wTap.isOwnerSetting(sTap);
        assert distance.get() == 3.0 && distance.minimum() == 0.0 && distance.maximum() == 6.0
                && distance.increment() == 0.1 && wTap.ownerOf(distance) == sTap;

        assert WTap.activates(100.0, 99.999);
        assert WTap.activates(50.0, 49.999) && !WTap.activates(50.0, 50.0);
        assert WTap.activates(1.0, 0.999) && !WTap.activates(1.0, 1.0);
        assert WTap.withinDistance(9.0, 3.0) && !WTap.withinDistance(9.001, 3.0);

        Input physical = new Input(true, false, true, false, true, true, true);
        Input normal = WTap.tapInput(physical, false);
        Input back = WTap.tapInput(physical, true);
        assert !normal.forward() && !normal.backward();
        assert !back.forward() && back.backward();
        assert normal.left() && !normal.right() && normal.jump() && normal.shift() && normal.sprint();
        assert physical.forward() && !physical.backward();

        Input physicalBack = new Input(false, true, false, true, false, false, false);
        Input preservedBack = WTap.tapInput(physicalBack, false);
        assert preservedBack.backward() && preservedBack.right();
    }

    private static <T extends Setting<?>> T setting(WTap wTap, String name, Class<T> type) {
        return wTap.settings().stream()
                .filter(setting -> setting.name().equals(name) && type.isInstance(setting))
                .map(type::cast)
                .findFirst()
                .orElseThrow();
    }
}
