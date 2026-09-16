package cn.timer.coldplay.client.module.impl.combat;

import cn.timer.coldplay.client.module.Category;
import cn.timer.coldplay.client.module.Module;
import cn.timer.coldplay.client.setting.BooleanSetting;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.Npc;
import net.minecraft.world.entity.player.Player;
import org.lwjgl.glfw.GLFW;

/**
 * Queues an attack click instead of attacking, so vanilla's own startAttack performs the hit. That
 * is what keeps the miss lockout, the spear stab path, the item reach component and the packet
 * order right without restating any of them here.
 *
 * <p>The swing is held until it is worth the most: vanilla scales base damage by
 * {@code 0.2 + charge * charge * 0.8} and only allows a critical above nine tenths of charge, so a
 * spammed hit lands for a fifth of a timed one.
 */
public final class TriggerBot extends Module {
    private final BooleanSetting players = addSetting(new BooleanSetting("Players", true));
    private final BooleanSetting mobs = addSetting(new BooleanSetting("Mobs", false));
    private final BooleanSetting animals = addSetting(new BooleanSetting("Animals", false));
    private final BooleanSetting invisible = addSetting(new BooleanSetting("Invisible", false));
    private final BooleanSetting npc = addSetting(new BooleanSetting("NPC", false));

    public TriggerBot() {
        super("TriggerBot", "Attacks the entity under the crosshair", Category.COMBAT,
                GLFW.GLFW_KEY_UNKNOWN);
    }

    /**
     * Driven from the head of the client tick: the click queued here is the one vanilla drains in
     * handleKeybinds later in the same tick, against the crosshair pick it refreshes in between.
     */
    public void tick(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (!enabled() || player == null || minecraft.level == null || minecraft.screen != null
                || minecraft.getOverlay() != null || !minecraft.isWindowActive()
                || !minecraft.mouseHandler.isMouseGrabbed()) {
            return;
        }

        // crosshairPickEntity is written beside hitResult by the same pick, so it already carries
        // vanilla reach, the blocks in between and whatever rotation RotationManager is spoofing.
        if (!(minecraft.crosshairPickEntity instanceof LivingEntity target)
                || !WTap.validTarget(player, target)
                || !allowed(target, players.get(), mobs.get(), animals.get(), npc.get())
                || (!invisible.get() && target.isInvisibleTo(player))) {
            return;
        }

        // 0.5F is the partial tick Player.baseDamageScaleFactor itself reads, so this is full
        // damage on the exact tick the server will agree it is, not one tick later.
        if (player.getAttackStrengthScale(0.5F) < 1.0F) {
            return;
        }
        if (risingIntoCrit(!player.onGround(), player.getDeltaMovement().y, player.fallDistance,
                critAvailable(player))) {
            return;
        }

        KeyMapping attack = minecraft.options.keyAttack;
        // Unbound resolves to InputConstants.UNKNOWN, which every other unbound mapping shares.
        if (attack.isUnbound()) {
            return;
        }
        KeyMapping.click(KeyBindingHelper.getBoundKeyOf(attack));
    }

    /** Everything canCriticalAttack asks for that a jump will not change on its own. */
    private static boolean critAvailable(LocalPlayer player) {
        return !player.isSprinting() && !player.onClimbable() && !player.isInWater()
                && !player.isPassenger() && !player.isMobilityRestricted();
    }

    /**
     * A critical needs the player already falling, so a fully charged swing taken on the way up is
     * worth holding: the same hit lands for half again as much a few ticks later. Only an ascent is
     * waited on, never a hover, so the wait is bounded by the arc and the module cannot stall.
     */
    static boolean risingIntoCrit(boolean airborne, double verticalSpeed, double fallDistance,
                                  boolean critAvailable) {
        return critAvailable && airborne && fallDistance <= 0.0 && verticalSpeed > 0.0;
    }

    /** Free of Minecraft state so the category gate itself is testable. */
    static boolean allowed(Entity entity, boolean players, boolean mobs, boolean animals, boolean npc) {
        if (entity instanceof Player) {
            return players;
        }
        if (entity instanceof Enemy) {
            return mobs;
        }
        if (entity instanceof Animal) {
            return animals;
        }
        return entity instanceof Npc && npc;
    }
}
