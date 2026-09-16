package cn.timer.coldplay.client.gui.click;

import cn.timer.coldplay.client.ClientCore;
import cn.timer.coldplay.client.gui.Draw;
import cn.timer.coldplay.client.gui.TextField;
import cn.timer.coldplay.client.gui.Theme;
import cn.timer.coldplay.client.module.Module;
import cn.timer.coldplay.client.setting.BooleanSetting;
import cn.timer.coldplay.client.setting.ColorSetting;
import cn.timer.coldplay.client.setting.InventoryPickerSetting;
import cn.timer.coldplay.client.setting.InventoryPickerSetting.CategoryPreference;
import cn.timer.coldplay.client.setting.InventoryPickerSetting.CleanerMode;
import cn.timer.coldplay.client.setting.InventoryPickerSetting.Entry;
import cn.timer.coldplay.client.setting.InventoryPickerSetting.ItemPreference;
import cn.timer.coldplay.client.setting.InventoryPickerSetting.Preference;
import cn.timer.coldplay.client.setting.KeybindSetting;
import cn.timer.coldplay.client.setting.ModeSetting;
import cn.timer.coldplay.client.setting.NumberSetting;
import cn.timer.coldplay.client.setting.RangeSetting;
import cn.timer.coldplay.client.setting.Setting;
import cn.timer.coldplay.client.util.Animation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.world.item.ItemStack;

import java.awt.Color;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Settings window for one module. Opens docked beside its category row; dragging the header detaches it. */
public final class SettingsPanel {
    private static final int MIN_WIDTH = 176; // fits a nine-cell grid
    private static final int HEADER = ClickGuiScreen.HEADER_HEIGHT;
    private static final int ROW_H = 16;
    private static final int NUMBER_H = 26;

    private static final int MAX_BODY_H = 300;
    private static final int VIEWPORT_BOTTOM_PAD = 4;
    private static final int BODY_SCROLL_STEP = 18;
    private static final int TRACK_INSET = 6;
    private static final int INDENT_STEP = 8; // px per indent level
    private static final int CHEV_GAP = 2;
    private static final String RANGE_UNIT = "ms";

    private static final int GRID_COLS = 9;
    private static final int CELL = 18; // 16px icon plus 1px frame
    private static final int COL_GAP = 1;
    private static final int ROW_GAP = 2;
    private static final int HB_SEP = 4;

    private static final int WIDGET_PAD = 4;
    private static final int SEARCH_H = 14;
    private static final int VIEW_ROWS = 6;
    private static final int NOT_READY_H = 14;
    private static final int EXCLUDED_CLR = Theme.DANGER;
    private static final int CAT_CLR = Theme.FROST;
    private static final int EXCLUDE_OVERLAY = 0xC8121212;
    private static final int CLEAN_KEEP_CLR = 0xFF6FCF6F;

    private static final int CP_PAD = 5;
    private static final int SB_SIZE = 72; // saturation/brightness square, px
    private static final int HUE_W = 10;
    private static final int CP_GAP = 4;
    private static final int CP_STEP = 4; // gradient tile size, px
    private static final int HUE_STEP = 3;
    private static final int CP_SWATCH = 11;

    private final ClickGuiScreen screen;
    private final Module module;
    private final Font font;
    private final int width;
    private int x;
    private int y;

    private CategoryPanel dockHost; // null once detached
    private boolean dockRight = true;
    private boolean flush;
    private final Animation dockAnim = new Animation(0.0, Theme.WIPE_SPEED); // 0 closed, 1 open
    private int effX; // wipe-clipped rect drawn this frame
    private int effW;

    private int bodyScroll;
    private boolean draggingBodyScroll;

    private final WindowDrag windowDrag = new WindowDrag();
    private NumberSetting draggingSlider;
    private RangeSetting draggingRange;
    private int draggingRangeHandle; // 0 = lo thumb, 1 = hi thumb

    private ColorSetting activeColor; // null unless the picker is open
    private int cpX;
    private int cpY;
    private int cpW;
    private int cpH;
    private int cpSbX;
    private int cpSbY;
    private int cpHueX;
    private float curH;
    private float curS;
    private float curB;
    private boolean draggingSB;
    private boolean draggingHue;

    private int dragMouseX;
    private int dragMouseY;

    private final Map<InventoryPickerSetting<?>, PickerUi> pickers = new IdentityHashMap<>();
    private PickerUi searchingPicker;
    private PickerUi draggingPicker;
    private Preference draggingPreference;
    private ItemStack draggingStack = ItemStack.EMPTY;
    private int cursorCounter;

    public SettingsPanel(ClickGuiScreen screen, Module module, CategoryPanel host, Font font,
                         int screenWidth, int screenHeight) {
        this.screen = screen;
        this.module = module;
        this.font = font;
        this.width = computeWidth(); // layoutDocked needs it
        this.dockHost = host;
        layoutDocked(screenWidth, screenHeight);
    }

    public Module getModule() {
        return module;
    }

    /** Hidden rows are measured too, so revealing a setting does not resize the panel. */
    private int computeWidth() {
        int w = MIN_WIDTH;
        for (Setting<?> s : module.settings()) {
            if (s instanceof KeybindSetting) {
                continue;
            }
            int vw = valueWidth(s);
            w = Math.max(w, 5 + indent(s) * INDENT_STEP + font.width(s.name())
                    + (vw > 0 ? 4 + vw : 0) + 5);
        }
        return w;
    }

    /** Width of the widest value the setting can show. */
    private int valueWidth(Setting<?> s) {
        if (s instanceof ModeSetting mode) {
            int widest = 0;
            for (String m : mode.modes()) {
                widest = Math.max(widest, font.width(m));
            }
            return widest + 2 * (font.width(">") + CHEV_GAP);
        }
        if (s instanceof NumberSetting n) {
            return font.width(worstValueText(n.minimum(), n.maximum(), n.increment()) + unitSuffix(n));
        }
        if (s instanceof RangeSetting r) {
            String worst = worstValueText(r.allowedMinimum(), r.allowedMaximum(), r.increment()) + RANGE_UNIT;
            return font.width(worst + " - " + worst);
        }
        return 0;
    }

    private static String unitSuffix(NumberSetting n) {
        return n.unit().isEmpty() ? "" : " " + n.unit();
    }

    /** Re-docks beside the host row, preferring the right side. Returns false when the row is gone. */
    public boolean layoutDocked(int screenWidth, int screenHeight) {
        if (dockHost == null) {
            return true;
        }
        int rowY = dockHost.rowTop(module);
        if (rowY < 0) {
            return false;
        }
        boolean fitsRight = dockHost.getX() + dockHost.getWidth() + width <= screenWidth;
        boolean fitsLeft = dockHost.getX() - width >= 0;
        flush = fitsRight || fitsLeft;
        if (flush) {
            dockRight = fitsRight;
            x = dockRight ? dockHost.getX() + dockHost.getWidth() : dockHost.getX() - width;
        } else {
            dockRight = screenWidth - (dockHost.getX() + dockHost.getWidth()) >= dockHost.getX();
            x = dockRight ? dockHost.getX() + dockHost.getWidth() : dockHost.getX() - width;
            x = Math.clamp(x, 0, Math.max(0, screenWidth - width));
        }
        y = rowY;
        return true;
    }

    private void detach() {
        dockHost = null;
        flush = false;
        dockAnim.set(1.0);
    }

    public boolean isDocked() {
        return dockHost != null;
    }

    public boolean isDockedTo(CategoryPanel panel) {
        return dockHost == panel;
    }

    public CategoryPanel getDockHost() {
        return dockHost;
    }

    public boolean isFlush() {
        return flush;
    }

    public boolean isDockRight() {
        return dockRight;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getHeight() {
        return HEADER + bodyHeight();
    }

    public int getEffectiveX() {
        return effX;
    }

    public int getEffectiveWidth() {
        return effW;
    }

    // ---- setting visibility and row metrics ----

    private boolean visible(Setting<?> s) {
        if (s instanceof KeybindSetting) {
            return false; // bound from the module row's key chip
        }
        Setting<?> owner = module.ownerOf(s);
        return !(owner instanceof BooleanSetting toggle) || toggle.get();
    }

    private int indent(Setting<?> s) {
        return module.ownerOf(s) == null ? 0 : 1;
    }

    private int contentHeight() {
        int h = 0;
        for (Setting<?> s : module.settings()) {
            if (visible(s)) {
                h += rowHeight(s);
            }
        }
        return h;
    }

    private int bodyHeight() {
        int available = Math.max(96, screen.height - y - HEADER - VIEWPORT_BOTTOM_PAD);
        return Math.min(contentHeight(), Math.min(MAX_BODY_H, available));
    }

    private int maxBodyScroll() {
        return Math.max(0, contentHeight() - bodyHeight());
    }

    private void clampBodyScroll() {
        bodyScroll = Math.clamp(bodyScroll, 0, maxBodyScroll());
    }

    private int bodyTopY() {
        return y + HEADER - bodyScroll;
    }

    private boolean bodyContains(int mouseX, int mouseY) {
        return bodyHeight() > 0 && Draw.hovered(mouseX, mouseY, x, y + HEADER, width, bodyHeight());
    }

    private boolean hasBodyScroll() {
        return maxBodyScroll() > 0 && scrollbarTrackHeight() > 0;
    }

    private int scrollbarTrackX() {
        return x + width - 2;
    }

    private int scrollbarTrackY() {
        return y + HEADER + 2;
    }

    private int scrollbarTrackHeight() {
        return Math.max(0, bodyHeight() - 4);
    }

    private int scrollbarThumbHeight() {
        return Draw.scrollThumbHeight(scrollbarTrackHeight(), bodyHeight(), contentHeight());
    }

    private int scrollbarThumbY() {
        return scrollbarTrackY() + Draw.scrollThumbOffset(scrollbarTrackHeight(),
                scrollbarThumbHeight(), bodyScroll, maxBodyScroll());
    }

    private boolean scrollbarContains(int mouseX, int mouseY) {
        return hasBodyScroll() && Draw.hovered(mouseX, mouseY, scrollbarTrackX() - 1,
                y + HEADER, 3, bodyHeight());
    }

    private void updateBodyScroll(int mouseY) {
        int travel = scrollbarTrackHeight() - scrollbarThumbHeight();
        if (travel <= 0 || maxBodyScroll() <= 0) {
            return;
        }
        double progress = clamp01((mouseY - scrollbarTrackY() - scrollbarThumbHeight() / 2.0) / travel);
        bodyScroll = (int) Math.round(progress * maxBodyScroll());
        clampBodyScroll();
    }

    private void drawBodyScrollbar(GuiGraphics graphics) {
        if (!hasBodyScroll()) {
            return;
        }
        int trackX = scrollbarTrackX();
        int trackY = scrollbarTrackY();
        Draw.rect(graphics, trackX, trackY, 1, scrollbarTrackHeight(), Theme.SEP);
        Draw.rect(graphics, trackX, scrollbarThumbY(), 1, scrollbarThumbHeight(),
                draggingBodyScroll ? Theme.TEXT : Theme.FROST);
    }

    /** Scissors to the body viewport, clipped by the dock wipe. */
    private boolean beginBodyScissor(GuiGraphics graphics) {
        int h = bodyHeight();
        int left = Math.max(x, effX);
        int right = Math.min(x + width, effX + effW);
        if (h <= 0 || right <= left) {
            return false;
        }
        Draw.scissor(graphics, left, y + HEADER, right - left, h);
        return true;
    }

    /** Includes the color picker, which may extend outside the panel. */
    public boolean contains(int mouseX, int mouseY) {
        if (Draw.hovered(mouseX, mouseY, x, y, width, getHeight())) {
            return true;
        }
        return activeColor != null && Draw.hovered(mouseX, mouseY, cpX, cpY, cpW, cpH);
    }

    /** Settings carry no descriptions yet; the panel only swallows the module tooltip. */
    public String getTooltipAt(int mouseX, int mouseY) {
        return null;
    }

    private int rowHeight(Setting<?> s) {
        if (s instanceof NumberSetting || s instanceof RangeSetting) {
            return NUMBER_H;
        }
        if (s instanceof InventoryPickerSetting.Layout layout) {
            return layoutHeight(picker(layout), layout);
        }
        if (s instanceof InventoryPickerSetting.Cleaner cleaner) {
            return cleanerHeight(picker(cleaner));
        }
        return ROW_H;
    }

    // ---- item picker geometry ----

    private int gridStripWidth() {
        return GRID_COLS * CELL + (GRID_COLS - 1) * COL_GAP;
    }

    private int gridInset() {
        return (width - gridStripWidth()) / 2;
    }

    private int gridViewH() {
        return VIEW_ROWS * CELL + (VIEW_ROWS - 1) * ROW_GAP;
    }

    private int colX(int index) {
        return x + gridInset() + (index % GRID_COLS) * (CELL + COL_GAP);
    }

    private int pickerCatY(int rowY) {
        return rowY + ROW_H + WIDGET_PAD;
    }

    private int pickerSearchY(int rowY) {
        return pickerCatY(rowY) + CELL + WIDGET_PAD;
    }

    private int pickerGridY(int rowY) {
        return pickerSearchY(rowY) + SEARCH_H + WIDGET_PAD;
    }

    private int notReadyHeight() {
        return ROW_H + NOT_READY_H + WIDGET_PAD;
    }

    /** Fallback depth excludes the primary item in each slot. */
    private static int maxFallbacks(InventoryPickerSetting.Layout layout) {
        return Math.max(0, layout.maxDepth() - 1);
    }

    private int stripY(PickerUi ui, int rowY) {
        if (ui.category != null) {
            return pickerGridY(rowY) + gridViewH() + WIDGET_PAD;
        }
        return pickerCatY(rowY) + CELL + HB_SEP;
    }

    private static int stripHeight(InventoryPickerSetting.Layout layout) {
        return CELL + maxFallbacks(layout) * (CELL + ROW_GAP);
    }

    private int layoutHeight(PickerUi ui, InventoryPickerSetting.Layout layout) {
        if (!ui.open) {
            return ROW_H;
        }
        if (!ui.ready) {
            return notReadyHeight();
        }
        return stripY(ui, 0) + stripHeight(layout) + WIDGET_PAD;
    }

    private int cleanerHeight(PickerUi ui) {
        if (!ui.open) {
            return ROW_H;
        }
        if (!ui.ready) {
            return notReadyHeight();
        }
        if (ui.category == null) {
            return pickerCatY(0) + CELL + WIDGET_PAD;
        }
        return pickerGridY(0) + gridViewH() + WIDGET_PAD;
    }

    private static int rowsFor(int count) {
        return Math.max(1, (count + GRID_COLS - 1) / GRID_COLS);
    }

    private int maxRowFor(PickerUi ui) {
        return Math.max(0, rowsFor(ui.filtered().size()) - VIEW_ROWS);
    }

    private int rowOffsetFor(PickerUi ui) {
        return Math.clamp(ui.scrollRow, 0, maxRowFor(ui));
    }

    private PickerUi picker(InventoryPickerSetting<?> setting) {
        return pickers.computeIfAbsent(setting, PickerUi::new);
    }

    private void refreshPickers() {
        Minecraft minecraft = Minecraft.getInstance();
        for (Setting<?> s : module.settings()) {
            if (s instanceof InventoryPickerSetting<?> setting) {
                picker(setting).refresh(minecraft);
            }
        }
    }

    // ---- rendering ----

    public void render(GuiGraphics graphics, int mouseX, int mouseY) {
        renderContent(graphics, mouseX, mouseY);
        renderChrome(graphics);
    }

    /** Content stays at its final position; the dock animation only clips it. */
    public void renderContent(GuiGraphics graphics, int mouseX, int mouseY) {
        cursorCounter++;
        refreshPickers();
        clampBodyScroll();
        int height = getHeight();

        double p = Theme.step(dockAnim, 1.0);
        effW = (int) Math.round(width * p);
        effX = dockRight ? x : x + width - effW;
        boolean clipped = effW < width;
        if (clipped && effW <= 0) {
            return;
        }
        if (clipped) {
            Draw.scissor(graphics, effX, y, effW, height);
        }

        ClickGuiScreen.drawWindowBase(graphics, x, y, width, height);
        ClickGuiScreen.drawWindowHeader(graphics, font, module.name(), x, y, width, mouseX, mouseY);

        if (clipped) {
            Draw.unscissor(graphics);
        }
        if (!beginBodyScissor(graphics)) {
            return;
        }

        List<Setting<?>> settings = module.settings();
        int rowY = bodyTopY();
        for (int i = 0; i < settings.size(); i++) {
            Setting<?> s = settings.get(i);
            if (!visible(s)) {
                continue;
            }
            int groupH = ownerGroupHeight(settings, i);
            if (groupH > 0) {
                Draw.rect(graphics, labelX(s) - 3, rowY, Theme.TICK_PX, groupH, Theme.FROST);
            }
            if (s instanceof BooleanSetting toggle) {
                renderBoolean(graphics, toggle, rowY, mouseX, mouseY);
            } else if (s instanceof ModeSetting mode) {
                renderMode(graphics, mode, rowY, mouseX, mouseY);
            } else if (s instanceof NumberSetting number) {
                renderNumber(graphics, number, rowY);
            } else if (s instanceof RangeSetting range) {
                renderRange(graphics, range, rowY);
            } else if (s instanceof ColorSetting color) {
                renderColor(graphics, color, rowY, mouseX, mouseY);
            } else if (s instanceof InventoryPickerSetting.Layout layout) {
                renderLayout(graphics, layout, rowY, mouseX, mouseY);
            } else if (s instanceof InventoryPickerSetting.Cleaner cleaner) {
                renderCleaner(graphics, cleaner, rowY, mouseX, mouseY);
            }
            rowY += rowHeight(s);
        }
        drawBodyScrollbar(graphics);
        Draw.unscissor(graphics);
    }

    public void renderChrome(GuiGraphics graphics) {
        ClickGuiScreen.drawWindowFrame(graphics, x, y, width, getHeight());
    }

    private int labelX(Setting<?> s) {
        return x + 5 + indent(s) * INDENT_STEP;
    }

    /** Height of an enabled boolean's row plus its children, or 0 when it has none. */
    private int ownerGroupHeight(List<Setting<?>> settings, int i) {
        Setting<?> s = settings.get(i);
        if (!(s instanceof BooleanSetting toggle) || !toggle.get() || !module.isOwnerSetting(s)) {
            return 0;
        }
        int childH = 0;
        for (int j = i + 1; j < settings.size(); j++) {
            Setting<?> c = settings.get(j);
            if (!visible(c)) {
                continue;
            }
            if (module.ownerOf(c) != s) {
                break;
            }
            childH += rowHeight(c);
        }
        return childH > 0 ? rowHeight(s) + childH : 0;
    }

    private void drawLabel(GuiGraphics graphics, Setting<?> s, int rowY, int valueLeft) {
        int lx = labelX(s);
        Draw.text(graphics, font, Draw.trimToWidth(font, s.name(), valueLeft - 4 - lx, ".."),
                lx, Draw.textY(rowY, ROW_H), labelColor(s));
    }

    private int labelColor(Setting<?> s) {
        return indent(s) > 0 ? Theme.TEXT_DIM : Theme.TEXT;
    }

    private void drawSliderLabel(GuiGraphics graphics, Setting<?> s, int rowY, int valueLeft) {
        int lx = labelX(s);
        String name = Draw.trimToWidth(font, s.name(), valueLeft - 4 - lx, "..");
        Draw.text(graphics, font, name, lx, rowY + 3, labelColor(s));
    }

    private void renderBoolean(GuiGraphics graphics, BooleanSetting s, int rowY, int mouseX, int mouseY) {
        int box = 11;
        int bx = x + width - box - 5;
        drawLabel(graphics, s, rowY, bx);
        int by = rowY + (ROW_H - box) / 2;
        // hover matches the click hit zone (the whole row), not just the little box
        boolean hover = Draw.hovered(mouseX, mouseY, x, rowY, width, ROW_H);
        Draw.borderedRect(graphics, bx, by, bx + box, by + box, Theme.WELL,
                hover ? Theme.CONTOUR : Theme.SEP);
        if (s.get()) {
            Draw.rect(graphics, bx + 2, by + 2, box - 4, box - 4, Theme.FROST);
        }
    }

    /** {valueX, leftChevX, rightChevX, chevW} for a mode row. */
    private int[] modeValueGeometry(ModeSetting s) {
        int chevW = font.width(">");
        int valueRight = x + width - 5 - (chevW + CHEV_GAP);
        int valueX = valueRight - font.width(s.get());
        return new int[]{valueX, valueX - CHEV_GAP - chevW, x + width - 5 - chevW, chevW};
    }

    private void renderMode(GuiGraphics graphics, ModeSetting s, int rowY, int mouseX, int mouseY) {
        int[] g = modeValueGeometry(s);
        drawLabel(graphics, s, rowY, g[1]);
        int vy = Draw.textY(rowY, ROW_H);
        Draw.text(graphics, font, s.get(), g[0], vy, Theme.TEXT_DIM);
        int arrowColor = Draw.hovered(mouseX, mouseY, x, rowY, width, ROW_H) ? Theme.TEXT : Theme.TEXT_MUTE;
        Draw.text(graphics, font, "<", g[1], vy, arrowColor);
        Draw.text(graphics, font, ">", g[2], vy, arrowColor);
    }

    private SliderTrack sliderTrack(int rowY) {
        return new SliderTrack(x + TRACK_INSET, rowY + 17, width - TRACK_INSET * 2);
    }

    private void renderNumber(GuiGraphics graphics, NumberSetting s, int rowY) {
        String val = formatValue(s.get()) + unitSuffix(s);
        int vw = font.width(val);
        int valueLeft = x + width - vw - 5;
        Draw.text(graphics, font, val, valueLeft, rowY + 3, Theme.TEXT_DIM);
        drawSliderLabel(graphics, s, rowY, valueLeft);

        sliderTrack(rowY).drawSingle(graphics, s.get(), s.minimum(), s.maximum());
    }

    private void renderRange(GuiGraphics graphics, RangeSetting s, int rowY) {
        String val = s.lower() + RANGE_UNIT + " - " + s.upper() + RANGE_UNIT;
        int vw = font.width(val);
        int valueLeft = x + width - vw - 5;
        Draw.text(graphics, font, val, valueLeft, rowY + 3, Theme.TEXT_DIM);
        drawSliderLabel(graphics, s, rowY, valueLeft);

        sliderTrack(rowY).drawRange(graphics, s.lower(), s.upper(), s.allowedMinimum(), s.allowedMaximum());
    }

    private static double clamp01(double v) {
        return Math.clamp(v, 0.0D, 1.0D);
    }

    private void renderColor(GuiGraphics graphics, ColorSetting s, int rowY, int mouseX, int mouseY) {
        int bx = x + width - CP_SWATCH - 5;
        drawLabel(graphics, s, rowY, bx);
        int by = rowY + (ROW_H - CP_SWATCH) / 2;
        boolean hover = Draw.hovered(mouseX, mouseY, bx, by, CP_SWATCH, CP_SWATCH);
        Draw.borderedRect(graphics, bx, by, bx + CP_SWATCH, by + CP_SWATCH,
                0xFF000000 | (s.get() & 0xFFFFFF), hover ? Theme.CONTOUR : Theme.SEP);
    }

    private void drawWidgetHeader(GuiGraphics graphics, Setting<?> s, int rowY, boolean open) {
        String tag = open ? "[-]" : "[+]";
        int tw = font.width(tag);
        int lx = labelX(s);
        int textY = Draw.textY(rowY, ROW_H);
        Draw.text(graphics, font, Draw.trimToWidth(font, s.name(), (x + width - tw - 5) - 4 - lx, ".."),
                lx, textY, labelColor(s));
        Draw.text(graphics, font, tag, x + width - tw - 5, textY, Theme.TEXT_DIM);
    }

    private static void drawPickerCell(GuiGraphics graphics, int cx, int cy, int fill, int frame) {
        Draw.borderedRect(graphics, cx, cy, cx + CELL, cy + CELL, fill, frame);
    }

    /** Frame colors and overlays that differ between the hotbar and cleaner pickers. */
    private interface PickerStyle {
        int category(InventoryPickerSetting.Category category, boolean hover);

        int entry(Entry entry, boolean hover);

        default void categoryOverlay(GuiGraphics graphics, InventoryPickerSetting.Category category, int cx, int cy) {
        }

        default void entryOverlay(GuiGraphics graphics, Entry entry, int cx, int cy) {
        }
    }

    /** Header, category row, search box and grid shared by both pickers; false when nothing below the header drew. */
    private boolean drawPickerCommon(GuiGraphics graphics, PickerUi ui, int rowY, int mouseX, int mouseY,
                                     PickerStyle style) {
        drawWidgetHeader(graphics, ui.setting, rowY, ui.open);
        if (!ui.open) {
            return false;
        }
        if (!ui.ready) {
            Draw.text(graphics, font, "Join a world to load items", x + 5,
                    Draw.textY(rowY + ROW_H, NOT_READY_H), Theme.TEXT_MUTE);
            return false;
        }
        int catY = pickerCatY(rowY);
        InventoryPickerSetting.Category[] categories = InventoryPickerSetting.Category.values();
        for (int i = 0; i < categories.length; i++) {
            int cx = colX(i);
            boolean open = categories[i] == ui.category;
            boolean hover = Draw.hovered(mouseX, mouseY, cx, catY, CELL, CELL);
            int frame = open ? Theme.FROST : style.category(categories[i], hover);
            drawPickerCell(graphics, cx, catY, open ? Theme.WELL : Theme.BODY, frame);
            graphics.renderItem(ui.setting.catalog().icon(categories[i]), cx + 1, catY + 1);
            style.categoryOverlay(graphics, categories[i], cx, catY);
        }
        if (ui.category == null) {
            return true;
        }
        TextField.draw(graphics, font, colX(0), pickerSearchY(rowY), gridStripWidth(), SEARCH_H,
                ui.search, searchingPicker == ui, "Search items...", cursorCounter);
        for (VisibleCell cell : visibleCells(ui, rowY)) {
            boolean hover = Draw.hovered(mouseX, mouseY, cell.x(), cell.y(), CELL, CELL);
            drawPickerCell(graphics, cell.x(), cell.y(), Theme.BODY, style.entry(cell.entry(), hover));
            graphics.renderItem(cell.entry().stack(), cell.x() + 1, cell.y() + 1);
            style.entryOverlay(graphics, cell.entry(), cell.x(), cell.y());
        }
        return true;
    }

    private static boolean isCategoryAssigned(InventoryPickerSetting.Layout layout,
                                              InventoryPickerSetting.Category category) {
        for (List<Preference> slot : layout.slots()) {
            for (Preference preference : slot) {
                if (preference instanceof CategoryPreference assigned && assigned.category() == category) {
                    return true;
                }
            }
        }
        return false;
    }

    private void renderLayout(GuiGraphics graphics, InventoryPickerSetting.Layout layout, int rowY,
                              int mouseX, int mouseY) {
        PickerUi ui = picker(layout);
        boolean drawn = drawPickerCommon(graphics, ui, rowY, mouseX, mouseY, new PickerStyle() {
            @Override
            public int category(InventoryPickerSetting.Category category, boolean hover) {
                return isCategoryAssigned(layout, category) ? Theme.FROST : (hover ? Theme.CONTOUR : Theme.SEP);
            }

            @Override
            public int entry(Entry entry, boolean hover) {
                return layout.isExcluded(entry.ref()) ? EXCLUDED_CLR : (hover ? Theme.CONTOUR : Theme.SEP);
            }

            @Override
            public void entryOverlay(GuiGraphics g, Entry entry, int cx, int cy) {
                if (layout.isExcluded(entry.ref())) {
                    Draw.rect(g, cx + 1, cy + 1, CELL - 2, CELL - 2, EXCLUDE_OVERLAY);
                }
            }
        });
        if (!drawn) {
            return;
        }
        // hotbar strip: each slot is a vertical stack (primary on top, fallbacks below)
        int stripY = stripY(ui, rowY);
        for (int i = 0; i < InventoryPickerSetting.Layout.SLOT_COUNT; i++) {
            int cx = colX(i);
            List<Preference> list = layout.slot(i);
            if (list.isEmpty()) {
                drawPickerCell(graphics, cx, stripY, Theme.BODY, Theme.SEP);
                Draw.textCentered(graphics, font, "+", cx, stripY, CELL, CELL, Theme.TEXT_DIM);
                continue;
            }
            for (int k = 0; k < list.size(); k++) {
                int cy = stripY + k * (CELL + ROW_GAP);
                Preference preference = list.get(k);
                boolean isCat = preference instanceof CategoryPreference;
                drawPickerCell(graphics, cx, cy, k == 0 ? Theme.WELL : Theme.BODY,
                        (isCat || k == 0) ? Theme.FROST : Theme.SEP);
                ItemStack stack = preferenceStack(layout, preference);
                if (!stack.isEmpty()) {
                    graphics.renderItem(stack, cx + 1, cy + 1);
                }
                if (isCat) {
                    Draw.rect(graphics, cx + CELL - 4, cy + 1, 3, 3, CAT_CLR);
                }
            }
        }
        if (draggingPicker == ui) {
            int target = slotColAt(ui, layout, rowY, dragMouseX, dragMouseY);
            if (target >= 0) {
                Draw.outline(graphics, colX(target), stripY, colX(target) + CELL,
                        stripY + stripHeight(layout), 1, Theme.TEXT);
            }
        }
    }

    private void renderCleaner(GuiGraphics graphics, InventoryPickerSetting.Cleaner cleaner, int rowY,
                               int mouseX, int mouseY) {
        PickerUi ui = picker(cleaner);
        drawPickerCommon(graphics, ui, rowY, mouseX, mouseY, new PickerStyle() {
            @Override
            public int category(InventoryPickerSetting.Category category, boolean hover) {
                CleanerMode mode = cleaner.categoryMode(category);
                return mode != CleanerMode.UNSET ? modeColor(mode) : (hover ? Theme.CONTOUR : Theme.SEP);
            }

            @Override
            public int entry(Entry entry, boolean hover) {
                CleanerMode mode = cleaner.itemMode(entry.ref());
                return mode != CleanerMode.UNSET ? modeColor(mode) : (hover ? Theme.CONTOUR : Theme.SEP);
            }

            @Override
            public void categoryOverlay(GuiGraphics g, InventoryPickerSetting.Category category, int cx, int cy) {
                CleanerMode mode = cleaner.categoryMode(category);
                if (mode != CleanerMode.UNSET) {
                    drawModeBadge(g, mode, cx, cy);
                }
            }

            @Override
            public void entryOverlay(GuiGraphics g, Entry entry, int cx, int cy) {
                CleanerMode mode = cleaner.itemMode(entry.ref());
                if (mode != CleanerMode.UNSET) {
                    drawModeBadge(g, mode, cx, cy);
                }
            }
        });
    }

    private static int modeColor(CleanerMode mode) {
        return switch (mode) {
            case DROP -> EXCLUDED_CLR;
            case KEEP_ONE -> CLEAN_KEEP_CLR;
            case IGNORE -> Theme.TEXT_DIM;
            case UNSET -> Theme.SEP;
        };
    }

    private static String modeLetter(CleanerMode mode) {
        return switch (mode) {
            case DROP -> "D";
            case KEEP_ONE -> "K";
            case IGNORE -> "I";
            case UNSET -> "";
        };
    }

    private void drawModeBadge(GuiGraphics graphics, CleanerMode mode, int cx, int cy) {
        String letter = modeLetter(mode);
        if (letter.isEmpty()) {
            return;
        }
        int tx = cx + CELL - font.width(letter) - 1;
        int ty = cy + 1;
        Draw.text(graphics, font, letter, tx + 1, ty + 1, 0xFF000000);
        Draw.text(graphics, font, letter, tx, ty, modeColor(mode));
    }

    private ItemStack preferenceStack(InventoryPickerSetting<?> setting, Preference preference) {
        if (preference instanceof CategoryPreference category) {
            return setting.catalog().icon(category.category());
        }
        return ((ItemPreference) preference).item().stack();
    }

    private record VisibleCell(Entry entry, int x, int y) {
    }

    private List<VisibleCell> visibleCells(PickerUi ui, int rowY) {
        if (ui.category == null) {
            return List.of();
        }
        List<Entry> filtered = ui.filtered();
        int firstIndex = rowOffsetFor(ui) * GRID_COLS;
        int visibleCount = Math.clamp(filtered.size() - firstIndex, 0, VIEW_ROWS * GRID_COLS);
        List<VisibleCell> cells = new ArrayList<>(visibleCount);
        int gridY = pickerGridY(rowY);
        for (int visibleIndex = 0; visibleIndex < visibleCount; visibleIndex++) {
            int row = visibleIndex / GRID_COLS;
            int column = visibleIndex % GRID_COLS;
            cells.add(new VisibleCell(filtered.get(firstIndex + visibleIndex),
                    colX(column), gridY + row * (CELL + ROW_GAP)));
        }
        return cells;
    }

    public void renderDragGhost(GuiGraphics graphics) {
        if (draggingPicker != null && !draggingStack.isEmpty()) {
            graphics.renderItem(draggingStack, dragMouseX - 8, dragMouseY - 8);
        }
    }

    // ---- color picker ----

    /** True if an overlay was open and got closed. */
    public boolean closeOverlay() {
        if (activeColor != null) {
            activeColor = null;
            return true;
        }
        return false;
    }

    private void openColorPicker(ColorSetting cs, int anchorX, int anchorY) {
        activeColor = cs;
        int rgb = cs.get();
        float[] hsb = Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, null);
        curH = hsb[0];
        curS = hsb[1];
        curB = hsb[2];
        int titleH = Draw.TEXT_H + 3;
        int readoutH = Draw.TEXT_H + 2;
        cpW = CP_PAD * 2 + SB_SIZE + CP_GAP + HUE_W;
        cpH = CP_PAD * 2 + titleH + SB_SIZE + 3 + readoutH;
        cpX = Math.clamp(anchorX, 0, Math.max(0, screen.width - cpW));
        cpY = Math.clamp(anchorY, 0, Math.max(0, screen.height - cpH));
        cpSbX = cpX + CP_PAD;
        cpSbY = cpY + CP_PAD + titleH;
        cpHueX = cpSbX + SB_SIZE + CP_GAP;
    }

    /** The gradients are tiled from flat rects; there is no gradient primitive. */
    public void renderColorPicker(GuiGraphics graphics, int mouseX, int mouseY) {
        if (activeColor == null) {
            return;
        }
        Draw.borderedRect(graphics, cpX, cpY, cpX + cpW, cpY + cpH, Theme.BODY,
                Theme.CONTOUR, Theme.CONTOUR_PX);
        Draw.text(graphics, font, activeColor.name(), cpX + CP_PAD, cpY + CP_PAD, Theme.TEXT_DIM);
        // saturation (x) / brightness (y) square at the current hue
        for (int dx = 0; dx < SB_SIZE; dx += CP_STEP) {
            int cw = Math.min(CP_STEP, SB_SIZE - dx);
            float s = dx / (float) SB_SIZE;
            for (int dy = 0; dy < SB_SIZE; dy += CP_STEP) {
                int ch = Math.min(CP_STEP, SB_SIZE - dy);
                float b = 1f - dy / (float) SB_SIZE;
                Draw.rect(graphics, cpSbX + dx, cpSbY + dy, cw, ch, 0xFF000000 | (Color.HSBtoRGB(curH, s, b) & 0xFFFFFF));
            }
        }
        Draw.outline(graphics, cpSbX, cpSbY, cpSbX + SB_SIZE, cpSbY + SB_SIZE, 1, Theme.CONTOUR);
        for (int dy = 0; dy < SB_SIZE; dy += HUE_STEP) {
            int ch = Math.min(HUE_STEP, SB_SIZE - dy);
            float h = dy / (float) SB_SIZE;
            Draw.rect(graphics, cpHueX, cpSbY + dy, HUE_W, ch, 0xFF000000 | (Color.HSBtoRGB(h, 1f, 1f) & 0xFFFFFF));
        }
        Draw.outline(graphics, cpHueX, cpSbY, cpHueX + HUE_W, cpSbY + SB_SIZE, 1, Theme.CONTOUR);
        int selX = cpSbX + Math.round(curS * SB_SIZE);
        int selY = cpSbY + Math.round((1f - curB) * SB_SIZE);
        Draw.outline(graphics, selX - 3, selY - 3, selX + 4, selY + 4, 1, 0xFF000000);
        Draw.outline(graphics, selX - 2, selY - 2, selX + 3, selY + 3, 1, Theme.TEXT);
        int hueSelY = cpSbY + Math.round(curH * SB_SIZE);
        Draw.rectBounds(graphics, cpHueX - 1, hueSelY - 1, cpHueX + HUE_W + 1, hueSelY + 1, Theme.TEXT);
        int rgb = activeColor.get();
        String txt = "R" + ((rgb >> 16) & 0xFF) + " G" + ((rgb >> 8) & 0xFF) + " B" + (rgb & 0xFF);
        Draw.text(graphics, font, txt, cpSbX, cpSbY + SB_SIZE + 3, Theme.TEXT_DIM);
    }

    private void updateSB(int mouseX, int mouseY) {
        curS = Math.clamp((mouseX - cpSbX) / (float) SB_SIZE, 0f, 1f);
        curB = Math.clamp(1f - (mouseY - cpSbY) / (float) SB_SIZE, 0f, 1f);
        applyPickerColor();
    }

    private void updateHue(int mouseY) {
        curH = Math.clamp((mouseY - cpSbY) / (float) SB_SIZE, 0f, 1f);
        applyPickerColor();
    }

    private void applyPickerColor() {
        if (activeColor != null) {
            activeColor.set(Color.HSBtoRGB(curH, curS, curB));
        }
    }

    // ---- input ----

    public boolean mouseClicked(int mouseX, int mouseY, int button) {
        // the picker can extend outside the panel, so test it before the bounds check
        if (activeColor != null) {
            if (Draw.hovered(mouseX, mouseY, cpX, cpY, cpW, cpH)) {
                if (button == 0) {
                    if (Draw.hovered(mouseX, mouseY, cpSbX, cpSbY, SB_SIZE, SB_SIZE)) {
                        draggingSB = true;
                        updateSB(mouseX, mouseY);
                    } else if (Draw.hovered(mouseX, mouseY, cpHueX, cpSbY, HUE_W, SB_SIZE)) {
                        draggingHue = true;
                        updateHue(mouseY);
                    }
                }
                return true;
            }
            activeColor = null;
            return true;
        }

        refreshPickers();
        clampBodyScroll();
        int height = getHeight();
        if (!Draw.hovered(mouseX, mouseY, x, y, width, height)) {
            return false;
        }
        if (button == 0 && ClickGuiScreen.hitsClose(x, y, width, HEADER, mouseX, mouseY)) {
            screen.closeSettings();
            return true;
        }
        if (Draw.hovered(mouseX, mouseY, x, y, width, HEADER)) {
            if (button == 0) {
                if (dockHost != null) {
                    detach();
                }
                windowDrag.begin(mouseX, mouseY, x, y);
            }
            return true;
        }
        if (button == 0 && scrollbarContains(mouseX, mouseY)) {
            draggingBodyScroll = true;
            updateBodyScroll(mouseY);
            return true;
        }
        if (!bodyContains(mouseX, mouseY)) {
            return true;
        }
        searchingPicker = null;
        int rowY = bodyTopY();
        for (Setting<?> s : module.settings()) {
            if (!visible(s)) {
                continue;
            }
            int rh = rowHeight(s);
            if (Draw.hovered(mouseX, mouseY, x, rowY, width, rh)) {
                handleSettingClick(s, button, mouseX, mouseY, rowY);
                return true;
            }
            rowY += rh;
        }
        return true;
    }

    private void handleSettingClick(Setting<?> s, int button, int mouseX, int mouseY, int rowY) {
        if (s instanceof BooleanSetting toggle) {
            if (button == 0) {
                toggle.toggle();
                save();
            }
        } else if (s instanceof ModeSetting mode) {
            if (button == 0) {
                // left-click on the "<" chevron cycles back; anywhere else in the row cycles forward
                int[] g = modeValueGeometry(mode);
                boolean backChev = Draw.hovered(mouseX, mouseY, g[1] - 2, rowY, g[3] + 4, ROW_H);
                mode.next(backChev ? -1 : 1);
                save();
            } else if (button == 1) {
                mode.next(-1);
                save();
            }
        } else if (s instanceof NumberSetting number) {
            if (button == 0) {
                draggingSlider = number;
                updateSlider(mouseX);
            }
        } else if (s instanceof RangeSetting range) {
            if (button == 0) {
                draggingRange = range;
                draggingRangeHandle = nearestHandle(range, mouseX);
                updateRangeSlider(mouseX);
            }
        } else if (s instanceof ColorSetting color) {
            if (button == 0) {
                openColorPicker(color, x + width + 2, rowY);
            }
        } else if (s instanceof InventoryPickerSetting.Layout layout) {
            handleLayoutClick(layout, button, mouseX, mouseY, rowY);
        } else if (s instanceof InventoryPickerSetting.Cleaner cleaner) {
            handleCleanerClick(cleaner, button, mouseX, mouseY, rowY);
        }
    }

    private interface PickerActions {
        void category(InventoryPickerSetting.Category category, int button);

        void entry(Entry entry, int button);
    }

    /** False when the click landed outside the shared picker areas. */
    private boolean handlePickerCommon(PickerUi ui, int button, int mouseX, int mouseY, int rowY,
                                       PickerActions actions) {
        if (Draw.hovered(mouseX, mouseY, x, rowY, width, ROW_H)) {
            ui.open = !ui.open;
            ui.refresh(Minecraft.getInstance());
            return true;
        }
        if (!ui.open || !ui.ready) {
            return true;
        }
        int category = categoryAt(rowY, mouseX, mouseY);
        if (category >= 0) {
            actions.category(InventoryPickerSetting.Category.values()[category], button);
            return true;
        }
        if (searchContains(ui, rowY, mouseX, mouseY)) {
            searchingPicker = ui;
            cursorCounter = 0;
            return true;
        }
        Entry entry = gridEntryAt(ui, rowY, mouseX, mouseY);
        if (entry != null) {
            actions.entry(entry, button);
            return true;
        }
        return false;
    }

    private void handleLayoutClick(InventoryPickerSetting.Layout layout, int button,
                                   int mouseX, int mouseY, int rowY) {
        PickerUi ui = picker(layout);
        boolean handled = handlePickerCommon(ui, button, mouseX, mouseY, rowY, new PickerActions() {
            @Override
            public void category(InventoryPickerSetting.Category category, int clickedButton) {
                if (clickedButton == 0) {
                    beginDrag(ui, new CategoryPreference(category), mouseX, mouseY);
                } else {
                    toggleCategory(ui, category);
                }
            }

            @Override
            public void entry(Entry entry, int clickedButton) {
                if (clickedButton == 0) {
                    beginDrag(ui, new ItemPreference(entry.ref()), mouseX, mouseY);
                } else {
                    layout.toggle(entry.ref());
                    save();
                }
            }
        });
        if (handled) {
            return;
        }
        int[] slotItem = slotItemAt(ui, layout, rowY, mouseX, mouseY);
        if (slotItem != null && button == 1) {
            layout.remove(slotItem[0], slotItem[1]);
            save();
        }
    }

    private void handleCleanerClick(InventoryPickerSetting.Cleaner cleaner, int button,
                                    int mouseX, int mouseY, int rowY) {
        PickerUi ui = picker(cleaner);
        handlePickerCommon(ui, button, mouseX, mouseY, rowY, new PickerActions() {
            @Override
            public void category(InventoryPickerSetting.Category category, int clickedButton) {
                if (clickedButton == 1) {
                    toggleCategory(ui, category);
                } else {
                    cleaner.cycle(category, 1);
                    save();
                }
            }

            @Override
            public void entry(Entry entry, int clickedButton) {
                cleaner.cycle(entry.ref(), clickedButton == 0 ? 1 : -1);
                save();
            }
        });
    }

    private void toggleCategory(PickerUi ui, InventoryPickerSetting.Category category) {
        ui.category = ui.category == category ? null : category;
        ui.scrollRow = 0;
        if (searchingPicker == ui) {
            searchingPicker = null;
        }
    }

    private void beginDrag(PickerUi ui, Preference preference, int mouseX, int mouseY) {
        draggingPicker = ui;
        draggingPreference = preference;
        draggingStack = preferenceStack(ui.setting, preference);
        dragMouseX = mouseX;
        dragMouseY = mouseY;
    }

    public void drag(int mouseX, int mouseY, int screenWidth, int screenHeight) {
        if (draggingSB) {
            updateSB(mouseX, mouseY);
            return;
        }
        if (draggingHue) {
            updateHue(mouseY);
            return;
        }
        if (draggingPicker != null) {
            dragMouseX = mouseX;
            dragMouseY = mouseY;
            return;
        }
        if (draggingBodyScroll) {
            updateBodyScroll(mouseY);
            return;
        }
        int[] pos = windowDrag.update(mouseX, mouseY, width, getHeight(), screenWidth, screenHeight);
        if (pos != null) {
            x = pos[0];
            y = pos[1];
            clampBodyScroll();
        }
        if (draggingSlider != null) {
            updateSlider(mouseX);
        }
        if (draggingRange != null) {
            updateRangeSlider(mouseX);
        }
    }

    private void updateSlider(int mouseX) {
        draggingSlider.set(sliderTrack(0).valueAt(mouseX, draggingSlider.minimum(), draggingSlider.maximum()));
    }

    private int nearestHandle(RangeSetting range, int mouseX) {
        return sliderTrack(0).nearestHandle(range.lower(), range.upper(),
                range.allowedMinimum(), range.allowedMaximum(), mouseX);
    }

    private void updateRangeSlider(int mouseX) {
        int value = (int) Math.round(sliderTrack(0).valueAt(mouseX,
                draggingRange.allowedMinimum(), draggingRange.allowedMaximum()));
        // the setter clamps against the other handle so they cannot cross
        if (draggingRangeHandle == 0) {
            draggingRange.setLower(value);
        } else {
            draggingRange.setUpper(value);
        }
    }

    public void mouseReleased() {
        windowDrag.end();
        draggingBodyScroll = false;
        if (draggingSB || draggingHue) {
            draggingSB = false;
            draggingHue = false;
            save();
        }
        if (draggingSlider != null) {
            draggingSlider = null;
            save();
        }
        if (draggingRange != null) {
            draggingRange = null;
            save();
        }
        if (draggingPicker != null && draggingPreference != null) {
            finishDrag();
            draggingPicker = null;
            draggingPreference = null;
            draggingStack = ItemStack.EMPTY;
        }
    }

    /** Dropping on a hotbar column binds the preference; dropping back on the source cell acts as a click. */
    private void finishDrag() {
        PickerUi ui = draggingPicker;
        Preference preference = draggingPreference;
        if (!(ui.setting instanceof InventoryPickerSetting.Layout layout)) {
            return;
        }
        int rowY = rowYOf(ui.setting);
        if (rowY < 0) {
            return;
        }
        int col = slotColAt(ui, layout, rowY, dragMouseX, dragMouseY);
        if (col != -1) {
            layout.assign(col, preference);
            save();
        } else if (preference instanceof CategoryPreference category) {
            int cat = categoryAt(rowY, dragMouseX, dragMouseY);
            if (cat != -1 && InventoryPickerSetting.Category.values()[cat] == category.category()) {
                toggleCategory(ui, category.category());
            }
        } else if (preference instanceof ItemPreference item) {
            Entry entry = gridEntryAt(ui, rowY, dragMouseX, dragMouseY);
            if (entry != null && entry.ref().equals(item.item())) {
                layout.toggle(item.item());
                save();
            }
        }
    }

    private int rowYOf(Setting<?> target) {
        int rowY = bodyTopY();
        for (Setting<?> s : module.settings()) {
            if (!visible(s)) {
                continue;
            }
            if (s == target) {
                return rowY;
            }
            rowY += rowHeight(s);
        }
        return -1;
    }

    private int categoryAt(int rowY, int mouseX, int mouseY) {
        int catY = pickerCatY(rowY);
        int count = InventoryPickerSetting.Category.values().length;
        for (int i = 0; i < count; i++) {
            if (Draw.hovered(mouseX, mouseY, colX(i), catY, CELL, CELL)) {
                return i;
            }
        }
        return -1;
    }

    private boolean searchContains(PickerUi ui, int rowY, int mouseX, int mouseY) {
        return ui.category != null
                && Draw.hovered(mouseX, mouseY, colX(0), pickerSearchY(rowY), gridStripWidth(), SEARCH_H);
    }

    private boolean gridContains(PickerUi ui, int rowY, int mouseX, int mouseY) {
        return ui.category != null
                && Draw.hovered(mouseX, mouseY, colX(0), pickerGridY(rowY), gridStripWidth(), gridViewH());
    }

    private Entry gridEntryAt(PickerUi ui, int rowY, int mouseX, int mouseY) {
        for (VisibleCell cell : visibleCells(ui, rowY)) {
            if (Draw.hovered(mouseX, mouseY, cell.x(), cell.y(), CELL, CELL)) {
                return cell.entry();
            }
        }
        return null;
    }

    private int slotColAt(PickerUi ui, InventoryPickerSetting.Layout layout, int rowY, int mouseX, int mouseY) {
        int stripY = stripY(ui, rowY);
        int stripH = stripHeight(layout);
        for (int i = 0; i < InventoryPickerSetting.Layout.SLOT_COUNT; i++) {
            if (Draw.hoveredExclusive(mouseX, mouseY, colX(i), stripY, CELL, stripH)) {
                return i;
            }
        }
        return -1;
    }

    /** {slot, itemIndex} under the cursor, or null. */
    private int[] slotItemAt(PickerUi ui, InventoryPickerSetting.Layout layout, int rowY, int mouseX, int mouseY) {
        int col = slotColAt(ui, layout, rowY, mouseX, mouseY);
        if (col < 0) {
            return null;
        }
        int stripY = stripY(ui, rowY);
        int k = (mouseY - stripY) / (CELL + ROW_GAP);
        if (k < 0 || k >= layout.slot(col).size()) {
            return null;
        }
        return new int[]{col, k};
    }

    public boolean isSearching() {
        return searchingPicker != null;
    }

    public void keyPressed(KeyEvent event) {
        if (searchingPicker != null) {
            applySearchEdit(TextField.keyPressed(searchingPicker.search, event, -1));
        }
    }

    public void charTyped(CharacterEvent event) {
        if (searchingPicker != null) {
            applySearchEdit(TextField.charTyped(searchingPicker.search, event, -1));
        }
    }

    private void applySearchEdit(TextField.EditResult edit) {
        if (!edit.focused()) {
            searchingPicker = null;
        } else if (edit.changed()) {
            searchingPicker.search = edit.value();
            searchingPicker.scrollRow = 0;
        }
    }

    /** {@code wheel} is positive for scrolling up. */
    public void scroll(int wheel, int mouseX, int mouseY) {
        clampBodyScroll();
        if (!bodyContains(mouseX, mouseY)) {
            return;
        }
        int rowY = bodyTopY();
        for (Setting<?> s : module.settings()) {
            if (!visible(s)) {
                continue;
            }
            int rh = rowHeight(s);
            if (s instanceof InventoryPickerSetting<?> setting) {
                PickerUi ui = picker(setting);
                if (ui.open && ui.ready && gridContains(ui, rowY, mouseX, mouseY)) {
                    ui.scrollRow = Math.clamp(ui.scrollRow + (wheel > 0 ? -1 : 1), 0, maxRowFor(ui));
                    return;
                }
            }
            rowY += rh;
        }
        if (maxBodyScroll() > 0) {
            int delta = wheel > 0 ? -BODY_SCROLL_STEP : BODY_SCROLL_STEP;
            bodyScroll = Math.clamp(bodyScroll + delta, 0, maxBodyScroll());
        }
    }

    private void save() {
        ClientCore.get().save();
    }

    static String formatValue(double v) {
        if (v == Math.floor(v)) {
            return String.valueOf((long) v);
        }
        return String.valueOf(Math.round(v * 100.0) / 100.0);
    }

    /** Widest value text; a value one increment inside the bounds can be wider than either endpoint. */
    static String worstValueText(double min, double max, double increment) {
        String worst = "";
        for (double v : new double[]{min, max, min + increment, max - increment}) {
            String text = formatValue(v);
            if (text.length() > worst.length()) {
                worst = text;
            }
        }
        return worst;
    }

    /** Transient editor state for one item picker setting. */
    private static final class PickerUi {
        final InventoryPickerSetting<?> setting;
        boolean open;
        boolean ready;
        InventoryPickerSetting.Category category; // null while no category grid is open
        String search = "";
        int scrollRow;

        PickerUi(InventoryPickerSetting<?> setting) {
            this.setting = setting;
        }

        void refresh(Minecraft minecraft) {
            if (!open) {
                ready = false;
                return;
            }
            ready = setting.catalog().ensure(minecraft);
            if (ready) {
                setting.resolve(minecraft.level.registryAccess());
            }
        }

        List<Entry> filtered() {
            return category == null ? List.of() : setting.catalog().filtered(category, search);
        }
    }
}
