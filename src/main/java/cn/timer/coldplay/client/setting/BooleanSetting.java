package cn.timer.coldplay.client.setting;

import java.util.Objects;

public final class BooleanSetting extends Setting<Boolean> {
    private boolean value;

    public BooleanSetting(String name, boolean value) {
        super(name);
        this.value = value;
    }

    @Override
    public Boolean get() {
        return value;
    }

    @Override
    public void set(Boolean value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    public void toggle() {
        value = !value;
    }
}
