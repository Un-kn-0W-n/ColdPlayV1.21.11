package cn.timer.coldplay.client.module.impl.combat;

import cn.timer.coldplay.client.module.Category;
import cn.timer.coldplay.client.module.Module;
import cn.timer.coldplay.client.setting.BooleanSetting;
import cn.timer.coldplay.client.setting.RangeSetting;
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
 */
public final class TriggerBot extends Module {
    private final BooleanSetting players = addSetting(new BooleanSetting("Players", true));
    private final BooleanSetting mobs = addSetting(new BooleanSetting("Mobs", false));
    private final BooleanSetting animals = addSetting(new BooleanSetting("Animals", false));
    private final BooleanSetting invisible = addSetting(new BooleanSetting("Invisible", false));
    private final BooleanSetting npc = addSetting(new BooleanSetting("NPC", false));
    private final BooleanSetting cooldown = addSetting(new BooleanSetting("Cooldown", true));
    private final RangeSetting delay = addSetting(new RangeSetting("Delay", 0, 0, 0, 500, 10));

    private long nextAttackAt;

    public TriggerBot() {
        super("TriggerBot", "Attacks the entity under the crosshair", Category.COMBAT,
                GLFW.GLFW_KEY_UNKNOWN);
    }

    @Override
    protected void onEnable() {
        nextAttackAt = System.nanoTime();
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
        if (cooldown.get() && player.getAttackStrengthScale(0.0F) < 1.0F) {
            return;
        }

        long now = System.nanoTime();
        if (now - nextAttackAt < 0L) {
            return;
        }
        KeyMapping attack = minecraft.options.keyAttack;
        // Unbound resolves to InputConstants.UNKNOWN, which every other unbound mapping shares.
        if (attack.isUnbound()) {
            return;
        }
        KeyMapping.click(KeyBindingHelper.getBoundKeyOf(attack));
        nextAttackAt = now + delay.sampleMillis() * 1_000_000L;
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
