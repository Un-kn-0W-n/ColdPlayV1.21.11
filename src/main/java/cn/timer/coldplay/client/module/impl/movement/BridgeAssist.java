package cn.timer.coldplay.client.module.impl.movement;

import cn.timer.coldplay.client.module.Category;
import cn.timer.coldplay.client.module.Module;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/**
 * Sneaks for the fewest ticks that still keep the player on the bridge. Sneaking is the slow part of
 * bridging, so every tick of it is spent as late as it can be: the ring below is vanilla's own hang
 * limit and nothing more, and the tap ends on the first tick the block underfoot makes it pointless.
 */
public final class BridgeAssist extends Module {
    static final double SKIN = 1.0E-7;
    /**
     * The ring reach, and the only one there is. The probe box is the whole 0.6-wide footprint, so
     * 0.3 is exactly the reach that reads as empty once the player is over the drop and not a step
     * before — the latest a sneak can start and still be a sneak the player is already owed.
     */
    static final double HANG_PROBE = 0.3;
    static final double TRAVEL_LEAD = 0.1;
    static final double PROBE_STEP = 0.02;

    private static final double DIAGONAL = Math.sqrt(0.5);
    private static final double[][] DIRECTIONS = {
            {1.0, 0.0}, {-1.0, 0.0}, {0.0, 1.0}, {0.0, -1.0},
            {DIAGONAL, DIAGONAL}, {-DIAGONAL, DIAGONAL},
            {DIAGONAL, -DIAGONAL}, {-DIAGONAL, -DIAGONAL}
    };

    @FunctionalInterface
    interface FloorProbe {
        boolean canFallAt(double offsetX, double offsetZ);
    }

    private LocalPlayer owner;
    private boolean bridging;

    public BridgeAssist() {
        super("BridgeAssist", "Sneaks at block edges while holding blocks", Category.MOVEMENT,
                GLFW.GLFW_KEY_UNKNOWN);
    }

    public boolean wantsSneak(LocalPlayer player, ClientLevel level, Input input) {
        if (!enabled()) {
            return false;
        }
        if (player != owner) { // respawn, dimension change or logout: never the same bridge
            owner = player;
            clear();
        }
        if (player == null || level == null || !player.onGround()) {
            clear();
            return false;
        }

        ItemStack held = player.getMainHandItem();
        FloorProbe probe = floorProbe(player, level);
        Vec3 motion = player.getDeltaMovement();
        double speed = Math.sqrt(motion.x * motion.x + motion.z * motion.z);

        // Two questions, and only two: am I over the drop already, and would this stride put me
        // there. Neither grows a margin the player would have to walk off at sneak speed.
        return step(held.getItem() instanceof BlockItem,
                held.isEmpty(),
                atAnyEdge(probe) || edgeAhead(probe, motion.x, motion.z, speed),
                movingForward(input));
    }

    /**
     * The whole cross-tick decision. The sneak lasts exactly as long as the edge does: the tick a
     * block lands under the feet there is floor again, and holding the key past that only spends the
     * run-up to the next block at a third of walking speed.
     */
    boolean step(boolean holdingBlock, boolean handEmpty, boolean atEdge, boolean movingForward) {
        // An emptied hand is the last block being placed; anything else ends the bridge.
        if (!holdingBlock && (!bridging || !handEmpty)) {
            clear();
            return false;
        }
        bridging = holdingBlock || atEdge;
        return atEdge && !movingForward;
    }

    @Override
    protected void onDisable() {
        clear();
    }

    private void clear() {
        bridging = false;
    }

    /** Vanilla's Player.canFallAtLeast, reimplemented because it is private. */
    private FloorProbe floorProbe(LocalPlayer player, ClientLevel level) {
        AABB box = player.getBoundingBox();
        double depth = player.maxUpStep();
        return (offsetX, offsetZ) -> {
            // Unloaded terrain reads as air, which would report a ledge at the edge of the render distance.
            if (!level.isLoaded(BlockPos.containing(
                    player.getX() + offsetX, box.minY - depth, player.getZ() + offsetZ))) {
                return false;
            }
            return level.noCollision(player, floorSlab(box, offsetX, offsetZ, depth));
        };
    }

    static AABB floorSlab(AABB box, double offsetX, double offsetZ, double depth) {
        return new AABB(
                box.minX + SKIN + offsetX, box.minY - depth - SKIN, box.minZ + SKIN + offsetZ,
                box.maxX - SKIN + offsetX, box.minY, box.maxZ - SKIN + offsetZ);
    }

    /** Standing over the drop, whichever way it lies: the sneak the player is already owed. */
    static boolean atAnyEdge(FloorProbe probe) {
        for (double[] direction : DIRECTIONS) {
            if (probe.canFallAt(direction[0] * HANG_PROBE, direction[1] * HANG_PROBE)) {
                return true;
            }
        }
        return false;
    }

    /**
     * About to be over it: the stride just reported, marched out to the furthest one this tick could
     * still turn into. Nothing beyond that, or the player creeps the difference.
     */
    static boolean edgeAhead(FloorProbe probe, double dirX, double dirZ, double speed) {
        int steps = Math.max(1, (int) Math.ceil(TRAVEL_LEAD / PROBE_STEP));
        for (int step = 0; step <= steps; step++) {
            if (edgeAlong(probe, dirX, dirZ, speed + TRAVEL_LEAD * step / steps)) {
                return true;
            }
        }
        return false;
    }

    static boolean edgeAlong(FloorProbe probe, double dirX, double dirZ, double lookahead) {
        double length = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (length < 1.0E-4) {
            return false;
        }
        return probe.canFallAt(dirX / length * lookahead, dirZ / length * lookahead);
    }

    static float impulse(boolean positive, boolean negative) {
        return positive == negative ? 0.0F : positive ? 1.0F : -1.0F;
    }

    static boolean movingForward(Input input) {
        return impulse(input.forward(), input.backward()) > 0.0F;
    }

    public static Input withShift(Input input) {
        return new Input(input.forward(), input.backward(), input.left(), input.right(), input.jump(),
                true, input.sprint());
    }
}
