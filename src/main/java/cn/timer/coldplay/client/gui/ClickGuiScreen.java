package cn.timer.coldplay.client.gui;

import cn.timer.coldplay.client.ClientCore;
import cn.timer.coldplay.client.module.Category;
import cn.timer.coldplay.client.module.Module;
import cn.timer.coldplay.client.module.ModuleManager;
import cn.timer.coldplay.client.setting.BooleanSetting;
import cn.timer.coldplay.client.setting.ColorSetting;
import cn.timer.coldplay.client.setting.KeybindSetting;
import cn.timer.coldplay.client.setting.InventoryPickerSetting;
import cn.timer.coldplay.client.setting.InventoryPickerSetting.CategoryPreference;
import cn.timer.coldplay.client.setting.InventoryPickerSetting.CleanerMode;
import cn.timer.coldplay.client.setting.InventoryPickerSetting.Entry;
import cn.timer.coldplay.client.setting.InventoryPickerSetting.ItemPreference;
import cn.timer.coldplay.client.setting.InventoryPickerSetting.Preference;
import cn.timer.coldplay.client.setting.ModeSetting;
import cn.timer.coldplay.client.setting.NumberSetting;
import cn.timer.coldplay.client.setting.RangeSetting;
import cn.timer.coldplay.client.setting.Setting;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ClickGuiScreen extends Screen {
    private static final int PANEL_WIDTH = 112;
    private static final int PICKER_PANEL_WIDTH = 182;
    private static final int ROW_HEIGHT = 18;
    private static final int PICKER_CELL = 18;
    private static final int PICKER_STEP = 19;
    private static final int PICKER_COLUMNS = 9;
    private static final int PICKER_ROWS = 6;
    private static final int PICKER_WIDTH = PICKER_CELL * PICKER_COLUMNS + PICKER_COLUMNS - 1;
    private static final int PICKER_GRID_HEIGHT = PICKER_CELL * PICKER_ROWS + PICKER_ROWS - 1;
    private static final int PANEL_GAP = 6;
    private static final int BACKGROUND = 0xE0181C22;
    private static final int ROW = 0xE0252B33;
    private static final int HOVER = 0xE0333B45;
    private static final int ACCENT = 0xE000AACC;
    private static final int TEXT = 0xFFF3F6F8;
    private static final int CELL_BACKGROUND = 0xFF171B20;
    private static final int CELL_FRAME = 0xFF59616B;
    private static final int EXCLUDED = 0xFF9A4B4B;
    private static final int KEEP = 0xFF4C9A5B;
    private static final int IGNORED = 0xFF858B92;
    private static final int[] PALETTE = {
            0xFFFFFFFF, 0xFFAAAAAA, 0xFF555555, 0xFF000000,
            0xFFFF5555, 0xFFAA0000, 0xFFFFAA00, 0xFFFFFF55,
            0xFF55FF55, 0xFF00AA00, 0xFF55FFFF, 0xFF00AAAA,
            0xFF5555FF, 0xFF0000AA, 0xFFFF55FF, 0xFFAA55FF
    };

    private final ModuleManager modules;
    private final EnumMap<Category, CategoryPanel> panels = new EnumMap<>(Category.class);
    private final Set<String> expandedModules = new HashSet<>();
    private final Map<InventoryPickerSetting<?>, PickerState> pickerStates = new IdentityHashMap<>();
    private Screen parent;
    private KeybindComponent capturing;
    private ColorPalette palette;
    private CategoryPanel activePanel;
    private InventoryPickerSetting<?> openPicker;

    public ClickGuiScreen(ModuleManager modules) {
        super(Component.literal("ColdPlay"));
        this.modules = modules;
        for (Category category : Category.values()) {
            panels.put(category, new CategoryPanel(category, 10 + category.ordinal() * (PANEL_WIDTH + PANEL_GAP), 20));
        }
    }

    public void setParent(Screen parent) {
        this.parent = parent;
    }

    public boolean isCapturingKey() {
        return capturing != null;
    }

    public void resetTransientState() {
        resetEditorState();
        if (minecraft != null && minecraft.screen == this) {
            rebuildWidgets();
        }
    }

    @Override
    protected void init() {
        palette = null;
        InventoryPickerSetting<?> previousPicker = openPicker;
        activePanel = panelFor(openPicker);
        if (activePanel == null) {
            PickerState state = pickerStates.get(previousPicker);
            if (state != null) {
                state.clearInteraction();
            }
            openPicker = null;
        }
        List<CategoryPanel> buildOrder = new ArrayList<>(panels.values());
        if (activePanel != null) {
            buildOrder.remove(activePanel);
            buildOrder.addFirst(activePanel);
        }
        for (CategoryPanel panel : buildOrder) {
            panel.width = panel == activePanel ? PICKER_PANEL_WIDTH : PANEL_WIDTH;
            clamp(panel);
            panel.widgets.clear();
            int y = panel.y;
            add(panel, new CategoryHeader(panel, panel.x, y));
            y += ROW_HEIGHT;

            if (panel.expanded) {
                for (Module module : modules.getByCategory(panel.category)) {
                    add(panel, new ModuleButton(panel, module, panel.x, y - panel.scroll));
                    y += ROW_HEIGHT;
                    if (expandedModules.contains(module.name())) {
                        List<Setting<?>> settings = new ArrayList<>(module.settings());
                        settings.sort(Comparator.comparing(setting -> setting instanceof KeybindSetting));
                        for (Setting<?> setting : settings) {
                            Setting<?> owner = module.ownerOf(setting);
                            if (owner instanceof BooleanSetting toggle && !toggle.get()) {
                                continue;
                            }
                            int indent = owner == null ? 6 : 12;
                            AbstractWidget widget = settingWidget(module, setting, panel, panel.x + indent,
                                    y - panel.scroll, panel.width - indent);
                            add(panel, widget);
                            y += widget.getHeight();
                        }
                    }
                }
            }
            panel.contentHeight = Math.max(0, y - panel.y - ROW_HEIGHT);
            int viewport = Math.min(panel.contentHeight, Math.max(0, height - panel.y - ROW_HEIGHT));
            int oldScroll = panel.scroll;
            panel.scroll = Math.clamp(panel.scroll, 0, Math.max(0, panel.contentHeight - viewport));
            int scrollCorrection = oldScroll - panel.scroll;
            if (scrollCorrection != 0) {
                panel.widgets.stream().skip(1).forEach(widget -> widget.setY(widget.getY() + scrollCorrection));
            }
            panel.height = ROW_HEIGHT + viewport;
            panel.widgets.stream()
                    .filter(InventoryPickerComponent.class::isInstance)
                    .map(InventoryPickerComponent.class::cast)
                    .forEach(InventoryPickerComponent::clearSearchFocusIfClipped);
        }
    }

    private AbstractWidget settingWidget(Module module, Setting<?> setting, CategoryPanel panel,
                                         int x, int y, int width) {
        boolean owner = module.isOwnerSetting(setting);
        if (setting instanceof BooleanSetting toggle) {
            return new ToggleComponent(module, toggle, owner, x, y, width);
        }
        if (setting instanceof ColorSetting color) {
            return new ColorComponent(color, x, y, width);
        }
        if (setting instanceof NumberSetting number) {
            return new SliderComponent(number, owner, x, y, width);
        }
        if (setting instanceof RangeSetting range) {
            return new RangeComponent(range, owner, x, y, width);
        }
        if (setting instanceof InventoryPickerSetting<?> picker) {
            return new InventoryPickerComponent(picker, panel, x, y, width);
        }
        if (setting instanceof ModeSetting mode) {
            return new ModeComponent(mode, owner, x, y, width);
        }
        if (setting instanceof KeybindSetting keybind) {
            return new KeybindComponent(keybind, x, y, width);
        }
        throw new IllegalArgumentException("Unsupported setting: " + setting.getClass().getName());
    }

    private <T extends AbstractWidget> T add(CategoryPanel panel, T widget) {
        panel.widgets.add(widget);
        return addRenderableWidget(widget);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float tickDelta) {
        graphics.fill(0, 0, width, height, 0x78000000);
        for (CategoryPanel panel : renderOrder()) {
            graphics.fill(panel.x, panel.y, panel.x + panel.width, panel.y + panel.height, BACKGROUND);
            graphics.renderOutline(panel.x, panel.y, panel.width, panel.height, 0xFF707780);
            if (!panel.widgets.isEmpty()) {
                panel.widgets.getFirst().render(graphics, mouseX, mouseY, tickDelta);
            }
            if (panel.height > ROW_HEIGHT) {
                graphics.enableScissor(panel.x, panel.y + ROW_HEIGHT, panel.x + panel.width,
                        panel.y + panel.height);
                panel.widgets.stream().skip(1).forEach(widget -> widget.render(graphics, mouseX, mouseY, tickDelta));
                graphics.disableScissor();
            }
        }
        PickerState pickerState = pickerStates.get(openPicker);
        if (pickerState != null && pickerState.drag != null && !pickerState.dragStack.isEmpty()) {
            graphics.renderItem(pickerState.dragStack, mouseX - 8, mouseY - 8);
        }
        if (palette != null) {
            palette.render(graphics, mouseX, mouseY, tickDelta);
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (palette != null && palette.handleKey(event)) {
            return true;
        }
        if (capturing != null) {
            if (event.isEscape()) {
                capturing.cancelCapture();
            } else if (event.key() == InputConstants.KEY_BACKSPACE || event.key() == InputConstants.KEY_DELETE) {
                capturing.bind(InputConstants.UNKNOWN);
            } else {
                capturing.bind(InputConstants.getKey(event));
            }
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (palette != null) {
            if (palette.isMouseOver(event.x(), event.y())) {
                palette.choose(event);
                return true;
            }
            closePalette();
        }
        CategoryPanel panel = topPanelAt(event.x(), event.y());
        if (panel == null) {
            setFocused(null);
            return false;
        }
        AbstractWidget widget = widgetAt(panel, event.x(), event.y());
        if (widget != null && widget.mouseClicked(event, doubleClick)) {
            if (children().contains(widget) && widget.shouldTakeFocusAfterInteraction()) {
                setFocused(widget);
                if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
                    setDragging(true);
                }
            }
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        CategoryPanel panel = topPanelAt(mouseX, mouseY);
        if (panel == null || mouseY < panel.y + ROW_HEIGHT) {
            return false;
        }
        AbstractWidget widget = widgetAt(panel, mouseX, mouseY);
        if (widget != null && widget.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) {
            return true;
        }
        int viewport = panel.height - ROW_HEIGHT;
        int oldScroll = panel.scroll;
        panel.scroll = Math.clamp(panel.scroll - (int) Math.round(verticalAmount * ROW_HEIGHT), 0,
                Math.max(0, panel.contentHeight - viewport));
        int dy = oldScroll - panel.scroll;
        if (dy != 0) {
            panel.widgets.stream().skip(1).forEach(child -> child.setY(child.getY() + dy));
            panel.widgets.stream()
                    .filter(InventoryPickerComponent.class::isInstance)
                    .map(InventoryPickerComponent.class::cast)
                    .forEach(InventoryPickerComponent::clearSearchFocusIfClipped);
        }
        return true;
    }

    @Override
    public void onClose() {
        capturing = null;
        palette = null;
        resetEditorState();
        ClientCore.get().save();
        minecraft.setScreen(parent);
    }

    @Override
    public void removed() {
        resetEditorState();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    public Map<Category, PanelState> panelStates() {
        EnumMap<Category, PanelState> states = new EnumMap<>(Category.class);
        panels.forEach((category, panel) -> states.put(category, new PanelState(panel.x, panel.y, panel.expanded)));
        return states;
    }

    public void setPanelState(Category category, int x, int y, boolean expanded) {
        CategoryPanel panel = panels.get(category);
        panel.x = x;
        panel.y = y;
        panel.expanded = expanded;
    }

    public Set<String> expandedModules() {
        return Set.copyOf(expandedModules);
    }

    public void setExpandedModules(Set<String> names) {
        expandedModules.clear();
        expandedModules.addAll(names);
    }

    private void move(CategoryPanel panel, int x, int y) {
        int oldX = panel.x;
        int oldY = panel.y;
        panel.x = x;
        panel.y = y;
        clamp(panel);
        int dx = panel.x - oldX;
        int dy = panel.y - oldY;
        panel.widgets.forEach(widget -> {
            widget.setX(widget.getX() + dx);
            widget.setY(widget.getY() + dy);
        });
    }

    private void clamp(CategoryPanel panel) {
        panel.x = Math.clamp(panel.x, 0, Math.max(0, width - panel.width));
        panel.y = Math.clamp(panel.y, 0, Math.max(0, height - ROW_HEIGHT));
    }

    private CategoryPanel panelFor(InventoryPickerSetting<?> setting) {
        if (setting == null) {
            return null;
        }
        for (CategoryPanel panel : panels.values()) {
            if (!panel.expanded) {
                continue;
            }
            for (Module module : modules.getByCategory(panel.category)) {
                if (!expandedModules.contains(module.name()) || !module.settings().contains(setting)) {
                    continue;
                }
                Setting<?> owner = module.ownerOf(setting);
                if (!(owner instanceof BooleanSetting toggle) || toggle.get()) {
                    return panel;
                }
            }
        }
        return null;
    }

    private void togglePicker(InventoryPickerSetting<?> setting, CategoryPanel panel) {
        if (openPicker == setting) {
            PickerState state = pickerStates.get(setting);
            if (state != null) {
                state.clearInteraction();
            }
            openPicker = null;
            activePanel = null;
        } else {
            if (openPicker != null) {
                PickerState state = pickerStates.get(openPicker);
                if (state != null) {
                    state.clearInteraction();
                }
            }
            openPicker = setting;
            activePanel = panel;
        }
        rebuildWidgets();
    }

    private void resetEditorState() {
        InventoryPickerSetting<?> firstPicker = null;
        for (Module module : modules.modules()) {
            for (Setting<?> setting : module.settings()) {
                if (setting instanceof InventoryPickerSetting<?> picker) {
                    picker.clearResolved();
                    firstPicker = picker;
                }
            }
        }
        if (firstPicker != null) {
            firstPicker.catalog().clear();
        }
        openPicker = null;
        activePanel = null;
        pickerStates.clear();
        panels.values().forEach(panel -> {
            panel.width = PANEL_WIDTH;
            panel.scroll = 0;
        });
    }

    private boolean resetPickerState(Module module) {
        InventoryPickerSetting<?> firstPicker = null;
        boolean closedActivePicker = false;
        for (Setting<?> setting : module.settings()) {
            if (!(setting instanceof InventoryPickerSetting<?> picker)) {
                continue;
            }
            PickerState state = pickerStates.remove(picker);
            if (state != null) {
                state.clearInteraction();
            }
            picker.clearResolved();
            firstPicker = picker;
            if (openPicker == picker) {
                openPicker = null;
                activePanel = null;
                closedActivePicker = true;
            }
        }
        if (firstPicker != null) {
            firstPicker.catalog().clear();
        }
        return closedActivePicker;
    }

    private List<CategoryPanel> renderOrder() {
        List<CategoryPanel> order = new ArrayList<>(panels.values());
        if (activePanel != null) {
            order.remove(activePanel);
            order.add(activePanel);
        }
        return order;
    }

    private CategoryPanel topPanelAt(double mouseX, double mouseY) {
        List<CategoryPanel> order = renderOrder();
        for (int index = order.size() - 1; index >= 0; index--) {
            CategoryPanel panel = order.get(index);
            if (mouseX >= panel.x && mouseX < panel.x + panel.width
                    && mouseY >= panel.y && mouseY < panel.y + panel.height) {
                return panel;
            }
        }
        return null;
    }

    private AbstractWidget widgetAt(CategoryPanel panel, double mouseX, double mouseY) {
        if (mouseY < panel.y + ROW_HEIGHT) {
            return panel.widgets.isEmpty() ? null : panel.widgets.getFirst();
        }
        if (mouseY >= panel.y + panel.height) {
            return null;
        }
        for (int index = 1; index < panel.widgets.size(); index++) {
            AbstractWidget widget = panel.widgets.get(index);
            if (widget.isMouseOver(mouseX, mouseY)) {
                return widget;
            }
        }
        return null;
    }

    private void toggle(CategoryPanel panel) {
        panel.expanded = !panel.expanded;
        rebuildWidgets();
        ClientCore.get().save();
    }

    private Component settingMessage(String text, boolean owner) {
        Component message = Component.literal(text);
        return owner ? message.copy().withStyle(ChatFormatting.BOLD) : message;
    }

    private void openPalette(ColorSetting setting, AbstractWidget source) {
        closePalette();
        int size = 64;
        int x = Math.clamp(source.getRight() - size, 0, Math.max(0, width - size));
        int y = source.getBottom() + size <= height ? source.getBottom() : source.getY() - size;
        palette = addRenderableWidget(new ColorPalette(setting, x, Math.max(0, y), size));
    }

    private void closePalette() {
        if (palette != null) {
            removeWidget(palette);
            palette = null;
        }
    }

    public record PanelState(int x, int y, boolean expanded) {
    }

    private static final class CategoryPanel {
        private final Category category;
        private final List<AbstractWidget> widgets = new ArrayList<>();
        private int x;
        private int y;
        private int width = PANEL_WIDTH;
        private int height = ROW_HEIGHT;
        private int contentHeight;
        private int scroll;
        private boolean expanded = true;

        private CategoryPanel(Category category, int x, int y) {
            this.category = category;
            this.x = x;
            this.y = y;
        }
    }

    private abstract class RowButton extends AbstractButton {
        protected RowButton(int x, int y, int width, Component message) {
            super(x, y, width, ROW_HEIGHT, message);
        }

        protected int color() {
            return isHoveredOrFocused() ? HOVER : ROW;
        }

        @Override
        protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float tickDelta) {
            graphics.fill(getX(), getY(), getRight(), getBottom(), color());
            if (isFocused()) {
                graphics.renderOutline(getX(), getY(), getWidth(), getHeight(), ACCENT);
            }
            graphics.drawCenteredString(font, getMessage(), getX() + getWidth() / 2, getY() + 5, TEXT);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    private final class CategoryHeader extends RowButton {
        private final CategoryPanel panel;
        private double startMouseX;
        private double startMouseY;
        private int startPanelX;
        private int startPanelY;
        private boolean moved;

        private CategoryHeader(CategoryPanel panel, int x, int y) {
            super(x, y, panel.width, Component.literal(panel.category.name()));
            this.panel = panel;
        }

        @Override
        protected int color() {
            return ACCENT;
        }

        @Override
        public void onPress(InputWithModifiers input) {
            toggle(panel);
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            startMouseX = event.x();
            startMouseY = event.y();
            startPanelX = panel.x;
            startPanelY = panel.y;
            moved = false;
        }

        @Override
        protected void onDrag(MouseButtonEvent event, double dragX, double dragY) {
            int dx = (int) Math.round(event.x() - startMouseX);
            int dy = (int) Math.round(event.y() - startMouseY);
            moved |= Math.abs(dx) > 3 || Math.abs(dy) > 3;
            move(panel, startPanelX + dx, startPanelY + dy);
        }

        @Override
        public void onRelease(MouseButtonEvent event) {
            if (moved) {
                ClientCore.get().save();
            } else {
                toggle(panel);
            }
        }
    }

    private final class ModuleButton extends RowButton {
        private final Module module;

        private ModuleButton(CategoryPanel panel, Module module, int x, int y) {
            super(x, y, panel.width, Component.literal(module.name()));
            this.module = module;
            setTooltip(Tooltip.create(Component.literal(module.description())));
        }

        @Override
        protected int color() {
            return module.enabled() ? ACCENT : super.color();
        }

        @Override
        public Component getMessage() {
            return Component.literal(module.name() + (expandedModules.contains(module.name()) ? "  ▼" : "  ▶"));
        }

        @Override
        public void onPress(InputWithModifiers input) {
            module.toggle();
            if (!module.enabled() && resetPickerState(module)) {
                rebuildWidgets();
            }
            ClientCore.get().save();
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (isMouseOver(event.x(), event.y()) && event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
                expand(!expandedModules.contains(module.name()));
                return true;
            }
            return super.mouseClicked(event, doubleClick);
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            if (isFocused() && (event.isLeft() || event.isRight())) {
                expand(event.isRight());
                return true;
            }
            return super.keyPressed(event);
        }

        private void expand(boolean expanded) {
            if (expanded) {
                expandedModules.add(module.name());
            } else {
                expandedModules.remove(module.name());
            }
            rebuildWidgets();
            ClientCore.get().save();
        }
    }

    private final class ModeComponent extends RowButton {
        private final ModeSetting setting;
        private final boolean owner;

        private ModeComponent(ModeSetting setting, boolean owner, int x, int y, int width) {
            super(x, y, width, Component.empty());
            this.setting = setting;
            this.owner = owner;
        }

        @Override
        public Component getMessage() {
            return settingMessage(setting.name() + ": " + setting.get(), owner);
        }

        @Override
        public void onPress(InputWithModifiers input) {
            setting.next(input.hasShiftDown() ? -1 : 1);
            ClientCore.get().save();
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (isMouseOver(event.x(), event.y()) && event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
                setting.next(-1);
                ClientCore.get().save();
                return true;
            }
            return super.mouseClicked(event, doubleClick);
        }
    }

    private final class ToggleComponent extends RowButton {
        private final Module module;
        private final BooleanSetting setting;
        private final boolean owner;

        private ToggleComponent(Module module, BooleanSetting setting, boolean owner,
                                int x, int y, int width) {
            super(x, y, width, Component.empty());
            this.module = module;
            this.setting = setting;
            this.owner = owner;
        }

        @Override
        protected int color() {
            return setting.get() ? ACCENT : super.color();
        }

        @Override
        public Component getMessage() {
            return settingMessage(setting.name() + ": " + (setting.get() ? "ON" : "OFF"), owner);
        }

        @Override
        public void onPress(InputWithModifiers input) {
            setting.toggle();
            ClientCore.get().save();
            if (module.isOwnerSetting(setting)) {
                rebuildWidgets();
            }
        }
    }

    private final class ColorComponent extends RowButton {
        private final ColorSetting setting;

        private ColorComponent(ColorSetting setting, int x, int y, int width) {
            super(x, y, width, Component.literal(setting.name()));
            this.setting = setting;
        }

        @Override
        protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float tickDelta) {
            graphics.fill(getX(), getY(), getRight(), getBottom(), color());
            if (isFocused()) {
                graphics.renderOutline(getX(), getY(), getWidth(), getHeight(), ACCENT);
            }
            graphics.drawString(font, Component.literal(setting.name()), getX() + 4, getY() + 5, TEXT, false);
            int previewX = getRight() - 16;
            graphics.fill(previewX, getY() + 3, previewX + 12, getBottom() - 3, setting.get());
            graphics.renderOutline(previewX, getY() + 3, 12, getHeight() - 6, TEXT);
        }

        @Override
        public void onPress(InputWithModifiers input) {
            openPalette(setting, this);
        }

        @Override
        public Component getMessage() {
            return Component.literal(setting.name() + ": " + String.format("#%06X", setting.get() & 0xFFFFFF));
        }
    }

    private final class KeybindComponent extends RowButton {
        private final KeybindSetting setting;

        private KeybindComponent(KeybindSetting setting, int x, int y, int width) {
            super(x, y, width, Component.empty());
            this.setting = setting;
        }

        @Override
        public Component getMessage() {
            if (capturing == this) {
                return Component.literal(setting.name() + ": press a key");
            }
            return Component.literal(setting.name() + ": ").append(setting.get().getDisplayName());
        }

        @Override
        public void onPress(InputWithModifiers input) {
            capturing = this;
        }

        private void bind(InputConstants.Key key) {
            setting.set(key);
            minecraft.options.save();
            capturing = null;
        }

        private void cancelCapture() {
            capturing = null;
        }
    }

    private final class SliderComponent extends AbstractSliderButton {
        private final NumberSetting setting;
        private final boolean owner;

        private SliderComponent(NumberSetting setting, boolean owner, int x, int y, int width) {
            super(x, y, width, ROW_HEIGHT, Component.empty(),
                    (setting.get() - setting.minimum()) / (setting.maximum() - setting.minimum()));
            this.setting = setting;
            this.owner = owner;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(settingMessage(setting.name() + ": " + format(setting.get())
                    + (setting.unit().isEmpty() ? "" : " " + setting.unit()), owner));
        }

        @Override
        protected void applyValue() {
            setting.set(setting.minimum() + value * (setting.maximum() - setting.minimum()));
            value = (setting.get() - setting.minimum()) / (setting.maximum() - setting.minimum());
            updateMessage();
        }

        @Override
        public void onRelease(MouseButtonEvent event) {
            super.onRelease(event);
            ClientCore.get().save();
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            boolean handled = super.keyPressed(event);
            if (handled) {
                ClientCore.get().save();
            }
            return handled;
        }

        private String format(double value) {
            return value == Math.rint(value) ? Long.toString(Math.round(value)) : Double.toString(value);
        }
    }

    private static final class PickerState {
        private InventoryPickerSetting.Category category;
        private String search = "";
        private int gridRow;
        private boolean searchFocused;
        private Preference drag;
        private ItemStack dragStack = ItemStack.EMPTY;

        private void clearInteraction() {
            searchFocused = false;
            drag = null;
            dragStack = ItemStack.EMPTY;
        }
    }

    private final class InventoryPickerComponent extends AbstractWidget {
        private final InventoryPickerSetting<?> setting;
        private final CategoryPanel panel;
        private final PickerState state;
        private final EditBox search;

        private InventoryPickerComponent(InventoryPickerSetting<?> setting, CategoryPanel panel,
                                         int x, int y, int width) {
            super(x, y, width, ROW_HEIGHT, Component.literal(setting.name()));
            this.setting = setting;
            this.panel = panel;
            this.state = pickerStates.computeIfAbsent(setting, ignored -> new PickerState());
            this.search = new EditBox(font, x, y, PICKER_WIDTH, ROW_HEIGHT,
                    Component.literal("Search items"));
            search.setMaxLength(64);
            search.setHint(Component.literal("Search items...").withStyle(ChatFormatting.DARK_GRAY));
            search.setValue(state.search);
            search.setResponder(value -> {
                state.search = value;
                state.gridRow = 0;
            });
            search.setFocused(state.searchFocused);
            setHeight(pickerHeight(ready()));
        }

        private boolean expanded() {
            return openPicker == setting;
        }

        private boolean ready() {
            if (!expanded() || !setting.catalog().ensure(minecraft)) {
                return false;
            }
            setting.resolve(minecraft.level.registryAccess());
            return true;
        }

        private int pickerHeight(boolean ready) {
            if (!expanded()) {
                return ROW_HEIGHT;
            }
            if (!ready) {
                return ROW_HEIGHT + 30;
            }
            int result = ROW_HEIGHT + PICKER_CELL;
            if (state.category != null) {
                result += 1 + ROW_HEIGHT + 1 + PICKER_GRID_HEIGHT;
            }
            if (setting instanceof InventoryPickerSetting.Layout layout) {
                result += 3 + Math.max(1, layout.maxDepth()) * PICKER_STEP - 1;
            }
            return result + 1;
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float tickDelta) {
            graphics.fill(getX(), getY(), getRight(), getY() + ROW_HEIGHT,
                    mouseY >= getY() && mouseY < getY() + ROW_HEIGHT ? HOVER : ROW);
            graphics.drawString(font, setting.name(), getX() + 4, getY() + 5, TEXT, false);
            graphics.drawString(font, expanded() ? "[-]" : "[+]", getRight() - 19, getY() + 5, TEXT, false);
            if (!expanded()) {
                return;
            }
            if (!ready()) {
                graphics.drawCenteredString(font, "Join a world to load items", getX() + getWidth() / 2,
                        getY() + ROW_HEIGHT + 10, IGNORED);
                return;
            }
            renderPicker(graphics, mouseX, mouseY, tickDelta);
        }

        private void renderPicker(GuiGraphics graphics, int mouseX, int mouseY, float tickDelta) {
            renderCategories(graphics, mouseX, mouseY);
            if (state.category != null) {
                search.setX(getX());
                search.setY(searchY());
                search.setWidth(PICKER_WIDTH);
                search.render(graphics, mouseX, mouseY, tickDelta);
                renderGrid(graphics, mouseX, mouseY);
            }
            if (setting instanceof InventoryPickerSetting.Layout layout) {
                renderTemplate(graphics, layout, mouseX, mouseY);
            }
        }

        private void renderCategories(GuiGraphics graphics, int mouseX, int mouseY) {
            InventoryPickerSetting.Category[] categories = InventoryPickerSetting.Category.values();
            for (int index = 0; index < categories.length; index++) {
                InventoryPickerSetting.Category category = categories[index];
                int x = getX() + index * PICKER_STEP;
                int y = categoryY();
                boolean hovered = inCell(mouseX, mouseY, x, y);
                CleanerMode mode = setting instanceof InventoryPickerSetting.Cleaner cleaner
                        ? cleaner.categoryMode(category) : CleanerMode.UNSET;
                boolean assigned = setting instanceof InventoryPickerSetting.Layout layout
                        && layout.slots().stream().flatMap(List::stream)
                        .anyMatch(preference -> preference.equals(new CategoryPreference(category)));
                int frame = mode == CleanerMode.UNSET ? assigned ? ACCENT : CELL_FRAME : modeColor(mode);
                drawCell(graphics, x, y, frame, hovered);
                graphics.renderItem(setting.catalog().icon(category), x + 1, y + 1);
                if (mode != CleanerMode.UNSET) {
                    drawBadge(graphics, x, y, mode);
                }
                if (state.category == category) {
                    graphics.renderOutline(x, y, PICKER_CELL, PICKER_CELL, ACCENT);
                    if (mode != CleanerMode.UNSET) {
                        graphics.renderOutline(x + 2, y + 2, PICKER_CELL - 4, PICKER_CELL - 4, modeColor(mode));
                    }
                }
                if (hovered) {
                    graphics.setTooltipForNextFrame(Component.literal(category.displayName()), mouseX, mouseY);
                }
            }
        }

        private void renderGrid(GuiGraphics graphics, int mouseX, int mouseY) {
            List<Entry> entries = filteredEntries();
            int totalRows = (entries.size() + PICKER_COLUMNS - 1) / PICKER_COLUMNS;
            state.gridRow = Math.clamp(state.gridRow, 0, Math.max(0, totalRows - PICKER_ROWS));
            int first = state.gridRow * PICKER_COLUMNS;
            int limit = Math.min(entries.size(), first + PICKER_COLUMNS * PICKER_ROWS);
            for (int index = first; index < limit; index++) {
                Entry entry = entries.get(index);
                int visibleIndex = index - first;
                int x = getX() + visibleIndex % PICKER_COLUMNS * PICKER_STEP;
                int y = gridY() + visibleIndex / PICKER_COLUMNS * PICKER_STEP;
                boolean hovered = inCell(mouseX, mouseY, x, y);
                int frame = CELL_FRAME;
                boolean excluded = false;
                CleanerMode mode = CleanerMode.UNSET;
                if (setting instanceof InventoryPickerSetting.Layout layout) {
                    excluded = layout.isExcluded(entry.ref());
                    boolean assigned = layout.slots().stream().flatMap(List::stream)
                            .anyMatch(preference -> preference.equals(new ItemPreference(entry.ref())));
                    frame = excluded ? EXCLUDED : assigned ? ACCENT : CELL_FRAME;
                } else if (setting instanceof InventoryPickerSetting.Cleaner cleaner) {
                    mode = cleaner.itemMode(entry.ref());
                    frame = modeColor(mode);
                }
                drawCell(graphics, x, y, frame, hovered);
                graphics.renderItem(entry.stack(), x + 1, y + 1);
                if (excluded) {
                    graphics.fill(x + 1, y + 1, x + PICKER_CELL - 1, y + PICKER_CELL - 1, 0x78000000);
                }
                if (mode != CleanerMode.UNSET) {
                    drawBadge(graphics, x, y, mode);
                }
                if (hovered) {
                    graphics.setTooltipForNextFrame(font, entry.stack(), mouseX, mouseY);
                }
            }
        }

        private void renderTemplate(GuiGraphics graphics, InventoryPickerSetting.Layout layout,
                                    int mouseX, int mouseY) {
            int depth = Math.max(1, layout.maxDepth());
            for (int row = 0; row < depth; row++) {
                for (int slot = 0; slot < InventoryPickerSetting.Layout.SLOT_COUNT; slot++) {
                    int x = getX() + slot * PICKER_STEP;
                    int y = templateY() + row * PICKER_STEP;
                    boolean hovered = inCell(mouseX, mouseY, x, y);
                    List<Preference> preferences = layout.slot(slot);
                    Preference preference = row < preferences.size() ? preferences.get(row) : null;
                    drawCell(graphics, x, y, row == 0 && preference != null ? ACCENT : CELL_FRAME, hovered);
                    if (preference == null) {
                        if (row == 0) {
                            graphics.drawCenteredString(font, "+", x + PICKER_CELL / 2, y + 5, IGNORED);
                        }
                        continue;
                    }
                    ItemStack stack = preferenceStack(preference);
                    if (!stack.isEmpty()) {
                        graphics.renderItem(stack, x + 1, y + 1);
                    }
                    if (preference instanceof CategoryPreference) {
                        graphics.fill(x + PICKER_CELL - 4, y + 1, x + PICKER_CELL - 1, y + 4, ACCENT);
                    }
                    if (hovered) {
                        if (preference instanceof CategoryPreference category) {
                            graphics.setTooltipForNextFrame(Component.literal(category.category().displayName()),
                                    mouseX, mouseY);
                        } else if (!stack.isEmpty()) {
                            graphics.setTooltipForNextFrame(font, stack, mouseX, mouseY);
                        }
                    }
                }
            }
            int target = targetSlot(mouseX, mouseY);
            if (state.drag != null && target >= 0) {
                graphics.renderOutline(getX() + target * PICKER_STEP, templateY(), PICKER_CELL, PICKER_CELL, TEXT);
            }
        }

        private void drawCell(GuiGraphics graphics, int x, int y, int frame, boolean hovered) {
            graphics.fill(x, y, x + PICKER_CELL, y + PICKER_CELL, CELL_BACKGROUND);
            graphics.renderOutline(x, y, PICKER_CELL, PICKER_CELL, frame);
            if (hovered) {
                graphics.renderOutline(x + 1, y + 1, PICKER_CELL - 2, PICKER_CELL - 2, TEXT);
            }
        }

        private void drawBadge(GuiGraphics graphics, int x, int y, CleanerMode mode) {
            String badge = switch (mode) {
                case DROP -> "D";
                case KEEP_ONE -> "K";
                case IGNORE -> "I";
                case UNSET -> "";
            };
            if (!badge.isEmpty()) {
                graphics.fill(x + 1, y + 1, x + 8, y + 10, 0xD0000000);
                graphics.drawString(font, badge, x + 2, y + 1, modeColor(mode), false);
            }
        }

        private int modeColor(CleanerMode mode) {
            return switch (mode) {
                case DROP -> EXCLUDED;
                case KEEP_ONE -> KEEP;
                case IGNORE -> IGNORED;
                case UNSET -> CELL_FRAME;
            };
        }

        private List<Entry> filteredEntries() {
            return state.category == null ? List.of()
                    : setting.catalog().filtered(state.category, state.search);
        }

        private ItemStack preferenceStack(Preference preference) {
            if (preference instanceof CategoryPreference category) {
                return setting.catalog().icon(category.category());
            }
            return ((ItemPreference) preference).item().stack();
        }

        private int categoryY() {
            return getY() + ROW_HEIGHT;
        }

        private int searchY() {
            return categoryY() + PICKER_STEP;
        }

        private int gridY() {
            return searchY() + ROW_HEIGHT + 1;
        }

        private int templateY() {
            return state.category == null ? categoryY() + PICKER_CELL + 3
                    : gridY() + PICKER_GRID_HEIGHT + 3;
        }

        private boolean inCell(double mouseX, double mouseY, int x, int y) {
            return inPanelBody(mouseX, mouseY)
                    && mouseX >= x && mouseX < x + PICKER_CELL
                    && mouseY >= y && mouseY < y + PICKER_CELL;
        }

        private int targetSlot(double mouseX, double mouseY) {
            if (!inPanelBody(mouseX, mouseY)
                    || !(setting instanceof InventoryPickerSetting.Layout layout)
                    || mouseY < templateY()
                    || mouseY >= templateY() + Math.max(1, layout.maxDepth()) * PICKER_STEP) {
                return -1;
            }
            int localX = (int) mouseX - getX();
            if (localX < 0 || localX >= PICKER_WIDTH || localX % PICKER_STEP >= PICKER_CELL) {
                return -1;
            }
            return localX / PICKER_STEP;
        }

        private boolean inPanelBody(double mouseX, double mouseY) {
            return mouseX >= panel.x && mouseX < panel.x + panel.width
                    && mouseY >= panel.y + ROW_HEIGHT && mouseY < panel.y + panel.height;
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (!isMouseOver(event.x(), event.y())) {
                return false;
            }
            if (event.y() < getY() + ROW_HEIGHT) {
                playDownSound(minecraft.getSoundManager());
                togglePicker(setting, panel);
                return true;
            }
            if (!expanded() || !ready()) {
                return expanded();
            }
            if (state.category != null && event.x() >= getX() && event.x() < getX() + PICKER_WIDTH
                    && event.y() >= searchY() && event.y() < searchY() + ROW_HEIGHT) {
                boolean handled = search.mouseClicked(event, doubleClick);
                search.setFocused(handled);
                state.searchFocused = handled;
                return true;
            }
            search.setFocused(false);
            state.searchFocused = false;

            InventoryPickerSetting.Category category = categoryAt(event.x(), event.y());
            if (category != null) {
                playDownSound(minecraft.getSoundManager());
                boolean changedCategory = state.category != category;
                state.category = category;
                state.gridRow = 0;
                if (setting instanceof InventoryPickerSetting.Cleaner cleaner
                        && (event.button() == InputConstants.MOUSE_BUTTON_LEFT
                        || event.button() == InputConstants.MOUSE_BUTTON_RIGHT)) {
                    cleaner.cycle(category, event.button() == InputConstants.MOUSE_BUTTON_RIGHT ? -1 : 1);
                    ClientCore.get().save();
                } else if (!changedCategory && setting instanceof InventoryPickerSetting.Layout
                        && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
                    beginDrag(new CategoryPreference(category));
                }
                if (changedCategory) {
                    rebuildWidgets();
                }
                return true;
            }

            Entry entry = entryAt(event.x(), event.y());
            if (entry != null) {
                playDownSound(minecraft.getSoundManager());
                if (setting instanceof InventoryPickerSetting.Cleaner cleaner
                        && (event.button() == InputConstants.MOUSE_BUTTON_LEFT
                        || event.button() == InputConstants.MOUSE_BUTTON_RIGHT)) {
                    cleaner.cycle(entry.ref(), event.button() == InputConstants.MOUSE_BUTTON_RIGHT ? -1 : 1);
                    ClientCore.get().save();
                } else if (setting instanceof InventoryPickerSetting.Layout layout) {
                    if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
                        layout.toggle(entry.ref());
                        ClientCore.get().save();
                    } else if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
                        beginDrag(new ItemPreference(entry.ref()));
                    }
                }
                return true;
            }

            if (setting instanceof InventoryPickerSetting.Layout layout) {
                int slot = targetSlot(event.x(), event.y());
                int row = templateRow(event.x(), event.y());
                if (slot >= 0 && row >= 0 && row < layout.slot(slot).size()
                        && (event.button() == InputConstants.MOUSE_BUTTON_LEFT
                        || event.button() == InputConstants.MOUSE_BUTTON_RIGHT)) {
                    layout.remove(slot, row);
                    playDownSound(minecraft.getSoundManager());
                    ClientCore.get().save();
                    rebuildWidgets();
                }
            }
            return true;
        }

        @Override
        public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
            return state.drag != null || search.isFocused() && search.mouseDragged(event, dragX, dragY);
        }

        @Override
        public boolean mouseReleased(MouseButtonEvent event) {
            if (state.drag == null) {
                return search.isFocused() && search.mouseReleased(event);
            }
            Preference preference = state.drag;
            int slot = targetSlot(event.x(), event.y());
            state.clearInteraction();
            if (slot >= 0 && setting instanceof InventoryPickerSetting.Layout layout) {
                layout.assign(slot, preference);
                playDownSound(minecraft.getSoundManager());
                ClientCore.get().save();
                rebuildWidgets();
            }
            return true;
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount,
                                     double verticalAmount) {
            if (state.category == null || mouseX < getX() || mouseX >= getX() + PICKER_WIDTH
                    || mouseY < gridY() || mouseY >= gridY() + PICKER_GRID_HEIGHT) {
                return false;
            }
            int rows = (filteredEntries().size() + PICKER_COLUMNS - 1) / PICKER_COLUMNS;
            int direction = verticalAmount > 0.0 ? -1 : verticalAmount < 0.0 ? 1 : 0;
            state.gridRow = Math.clamp(state.gridRow + direction, 0, Math.max(0, rows - PICKER_ROWS));
            return true;
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            if (search.isFocused() && search.keyPressed(event)) {
                return true;
            }
            if (event.isEscape() && search.isFocused()) {
                setSearchFocused(false);
                return true;
            }
            if (event.isEscape() && expanded()) {
                togglePicker(setting, panel);
                return true;
            }
            if (event.isConfirmation()) {
                if (expanded() && state.category != null && ready()) {
                    setSearchFocused(true);
                    return true;
                }
                togglePicker(setting, panel);
                return true;
            }
            return false;
        }

        @Override
        public boolean charTyped(CharacterEvent event) {
            return search.isFocused() && search.charTyped(event);
        }

        private void beginDrag(Preference preference) {
            state.drag = preference;
            state.dragStack = preferenceStack(preference);
        }

        private void setSearchFocused(boolean focused) {
            search.setFocused(focused);
            state.searchFocused = focused;
        }

        private void clearSearchFocusIfClipped() {
            if (search.isFocused() && (searchY() < panel.y + ROW_HEIGHT
                    || searchY() + ROW_HEIGHT > panel.y + panel.height)) {
                setSearchFocused(false);
            }
        }

        private InventoryPickerSetting.Category categoryAt(double mouseX, double mouseY) {
            if (mouseY < categoryY() || mouseY >= categoryY() + PICKER_CELL) {
                return null;
            }
            int localX = (int) mouseX - getX();
            if (localX < 0 || localX >= PICKER_WIDTH || localX % PICKER_STEP >= PICKER_CELL) {
                return null;
            }
            return InventoryPickerSetting.Category.values()[localX / PICKER_STEP];
        }

        private Entry entryAt(double mouseX, double mouseY) {
            if (state.category == null || mouseY < gridY() || mouseY >= gridY() + PICKER_GRID_HEIGHT) {
                return null;
            }
            int localX = (int) mouseX - getX();
            int localY = (int) mouseY - gridY();
            if (localX < 0 || localX >= PICKER_WIDTH || localX % PICKER_STEP >= PICKER_CELL
                    || localY % PICKER_STEP >= PICKER_CELL) {
                return null;
            }
            List<Entry> entries = filteredEntries();
            int index = (state.gridRow + localY / PICKER_STEP) * PICKER_COLUMNS
                    + localX / PICKER_STEP;
            return index < entries.size() ? entries.get(index) : null;
        }

        private int templateRow(double mouseX, double mouseY) {
            if (targetSlot(mouseX, mouseY) < 0) {
                return -1;
            }
            int localY = (int) mouseY - templateY();
            return localY % PICKER_STEP < PICKER_CELL ? localY / PICKER_STEP : -1;
        }

        @Override
        public void setFocused(boolean focused) {
            super.setFocused(focused);
            if (!focused) {
                setSearchFocused(false);
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            if (search.isFocused()) {
                search.updateNarration(output);
            } else {
                defaultButtonNarrationText(output);
            }
        }
    }

    private final class RangeComponent extends AbstractWidget {
        private final RangeSetting setting;
        private final boolean owner;
        private boolean upperThumb;

        private RangeComponent(RangeSetting setting, boolean owner, int x, int y, int width) {
            super(x, y, width, ROW_HEIGHT, Component.empty());
            this.setting = setting;
            this.owner = owner;
        }

        @Override
        public Component getMessage() {
            return settingMessage(setting.name() + ": " + setting.lower() + "–" + setting.upper() + " ms", owner);
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float tickDelta) {
            graphics.fill(getX(), getY(), getRight(), getBottom(), isHoveredOrFocused() ? HOVER : ROW);
            graphics.drawString(font, getMessage(), getX() + 4, getY() + 2, TEXT, false);
            int left = getX() + 4;
            int right = getRight() - 4;
            int lowerX = thumbX(setting.lower());
            int upperX = thumbX(setting.upper());
            graphics.fill(left, getBottom() - 4, right, getBottom() - 2, 0xFF59616B);
            graphics.fill(lowerX, getBottom() - 4, upperX + 1, getBottom() - 2, ACCENT);
            graphics.fill(lowerX - 1, getBottom() - 6, lowerX + 2, getBottom(), TEXT);
            graphics.fill(upperX - 1, getBottom() - 6, upperX + 2, getBottom(), TEXT);
            if (isFocused()) {
                graphics.renderOutline(getX(), getY(), getWidth(), getHeight(), ACCENT);
            }
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            double lowerDistance = Math.abs(event.x() - thumbX(setting.lower()));
            double upperDistance = Math.abs(event.x() - thumbX(setting.upper()));
            upperThumb = upperDistance < lowerDistance
                    || upperDistance == lowerDistance && event.x() >= thumbX(setting.upper());
            applyMouse(event.x());
        }

        @Override
        protected void onDrag(MouseButtonEvent event, double dragX, double dragY) {
            applyMouse(event.x());
        }

        @Override
        public void onRelease(MouseButtonEvent event) {
            ClientCore.get().save();
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            if (!isFocused()) {
                return false;
            }
            if (event.isUp() || event.isDown()) {
                upperThumb = event.isUp();
                return true;
            }
            int direction = event.isLeft() ? -1 : event.isRight() ? 1 : 0;
            if (direction == 0) {
                return false;
            }
            if (upperThumb) {
                setting.setUpper(setting.upper() + direction * setting.increment());
            } else {
                setting.setLower(setting.lower() + direction * setting.increment());
            }
            ClientCore.get().save();
            return true;
        }

        private void applyMouse(double mouseX) {
            int left = getX() + 4;
            int right = Math.max(left + 1, getRight() - 4);
            double value = Math.clamp((mouseX - left) / (right - left), 0.0, 1.0);
            int selected = (int) Math.round(setting.allowedMinimum()
                    + value * (setting.allowedMaximum() - setting.allowedMinimum()));
            if (upperThumb) {
                setting.setUpper(selected);
            } else {
                setting.setLower(selected);
            }
        }

        private int thumbX(int value) {
            int span = setting.allowedMaximum() - setting.allowedMinimum();
            if (span == 0) {
                return getX() + 4;
            }
            return getX() + 4 + (int) Math.round((getWidth() - 8)
                    * (value - setting.allowedMinimum()) / (double) span);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    private final class ColorPalette extends AbstractWidget {
        private static final int PADDING = 4;
        private static final int CELL = 14;

        private final ColorSetting setting;
        private int cursor;

        private ColorPalette(ColorSetting setting, int x, int y, int size) {
            super(x, y, size, size, Component.literal("Color palette"));
            this.setting = setting;
            cursor = 0;
            for (int index = 0; index < PALETTE.length; index++) {
                if (PALETTE[index] == setting.get()) {
                    cursor = index;
                    break;
                }
            }
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float tickDelta) {
            graphics.fill(getX(), getY(), getRight(), getBottom(), BACKGROUND);
            graphics.renderOutline(getX(), getY(), getWidth(), getHeight(), ACCENT);
            for (int index = 0; index < PALETTE.length; index++) {
                int x = getX() + PADDING + index % 4 * CELL;
                int y = getY() + PADDING + index / 4 * CELL;
                graphics.fill(x + 1, y + 1, x + CELL - 1, y + CELL - 1, PALETTE[index]);
                if (PALETTE[index] == setting.get()) {
                    graphics.renderOutline(x, y, CELL, CELL, TEXT);
                } else if (index == cursor) {
                    graphics.renderOutline(x, y, CELL, CELL, ACCENT);
                }
            }
        }

        private void choose(MouseButtonEvent event) {
            if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
                return;
            }
            int x = (int) event.x() - getX() - PADDING;
            int y = (int) event.y() - getY() - PADDING;
            if (x >= 0 && x < CELL * 4 && y >= 0 && y < CELL * 4) {
                select(y / CELL * 4 + x / CELL);
            }
        }

        private boolean handleKey(KeyEvent event) {
            if (event.isEscape()) {
                closePalette();
                return true;
            }
            if (event.isLeft()) {
                cursor = Math.floorMod(cursor - 1, PALETTE.length);
            } else if (event.isRight()) {
                cursor = Math.floorMod(cursor + 1, PALETTE.length);
            } else if (event.isUp()) {
                cursor = Math.floorMod(cursor - 4, PALETTE.length);
            } else if (event.isDown()) {
                cursor = Math.floorMod(cursor + 4, PALETTE.length);
            } else if (event.isConfirmation()) {
                select(cursor);
            } else {
                return false;
            }
            return true;
        }

        private void select(int index) {
            setting.set(PALETTE[index]);
            ClientCore.get().save();
            closePalette();
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
