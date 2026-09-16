package cn.timer.coldplay.client.gui.click;

import cn.timer.coldplay.client.ClientCore;
import cn.timer.coldplay.client.gui.Draw;
import cn.timer.coldplay.client.gui.Theme;
import cn.timer.coldplay.client.module.Category;
import cn.timer.coldplay.client.module.Module;
import cn.timer.coldplay.client.util.Animation;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/** Draggable Click GUI panel listing one category's modules. */
public final class CategoryPanel {

    private static final int ARROW_X = 5;
    private static final int NAME_X = 14;
    private static final int RIGHT_PAD = 8;
    private static final int MIN_WIDTH = 78;

    private final ClickGuiScreen screen;
    private final Category category;
    private final Font font;
    private final List<ModuleButton> buttons = new ArrayList<>();
    private final int width;

    private int x;
    private int y;
    private boolean collapsed;

    private final Animation reveal = new Animation(0.0, Theme.WIPE_SPEED); // 0 collapsed, 1 expanded
    private int visualHeight = ClickGuiScreen.HEADER_HEIGHT;

    private final WindowDrag windowDrag = new WindowDrag();

    public CategoryPanel(ClickGuiScreen screen, Category category, int x, int y, Font font) {
        this.screen = screen;
        this.category = category;
        this.font = font;
        this.x = x;
        this.y = y;
        for (Module module : ClientCore.get().modules().getByCategory(category)) {
            buttons.add(new ModuleButton(module));
        }
        this.width = computeWidth();
    }

    private int computeWidth() {
        int w = NAME_X + font.width(category.name()) + RIGHT_PAD;
        for (ModuleButton b : buttons) {
            w = Math.max(w, ModuleButton.preferredWidth(b.getModule(), font));
        }
        return Math.max(w, MIN_WIDTH);
    }

    public void render(GuiGraphics graphics, int mouseX, int mouseY, Module listeningModule) {
        renderContent(graphics, mouseX, mouseY, listeningModule);
        renderChrome(graphics);
    }

    /** Split from renderChrome so docked panels can share one border. */
    public void renderContent(GuiGraphics graphics, int mouseX, int mouseY, Module listeningModule) {
        double p = Theme.step(reveal, collapsed ? 0.0 : 1.0);
        int visRowsH = (int) Math.round(visibleCount() * ClickGuiScreen.ROW_HEIGHT * p);
        visualHeight = ClickGuiScreen.HEADER_HEIGHT + visRowsH;

        ClickGuiScreen.drawWindowBase(graphics, x, y, width, visualHeight);
        int textY = Draw.textY(y, ClickGuiScreen.HEADER_HEIGHT);
        drawArrow(graphics, x + ARROW_X, y + (ClickGuiScreen.HEADER_HEIGHT - 5) / 2, collapsed, Theme.TEXT_DIM);
        Draw.text(graphics, font, category.name(), x + NAME_X, textY, Theme.TEXT);

        if (visRowsH > 0) {
            Draw.scissor(graphics, x, y + ClickGuiScreen.HEADER_HEIGHT, width, visRowsH);
            int rowY = y + ClickGuiScreen.HEADER_HEIGHT;
            for (ModuleButton button : buttons) {
                if (!screen.matchesSearch(button.getModule())) {
                    continue;
                }
                button.render(graphics, font, x, rowY, width, mouseX, mouseY, button.getModule() == listeningModule);
                if (rowY > y + ClickGuiScreen.HEADER_HEIGHT) {
                    Draw.rectBounds(graphics, x, rowY, x + width, rowY + 1, Theme.SEP);
                }
                rowY += ClickGuiScreen.ROW_HEIGHT;
            }
            Draw.unscissor(graphics);
        }
    }

    /** Drawn after content so row separators do not cover the border. */
    public void renderChrome(GuiGraphics graphics) {
        ClickGuiScreen.drawWindowFrame(graphics, x, y, width, visualHeight);
    }

    /** 5x5 pixel triangle; the font has no arrow glyph. */
    private static void drawArrow(GuiGraphics graphics, int ax, int ay, boolean collapsed, int color) {
        for (int i = 0; i < 3; i++) {
            if (collapsed) {
                Draw.rectBounds(graphics, ax + i, ay + i, ax + i + 1, ay + 5 - i, color);
            } else {
                Draw.rectBounds(graphics, ax + i, ay + i, ax + 5 - i, ay + i + 1, color);
            }
        }
    }

    public boolean mouseClicked(int mouseX, int mouseY, int button) {
        if (button == 0 && Draw.hovered(mouseX, mouseY, x, y, NAME_X, ClickGuiScreen.HEADER_HEIGHT)) {
            collapsed = !collapsed;
            persistAndSave();
            return true;
        }
        // Consume header clicks even when they do not start a drag.
        if (Draw.hovered(mouseX, mouseY, x, y, width, ClickGuiScreen.HEADER_HEIGHT)) {
            if (button == 0) {
                windowDrag.begin(mouseX, mouseY, x, y);
            }
            return true;
        }
        if (collapsed) {
            return false;
        }
        int rowY = y + ClickGuiScreen.HEADER_HEIGHT;
        for (ModuleButton b : buttons) {
            if (!screen.matchesSearch(b.getModule())) {
                continue;
            }
            if (Draw.hovered(mouseX, mouseY, x, rowY, width, ClickGuiScreen.ROW_HEIGHT)) {
                boolean qmark = button == 0 && b.isOverQmark(font, mouseX, mouseY, x, rowY, width);
                screen.handleModuleClick(this, b.getModule(), qmark ? 1 : button);
                return true;
            }
            rowY += ClickGuiScreen.ROW_HEIGHT;
        }
        return false;
    }

    public void drag(int mouseX, int mouseY, int screenWidth, int screenHeight) {
        int[] pos = windowDrag.update(mouseX, mouseY, width, ClickGuiScreen.HEADER_HEIGHT, screenWidth, screenHeight);
        if (pos != null) {
            x = pos[0];
            y = pos[1];
        }
    }

    public void mouseReleased() {
        if (windowDrag.isDragging()) {
            windowDrag.end();
            persistAndSave();
        }
    }

    /** Updates the screen's saved state without writing it to disk. */
    public void persist() {
        screen.storePanelState(category, x, y, collapsed);
    }

    private void persistAndSave() {
        persist();
        ClientCore.get().save();
    }

    public void clampToScreen(int screenWidth, int screenHeight) {
        x = Math.clamp(x, 0, Math.max(0, screenWidth - width));
        y = Math.clamp(y, 0, Math.max(0, screenHeight - ClickGuiScreen.HEADER_HEIGHT));
    }

    public Category getCategory() {
        return category;
    }

    public int getWidth() {
        return width;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public boolean isCollapsed() {
        return collapsed;
    }

    /** Logical height, not the animated one. */
    public int getHeight() {
        return totalHeight();
    }

    public int getVisualHeight() {
        return visualHeight;
    }

    /** -1 when the row is hidden or absent. */
    public int rowTop(Module module) {
        if (collapsed) {
            return -1;
        }
        int rowY = y + ClickGuiScreen.HEADER_HEIGHT;
        for (ModuleButton b : buttons) {
            if (!screen.matchesSearch(b.getModule())) {
                continue;
            }
            if (b.getModule() == module) {
                return rowY;
            }
            rowY += ClickGuiScreen.ROW_HEIGHT;
        }
        return -1;
    }

    public boolean contains(int mouseX, int mouseY) {
        return Draw.hovered(mouseX, mouseY, x, y, width, totalHeight());
    }

    public String getTooltipAt(int mouseX, int mouseY) {
        if (collapsed) {
            return null;
        }
        int rowY = y + ClickGuiScreen.HEADER_HEIGHT;
        for (ModuleButton b : buttons) {
            if (!screen.matchesSearch(b.getModule())) {
                continue;
            }
            if (Draw.hovered(mouseX, mouseY, x, rowY, width, ClickGuiScreen.ROW_HEIGHT)) {
                return b.getModule().description();
            }
            rowY += ClickGuiScreen.ROW_HEIGHT;
        }
        return null;
    }

    public void restoreState(int x, int y, boolean collapsed) {
        this.x = x;
        this.y = y;
        this.collapsed = collapsed;
        reveal.set(collapsed ? 0.0 : 1.0);
    }

    private int totalHeight() {
        return ClickGuiScreen.HEADER_HEIGHT + (collapsed ? 0 : visibleCount() * ClickGuiScreen.ROW_HEIGHT);
    }

    private int visibleCount() {
        int n = 0;
        for (ModuleButton b : buttons) {
            if (screen.matchesSearch(b.getModule())) {
                n++;
            }
        }
        return n;
    }
}
