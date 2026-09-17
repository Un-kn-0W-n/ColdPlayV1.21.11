package cn.timer.coldplay.client.module.impl.movement;

import cn.timer.coldplay.client.module.Category;
import cn.timer.coldplay.client.module.Module;
import cn.timer.coldplay.client.setting.NumberSetting;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.concurrent.ThreadLocalRandom;

public final class BridgeAssist extends Module {
    static final double SKIN = 1.0E-7;
    static final double MIN_PROBE = 0.3;
    static final int MIN_HOLD_TICKS = 2;
    static final int MAX_HOLD_TICKS = 3;
    static final double TRAVEL_LEAD = 0.1;
    static final double PROBE_STEP = 0.05;

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

    private final NumberSetting edgeDistance = addSetting(
            new NumberSetting("EdgeDistance", MIN_PROBE, MIN_PROBE, 0.5, 0.01));

    private LocalPlayer owner;
    private boolean bridging;
    private int holdTicks;

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
        // The ring has to grow with speed: at a fixed radius it straddles gaps a fast player falls into.
        double reach = Math.max(edgeDistance.get(), speed + TRAVEL_LEAD);

        return step(held.getItem() instanceof BlockItem,
                held.isEmpty(),
                atAnyEdge(probe, reach) || edgeAhead(probe, motion.x, motion.z, speed),
                movingForward(input),
                ThreadLocalRandom.current().nextInt(MIN_HOLD_TICKS, MAX_HOLD_TICKS + 1));
    }

    boolean step(boolean holdingBlock, boolean handEmpty, boolean atEdge, boolean movingForward,
                 int pulse) {
        // An emptied hand is the last block being placed; anything else ends the bridge.
        if (!holdingBlock && (!bridging || !handEmpty)) {
            clear();
            return false;
        }
        // An owed sneak keeps the session alive through the pulse.
        bridging = holdingBlock || atEdge || holdTicks > 0;
        if (movingForward || !(atEdge || holdTicks > 0)) {
            holdTicks = 0; // stepping forward ends the pulse
            return false;
        }
        holdTicks = atEdge ? pulse : holdTicks - 1;
        return true;
    }

    @Override
    protected void onDisable() {
        clear();
    }

    private void clear() {
        bridging = false;
        holdTicks = 0;
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

    static boolean atAnyEdge(FloorProbe probe, double lookahead) {
        double reach = Math.max(lookahead, MIN_PROBE);
        for (double[] direction : DIRECTIONS) {
            if (probe.canFallAt(direction[0] * reach, direction[1] * reach)) {
                return true;
            }
        }
        return false;
    }

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
