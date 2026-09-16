package cn.timer.coldplay.client.setting;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;

import java.util.Objects;

public final class KeybindSetting extends Setting<InputConstants.Key> {
    private final KeyMapping mapping;

    public KeybindSetting(String name, KeyMapping mapping) {
        super(name);
        this.mapping = Objects.requireNonNull(mapping, "mapping");
    }

    @Override
    public InputConstants.Key get() {
        return KeyBindingHelper.getBoundKeyOf(mapping);
    }

    @Override
    public void set(InputConstants.Key value) {
        mapping.setKey(Objects.requireNonNull(value, "value"));
        KeyMapping.resetMapping();
    }

    public KeyMapping mapping() {
        return mapping;
    }
}
