package cn.timer.coldplay.client.setting;

public final class SettingSelfCheck {
    private SettingSelfCheck() {
    }

    public static void main(String[] args) {
        BooleanSetting toggle = new BooleanSetting("ArrayList", true);
        assert toggle.get();
        toggle.toggle();
        assert !toggle.get();
        toggle.set(true);
        assert toggle.get();

        NumberSetting number = new NumberSetting("Gamma", 16.0, 1.0, 100.0, 1.0);
        number.set(-10.0);
        assert number.get() == 1.0;
        number.set(200.0);
        assert number.get() == 100.0;
        number.set(16.6);
        assert number.get() == 17.0;

        int[] changes = {0};
        double[] changedValue = {0.0};
        NumberSetting delay = new NumberSetting("Delay", 200.0, 50.0, 1000.0, 1.0, "ms", value -> {
            changes[0]++;
            changedValue[0] = value;
        });
        assert delay.unit().equals("ms");
        assert changes[0] == 0;
        delay.set(200.4);
        assert changes[0] == 0;
        delay.set(201.4);
        assert changes[0] == 1;
        assert changedValue[0] == 201.0;

        ModeSetting mode = new ModeSetting("Mode", "GAMMA", "GAMMA", "NIGHT_VISION");
        mode.next(1);
        assert mode.get().equals("NIGHT_VISION");
        mode.next(1);
        assert mode.get().equals("GAMMA");

        boolean rejected = false;
        try {
            mode.set("INVALID");
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        assert rejected;
    }
}
