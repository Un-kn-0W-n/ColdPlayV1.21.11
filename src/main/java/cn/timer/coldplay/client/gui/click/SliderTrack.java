package cn.timer.coldplay.client.gui.click;

import cn.timer.coldplay.client.gui.Draw;
import cn.timer.coldplay.client.gui.Theme;
import net.minecraft.client.gui.GuiGraphics;

/** Slider track shared by number and range settings. */
final class SliderTrack {
    private final int x;
    private final int y;
    private final int width;

    SliderTrack(int x, int y, int width) {
        this.x = x;
        this.y = y;
        this.width = Math.max(0, width);
    }

    void drawSingle(GuiGraphics graphics, double value, double min, double max) {
        drawBase(graphics);
        int thumb = position(value, min, max);
        Draw.rect(graphics, x, y, Math.max(0, thumb - x), 3, Theme.FROST);
        Draw.rect(graphics, thumb - 1, y - 2, 3, 7, Theme.FROST);
    }

    void drawRange(GuiGraphics graphics, double lo, double hi, double min, double max) {
        drawBase(graphics);
        if (max <= min) {
            return;
        }
        int loX = position(lo, min, max);
        int hiX = position(hi, min, max);
        Draw.rect(graphics, loX, y, Math.max(1, hiX - loX), 3, Theme.FROST);
        Draw.rect(graphics, loX - 1, y - 1, 3, 5, Theme.FROST);
        Draw.rect(graphics, hiX - 1, y - 1, 3, 5, Theme.FROST);
    }

    double valueAt(int mouseX, double min, double max) {
        if (width <= 0 || max <= min) {
            return min;
        }
        double ratio = Math.clamp((mouseX - x) / (double) width, 0.0D, 1.0D);
        return min + ratio * (max - min);
    }

    int nearestHandle(double lo, double hi, double min, double max, int mouseX) {
        int loX = position(lo, min, max);
        int hiX = position(hi, min, max);
        if (hiX - loX < 0.5D) {
            return mouseX >= hiX ? 1 : 0;
        }
        return Math.abs(mouseX - loX) <= Math.abs(mouseX - hiX) ? 0 : 1;
    }

    private void drawBase(GuiGraphics graphics) {
        Draw.rect(graphics, x, y, width, 3, Theme.WELL);
    }

    private int position(double value, double min, double max) {
        double span = max - min;
        double ratio = span > 0.0D
                ? Math.clamp((value - min) / span, 0.0D, 1.0D)
                : 0.0D;
        return x + (int) (width * ratio);
    }
}
