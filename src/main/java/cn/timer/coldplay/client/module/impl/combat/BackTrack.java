package cn.timer.coldplay.client.module.impl.combat;

import cn.timer.coldplay.client.module.Category;
import cn.timer.coldplay.client.module.Module;
import cn.timer.coldplay.client.setting.BooleanSetting;
import cn.timer.coldplay.client.setting.ColorSetting;
import cn.timer.coldplay.client.setting.NumberSetting;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.minecraft.SharedConstants;
import net.minecraft.ReportedException;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.VecDeltaCodec;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class BackTrack extends Module {
    public static final RenderStateDataKey<Integer> REAL_COLOR =
            RenderStateDataKey.create(() -> "coldplay:backtrack_real_color");

    static final int MAX_QUEUED_UPDATES = 256;
    private static final long INTERPOLATION_NANOS =
            TimeUnit.MILLISECONDS.toNanos(SharedConstants.MILLIS_PER_TICK);
    private static final double POSITION_EPSILON_SQUARED = 1.0E-6;

    private final ArrayDeque<QueuedUpdate> queue = new ArrayDeque<>();
    private final NumberSetting delay = addSetting(new NumberSetting(
            "Delay", 200.0, 50.0, 1000.0, 1.0, "ms", this::onDelayChanged));
    private final BooleanSetting render = addOwnerSetting(new BooleanSetting("Render", true));
    private final ColorSetting color = addChildSetting(render, new ColorSetting("Color", 0xFFFF5555));

    private int targetId = -1;
    private LocalPlayer trackedPlayer;
    private ClientLevel trackedLevel;
    private ClientPacketListener trackedListener;
    private PositionMoveRotation realState;
    private VecDeltaCodec realCodec;
    private Vec3 renderFrom;
    private Vec3 renderTo;
    private long renderStartedAt;
    private boolean hasRealPositionUpdate;
    private boolean replaying;
    private QueuedUpdate scheduledHead;
    private long scheduledDelayNanos;
    private long wakeGeneration;

    public BackTrack() {
        super("BackTrack", "Delays the last attacked target's position updates", Category.COMBAT,
                GLFW.GLFW_KEY_UNKNOWN);
    }

    public InteractionResult onAttack(Player player, Level level, InteractionHand hand, Entity entity,
                                      @Nullable EntityHitResult hitResult) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer localPlayer = minecraft.player;
        ClientPacketListener listener = minecraft.getConnection();
        if (!enabled() || player != localPlayer || level != minecraft.level || hand != InteractionHand.MAIN_HAND
                || localPlayer == null || listener == null || minecraft.gameMode == null
                || !localPlayer.isAlive() || localPlayer.isSpectator()
                || !(entity instanceof LivingEntity target) || !validTarget(localPlayer, target)) {
            return InteractionResult.PASS;
        }

        if (targetId == target.getId() && trackedPlayer == localPlayer
                && trackedLevel == minecraft.level && trackedListener == listener) {
            return InteractionResult.PASS;
        }

        releaseAndClear();
        targetId = target.getId();
        trackedPlayer = localPlayer;
        trackedLevel = minecraft.level;
        trackedListener = listener;
        realState = PositionMoveRotation.of(target);
        realCodec = new VecDeltaCodec();
        realCodec.setBase(target.getPositionCodec().getBase());
        renderFrom = realState.position();
        renderTo = realState.position();
        renderStartedAt = System.nanoTime();
        hasRealPositionUpdate = false;
        return InteractionResult.PASS;
    }

    public void tick(Minecraft minecraft) {
        if (targetId < 0) {
            return;
        }
        if (!sameContext(minecraft)) {
            discardAndClear();
            return;
        }
        if (!validTrackedTarget(minecraft)) {
            releaseAndClear();
            return;
        }

        long now = System.nanoTime();
        drainDue(now);
        if (targetId >= 0 && !validTrackedTarget(minecraft)) {
            releaseAndClear();
        } else {
            scheduleNext(System.nanoTime());
        }
    }

    public boolean handleMove(ClientboundMoveEntityPacket packet, ClientPacketListener listener,
                              long arrivalNanos) {
        if (replaying || trackedLevel == null) {
            return false;
        }
        Entity entity = packet.getEntity(trackedLevel);
        if (entity == null || !prepareToDelay(listener, entity.getId())) {
            return false;
        }

        if (packet.hasRotation()) {
            realState = realState.withRotation(packet.getYRot(), packet.getXRot());
        }
        if (!packet.hasPosition()) {
            return false;
        }

        Vec3 position = realCodec.decode(packet.getXa(), packet.getYa(), packet.getZa());
        realCodec.setBase(position);
        realState = new PositionMoveRotation(position, realState.deltaMovement(),
                realState.yRot(), realState.xRot());
        capturePosition(position, true);
        return enqueue(packet, listener, arrivalNanos);
    }

    public boolean handlePositionSync(ClientboundEntityPositionSyncPacket packet,
                                      ClientPacketListener listener, long arrivalNanos) {
        if (replaying || !prepareToDelay(listener, packet.id())) {
            return false;
        }

        realState = packet.values();
        realCodec.setBase(realState.position());
        capturePosition(realState.position(), false);
        return enqueue(packet, listener, arrivalNanos);
    }

    public boolean handleTeleport(ClientboundTeleportEntityPacket packet, ClientPacketListener listener,
                                  long arrivalNanos) {
        if (replaying || !prepareToDelay(listener, packet.id())) {
            return false;
        }

        realState = PositionMoveRotation.calculateAbsolute(realState, packet.change(), packet.relatives());
        capturePosition(realState.position(), false);
        return enqueue(packet, listener, arrivalNanos);
    }

    public void extractRealPosition(WorldExtractionContext context) {
        if (!enabled() || !render.get() || !hasRealPositionUpdate
                || targetId < 0 || context.world() != trackedLevel) {
            return;
        }
        LivingEntity target = targetEntity();
        if (target == null || target.isRemoved() || !target.isAlive() || renderTo == null) {
            return;
        }

        float partialTick = context.tickCounter().getGameTimeDeltaPartialTick(
                !context.world().tickRateManager().isEntityFrozen(target));
        Vec3 realPosition = renderPosition(System.nanoTime());
        if (realPosition.distanceToSqr(target.getPosition(partialTick)) <= POSITION_EPSILON_SQUARED) {
            return;
        }

        EntityRenderState extracted = Minecraft.getInstance().getEntityRenderDispatcher()
                .extractEntity(target, partialTick);
        if (!(extracted instanceof LivingEntityRenderState state)) {
            return;
        }

        state.x = realPosition.x;
        state.y = realPosition.y;
        state.z = realPosition.z;
        state.distanceToCameraSq = realPosition.distanceToSqr(context.camera().position());
        state.nameTag = null;
        state.nameTagAttachment = null;
        state.leashStates = null;
        state.passengerOffset = null;
        state.displayFireAnimation = false;
        state.shadowPieces.clear();
        state.isInvisible = true;
        state.isInvisibleToPlayer = false;
        state.outlineColor = color.get();
        state.setData(REAL_COLOR, color.get());
        context.worldState().haveGlowingEntities = true;
        context.worldState().entityRenderStates.add(state);
    }

    public void onEntityUnload(Entity entity, ClientLevel level) {
        if (level == trackedLevel && entity.getId() == targetId) {
            discardAndClear();
        }
    }

    public void releaseAndClear() {
        if (sameContext(Minecraft.getInstance())) {
            releaseAll();
        } else {
            queue.clear();
        }
        clearTracking();
    }

    public void discardAndClear() {
        queue.clear();
        clearTracking();
    }

    @Override
    protected void onEnable() {
        discardAndClear();
    }

    @Override
    protected void onDisable() {
        releaseAndClear();
    }

    @Override
    protected void onRender(DeltaTracker ignored) {
        tick(Minecraft.getInstance());
    }

    private boolean prepareToDelay(ClientPacketListener listener, int packetTargetId) {
        if (!enabled() || packetTargetId != targetId || listener != trackedListener) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (!sameContext(minecraft)) {
            discardAndClear();
            return false;
        }
        if (!validTrackedTarget(minecraft)) {
            releaseAndClear();
            return false;
        }
        return true;
    }

    private boolean enqueue(Packet<ClientGamePacketListener> packet, ClientPacketListener listener,
                            long arrivalNanos) {
        long now = System.nanoTime();
        drainDue(now);
        now = System.nanoTime();
        if (targetId < 0 || !validTrackedTarget(Minecraft.getInstance())) {
            releaseAndClear();
            return false;
        }
        if (isDue(arrivalNanos, now, delayNanos())) {
            scheduleNext(now);
            if (queue.isEmpty()) {
                hasRealPositionUpdate = false;
            }
            return false;
        }
        if (reachesSafetyLimit(queue.size())) {
            releaseAll();
            clearTracking();
            return false;
        }

        queue.addLast(new QueuedUpdate(packet, arrivalNanos, targetId, trackedLevel, listener));
        scheduleNext(now);
        return true;
    }

    private void drainDue(long now) {
        if (!sameContext(Minecraft.getInstance())) {
            discardAndClear();
            return;
        }
        long delayNanos = delayNanos();
        while (!queue.isEmpty() && isDue(queue.peekFirst().arrivalNanos(), now, delayNanos)) {
            replay(queue.removeFirst());
            now = System.nanoTime();
        }
    }

    private void releaseAll() {
        while (!queue.isEmpty()) {
            replay(queue.removeFirst());
        }
    }

    private void replay(QueuedUpdate update) {
        if (update.targetId() != targetId || update.level() != trackedLevel
                || update.listener() != trackedListener || !update.listener().shouldHandleMessage(update.packet())) {
            return;
        }

        replaying = true;
        try {
            update.packet().handle(update.listener());
        } catch (Exception exception) {
            if (exception instanceof ReportedException reportedException
                    && reportedException.getCause() instanceof OutOfMemoryError) {
                throw PacketUtils.makeReportedException(exception, update.packet(), update.listener());
            }
            update.listener().onPacketError(update.packet(), exception);
        } finally {
            replaying = false;
        }
    }

    private void onDelayChanged(double ignored) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        Runnable update = () -> {
            long now = System.nanoTime();
            drainDue(now);
            scheduleNext(System.nanoTime());
        };
        if (minecraft.packetProcessor().isSameThread()) {
            update.run();
        } else {
            minecraft.execute(update);
        }
    }

    private void scheduleNext(long now) {
        QueuedUpdate head = queue.peekFirst();
        long delayNanos = delayNanos();
        if (head == null) {
            invalidateWake();
            return;
        }
        if (head == scheduledHead && delayNanos == scheduledDelayNanos) {
            return;
        }

        long generation = ++wakeGeneration;
        scheduledHead = head;
        scheduledDelayNanos = delayNanos;
        long waitNanos = Math.max(0L, delayNanos - (now - head.arrivalNanos()));
        CompletableFuture.delayedExecutor(waitNanos, TimeUnit.NANOSECONDS).execute(() ->
                Minecraft.getInstance().execute(() -> {
                    if (generation != wakeGeneration) {
                        return;
                    }
                    scheduledHead = null;
                    tick(Minecraft.getInstance());
                }));
    }

    private void invalidateWake() {
        if (scheduledHead != null) {
            wakeGeneration++;
        }
        scheduledHead = null;
        scheduledDelayNanos = 0L;
    }

    void capturePosition(Vec3 position, boolean interpolate) {
        long now = System.nanoTime();
        if (!interpolate || renderTo == null) {
            renderFrom = position;
        } else {
            renderFrom = renderPosition(now);
        }
        renderTo = position;
        renderStartedAt = now;
        hasRealPositionUpdate = true;
    }

    Vec3 renderPosition(long now) {
        return interpolate(renderFrom, renderTo, renderStartedAt, now);
    }

    private LivingEntity targetEntity() {
        if (trackedLevel == null || targetId < 0) {
            return null;
        }
        Entity entity = trackedLevel.getEntity(targetId);
        return entity instanceof LivingEntity living ? living : null;
    }

    private boolean validTrackedTarget(Minecraft minecraft) {
        LivingEntity target = targetEntity();
        return minecraft.player == trackedPlayer && minecraft.level == trackedLevel
                && minecraft.getConnection() == trackedListener && trackedPlayer != null
                && trackedPlayer.isAlive() && !trackedPlayer.isSpectator()
                && target != null && validTarget(trackedPlayer, target);
    }

    private static boolean validTarget(LocalPlayer player, LivingEntity target) {
        return target.level() == player.level() && WTap.validTarget(player, target)
                && player.isWithinAttackRange(target.getBoundingBox(), 0.0D)
                && !target.hasIndirectPassenger(player) && !player.hasIndirectPassenger(target);
    }

    private boolean sameContext(Minecraft minecraft) {
        return targetId >= 0 && minecraft.player == trackedPlayer && minecraft.level == trackedLevel
                && minecraft.getConnection() == trackedListener;
    }

    private long delayNanos() {
        return Math.round(delay.get() * 1_000_000.0);
    }

    private void clearTracking() {
        queue.clear();
        targetId = -1;
        trackedPlayer = null;
        trackedLevel = null;
        trackedListener = null;
        realState = null;
        realCodec = null;
        renderFrom = null;
        renderTo = null;
        renderStartedAt = 0L;
        hasRealPositionUpdate = false;
        invalidateWake();
    }

    static boolean isDue(long arrivalNanos, long nowNanos, long delayNanos) {
        return nowNanos - arrivalNanos >= delayNanos;
    }

    static boolean reachesSafetyLimit(int queuedUpdates) {
        return queuedUpdates + 1 >= MAX_QUEUED_UPDATES;
    }

    static Vec3 interpolate(Vec3 from, Vec3 to, long startedAtNanos, long nowNanos) {
        if (from == null || to == null) {
            return Vec3.ZERO;
        }
        long elapsed = Math.max(0L, nowNanos - startedAtNanos);
        if (elapsed >= INTERPOLATION_NANOS) {
            return to;
        }
        return from.lerp(to, (double) elapsed / INTERPOLATION_NANOS);
    }

    private record QueuedUpdate(Packet<ClientGamePacketListener> packet, long arrivalNanos,
                                int targetId, ClientLevel level, ClientPacketListener listener) {
    }
}
