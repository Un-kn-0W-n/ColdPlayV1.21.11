package cn.timer.coldplay.client.module;

import cn.timer.coldplay.client.setting.KeybindSetting;
import cn.timer.coldplay.client.setting.ModeSetting;
import cn.timer.coldplay.client.setting.Setting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.DeltaTracker;
import com.mojang.blaze3d.platform.InputConstants;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public abstract class Module {
    private final String name;
    private final String description;
    private final Category category;
    private final List<Setting<?>> settings = new ArrayList<>();
    private final List<Setting<?>> settingsView = Collections.unmodifiableList(settings);
    private final Set<Setting<?>> ownerSettings = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Setting<?>, Setting<?>> settingOwners = new IdentityHashMap<>();
    private final KeybindSetting keybind;
    private boolean enabled;

    protected Module(String name, String description, Category category, int defaultKey) {
        this.name = name;
        this.description = description;
        this.category = category;
        this.keybind = addSetting(new KeybindSetting("Keybind", new KeyMapping(
                "key.coldplay." + name.toLowerCase(Locale.ROOT),
                InputConstants.Type.KEYSYM,
                defaultKey,
                KeyMapping.Category.MISC
        )));
    }

    protected final <T extends Setting<?>> T addSetting(T setting) {
        settings.add(setting);
        return setting;
    }

    protected final <T extends Setting<?>> T addOwnerSetting(T setting) {
        ownerSettings.add(setting);
        return addSetting(setting);
    }

    protected final <T extends Setting<?>> T addChildSetting(Setting<?> owner, T setting) {
        if (!ownerSettings.contains(owner)) {
            throw new IllegalArgumentException("Child setting owner is not registered");
        }
        settingOwners.put(setting, owner);
        return addSetting(setting);
    }

    public final String name() {
        return name;
    }

    public final String description() {
        return description;
    }

    public final Category category() {
        return category;
    }

    public final List<Setting<?>> settings() {
        return settingsView;
    }

    public final boolean isOwnerSetting(Setting<?> setting) {
        return ownerSettings.contains(setting);
    }

    public final Setting<?> ownerOf(Setting<?> setting) {
        return settingOwners.get(setting);
    }

    public final String settingKey(Setting<?> setting) {
        Setting<?> owner = ownerOf(setting);
        return owner == null ? setting.name() : owner.name() + "." + setting.name();
    }

    public final KeybindSetting keybind() {
        return keybind;
    }

    /** Gray text after the name in the HUD array list: the first mode setting's value, or null. */
    public final String suffix() {
        for (Setting<?> setting : settings) {
            if (setting instanceof ModeSetting mode) {
                return mode.get();
            }
        }
        return null;
    }

    public final boolean enabled() {
        return enabled;
    }

    public final void toggle() {
        setEnabled(!enabled);
    }

    public final void setEnabled(boolean enabled) {
        if (this.enabled == enabled) {
            return;
        }
        this.enabled = enabled;
        if (enabled) {
            onEnable();
        } else {
            onDisable();
        }
    }

    protected void onEnable() {
    }

    protected void onDisable() {
    }

    protected void onRender(DeltaTracker deltaTracker) {
    }
}
