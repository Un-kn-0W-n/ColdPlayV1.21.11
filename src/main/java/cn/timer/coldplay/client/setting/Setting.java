package cn.timer.coldplay.client.setting;

import java.util.Objects;

public abstract class Setting<T> {
    private final String name;

    protected Setting(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    public final String name() {
        return name;
    }

    public abstract T get();

    public abstract void set(T value);
}
