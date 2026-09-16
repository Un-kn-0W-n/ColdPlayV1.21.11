package cn.timer.coldplay.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Util;

import java.util.Locale;

/** Animated menu backdrop and the section-title treatment shared by the full-screen ColdPlay panels. */
public final class MenuBackground {

    public static final int ABYSS = 0xFF07101D;
    public static final int ICE = 0xFFE8F0F8;

    private static final int TITLE_HEIGHT = 12;
    private static final float TITLE_SCALE = 1.5F;
    private static final int RAIL_HALF = 18;
    private static final long CYCLE_MILLIS = 24_000L;

    private MenuBackground() {
    }

    /** Slow-drifting banded gradient with a frost sweep crossing the screen. */
    public static void draw(GuiGraphics graphics, int width, int height) {
        int bands = Math.clamp(height / 7, 18, 56);
        float phase = (Util.getMillis() % CYCLE_MILLIS) / (float) CYCLE_MILLIS;
        for (int i = 0; i < bands; i++) {
            float y0 = i / (float) bands;
            float y1 = (i + 1) / (float) bands;
            float current = 0.5F + 0.5F * (float) Math.sin(i * 0.47F + phase * Math.PI * 2.0F);
            int base = lerp(0xFF0A1A2C, 0xFF040810, y0);
            int color = lerp(base, 0xFF16414E, 0.10F + current * 0.14F);
            Draw.rectBounds(graphics, 0, Math.round(y0 * height), width, Math.round(y1 * height) + 1, color);
        }

        int half = Math.max(70, width / 5);
        int travel = width + half * 2;
        int center = Math.round(phase * travel) - half;
        for (int i = 6; i >= 1; i--) {
            int sweep = half * i / 3;
            int alpha = 3 + (7 - i) * 3;
            Draw.rectBounds(graphics, center - sweep, 0, center + sweep, height, Theme.withAlpha(0xFF4FBDD0, alpha));
        }
    }

    /** Upper-case title with a short frost rail beneath it, centered on {@code centerX}. */
    public static void drawSectionTitle(GuiGraphics graphics, Font font, String title, int centerX, int y) {
        String label = title.toUpperCase(Locale.ROOT);
        Draw.pushScale(graphics, centerX, y, TITLE_SCALE);
        Draw.textShadow(graphics, font, label, Math.round(centerX - font.width(label) / 2.0F), y, ICE);
        Draw.popScale(graphics);
        int railY = y + TITLE_HEIGHT + 3;
        Draw.rectBounds(graphics, centerX - RAIL_HALF, railY, centerX + RAIL_HALF, railY + 1, Theme.FROST);
    }

    private static int lerp(int from, int to, float t) {
        float clamped = Math.clamp(t, 0.0F, 1.0F);
        int a = channel(from >>> 24, to >>> 24, clamped);
        int r = channel(from >> 16 & 0xFF, to >> 16 & 0xFF, clamped);
        int g = channel(from >> 8 & 0xFF, to >> 8 & 0xFF, clamped);
        int b = channel(from & 0xFF, to & 0xFF, clamped);
        return a << 24 | r << 16 | g << 8 | b;
    }

    private static int channel(int from, int to, float t) {
        return Math.round(from + (to - from) * t);
    }
}
