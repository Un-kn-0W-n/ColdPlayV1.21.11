package cn.timer.coldplay.client.module.impl.movement;

import cn.timer.coldplay.client.module.Category;
import cn.timer.coldplay.client.module.Module;
import org.lwjgl.glfw.GLFW;

public final class Sprint extends Module {
    public Sprint() {
        super("Sprint", "Sprints while moving forward", Category.MOVEMENT, GLFW.GLFW_KEY_UNKNOWN);
    }
}
