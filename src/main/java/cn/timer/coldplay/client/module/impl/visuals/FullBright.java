package cn.timer.coldplay.client.module.impl.visuals;

import cn.timer.coldplay.client.module.Category;
import cn.timer.coldplay.client.module.Module;
import cn.timer.coldplay.client.setting.ModeSetting;
import cn.timer.coldplay.client.setting.NumberSetting;
import org.lwjgl.glfw.GLFW;

public final class FullBright extends Module {
    public static final String GAMMA = "GAMMA";
    public static final String NIGHT_VISION = "NIGHT_VISION";

    private final ModeSetting mode = addSetting(new ModeSetting("Mode", GAMMA, GAMMA, NIGHT_VISION));
    private final NumberSetting gamma = addSetting(new NumberSetting("Gamma", 16.0, 1.0, 100.0, 1.0));

    public FullBright() {
        super("FullBright", "Improves visibility in dark areas", Category.VISUALS, GLFW.GLFW_KEY_UNKNOWN);
    }

    public ModeSetting mode() {
        return mode;
    }

    public NumberSetting gamma() {
        return gamma;
    }

    public boolean uses(String mode) {
        return enabled() && this.mode.get().equals(mode);
    }
}
