package cn.timer.coldplay.client.module.impl.visuals;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;

/**
 * World to GUI projection for the HUD-drawn visuals. Every metric is in GUI pixels unless it says otherwise.
 *
 * <p>It projects through the matrices the world was drawn with this frame, captured by GameRendererMixin.
 * Vanilla's projectPointToScreen leaves out view bobbing, the damage tilt and nausea, and reads the FOV at
 * partial tick 0, so anything placed with it sways against the world while walking, jolts on landing and
 * steps at 20 Hz while the sprint FOV eases.
 */
public final class Projection {
    private static final float NEAR_PLANE = 0.05F;
    private static final Matrix4f VIEW_PROJECTION = new Matrix4f();
    private static Vec3 cameraPosition;

    private Projection() {
    }

    /**
     * Records the level pass's projection and view rotation. The HUD runs after it in the same frame and under
     * the same guard, so every HUD pass sees the frame it is drawn over.
     */
    public static void capture(Matrix4fc projection, Matrix4fc view, Vec3 camera) {
        VIEW_PROJECTION.set(projection).mul(view);
        cameraPosition = camera;
    }

    /** The GUI point a world position lands on, or null when it is behind the camera or no level was drawn yet. */
    static Vec3 toGui(Vec3 point, int width, int height) {
        if (cameraPosition == null) {
            return null;
        }
        Vector4f clip = VIEW_PROJECTION.transform(new Vector4f(
                (float) (point.x - cameraPosition.x),
                (float) (point.y - cameraPosition.y),
                (float) (point.z - cameraPosition.z), 1.0F));
        // w is the depth in front of the eye; false for NaN too.
        if (!(clip.w > NEAR_PLANE)) {
            return null;
        }
        double x = screenX(clip.x / clip.w, width);
        double y = screenY(clip.y / clip.w, height);
        return Double.isFinite(x) && Double.isFinite(y) ? new Vec3(x, y, 0.0) : null;
    }

    /**
     * The box an entity's interpolated hitbox covers, in screen pixels at {@code scale}, or null once it is
     * off the viewport or behind it.
     */
    static ScreenBox box(LivingEntity entity, float partialTick, int width, int height, int scale) {
        Vec3 feet = entity.getPosition(partialTick);
        double halfWidth = entity.getBbWidth() * 0.5;
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (int corner = 0; corner < 8; corner++) {
            Vec3 point = toGui(new Vec3(
                    feet.x + ((corner & 1) == 0 ? -halfWidth : halfWidth),
                    feet.y + ((corner & 2) == 0 ? 0.0 : entity.getBbHeight()),
                    feet.z + ((corner & 4) == 0 ? -halfWidth : halfWidth)), width, height);
            if (point == null) {
                continue;
            }
            minX = Math.min(minX, point.x);
            minY = Math.min(minY, point.y);
            maxX = Math.max(maxX, point.x);
            maxY = Math.max(maxY, point.y);
        }
        return clipBounds(minX * scale, minY * scale, maxX * scale, maxY * scale, width * scale, height * scale);
    }

    /** Screen pixels per GUI pixel. */
    static int guiScale() {
        return Minecraft.getInstance().getWindow().getGuiScale();
    }

    /** A GUI coordinate moved to the nearest screen pixel, so a label glides instead of stepping a GUI pixel. */
    static double snap(double gui, int scale) {
        return Math.round(gui * scale) / (double) scale;
    }

    /** Normalized device coordinates to GUI pixels. */
    static double screenX(double ndcX, int screenWidth) {
        return (ndcX + 1.0) * screenWidth * 0.5;
    }

    static double screenY(double ndcY, int screenHeight) {
        return (1.0 - ndcY) * screenHeight * 0.5;
    }

    static ScreenBox clipBounds(double minX, double minY, double maxX, double maxY,
                                int screenWidth, int screenHeight) {
        if (!Double.isFinite(minX) || !Double.isFinite(minY)
                || !Double.isFinite(maxX) || !Double.isFinite(maxY)
                || maxX < 0.0 || maxY < 0.0 || minX > screenWidth || minY > screenHeight) {
            return null;
        }
        int left = (int) Math.floor(Math.clamp(minX, 0.0, screenWidth));
        int top = (int) Math.floor(Math.clamp(minY, 0.0, screenHeight));
        int right = (int) Math.ceil(Math.clamp(maxX, 0.0, screenWidth));
        int bottom = (int) Math.ceil(Math.clamp(maxY, 0.0, screenHeight));
        return right > left && bottom > top ? new ScreenBox(left, top, right, bottom) : null;
    }

    record ScreenBox(int left, int top, int right, int bottom) {
        int width() {
            return right - left;
        }

        int height() {
            return bottom - top;
        }
    }
}
