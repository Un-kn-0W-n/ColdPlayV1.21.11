package cn.timer.coldplay.client.module.impl.combat;

import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.util.StringUtil;
import net.minecraft.world.level.GameType;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/**
 * Pins the vanilla facts the AntiBot heuristics rest on, so a Minecraft update that
 * invalidates one of them fails here instead of silently turning a check into dead code.
 *
 * The 1.21.11 implementation this replaced scored six signals, three of which could not
 * fire at all against a normally spawned player; the assertions below are the evidence.
 */
public final class AntiBotSignalFactsCheck {
    private AntiBotSignalFactsCheck() {
    }

    public static void main(String[] args) {
        gameModeIsNeverNull();
        theDrawnTabListIsOnlyTheListedSet();
        aBareProfileCarriesNoSkin();
        negativeLatencyIsAVanillaState();
        theWireDoesNotValidateNames();
        nameValidationRejectsWhatBotsUse();
        System.out.println("AntiBotSignalFactsCheck ok");
    }

    /**
     * PlayerInfo.gameMode is initialised to GameType.DEFAULT_MODE and the packet decoder maps
     * out-of-range ids to it, so "missing game mode" is not an observable state.
     */
    private static void gameModeIsNeverNull() {
        assert GameType.DEFAULT_MODE != null;
        assert GameType.DEFAULT_MODE == GameType.SURVIVAL;
        for (int id = -4; id < 8; id++) {
            assert GameType.byId(id) != null : "GameType.byId(" + id + ") returned null";
        }
    }

    /**
     * Vanilla renders latency below zero as the "unknown ping" icon, so it is a server
     * telling the client it has no measurement - not a sign of a fake player.
     */
    private static void negativeLatencyIsAVanillaState() {
        assert !AntiBot.zeroPing(true, -1, true, 200)
                : "unknown ping must not be read as a bot signal";
        assert AntiBot.zeroPing(true, 0, true, 200);
        assert !AntiBot.zeroPing(false, 0, true, 200) : "an absent tab entry is Not In Tab's job";
        assert !AntiBot.zeroPing(true, 1, true, 200);
        assert !AntiBot.zeroPing(true, 0, false, 200)
                : "a server that pings nobody tells us nothing";
        assert !AntiBot.zeroPing(true, 0, true, 20)
                : "a player who just joined has not been pinged yet";
    }

    /**
     * The tab list the player actually sees is the listed set, not every profile the client holds.
     * That gap is what lets a server spawn a player entity nobody can see in tab, and it is the
     * whole basis of the UNLISTED check - if vanilla ever draws getOnlinePlayers instead, the
     * check loses its meaning and this assertion should be what tells us.
     */
    private static void theDrawnTabListIsOnlyTheListedSet() {
        assert calls(PlayerTabOverlay.class, "getListedOnlinePlayers")
                : "PlayerTabOverlay no longer draws the listed set";
        assert !calls(PlayerTabOverlay.class, "getOnlinePlayers")
                : "PlayerTabOverlay now draws every known profile, so UNLISTED means nothing";
    }

    /** A profile the server invents for a bot has an empty property map; a real account does not. */
    private static void aBareProfileCarriesNoSkin() {
        UUID id = UUID.randomUUID();
        assert new GameProfile(id, "Bot").properties().get("textures").isEmpty();

        PropertyMap properties = new PropertyMap(ImmutableMultimap.of(
                "textures", new Property("textures", "eyJ0aW1lc3RhbXAiOjF9")));
        assert !new GameProfile(id, "Real", properties).properties().get("textures").isEmpty();
    }

    private static boolean calls(Class<?> owner, String method) {
        ClassNode node = new ClassNode();
        String resource = owner.getName().replace('.', '/') + ".class";
        try (InputStream stream = owner.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new AssertionError("could not read bytecode for " + owner.getName());
            }
            new ClassReader(stream.readAllBytes()).accept(node, ClassReader.SKIP_FRAMES);
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
        for (MethodNode methodNode : node.methods) {
            for (var instruction : methodNode.instructions) {
                if (instruction instanceof MethodInsnNode call && call.name.equals(method)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** GameProfile itself accepts names the client would never accept from a keyboard. */
    private static void theWireDoesNotValidateNames() {
        UUID id = UUID.randomUUID();
        GameProfile formatted = new GameProfile(id, "§cBot");
        assert formatted.name().equals("§cBot");
        assert !StringUtil.isValidPlayerName(formatted.name())
                : "colour-coded names must stay detectable";

        GameProfile empty = new GameProfile(id, "");
        assert empty.name().isEmpty();
        assert AntiBot.invalidName(empty.name()) : "empty names must be flagged";
    }

    private static void nameValidationRejectsWhatBotsUse() {
        assert !AntiBot.invalidName("Notch");
        assert !AntiBot.invalidName("Xx_Miner_xX");
        assert !AntiBot.invalidName("a1234567890bcdef");          // exactly 16
        assert AntiBot.invalidName("a1234567890bcdefg");          // 17
        assert AntiBot.invalidName("");
        assert AntiBot.invalidName(null);
        assert AntiBot.invalidName("has space");
        assert AntiBot.invalidName("§cBot");
        assert AntiBot.invalidName("Bót");

        // Anything StringUtil accepts and that is underscore-or-alphanumeric must pass.
        assert StringUtil.isValidPlayerName("Notch") && !AntiBot.invalidName("Notch");
    }
}
