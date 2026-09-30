package cn.timer.coldplay.client.module.impl.visuals;

import cn.timer.coldplay.client.setting.NumberSetting;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

public final class Tracers extends EntityModule {
    private final NumberSetting width = addSetting(new NumberSetting("Width", 2.0, 0.5, 5.0, 0.5));
    private final NumberSetting opacity = addSetting(new NumberSetting("Opacity", 255.0, 5.0, 255.0, 5.0));
    /** Whether view bobbing was on before Tracers took it, and so has to be handed back. */
    private boolean restoreBobbing;

    public Tracers() {
        super("Tracers", "Draws lines to entities", true);
    }

    /** The lines hang off the camera, so bobbing would swing them off the crosshair. */
    @Override
    protected void onEnable() {
        OptionInstance<Boolean> bobView = Minecraft.getInstance().options.bobView();
        restoreBobbing = bobView.get();
        bobView.set(false);
    }

    /** Only hands bobbing back if it was on before, so a player's own change in between survives. */
    @Override
    protected void onDisable() {
        if (restoreBobbing) {
            Minecraft.getInstance().options.bobView().set(true);
        }
        restoreBobbing = false;
    }

    @Override
    protected void onRender(DeltaTracker deltaTracker) {
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vec3 origin = origin(camera.position(), camera.forwardVector());
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
        float lineWidth = width.get().floatValue();
        double alpha = opacity.get();
        // Interpolated, so the far end does not jitter between ticks.
        forEachTarget((entity, color) -> Gizmos.line(origin,
                target(entity.getPosition(partialTick), entity.getBbHeight()),
                withOpacity(color, alpha), lineWidth).setAlwaysOnTop());
    }

    /** One block down the view axis: a line starting on the camera would project to a single pixel. */
    static Vec3 origin(Vec3 cameraPosition, Vector3fc forward) {
        return cameraPosition.add(forward.x(), forward.y(), forward.z());
    }

    static Vec3 target(Vec3 feet, float height) {
        return feet.add(0.0, height * 0.5, 0.0);
    }
}
