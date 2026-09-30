package cn.timer.coldplay.client.module.impl.visuals;

import cn.timer.coldplay.client.gui.Draw;
import cn.timer.coldplay.client.setting.ModeSetting;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;

public final class ESP extends EntityModule {
    public static final String TWO_D = "2D";
    public static final String OUTLINE = "Outline";
    public static final String CHAMS = "Chams";
    /** Marks a render state for Chams; submit only sees the state, so the renderer mixins read it back. */
    private static final RenderStateDataKey<Boolean> SEE_THROUGH =
            RenderStateDataKey.create(() -> "coldplay:esp_see_through");

    private final ModeSetting mode = addSetting(new ModeSetting("Mode", TWO_D, TWO_D, OUTLINE, CHAMS));

    public ESP() {
        super("ESP", "Highlights entities through walls", true);
    }

    /** Outline and Chams live on the render state, so they are applied while vanilla extracts it. */
    public void extractRenderState(LivingEntity entity, LivingEntityRenderState state) {
        Integer color = mode.get().equals(TWO_D) ? null : colorOf(entity);
        boolean chams = color != null && mode.get().equals(CHAMS);
        state.setData(SEE_THROUGH, chams ? Boolean.TRUE : null);
        if (color == null) {
            return;
        }
        if (!chams) {
            state.outlineColor = color;
        } else if (entity.isInvisible()) {
            state.isInvisible = false;
            state.isInvisibleToPlayer = false;
        }
    }

    public static boolean seeThrough(LivingEntityRenderState state) {
        return state.getData(SEE_THROUGH) != null;
    }

    @Override
    protected void onRender2D(GuiGraphics graphics, DeltaTracker deltaTracker) {
        if (!mode.get().equals(TWO_D)) {
            return;
        }
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        int scale = Projection.guiScale();
        // In screen pixels, so each edge follows its entity a screen pixel at a time rather than a GUI pixel.
        graphics.pose().pushMatrix();
        graphics.pose().scale(1.0F / scale, 1.0F / scale);
        forEachTarget((entity, color) -> {
            Projection.ScreenBox box = Projection.box(entity, partialTick, width, height, scale);
            if (box != null) {
                // one GUI pixel thick, as renderOutline drew it
                Draw.outline(graphics, box.left(), box.top(), box.right(), box.bottom(), scale, color);
            }
        });
        graphics.pose().popMatrix();
    }
}
