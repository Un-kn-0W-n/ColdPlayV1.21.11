package cn.timer.coldplay.client.module.impl.visuals;

import cn.timer.coldplay.client.setting.ColorSetting;
import cn.timer.coldplay.client.setting.Setting;

public final class EntityESPSelfCheck {
    private EntityESPSelfCheck() {
    }

    public static void main(String[] args) {
        ColorSetting color = new ColorSetting("Color", 0x00123456);
        assert color.get() == 0xFF123456;
        color.set(0x80112233);
        assert color.get() == 0xFF112233;

        EntityESP esp = new EntityESP();
        Setting<?> players = setting(esp, "Players", null);
        Setting<?> playersColor = setting(esp, "Color", players);
        Setting<?> espOwner = setting(esp, "ESP", null);
        Setting<?> mode = setting(esp, "Mode", espOwner);
        assert esp.isOwnerSetting(players);
        assert esp.settingKey(players).equals("Players");
        assert esp.settingKey(playersColor).equals("Players.Color");
        assert esp.settingKey(mode).equals("ESP.Mode");

        assert EntityESP.inRange(64.0 * 64.0, 64.0);
        assert !EntityESP.inRange(64.0 * 64.0 + 0.01, 64.0);

        EntityESP.ScreenBox clipped = EntityESP.clipBounds(-5.0, -10.0, 20.0, 30.0,
                100, 80, 0xFF55FFFF);
        assert clipped != null;
        assert clipped.left() == 0 && clipped.top() == 0;
        assert clipped.width() == 20 && clipped.height() == 30;
        assert clipped.color() == 0xFF55FFFF;
        assert EntityESP.clipBounds(Double.NaN, 0.0, 1.0, 1.0, 100, 80, 0) == null;
        assert EntityESP.clipBounds(101.0, 0.0, 110.0, 10.0, 100, 80, 0) == null;
        assert EntityESP.clipBounds(20.0, 20.0, 10.0, 10.0, 100, 80, 0) == null;
    }

    private static Setting<?> setting(EntityESP esp, String name, Setting<?> owner) {
        return esp.settings().stream()
                .filter(setting -> setting.name().equals(name) && esp.ownerOf(setting) == owner)
                .findFirst()
                .orElseThrow();
    }
}
