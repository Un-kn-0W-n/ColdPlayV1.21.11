package cn.timer.coldplay.client.module.impl.visuals;

import cn.timer.coldplay.client.setting.SettingSelfCheck;

public final class HudSelfCheck {
    private HudSelfCheck() {
    }

    public static void main(String[] args) {
        SettingSelfCheck.main(args);
        EntityESPSelfCheck.main(args);

        assert Hud.anchorX(Hud.TOP_LEFT, 100, 20) == 4;
        assert Hud.anchorX(Hud.TOP_RIGHT, 100, 20) == 76;
        assert Hud.anchorX(Hud.BOTTOM_LEFT, 100, 20) == 4;
        assert Hud.anchorX(Hud.BOTTOM_RIGHT, 100, 20) == 76;

        assert Hud.anchorY(Hud.TOP_LEFT, 100, 30) == 4;
        assert Hud.anchorY(Hud.TOP_RIGHT, 100, 30) == 4;
        assert Hud.anchorY(Hud.BOTTOM_LEFT, 100, 30) == 66;
        assert Hud.anchorY(Hud.BOTTOM_RIGHT, 100, 30) == 66;

        assert Hud.contentHeight(3, false, 10, 20) == 30;
        assert Hud.contentHeight(3, true, 10, 20) == 52;
        assert Hud.contentHeight(0, true, 10, 20) == 20;
    }
}
