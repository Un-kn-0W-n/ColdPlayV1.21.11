package cn.timer.coldplay.client.gui.click;

public final class ModuleButtonSelfCheck {
    private ModuleButtonSelfCheck() {
    }

    public static void main(String[] args) {
        assert ModuleButton.compactKeyName("Right Shift").equals("RShift");
        assert ModuleButton.compactKeyName("Left Control").equals("LControl");
        assert ModuleButton.compactKeyName("R").equals("R");
        assert ModuleButton.compactKeyName(null).isEmpty();

        assert SettingsPanel.formatValue(4.0).equals("4");
        assert SettingsPanel.formatValue(0.1).equals("0.1");
        assert SettingsPanel.formatValue(1.256).equals("1.26");
        assert SettingsPanel.worstValueText(1.0, 6.0, 0.1).equals("1.1");
        assert SettingsPanel.worstValueText(50.0, 1000.0, 1.0).equals("1000");
    }
}
