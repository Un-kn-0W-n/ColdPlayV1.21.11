package cn.timer.coldplay.client.gui.click;

import cn.timer.coldplay.client.gui.Draw;
import cn.timer.coldplay.client.gui.Theme;
import cn.timer.coldplay.client.module.Module;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** One module row inside a CategoryPanel. */
public final class ModuleButton {

    private static final int NAME_X = 6;
    private static final int QMARK_SIZE = 12;
    private static final int QMARK_MARGIN = 4;
    private static final int NAME_GAP = 8;

    private final Module module;

    public ModuleButton(Module module) {
        this.module = module;
    }

    public Module getModule() {
        return module;
    }

    public void render(GuiGraphics graphics, Font font, int x, int rowY, int width,
                       int mouseX, int mouseY, boolean listening) {
        boolean hovered = Draw.hovered(mouseX, mouseY, x, rowY, width, ClickGuiScreen.ROW_HEIGHT);
        boolean on = module.enabled();

        if (hovered) {
            Draw.rect(graphics, x, rowY, width, ClickGuiScreen.ROW_HEIGHT, Theme.HOVER_LIFT);
        }
        if (on) {
            Draw.rect(graphics, x + Theme.CONTOUR_PX, rowY, Theme.TICK_PX, ClickGuiScreen.ROW_HEIGHT, Theme.FROST);
        }

        String glyph = listening ? "_" : glyph();
        int qw = qmarkWidth(font, glyph);
        int qx = x + width - QMARK_MARGIN - qw;
        int qy = qmarkTop(rowY);

        // a key chip that grew after the panel was sized must not run into the name
        int textY = Draw.textY(rowY, ClickGuiScreen.ROW_HEIGHT);
        String name = Draw.trimToWidth(font, module.name(), qx - NAME_GAP / 2 - (x + NAME_X), "..");
        Draw.text(graphics, font, name, x + NAME_X, textY, on ? Theme.TEXT : Theme.TEXT_DIM);
        boolean qHover = Draw.hovered(mouseX, mouseY, qx, qy, qw, QMARK_SIZE);
        Draw.rect(graphics, qx, qy, qw, QMARK_SIZE, listening ? Theme.lighten(Theme.WELL, 70) : Theme.WELL);
        if (qHover) {
            Draw.outline(graphics, qx, qy, qx + qw, qy + QMARK_SIZE, 1, Theme.CONTOUR);
        }
        Draw.textCentered(graphics, font, glyph, qx, qy, qw, QMARK_SIZE,
                qHover ? Theme.TEXT : Theme.TEXT_DIM);
    }

    private String glyph() {
        InputConstants.Key key = module.keybind().get();
        if (key == null || key.equals(InputConstants.UNKNOWN)) {
            return "?";
        }
        String name = compactKeyName(key.getDisplayName().getString());
        return name.isEmpty() ? "?" : name;
    }

    /** "Right Shift" reads as "RShift" so the key chip stays narrow. */
    static String compactKeyName(String name) {
        if (name == null) {
            return "";
        }
        return name.replace("Right ", "R").replace("Left ", "L").trim();
    }

    private static int qmarkWidth(Font font, String glyph) {
        return Math.max(QMARK_SIZE, font.width(glyph) + 4);
    }

    static int qmarkTop(int rowY) {
        return rowY + (ClickGuiScreen.ROW_HEIGHT - QMARK_SIZE) / 2;
    }

    boolean isOverQmark(Font font, int mouseX, int mouseY, int rowX, int rowY, int width) {
        int qw = qmarkWidth(font, glyph());
        return Draw.hovered(mouseX, mouseY, rowX + width - QMARK_MARGIN - qw, qmarkTop(rowY), qw, QMARK_SIZE);
    }

    /** Sized for the key currently bound, so panels open wide enough for names like "RShift". */
    static int preferredWidth(Module module, Font font) {
        int chip = qmarkWidth(font, new ModuleButton(module).glyph());
        return NAME_X + font.width(module.name()) + NAME_GAP + chip + QMARK_MARGIN;
    }
}
