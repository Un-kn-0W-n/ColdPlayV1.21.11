package cn.timer.coldplay.client.module.impl.visuals;

import cn.timer.coldplay.client.manager.RotationManager;
import cn.timer.coldplay.client.module.Category;
import cn.timer.coldplay.client.module.Module;
import cn.timer.coldplay.client.setting.BooleanSetting;
import cn.timer.coldplay.client.setting.ColorSetting;
import cn.timer.coldplay.client.setting.NumberSetting;
import cn.timer.coldplay.client.util.Gizmo;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ExperienceBottleItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ThrowablePotionItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public final class Trajectories extends Module {

    static final double AIR_DRAG = 0.99;
    static final double ARROW_GRAVITY = 0.05;
    static final double ARROW_WATER_DRAG = 0.6;
    static final double TRIDENT_WATER_DRAG = 0.99;
    static final double THROWABLE_GRAVITY = 0.03;
    static final double POTION_GRAVITY = 0.05;
    static final double XP_BOTTLE_GRAVITY = 0.07;
    static final double THROWABLE_WATER_DRAG = 0.8;

    static final float BOW_MAX_POWER = 3.0F;
    static final float CROSSBOW_ARROW_POWER = 3.15F;
    static final float THROWABLE_POWER = 1.5F;
    static final float POTION_POWER = 0.5F;
    static final float XP_BOTTLE_POWER = 0.7F;
    static final float POTION_PITCH_OFFSET = -20.0F;
    static final float MIN_BOW_POWER = 0.1F;

    /** {@link #useTicks} for a stack the player is not currently drawing; zero is a valid draw time. */
    static final int NOT_USING = -1;

    private static final double MARKER_HALF_SIZE = 0.25;
    private static final double MARKER_LIFT = 0.01;
    private static final double QUERY_INFLATION = 1.0;

    private static final InteractionHand[] HANDS = InteractionHand.values();

    private final NumberSetting ticks = addSetting(new NumberSetting("Ticks", 60.0, 20.0, 200.0, 10.0));
    private final NumberSetting width = addSetting(new NumberSetting("Width", 2.0, 0.5, 5.0, 0.5));
    private final NumberSetting opacity = addSetting(new NumberSetting("Opacity", 80.0, 0.0, 255.0, 5.0));

    private final BooleanSetting path = addOwnerSetting(new BooleanSetting("Path", true));
    private final ColorSetting pathColor = addChildSetting(path, new ColorSetting("Color", 0xFF64C8FF));
    private final BooleanSetting landing = addOwnerSetting(new BooleanSetting("Landing", true));
    private final ColorSetting landingColor = addChildSetting(landing, new ColorSetting("Color", 0xFF64C8FF));
    private final BooleanSetting entities = addOwnerSetting(new BooleanSetting("Entities", true));
    private final ColorSetting entitiesColor = addChildSetting(entities, new ColorSetting("Color", 0xFFFF3C3C));

    private final BooleanSetting shooterMotion = addSetting(new BooleanSetting("Shooter Motion", false));

    private final BooleanSetting bow = addSetting(new BooleanSetting("Bow", true));
    private final BooleanSetting crossbow = addSetting(new BooleanSetting("Crossbow", true));
    private final BooleanSetting trident = addSetting(new BooleanSetting("Trident", true));
    private final BooleanSetting throwables = addSetting(new BooleanSetting("Throwables", true));
    private final BooleanSetting potions = addSetting(new BooleanSetting("Potions", true));

    public Trajectories() {
        super("Trajectories", "Predicts where the held projectile lands", Category.VISUALS, GLFW.GLFW_KEY_UNKNOWN);
    }

    enum Kind {
        BOW, CROSSBOW, TRIDENT, THROWABLE, POTION
    }

    record Profile(Kind kind, EntityType<?> type, boolean arrowOrder, double gravity, double waterDrag,
                   float power, float pitchOffset, boolean inheritsShooterMotion, boolean releaseFired) {
    }

    record Flight(List<Vec3> points, BlockHitResult blockHit) {
    }

    record Interception(EntityHitResult hit, int segment) {
    }

    @Override
    protected void onRender(DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        boolean drawPath = path.get();
        boolean drawLanding = landing.get();
        boolean drawEntities = entities.get();
        if (level == null || player == null || !drawPath && !drawLanding && !drawEntities) {
            return;
        }

        Profile profile = heldProfile(player);
        if (profile == null) {
            return;
        }

        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
        RotationManager rotations = RotationManager.getInstance();
        boolean spoofed = profile.releaseFired() && rotations.isActive();

        float yaw = spoofed ? rotations.getServerYaw() : player.getViewYRot(partialTick);
        float pitch = spoofed ? rotations.getServerPitch() : player.getViewXRot(partialTick);

        // Vanilla spawns a projectile thrown from the hand a tenth of a block below eye level.
        Vec3 start = player.getEyePosition(partialTick).subtract(0.0, 0.1, 0.0);
        Vec3 velocity = launchVelocity(launchDirection(yaw, pitch, profile.pitchOffset()), profile.power(),
                player.getKnownMovement(), player.onGround(),
                profile.inheritsShooterMotion() && shooterMotion.get());

        Flight flight = simulate(profile, start, velocity, ticks.get().intValue(), level, pos -> loaded(level, pos));
        List<Vec3> points = flight.points();
        BlockHitResult blockHit = flight.blockHit();

        Interception interception = drawEntities ? intercept(level, player, profile, points) : null;
        if (interception != null) {
            points = new ArrayList<>(points.subList(0, interception.segment() + 1));
            points.add(interception.hit().getLocation());
            blockHit = null;
        }

        double strokeWidth = width.get();
        if (drawPath && points.size() > 1) {
            // The stroke stays opaque; Opacity drives the fills below, as it does in BlockESP.
            int color = ARGB.opaque(pathColor.get());
            for (int i = 1; i < points.size(); i++) {
                Gizmos.line(points.get(i - 1), points.get(i), color, (float) strokeWidth).setAlwaysOnTop();
            }
        }
        if (drawLanding && blockHit != null) {
            drawMarker(blockHit.getLocation(), blockHit.getDirection(),
                    Gizmo.style(landingColor.get(), strokeWidth, opacity.get()));
        }
        if (interception != null) {
            Entity victim = interception.hit().getEntity();
            AABB box = victim.getBoundingBox().move(victim.getPosition(partialTick).subtract(victim.position()));
            Gizmos.cuboid(box, Gizmo.style(entitiesColor.get(), strokeWidth, opacity.get())).setAlwaysOnTop();
        }
    }

    /** The first hand holding something this module is set to predict, or null. */
    private Profile heldProfile(LocalPlayer player) {
        for (InteractionHand hand : HANDS) {
            ItemStack candidate = player.getItemInHand(hand);
            Profile found = profileFor(candidate, useTicks(player, candidate));
            if (found != null && allowed(found) && !riptides(player, candidate)) {
                return found;
            }
        }
        return null;
    }

    private boolean allowed(Profile profile) {
        return switch (profile.kind()) {
            case BOW -> bow.get();
            case CROSSBOW -> crossbow.get();
            case TRIDENT -> trident.get();
            case THROWABLE -> throwables.get();
            case POTION -> potions.get();
        };
    }

    private static boolean loaded(ClientLevel level, BlockPos pos) {
        return level.getChunkSource().getChunk(SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getZ()), ChunkStatus.FULL, false) != null;
    }

    static int useTicks(LocalPlayer player, ItemStack stack) {
        return player.isUsingItem() && player.getUseItem() == stack ? player.getTicksUsingItem() : NOT_USING;
    }

    private static boolean riptides(LocalPlayer player, ItemStack stack) {
        return stack.getItem() instanceof TridentItem
                && EnchantmentHelper.getTridentSpinAttackStrength(stack, player) > 0.0F
                && player.isInWaterOrRain();
    }

    static Profile profileFor(ItemStack stack, int useTicks) {
        Item item = stack.getItem();
        if (item instanceof BowItem) {
            float power = useTicks == NOT_USING ? 1.0F : BowItem.getPowerForTime(useTicks);
            return power < MIN_BOW_POWER ? null
                    : new Profile(Kind.BOW, EntityType.ARROW, true, ARROW_GRAVITY, ARROW_WATER_DRAG,
                            power * BOW_MAX_POWER, 0.0F, true, true);
        }
        if (item instanceof CrossbowItem) {
            ChargedProjectiles charged = stack.get(DataComponents.CHARGED_PROJECTILES);
            // A firework bolt is self-propelled with no gravity, so no arc describes it.
            if (!CrossbowItem.isCharged(stack) || charged == null || charged.contains(Items.FIREWORK_ROCKET)) {
                return null;
            }
            return new Profile(Kind.CROSSBOW, EntityType.ARROW, true, ARROW_GRAVITY, ARROW_WATER_DRAG,
                    CROSSBOW_ARROW_POWER, 0.0F, false, false);
        }
        if (item instanceof TridentItem) {
            return useTicks >= 0 && useTicks < TridentItem.THROW_THRESHOLD_TIME ? null
                    : new Profile(Kind.TRIDENT, EntityType.TRIDENT, true, ARROW_GRAVITY, TRIDENT_WATER_DRAG,
                            TridentItem.PROJECTILE_SHOOT_POWER, 0.0F, true, true);
        }
        if (item instanceof ThrowablePotionItem) {
            EntityType<?> type = item == Items.LINGERING_POTION
                    ? EntityType.LINGERING_POTION : EntityType.SPLASH_POTION;
            return new Profile(Kind.POTION, type, false, POTION_GRAVITY, THROWABLE_WATER_DRAG,
                    POTION_POWER, POTION_PITCH_OFFSET, true, false);
        }
        if (item instanceof ExperienceBottleItem) {
            return new Profile(Kind.POTION, EntityType.EXPERIENCE_BOTTLE, false, XP_BOTTLE_GRAVITY,
                    THROWABLE_WATER_DRAG, XP_BOTTLE_POWER, POTION_PITCH_OFFSET, true, false);
        }
        EntityType<?> thrown = item == Items.SNOWBALL ? EntityType.SNOWBALL
                : item == Items.EGG ? EntityType.EGG
                : item == Items.ENDER_PEARL ? EntityType.ENDER_PEARL : null;
        return thrown == null ? null
                : new Profile(Kind.THROWABLE, thrown, false, THROWABLE_GRAVITY, THROWABLE_WATER_DRAG,
                        THROWABLE_POWER, 0.0F, true, false);
    }

    static Vec3 launchDirection(float yaw, float pitch, float pitchOffset) {
        float yawRadians = yaw * Mth.DEG_TO_RAD;
        float pitchRadians = pitch * Mth.DEG_TO_RAD;
        float offsetRadians = (pitch + pitchOffset) * Mth.DEG_TO_RAD;
        return new Vec3(
                -Mth.sin(yawRadians) * Mth.cos(pitchRadians),
                -Mth.sin(offsetRadians),
                Mth.cos(yawRadians) * Mth.cos(pitchRadians)).normalize();
    }

    static Vec3 launchVelocity(Vec3 direction, float power, Vec3 shooterMotion, boolean onGround, boolean inherit) {
        Vec3 velocity = direction.scale(power);
        return inherit ? velocity.add(shooterMotion.x, onGround ? 0.0 : shooterMotion.y, shooterMotion.z) : velocity;
    }

    /** Arrows drag first and then fall, so this tick's gravity is not damped. */
    static Vec3 stepArrow(Vec3 velocity, double gravity, double drag) {
        return new Vec3(velocity.x * drag, velocity.y * drag - gravity, velocity.z * drag);
    }

    /** Throwables fall first and drag the result, so this tick's gravity is damped with it. */
    static Vec3 stepThrowable(Vec3 velocity, double gravity, double drag) {
        return new Vec3(velocity.x * drag, (velocity.y - gravity) * drag, velocity.z * drag);
    }

    /** Vanilla ramps the hit margin in from nothing over a projectile's first eight ticks. */
    static float margin(int tick) {
        return Math.clamp((tick - 2) / 20.0F, 0.0F, ProjectileUtil.DEFAULT_ENTITY_HIT_RESULT_MARGIN);
    }

    static Flight simulate(Profile profile, Vec3 start, Vec3 initialVelocity, int maxTicks,
                           BlockGetter terrain, Predicate<BlockPos> loaded) {
        List<Vec3> points = new ArrayList<>(maxTicks + 1);
        points.add(start);
        Vec3 position = start;
        Vec3 velocity = initialVelocity;
        for (int tick = 0; tick < maxTicks; tick++) {
            BlockPos block = BlockPos.containing(position);
            if (!loaded.test(block) || terrain.isOutsideBuildHeight(block)) {
                return new Flight(points, null);
            }
            double drag = terrain.getFluidState(block).getType().isSame(Fluids.WATER) ? profile.waterDrag() : AIR_DRAG;
            if (!profile.arrowOrder()) {
                velocity = stepThrowable(velocity, profile.gravity(), drag);
            }
            Vec3 next = position.add(velocity);
            BlockHitResult hit = terrain.clip(new ClipContext(position, next,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
            if (hit.getType() != HitResult.Type.MISS) {
                points.add(hit.getLocation());
                return new Flight(points, hit);
            }
            position = next;
            points.add(position);
            if (profile.arrowOrder()) {
                velocity = stepArrow(velocity, profile.gravity(), drag);
            }
        }
        return new Flight(points, null);
    }

    static Interception intercept(ClientLevel level, LocalPlayer player, Profile profile, List<Vec3> points) {
        Predicate<Entity> filter = Entity::canBeHitByProjectile;
        if (profile.arrowOrder()) {
            filter = filter.and(candidate -> !(candidate instanceof Player other) || player.canHarmPlayer(other));
        }
        EntityDimensions dimensions = profile.type().getDimensions();
        // One broadphase over the whole arc first: with nothing near it, every segment below would miss.
        if (level.getEntities(player, pathQuery(points, dimensions), filter).isEmpty()) {
            return null;
        }
        for (int segment = 0; segment + 1 < points.size(); segment++) {
            Vec3 from = points.get(segment);
            Vec3 to = points.get(segment + 1);
            AABB query = dimensions.makeBoundingBox(from).expandTowards(to.subtract(from)).inflate(QUERY_INFLATION);
            float margin = margin(segment);
            EntityHitResult hit = profile.arrowOrder()
                    ? nearestPierced(level, player, from, to, query, filter, margin)
                    : ProjectileUtil.getEntityHitResult(level, player, from, to, query, filter, margin);
            if (hit != null) {
                return new Interception(hit, segment);
            }
        }
        return null;
    }

    /**
     * A box containing every per-segment query box. Deliberately generous: it only has to avoid
     * missing an entity a segment would have found, so over-reaching costs one wider broadphase.
     */
    private static AABB pathQuery(List<Vec3> points, EntityDimensions dimensions) {
        Vec3 first = points.get(0);
        double minX = first.x;
        double minY = first.y;
        double minZ = first.z;
        double maxX = first.x;
        double maxY = first.y;
        double maxZ = first.z;
        for (int i = 1; i < points.size(); i++) {
            Vec3 point = points.get(i);
            minX = Math.min(minX, point.x);
            minY = Math.min(minY, point.y);
            minZ = Math.min(minZ, point.z);
            maxX = Math.max(maxX, point.x);
            maxY = Math.max(maxY, point.y);
            maxZ = Math.max(maxZ, point.z);
        }
        double horizontal = dimensions.width() * 0.5 + QUERY_INFLATION;
        double vertical = dimensions.height() + QUERY_INFLATION;
        return new AABB(minX - horizontal, minY - vertical, minZ - horizontal,
                maxX + horizontal, maxY + vertical, maxZ + horizontal);
    }

    /** An arrow pierces, so of everything the segment crosses it is the nearest that stops the preview. */
    private static EntityHitResult nearestPierced(ClientLevel level, LocalPlayer player, Vec3 from, Vec3 to,
                                                  AABB query, Predicate<Entity> filter, float margin) {
        EntityHitResult nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (EntityHitResult candidate : ProjectileUtil.getManyEntityHitResult(level, player, from, to, query,
                filter, margin, ClipContext.Block.COLLIDER, false)) {
            double distance = from.distanceToSqr(candidate.getEntity().position());
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = candidate;
            }
        }
        return nearest;
    }

    static void drawMarker(Vec3 at, Direction face, GizmoStyle style) {
        Vec3 normal = face.getUnitVec3();
        Vec3 centre = at.add(normal.scale(MARKER_LIFT));
        Vec3 reference = face.getAxis() == Direction.Axis.Y ? new Vec3(1.0, 0.0, 0.0) : new Vec3(0.0, 1.0, 0.0);
        Vec3 right = normal.cross(reference).scale(MARKER_HALF_SIZE);
        Vec3 up = reference.scale(MARKER_HALF_SIZE);
        Gizmos.rect(centre.subtract(right).subtract(up), centre.add(right).subtract(up),
                centre.add(right).add(up), centre.subtract(right).add(up), style).setAlwaysOnTop();
    }
}
