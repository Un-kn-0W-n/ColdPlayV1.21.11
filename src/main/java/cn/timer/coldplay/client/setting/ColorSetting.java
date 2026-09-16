package cn.timer.coldplay.client.setting;

import java.util.Objects;

public final class ColorSetting extends Setting<Integer> {
    private int value;

    public ColorSetting(String name, int value) {
        super(name);
        set(value);
    }

    @Override
    public Integer get() {
        return value;
    }

    @Override
    public void set(Integer value) {
        this.value = 0xFF000000 | Objects.requireNonNull(value, "value") & 0xFFFFFF;
    }
}
