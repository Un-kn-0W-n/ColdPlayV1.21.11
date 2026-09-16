package cn.timer.coldplay.client.module.impl.visuals;

import cn.timer.coldplay.client.setting.ColorSetting;
import cn.timer.coldplay.client.setting.Setting;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.Direction;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.IOException;
import java.util.Arrays;
import java.util.EnumSet;

public final class EntityESPSelfCheck {
    private EntityESPSelfCheck() {
    }

    public static void main(String[] args) {
        checkChamsMaterial();
        ColorSetting color = new ColorSetting("Color", 0x00123456);
        assert color.get() == 0xFF123456;
        color.set(0x80112233);
        assert color.get() == 0xFF112233;

        EntityESP esp = new EntityESP();
        Setting<?> players = setting(esp, "Players", null);
        Setting<?> playersColor = setting(esp, "Color", players);
        Setting<?> espOwner = setting(esp, "ESP", null);
        Setting<?> mode = setting(esp, "Mode", espOwner);
        assert esp.isOwnerSetting(players);
        assert esp.settingKey(players).equals("Players");
        assert esp.settingKey(playersColor).equals("Players.Color");
        assert esp.settingKey(mode).equals("ESP.Mode");

        assert EntityESP.inRange(64.0 * 64.0, 64.0);
        assert !EntityESP.inRange(64.0 * 64.0 + 0.01, 64.0);

        EntityESP.ScreenBox clipped = EntityESP.clipBounds(-5.0, -10.0, 20.0, 30.0,
                100, 80, 0xFF55FFFF);
        assert clipped != null;
        assert clipped.left() == 0 && clipped.top() == 0;
        assert clipped.width() == 20 && clipped.height() == 30;
        assert clipped.color() == 0xFF55FFFF;
        assert EntityESP.clipBounds(Double.NaN, 0.0, 1.0, 1.0, 100, 80, 0) == null;
        assert EntityESP.clipBounds(101.0, 0.0, 110.0, 10.0, 100, 80, 0) == null;
        assert EntityESP.clipBounds(20.0, 20.0, 10.0, 10.0, 100, 80, 0) == null;
    }

    private static void checkChamsMaterial() {
        var material = RenderTypes.textSeeThrough(net.minecraft.resources.Identifier.withDefaultNamespace("test"));
        var pipeline = material.pipeline();
        assert pipeline.getDepthTestFunction() == DepthTestFunction.NO_DEPTH_TEST;
        assert !pipeline.isWriteDepth();
        assert pipeline.getVertexFormat().contains(com.mojang.blaze3d.vertex.VertexFormatElement.UV0)
                : "Chams must retain the skin's texture coordinates";
        assert pipeline.getSamplers().contains("Sampler0") : "Chams must sample the skin texture";
        assert !material.isOutline() && material.outline().isEmpty();
        assert RenderTypes.entitySolid(net.minecraft.resources.Identifier.withDefaultNamespace("test"))
                .pipeline().getDepthTestFunction() != DepthTestFunction.NO_DEPTH_TEST;

        // Exercise real model vertices with the existing material, without needing a GPU.
        try (var storage = new ByteBufferBuilder(4096)) {
            var vertices = new BufferBuilder(storage, pipeline.getVertexFormatMode(), pipeline.getVertexFormat());
            var cube = new ModelPart.Cube(0, 0, 0, 0, 0, 8, 8, 8,
                    0, 0, 0, false, 64, 64, EnumSet.allOf(Direction.class));
            cube.compile(new PoseStack().last(), vertices, 0, 0, 0xFF55FFFF);
            try (var mesh = vertices.buildOrThrow()) {
                assert mesh.drawState().vertexCount() == 24 : "Chams material must accept all six model faces";
            }
        }

        try (var input = EntityESPSelfCheck.class.getClassLoader().getResourceAsStream(
                "cn/timer/coldplay/client/mixin/LivingEntityRendererMixin.class")) {
            assert input != null;
            var mixin = new ClassNode();
            new ClassReader(input).accept(mixin, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            assert mixin.methods.stream().anyMatch(method -> Arrays.stream(method.instructions.toArray())
                    .anyMatch(insn -> insn instanceof MethodInsnNode call
                            && call.owner.equals("net/minecraft/client/renderer/rendertype/RenderTypes")
                            && call.name.equals("textSeeThrough")))
                    : "Chams must select a textured see-through material, not a flat highlight";
            assert mixin.methods.stream().filter(method -> method.name.equals("coldplay$chamsTint"))
                    .flatMap(method -> Arrays.stream(method.instructions.toArray()))
                    .noneMatch(insn -> insn instanceof MethodInsnNode call && call.name.equals("usesChams"))
                    : "Chams must preserve the original model tint";
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static Setting<?> setting(EntityESP esp, String name, Setting<?> owner) {
        return esp.settings().stream()
                .filter(setting -> setting.name().equals(name) && esp.ownerOf(setting) == owner)
                .findFirst()
                .orElseThrow();
    }
}
