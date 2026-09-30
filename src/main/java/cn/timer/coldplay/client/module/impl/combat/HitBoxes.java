package cn.timer.coldplay.client.module.impl.combat;

import cn.timer.coldplay.client.manager.RotationManager;
import cn.timer.coldplay.client.module.Category;
import cn.timer.coldplay.client.module.Module;
import cn.timer.coldplay.client.setting.ModeSetting;
import cn.timer.coldplay.client.setting.NumberSetting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/**
 * Vanilla's crosshair pick inflates every hitbox by the entity's pick radius, so widening that
 * radius widens the hitbox. Silent also turns the server rotation onto the real box whenever only
 * the widened one is under the crosshair, so the attack goes out with a look that really hits.
 */
public final class HitBoxes extends Module {
    private static final String VANILLA = "Vanilla";
    private static final String SILENT = "Silent";
    private static final int ROTATION_PRIORITY = 50; // below KillAura
    private static final double TURN_RATE = 180.0;
    // The server look is snapped to the mouse quantum, up to half of 0.6144 degrees per axis at full
    // sensitivity: about 0.03 blocks at 6 blocks. Aiming this far inside keeps the snapped look on the box.
    static final double INSET = 0.05;
    private static final HitBoxes INSTANCE = new HitBoxes();

    private final ModeSetting mode = addSetting(new ModeSetting("Mode", VANILLA, VANILLA, SILENT));
    private final NumberSetting expand = addSetting(new NumberSetting("Expand", 0.2, 0.05, 1.0, 0.05));

    private HitBoxes() {
        super("HitBoxes", "Expands entity hitboxes", Category.COMBAT, GLFW.GLFW_KEY_UNKNOWN);
    }

    public static HitBoxes get() {
        return INSTANCE;
    }

    /** Client entities only: in singleplayer the integrated server reads the same pick radius. */
    public float expansion(Entity entity) {
        return enabled() && entity instanceof LivingEntity && entity.level().isClientSide()
                ? expand.get().floatValue() : 0.0F;
    }

    @Override
    protected void onRender(DeltaTracker ignored) {
        LocalPlayer player = Minecraft.getInstance().player;
        // Vanilla's own pick along the camera look, which already sees the widened boxes.
        if (player == null || !SILENT.equals(mode.get())
                || !(player.raycastHitResult(1.0F, player) instanceof EntityHitResult hit)
                || !(hit.getEntity() instanceof LivingEntity)) {
            return;
        }

        AABB box = hit.getEntity().getBoundingBox();
        Vec3 eyes = player.getEyePosition();
        Vec3 end = eyes.add(player.getLookAngle().scale(player.entityInteractionRange()));
        if (box.contains(eyes) || box.clip(eyes, end).isPresent()) {
            return; // the real look already hits
        }

        Vec2 rotation = KillAura.rotationTo(aimPoint(box, hit.getLocation()).subtract(eyes));
        RotationManager.getInstance().request(this, rotation.y, rotation.x, ROTATION_PRIORITY, TURN_RATE);
    }

    /** The point of the real box nearest to where the look enters the widened one. */
    static Vec3 aimPoint(AABB box, Vec3 hit) {
        AABB inner = box.deflate(INSET);
        return new Vec3(Mth.clamp(hit.x, inner.minX, inner.maxX), Mth.clamp(hit.y, inner.minY, inner.maxY),
                Mth.clamp(hit.z, inner.minZ, inner.maxZ));
    }
}
