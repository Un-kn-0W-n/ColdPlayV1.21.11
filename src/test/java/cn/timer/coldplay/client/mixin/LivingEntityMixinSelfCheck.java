package cn.timer.coldplay.client.mixin;

import net.minecraft.SharedConstants;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks every assumption behind the body-yaw hooks in {@code LivingEntityMixin} against the real
 * 1.21.11 classes: first the bytecode shape the injection points rely on, then the vanilla behaviour
 * of {@code tickHeadTurn} and the renderer that produced the "only the head turns" symptom.
 */
public final class LivingEntityMixinSelfCheck {
    private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
    private static final String RENDER_STATE = "net/minecraft/client/renderer/entity/state/LivingEntityRenderState";
    private static final String EXTRACT_DESC = "(L" + LIVING + ";L" + RENDER_STATE + ";F)V";
    private static final String SOLVE_BODY_DESC = "(L" + LIVING + ";FF)F";
    private static final float SERVER_YAW = 120.0F;
    private static final float CAMERA_YAW = 0.0F;

    private LivingEntityMixinSelfCheck() {
    }

    public static void main(String[] args) {
        if (!LivingEntityMixinSelfCheck.class.desiredAssertionStatus()) {
            throw new IllegalStateException("Run with -ea; every check below is an assert");
        }
        try {
            run();
        } catch (IOException | ReflectiveOperationException failure) {
            throw new AssertionError("could not inspect the Minecraft classes", failure);
        }
    }

    private static void run() throws IOException, ReflectiveOperationException {
        checkTickBytecode();
        checkHeadTurnBytecode();
        checkPlayerHierarchyDoesNotOverrideHeadTurn();
        checkRendererBytecode();

        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        checkVanillaBodyFollowsCameraYaw();
        checkRendererDoesNotClampHead();
        checkBodyFollowsSpoofedYaw();
    }

    /** The sliced hook in {@code tick()} must see exactly the two body-yaw reads and none of the range checks. */
    private static void checkTickBytecode() throws IOException {
        ClassNode living = read(LivingEntity.class);
        MethodNode tick = method(living, "tick", "()V");
        MethodInsnNode aiStep = single(invocations(tick, LIVING, "aiStep", "()V"), "aiStep()");
        MethodInsnNode headTurn = single(invocations(tick, LIVING, "tickHeadTurn", "(F)V"), "tickHeadTurn(F)V");
        int aiStepIndex = tick.instructions.indexOf(aiStep);
        int headTurnIndex = tick.instructions.indexOf(headTurn);
        assert aiStepIndex < headTurnIndex : "aiStep() must run before tickHeadTurn()";

        List<MethodInsnNode> yawReads = invocations(tick, LIVING, "getYRot", "()F");
        long inSlice = yawReads.stream()
                .filter(insn -> between(tick, insn, aiStepIndex, headTurnIndex))
                .count();
        assert inSlice == 2
                : "coldplay$bodyTurnYaw expects two getYRot() reads between aiStep() and tickHeadTurn(), found " + inSlice;
        long afterHeadTurn = yawReads.stream()
                .filter(insn -> tick.instructions.indexOf(insn) > headTurnIndex)
                .count();
        assert afterHeadTurn >= 1
                : "the yRotO range checks that must stay camera-relative should follow tickHeadTurn()";
        assert fieldAccesses(tick, LIVING, "yBodyRot", Opcodes.PUTFIELD).isEmpty()
                : "tick() writes yBodyRot outside tickHeadTurn(); the hook would miss that write";
        assert fieldAccesses(method(living, "aiStep", "()V"), LIVING, "yBodyRot", Opcodes.PUTFIELD).isEmpty()
                : "aiStep() writes yBodyRot; the hook would miss that write";
    }

    /** The unsliced hook in {@code tickHeadTurn} must be the single camera-yaw read that clamps the body. */
    private static void checkHeadTurnBytecode() throws IOException {
        MethodNode headTurn = method(read(LivingEntity.class), "tickHeadTurn", "(F)V");
        assert invocations(headTurn, LIVING, "getYRot", "()F").size() == 1
                : "coldplay$headTurnYaw expects exactly one getYRot() read in tickHeadTurn()";
        assert !fieldAccesses(headTurn, LIVING, "yBodyRot", Opcodes.PUTFIELD).isEmpty()
                : "tickHeadTurn() should be where yBodyRot is written";
        assert fieldAccesses(headTurn, LIVING, "yHeadRot", Opcodes.GETFIELD).isEmpty()
                : "tickHeadTurn() reads yHeadRot; the render-only head override would then have driven the body";
        assert invocations(headTurn, LIVING, "getMaxHeadRotationRelativeToBody", "()F").size() == 1
                : "tickHeadTurn() should clamp the body to getMaxHeadRotationRelativeToBody()";
    }

    /** The hook targets LivingEntity, so no class on the local player's chain may replace tickHeadTurn. */
    private static void checkPlayerHierarchyDoesNotOverrideHeadTurn() throws ClassNotFoundException {
        String[] chain = {
                "net.minecraft.world.entity.Avatar",
                "net.minecraft.world.entity.player.Player",
                "net.minecraft.client.player.AbstractClientPlayer",
                "net.minecraft.client.player.LocalPlayer"
        };
        for (String name : chain) {
            Class<?> type = Class.forName(name, false, LivingEntityMixinSelfCheck.class.getClassLoader());
            assert LivingEntity.class.isAssignableFrom(type) : name + " is not a LivingEntity";
            assert !declares(type, "tickHeadTurn", float.class) : name + " overrides tickHeadTurn(F)V";
        }
    }

    /** The renderer takes the body from yBodyRot and never clamps the head against it. */
    private static void checkRendererBytecode() throws IOException {
        ClassNode renderer = read(LivingEntityRenderer.class);
        MethodNode extract = method(renderer, "extractRenderState", EXTRACT_DESC);
        String rendererName = renderer.name;
        assert invocations(extract, rendererName, "solveBodyRot", SOLVE_BODY_DESC).size() == 1
                : "extractRenderState() should derive bodyRot through solveBodyRot()";
        for (String field : new String[] {"bodyRot", "yRot", "xRot"}) {
            assert !fieldAccesses(extract, RENDER_STATE, field, Opcodes.PUTFIELD).isEmpty()
                    : "extractRenderState() no longer writes state." + field;
        }
        assert invocations(extract, "net/minecraft/util/Mth", "clamp", "(FFF)F").isEmpty()
                : "extractRenderState() clamps the head yaw; the symptom analysis would be wrong";
        MethodNode solve = method(renderer, "solveBodyRot", SOLVE_BODY_DESC);
        assert !fieldAccesses(solve, LIVING, "yBodyRot", Opcodes.GETFIELD).isEmpty()
                : "solveBodyRot() should read yBodyRot";
    }

    /** Bug reproduction: vanilla keeps the body within 50 degrees of the camera, not of the drawn head. */
    private static void checkVanillaBodyFollowsCameraYaw() {
        Stand stand = new Stand();
        assert stand.headLimit() == 50.0F : "unexpected head limit " + stand.headLimit();

        stand.aimCamera(CAMERA_YAW);
        stand.turnStanding();
        assert stand.yBodyRot == 0.0F : "standing still the body should stay put, got " + stand.yBodyRot;
        stand.turnSwinging();
        assert stand.yBodyRot == 0.0F : "swinging pulls the body toward the camera yaw, got " + stand.yBodyRot;
        float drawnHead = Mth.wrapDegrees(SERVER_YAW - stand.yBodyRot);
        assert Math.abs(drawnHead) == 120.0F
                : "the head would be drawn " + drawnHead + " degrees off the body; expected the 120 degree twist";

        stand.aimCamera(SERVER_YAW);
        stand.turnStanding();
        assert stand.yBodyRot == 70.0F
                : "turning the camera itself clamps the body to yaw - 50, got " + stand.yBodyRot;
    }

    /** The renderer would only rescue the body if solveBodyRot clamped; it passes yBodyRot through. */
    private static void checkRendererDoesNotClampHead() throws ReflectiveOperationException {
        Stand stand = new Stand();
        stand.yBodyRot = 0.0F;
        stand.yBodyRotO = 0.0F;
        float bodyRot = solveBodyRot(stand, SERVER_YAW, 1.0F);
        assert bodyRot == 0.0F : "solveBodyRot() moved the body for a non-riding entity: " + bodyRot;
        assert Mth.wrapDegrees(SERVER_YAW - bodyRot) == 120.0F;

        stand.yBodyRot = 70.0F;
        stand.yBodyRotO = 70.0F;
        assert solveBodyRot(stand, SERVER_YAW, 1.0F) == 70.0F;
    }

    /** Fix contract: once the body logic reads the server yaw, head and body stay within the vanilla limit. */
    private static void checkBodyFollowsSpoofedYaw() {
        Stand stand = new Stand();
        stand.aimCamera(SERVER_YAW);
        stand.turnStanding();
        float drawnHead = Mth.wrapDegrees(SERVER_YAW - stand.yBodyRot);
        assert stand.yBodyRot == 70.0F && drawnHead == 50.0F
                : "body " + stand.yBodyRot + ", head offset " + drawnHead;

        stand.turnSwinging();
        assert stand.yBodyRot == 85.0F : "swinging should ease the body toward the server yaw, got " + stand.yBodyRot;
        for (int tick = 0; tick < 40; tick++) {
            stand.turnSwinging();
        }
        assert Math.abs(Mth.wrapDegrees(SERVER_YAW - stand.yBodyRot)) < 1.0F
                : "body never settled on the server yaw: " + stand.yBodyRot;
    }

    /**
     * A bare LivingEntity is the faithful stand-in for the local player: nothing between LocalPlayer and
     * LivingEntity overrides tickHeadTurn (checked above), whereas Mob and ArmorStand do.
     */
    private static final class Stand extends LivingEntity {
        private Stand() {
            super(EntityType.PLAYER, null);
            yBodyRot = 0.0F;
            yBodyRotO = 0.0F;
        }

        void aimCamera(float yaw) {
            setYRot(yaw);
            yRotO = yaw;
        }

        /** Standing still, tick() hands tickHeadTurn the current body yaw. */
        void turnStanding() {
            tickHeadTurn(yBodyRot);
        }

        /** While attackAnim is positive, tick() hands tickHeadTurn getYRot() instead. */
        void turnSwinging() {
            tickHeadTurn(getYRot());
        }

        float headLimit() {
            return getMaxHeadRotationRelativeToBody();
        }

        @Override
        public HumanoidArm getMainArm() {
            return HumanoidArm.RIGHT;
        }
    }

    private static float solveBodyRot(LivingEntity entity, float headYaw, float partialTick)
            throws ReflectiveOperationException {
        Method solve = LivingEntityRenderer.class.getDeclaredMethod(
                "solveBodyRot", LivingEntity.class, float.class, float.class);
        solve.setAccessible(true);
        return (float) solve.invoke(null, entity, headYaw, partialTick);
    }

    private static ClassNode read(Class<?> type) throws IOException {
        String resource = type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("missing " + resource);
            }
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    private static MethodNode method(ClassNode owner, String name, String desc) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) {
                return method;
            }
        }
        throw new AssertionError(owner.name + " has no " + name + desc);
    }

    private static List<MethodInsnNode> invocations(MethodNode method, String owner, String name, String desc) {
        List<MethodInsnNode> found = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(owner)
                    && call.name.equals(name) && call.desc.equals(desc)) {
                found.add(call);
            }
        }
        return found;
    }

    private static List<FieldInsnNode> fieldAccesses(MethodNode method, String owner, String name, int opcode) {
        List<FieldInsnNode> found = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof FieldInsnNode field && field.getOpcode() == opcode
                    && field.owner.equals(owner) && field.name.equals(name)) {
                found.add(field);
            }
        }
        return found;
    }

    private static <T> T single(List<T> found, String what) {
        assert found.size() == 1 : "expected exactly one " + what + ", found " + found.size();
        return found.get(0);
    }

    private static boolean between(MethodNode method, AbstractInsnNode insn, int start, int end) {
        int index = method.instructions.indexOf(insn);
        return index > start && index < end;
    }

    private static boolean declares(Class<?> type, String name, Class<?>... parameters) {
        try {
            type.getDeclaredMethod(name, parameters);
            return true;
        } catch (NoSuchMethodException missing) {
            return false;
        }
    }
}
