package cn.timer.coldplay.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.util.StringUtil;

/** Drawing and key handling (append, backspace, clear, paste) for the themed text fields. */
public final class TextField {
    private TextField() {
    }

    public static void draw(GuiGraphics graphics, Font font, int x, int y, int width, int height,
                            String value, boolean focused, String placeholder, int cursorCounter) {
        draw(graphics, font, x, y, width, height, value, focused, placeholder, cursorCounter, false);
    }

    /** {@code masked} shows one asterisk per character, for tokens and passwords. */
    public static void draw(GuiGraphics graphics, Font font, int x, int y, int width, int height,
                            String value, boolean focused, String placeholder, int cursorCounter,
                            boolean masked) {
        Draw.borderedRect(graphics, x, y, x + width, y + height, Theme.WELL,
                focused ? Theme.FROST : Theme.SEP);

        String safeValue = value == null ? "" : value;
        boolean showPlaceholder = safeValue.isEmpty() && !focused;
        String shown = tail(font, masked ? "*".repeat(safeValue.length()) : safeValue, width - 6);
        int textY = Draw.textY(y, height);
        Draw.text(graphics, font, showPlaceholder ? placeholder : shown, x + 3, textY,
                showPlaceholder ? Theme.TEXT_DIM : Theme.TEXT);

        if (focused && cursorCounter / 6 % 2 == 0) {
            int caretX = x + 3 + font.width(shown);
            Draw.rect(graphics, caretX, y + 2, 1, height - 4, Theme.FROST);
        }
    }

    /** Longest suffix that fits, so the caret stays visible. */
    private static String tail(Font font, String value, int maxWidth) {
        int used = 0;
        for (int i = value.length(); i > 0; i--) {
            used += font.width(value.substring(i - 1, i));
            if (used > maxWidth) {
                return value.substring(i);
            }
        }
        return value;
    }

    /** Non-character keys: escape drops focus, backspace trims, delete clears, paste appends. */
    public static EditResult keyPressed(String current, KeyEvent event, int maxLength) {
        String value = current == null ? "" : current;
        if (event.isEscape()) {
            return new EditResult(value, false, false);
        }
        String edited = value;
        if (event.key() == InputConstants.KEY_BACKSPACE) {
            if (!edited.isEmpty()) {
                edited = edited.substring(0, edited.length() - 1);
            }
        } else if (event.key() == InputConstants.KEY_DELETE) {
            edited = "";
        } else if (event.isPaste()) {
            edited += StringUtil.filterText(Minecraft.getInstance().keyboardHandler.getClipboard());
        }
        return result(value, edited, maxLength);
    }

    public static EditResult charTyped(String current, CharacterEvent event, int maxLength) {
        String value = current == null ? "" : current;
        String edited = event.isAllowedChatCharacter() ? value + event.codepointAsString() : value;
        return result(value, edited, maxLength);
    }

    private static EditResult result(String original, String edited, int maxLength) {
        if (maxLength >= 0 && edited.length() > maxLength) {
            edited = edited.substring(0, maxLength);
        }
        return new EditResult(edited, true, !edited.equals(original));
    }

    public record EditResult(String value, boolean focused, boolean changed) {
    }
}
