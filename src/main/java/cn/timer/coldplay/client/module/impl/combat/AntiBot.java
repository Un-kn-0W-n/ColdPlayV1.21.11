package cn.timer.coldplay.client.module.impl.combat;

import cn.timer.coldplay.client.module.Category;
import cn.timer.coldplay.client.module.Module;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.lwjgl.glfw.GLFW;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public final class AntiBot extends Module {
    private static final int SPAWN_TICKS = 2;        // judged on the tick it appears
    private static final int PING_GRACE_TICKS = 20;  // 0 ms until the first latency update
    private static final double SPAWN_RADIUS = 5.0;  // blocks, for the invisible-spawn check
    // Only the 16-character limit is enforced on the wire, so the alphabet is a real check.
    private static final Pattern VALID_NAME = Pattern.compile("\\w{1,16}");
    private static final UUID NIL_UUID = new UUID(0L, 0L);
    private static final AntiBot INSTANCE = new AntiBot();

    private final Map<Integer, Track> tracks = new HashMap<>();
    // Scan state is instance state because isBot() judges on demand between scans.
    private final Map<UUID, Integer> uuidCounts = new HashMap<>();
    private final Map<String, Integer> nameCounts = new HashMap<>();
    private final Map<String, UUID> tabIdByName = new HashMap<>();
    private ClientLevel trackedLevel;
    private boolean othersReportPing;
    private boolean othersHaveSkin;
    private long scanNumber;

    private AntiBot() {
        super("AntiBot", "Filters bots and fake players from combat targets", Category.COMBAT,
                GLFW.GLFW_KEY_UNKNOWN);
    }

    public static AntiBot get() {
        return INSTANCE;
    }

    /** Judged on demand and memoised for the rest of the tick, so the render path pays once. */
    public boolean isBot(Entity entity) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener connection = minecraft.getConnection();
        if (!enabled() || connection == null || !(entity instanceof Player player)
                || player == minecraft.player || player.level() != trackedLevel) {
            return false;
        }
        Track track = track(player, minecraft.player);
        if (track.judgedScan != scanNumber) {
            track.judgedScan = scanNumber;
            track.bot = !evaluate(observe(track, player, connection)).isEmpty();
        }
        return track.bot;
    }

    public void tick(Minecraft minecraft) {
        ClientLevel level = minecraft.level;
        ClientPacketListener connection = minecraft.getConnection();
        if (!enabled() || level == null || connection == null) {
            clear();
            return;
        }
        if (level != trackedLevel) {
            clear();
            trackedLevel = level;
        }
        scan(level, connection, minecraft.player);
    }

    @Override
    protected void onDisable() {
        clear();
    }

    /** Refreshes the facts a verdict reads; the verdict itself is left to {@link #isBot}. */
    private void scan(ClientLevel level, ClientPacketListener connection, Player localPlayer) {
        scanNumber++;
        readTab(connection);
        uuidCounts.clear();
        nameCounts.clear();
        for (Player player : level.players()) {
            if (player.isRemoved()) continue;
            // The local player is counted but never judged, so a bot wearing your name duplicates.
            uuidCounts.merge(player.getUUID(), 1, Integer::sum);
            String name = profileName(player.getGameProfile());
            if (name != null) nameCounts.merge(name.toLowerCase(Locale.ROOT), 1, Integer::sum);
            if (player == localPlayer) continue;
            Track track = track(player, localPlayer);
            track.lastSeenScan = scanNumber;
            track.trackedTicks++;
        }
        tracks.values().removeIf(track -> track.lastSeenScan != scanNumber);
    }

    private void readTab(ClientPacketListener connection) {
        tabIdByName.clear();
        othersReportPing = false;
        othersHaveSkin = false;
        for (PlayerInfo info : connection.getOnlinePlayers()) {
            GameProfile profile = info.getProfile();
            String name = profileName(profile);
            if (name != null) tabIdByName.putIfAbsent(name.toLowerCase(Locale.ROOT), profile.id());
            othersReportPing |= info.getLatency() > 0;
            othersHaveSkin |= hasSkin(profile);
        }
    }

    private Track track(Player player, Player localPlayer) {
        Track track = tracks.get(player.getId());
        if (track != null && Objects.equals(track.uuid, player.getUUID())) return track;
        // A recycled entity id must not inherit the previous occupant's history. Spawning
        // invisible next to you is recorded here because it is unrecoverable once the entity ages.
        boolean invisibleSpawn = player.tickCount <= SPAWN_TICKS && player.isInvisible()
                && localPlayer != null
                && localPlayer.distanceToSqr(player) <= SPAWN_RADIUS * SPAWN_RADIUS;
        track = new Track(player.getUUID(), invisibleSpawn);
        tracks.put(player.getId(), track);
        return track;
    }

    private Observation observe(Track track, Player player, ClientPacketListener connection) {
        UUID uuid = player.getUUID();
        String name = profileName(player.getGameProfile());
        String key = name == null ? null : name.toLowerCase(Locale.ROOT);
        PlayerInfo info = uuid == null ? null : connection.getPlayerInfo(uuid);
        // Whoever the tab list names for this name is the real holder of it.
        UUID tabIdForName = key == null ? null : tabIdByName.get(key);
        return new Observation(uuid, name, info != null,
                info != null && connection.getListedOnlinePlayers().contains(info),
                info == null ? null : profileName(info.getProfile()),
                info == null ? 0 : info.getLatency(),
                othersReportPing, hasSkin(player.getGameProfile()), othersHaveSkin,
                uuidCounts.getOrDefault(uuid, 0) > 1,
                key != null && nameCounts.getOrDefault(key, 0) > 1
                        && (tabIdForName == null || !tabIdForName.equals(uuid)),
                track.trackedTicks, track.invisibleSpawn, player.isInvisible());
    }

    /**
     * The evidence, not a score: every check is a hard block on its own and none can be switched
     * off, which is why each one stays silent where its signal carries no information. Free of
     * Minecraft state so the verdict itself is testable.
     */
    static EnumSet<DetectionReason> evaluate(Observation obs) {
        EnumSet<DetectionReason> reasons = EnumSet.noneOf(DetectionReason.class);
        if (obs.uuid() == null || NIL_UUID.equals(obs.uuid())) reasons.add(DetectionReason.INVALID_UUID);
        if (invalidName(obs.name())) reasons.add(DetectionReason.INVALID_NAME);
        if (!obs.inTab()) reasons.add(DetectionReason.NOT_IN_PLAYER_LIST);
        // A bot must have a profile to spawn; the server hides it by sending that one unlisted.
        if (obs.inTab() && !obs.listed()) reasons.add(DetectionReason.UNLISTED);
        if (obs.inTab() && !obs.hasSkin() && obs.othersHaveSkin()) reasons.add(DetectionReason.NO_SKIN);
        // Reachable once the server re-adds the uuid under a new name; the entity profile is fixed.
        if (obs.inTab() && !Objects.equals(obs.name(), obs.tabName())) reasons.add(DetectionReason.PROFILE_MISMATCH);
        if (obs.duplicateUuid()) reasons.add(DetectionReason.DUPLICATE_UUID);
        if (obs.duplicateName()) reasons.add(DetectionReason.DUPLICATE_NAME);
        if (zeroPing(obs.inTab(), obs.latency(), obs.othersReportPing(), obs.trackedTicks())) {
            reasons.add(DetectionReason.ZERO_PING);
        }
        if (obs.invisibleSpawn() && obs.invisible()) reasons.add(DetectionReason.INVISIBLE_SPAWN);
        return reasons;
    }

    /**
     * A tab entry stuck at exactly 0 ms while the server is measuring everyone else. Latency below
     * zero is vanilla's "no measurement yet" state, so only an exact zero counts.
     */
    static boolean zeroPing(boolean inTab, int latency, boolean othersReportPing, int trackedTicks) {
        return inTab && latency == 0 && othersReportPing && trackedTicks > PING_GRACE_TICKS;
    }

    /** Empty, over 16 characters, or anything outside A-Z, a-z, 0-9 and underscore. */
    static boolean invalidName(String name) {
        return name == null || !VALID_NAME.matcher(name).matches();
    }

    /** A real account carries a signed textures property; {@code new GameProfile} carries none. */
    private static boolean hasSkin(GameProfile profile) {
        return profile != null && !profile.properties().get("textures").isEmpty();
    }

    private static String profileName(GameProfile profile) {
        return profile == null || profile.name() == null || profile.name().isEmpty()
                ? null : profile.name();
    }

    private void clear() {
        tracks.clear();
        uuidCounts.clear();
        nameCounts.clear();
        tabIdByName.clear();
        trackedLevel = null;
        scanNumber = 0L;
    }

    public enum DetectionReason {
        NOT_IN_PLAYER_LIST,
        UNLISTED,
        NO_SKIN,
        INVALID_NAME,
        DUPLICATE_NAME,
        DUPLICATE_UUID,
        ZERO_PING,
        INVISIBLE_SPAWN,
        INVALID_UUID,
        PROFILE_MISMATCH
    }

    /** Everything the client can see about one player entity, flattened for the verdict. */
    record Observation(UUID uuid, String name, boolean inTab, boolean listed, String tabName,
                       int latency, boolean othersReportPing, boolean hasSkin,
                       boolean othersHaveSkin, boolean duplicateUuid, boolean duplicateName,
                       int trackedTicks, boolean invisibleSpawn, boolean invisible) {
    }

    private static final class Track {
        private final UUID uuid;
        private final boolean invisibleSpawn;
        private boolean bot;
        private int trackedTicks;
        private long lastSeenScan;
        private long judgedScan = -1L;

        private Track(UUID uuid, boolean invisibleSpawn) {
            this.uuid = uuid;
            this.invisibleSpawn = invisibleSpawn;
        }
    }
}
