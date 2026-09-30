package cn.timer.coldplay.client.module.impl.visuals;

import cn.timer.coldplay.client.module.Category;
import cn.timer.coldplay.client.module.Module;
import cn.timer.coldplay.client.module.impl.combat.AntiBot;
import cn.timer.coldplay.client.setting.BooleanSetting;
import cn.timer.coldplay.client.setting.ColorSetting;
import cn.timer.coldplay.client.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ARGB;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import org.lwjgl.glfw.GLFW;

import java.util.function.ObjIntConsumer;

/** Range plus the Players/Mobs/Animals/Invisibles filter every entity visual shares. */
abstract class EntityModule extends Module {
    static final int WHITE = 0xFFFFFFFF;

    private final NumberSetting range = addSetting(new NumberSetting("Range", 64.0, 1.0, 128.0, 1.0));
    private final Kind players;
    private final Kind mobs;
    private final Kind animals;
    private final Kind invisibles;

    /** {@code colored} gives every kind its own Color child, for the modules that draw in it. */
    protected EntityModule(String name, String description, boolean colored) {
        super(name, description, Category.VISUALS, GLFW.GLFW_KEY_UNKNOWN);
        players = kind("Players", true, colored, 0xFF55FFFF);
        mobs = kind("Mobs", false, colored, 0xFFFF5555);
        animals = kind("Animals", false, colored, 0xFF55FF55);
        invisibles = kind("Invisibles", false, colored, 0xFFAA55FF);
    }

    /** The colour this module draws the entity in, or null when it does not show it. */
    final Integer colorOf(LivingEntity entity) {
        Kind kind = kindOf(entity);
        if (kind == null) {
            return null;
        }
        return kind.color() == null ? WHITE : kind.color().get();
    }

    /** Every entity this module shows, with its colour, straight off the level. */
    protected final void forEachTarget(ObjIntConsumer<LivingEntity> action) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        // The box query is only a coarse prefilter; kindOf() applies the real spherical range.
        for (LivingEntity entity : minecraft.level.getEntitiesOfClass(LivingEntity.class,
                minecraft.player.getBoundingBox().inflate(range.get()))) {
            Integer color = colorOf(entity);
            if (color != null) {
                action.accept(entity, color);
            }
        }
    }

    /** ColorSetting pins alpha to FF, so opacity is the only handle on it. */
    static int withOpacity(int color, double opacity) {
        return ARGB.color((int) Math.round(opacity), color);
    }

    private Kind kindOf(LivingEntity entity) {
        // First, and before the singleton: the ESP mixin asks for every entity every frame, on or off.
        if (!enabled()) {
            return null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Player self = minecraft.player;
        double reach = range.get();
        if (self == null || minecraft.level == null
                || entity.isRemoved() || !EntitySelector.LIVING_ENTITY_STILL_ALIVE.test(entity)
                || entity == self && !minecraft.gameRenderer.getMainCamera().isDetached()
                || self.distanceToSqr(entity) > reach * reach) {
            return null;
        }

        // Enemy ahead of Animal, so a hoglin counts as a mob. AntiBot's fakes are never shown.
        Kind kind;
        if (entity instanceof Player player) {
            kind = AntiBot.get().isBot(player) ? null : players;
        } else if (entity instanceof Enemy) {
            kind = mobs;
        } else if (entity instanceof Animal) {
            kind = animals;
        } else {
            kind = null;
        }

        if (kind == null || !kind.shown().get()) {
            return null;
        }
        if (entity.isInvisible()) {
            return invisibles.shown().get() ? invisibles : null;
        }
        return kind;
    }

    private Kind kind(String name, boolean shown, boolean colored, int color) {
        BooleanSetting toggle = addOwnerSetting(new BooleanSetting(name, shown));
        return new Kind(toggle, colored ? addChildSetting(toggle, new ColorSetting("Color", color)) : null);
    }

    /** One filter toggle and, on a coloured module, the colour it draws in. */
    private record Kind(BooleanSetting shown, ColorSetting color) {
    }
}
