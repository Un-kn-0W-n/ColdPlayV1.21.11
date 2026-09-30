package cn.timer.coldplay.client.module.impl.visuals;

import cn.timer.coldplay.client.module.Category;
import cn.timer.coldplay.client.module.Module;
import cn.timer.coldplay.client.setting.ColorSetting;
import cn.timer.coldplay.client.setting.ModeSetting;
import cn.timer.coldplay.client.setting.NumberSetting;
import cn.timer.coldplay.client.util.Gizmo;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BedBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.AABB;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class BedESP extends Module {
    private static final String CARD = "Card";
    private static final String GLOW = "Glow";
    private static final int GLOW_LAYERS = 3;
    private static final double GLOW_STEP = 0.05; // blocks each halo shell reaches past the last
    private static final int GLASS_SWATCH = 0x66FFFFFF; // glass and a few others have no map colour

    private final ModeSetting mode = addSetting(new ModeSetting("Mode", CARD, CARD, GLOW));
    private final NumberSetting range = addSetting(new NumberSetting("Range", 64.0, 16.0, 256.0, 16.0));
    private final NumberSetting opacity = addSetting(new NumberSetting("Opacity", 80.0, 0.0, 255.0, 5.0));
    private final NumberSetting width = addSetting(new NumberSetting("Width", 2.0, 0.0, 5.0, 0.5));
    private final ColorSetting color = addSetting(new ColorSetting("Color", 0xFFFF3C3C));
    private final List<ChestESP.Label> labels = new ArrayList<>();
    private final Map<ChestESP.Key, ChestESP.Raster> rasters = new HashMap<>();

    public BedESP() {
        super("BedESP", "Highlights beds through walls", Category.VISUALS, GLFW.GLFW_KEY_UNKNOWN);
    }

    @Override
    protected void onRender(DeltaTracker ignored) {
        labels.clear();
        boolean glow = mode.get().equals(GLOW);
        int rgb = color.get();
        int alpha = (int) Math.round(opacity.get());
        ChestESP.draw(BedBlockEntity.class, BedESP::box, range.get(), Gizmo.style(rgb, width.get(), opacity.get()),
                (state, box) -> {
                    if (glow) {
                        // The gizmo pass has no blur, so fading shells stand in for the glow.
                        for (int layer = 1; layer <= GLOW_LAYERS; layer++) {
                            Gizmos.cuboid(box.inflate(GLOW_STEP * layer),
                                    GizmoStyle.fill(ARGB.color(alpha / (layer + 1), rgb))).setAlwaysOnTop();
                        }
                    } else {
                        labels.add(new ChestESP.Label(box.getCenter().with(Direction.Axis.Y, box.maxY),
                                state.getBlock(), state.getBlock().getName().getString(),
                                defense(Minecraft.getInstance().level, box)));
                    }
                });
    }

    public void render2D(GuiGraphics graphics, DeltaTracker ignored) {
        ChestESP.drawLabels(graphics, enabled() ? labels : List.of(), rasters);
    }

    /** A bed is one box, drawn from its head; the foot draws nothing. */
    static AABB box(BlockPos head, BlockState state) {
        if (state.getValue(BedBlock.PART) != BedPart.HEAD) {
            return null;
        }
        BlockPos foot = head.relative(BedBlock.getConnectedDirection(state));
        return ChestESP.bounds(head, state).minmax(ChestESP.bounds(foot, state));
    }

    /** The two blocks a bed's defense uses most, within 3 blocks out and 3 up, as the 1.8.9 card counted. */
    static List<ChestESP.Cover> defense(BlockGetter level, AABB bed) {
        // getBlockStates floors an inclusive max, so maxX + 2 is the third column past the bed.
        return level.getBlockStates(new AABB(bed.minX - 3, bed.minY, bed.minZ - 3,
                        bed.maxX + 2, bed.minY + 3, bed.maxZ + 2))
                .filter(state -> !state.isAir() && !(state.getBlock() instanceof BedBlock))
                .collect(Collectors.groupingBy(BlockState::getBlock, Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<Block, Long>comparingByValue().reversed())
                .limit(2)
                .map(entry -> new ChestESP.Cover(swatch(entry.getKey()), entry.getKey().getName().getString(),
                        entry.getValue()))
                .toList();
    }

    static int swatch(Block block) {
        int rgb = block.defaultMapColor().col;
        return rgb == 0 ? GLASS_SWATCH : 0xFF000000 | rgb;
    }
}
