package cn.timer.coldplay.client.module.impl.visuals;

import cn.timer.coldplay.client.module.Category;
import cn.timer.coldplay.client.module.Module;
import cn.timer.coldplay.client.module.impl.combat.AntiBot;
import cn.timer.coldplay.client.setting.BooleanSetting;
import cn.timer.coldplay.client.setting.ColorSetting;
import cn.timer.coldplay.client.setting.ModeSetting;
import cn.timer.coldplay.client.setting.NumberSetting;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.GuiElementRenderState;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.Npc;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3x2f;
import org.joml.Vector3fc;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public final class EntityESP extends Module {
    public static final String TWO_D = "2D";
    public static final String OUTLINE = "Outline";
    public static final String CHAMS = "Chams";
    public static final String LINES = "Lines";
    public static final String ARROWS = "Arrows";
    public static final RenderStateDataKey<Integer> COLOR =
            RenderStateDataKey.create(() -> "coldplay:entity_esp_color");
    private static final RenderStateDataKey<NameTags.Tag> NAME_TAG =
            RenderStateDataKey.create(() -> "coldplay:entity_esp_name_tag");

    private static final double NEAR_PLANE = 0.05;
    /** Where an arrow's colour is still fully red, and where it has faded all the way to black. */
    private static final double FADE_NEAR = 5.0;
    private static final double FADE_FAR = 15.0;

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
    // A second mode setting is safe here only because it trails the ESP one: suffix() reports the
    // first, and ConfigManager keys children as "Tracers.Mode", clear of "ESP.Mode".
    private final ModeSetting tracerMode = addChildSetting(tracers,
            new ModeSetting("Mode", LINES, LINES, ARROWS));
    private final NumberSetting tracerWidth = addChildSetting(tracers,
            new NumberSetting("Width", 2.0, 0.5, 5.0, 0.5));
    // Bottoms out at 5 rather than 0: ColorSetting pins the alpha to FF, so this is the only
    // handle on line opacity and an enabled-but-invisible tracer would just look broken.
    private final NumberSetting tracerOpacity = addChildSetting(tracers,
            new NumberSetting("Opacity", 255.0, 5.0, 255.0, 5.0));
    private final NumberSetting arrowSize = addChildSetting(tracers,
            new NumberSetting("Size", 6.0, 3.0, 16.0, 1.0));
    // Far enough out that the ring clears the vanilla crosshair, which spans 15px.
    private final NumberSetting arrowRadius = addChildSetting(tracers,
            new NumberSetting("Radius", 30.0, 10.0, 120.0, 1.0));

    private final List<ScreenBox> boxes = new ArrayList<>();
    private final List<ScreenArrow> arrows = new ArrayList<>();
    private final List<NameTags.Placed> tags = new ArrayList<>();
    /** What view bobbing was before Lines took it, or null while the vanilla value is untouched. */
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
        arrows.clear();
        tags.clear();
        Minecraft minecraft = Minecraft.getInstance();
        boolean wantBoxes = uses(TWO_D);
        boolean wantTags = usesNameTags();
        boolean wantArrows = usesTracer(ARROWS);
        if (!(wantBoxes || wantTags || wantArrows) || minecraft.player == null || minecraft.level == null) {
            return;
        }

        int width = minecraft.getWindow().getGuiScaledWidth();
        int height = minecraft.getWindow().getGuiScaledHeight();
        if (wantArrows) {
            extractArrows(context, minecraft.player, width, height);
        }
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

    /**
     * Arrows are collected from the world rather than from the render states the boxes use: the
     * states are what survived frustum culling, and an off-screen entity is exactly what did not.
     * The box query is only a coarse prefilter; colorFor() applies the real spherical range.
     */
    private void extractArrows(WorldExtractionContext context, Player localPlayer, int width, int height) {
        Camera camera = context.camera();
        Vec3 eye = camera.position();
        float partialTick = context.tickCounter().getGameTimeDeltaPartialTick(false);
        for (LivingEntity entity : context.world().getEntitiesOfClass(LivingEntity.class,
                localPlayer.getBoundingBox().inflate(range.get()))) {
            // colorFor() is only the filter here, not the palette: an arrow is coloured by how far
            // away its target is, so the type colours the boxes and lines use do not apply.
            if (colorFor(entity) == null) {
                continue;
            }
            // Interpolated, so the bearing does not step between ticks.
            Vec3 feet = entity.getPosition(partialTick);
            double halfWidth = entity.getBbWidth() * 0.5;
            // A hitbox that still lands on screen needs no arrow, and projectBox() returns null for
            // one that is off the viewport or wholly behind the camera: that is the same question.
            // Only whether it projects matters, so the box it would have drawn is thrown away.
            if (projectBox(context.gameRenderer(), camera,
                    feet.x - halfWidth, feet.y, feet.z - halfWidth,
                    feet.x + halfWidth, feet.y + entity.getBbHeight(), feet.z + halfWidth,
                    width, height, 0) != null) {
                continue;
            }
            // Measured from the player rather than the camera, so third person does not recolour
            // the ring; that is also the distance the Range filter above works in.
            arrows.add(new ScreenArrow(bearingAngle(camera.yRot(), eye.x, eye.z, feet.x, feet.z),
                    distanceColor(Math.sqrt(localPlayer.distanceToSqr(entity)))));
        }
    }

    public void render2D(GuiGraphics graphics, DeltaTracker ignored) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            boxes.clear();
            arrows.clear();
            tags.clear();
            return;
        }
        if (uses(TWO_D)) {
            for (ScreenBox box : boxes) {
                graphics.renderOutline(box.left(), box.top(), box.width(), box.height(), box.color());
            }
        }
        if (usesTracer(ARROWS)) {
            drawArrows(graphics);
        }
        if (usesNameTags()) {
            int border = tagBorder.get();
            for (NameTags.Placed placed : tags) {
                NameTags.draw(graphics, placed, border);
            }
        }
    }

    /**
     * Draws one arrowhead per off-screen target, on a ring around the crosshair. Every drawing
     * method GuiGraphics exposes is an axis aligned rectangle, so an arrowhead built out of them
     * has to step its two sloped edges a whole pixel at a time -- at a 6px arrow that is most of
     * the shape. Submitting a triangle straight to the collector lets the GPU rasterize the slopes.
     */
    private void drawArrows(GuiGraphics graphics) {
        if (arrows.isEmpty()) {
            return;
        }
        float size = arrowSize.get().floatValue();
        float radius = arrowRadius.get().floatValue();
        double opacity = tracerOpacity.get();
        float centerX = graphics.guiWidth() / 2.0F;
        float centerY = graphics.guiHeight() / 2.0F;
        for (ScreenArrow arrow : arrows) {
            graphics.guiRenderState.submitGuiElement(ArrowHead.of(centerX, centerY, arrow.angle(),
                    radius, size, tracerColor(arrow.color(), opacity)));
        }
    }

    /**
     * Red up close, fading to black from 15 blocks out and staying there. An arrow says nothing
     * about depth on its own -- an off-screen target has no box and no tracer length to read -- so
     * the ring carries distance in its colour instead. Opacity is applied later, over the top.
     */
    static int distanceColor(double distance) {
        double fade = Math.clamp((FADE_FAR - distance) / (FADE_FAR - FADE_NEAR), 0.0, 1.0);
        return ARGB.color((int) Math.round(255 * fade), (int) Math.round(100 * fade),
                (int) Math.round(100 * fade));
    }

    /**
     * Where a target sits on the crosshair ring, in y-down screen radians: straight ahead is up,
     * right is right, behind is down. Yaw only, so pitching the camera does not swing the ring and
     * looking straight up or down leaves it readable.
     */
    static float bearingAngle(float cameraYaw, double cameraX, double cameraZ,
                              double targetX, double targetZ) {
        float wantYaw = (float) (Math.toDegrees(Math.atan2(targetZ - cameraZ, targetX - cameraX)) - 90.0);
        return (float) (Math.toRadians(Mth.wrapDegrees(wantYaw - cameraYaw)) - Math.PI / 2.0);
    }

    /**
     * Lines ride the world pass instead of the 2D one: the vanilla gizmo collector already clips
     * the far end against the near plane, so a target behind the camera still yields a line running
     * off the correct screen edge, which is the whole point of a tracer. The 2D lists cover only
     * what survived frustum culling, so they are no use here.
     */
    @Override
    protected void onRender(DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        Player localPlayer = minecraft.player;
        // Only Lines hangs off the camera, so only Lines has any business taking view bobbing.
        boolean active = usesTracer(LINES);
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
     * Head bob swings the camera, and every line is anchored to it, so the whole fan sweeps with
     * the walk cycle. Turning Lines on forces bobbing off and remembers what it was; leaving Lines
     * hands it back, but only if it was on to begin with. A value the player switched on themselves
     * mid-session is therefore left alone rather than stamped back over. Arrows never take it:
     * their bearing is yaw only, which bobbing does not touch.
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
        arrows.clear();
        tags.clear();
        // onRender stops firing once the module is off, so this is the only chance to give it back.
        syncViewBobbing(false, Minecraft.getInstance().options.bobView());
    }

    private boolean uses(String selectedMode) {
        return enabled() && esp.get() && mode.get().equals(selectedMode);
    }

    /** The tracer group has its own toggle and its own mode, both independent of the ESP ones. */
    boolean usesTracer(String selectedMode) {
        return enabled() && tracers.get() && tracerMode.get().equals(selectedMode);
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
        return projectBox(renderer, camera,
                state.x - halfWidth, state.y, state.z - halfWidth,
                state.x + halfWidth, state.y + state.boundingBoxHeight, state.z + halfWidth,
                width, height, color);
    }

    /** The screen box a world hitbox covers, or null once it is off the viewport or behind it. */
    private static ScreenBox projectBox(GameRenderer renderer, Camera camera,
                                        double minX, double minY, double minZ,
                                        double maxX, double maxY, double maxZ,
                                        int width, int height, int color) {
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

    /** An off-screen target's crosshair-ring bearing, in y-down screen radians, and its fade. */
    record ScreenArrow(float angle, int color) {
    }

    /**
     * One filled arrowhead, already in screen pixels, as a GUI element the collector can draw.
     * Points are float, so the slopes land wherever the bearing puts them rather than on whole
     * pixels; that is the whole reason this exists instead of a stack of rectangles.
     */
    record ArrowHead(float tipX, float tipY, float rightX, float rightY, float leftX, float leftY,
                     int color, ScreenRectangle bounds) implements GuiElementRenderState {
        /** Screen space already, so the vertices need no further transform. */
        private static final Matrix3x2f NO_TRANSFORM = new Matrix3x2f();

        /**
         * An arrowhead straddling the ring point: tip a whole size beyond it, back edge half a size
         * inside it, and widest across the back. Same proportions as the 1.8.9 client.
         */
        static ArrowHead of(float centerX, float centerY, float angle, float radius, float size,
                            int color) {
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            double ringX = centerX + cos * radius;
            double ringY = centerY + sin * radius;
            double backX = ringX - cos * (size * 0.5);
            double backY = ringY - sin * (size * 0.5);
            double half = size * 0.6;
            // Right before left, which winds the triangle the same way as the quads GuiGraphics
            // submits, so it survives if the GUI pipeline ever starts culling back faces.
            float tipX = (float) (ringX + cos * size);
            float tipY = (float) (ringY + sin * size);
            float rightX = (float) (backX + sin * half);
            float rightY = (float) (backY - cos * half);
            float leftX = (float) (backX - sin * half);
            float leftY = (float) (backY + cos * half);
            return new ArrowHead(tipX, tipY, rightX, rightY, leftX, leftY, color,
                    bounds(tipX, tipY, rightX, rightY, leftX, leftY));
        }

        /** GuiRenderState silently drops an element with no bounds, so this is never null. */
        private static ScreenRectangle bounds(float tipX, float tipY, float rightX, float rightY,
                                              float leftX, float leftY) {
            int left = (int) Math.floor(Math.min(tipX, Math.min(rightX, leftX)));
            int top = (int) Math.floor(Math.min(tipY, Math.min(rightY, leftY)));
            int right = (int) Math.ceil(Math.max(tipX, Math.max(rightX, leftX)));
            int bottom = (int) Math.ceil(Math.max(tipY, Math.max(rightY, leftY)));
            return new ScreenRectangle(left, top, Math.max(1, right - left), Math.max(1, bottom - top));
        }

        @Override
        public void buildVertices(VertexConsumer consumer) {
            // The GUI pass draws indexed quads, so a triangle goes through as a quad whose last
            // two corners coincide: the second half of it collapses to no area at all.
            consumer.addVertexWith2DPose(NO_TRANSFORM, tipX, tipY).setColor(color);
            consumer.addVertexWith2DPose(NO_TRANSFORM, rightX, rightY).setColor(color);
            consumer.addVertexWith2DPose(NO_TRANSFORM, leftX, leftY).setColor(color);
            consumer.addVertexWith2DPose(NO_TRANSFORM, leftX, leftY).setColor(color);
        }

        @Override
        public RenderPipeline pipeline() {
            return RenderPipelines.GUI;
        }

        @Override
        public TextureSetup textureSetup() {
            return TextureSetup.noTexture();
        }

        @Override
        public ScreenRectangle scissorArea() {
            return null; // nothing has a scissor open over the HUD when the ring draws
        }
    }
}
