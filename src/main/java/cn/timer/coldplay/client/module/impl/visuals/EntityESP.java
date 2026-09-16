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
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.ARGB;
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
    private static final RenderStateDataKey<NameTags.Tag> NAME_TAG =
            RenderStateDataKey.create(() -> "coldplay:entity_esp_name_tag");

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
    // Appended after the ESP group: SettingsPanel groups a child under the owner it follows, and
    // Module.suffix() takes the first mode setting, which must stay the ESP one.
    private final BooleanSetting nameTags = addOwnerSetting(new BooleanSetting("NameTags", false));
    private final BooleanSetting tagItems = addChildSetting(nameTags, new BooleanSetting("Items", true));
    private final BooleanSetting tagArmor = addChildSetting(nameTags, new BooleanSetting("Armor", true));
    private final BooleanSetting tagHealth = addChildSetting(nameTags, new BooleanSetting("Health", true));
    // The vanilla nameplate spans the head plus 0.275 to 0.5 blocks, so the 1.8.9 client's 0.25
    // would put the bar on top of the name. 0.6 clears it; lower values overlap on purpose.
    private final NumberSetting tagOffset = addChildSetting(nameTags,
            new NumberSetting("Offset", 0.6, 0.0, 2.0, 0.05));
    private final ColorSetting tagBorder = addChildSetting(nameTags, new ColorSetting("Border", 0xFF000000));
    private final BooleanSetting tracers = addOwnerSetting(new BooleanSetting("Tracers", false));
    private final NumberSetting tracerWidth = addChildSetting(tracers,
            new NumberSetting("Width", 2.0, 0.5, 5.0, 0.5));
    // Bottoms out at 5 rather than 0: ColorSetting pins the alpha to FF, so this is the only
    // handle on line opacity and an enabled-but-invisible tracer would just look broken.
    private final NumberSetting tracerOpacity = addChildSetting(tracers,
            new NumberSetting("Opacity", 255.0, 5.0, 255.0, 5.0));

    private final List<ScreenBox> boxes = new ArrayList<>();
    private final List<NameTags.Placed> tags = new ArrayList<>();
    /** What view bobbing was before Tracers took it, or null while the vanilla value is untouched. */
    private Boolean bobViewBeforeTracers;

    public EntityESP() {
        super("EntityESP", "Highlights valid entities", Category.VISUALS, GLFW.GLFW_KEY_UNKNOWN);
    }

    public void extractRenderState(LivingEntity entity, LivingEntityRenderState state) {
        Integer color = colorFor(entity);
        state.setData(COLOR, color);
        // Equipment and scoreboard health are only reachable from the entity, so they are read here
        // and carried on the state; the HUD pass never touches the world.
        state.setData(NAME_TAG, color == null ? null : extractNameTag(entity));
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

    private NameTags.Tag extractNameTag(LivingEntity entity) {
        if (!usesNameTags()) {
            return null;
        }
        boolean bar = tagHealth.get();
        NameTags.Tag tag = new NameTags.Tag(bar ? NameTags.healthFraction(entity) : 0.0F, bar,
                NameTags.icons(entity, tagItems.get(), tagArmor.get()));
        return tag.isEmpty() ? null : tag;
    }

    public void extract2D(WorldExtractionContext context) {
        boxes.clear();
        tags.clear();
        Minecraft minecraft = Minecraft.getInstance();
        boolean wantBoxes = uses(TWO_D);
        boolean wantTags = usesNameTags();
        if (!(wantBoxes || wantTags) || minecraft.player == null || minecraft.level == null) {
            return;
        }

        int width = minecraft.getWindow().getGuiScaledWidth();
        int height = minecraft.getWindow().getGuiScaledHeight();
        for (EntityRenderState state : context.worldState().entityRenderStates) {
            Integer color = state.getData(COLOR);
            if (color == null) {
                continue;
            }
            if (wantBoxes) {
                ScreenBox box = projectBounds(context.gameRenderer(), context.camera(), state, width, height, color);
                if (box != null) {
                    boxes.add(box);
                }
            }
            NameTags.Tag tag = wantTags ? state.getData(NAME_TAG) : null;
            if (tag != null) {
                NameTags.Placed placed = projectTag(context.gameRenderer(), context.camera(), state,
                        tag, width, height);
                if (placed != null) {
                    tags.add(placed);
                }
            }
        }
    }

    public void render2D(GuiGraphics graphics, DeltaTracker ignored) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            boxes.clear();
            tags.clear();
            return;
        }
        if (uses(TWO_D)) {
            for (ScreenBox box : boxes) {
                graphics.renderOutline(box.left(), box.top(), box.width(), box.height(), box.color());
            }
        }
        if (usesNameTags()) {
            int border = tagBorder.get();
            for (NameTags.Placed placed : tags) {
                NameTags.draw(graphics, placed, border);
            }
        }
    }

    /**
     * Tracers ride the world pass instead of the 2D one: the vanilla gizmo collector already clips
     * the far end against the near plane, so a target behind the camera still yields a line running
     * off the correct screen edge, which is the whole point of a tracer. The 2D lists cover only
     * what survived frustum culling, so they are no use here.
     */
    @Override
    protected void onRender(DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        Player localPlayer = minecraft.player;
        boolean active = tracers.get();
        // Ahead of the world guards: the toggle is what drives bobbing, not whether a frame drew.
        syncViewBobbing(active, minecraft.options.bobView());
        if (!active || localPlayer == null || minecraft.level == null) {
            return;
        }

        Camera camera = minecraft.gameRenderer.getMainCamera();
        Vec3 origin = tracerOrigin(camera.position(), camera.forwardVector());
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
        float width = tracerWidth.get().floatValue();
        double opacity = tracerOpacity.get();
        // The box query is only a coarse prefilter; colorFor() applies the real spherical range.
        for (LivingEntity entity : minecraft.level.getEntitiesOfClass(LivingEntity.class,
                localPlayer.getBoundingBox().inflate(range.get()))) {
            Integer color = colorFor(entity);
            if (color != null) {
                // Interpolated, so the far end does not jitter between ticks.
                Vec3 target = tracerTarget(entity.getPosition(partialTick), entity.getBbHeight());
                Gizmos.line(origin, target, tracerColor(color, opacity), width).setAlwaysOnTop();
            }
        }
    }

    /**
     * A line that starts at the camera projects to a single point, so the origin sits one block
     * down the view axis: that lands it on the crosshair whatever the field of view, and gives the
     * line a real screen-space length.
     */
    static Vec3 tracerOrigin(Vec3 cameraPosition, Vector3fc forward) {
        return cameraPosition.add(forward.x(), forward.y(), forward.z());
    }

    /** Aims at the middle of the entity rather than its feet. */
    static Vec3 tracerTarget(Vec3 feet, float height) {
        return feet.add(0.0, height * 0.5, 0.0);
    }

    /** Below 255 the gizmo collector routes the line to the translucent pass. */
    static int tracerColor(int color, double opacity) {
        return ARGB.color((int) Math.round(opacity), color);
    }

    /**
     * Head bob swings the camera, and every tracer is anchored to it, so the whole fan sweeps with
     * the walk cycle. Turning tracers on forces bobbing off and remembers what it was; turning them
     * off hands it back, but only if it was on to begin with. A value the player switched on
     * themselves mid-session is therefore left alone rather than stamped back over.
     */
    private void syncViewBobbing(boolean tracersActive, OptionInstance<Boolean> bobView) {
        Boolean next = nextViewBobbing(tracersActive, bobView.get());
        if (next != null) {
            bobView.set(next);
        }
    }

    /**
     * The whole decision, split out because OptionInstance.set reaches for the Minecraft singleton
     * and so cannot run outside the game. Returns what bobbing must become, or null to leave it be.
     */
    Boolean nextViewBobbing(boolean tracersActive, boolean currentBobView) {
        if (tracersActive) {
            if (bobViewBeforeTracers != null) {
                return null;
            }
            bobViewBeforeTracers = currentBobView;
            return Boolean.FALSE;
        }
        if (bobViewBeforeTracers == null) {
            return null;
        }
        boolean restore = bobViewBeforeTracers;
        bobViewBeforeTracers = null;
        return restore ? Boolean.TRUE : null;
    }

    @Override
    protected void onDisable() {
        boxes.clear();
        tags.clear();
        // onRender stops firing once the module is off, so this is the only chance to give it back.
        syncViewBobbing(false, Minecraft.getInstance().options.bobView());
    }

    private boolean uses(String selectedMode) {
        return enabled() && esp.get() && mode.get().equals(selectedMode);
    }

    /** Independent of the ESP modes, so tags also show alongside Outline and Chams. */
    private boolean usesNameTags() {
        return enabled() && nameTags.get();
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

    /**
     * True when the point is far enough in front of the camera to project. Behind the camera the
     * perspective divide flips the sign, so the result still lands inside the viewport.
     */
    private static boolean inFront(Camera camera, double x, double y, double z) {
        Vec3 position = camera.position();
        Vector3fc forward = camera.forwardVector();
        return (x - position.x) * forward.x()
                + (y - position.y) * forward.y()
                + (z - position.z) * forward.z() > NEAR_PLANE;
    }

    /** Normalized device coordinates to GUI pixels. */
    static double screenX(double ndcX, int screenWidth) {
        return (ndcX + 1.0) * screenWidth * 0.5;
    }

    static double screenY(double ndcY, int screenHeight) {
        return (1.0 - ndcY) * screenHeight * 0.5;
    }

    private NameTags.Placed projectTag(GameRenderer renderer, Camera camera, EntityRenderState state,
                                       NameTags.Tag tag, int width, int height) {
        double x = state.x;
        double y = state.y + state.boundingBoxHeight + tagOffset.get();
        double z = state.z;
        if (!inFront(camera, x, y, z)) {
            return null;
        }
        Vec3 projected = renderer.projectPointToScreen(new Vec3(x, y, z));
        double anchorX = screenX(projected.x, width);
        double anchorY = screenY(projected.y, height);
        if (!Double.isFinite(anchorX) || !Double.isFinite(anchorY)) {
            return null;
        }
        int tagX = (int) Math.round(anchorX);
        int tagY = (int) Math.round(anchorY);
        return NameTags.onScreen(tagX, tagY, tag, width, height) ? new NameTags.Placed(tagX, tagY, tag) : null;
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

        double screenMinX = Double.POSITIVE_INFINITY;
        double screenMinY = Double.POSITIVE_INFINITY;
        double screenMaxX = Double.NEGATIVE_INFINITY;
        double screenMaxY = Double.NEGATIVE_INFINITY;
        for (int corner = 0; corner < 8; corner++) {
            double x = (corner & 1) == 0 ? minX : maxX;
            double y = (corner & 2) == 0 ? minY : maxY;
            double z = (corner & 4) == 0 ? minZ : maxZ;
            if (!inFront(camera, x, y, z)) {
                continue;
            }

            Vec3 projected = renderer.projectPointToScreen(new Vec3(x, y, z));
            double pointX = screenX(projected.x, width);
            double pointY = screenY(projected.y, height);
            if (!Double.isFinite(pointX) || !Double.isFinite(pointY)) {
                continue;
            }
            screenMinX = Math.min(screenMinX, pointX);
            screenMinY = Math.min(screenMinY, pointY);
            screenMaxX = Math.max(screenMaxX, pointX);
            screenMaxY = Math.max(screenMaxY, pointY);
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
