package cn.timer.coldplay.client.gui.click;

import cn.timer.coldplay.client.ClientCore;
import cn.timer.coldplay.client.gui.Draw;
import cn.timer.coldplay.client.gui.TextField;
import cn.timer.coldplay.client.gui.Theme;
import cn.timer.coldplay.client.gui.hud.HudEditScreen;
import cn.timer.coldplay.client.hud.HudState;
import cn.timer.coldplay.client.module.Category;
import cn.timer.coldplay.client.module.Module;
import cn.timer.coldplay.client.module.ModuleManager;
import cn.timer.coldplay.client.module.impl.visuals.ClickGuiModule;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Click GUI: one draggable panel per category, a docked settings window, search and the HUD editor entry. */
public final class ClickGuiScreen extends Screen {

    public static final int HEADER_HEIGHT = 17;
    public static final int ROW_HEIGHT = 16;
    public static final int CLOSE_W = 13;
    private static final int MARGIN = 6;
    private static final int SETTINGS_BTN_WIDTH = 70;
    private static final int SEARCH_BOX_WIDTH = 90;
    private static final String[] FLYOUT_ROWS = {"Edit GUI"}; // bottom-up

    private static final int TOOLTIP_PAD = 5;
    private static final int TOOLTIP_MAX_WIDTH = 160;
    private static final int TOOLTIP_LINE_GAP = 1;

    private final ModuleManager modules;
    private final HudState hudState;
    private final EnumMap<Category, PanelState> panelStates = new EnumMap<>(Category.class);
    private int layoutWidth; // 0 until the first layout or config load
    private int layoutHeight;

    private final List<CategoryPanel> panels = new ArrayList<>();
    private SettingsPanel settingsPanel;
    private Module listeningModule;
    private boolean settingsFlyoutOpen;

    private String search = "";
    private boolean searchFocused;
    private int searchCursorCounter;

    private Screen parent;
    private boolean keybindDrainRequested;

    public ClickGuiScreen(ModuleManager modules, HudState hudState) {
        super(Component.literal("ColdPlay"));
        this.modules = modules;
        this.hudState = hudState;
    }

    public void setParent(Screen parent) {
        this.parent = parent;
    }

    /** True while a module row waits for its new key. */
    public boolean isCapturingKey() {
        return listeningModule != null;
    }

    /** Set after a key press the game should not also treat as a module toggle; cleared once read. */
    public boolean consumeKeybindDrain() {
        boolean drain = keybindDrainRequested;
        keybindDrainRequested = false;
        return drain;
    }

    public void resetTransientState() {
        settingsPanel = null;
        listeningModule = null;
    }

    // ---- persisted layout ----

    public record PanelState(int x, int y, boolean expanded) {
    }

    public Map<Category, PanelState> panelStates() {
        return new EnumMap<>(panelStates);
    }

    public void setPanelState(Category category, int x, int y, boolean expanded) {
        panelStates.put(category, new PanelState(x, y, expanded));
    }

    void storePanelState(Category category, int x, int y, boolean collapsed) {
        panelStates.put(category, new PanelState(x, y, !collapsed));
    }

    public int layoutWidth() {
        return layoutWidth;
    }

    public int layoutHeight() {
        return layoutHeight;
    }

    public void setLayoutSize(int width, int height) {
        layoutWidth = width;
        layoutHeight = height;
    }

    /** Re-expresses saved panel anchors when the GUI size changes between sessions. */
    private void reflowPanels(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        if (layoutWidth > 0 && layoutHeight > 0 && (layoutWidth != width || layoutHeight != height)) {
            for (Map.Entry<Category, PanelState> entry : panelStates.entrySet()) {
                PanelState state = entry.getValue();
                entry.setValue(new PanelState(HudState.reanchor(state.x(), layoutWidth, width),
                        HudState.reanchor(state.y(), layoutHeight, height), state.expanded()));
            }
        }
        layoutWidth = width;
        layoutHeight = height;
    }

    // ---- lifecycle ----

    @Override
    protected void init() {
        panels.clear();
        settingsPanel = null;
        listeningModule = null;
        settingsFlyoutOpen = false;
        search = "";
        searchFocused = false;

        reflowPanels(this.width, this.height);
        int cursorX = MARGIN;
        for (Category category : Category.values()) {
            CategoryPanel panel = new CategoryPanel(this, category, cursorX, MARGIN, font);
            PanelState state = panelStates.get(category);
            if (state != null) {
                panel.restoreState(state.x(), state.y(), !state.expanded());
            }
            panel.clampToScreen(this.width, this.height);
            panels.add(panel);
            cursorX += panel.getWidth() + MARGIN;
        }
    }

    /** A flat dim instead of the vanilla blur; the screen renderer calls this before {@link #render}. */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        Draw.rectBounds(graphics, 0, 0, this.width, this.height, Theme.DIM_SCREEN);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        updateActiveDrags(mouseX, mouseY);
        // the docked pair renders last, on top
        CategoryPanel dockHost = settingsPanel != null ? settingsPanel.getDockHost() : null;
        for (CategoryPanel panel : panels) {
            if (panel != dockHost) {
                panel.render(graphics, mouseX, mouseY, listeningModule);
            }
        }
        if (settingsPanel != null) {
            if (dockHost != null) {
                renderDockedPair(graphics, dockHost, mouseX, mouseY);
            } else {
                settingsPanel.render(graphics, mouseX, mouseY);
            }
            // overlays go above the cell fills
            settingsPanel.renderDragGhost(graphics);
            settingsPanel.renderColorPicker(graphics, mouseX, mouseY);
        }
        drawSettingsButton(graphics, mouseX, mouseY);
        drawSearchBox(graphics);
        String tip = resolveTooltip(mouseX, mouseY);
        if (tip != null && !tip.isEmpty()) {
            drawTooltip(graphics, tip, mouseX, mouseY);
        }
    }

    // hosts move before their docked settings panel
    private void updateActiveDrags(int mouseX, int mouseY) {
        for (CategoryPanel panel : panels) {
            panel.drag(mouseX, mouseY, this.width, this.height);
        }
        if (settingsPanel != null) {
            settingsPanel.drag(mouseX, mouseY, this.width, this.height);
            if (!settingsPanel.layoutDocked(this.width, this.height)) {
                settingsPanel = null;
            }
        }
    }

    private void renderDockedPair(GuiGraphics graphics, CategoryPanel host, int mouseX, int mouseY) {
        host.renderContent(graphics, mouseX, mouseY, listeningModule);
        settingsPanel.renderContent(graphics, mouseX, mouseY);
        if (!settingsPanel.isFlush()) {
            host.renderChrome(graphics);
            settingsPanel.renderChrome(graphics);
            return;
        }
        drawHeaderRule(graphics, host.getX(), host.getY(), host.getWidth());
        if (settingsPanel.getEffectiveWidth() > 0) {
            drawHeaderRule(graphics, settingsPanel.getEffectiveX(), settingsPanel.getY(),
                    settingsPanel.getEffectiveWidth());
        }
        drawDockedContour(graphics, host.getX(), host.getY(), host.getWidth(), host.getVisualHeight(),
                settingsPanel.getEffectiveX(), settingsPanel.getY(),
                settingsPanel.getEffectiveWidth(), settingsPanel.getHeight(),
                settingsPanel.isDockRight(), Theme.CONTOUR);
        if (settingsPanel.getEffectiveWidth() > 0) {
            int seam = settingsPanel.isDockRight() ? host.getX() + host.getWidth() : host.getX();
            Draw.rect(graphics, seam - 1, settingsPanel.getY(), Theme.TICK_PX, ROW_HEIGHT, Theme.FROST);
        }
    }

    // Outlines the docked pair as one shape; the shared seam is never drawn.
    private static void drawDockedContour(GuiGraphics g, int cx, int cy, int cw, int ch,
                                          int sx, int sy, int sw, int sh,
                                          boolean rightDock, int color) {
        int cRight = cx + cw;
        int cBot = cy + ch;
        if (sw <= 0) {
            Draw.outline(g, cx, cy, cRight, cBot, 1, color);
            return;
        }
        int sRight = sx + sw;
        int sBot = sy + sh;

        Draw.hLine(g, cx, cRight, cy, color);
        if (rightDock) {
            Draw.vLine(g, cx, cy, cBot, color);
            Draw.vLine(g, cRight - 1, cy, sy, color);
            Draw.hLine(g, cRight - 1, sRight, sy, color);
            Draw.vLine(g, sRight - 1, sy, sBot, color);
            if (sBot < cBot) {
                Draw.hLine(g, cRight - 1, sRight, sBot - 1, color);
                Draw.vLine(g, cRight - 1, sBot, cBot, color);
                Draw.hLine(g, cx, cRight, cBot - 1, color);
            } else if (sBot > cBot) {
                Draw.hLine(g, cx, cRight, cBot - 1, color);
                Draw.vLine(g, sx, cBot - 1, sBot, color);
                Draw.hLine(g, sx, sRight, sBot - 1, color);
            } else {
                Draw.hLine(g, cx, sRight, cBot - 1, color);
            }
        } else {
            Draw.vLine(g, cRight - 1, cy, cBot, color);
            Draw.vLine(g, cx, cy, sy, color);
            Draw.hLine(g, sx, cx + 1, sy, color);
            Draw.vLine(g, sx, sy, sBot, color);
            if (sBot < cBot) {
                Draw.hLine(g, sx, cx + 1, sBot - 1, color);
                Draw.vLine(g, cx, sBot, cBot, color);
                Draw.hLine(g, cx, cRight, cBot - 1, color);
            } else if (sBot > cBot) {
                Draw.hLine(g, cx, cRight, cBot - 1, color);
                Draw.vLine(g, cx - 1, cBot - 1, sBot, color);
                Draw.hLine(g, sx, cx, sBot - 1, color);
            } else {
                Draw.hLine(g, sx, cRight, cBot - 1, color);
            }
        }
    }

    private int settingsButtonY() {
        return this.height - HEADER_HEIGHT - MARGIN;
    }

    private void drawSettingsButton(GuiGraphics graphics, int mouseX, int mouseY) {
        int btnX = MARGIN;
        int btnY = settingsButtonY();
        boolean hover = Draw.hovered(mouseX, mouseY, btnX, btnY, SETTINGS_BTN_WIDTH, HEADER_HEIGHT);
        Draw.rect(graphics, btnX, btnY, SETTINGS_BTN_WIDTH, HEADER_HEIGHT, Theme.BODY);
        if (hover) {
            Draw.rect(graphics, btnX, btnY, SETTINGS_BTN_WIDTH, HEADER_HEIGHT, Theme.HOVER_LIFT);
        }
        Theme.contour(graphics, btnX, btnY, SETTINGS_BTN_WIDTH, HEADER_HEIGHT);
        Draw.textCentered(graphics, font, "Settings", btnX, btnY, SETTINGS_BTN_WIDTH, HEADER_HEIGHT, Theme.TEXT);
        if (settingsFlyoutOpen) {
            for (int i = 0; i < FLYOUT_ROWS.length; i++) {
                int rowY = btnY - ROW_HEIGHT * (i + 1);
                boolean rowHover = Draw.hovered(mouseX, mouseY, btnX, rowY, SETTINGS_BTN_WIDTH, ROW_HEIGHT);
                Draw.rect(graphics, btnX, rowY, SETTINGS_BTN_WIDTH, ROW_HEIGHT, Theme.BODY);
                if (rowHover) {
                    Draw.rect(graphics, btnX, rowY, SETTINGS_BTN_WIDTH, ROW_HEIGHT, Theme.HOVER_LIFT);
                }
                Theme.contour(graphics, btnX, rowY, SETTINGS_BTN_WIDTH, ROW_HEIGHT);
                Draw.textCentered(graphics, font, FLYOUT_ROWS[i], btnX, rowY, SETTINGS_BTN_WIDTH, ROW_HEIGHT, Theme.TEXT);
            }
        }
    }

    public boolean matchesSearch(Module module) {
        return search.isEmpty() || module.name().toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT));
    }

    private int searchBoxX() {
        return this.width - MARGIN - SEARCH_BOX_WIDTH;
    }

    private void drawSearchBox(GuiGraphics graphics) {
        searchCursorCounter++;
        TextField.draw(graphics, font, searchBoxX(), settingsButtonY(), SEARCH_BOX_WIDTH, HEADER_HEIGHT,
                search, searchFocused, "Search...", searchCursorCounter);
    }

    // the topmost containing panel wins even if it has no tooltip
    private String resolveTooltip(int mouseX, int mouseY) {
        if (settingsPanel != null && settingsPanel.contains(mouseX, mouseY)) {
            return settingsPanel.getTooltipAt(mouseX, mouseY);
        }
        CategoryPanel dockHost = settingsPanel != null ? settingsPanel.getDockHost() : null;
        if (dockHost != null && dockHost.contains(mouseX, mouseY)) {
            return dockHost.getTooltipAt(mouseX, mouseY);
        }
        for (int i = panels.size() - 1; i >= 0; i--) {
            CategoryPanel panel = panels.get(i);
            if (panel != dockHost && panel.contains(mouseX, mouseY)) {
                return panel.getTooltipAt(mouseX, mouseY);
            }
        }
        return null;
    }

    private void drawTooltip(GuiGraphics graphics, String text, int mouseX, int mouseY) {
        List<FormattedCharSequence> lines = font.split(FormattedText.of(text), TOOLTIP_MAX_WIDTH);
        int lineH = font.lineHeight;
        int contentW = 0;
        for (FormattedCharSequence line : lines) {
            contentW = Math.max(contentW, font.width(line));
        }
        int boxW = contentW + TOOLTIP_PAD * 2;
        int boxH = lines.size() * lineH + (lines.size() - 1) * TOOLTIP_LINE_GAP + TOOLTIP_PAD * 2;

        int left = mouseX + 8;
        int top = mouseY + 8;
        if (left + boxW > this.width) {
            left = mouseX - 8 - boxW;
        }
        if (top + boxH > this.height) {
            top = mouseY - 8 - boxH;
        }
        left = Math.clamp(left, 2, Math.max(2, this.width - boxW - 2));
        top = Math.clamp(top, 2, Math.max(2, this.height - boxH - 2));

        Draw.borderedRect(graphics, left, top, left + boxW, top + boxH, Theme.TOOLTIP_BG, Theme.CONTOUR, Theme.CONTOUR_PX);
        int textY = top + TOOLTIP_PAD;
        for (FormattedCharSequence line : lines) {
            graphics.drawString(font, line, left + TOOLTIP_PAD, textY, Theme.TEXT, true);
            textY += lineH + TOOLTIP_LINE_GAP;
        }
    }

    // ---- input ----

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int mouseX = (int) event.x();
        int mouseY = (int) event.y();
        int button = event.button();
        if (button != InputConstants.MOUSE_BUTTON_MIDDLE) {
            listeningModule = null;
        }
        searchFocused = false;
        if (settingsPanel != null && settingsPanel.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button == InputConstants.MOUSE_BUTTON_LEFT && Draw.hovered(mouseX, mouseY,
                searchBoxX(), settingsButtonY(), SEARCH_BOX_WIDTH, HEADER_HEIGHT)) {
            searchFocused = true;
            searchCursorCounter = 0;
            return true;
        }
        if (button == InputConstants.MOUSE_BUTTON_LEFT) {
            int btnX = MARGIN;
            int btnY = settingsButtonY();
            if (Draw.hovered(mouseX, mouseY, btnX, btnY, SETTINGS_BTN_WIDTH, HEADER_HEIGHT)) {
                settingsFlyoutOpen = !settingsFlyoutOpen;
                return true;
            }
            if (settingsFlyoutOpen) {
                int editY = btnY - ROW_HEIGHT;
                if (Draw.hovered(mouseX, mouseY, btnX, editY, SETTINGS_BTN_WIDTH, ROW_HEIGHT)) {
                    settingsFlyoutOpen = false;
                    minecraft.setScreen(new HudEditScreen(hudState));
                    return true;
                }
            }
        }
        // hit-test in draw order, docked pair first
        CategoryPanel dockHost = settingsPanel != null ? settingsPanel.getDockHost() : null;
        if (dockHost != null && dockHost.mouseClicked(mouseX, mouseY, button)) {
            raisePanel(dockHost);
            if (settingsPanel != null && settingsPanel.isDockedTo(dockHost) && dockHost.isCollapsed()) {
                settingsPanel = null;
            }
            return true;
        }
        for (int i = panels.size() - 1; i >= 0; i--) {
            CategoryPanel panel = panels.get(i);
            if (panel == dockHost) {
                continue;
            }
            if (panel.mouseClicked(mouseX, mouseY, button)) {
                if (i != panels.size() - 1) {
                    panels.remove(i);
                    panels.add(panel);
                }
                return true;
            }
        }
        if (button == InputConstants.MOUSE_BUTTON_LEFT) {
            settingsPanel = null;
            listeningModule = null;
            settingsFlyoutOpen = false;
        }
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        updateActiveDrags((int) event.x(), (int) event.y());
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (settingsPanel != null) {
            settingsPanel.mouseReleased();
        }
        for (CategoryPanel panel : panels) {
            panel.mouseReleased();
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int wheel = verticalAmount > 0 ? 1 : verticalAmount < 0 ? -1 : 0;
        if (wheel == 0) {
            return false;
        }
        if (settingsPanel != null) {
            settingsPanel.scroll(wheel, (int) mouseX, (int) mouseY);
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (settingsPanel != null && settingsPanel.isSearching()) {
            settingsPanel.keyPressed(event);
            return true;
        }
        if (searchFocused) {
            TextField.EditResult edit = TextField.keyPressed(search, event, -1);
            search = edit.value();
            searchFocused = edit.focused();
            return true;
        }
        KeyMapping guiKey = guiKeyMapping();
        if (listeningModule != null) {
            // the GUI key is eaten before module binds see it, so it can never work as a bind
            boolean unusable = event.isEscape()
                    || event.key() == InputConstants.KEY_BACKSPACE
                    || event.key() == InputConstants.KEY_DELETE
                    || (guiKey != null && guiKey.matches(event));
            listeningModule.keybind().set(unusable ? InputConstants.UNKNOWN : InputConstants.getKey(event));
            listeningModule = null;
            keybindDrainRequested = true;
            minecraft.options.save();
            ClientCore.get().save();
            return true;
        }
        if (event.isEscape()) {
            if (settingsPanel != null) {
                if (!settingsPanel.closeOverlay()) {
                    settingsPanel = null;
                }
                return true;
            }
            onClose();
            return true;
        }
        if (guiKey != null && guiKey.matches(event)) {
            keybindDrainRequested = true;
            onClose();
            return true;
        }
        return false;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (settingsPanel != null && settingsPanel.isSearching()) {
            settingsPanel.charTyped(event);
            return true;
        }
        if (searchFocused) {
            TextField.EditResult edit = TextField.charTyped(search, event, -1);
            search = edit.value();
            searchFocused = edit.focused();
            return true;
        }
        return false;
    }

    private KeyMapping guiKeyMapping() {
        ClickGuiModule clickGui = modules.get(ClickGuiModule.class);
        return clickGui == null ? null : clickGui.keybind().mapping();
    }

    @Override
    public void onClose() {
        listeningModule = null;
        minecraft.setScreen(parent);
    }

    @Override
    public void removed() {
        for (CategoryPanel panel : panels) {
            panel.persist();
        }
        settingsPanel = null;
        listeningModule = null;
        ClientCore.get().save();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void raisePanel(CategoryPanel panel) {
        if (panels.remove(panel)) {
            panels.add(panel);
        }
    }

    /** Mouse buttons: 0 = toggle, 1 = settings, 2 = keybind. */
    public void handleModuleClick(CategoryPanel host, Module module, int button) {
        if (button == 0) {
            module.toggle();
            ClientCore.get().save();
        } else if (button == 1) {
            if (settingsPanel != null && settingsPanel.getModule() == module) {
                settingsPanel = null;
            } else {
                settingsPanel = new SettingsPanel(this, module, host, font, this.width, this.height);
            }
        } else if (button == 2) {
            listeningModule = (listeningModule == module) ? null : module;
        }
    }

    public void closeSettings() {
        settingsPanel = null;
    }

    // ---- shared window chrome ----

    // BODY is translucent; drawing it twice shows as a band
    public static void drawWindowBase(GuiGraphics graphics, int x, int y, int width, int height) {
        Draw.rect(graphics, x, y, width, height, Theme.BODY);
    }

    // drawn after content so the frame covers row separators
    public static void drawWindowFrame(GuiGraphics graphics, int x, int y, int width, int height) {
        drawHeaderRule(graphics, x, y, width);
        Theme.contour(graphics, x, y, width, height);
    }

    public static void drawHeaderRule(GuiGraphics graphics, int x, int y, int width) {
        Draw.rectBounds(graphics, x, y + HEADER_HEIGHT - 1, x + width, y + HEADER_HEIGHT, Theme.SEP);
    }

    public static void drawWindowHeader(GuiGraphics graphics, Font font, String title, int x, int y, int width,
                                        int mouseX, int mouseY) {
        boolean closeHover = hitsClose(x, y, width, HEADER_HEIGHT, mouseX, mouseY);
        if (closeHover) {
            Draw.rectBounds(graphics, x + width - CLOSE_W, y, x + width, y + HEADER_HEIGHT, Theme.HOVER_LIFT);
        }
        Draw.text(graphics, font, title, x + 5, Draw.textY(y, HEADER_HEIGHT), Theme.TEXT);
        Draw.textCentered(graphics, font, "x", x + width - CLOSE_W, y, CLOSE_W, HEADER_HEIGHT,
                closeHover ? Theme.TEXT : Theme.TEXT_DIM);
    }

    public static boolean hitsClose(int windowX, int windowY, int windowWidth, int headerHeight,
                                    int mouseX, int mouseY) {
        return Draw.hovered(mouseX, mouseY, windowX + windowWidth - CLOSE_W, windowY, CLOSE_W, headerHeight);
    }
}
