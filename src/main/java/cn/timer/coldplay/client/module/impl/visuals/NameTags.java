package cn.timer.coldplay.client.module.impl.visuals;

import cn.timer.coldplay.client.gui.Draw;
import cn.timer.coldplay.client.util.HealthResolver;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.util.Arrays;

/**
 * Held items, armor and a real-health bar above an {@link EntityESP} target. The entity half is read
 * while its render state is extracted; the anchor is projected with the boxes and drawn on the HUD
 * pass. Every metric is in GUI pixels.
 */
final class NameTags {

    static final int ICON = 8;           // a 16px GUI icon at 0.5 scale
    static final int ICON_GAP = 1;
    static final int BAR_BORDER = 2;
    static final int BAR_INNER_H = 3;
    static final int BAR_H = BAR_INNER_H + BAR_BORDER * 2;
    static final int ROW_GAP = 2;        // icon row to bar
    static final int MIN_BAR_W = 60;

    /** Held items first, then armor boots to helmet; BODY carries wolf and horse armor. */
    private static final EquipmentSlot[] ICON_SLOTS = {
            EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
            EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD,
            EquipmentSlot.BODY
    };

    private static final ItemStack[] NO_ICONS = new ItemStack[0];

    private NameTags() {
    }

    /** One entity's tag contents, held on its render state for the rest of the frame. */
    record Tag(float healthFraction, boolean bar, ItemStack[] icons) {
        boolean isEmpty() {
            return !bar && icons.length == 0;
        }
    }

    /** A {@link Tag} placed at the anchor its bar's bottom edge sits on. */
    record Placed(int x, int y, Tag tag) {
    }

    /** The non-empty equipment to draw, in row order; a shared empty array when there is none. */
    static ItemStack[] icons(LivingEntity entity, boolean held, boolean armor) {
        if (!held && !armor) {
            return NO_ICONS;
        }
        ItemStack[] found = new ItemStack[ICON_SLOTS.length];
        int count = 0;
        for (EquipmentSlot slot : ICON_SLOTS) {
            if (!(slot.getType() == EquipmentSlot.Type.HAND ? held : armor)) {
                continue;
            }
            ItemStack stack = entity.getItemBySlot(slot);
            if (!stack.isEmpty()) {
                found[count++] = stack;
            }
        }
        return count == 0 ? NO_ICONS : Arrays.copyOf(found, count);
    }

    /** Scoreboard health over the entity's maximum, clamped; a sidebar may report more than the max. */
    static float healthFraction(LivingEntity entity) {
        float maximum = entity.getMaxHealth();
        return maximum > 0.0F ? Math.clamp(HealthResolver.resolve(entity) / maximum, 0.0F, 1.0F) : 0.0F;
    }

    static int rowWidth(int icons) {
        return icons > 0 ? icons * ICON + (icons - 1) * ICON_GAP : 0;
    }

    /** The bar spans the icon row when a full loadout outgrows the minimum. */
    static int barWidth(int icons) {
        return Math.max(rowWidth(icons), MIN_BAR_W);
    }

    static int width(Tag tag) {
        return tag.bar() ? barWidth(tag.icons().length) : rowWidth(tag.icons().length);
    }

    /** Height above the anchor: the bar, plus the icon row and its gap when there are icons. */
    static int height(Tag tag) {
        int height = tag.bar() ? BAR_H : 0;
        if (tag.icons().length > 0) {
            height += (tag.bar() ? ROW_GAP : 0) + ICON;
        }
        return height;
    }

    static int fillWidth(int barWidth, float fraction) {
        return Math.round((barWidth - BAR_BORDER * 2) * Math.clamp(fraction, 0.0F, 1.0F));
    }

    /** Full red at empty through full green at full, as the 1.8.9 client shaded it. */
    static int healthColor(float fraction) {
        float clamped = Math.clamp(fraction, 0.0F, 1.0F);
        return 0xFF000000 | Math.round(255 * (1.0F - clamped)) << 16 | Math.round(255 * clamped) << 8;
    }

    /** False when none of the tag would land on a screen of this size; bounds as {@link #draw} lays them out. */
    static boolean onScreen(int x, int y, Tag tag, int screenWidth, int screenHeight) {
        int tagWidth = width(tag);
        int left = x - tagWidth / 2;
        return left + tagWidth >= 0 && left <= screenWidth && y >= 0 && y - height(tag) <= screenHeight;
    }

    static void draw(GuiGraphics graphics, Placed placed, int borderColor) {
        Tag tag = placed.tag();
        int barTop = placed.y() - (tag.bar() ? BAR_H : 0);

        if (tag.icons().length > 0) {
            int iconTop = barTop - (tag.bar() ? ROW_GAP : 0) - ICON;
            int cursor = placed.x() - rowWidth(tag.icons().length) / 2;
            for (ItemStack stack : tag.icons()) {
                Draw.pushScale(graphics, cursor, iconTop, 0.5F);
                graphics.renderItem(stack, cursor, iconTop);
                Draw.popScale(graphics);
                cursor += ICON + ICON_GAP;
            }
        }

        if (tag.bar()) {
            int barW = barWidth(tag.icons().length);
            int left = placed.x() - barW / 2;
            // the unfilled interior stays clear, so only the border and the fill are drawn
            Draw.outline(graphics, left, barTop, left + barW, placed.y(), BAR_BORDER, borderColor);
            int fill = fillWidth(barW, tag.healthFraction());
            Draw.rectBounds(graphics, left + BAR_BORDER, barTop + BAR_BORDER,
                    left + BAR_BORDER + fill, placed.y() - BAR_BORDER, healthColor(tag.healthFraction()));
        }
    }
}
