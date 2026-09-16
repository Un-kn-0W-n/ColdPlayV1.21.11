package cn.timer.coldplay.client.setting;

import java.util.List;

public final class ModeSetting extends Setting<String> {
    private final List<String> modes;
    private String value;

    public ModeSetting(String name, String value, String... modes) {
        super(name);
        this.modes = List.of(modes);
        if (this.modes.isEmpty()) {
            throw new IllegalArgumentException("Mode setting requires at least one mode");
        }
        set(value);
    }

    @Override
    public String get() {
        return value;
    }

    @Override
    public void set(String value) {
        this.value = modes.stream()
                .filter(mode -> mode.equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown mode: " + value));
    }

    public List<String> modes() {
        return modes;
    }

    public void next(int direction) {
        int current = modes.indexOf(value);
        set(modes.get(Math.floorMod(current + direction, modes.size())));
    }
}
