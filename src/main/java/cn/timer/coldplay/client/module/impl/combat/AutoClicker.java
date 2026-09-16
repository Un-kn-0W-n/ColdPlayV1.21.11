package cn.timer.coldplay.client.module.impl.combat;

import cn.timer.coldplay.client.module.Category;
import cn.timer.coldplay.client.module.Module;
import cn.timer.coldplay.client.setting.ModeSetting;
import cn.timer.coldplay.client.setting.NumberSetting;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.lwjgl.glfw.GLFW;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntSupplier;

public final class AutoClicker extends Module {
    private static final String LEFT = "Left";
    private static final String RIGHT = "Right";

    private final ModeSetting button = addSetting(new ModeSetting("Button", LEFT, LEFT, RIGHT));
    private final NumberSetting minCps = addSetting(new NumberSetting("Min CPS", 8.0, 1.0, 20.0, 1.0));
    private final NumberSetting maxCps = addSetting(new NumberSetting("Max CPS", 12.0, 1.0, 20.0, 1.0));

    private int countdown;

    public AutoClicker() {
        super("AutoClicker", "Repeats the mouse button you are holding", Category.COMBAT,
                GLFW.GLFW_KEY_UNKNOWN);
    }

    public void tick(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (!enabled() || player == null || minecraft.level == null || minecraft.screen != null
                || minecraft.getOverlay() != null || !minecraft.isWindowActive()
                || !minecraft.mouseHandler.isMouseGrabbed() || player.isDeadOrDying()
                || player.isSleeping()) {
            release();
            return;
        }

        KeyMapping mapping = RIGHT.equals(button.get()) ? minecraft.options.keyUse : minecraft.options.keyAttack;
        // Unbound resolves to InputConstants.UNKNOWN, which every other unbound mapping shares.
        if (mapping.isUnbound() || !mapping.isDown()) {
            release();
            return;
        }
        // The physical press already swung at whatever it hit; no generated swings at fake players.
        if (mapping == minecraft.options.keyAttack && AntiBot.get().isBot(minecraft.crosshairPickEntity)) {
            release();
            return;
        }

        if (advance(this::gap)) {
            KeyMapping.click(KeyBindingHelper.getBoundKeyOf(mapping));
        }
    }

    @Override
    protected void onDisable() {
        release();
    }

    boolean advance(IntSupplier gap) {
        if (countdown == 0) {
            countdown = gap.getAsInt();
            return false;
        }
        if (--countdown > 0) {
            return false;
        }
        countdown = gap.getAsInt();
        return true;
    }

    void release() {
        countdown = 0;
    }

    private int gap() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return gapTicks(minCps.get(), maxCps.get(), random.nextDouble(), random.nextDouble());
    }

    static int gapTicks(double minCps, double maxCps, double cpsSample, double roundSample) {
        double lower = Math.min(minCps, maxCps);
        double upper = Math.max(minCps, maxCps);
        double ideal = 20.0 / (lower + (upper - lower) * Math.clamp(cpsSample, 0.0, 1.0));
        int gap = (int) ideal;
        return Math.max(1, roundSample < ideal - gap ? gap + 1 : gap);
    }
}
