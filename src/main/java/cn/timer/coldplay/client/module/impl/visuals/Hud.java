package cn.timer.coldplay.client.module.impl.visuals;

import cn.timer.coldplay.client.module.Category;
import cn.timer.coldplay.client.module.Module;
import cn.timer.coldplay.client.module.ModuleManager;
import cn.timer.coldplay.client.setting.BooleanSetting;
import cn.timer.coldplay.client.setting.ModeSetting;
import cn.timer.coldplay.client.setting.NumberSetting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class Hud extends Module {
    static final String TOP_LEFT = "Top Left";
    static final String TOP_RIGHT = "Top Right";
    static final String BOTTOM_LEFT = "Bottom Left";
    static final String BOTTOM_RIGHT = "Bottom Right";

    private static final int BACKGROUND = 0xE0181C22;
    private static final int ACCENT = 0xE000AACC;
    private static final int TEXT = 0xFFF3F6F8;
    private static final int MARGIN = 4;
    private static final int PADDING_X = 4;
    private static final int PADDING_Y = 2;
    private static final int GAP = 2;
    private static final float WATERMARK_SCALE = 1.5f;
    private static final String WATERMARK = "ColdPlay";

    private final ModeSetting position = addSetting(new ModeSetting(
            "Position", TOP_LEFT, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT));
    private final BooleanSetting arrayList = addSetting(new BooleanSetting("ArrayList", true));
    private final BooleanSetting watermark = addSetting(new BooleanSetting("WaterMark", true));
    private final NumberSetting scale = addSetting(new NumberSetting("Scale", 1.0, 0.5, 2.0, 0.1));
    private final ModuleManager modules;

    public Hud(ModuleManager modules) {
        super("Hud", "Displays enabled modules and the ColdPlay watermark", Category.VISUALS, GLFW.GLFW_KEY_UNKNOWN);
        this.modules = Objects.requireNonNull(modules, "modules");
    }

    public void render(GuiGraphics graphics, DeltaTracker ignored) {
        if (!enabled()) {
            return;
        }

        Font font = Minecraft.getInstance().font;
        List<String> moduleNames = arrayList.get()
                ? modules.modules().stream()
                .filter(module -> module != this && module.enabled())
                .sorted(Comparator.comparingInt((Module module) -> font.width(module.name()))
                        .reversed().thenComparing(Module::name))
                .map(Module::name)
                .toList()
                : List.of();
        if (!watermark.get() && moduleNames.isEmpty()) {
            return;
        }

        float renderScale = scale.get().floatValue();
        int screenWidth = (int) (graphics.guiWidth() / renderScale);
        int screenHeight = (int) (graphics.guiHeight() / renderScale);
        int rowHeight = font.lineHeight + PADDING_Y * 2;
        int watermarkHeight = (int) Math.ceil(font.lineHeight * WATERMARK_SCALE) + PADDING_Y * 2;
        int totalHeight = contentHeight(moduleNames.size(), watermark.get(), rowHeight, watermarkHeight);
        int y = anchorY(position.get(), screenHeight, totalHeight);
        boolean bottom = position.get().startsWith("Bottom");

        graphics.pose().pushMatrix();
        graphics.pose().scale(renderScale, renderScale);
        try {
            if (watermark.get() && !bottom) {
                drawWatermark(graphics, font, screenWidth, y, watermarkHeight);
                y += watermarkHeight + (moduleNames.isEmpty() ? 0 : GAP);
            }
            for (String moduleName : moduleNames) {
                int width = font.width(moduleName) + PADDING_X * 2;
                int x = anchorX(position.get(), screenWidth, width);
                graphics.fill(x, y, x + width, y + rowHeight, BACKGROUND);
                graphics.renderOutline(x, y, width, rowHeight, ACCENT);
                graphics.drawString(font, moduleName, x + PADDING_X, y + PADDING_Y, TEXT, true);
                y += rowHeight;
            }
            if (watermark.get() && bottom) {
                y += moduleNames.isEmpty() ? 0 : GAP;
                drawWatermark(graphics, font, screenWidth, y, watermarkHeight);
            }
        } finally {
            graphics.pose().popMatrix();
        }
    }

    private void drawWatermark(GuiGraphics graphics, Font font, int screenWidth, int y, int height) {
        int width = (int) Math.ceil(font.width(WATERMARK) * WATERMARK_SCALE) + PADDING_X * 2;
        int x = anchorX(position.get(), screenWidth, width);
        graphics.fill(x, y, x + width, y + height, BACKGROUND);
        graphics.renderOutline(x, y, width, height, ACCENT);
        graphics.pose().pushMatrix();
        graphics.pose().translate(x + PADDING_X, y + PADDING_Y);
        graphics.pose().scale(WATERMARK_SCALE, WATERMARK_SCALE);
        try {
            graphics.drawString(font, WATERMARK, 0, 0, TEXT, true);
        } finally {
            graphics.pose().popMatrix();
        }
    }

    static int contentHeight(int moduleCount, boolean watermark, int rowHeight, int watermarkHeight) {
        return moduleCount * rowHeight + (watermark ? watermarkHeight : 0)
                + (watermark && moduleCount > 0 ? GAP : 0);
    }

    static int anchorX(String position, int screenWidth, int width) {
        return position.endsWith("Right") ? screenWidth - MARGIN - width : MARGIN;
    }

    static int anchorY(String position, int screenHeight, int height) {
        return position.startsWith("Bottom") ? screenHeight - MARGIN - height : MARGIN;
    }
}
