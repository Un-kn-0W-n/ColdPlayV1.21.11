package cn.timer.coldplay.client.mixin;

import cn.timer.coldplay.client.ClientCore;
import cn.timer.coldplay.client.manager.RotationManager;
import cn.timer.coldplay.client.module.impl.combat.BackTrack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.IdentityHashMap;
import java.util.Map;

@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerMixin {
    @Unique
    private final Map<Packet<?>, Long> coldplay$arrivalNanos = new IdentityHashMap<>();

    @Inject(method = "handleMoveEntity", at = @At("HEAD"), cancellable = true)
    private void coldplay$backTrackMove(ClientboundMoveEntityPacket packet, CallbackInfo callback) {
        boolean clientThread = coldplay$isClientThread();
        long arrivalNanos = coldplay$arrivalNanos(packet, clientThread);
        BackTrack backTrack = coldplay$backTrack();
        if (clientThread && backTrack != null
                && backTrack.handleMove(packet, (ClientPacketListener) (Object) this, arrivalNanos)) {
            callback.cancel();
        }
    }

    @Inject(method = "handleTeleportEntity", at = @At("HEAD"), cancellable = true)
    private void coldplay$backTrackTeleport(ClientboundTeleportEntityPacket packet, CallbackInfo callback) {
        boolean clientThread = coldplay$isClientThread();
        long arrivalNanos = coldplay$arrivalNanos(packet, clientThread);
        BackTrack backTrack = coldplay$backTrack();
        if (clientThread && backTrack != null
                && backTrack.handleTeleport(packet, (ClientPacketListener) (Object) this, arrivalNanos)) {
            callback.cancel();
        }
    }

    @Inject(method = "handleEntityPositionSync", at = @At("HEAD"), cancellable = true)
    private void coldplay$backTrackPositionSync(ClientboundEntityPositionSyncPacket packet,
                                                 CallbackInfo callback) {
        boolean clientThread = coldplay$isClientThread();
        long arrivalNanos = coldplay$arrivalNanos(packet, clientThread);
        BackTrack backTrack = coldplay$backTrack();
        if (clientThread && backTrack != null
                && backTrack.handlePositionSync(packet, (ClientPacketListener) (Object) this, arrivalNanos)) {
            callback.cancel();
        }
    }

    @Inject(method = "handleMovePlayer", at = @At("TAIL"))
    private void coldplay$positionCorrection(ClientboundPlayerPositionPacket packet, CallbackInfo callback) {
        RotationManager.getInstance().resetAfterServerCorrection(Minecraft.getInstance().player);
        coldplay$releaseBackTrack();
    }

    @Inject(method = "handleRotatePlayer", at = @At("TAIL"))
    private void coldplay$rotationCorrection(ClientboundPlayerRotationPacket packet, CallbackInfo callback) {
        RotationManager.getInstance().resetAfterServerCorrection(Minecraft.getInstance().player);
        coldplay$releaseBackTrack();
    }

    @Inject(method = "handleRespawn", at = @At("HEAD"))
    private void coldplay$respawn(ClientboundRespawnPacket packet, CallbackInfo callback) {
        if (coldplay$isClientThread()) {
            coldplay$releaseBackTrack();
        }
    }

    @Unique
    private static boolean coldplay$isClientThread() {
        return Minecraft.getInstance().packetProcessor().isSameThread();
    }

    @Unique
    private long coldplay$arrivalNanos(Packet<?> packet, boolean clientThread) {
        long now = System.nanoTime();
        synchronized (coldplay$arrivalNanos) {
            if (!clientThread) {
                coldplay$arrivalNanos.putIfAbsent(packet, now);
                return now;
            }
            Long arrival = coldplay$arrivalNanos.remove(packet);
            return arrival == null ? now : arrival;
        }
    }

    @Unique
    private static BackTrack coldplay$backTrack() {
        ClientCore core = ClientCore.get();
        return core.initialized() ? core.modules().get(BackTrack.class) : null;
    }

    @Unique
    private static void coldplay$releaseBackTrack() {
        BackTrack backTrack = coldplay$backTrack();
        if (backTrack != null) {
            backTrack.releaseAndClear();
        }
    }
}
