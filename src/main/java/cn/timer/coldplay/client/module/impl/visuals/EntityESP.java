package cn.timer.coldplay.client.module.impl.visuals;

import cn.timer.coldplay.client.module.Category;
import cn.timer.coldplay.client.module.Module;
import cn.timer.coldplay.client.module.impl.combat.AntiBot;
import cn.timer.coldplay.client.setting.BooleanSetting;
import cn.timer.coldplay.client.setting.ColorSetting;
import cn.timer.coldplay.client.setting.ModeSetting;
import cn.timer.coldplay.client.setting.NumberSetting;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.Npc;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public final class EntityESP extends Module {
    public static final String TWO_D = "2D";
    public static final String OUTLINE = "Outline";
    public static final String CHAMS = "Chams";
    public static final RenderStateDataKey<Integer> COLOR =
            RenderStateDataKey.create(() -> "coldplay:entity_esp_color");

    private static final double NEAR_PLANE = 0.05;

    private final NumberSetting range = addOwnerSetting(new NumberSetting("Range", 64.0, 1.0, 128.0, 1.0));
    private final BooleanSetting players = addOwnerSetting(new BooleanSetting("Players", true));
    private final ColorSetting playersColor = addChildSetting(players, new ColorSetting("Color", 0xFF55FFFF));
    private final BooleanSetting mobs = addOwnerSetting(new BooleanSetting("Mobs", false));
    private final ColorSetting mobsColor = addChildSetting(mobs, new ColorSetting("Color", 0xFFFF5555));
    private final BooleanSetting animals = addOwnerSetting(new BooleanSetting("Animals", false));
    private final ColorSetting animalsColor = addChildSetting(animals, new ColorSetting("Color", 0xFF55FF55));
    private final BooleanSetting invisibles = addOwnerSetting(new BooleanSetting("Invisibles", false));
    private final ColorSetting invisiblesColor = addChildSetting(invisibles, new ColorSetting("Color", 0xFFAA55FF));
    private final BooleanSetting npc = addOwnerSetting(new BooleanSetting("NPC", false));
    private final ColorSetting npcColor = addChildSetting(npc, new ColorSetting("Color", 0xFFFFAA00));
    private final BooleanSetting esp = addOwnerSetting(new BooleanSetting("ESP", true));
    private final ModeSetting mode = addChildSetting(esp,
            new ModeSetting("Mode", TWO_D, TWO_D, OUTLINE, CHAMS));

    private final List<ScreenBox> boxes = new ArrayList<>();

    public EntityESP() {
        super("EntityESP", "Highlights valid entities", Category.VISUALS, GLFW.GLFW_KEY_UNKNOWN);
    }

    public void extractRenderState(LivingEntity entity, LivingEntityRenderState state) {
        Integer color = colorFor(entity);
        state.setData(COLOR, color);
        if (color == null) {
            return;
        }
        if (uses(OUTLINE)) {
            state.outlineColor = color;
        } else if (uses(CHAMS) && entity.isInvisible()) {
            state.isInvisible = false;
            state.isInvisibleToPlayer = false;
        }
    }

    public boolean usesChams() {
        return uses(CHAMS);
    }

    public void extract2D(WorldExtractionContext context) {
        boxes.clear();
        Minecraft minecraft = Minecraft.getInstance();
        if (!uses(TWO_D) || minecraft.player == null || minecraft.level == null) {
            return;
        }

        int width = minecraft.getWindow().getGuiScaledWidth();
        int height = minecraft.getWindow().getGuiScaledHeight();
        for (EntityRenderState state : context.worldState().entityRenderStates) {
            Integer color = state.getData(COLOR);
            if (color == null) {
                continue;
            }
            ScreenBox box = projectBounds(context.gameRenderer(), context.camera(), state, width, height, color);
            if (box != null) {
                boxes.add(box);
            }
        }
    }

    public void render2D(GuiGraphics graphics, DeltaTracker ignored) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!uses(TWO_D) || minecraft.player == null || minecraft.level == null) {
            boxes.clear();
            return;
        }
        for (ScreenBox box : boxes) {
            graphics.renderOutline(box.left(), box.top(), box.width(), box.height(), box.color());
        }
    }

    @Override
    protected void onDisable() {
        boxes.clear();
    }

    private boolean uses(String selectedMode) {
        return enabled() && esp.get() && mode.get().equals(selectedMode);
    }

    private Integer colorFor(LivingEntity entity) {
        Minecraft minecraft = Minecraft.getInstance();
        Player localPlayer = minecraft.player;
        if (!enabled() || localPlayer == null || minecraft.level == null
                || entity.isRemoved() || !EntitySelector.LIVING_ENTITY_STILL_ALIVE.test(entity)
                || entity == localPlayer && !minecraft.gameRenderer.getMainCamera().isDetached()
                || !inRange(localPlayer.distanceToSqr(entity), range.get())) {
            return null;
        }

        BooleanSetting filter;
        ColorSetting color;
        if (entity instanceof Player player) {
            boolean fakePlayer = AntiBot.get().isBot(player);
            filter = fakePlayer ? npc : players;
            color = fakePlayer ? npcColor : playersColor;
        } else if (entity instanceof Npc) {
            filter = npc;
            color = npcColor;
        } else if (entity instanceof Enemy) {
            filter = mobs;
            color = mobsColor;
        } else if (entity instanceof Animal) {
            filter = animals;
            color = animalsColor;
        } else {
            return null;
        }

        if (!filter.get()) {
            return null;
        }
        if (entity.isInvisible()) {
            return invisibles.get() ? invisiblesColor.get() : null;
        }
        return color.get();
    }

    static boolean inRange(double distanceSquared, double range) {
        return distanceSquared <= range * range;
    }

    private static ScreenBox projectBounds(GameRenderer renderer, Camera camera, EntityRenderState state,
                                           int width, int height, int color) {
        double halfWidth = state.boundingBoxWidth * 0.5;
        double minX = state.x - halfWidth;
        double maxX = state.x + halfWidth;
        double minY = state.y;
        double maxY = state.y + state.boundingBoxHeight;
        double minZ = state.z - halfWidth;
        double maxZ = state.z + halfWidth;
        Vec3 cameraPosition = camera.position();
        Vector3fc forward = camera.forwardVector();

        double screenMinX = Double.POSITIVE_INFINITY;
        double screenMinY = Double.POSITIVE_INFINITY;
        double screenMaxX = Double.NEGATIVE_INFINITY;
        double screenMaxY = Double.NEGATIVE_INFINITY;
        for (int corner = 0; corner < 8; corner++) {
            double x = (corner & 1) == 0 ? minX : maxX;
            double y = (corner & 2) == 0 ? minY : maxY;
            double z = (corner & 4) == 0 ? minZ : maxZ;
            double depth = (x - cameraPosition.x) * forward.x()
                    + (y - cameraPosition.y) * forward.y()
                    + (z - cameraPosition.z) * forward.z();
            if (depth <= NEAR_PLANE) {
                continue;
            }

            Vec3 projected = renderer.projectPointToScreen(new Vec3(x, y, z));
            double screenX = (projected.x + 1.0) * width * 0.5;
            double screenY = (1.0 - projected.y) * height * 0.5;
            if (!Double.isFinite(screenX) || !Double.isFinite(screenY)) {
                continue;
            }
            screenMinX = Math.min(screenMinX, screenX);
            screenMinY = Math.min(screenMinY, screenY);
            screenMaxX = Math.max(screenMaxX, screenX);
            screenMaxY = Math.max(screenMaxY, screenY);
        }
        return clipBounds(screenMinX, screenMinY, screenMaxX, screenMaxY, width, height, color);
    }

    static ScreenBox clipBounds(double minX, double minY, double maxX, double maxY,
                                int screenWidth, int screenHeight, int color) {
        if (!Double.isFinite(minX) || !Double.isFinite(minY)
                || !Double.isFinite(maxX) || !Double.isFinite(maxY)
                || maxX < 0.0 || maxY < 0.0 || minX > screenWidth || minY > screenHeight) {
            return null;
        }
        int left = (int) Math.floor(Math.clamp(minX, 0.0, screenWidth));
        int top = (int) Math.floor(Math.clamp(minY, 0.0, screenHeight));
        int right = (int) Math.ceil(Math.clamp(maxX, 0.0, screenWidth));
        int bottom = (int) Math.ceil(Math.clamp(maxY, 0.0, screenHeight));
        return right > left && bottom > top ? new ScreenBox(left, top, right, bottom, color) : null;
    }

    record ScreenBox(int left, int top, int right, int bottom, int color) {
        int width() {
            return right - left;
        }

        int height() {
            return bottom - top;
        }
    }
}
