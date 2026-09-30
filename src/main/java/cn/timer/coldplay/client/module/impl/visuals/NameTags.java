package cn.timer.coldplay.client.module.impl.visuals;

import cn.timer.coldplay.client.gui.Draw;
import cn.timer.coldplay.client.setting.BooleanSetting;
import cn.timer.coldplay.client.setting.NumberSetting;
import cn.timer.coldplay.client.util.HealthResolver;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.awt.Color;
import java.util.Arrays;

/** The Loadout Card above each target: name and distance, equipment with durability, and a health bar. GUI pixels. */
public final class NameTags extends EntityModule {

    static final int ICON = 16;
    static final int ICON_GAP = 5;
    static final int PAD_X = 10;
    static final int PAD_Y = 8;
    static final int GAP = 7;
    static final int HEADER_H = 9;
    static final int ICON_ROW_H = ICON + 4; // icon, 2px gap, 2px durability sliver
    static final int BAR_ROW_H = 9;
    static final int BAR_H = 5;
    static final int MIN_W = 150;

    private static final int PANEL = 0xB80E1015;
    private static final int BORDER = 0x29FFFFFF;
    private static final int TEXT = 0xFFF4F6F8;
    private static final int DIM = 0xBDF4F6F8;
    private static final int TRACK = 0x1FFFFFFF;
    private static final int GOLD = 0xFFF7C948;
    private static final int WORN = 0xFFFFB547;

    /** Held items first, then armor boots to helmet; BODY carries wolf and horse armor. */
    private static final EquipmentSlot[] ICON_SLOTS = {
            EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
            EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD,
            EquipmentSlot.BODY
    };

    private static final ItemStack[] NO_ICONS = new ItemStack[0];

    private final BooleanSetting items = addSetting(new BooleanSetting("Items", true));
    private final BooleanSetting armor = addSetting(new BooleanSetting("Armor", true));
    private final BooleanSetting health = addSetting(new BooleanSetting("Health", true));
    private final NumberSetting offset = addSetting(new NumberSetting("Offset", 0.6, 0.0, 2.0, 0.05));

    public NameTags() {
        super("NameTags", "Shows equipment and health above entities", false);
    }

    @Override
    protected void onRender2D(GuiGraphics graphics, DeltaTracker deltaTracker) {
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        boolean bar = health.get();
        double above = offset.get();
        int scale = Projection.guiScale();
        Font font = Minecraft.getInstance().font;
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().position();
        forEachTarget((entity, ignored) -> {
            Vec3 feet = entity.getPosition(partialTick);
            Tag tag = new Tag(entity.getName().getString(), entity.getTeamColor(),
                    Math.round(camera.distanceTo(feet)) + "m", healthFraction(entity),
                    HealthResolver.resolve(entity), entity.getAbsorptionAmount(), entity.getMaxHealth(), bar,
                    icons(entity, items.get(), armor.get()));
            Vec3 anchor = Projection.toGui(feet.add(0.0, entity.getBbHeight() + above, 0.0), width, height);
            if (anchor == null) {
                return;
            }
            double x = Projection.snap(anchor.x, scale);
            double y = Projection.snap(anchor.y, scale);
            int tagWidth = width(tag, font);
            if (onScreen((int) Math.round(x), (int) Math.round(y), tagWidth, height(tag), width, height)) {
                graphics.pose().pushMatrix();
                graphics.pose().translate((float) x, (float) y);
                draw(graphics, font, tag, tagWidth);
                graphics.pose().popMatrix();
            }
        });
    }

    /** One entity's card contents. */
    record Tag(String name, int team, String distance, float healthFraction, float health, float absorption,
               float maxHealth, boolean bar, ItemStack[] icons) {
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

    static int width(Tag tag, Font font) {
        int header = 10 + font.width(tag.name()) + 8 + font.width(tag.distance());
        return Math.max(MIN_W, Math.max(header, rowWidth(tag.icons().length)) + 2 * PAD_X);
    }

    /** Height above the anchor: the header, plus the icon row and the bar row when present. */
    static int height(Tag tag) {
        int height = 2 * PAD_Y + HEADER_H;
        if (tag.icons().length > 0) {
            height += GAP + ICON_ROW_H;
        }
        if (tag.bar()) {
            height += GAP + BAR_ROW_H;
        }
        return height;
    }

    /** Hue 0 (red) at empty through 130 (green) at full, as the design shades it (HSL .75/.58 in HSB). */
    static int healthColor(float fraction) {
        return Color.HSBtoRGB(130.0F / 360.0F * Math.clamp(fraction, 0.0F, 1.0F), 0.704F, 0.895F);
    }

    /** False when none of the card would land on a screen of this size. */
    static boolean onScreen(int x, int y, int tagWidth, int tagHeight, int screenWidth, int screenHeight) {
        int left = x - tagWidth / 2;
        return left + tagWidth >= 0 && left <= screenWidth && y >= 0 && y - tagHeight <= screenHeight;
    }

    /** Draws the card centred on x = 0 with its bottom edge on y = 0. */
    static void draw(GuiGraphics graphics, Font font, Tag tag, int width) {
        int left = -width / 2;
        int top = -height(tag);
        Draw.roundedRect(graphics, left, top, width, -top, 10, BORDER);
        Draw.roundedRect(graphics, left + 1, top + 1, width - 2, -top - 2, 9, PANEL);

        int x = left + PAD_X;
        int right = left + width - PAD_X;
        int y = top + PAD_Y;
        Draw.rect(graphics, x, y + 2, 5, 5, 0xFF000000 | tag.team());
        Draw.text(graphics, font, tag.name(), x + 10, y + 1, TEXT);
        Draw.text(graphics, font, tag.distance(), right - font.width(tag.distance()), y + 1, DIM);
        y += HEADER_H;

        if (tag.icons().length > 0) {
            y += GAP;
            int cursor = x;
            for (ItemStack stack : tag.icons()) {
                graphics.renderItem(stack, cursor, y);
                if (stack.isDamageableItem()) {
                    float left01 = 1.0F - stack.getDamageValue() / (float) stack.getMaxDamage();
                    Draw.rect(graphics, cursor + 2, y + ICON + 2, 12, 2, TRACK);
                    Draw.rect(graphics, cursor + 2, y + ICON + 2, Math.round(12 * left01), 2,
                            left01 >= 0.4F ? 0xD1F4F6F8 : WORN);
                }
                cursor += ICON + ICON_GAP;
            }
            y += ICON_ROW_H;
        }

        if (tag.bar()) {
            y += GAP;
            int color = healthColor(tag.healthFraction());
            String hp = String.format("%.1f", tag.health());
            String extra = tag.absorption() > 0 ? "+" + Math.round(tag.absorption()) : "";
            int textW = font.width(hp) + (extra.isEmpty() ? 0 : 2 + font.width(extra));
            int barW = right - x - 7 - textW;
            int barY = y + (BAR_ROW_H - BAR_H) / 2;
            float total = Math.max(tag.maxHealth(), 1.0F) + tag.absorption();
            int fill = Math.round(barW * Math.clamp(tag.health() / total, 0.0F, 1.0F));
            int gold = Math.min(barW - fill, Math.round(barW * tag.absorption() / total));
            Draw.rect(graphics, x, barY, barW, BAR_H, TRACK);
            Draw.rect(graphics, x, barY, fill, BAR_H, color);
            Draw.rect(graphics, x + fill, barY, gold, BAR_H, GOLD);
            int textX = right - textW;
            Draw.text(graphics, font, hp, textX, y + 1, color);
            if (!extra.isEmpty()) {
                Draw.text(graphics, font, extra, textX + font.width(hp) + 2, y + 1, GOLD);
            }
        }
    }
}
