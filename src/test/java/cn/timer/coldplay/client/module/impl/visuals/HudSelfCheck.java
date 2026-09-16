package cn.timer.coldplay.client.module.impl.visuals;

import cn.timer.coldplay.client.gui.click.ModuleButtonSelfCheck;
import cn.timer.coldplay.client.hud.HudState;
import cn.timer.coldplay.client.hud.StatusBarsSelfCheck;
import cn.timer.coldplay.client.setting.NumberSetting;
import cn.timer.coldplay.client.setting.SettingSelfCheck;

public final class HudSelfCheck {
    private HudSelfCheck() {
    }

    public static void main(String[] args) {
        SettingSelfCheck.main(args);
        EntityESPSelfCheck.main(args);
        StatusBarsSelfCheck.main(args);
        ModuleButtonSelfCheck.main(args);

        // Anchors keep their distance to the nearest edge or the centre when the GUI size changes.
        assert HudState.reanchor(3, 100, 200) == 3;
        assert HudState.reanchor(95, 100, 200) == 195;
        assert HudState.reanchor(52, 100, 200) == 102;
        assert HudState.reanchor(48, 100, 60) == 28;

        HudState state = new HudState();
        HudState.Position stored = state.getOrCreate("Watermark", 3, 3, 100, 100);
        assert stored.x == 3 && stored.y == 3;
        assert state.getLayoutWidth() == 100 && state.getLayoutHeight() == 100;
        // A different screen size projects without touching the stored anchor.
        HudState.Position projected = state.getOrCreate("Watermark", 3, 3, 200, 150);
        assert projected.x == 3 && projected.y == 3;
        assert projected != stored;
        stored.x = 97;
        assert state.getOrCreate("Watermark", 3, 3, 200, 150).x == 197;
        state.rebase(200, 150);
        assert state.getOrCreate("Watermark", 3, 3, 200, 150).x == 197;
        assert state.getPositions().get("Watermark").x == 197;

        // Edit-mode boxes are only kept while editing and follow translations.
        state.report("ArrayList", 1, 2, 3, 4);
        assert state.getBox("ArrayList") == null;
        state.beginEditing();
        state.report("ArrayList", 1, 2, 3, 4);
        state.translateBox("ArrayList", 5, 6);
        int[] box = state.getBox("ArrayList");
        assert box[0] == 6 && box[1] == 8 && box[2] == 8 && box[3] == 10;
        state.report("Watermark", 0, 0, 10, 10, 0, 0, 2.0f);
        int[] scaled = state.getBox("Watermark");
        assert scaled[2] == 20 && scaled[3] == 20;
        state.endEditing();
        assert state.getBoxes().isEmpty();

        NumberSetting scale = HudState.scaleSetting("ArrayList Scale");
        assert scale.get() == 1.0;
        scale.set(5.0);
        assert scale.get() == 2.0;
        state.registerScale("ArrayList", scale);
        assert state.getScale("ArrayList") == scale;
        assert state.getScale("Watermark") == null;

        // Rows wipe in proportionally to their progress.
        assert Hud.rowHeight(1.0) == 12.0;
        assert Hud.rowHeight(0.5) == 6.0;
        assert Hud.rowHeight(0.0) == 0.0;
    }
}
