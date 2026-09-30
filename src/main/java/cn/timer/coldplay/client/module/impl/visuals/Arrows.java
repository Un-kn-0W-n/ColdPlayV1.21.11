package cn.timer.coldplay.client.module.impl.visuals;

import cn.timer.coldplay.client.setting.NumberSetting;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.GuiElementRenderState;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3x2f;

/** A ring of arrowheads around the crosshair, one per target that is off the screen. */
public final class Arrows extends EntityModule {
    private static final double FADE_NEAR = 5.0;
    private static final double FADE_FAR = 15.0;

    private final NumberSetting size = addSetting(new NumberSetting("Size", 6.0, 3.0, 16.0, 1.0));
    private final NumberSetting radius = addSetting(new NumberSetting("Radius", 30.0, 10.0, 120.0, 1.0));
    private final NumberSetting opacity = addSetting(new NumberSetting("Opacity", 255.0, 5.0, 255.0, 5.0));

    public Arrows() {
        super("Arrows", "Points at off-screen entities", false);
    }

    @Override
    protected void onRender2D(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer self = minecraft.player;
        Camera camera = minecraft.gameRenderer.getMainCamera();
        Vec3 eye = camera.position();
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        float centerX = width / 2.0F;
        float centerY = height / 2.0F;
        float arrowSize = size.get().floatValue();
        float ringRadius = radius.get().floatValue();
        double alpha = opacity.get();
        forEachTarget((entity, ignored) -> {
            if (Projection.box(entity, partialTick, width, height, 1) != null) {
                return; // on screen, so there is nothing to point at
            }
            // Interpolated, so the bearing does not step between ticks.
            Vec3 feet = entity.getPosition(partialTick);
            float angle = bearingAngle(camera.yRot(), eye.x, eye.z, feet.x, feet.z);
            int color = withOpacity(distanceColor(Math.sqrt(self.distanceToSqr(entity))), alpha);
            graphics.guiRenderState.submitGuiElement(
                    ArrowHead.of(centerX, centerY, angle, ringRadius, arrowSize, color));
        });
    }

    /** Red inside FADE_NEAR, fading to black at FADE_FAR: an off-screen target has no other depth cue. */
    static int distanceColor(double distance) {
        double fade = Math.clamp((FADE_FAR - distance) / (FADE_FAR - FADE_NEAR), 0.0, 1.0);
        return ARGB.color((int) Math.round(255 * fade), (int) Math.round(100 * fade),
                (int) Math.round(100 * fade));
    }

    /** Ring angle in radians, screen-space: up is -PI/2 for a target dead ahead. */
    static float bearingAngle(float cameraYaw, double cameraX, double cameraZ,
                              double targetX, double targetZ) {
        float wantYaw = (float) (Math.toDegrees(Math.atan2(targetZ - cameraZ, targetX - cameraX)) - 90.0);
        return (float) (Math.toRadians(Mth.wrapDegrees(wantYaw - cameraYaw)) - Math.PI / 2.0);
    }

    record ArrowHead(float tipX, float tipY, float rightX, float rightY, float leftX, float leftY,
                     int color, ScreenRectangle bounds) implements GuiElementRenderState {

        private static final Matrix3x2f NO_TRANSFORM = new Matrix3x2f();

        static ArrowHead of(float centerX, float centerY, float angle, float radius, float size,
                            int color) {
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            double ringX = centerX + cos * radius;
            double ringY = centerY + sin * radius;
            double backX = ringX - cos * (size * 0.5);
            double backY = ringY - sin * (size * 0.5);
            double half = size * 0.6;

            float tipX = (float) (ringX + cos * size);
            float tipY = (float) (ringY + sin * size);
            float rightX = (float) (backX + sin * half);
            float rightY = (float) (backY - cos * half);
            float leftX = (float) (backX - sin * half);
            float leftY = (float) (backY + cos * half);
            return new ArrowHead(tipX, tipY, rightX, rightY, leftX, leftY, color,
                    bounds(tipX, tipY, rightX, rightY, leftX, leftY));
        }

        private static ScreenRectangle bounds(float tipX, float tipY, float rightX, float rightY,
                                              float leftX, float leftY) {
            int left = (int) Math.floor(Math.min(tipX, Math.min(rightX, leftX)));
            int top = (int) Math.floor(Math.min(tipY, Math.min(rightY, leftY)));
            int right = (int) Math.ceil(Math.max(tipX, Math.max(rightX, leftX)));
            int bottom = (int) Math.ceil(Math.max(tipY, Math.max(rightY, leftY)));
            return new ScreenRectangle(left, top, Math.max(1, right - left), Math.max(1, bottom - top));
        }

        /** The GUI pass draws quads, so the last point repeats to collapse the second triangle. */
        @Override
        public void buildVertices(VertexConsumer consumer) {
            consumer.addVertexWith2DPose(NO_TRANSFORM, tipX, tipY).setColor(color);
            consumer.addVertexWith2DPose(NO_TRANSFORM, rightX, rightY).setColor(color);
            consumer.addVertexWith2DPose(NO_TRANSFORM, leftX, leftY).setColor(color);
            consumer.addVertexWith2DPose(NO_TRANSFORM, leftX, leftY).setColor(color);
        }

        @Override
        public RenderPipeline pipeline() {
            return RenderPipelines.GUI;
        }

        @Override
        public TextureSetup textureSetup() {
            return TextureSetup.noTexture();
        }

        @Override
        public ScreenRectangle scissorArea() {
            return null;
        }
    }
}
