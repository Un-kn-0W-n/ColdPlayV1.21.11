package cn.timer.coldplay.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** Flat panel button: filled well, separator outline that brightens on hover, centered label. */
public final class StyledButton {
    private StyledButton() {
    }

    /** Returns whether the button is hovered (never while disabled). */
    public static boolean draw(GuiGraphics graphics, Font font, String label,
                               int x, int y, int width, int height,
                               int mouseX, int mouseY, boolean enabled,
                               int background, int text) {
        boolean hover = enabled && Draw.hovered(mouseX, mouseY, x, y, width, height);

        Draw.rect(graphics, x, y, width, height, background);
        Draw.outline(graphics, x, y, x + width, y + height, 1, hover ? Theme.CONTOUR : Theme.SEP);
        Draw.textCentered(graphics, font, label, x, y, width, height, enabled ? text : Theme.TEXT_MUTE);
        return hover;
    }
}
