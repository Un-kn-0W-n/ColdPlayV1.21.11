package cn.timer.coldplay.client.hud;

import cn.timer.coldplay.client.ClientCore;
import cn.timer.coldplay.client.gui.Draw;
import cn.timer.coldplay.client.module.impl.visuals.Hud;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/** Pill bars for golden apple health, health, armor and hunger, drawn in place of the vanilla icon rows. */
public final class StatusBars {

    private static final int BAR_W = 90;
    private static final int BAR_H = 11;
    private static final int GAP = 2;
    private static final int LOWER_ROW = 46; // bar top, up from the screen bottom
    private static final int XP_RAIL = 29;   // rail top, up from the screen bottom
    private static final int XP_RAIL_H = 2;
    private static final int BUBBLE = 9;

    private static final int TRACK = 0xF01C1C1C; // hotbar panel
    private static final int ABSORPTION = 0xFFDEBB19;
    private static final int HEALTH = 0xFF6CD981;
    private static final int ARMOR = 0xFF449DF0;
    private static final int HUNGER = 0xFFFFA752;
    private static final int ICON = 0xEBFFFFFF;
    private static final int ICON_DETAIL = 0x47000000;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int XP_TRACK = 0xB3101014;
    private static final int XP_FILL = 0xFFB9B9C2;
    private static final int XP_TEXT = 0xFFB4B4BE;

    // 7x7 icons: X is the icon, D a darker detail
    private static final String[] APPLE = {
            "....XX.", "...X...", ".XXXXX.", "XXXXXXX", "XXXXXXX", "XXXXXXX", ".XX.XX."};
    private static final String[] HEART = {
            ".XX.XX.", "XXXXXXX", "XXXXXXX", "XXXXXXX", ".XXXXX.", "..XXX..", "...X..."};
    private static final String[] SHIELD = {
            "XXXXXXX", "XXXDXXX", "XXXDXXX", "XXXDXXX", ".XXDXX.", "..XDX..", "...X..."};
    private static final String[] FOOD = {
            "..XXXX.", ".XXXXXX", ".XXXXXX", ".XXXXXX", "..XXXX.", ".XX....", "XX....."};

    private static float absorptionPeak;
    private static boolean experienceRendered;

    private StatusBars() {
    }

    /** True while the HUD module is on and its Status Bars setting is enabled. */
    public static boolean active() {
        ClientCore core = ClientCore.get();
        if (!core.initialized() || core.modules() == null) {
            return false;
        }
        Hud hud = core.modules().get(Hud.class);
        return hud != null && hud.statusBarsActive();
    }

    /** Returns false when the frame should be left to vanilla (no player camera, or damage disabled). */
    public static boolean render(GuiGraphics graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.getCameraEntity() instanceof Player player)
                || minecraft.gameMode == null || !minecraft.gameMode.canHurtPlayer()) {
            return false;
        }
        int left = graphics.guiWidth() / 2 - 91;
        int right = left + BAR_W + GAP;
        int lower = graphics.guiHeight() - LOWER_ROW;
        int upper = lower - BAR_H - GAP;

        float absorption = player.getAbsorptionAmount();
        absorptionPeak = trackAbsorption(absorptionPeak, absorption);
        boolean golden = absorption > 0.0F;
        float health = player.getHealth();
        int armor = player.getArmorValue();
        int food = player.getFoodData().getFoodLevel();
        boolean mounted = player.getVehicle() instanceof LivingEntity; // vanilla shows mount hearts there

        if (golden) {
            bar(graphics, left, upper, absorption / absorptionPeak, ABSORPTION);
        }
        bar(graphics, left, lower, health / Math.max(1.0F, player.getMaxHealth()), HEALTH);
        bar(graphics, right, upper, armor / 20.0F, ARMOR);
        if (!mounted) {
            bar(graphics, right, lower, food / 20.0F, HUNGER);
        }

        if (golden) {
            icon(graphics, APPLE, left, upper);
        }
        icon(graphics, HEART, left, lower);
        icon(graphics, SHIELD, right, upper);
        if (!mounted) {
            icon(graphics, FOOD, right, lower);
        }

        Font font = minecraft.font;
        if (golden) {
            label(graphics, font, (int) Math.ceil(absorption), left, upper);
        }
        label(graphics, font, (int) Math.ceil(health), left, lower);
        label(graphics, font, armor, right, upper);
        if (!mounted) {
            label(graphics, font, food, right, lower);
        }

        int maxAir = player.getMaxAirSupply();
        int air = Math.min(player.getAirSupply(), maxAir);
        if (player.isEyeInFluid(FluidTags.WATER) || air < maxAir) {
            // vanilla bubbles, right-aligned on a row above the armor bar
            int full = (int) Math.ceil((air - 2) * 10.0D / 300.0D);
            int popping = (int) Math.ceil(air * 10.0D / 300.0D) - full;
            int y = upper - GAP - BUBBLE;
            Identifier bubble = Identifier.withDefaultNamespace("hud/air");
            Identifier bursting = Identifier.withDefaultNamespace("hud/air_bursting");
            for (int i = 0; i < full + popping; i++) {
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, i < full ? bubble : bursting,
                        right + BAR_W - i * 8 - BUBBLE, y, BUBBLE, BUBBLE);
            }
        }
        return true;
    }

    /** Level number and a thin rail in place of the vanilla XP bar. */
    public static boolean renderExperience(GuiGraphics graphics, Player player) {
        if (player == null) {
            return false;
        }
        Font font = Minecraft.getInstance().font;
        int left = graphics.guiWidth() / 2 - 91;
        int right = left + BAR_W * 2 + GAP;
        int railTop = graphics.guiHeight() - XP_RAIL;
        int railLeft = left;
        if (player.experienceLevel > 0) {
            String level = String.valueOf(player.experienceLevel);
            Draw.textShadow(graphics, font, level, left, railTop + XP_RAIL_H / 2 - Draw.CAP_H / 2, XP_TEXT);
            railLeft += font.width(level) + 3;
        }
        if (player.getXpNeededForNextLevel() > 0) {
            int fill = Math.round((right - railLeft) * Math.clamp(player.experienceProgress, 0.0F, 1.0F));
            Draw.rectBounds(graphics, railLeft, railTop, right, railTop + XP_RAIL_H, XP_TRACK);
            Draw.rectBounds(graphics, railLeft, railTop, railLeft + fill, railTop + XP_RAIL_H, XP_FILL);
        }
        experienceRendered = true;
        return true;
    }

    /** True once per frame after {@link #renderExperience} drew, so the vanilla level text stays hidden. */
    public static boolean consumeExperienceRendered() {
        boolean rendered = experienceRendered;
        experienceRendered = false;
        return rendered;
    }

    /** Top edge of the bars, a row higher while air bubbles show. */
    public static int stackTop(GuiGraphics graphics, Player player) {
        int top = graphics.guiHeight() - LOWER_ROW - BAR_H - GAP;
        if (player != null && player.isEyeInFluid(FluidTags.WATER)) {
            top -= GAP + BUBBLE;
        }
        return top;
    }

    /** The golden apple bar drains against the most absorption held since it was last empty. */
    static float trackAbsorption(float peak, float absorption) {
        return absorption > 0.0F ? Math.max(peak, absorption) : 0.0F;
    }

    /** A non-empty fill never drops below {@code minWidth}, so its rounded ends survive. */
    static int fillWidth(float fraction, int width, int minWidth) {
        if (!(fraction > 0.0F)) {
            return 0;
        }
        return Math.max(minWidth, Math.round(Math.min(1.0F, fraction) * width));
    }

    private static void bar(GuiGraphics graphics, int x, int y, float fraction, int color) {
        Draw.roundedRect(graphics, x, y, BAR_W, BAR_H, BAR_H / 2.0, TRACK);
        int fill = fillWidth(fraction, BAR_W, BAR_H);
        if (fill > 0) {
            Draw.roundedRect(graphics, x, y, fill, BAR_H, BAR_H / 2.0, color);
        }
    }

    private static void icon(GuiGraphics graphics, String[] rows, int barX, int barY) {
        int left = barX + 3;
        int top = barY + (BAR_H - rows.length) / 2;
        for (int row = 0; row < rows.length; row++) {
            String line = rows[row];
            int start = 0;
            while (start < line.length()) {
                char c = line.charAt(start);
                int end = start + 1;
                while (end < line.length() && line.charAt(end) == c) {
                    end++;
                }
                if (c != '.') {
                    Draw.rectBounds(graphics, left + start, top + row, left + end, top + row + 1,
                            c == 'D' ? ICON_DETAIL : ICON);
                }
                start = end;
            }
        }
    }

    private static void label(GuiGraphics graphics, Font font, int value, int barX, int barY) {
        String text = String.valueOf(value);
        Draw.textShadow(graphics, font, text, barX + (BAR_W - font.width(text)) / 2,
                barY + (BAR_H - Draw.CAP_H) / 2, TEXT);
    }
}
