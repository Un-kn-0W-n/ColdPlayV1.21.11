package cn.timer.coldplay.client.module.impl.visuals;

import cn.timer.coldplay.client.gui.Draw;
import cn.timer.coldplay.client.gui.Theme;
import cn.timer.coldplay.client.hud.HudState;
import cn.timer.coldplay.client.module.Category;
import cn.timer.coldplay.client.module.Module;
import cn.timer.coldplay.client.module.ModuleManager;
import cn.timer.coldplay.client.setting.BooleanSetting;
import cn.timer.coldplay.client.setting.NumberSetting;
import cn.timer.coldplay.client.util.Animation;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Enabled-module list plus watermark; rows linger after disable so they can fade out. */
public final class Hud extends Module {

    private static final int COLOR_BOX = 0xF0101014;
    private static final int COLOR_BORDER = 0xFFB9B9C2;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_SUFFIX = 0xFFB4B4BE; // gray mode suffix / watermark version

    // Layout dimensions use scaled GUI pixels.
    private static final int MARGIN = 3;   // default gap from the screen edge
    private static final int WM_GAP = 3;   // default gap between watermark and list
    private static final int PAD_X = 5;    // horizontal text padding inside a box
    private static final int PAD_Y = 2;    // vertical text padding inside a box
    private static final double ANIM_SPEED = 13.0; // higher = snappier ease
    private static final float TITLE_SCALE = 1.5f; // watermark name relative to the list font
    private static final int TITLE_H = Math.round(Draw.TEXT_H * TITLE_SCALE);
    private static final String CLIENT_NAME = "ColdPlay";

    private final BooleanSetting arrayList = addOwnerSetting(new BooleanSetting("ArrayList", true));
    private final BooleanSetting suffixes = addChildSetting(arrayList, new BooleanSetting("Suffixes", true));
    private final NumberSetting listScale = addChildSetting(arrayList, HudState.scaleSetting("ArrayList Scale"));
    private final BooleanSetting watermark = addOwnerSetting(new BooleanSetting("Watermark", true));
    private final NumberSetting watermarkScale = addChildSetting(watermark, HudState.scaleSetting("Watermark Scale"));
    private final BooleanSetting animations = addSetting(new BooleanSetting("Animations", true));
    private final BooleanSetting statusBars = addSetting(new BooleanSetting("Status Bars", true));

    private final ModuleManager modules;
    private final HudState hud;
    private final String clientVersion;
    private final Map<String, Row> progress = new LinkedHashMap<>();

    public Hud(ModuleManager modules, HudState hud) {
        super("Hud", "On-screen HUD: a list of active modules, a watermark and pill status bars",
                Category.VISUALS, GLFW.GLFW_KEY_UNKNOWN);
        this.modules = Objects.requireNonNull(modules, "modules");
        this.hud = Objects.requireNonNull(hud, "hud");
        this.clientVersion = FabricLoader.getInstance().getModContainer("coldplay")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("dev");
        hud.registerScale("ArrayList", listScale);
        hud.registerScale("Watermark", watermarkScale);
    }

    /** Pill bars replace the vanilla health, armor, hunger and experience rows while this holds. */
    public boolean statusBarsActive() {
        return enabled() && statusBars.get();
    }

    public void render(GuiGraphics graphics, DeltaTracker ignored) {
        if (!enabled()) {
            return;
        }
        boolean showList = arrayList.get();
        boolean showWatermark = watermark.get();
        if (!showList && !showWatermark) {
            return;
        }

        Font font = Minecraft.getInstance().font;
        int screenWidth = graphics.guiWidth();
        int screenHeight = graphics.guiHeight();

        // The list anchor is the corner its rows pin to; which screen half it sits in picks the alignment.
        float wmScale = watermarkScale.get().floatValue();
        float scale = listScale.get().floatValue();
        int wmHeight = Math.round((TITLE_H + PAD_Y * 2) * wmScale);
        HudState.Position wmState = hud.getOrCreate("Watermark", MARGIN, MARGIN,
                screenWidth, screenHeight);
        HudState.Position listState = hud.getOrCreate(
                "ArrayList", MARGIN, MARGIN + wmHeight + WM_GAP, screenWidth, screenHeight);
        boolean right = listState.x > screenWidth / 2;
        boolean top = listState.y < screenHeight / 2;

        updateAnimations(showList, animations.get());

        // Longest row hugs the anchored corner; the name tie-break stops equal widths from swapping.
        boolean showSuffixes = suffixes.get();
        List<Line> lines = new ArrayList<>();
        for (Map.Entry<String, Row> entry : progress.entrySet()) {
            Row row = entry.getValue();
            double p = row.animation.get();
            if (p <= 0.001) {
                continue;
            }
            lines.add(new Line(entry.getKey(), showSuffixes ? row.suffix : null, font, p));
        }
        lines.sort(BY_WIDTH_DESC);
        if (!top) {
            Collections.reverse(lines);
        }
        if (lines.isEmpty() && !showWatermark) {
            return;
        }

        // For a bottom anchor the stored y is the stack's bottom edge, so rows grow upward from it.
        double listHeight = 0;
        for (Line l : lines) {
            listHeight += rowHeight(l.progress);
        }
        double rowsTop = top ? listState.y : listState.y - listHeight;

        resolveGeometry(lines, rowsTop, listState.x, right);
        Draw.pushScale(graphics, listState.x, listState.y, scale);
        for (Line l : lines) {
            Draw.rectBounds(graphics, l.left, l.top, l.right, l.bottom, Theme.applyAlpha(COLOR_BOX, (float) l.progress));
        }
        drawContour(graphics, lines, listState.x, right);
        for (Line l : lines) {
            drawText(graphics, l, listState.x, right);
        }
        Draw.popScale(graphics);
        if (showWatermark) {
            drawWatermark(graphics, font, wmState.x, wmState.y, wmScale);
        }
        if (!lines.isEmpty() && hud.isEditing()) {
            int minLeft = Integer.MAX_VALUE;
            int maxRight = Integer.MIN_VALUE;
            for (Line l : lines) {
                minLeft = Math.min(minLeft, l.left);
                maxRight = Math.max(maxRight, l.right);
            }
            hud.report("ArrayList", minLeft, lines.getFirst().top,
                    maxRight, lines.getLast().bottom, listState.x, listState.y, scale);
        }
    }

    private void updateAnimations(boolean showList, boolean anim) {
        Map<String, Module> current = new LinkedHashMap<>();
        for (Module module : modules.modules()) {
            current.put(module.name(), module);
        }
        if (showList) {
            for (Module module : current.values()) {
                if (module == this) {
                    continue; // never list the HUD module itself
                }
                if (module.enabled() && !progress.containsKey(module.name())) {
                    progress.put(module.name(), new Row(module.suffix()));
                }
            }
        }
        Iterator<Map.Entry<String, Row>> it = progress.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Row> entry = it.next();
            Module module = current.get(entry.getKey());
            Row row = entry.getValue();
            boolean on = showList && module != null && module.enabled() && module != this;
            if (module != null) {
                row.suffix = module.suffix();
            }
            double target = on ? 1.0 : 0.0;
            if (anim) {
                row.animation.update(target);
            } else {
                row.animation.set(target);
            }
            if (!on && row.animation.get() < 0.01) {
                it.remove();
            }
        }
    }

    /** Height of a row at {@code progress} of its wipe, in unscaled GUI pixels. */
    static double rowHeight(double progress) {
        return (Draw.TEXT_H + PAD_Y * 2) * progress;
    }

    /** Rounds cumulative boundaries so adjacent rows share a pixel edge. */
    private static void resolveGeometry(List<Line> lines, double startY, int anchorX, boolean right) {
        double y = startY;
        for (Line l : lines) {
            double slotH = rowHeight(l.progress);
            l.top = (int) Math.round(y);
            l.bottom = (int) Math.round(y + slotH);
            int boxWidth = (int) Math.round((l.width + PAD_X * 2) * l.progress);
            l.left = right ? anchorX - boxWidth : anchorX;
            l.right = l.left + boxWidth;
            y += slotH;
        }
    }

    /** Draws the shared border; connectors between rows of different width sit inside the wider row's fill. */
    private static void drawContour(GuiGraphics graphics, List<Line> lines, int anchorX, boolean right) {
        if (lines.isEmpty()) {
            return;
        }
        int outer = anchorX;                      // shared straight edge (fill boundary)
        int outerCol = right ? outer - 1 : outer; // 1px line column just inside the fills
        Line prev = null;
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            int inner = right ? l.left : l.right;          // this row's free-edge fill boundary
            int innerCol = right ? inner : inner - 1;
            int color = Theme.applyAlpha(COLOR_BORDER, (float) l.progress);

            Draw.vLine(graphics, outerCol, l.top, l.bottom, color);
            Draw.vLine(graphics, innerCol, l.top, l.bottom, color);
            if (i == 0) {
                Draw.hLine(graphics, outer, inner, l.top, color);
            }
            if (i == lines.size() - 1) {
                Draw.hLine(graphics, outer, inner, l.bottom - 1, color);
            }

            if (prev != null) {
                int innerPrev = right ? prev.left : prev.right;
                if (innerPrev != inner) { // equal widths merge into one slab
                    boolean prevWider = Math.abs(innerPrev - outer) > Math.abs(inner - outer);
                    Line wide = prevWider ? prev : l;
                    int bandY = prevWider ? l.top - 1 : l.top; // sits inside the wider row's fill
                    int x0 = Math.min(innerPrev, inner);
                    int x1 = Math.max(innerPrev, inner);
                    // Extend one px toward the narrower row so the connector butt-joins its inner line.
                    if (right) {
                        x1 += 1;
                    } else {
                        x0 -= 1;
                    }
                    Draw.rectBounds(graphics, x0, bandY, x1, bandY + 1,
                            Theme.applyAlpha(COLOR_BORDER, (float) wide.progress));
                }
            }
            prev = l;
        }
    }

    /** Text is pinned at its fully-shown position and scissored to the box while the box wipes in. */
    private static void drawText(GuiGraphics graphics, Line l, int anchorX, boolean right) {
        if (l.right <= l.left || l.bottom <= l.top) {
            return;
        }
        float p = (float) l.progress;
        int textX = right ? anchorX - l.width - PAD_X : anchorX + PAD_X;
        int textY = l.top + PAD_Y;
        boolean clip = p < 0.999F; // fully shown rows need no scissor
        if (clip) {
            Draw.scissor(graphics, l.left, l.top, l.right - l.left, l.bottom - l.top); // pose-aware
        }
        Draw.textShadow(graphics, l.font, l.name, textX, textY, Theme.applyAlpha(COLOR_TEXT, p));
        if (l.suffix != null) {
            Draw.textShadow(graphics, l.font, " " + l.suffix, textX + l.nameWidth, textY,
                    Theme.applyAlpha(COLOR_SUFFIX, p));
        }
        if (clip) {
            Draw.unscissor(graphics);
        }
    }

    private void drawWatermark(GuiGraphics graphics, Font font, int left, int top, float scale) {
        String name = CLIENT_NAME;
        String version = " v" + clientVersion;
        int nameWidth = Math.round(font.width(name) * TITLE_SCALE);
        int chipWidth = nameWidth + font.width(version) + PAD_X * 2;
        int chipHeight = TITLE_H + PAD_Y * 2;

        Draw.pushScale(graphics, left, top, scale);
        Draw.borderedRect(graphics, left, top, left + chipWidth, top + chipHeight, COLOR_BOX, COLOR_BORDER);
        graphics.pose().pushMatrix();
        graphics.pose().translate(left + PAD_X, top + PAD_Y);
        graphics.pose().scale(TITLE_SCALE, TITLE_SCALE);
        Draw.textShadow(graphics, font, name, 0, 0, COLOR_TEXT);
        graphics.pose().popMatrix();
        // align the version's caps with the title's baseline
        int versionY = top + PAD_Y + (Math.round(Draw.CAP_H * TITLE_SCALE) - Draw.CAP_H);
        Draw.textShadow(graphics, font, version, left + PAD_X + nameWidth, versionY, COLOR_SUFFIX);
        Draw.popScale(graphics);
        if (hud.isEditing()) {
            hud.report("Watermark", left, top, left + chipWidth, top + chipHeight, left, top, scale);
        }
    }

    private static final class Row {
        final Animation animation = new Animation(0.0, ANIM_SPEED);
        String suffix;

        Row(String suffix) {
            this.suffix = suffix;
        }
    }

    private static final Comparator<Line> BY_WIDTH_DESC =
            Comparator.comparingInt((Line l) -> l.width).reversed().thenComparing(l -> l.name);

    private static final class Line {
        final String name;
        final String suffix; // active mode value, or null
        final Font font;
        final int nameWidth;
        final int width;     // full text width incl. suffix
        final double progress;

        int left;
        int right;
        int top;
        int bottom;

        Line(String name, String suffix, Font font, double progress) {
            this.name = name;
            this.suffix = suffix;
            this.font = font;
            this.nameWidth = font.width(name);
            this.width = nameWidth + (suffix != null ? font.width(" " + suffix) : 0);
            this.progress = progress;
        }
    }
}
