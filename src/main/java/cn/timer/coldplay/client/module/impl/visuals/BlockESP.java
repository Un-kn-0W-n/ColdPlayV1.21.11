package cn.timer.coldplay.client.module.impl.visuals;

import cn.timer.coldplay.client.module.Category;
import cn.timer.coldplay.client.module.Module;
import cn.timer.coldplay.client.setting.BooleanSetting;
import cn.timer.coldplay.client.setting.ColorSetting;
import cn.timer.coldplay.client.setting.NumberSetting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.lwjgl.glfw.GLFW;

import java.util.HashSet;
import java.util.Set;

public final class BlockESP extends Module {
    private final NumberSetting range = addSetting(new NumberSetting("Range", 64.0, 16.0, 256.0, 16.0));
    private final NumberSetting opacity = addSetting(new NumberSetting("Opacity", 80.0, 0.0, 255.0, 5.0));
    private final NumberSetting width = addSetting(new NumberSetting("Width", 2.0, 0.0, 5.0, 0.5));

    private final BooleanSetting chests = addOwnerSetting(new BooleanSetting("Chests", true));
    private final ColorSetting chestsColor = addChildSetting(chests, new ColorSetting("Color", 0xFFC8AA00));

    private final Set<Long> drawnPairs = new HashSet<>();

    public BlockESP() {
        super("BlockESP", "Highlights chests through walls", Category.VISUALS, GLFW.GLFW_KEY_UNKNOWN);
    }

    @Override
    protected void onRender(DeltaTracker ignored) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (level == null || player == null || !chests.get()) {
            return;
        }

        GizmoStyle style = style(chestsColor.get(), width.get(), opacity.get());
        double maxRange = range.get();
        int radius = chunkRadius(maxRange);
        int centreX = SectionPos.blockToSectionCoord(player.getBlockX());
        int centreZ = SectionPos.blockToSectionCoord(player.getBlockZ());
        drawnPairs.clear();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                LevelChunk chunk = level.getChunkSource().getChunk(centreX + dx, centreZ + dz, ChunkStatus.FULL, false);
                if (chunk == null) {
                    continue;
                }

                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    if (!(blockEntity instanceof ChestBlockEntity) || blockEntity.isRemoved()) {
                        continue;
                    }
                    BlockPos pos = blockEntity.getBlockPos();
                    if (!EntityESP.inRange(player.distanceToSqr(Vec3.atCenterOf(pos)), maxRange)) {
                        continue;
                    }
                    AABB box = chestBox(level, pos, blockEntity.getBlockState(), drawnPairs);
                    if (box != null) {
                        Gizmos.cuboid(box, style).setAlwaysOnTop();
                    }
                }
            }
        }
    }


    static int chunkRadius(double range) {
        return Mth.ceil(range / 16.0);
    }


    static GizmoStyle style(int color, double width, double opacity) {
        float strokeWidth = (float) width;
        int alpha = (int) Math.round(opacity);
        return alpha == 0
                ? GizmoStyle.stroke(color, strokeWidth)
                : GizmoStyle.strokeAndFill(color, strokeWidth, ARGB.color(alpha, color));
    }

    static AABB chestBox(BlockGetter level, BlockPos pos, BlockState state, Set<Long> drawnPairs) {
        AABB own = bounds(level, pos, state);
        ChestType type = state.hasProperty(ChestBlock.TYPE) ? state.getValue(ChestBlock.TYPE) : ChestType.SINGLE;
        if (own == null || type == ChestType.SINGLE) {
            return own;
        }
        BlockPos partnerPos = ChestBlock.getConnectedBlockPos(pos, state);
        if (!drawnPairs.add(Math.min(pos.asLong(), partnerPos.asLong()))) {
            return null;
        }
        AABB partner = bounds(level, partnerPos, state.setValue(ChestBlock.TYPE, type.getOpposite()));
        return partner == null ? own : own.minmax(partner);
    }
    
    static AABB bounds(BlockGetter level, BlockPos pos, BlockState state) {
        VoxelShape shape = state.getShape(level, pos);
        return shape.isEmpty() ? null : shape.bounds().move(pos);
    }
}
