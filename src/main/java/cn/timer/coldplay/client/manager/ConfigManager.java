package cn.timer.coldplay.client.manager;

import cn.timer.coldplay.client.ClientCore;
import cn.timer.coldplay.client.gui.click.ClickGuiScreen;
import cn.timer.coldplay.client.hud.HudState;
import cn.timer.coldplay.client.module.Category;
import cn.timer.coldplay.client.module.Module;
import cn.timer.coldplay.client.module.ModuleManager;
import cn.timer.coldplay.client.module.impl.visuals.ClickGuiModule;
import cn.timer.coldplay.client.setting.BooleanSetting;
import cn.timer.coldplay.client.setting.ColorSetting;
import cn.timer.coldplay.client.setting.KeybindSetting;
import cn.timer.coldplay.client.setting.InventoryPickerSetting;
import cn.timer.coldplay.client.setting.ModeSetting;
import cn.timer.coldplay.client.setting.NumberSetting;
import cn.timer.coldplay.client.setting.RangeSetting;
import cn.timer.coldplay.client.setting.Setting;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;

public final class ConfigManager {
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path path = FabricLoader.getInstance().getConfigDir().resolve("coldplay.json");
    private final ModuleManager modules;
    private final ClickGuiScreen clickGui;
    private final HudState hudState;

    public ConfigManager(ModuleManager modules, ClickGuiScreen clickGui, HudState hudState) {
        this.modules = modules;
        this.clickGui = clickGui;
        this.hudState = hudState;
    }

    public void load() {
        if (!Files.exists(path)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                warn("root", "expected an object");
                return;
            }
            JsonObject root = parsed.getAsJsonObject();
            JsonObject moduleData = object(root, "modules");
            if (moduleData != null) {
                loadSettings(moduleData);
                loadEnabledStates(moduleData);
            }
            JsonObject guiData = object(root, "gui");
            if (guiData != null) {
                loadGui(guiData);
            }
            JsonObject hudData = object(root, "hud");
            if (hudData != null) {
                loadHud(hudData);
            }
        } catch (IOException | RuntimeException exception) {
            ClientCore.LOGGER.warn("Could not load {}", path, exception);
        }
    }

    public void save() {
        JsonObject root = new JsonObject();
        root.add("modules", saveModules());
        root.add("gui", saveGui());
        root.add("hud", saveHud());

        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(temporary, gson.toJson(root), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            ClientCore.LOGGER.warn("Could not save {}", path, exception);
        }
    }

    private JsonObject saveModules() {
        JsonObject result = new JsonObject();
        for (Module module : modules.modules()) {
            JsonObject data = new JsonObject();
            data.addProperty("enabled", module.enabled());
            JsonObject settings = new JsonObject();
            for (Setting<?> setting : module.settings()) {
                String key = module.settingKey(setting);
                if (setting instanceof BooleanSetting toggle) {
                    settings.addProperty(key, toggle.get());
                } else if (setting instanceof ColorSetting color) {
                    settings.addProperty(key, color.get());
                } else if (setting instanceof NumberSetting number) {
                    settings.addProperty(key, number.get());
                } else if (setting instanceof ModeSetting mode) {
                    settings.addProperty(key, mode.get());
                } else if (setting instanceof RangeSetting range) {
                    JsonObject savedRange = new JsonObject();
                    savedRange.addProperty("minimum", range.lower());
                    savedRange.addProperty("maximum", range.upper());
                    settings.add(key, savedRange);
                } else if (setting instanceof InventoryPickerSetting<?> picker) {
                    settings.add(key, picker.toJson());
                }
            }
            data.add("settings", settings);
            result.add(module.name(), data);
        }
        return result;
    }

    private JsonObject saveGui() {
        JsonObject gui = new JsonObject();
        JsonObject panels = new JsonObject();
        for (Map.Entry<Category, ClickGuiScreen.PanelState> entry : clickGui.panelStates().entrySet()) {
            JsonObject data = new JsonObject();
            data.addProperty("x", entry.getValue().x());
            data.addProperty("y", entry.getValue().y());
            data.addProperty("expanded", entry.getValue().expanded());
            panels.add(entry.getKey().name(), data);
        }
        gui.add("panels", panels);
        gui.add("layout", layoutJson(clickGui.layoutWidth(), clickGui.layoutHeight()));
        return gui;
    }

    private JsonObject saveHud() {
        JsonObject hud = new JsonObject();
        hud.add("layout", layoutJson(hudState.getLayoutWidth(), hudState.getLayoutHeight()));
        JsonObject positions = new JsonObject();
        for (Map.Entry<String, HudState.Position> entry : hudState.getPositions().entrySet()) {
            JsonObject data = new JsonObject();
            data.addProperty("x", entry.getValue().x);
            data.addProperty("y", entry.getValue().y);
            positions.add(entry.getKey(), data);
        }
        hud.add("positions", positions);
        return hud;
    }

    private static JsonObject layoutJson(int width, int height) {
        JsonObject layout = new JsonObject();
        layout.addProperty("width", width);
        layout.addProperty("height", height);
        return layout;
    }

    private void loadSettings(JsonObject data) {
        for (Module module : modules.modules()) {
            JsonObject moduleData = object(data, module.name());
            if (moduleData == null) {
                continue;
            }
            JsonObject settings = object(moduleData, "settings");
            if (settings == null) {
                continue;
            }
            for (Setting<?> setting : module.settings()) {
                String key = module.settingKey(setting);
                if (setting instanceof KeybindSetting || !settings.has(key)) {
                    continue;
                }
                JsonElement value = settings.get(key);
                try {
                    if (setting instanceof BooleanSetting toggle && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
                        toggle.set(value.getAsBoolean());
                    } else if (setting instanceof ColorSetting color && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                        double parsed = value.getAsDouble();
                        if (!Double.isFinite(parsed) || parsed != Math.rint(parsed)
                                || parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE) {
                            throw new IllegalArgumentException("color must be an integer");
                        }
                        color.set((int) parsed);
                    } else if (setting instanceof NumberSetting number && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                        double parsed = value.getAsDouble();
                        if (!Double.isFinite(parsed)) {
                            throw new IllegalArgumentException("number is not finite");
                        }
                        number.set(parsed);
                    } else if (setting instanceof ModeSetting mode && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                        mode.set(value.getAsString());
                    } else if (setting instanceof RangeSetting range && value.isJsonObject()) {
                        JsonObject savedRange = value.getAsJsonObject();
                        range.set(new RangeSetting.Value(requiredInt(savedRange, "minimum"), requiredInt(savedRange, "maximum")));
                    } else if (setting instanceof InventoryPickerSetting<?> picker && value.isJsonObject()) {
                        picker.fromJson(value);
                    } else {
                        throw new IllegalArgumentException("wrong value type");
                    }
                } catch (RuntimeException exception) {
                    warn(module.name() + ".settings." + key, exception.getMessage());
                }
            }
        }
    }

    private void loadEnabledStates(JsonObject data) {
        for (Module module : modules.modules()) {
            if (module instanceof ClickGuiModule) {
                continue;
            }
            JsonObject moduleData = object(data, module.name());
            if (moduleData == null || !moduleData.has("enabled")) {
                continue;
            }
            JsonElement enabled = moduleData.get("enabled");
            if (enabled.isJsonPrimitive() && enabled.getAsJsonPrimitive().isBoolean()) {
                module.setEnabled(enabled.getAsBoolean());
            } else {
                warn(module.name() + ".enabled", "expected a boolean");
            }
        }
    }

    private void loadGui(JsonObject data) {
        JsonObject panels = object(data, "panels");
        JsonObject layout = object(data, "layout");
        // Positions saved by the previous GUI design lack a layout size; they were placed for wider
        // panels and would overlap, so the panels start from the default row instead.
        if (panels != null && layout != null) {
            for (Category category : Category.values()) {
                JsonObject panel = object(panels, category.name());
                if (panel == null) {
                    continue;
                }
                try {
                    clickGui.setPanelState(category, requiredInt(panel, "x"), requiredInt(panel, "y"), requiredBoolean(panel, "expanded"));
                } catch (RuntimeException exception) {
                    warn("gui.panels." + category.name(), exception.getMessage());
                }
            }
        }
        if (layout != null) {
            try {
                clickGui.setLayoutSize(requiredInt(layout, "width"), requiredInt(layout, "height"));
            } catch (RuntimeException exception) {
                warn("gui.layout", exception.getMessage());
            }
        }
    }

    private void loadHud(JsonObject data) {
        JsonObject layout = object(data, "layout");
        if (layout != null) {
            try {
                hudState.setLayoutSize(requiredInt(layout, "width"), requiredInt(layout, "height"));
            } catch (RuntimeException exception) {
                warn("hud.layout", exception.getMessage());
            }
        }
        JsonObject positions = object(data, "positions");
        if (positions != null) {
            for (Map.Entry<String, JsonElement> entry : positions.entrySet()) {
                if (!entry.getValue().isJsonObject()) {
                    warn("hud.positions." + entry.getKey(), "expected an object");
                    continue;
                }
                JsonObject position = entry.getValue().getAsJsonObject();
                try {
                    hudState.put(entry.getKey(), requiredInt(position, "x"), requiredInt(position, "y"));
                } catch (RuntimeException exception) {
                    warn("hud.positions." + entry.getKey(), exception.getMessage());
                }
            }
        }
    }

    private JsonObject object(JsonObject parent, String name) {
        if (!parent.has(name)) {
            return null;
        }
        JsonElement value = parent.get(name);
        if (!value.isJsonObject()) {
            warn(name, "expected an object");
            return null;
        }
        return value.getAsJsonObject();
    }

    private int requiredInt(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        double number = value.getAsDouble();
        if (!Double.isFinite(number) || number != Math.rint(number) || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        return (int) number;
    }

    private boolean requiredBoolean(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(name + " must be a boolean");
        }
        return value.getAsBoolean();
    }

    private void warn(String field, String message) {
        ClientCore.LOGGER.warn("Ignoring invalid ColdPlay config field {}: {}", field, message);
    }
}
