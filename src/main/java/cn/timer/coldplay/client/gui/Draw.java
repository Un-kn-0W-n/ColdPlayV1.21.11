package cn.timer.coldplay.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** Flat-color drawing helpers on top of {@link GuiGraphics}, in GUI-scaled pixels. */
public final class Draw {

    /** Cap height of the vanilla font; glyphs occupy rows 0-6 with the baseline at row 7. */
    public static final int CAP_H = 7;
    /** Cap height plus the shadow / descender row. */
    public static final int TEXT_H = 8;
    public static final int SCROLL_THUMB_MIN = 14; // px

    private Draw() {
    }

    /** Filled rectangle as x/y/width/height rather than left/top/right/bottom. */
    public static void rect(GuiGraphics graphics, int x, int y, int width, int height, int color) {
        if (width <= 0 || height <= 0) {
            return;
        }
        graphics.fill(x, y, x + width, y + height, color);
    }

    public static void rectBounds(GuiGraphics graphics, int left, int top, int right, int bottom, int color) {
        if (right <= left || bottom <= top) {
            return;
        }
        graphics.fill(left, top, right, bottom, color);
    }

    /** 1px vertical line segment: column at {@code x} spanning [y0, y1). */
    public static void vLine(GuiGraphics graphics, int x, int y0, int y1, int color) {
        rectBounds(graphics, x, Math.min(y0, y1), x + 1, Math.max(y0, y1), color);
    }

    /** 1px horizontal line segment between either-order x bounds at row {@code y}. */
    public static void hLine(GuiGraphics graphics, int xa, int xb, int y, int color) {
        rectBounds(graphics, Math.min(xa, xb), y, Math.max(xa, xb), y + 1, color);
    }

    /** Frame of {@code thickness} px just inside the bounds. */
    public static void outline(GuiGraphics graphics, int left, int top, int right, int bottom, int thickness, int color) {
        rectBounds(graphics, left, top, right, top + thickness, color);
        rectBounds(graphics, left, bottom - thickness, right, bottom, color);
        rectBounds(graphics, left, top, left + thickness, bottom, color);
        rectBounds(graphics, right - thickness, top, right, bottom, color);
    }

    public static void borderedRect(GuiGraphics graphics, int left, int top, int right, int bottom, int fill, int border) {
        borderedRect(graphics, left, top, right, bottom, fill, border, 1);
    }

    public static void borderedRect(GuiGraphics graphics, int left, int top, int right, int bottom,
                                    int fill, int border, int thickness) {
        rectBounds(graphics, left, top, right, bottom, fill);
        outline(graphics, left, top, right, bottom, thickness, border);
    }

    /** Filled rounded rectangle built from pixel rows; radius is clamped to half the short side. */
    public static void roundedRect(GuiGraphics graphics, int x, int y, int width, int height, double radius, int color) {
        if (width <= 0 || height <= 0) {
            return;
        }
        radius = Math.min(radius, Math.min(width, height) / 2.0);
        if (radius <= 0) {
            rect(graphics, x, y, width, height, color);
            return;
        }
        for (int row = 0; row < height; row++) {
            double cy = row + 0.5;
            double d = cy < radius ? radius - cy : (cy > height - radius ? cy - (height - radius) : 0.0);
            int inset = (int) Math.round(radius - Math.sqrt(Math.max(0.0, radius * radius - d * d)));
            rectBounds(graphics, x + inset, y + row, x + width - inset, y + row + 1, color);
        }
    }

    public static boolean hovered(double mouseX, double mouseY, double x, double y, double width, double height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    /** Half-open containment, for adjacent cells without shared-edge overlap. */
    public static boolean hoveredExclusive(double mouseX, double mouseY,
                                           double x, double y, double width, double height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    /** Clips later draws to a GUI-space rectangle; the current pose is applied to the bounds. */
    public static void scissor(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.enableScissor(x, y, x + Math.max(0, width), y + Math.max(0, height));
    }

    public static void unscissor(GuiGraphics graphics) {
        graphics.disableScissor();
    }

    /** Thumb height for a {@code trackH} track showing {@code viewH} of {@code contentH}. */
    public static int scrollThumbHeight(int trackH, int viewH, int contentH) {
        if (trackH <= 0) {
            return 0;
        }
        int natural = (int) Math.round(trackH * (viewH / (double) Math.max(1, contentH)));
        return trackH <= SCROLL_THUMB_MIN
                ? trackH : Math.clamp(natural, SCROLL_THUMB_MIN, trackH);
    }

    /** Thumb offset down the track at {@code scroll} of {@code maxScroll}; 0 when there is no travel. */
    public static int scrollThumbOffset(int trackH, int thumbH, int scroll, int maxScroll) {
        int travel = trackH - thumbH;
        return travel <= 0 || maxScroll <= 0
                ? 0 : (int) Math.round(travel * (scroll / (double) maxScroll));
    }

    /** Top of a text line whose caps sit centered in a box of {@code height}. */
    public static int textY(int top, int height) {
        return top + (height - CAP_H) / 2;
    }

    public static void text(GuiGraphics graphics, Font font, String text, int x, int y, int color) {
        graphics.drawString(font, text, x, y, color, false);
    }

    public static void textShadow(GuiGraphics graphics, Font font, String text, int x, int y, int color) {
        graphics.drawString(font, text, x, y, color, true);
    }

    public static void textCentered(GuiGraphics graphics, Font font, String text,
                                    int x, int y, int width, int height, int color) {
        graphics.drawString(font, text, x + (width - font.width(text)) / 2, textY(y, height), color, false);
    }

    public static String trimToWidth(Font font, String text, int maxWidth, String ellipsis) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        int room = maxWidth - font.width(ellipsis);
        if (room <= 0) {
            return "";
        }
        return font.plainSubstrByWidth(text, room) + ellipsis;
    }

    /** Scales later draws around (x, y); close with {@link #popScale(GuiGraphics)}. */
    public static void pushScale(GuiGraphics graphics, float x, float y, float scale) {
        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        graphics.pose().scale(scale, scale);
        graphics.pose().translate(-x, -y);
    }

    public static void popScale(GuiGraphics graphics) {
        graphics.pose().popMatrix();
    }
}
