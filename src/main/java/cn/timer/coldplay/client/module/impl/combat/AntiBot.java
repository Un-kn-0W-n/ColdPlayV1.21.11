package cn.timer.coldplay.client.module.impl.combat;

import cn.timer.coldplay.client.module.Category;
import cn.timer.coldplay.client.module.Module;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.util.StringUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Npc;
import net.minecraft.world.entity.player.Player;
import org.lwjgl.glfw.GLFW;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class AntiBot extends Module {
    private static final int SCAN_INTERVAL_TICKS = 5;
    private static final int STABLE_SCANS = 2;
    private static final int VALID_GRACE_SCANS = 4;
    private static final UUID NIL_UUID = new UUID(0L, 0L);
    private static final AntiBot INSTANCE = new AntiBot();
    private static final DetectionResult VALID = new DetectionResult(Classification.VALID, Set.of());
    private static final DetectionResult UNKNOWN = new DetectionResult(Classification.UNKNOWN, Set.of());
    private static final DetectionResult NPC = new DetectionResult(
            Classification.BOT, Set.of(DetectionReason.VANILLA_NPC));

    private final Map<Integer, Track> tracks = new HashMap<>();
    private final Map<UUID, Integer> uuidCounts = new HashMap<>();
    private final Map<String, Integer> nameCounts = new HashMap<>();
    private ClientLevel trackedLevel;
    private long scanNumber;
    private int scanCooldown;

    private AntiBot() {
        super("AntiBot", "Filters bots and fake players from combat targets", Category.COMBAT,
                GLFW.GLFW_KEY_UNKNOWN);
    }

    public static AntiBot get() {
        return INSTANCE;
    }

    public DetectionResult result(Entity entity) {
        if (entity instanceof Npc) {
            return NPC;
        }
        if (!(entity instanceof Player player)) {
            return VALID;
        }
        if (player.level() != trackedLevel) {
            return UNKNOWN;
        }
        Track track = tracks.get(player.getId());
        return track != null && Objects.equals(track.uuid, player.getUUID()) ? track.result : UNKNOWN;
    }

    public boolean canTarget(Entity entity) {
        return allows(enabled(), result(entity).classification());
    }

    public void tick(Minecraft minecraft) {
        ClientLevel level = minecraft.level;
        ClientPacketListener connection = minecraft.getConnection();
        if (level == null || connection == null) {
            clear();
            return;
        }
        if (level != trackedLevel) {
            clear();
            trackedLevel = level;
        }
        if (scanCooldown-- > 0) {
            return;
        }
        scanCooldown = SCAN_INTERVAL_TICKS - 1;
        scan(level, connection);
    }

    private void scan(ClientLevel level, ClientPacketListener connection) {
        scanNumber++;
        uuidCounts.clear();
        nameCounts.clear();
        for (Player player : level.players()) {
            if (player.isRemoved()) {
                continue;
            }
            uuidCounts.merge(player.getUUID(), 1, Integer::sum);
            String name = profileName(player.getGameProfile());
            if (name != null) {
                nameCounts.merge(name.toLowerCase(Locale.ROOT), 1, Integer::sum);
            }
        }

        for (Player player : level.players()) {
            if (player.isRemoved()) {
                continue;
            }
            UUID uuid = player.getUUID();
            Track track = tracks.get(player.getId());
            if (track == null || !Objects.equals(track.uuid, uuid)) {
                track = new Track(uuid);
                tracks.put(player.getId(), track);
            }
            track.lastSeenScan = scanNumber;
            update(track, player, connection);
        }
        tracks.values().removeIf(track -> track.lastSeenScan != scanNumber);
    }

    private void update(Track track, Player player, ClientPacketListener connection) {
        if (track.result.classification() == Classification.BOT) {
            return;
        }

        EnumSet<DetectionReason> reasons = track.observedReasons;
        reasons.clear();
        int weakSignals = 0;
        boolean strongSignal = false;
        UUID uuid = player.getUUID();
        GameProfile profile = player.getGameProfile();
        String profileName = profileName(profile);

        if (profile == null) {
            reasons.add(DetectionReason.INVALID_PROFILE);
            strongSignal = true;
        }
        if (uuid == null || NIL_UUID.equals(uuid) || profile == null
                || profile.id() == null || !uuid.equals(profile.id())) {
            reasons.add(DetectionReason.INVALID_UUID);
            strongSignal = true;
        }
        if (profileName == null || !StringUtil.isValidPlayerName(profileName)) {
            reasons.add(DetectionReason.INVALID_NAME);
            strongSignal = true;
        }

        PlayerInfo playerInfo = uuid == null ? null : connection.getPlayerInfo(uuid);
        if (playerInfo == null) {
            reasons.add(DetectionReason.NOT_IN_PLAYER_LIST);
            weakSignals++;
        } else {
            GameProfile listedProfile = playerInfo.getProfile();
            if (listedProfile == null || !Objects.equals(uuid, listedProfile.id())
                    || !Objects.equals(profileName, profileName(listedProfile))) {
                reasons.add(DetectionReason.PROFILE_MISMATCH);
                strongSignal = true;
            }
            if (playerInfo.getGameMode() == null) {
                reasons.add(DetectionReason.MISSING_GAME_MODE);
                weakSignals++;
            }
            if (playerInfo.getLatency() < 0) {
                reasons.add(DetectionReason.INVALID_LATENCY);
                weakSignals++;
            }
        }

        boolean duplicateUuid = uuidCounts.getOrDefault(uuid, 0) > 1;
        if (duplicateUuid) {
            reasons.add(DetectionReason.DUPLICATE_UUID);
        }
        if (profileName != null
                && nameCounts.getOrDefault(profileName.toLowerCase(Locale.ROOT), 0) > 1) {
            reasons.add(DetectionReason.DUPLICATE_NAME);
            weakSignals++;
        }

        if (strongSignal) {
            track.cleanScans = 0;
            track.suspiciousScans = 0;
            track.isolatedWeakScans = 0;
            publish(track, nextClassification(track.result.classification(), true, 0, 0, 0), reasons);
            return;
        }
        if (suspicious(duplicateUuid, weakSignals)) {
            track.cleanScans = 0;
            track.isolatedWeakScans = 0;
            track.suspiciousScans++;
            if (weakSignals >= 2) {
                reasons.add(DetectionReason.COMBINED_SIGNALS);
            }
        } else if (weakSignals == 0) {
            track.suspiciousScans = 0;
            track.isolatedWeakScans = 0;
            track.cleanScans++;
        } else {
            track.cleanScans = 0;
            track.suspiciousScans = 0;
            track.isolatedWeakScans++;
        }

        publish(track, nextClassification(track.result.classification(), false,
                track.suspiciousScans, track.cleanScans, track.isolatedWeakScans), reasons);
    }

    private void publish(Track track, Classification classification, Set<DetectionReason> reasons) {
        if (classification != track.result.classification() || !track.result.reasons().equals(reasons)) {
            track.result = new DetectionResult(classification, reasons);
        }
    }

    private void clear() {
        tracks.clear();
        uuidCounts.clear();
        nameCounts.clear();
        trackedLevel = null;
        scanNumber = 0L;
        scanCooldown = 0;
    }

    private static String profileName(GameProfile profile) {
        return profile == null || profile.name() == null || profile.name().isEmpty()
                ? null : profile.name();
    }

    static boolean suspicious(boolean duplicateUuid, int weakSignals) {
        return duplicateUuid || weakSignals >= 2;
    }

    static Classification nextClassification(Classification current, boolean strongSignal,
                                               int suspiciousScans, int cleanScans,
                                               int isolatedWeakScans) {
        if (current == Classification.BOT || strongSignal || suspiciousScans >= STABLE_SCANS) {
            return Classification.BOT;
        }
        if (cleanScans >= STABLE_SCANS) {
            return Classification.VALID;
        }
        if (current == Classification.VALID
                && isolatedWeakScans > 0 && isolatedWeakScans <= VALID_GRACE_SCANS) {
            return Classification.VALID;
        }
        return Classification.UNKNOWN;
    }

    static boolean allows(boolean enabled, Classification classification) {
        return !enabled || classification == Classification.VALID;
    }

    public enum Classification {
        VALID,
        BOT,
        UNKNOWN
    }

    public enum DetectionReason {
        VANILLA_NPC,
        INVALID_PROFILE,
        INVALID_UUID,
        INVALID_NAME,
        PROFILE_MISMATCH,
        NOT_IN_PLAYER_LIST,
        MISSING_GAME_MODE,
        INVALID_LATENCY,
        DUPLICATE_UUID,
        DUPLICATE_NAME,
        COMBINED_SIGNALS
    }

    public record DetectionResult(Classification classification, Set<DetectionReason> reasons) {
        public DetectionResult {
            Objects.requireNonNull(classification, "classification");
            reasons = Set.copyOf(reasons);
        }
    }

    private static final class Track {
        private final UUID uuid;
        private final EnumSet<DetectionReason> observedReasons = EnumSet.noneOf(DetectionReason.class);
        private DetectionResult result = UNKNOWN;
        private long lastSeenScan;
        private int cleanScans;
        private int suspiciousScans;
        private int isolatedWeakScans;

        private Track(UUID uuid) {
            this.uuid = uuid;
        }
    }
}
