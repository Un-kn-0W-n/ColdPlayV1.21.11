package cn.timer.coldplay.client.module;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.DeltaTracker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ModuleManager {
    private final List<Module> modules = new ArrayList<>();
    private final List<Module> modulesView = Collections.unmodifiableList(modules);

    public void register(Module module) {
        modules.add(module);
        KeyBindingHelper.registerKeyBinding(module.keybind().mapping());
    }

    public List<Module> modules() {
        return modulesView;
    }

    public Module getByName(String name) {
        return modules.stream().filter(module -> module.name().equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    public List<Module> getByCategory(Category category) {
        return modules.stream().filter(module -> module.category() == category).toList();
    }

    public <T extends Module> T get(Class<T> type) {
        return modules.stream().filter(type::isInstance).map(type::cast).findFirst().orElse(null);
    }

    public void render(DeltaTracker deltaTracker) {
        for (Module module : modules) {
            if (module.enabled()) {
                module.onRender(deltaTracker);
            }
        }
    }

    /** Discards queued key clicks, for a press the GUI already consumed. */
    public void drainKeybinds() {
        for (Module module : modules) {
            while (module.keybind().mapping().consumeClick()) {
                // discard
            }
        }
    }

    public boolean pollKeybinds() {
        boolean changed = false;
        for (Module module : modules) {
            while (module.keybind().mapping().consumeClick()) {
                module.toggle();
                changed = true;
            }
        }
        return changed;
    }
}
