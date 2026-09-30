package cn.timer.coldplay.client.mixin;

import cn.timer.coldplay.client.ClientCore;
import cn.timer.coldplay.client.manager.RotationManager;
import cn.timer.coldplay.client.module.impl.visuals.Projection;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
    @Unique
    private boolean coldplay$raycastSpoofed;
    @Unique
    private float coldplay$raycastYaw;
    @Unique
    private float coldplay$raycastPitch;
    @Unique
    private float coldplay$raycastPreviousYaw;
    @Unique
    private float coldplay$raycastPreviousPitch;

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void coldplay$render(DeltaTracker deltaTracker, CallbackInfo callback) {
        ClientCore.get().render(deltaTracker);
    }

    /**
     * The projection handed to the level renderer is the one the world is drawn with: FOV at this frame's
     * partial tick, view bobbing, the damage tilt and nausea. The HUD visuals project through the same one.
     */
    @ModifyArg(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V"),
            index = 5)
    private Matrix4f coldplay$captureProjection(GraphicsResourceAllocator allocator, DeltaTracker deltaTracker,
                                                boolean blockOutline, Camera camera, Matrix4f view,
                                                Matrix4f projection, Matrix4f cullProjection, GpuBufferSlice fog,
                                                Vector4f fogColor, boolean worldFog) {
        Projection.capture(projection, view, camera.position());
        return projection;
    }

    @Inject(method = "pick", at = @At("HEAD"))
    private void coldplay$beginRaycast(float tickDelta, CallbackInfo callback) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        RotationManager rotations = RotationManager.getInstance();
        if (!rotations.isActive() || player == null || minecraft.getCameraEntity() != player) {
            return;
        }

        coldplay$raycastYaw = player.getYRot();
        coldplay$raycastPitch = player.getXRot();
        coldplay$raycastPreviousYaw = player.yRotO;
        coldplay$raycastPreviousPitch = player.xRotO;
        player.setYRot(rotations.getServerYaw());
        player.setXRot(rotations.getServerPitch());
        player.yRotO = rotations.getServerYaw();
        player.xRotO = rotations.getServerPitch();
        coldplay$raycastSpoofed = true;
    }

    @Inject(method = "pick", at = @At("RETURN"))
    private void coldplay$endRaycast(float tickDelta, CallbackInfo callback) {
        if (!coldplay$raycastSpoofed) {
            return;
        }

        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.setYRot(coldplay$raycastYaw);
            player.setXRot(coldplay$raycastPitch);
            player.yRotO = coldplay$raycastPreviousYaw;
            player.xRotO = coldplay$raycastPreviousPitch;
        }
        coldplay$raycastSpoofed = false;
    }
}
