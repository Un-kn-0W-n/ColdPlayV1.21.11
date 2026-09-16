package cn.timer.coldplay.client.module.impl.visuals;

import cn.timer.coldplay.client.ClientCore;
import cn.timer.coldplay.client.module.Category;
import cn.timer.coldplay.client.module.Module;
import org.lwjgl.glfw.GLFW;

public final class ClickGuiModule extends Module {
    public ClickGuiModule() {
        super("ClickGUI", "Opens the ColdPlay ClickGUI", Category.VISUALS, GLFW.GLFW_KEY_RIGHT_SHIFT);
    }

    @Override
    protected void onEnable() {
        // The screen closes itself on its own key; a toggle from inside the GUI must not reopen it.
        if (!ClientCore.get().isClickGuiOpen()) {
            ClientCore.get().openClickGui();
        }
        setEnabled(false);
    }
}
