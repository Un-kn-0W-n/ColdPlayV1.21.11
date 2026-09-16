package cn.timer.coldplay.client.gui.hud;

import cn.timer.coldplay.client.ClientCore;
import cn.timer.coldplay.client.gui.Draw;
import cn.timer.coldplay.client.gui.Theme;
import cn.timer.coldplay.client.hud.HudState;
import cn.timer.coldplay.client.module.impl.visuals.ClickGuiModule;
import cn.timer.coldplay.client.setting.NumberSetting;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.Map;

/** Drag-to-move editor for the HUD elements; any corner of a sizable element resizes it. */
public final class HudEditScreen extends Screen {

    private static final int RESIZE_HANDLE = 8;

    private final HudState hud;

    private String grabbed; // null when idle
    private int grabDX;
    private int grabDY;
    private int grabW;
    private int grabH;
    private boolean resizing;
    private boolean resizeLeft;
    private boolean resizeTop;
    private int fixedX; // the corner opposite the dragged one
    private int fixedY;
    private NumberSetting scaling;
    private double baseScale;
    private float pivotX; // anchor as a fraction of the box
    private float pivotY;

    public HudEditScreen(HudState hud) {
        super(Component.literal("Edit HUD"));
        this.hud = hud;
    }

    @Override
    protected void init() {
        finishDrag();
        hud.rebase(this.width, this.height);
        hud.beginEditing();
    }

    /** The HUD must stay sharp while it is being arranged, so no vanilla blur. */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        for (Map.Entry<String, int[]> entry : hud.getBoxes().entrySet()) {
            int[] b = entry.getValue();
            boolean active = entry.getKey().equals(grabbed)
                    || (grabbed == null && contains(b, mouseX, mouseY));
            // Keep the outline outside the element's own border.
            Draw.outline(graphics, b[0] - 1, b[1] - 1, b[2] + 1, b[3] + 1, Theme.CONTOUR_PX,
                    active ? Theme.FROST : Theme.CONTOUR);
            if (resizable(entry.getKey())) {
                int size = handle(b);
                for (int corner = 0; corner < 4; corner++) {
                    boolean left = (corner & 1) == 0;
                    boolean top = (corner & 2) == 0;
                    Draw.rect(graphics, left ? b[0] : b[2] - size, top ? b[1] : b[3] - 2, size, 2, Theme.FROST);
                    Draw.rect(graphics, left ? b[0] : b[2] - 2, top ? b[1] : b[3] - size, 2, size, Theme.FROST);
                }
            }
        }
        String hint = "Drag HUD elements - Drag a corner to resize - Esc to finish";
        graphics.drawCenteredString(font, hint, this.width / 2, 4, Theme.TEXT_DIM);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int mouseX = (int) event.x();
        int mouseY = (int) event.y();
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT || grabbed != null) {
            return true;
        }
        // resize handles win over any overlapping element
        for (Map.Entry<String, int[]> entry : hud.getBoxes().entrySet()) {
            int[] b = entry.getValue();
            if (resizable(entry.getKey()) && onHandle(b, mouseX, mouseY)) {
                startResize(entry.getKey(), b, mouseX, mouseY);
                return true;
            }
        }
        for (Map.Entry<String, int[]> entry : hud.getBoxes().entrySet()) {
            int[] b = entry.getValue();
            if (contains(b, mouseX, mouseY)) {
                grabbed = entry.getKey();
                grabDX = mouseX - b[0];
                grabDY = mouseY - b[1];
                grabW = b[2] - b[0];
                grabH = b[3] - b[1];
                return true;
            }
        }
        return true;
    }

    private void startResize(String name, int[] b, int mouseX, int mouseY) {
        int size = handle(b);
        grabbed = name;
        resizing = true;
        resizeLeft = mouseX < b[0] + size;
        resizeTop = mouseY < b[1] + size;
        fixedX = resizeLeft ? b[2] : b[0];
        fixedY = resizeTop ? b[3] : b[1];
        grabDX = mouseX - (resizeLeft ? b[0] : b[2]);
        grabDY = mouseY - (resizeTop ? b[1] : b[3]);
        grabW = b[2] - b[0];
        grabH = b[3] - b[1];
        HudState.Position position = hud.getOrCreate(name, b[0], b[1]);
        scaling = hud.getScale(name);
        baseScale = scaling.get();
        pivotX = (position.x - b[0]) / (float) Math.max(1, grabW);
        pivotY = (position.y - b[1]) / (float) Math.max(1, grabH);
    }

    /** Moves or resizes the grabbed element to follow the cursor. */
    public void updateDrag(int mouseX, int mouseY) {
        int[] b = grabbed != null ? hud.getBox(grabbed) : null;
        if (b == null) {
            return;
        }
        if (resizing) {
            int edgeX = Math.clamp(mouseX - grabDX, 0, this.width);
            int edgeY = Math.clamp(mouseY - grabDY, 0, this.height);
            int w = resizeLeft ? fixedX - edgeX : edgeX - fixedX;
            int h = resizeTop ? fixedY - edgeY : edgeY - fixedY;
            int baseW = Math.max(1, grabW);
            int baseH = Math.max(1, grabH);
            double ratio = Math.max(w / (double) baseW, h / (double) baseH);
            // stay on screen, unless the element already spilled past it
            double fit = Math.min((resizeLeft ? fixedX : this.width - fixedX) / (double) baseW,
                    (resizeTop ? fixedY : this.height - fixedY) / (double) baseH);
            scaling.set(baseScale * Math.min(ratio, Math.max(1.0, fit)));
            double applied = scaling.get() / baseScale;
            w = (int) Math.round(grabW * applied);
            h = (int) Math.round(grabH * applied);
            int left = resizeLeft ? fixedX - w : fixedX;
            int top = resizeTop ? fixedY - h : fixedY;
            HudState.Position position = hud.getOrCreate(grabbed, left, top);
            if ("ArrayList".equals(grabbed)) {
                anchorCorner(position, left, top, w, h);
            } else {
                position.x = left + Math.round(pivotX * w);
                position.y = top + Math.round(pivotY * h);
            }
            hud.report(grabbed, left, top, left + w, top + h);
            return;
        }
        int newLeft = Math.clamp(mouseX - grabDX, 0, Math.max(0, this.width - grabW));
        int newTop = Math.clamp(mouseY - grabDY, 0, Math.max(0, this.height - grabH));
        int dx = newLeft - b[0];
        int dy = newTop - b[1];
        HudState.Position state = hud.getOrCreate(grabbed, newLeft, newTop);
        if ("ArrayList".equals(grabbed)) {
            anchorCorner(state, newLeft, newTop, grabW, grabH);
        } else {
            state.x += dx;
            state.y += dy;
        }
        hud.translateBox(grabbed, dx, dy);
    }

    /** ArrayList anchors to the nearest screen corner, so store that corner. */
    private void anchorCorner(HudState.Position position, int left, int top, int w, int h) {
        position.x = left + (left + w / 2 > this.width / 2 ? w : 0);
        position.y = top + (top + h / 2 < this.height / 2 ? 0 : h);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        updateDrag((int) event.x(), (int) event.y());
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && grabbed != null) {
            updateDrag((int) event.x(), (int) event.y());
            finishDrag();
            ClientCore.get().save();
        }
        return true;
    }

    private void finishDrag() {
        grabbed = null;
        resizing = false;
        scaling = null;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        ClickGuiModule clickGui = ClientCore.get().modules().get(ClickGuiModule.class);
        KeyMapping guiKey = clickGui == null ? null : clickGui.keybind().mapping();
        if (event.isEscape() || (guiKey != null && guiKey.matches(event))) {
            // drop this screen first so the Click GUI does not adopt it as its parent
            minecraft.setScreen(null);
            ClientCore.get().openClickGui();
            return true;
        }
        return false;
    }

    @Override
    public void removed() {
        finishDrag();
        hud.endEditing();
        ClientCore.get().save();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private boolean resizable(String name) {
        return hud.getScale(name) != null;
    }

    /** Corner grab size, shrunk so small boxes keep a middle to drag by. */
    private static int handle(int[] b) {
        return Math.max(2, Math.min(RESIZE_HANDLE, Math.min(b[2] - b[0], b[3] - b[1]) / 3));
    }

    private static boolean onHandle(int[] b, int x, int y) {
        int size = handle(b);
        return contains(b, x, y)
                && (x < b[0] + size || x >= b[2] - size)
                && (y < b[1] + size || y >= b[3] - size);
    }

    private static boolean contains(int[] b, int x, int y) {
        return Draw.hoveredExclusive(x, y, b[0], b[1], b[2] - b[0], b[3] - b[1]);
    }
}
