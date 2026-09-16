package cn.timer.coldplay.client.module.impl.combat;

import cn.timer.coldplay.client.module.Category;
import cn.timer.coldplay.client.module.Module;
import cn.timer.coldplay.client.setting.BooleanSetting;
import cn.timer.coldplay.client.setting.ColorSetting;
import cn.timer.coldplay.client.setting.NumberSetting;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.ARGB;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class BackTrack extends Module {
    static final int MAX_SAMPLES = 32;
    static final double TRACKING_RADIUS = 12.0;
    static final double SEPARATION_EPSILON_SQUARED = 1.0E-4;

    private static final float STROKE_WIDTH = 2.5F;
    private static final int FILL_ALPHA = 72;
    private static final long RETENTION_MARGIN_NANOS = TimeUnit.MILLISECONDS.toNanos(250L);

    private final Map<Integer, ArrayDeque<Sample>> history = new HashMap<>();
    private final NumberSetting delay = addSetting(new NumberSetting(
            "Delay", 200.0, 50.0, 1000.0, 1.0, "ms", ignored -> {
    }));
    private final BooleanSetting render = addOwnerSetting(new BooleanSetting("Render", true));
    private final ColorSetting color = addChildSetting(render, new ColorSetting("Color", 0xFFFF5555));

    private ClientLevel trackedLevel;
    private int renderTargetId = -1;

    public BackTrack() {
        super("BackTrack", "Aims at where the target was a moment ago", Category.COMBAT,
                GLFW.GLFW_KEY_UNKNOWN);
    }

    /** Remembers the last target attacked by hand, so the overlay knows whose hitbox to draw. */
    public InteractionResult onAttack(Player player, Level level, InteractionHand hand, Entity entity,
                                      @Nullable EntityHitResult hitResult) {
        Minecraft minecraft = Minecraft.getInstance();
        if (enabled() && player == minecraft.player && level == minecraft.level
                && hand == InteractionHand.MAIN_HAND && entity instanceof LivingEntity) {
            renderTargetId = entity.getId();
        }
        return InteractionResult.PASS;
    }

    public void tick(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (!enabled() || player == null || level == null || !player.isAlive() || player.isSpectator()) {
            clear();
            return;
        }
        if (level != trackedLevel) {
            clear();
            trackedLevel = level;
        }

        long now = System.nanoTime();
        prune(now - retentionNanos());
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class,
                player.getBoundingBox().inflate(TRACKING_RADIUS),
                candidate -> candidate != player && WTap.validTarget(player, candidate))) {
            ArrayDeque<Sample> samples = history.computeIfAbsent(entity.getId(), id -> new ArrayDeque<>());
            samples.addLast(new Sample(now, entity.getBoundingBox()));
            while (samples.size() > MAX_SAMPLES) {
                samples.removeFirst();
            }
        }
    }

    public AABB rewound(LivingEntity entity) {
        AABB live = entity.getBoundingBox();
        if (!enabled()) {
            return live;
        }
        ArrayDeque<Sample> samples = history.get(entity.getId());
        if (samples == null) {
            return live;
        }
        Sample sample = select(samples, System.nanoTime() - delayNanos());
        return sample == null ? live : sample.box();
    }

    public void extractRewind(WorldExtractionContext context) {
        if (!enabled() || !render.get() || renderTargetId < 0
                || trackedLevel == null || context.world() != trackedLevel) {
            return;
        }
        Entity entity = trackedLevel.getEntity(renderTargetId);
        if (!(entity instanceof LivingEntity target) || !target.isAlive()) {
            return;
        }
        AABB box = rewound(target);
        if (!separated(box, target.getBoundingBox())) {
            return;
        }

        int argb = color.get();
        Gizmos.cuboid(box, GizmoStyle.strokeAndFill(
                ARGB.opaque(argb), STROKE_WIDTH, ARGB.color(FILL_ALPHA, argb)));
    }

    public void onEntityUnload(Entity entity, ClientLevel level) {
        if (level != trackedLevel) {
            return;
        }
        history.remove(entity.getId());
        if (entity.getId() == renderTargetId) {
            renderTargetId = -1;
        }
    }

    public void clear() {
        history.clear();
        trackedLevel = null;
        renderTargetId = -1;
    }

    @Override
    protected void onEnable() {
        clear();
    }

    @Override
    protected void onDisable() {
        clear();
    }

    private void prune(long horizonNanos) {
        Iterator<ArrayDeque<Sample>> iterator = history.values().iterator();
        while (iterator.hasNext()) {
            ArrayDeque<Sample> samples = iterator.next();
            while (!samples.isEmpty() && samples.peekFirst().timeNanos() - horizonNanos < 0L) {
                samples.removeFirst();
            }
            if (samples.isEmpty()) {
                iterator.remove();
            }
        }
    }

    private long retentionNanos() {
        return TimeUnit.MILLISECONDS.toNanos(Math.round(delay.maximum())) + RETENTION_MARGIN_NANOS;
    }

    private long delayNanos() {
        return Math.round(delay.get() * 1_000_000.0);
    }

    /** The newest sample taken at or before {@code cutoffNanos}, or null when none reaches back that far. */
    static @Nullable Sample select(Iterable<Sample> samples, long cutoffNanos) {
        Sample best = null;
        for (Sample sample : samples) {
            if (sample.timeNanos() - cutoffNanos > 0L) {
                break;
            }
            best = sample;
        }
        return best;
    }

    static boolean separated(AABB first, AABB second) {
        return first.getCenter().distanceToSqr(second.getCenter()) > SEPARATION_EPSILON_SQUARED;
    }

    record Sample(long timeNanos, AABB box) {
    }
}
