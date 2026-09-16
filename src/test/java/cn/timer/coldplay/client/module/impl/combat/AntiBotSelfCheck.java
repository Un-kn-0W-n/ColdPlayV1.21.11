package cn.timer.coldplay.client.module.impl.combat;

import cn.timer.coldplay.client.module.impl.combat.AntiBot.DetectionReason;
import cn.timer.coldplay.client.module.impl.combat.AntiBot.Observation;
import cn.timer.coldplay.client.setting.Setting;

import java.util.EnumSet;
import java.util.UUID;

public final class AntiBotSelfCheck {
    private AntiBotSelfCheck() {
    }

    public static void main(String[] args) {
        AntiBotSignalFactsCheck.main(args);
        theModuleHasNoConfiguration();
        wellFormedBotsAreCaught();
        realPlayersSurvive();
        theLimitOfStaticSignals();
        System.out.println("AntiBotSelfCheck ok");
    }

    /** AntiBot is standalone: every check always runs, so the only setting is the base keybind. */
    private static void theModuleHasNoConfiguration() {
        for (Setting<?> setting : AntiBot.get().settings()) {
            assert setting.name().equals("Keybind")
                    : "AntiBot should have no configuration, found " + setting.name();
        }
    }

    /** Regression cover: every one of these is a bot that a well-formed profile alone would hide. */
    private static void wellFormedBotsAreCaught() {
        // Spawned beside you, invisible, tab entry and profile both well formed.
        UUID id = UUID.randomUUID();
        assert bot(observation(id, "Xx_Miner_xX").inTab("Xx_Miner_xX", 0)
                .spawnedInvisible().build(), DetectionReason.INVISIBLE_SPAWN);

        // The one that mattered: a profile the server sent unlisted, so the bot can spawn but
        // never shows up in the tab list the player sees.
        assert bot(observation(id, "Watcher").inTab("Watcher", 45).unlisted().build(),
                DetectionReason.UNLISTED);

        // A profile built as new GameProfile(uuid, name) carries no signed textures property.
        assert bot(observation(id, "Skinless").inTab("Skinless", 45).noSkin().build(),
                DetectionReason.NO_SKIN);

        // Entity kept alive after the server dropped its tab entry.
        assert bot(observation(id, "Bot_7712").notInTab().build(),
                DetectionReason.NOT_IN_PLAYER_LIST);

        // A second copy of a real player's name; the tab entry names the other one.
        assert bot(observation(id, "Notch").inTab("Notch", 45)
                .copiesOfName(2, UUID.randomUUID()).build(),
                DetectionReason.DUPLICATE_NAME);

        // Two entities sharing one uuid: vanilla warns but still adds both to level.players().
        assert bot(observation(id, "Twin").inTab("Twin", 45)
                .copiesOfUuid(2).build(), DetectionReason.DUPLICATE_UUID);

        // Colour codes are legal on the wire; only the client rejects them.
        assert bot(observation(id, "§cBot").inTab("§cBot", 45).build(),
                DetectionReason.INVALID_NAME);

        // Tab entry removed and re-added under a different name while the entity lived on.
        assert bot(observation(id, "Steve").inTab("Alex", 45).build(),
                DetectionReason.PROFILE_MISMATCH);

        assert bot(observation(new UUID(0L, 0L), "Nil").inTab("Nil", 45)
                .build(), DetectionReason.INVALID_UUID);
    }

    private static void realPlayersSurvive() {
        UUID id = UUID.randomUUID();
        assert valid(observation(id, "Notch").inTab("Notch", 45).build());

        // A server that reports 0 ms for every player is measuring nobody, so ping says nothing.
        assert valid(observation(id, "Alex").inTab("Alex", 0).pingsNobody().build());

        // Someone who joined a moment ago has not been pinged yet.
        assert valid(observation(id, "Fresh").inTab("Fresh", 0).justJoined().build());

        // Vanilla renders latency below zero as the unknown-ping icon; it is a server saying
        // "no measurement", not a fake player.
        assert valid(observation(id, "Alex").inTab("Alex", -1).build());

        // Drank invisibility long after spawning, so no invisible-spawn record exists.
        assert valid(observation(id, "Sneaky").inTab("Sneaky", 60)
                .invisibleNow().build());

        // Names at the exact 16-character limit are legal.
        assert valid(observation(id, "a1234567890bcdef")
                .inTab("a1234567890bcdef", 45).build());

        // One loaded copy of a name is not a duplicate, even with no tab entry for the name.
        assert valid(observation(id, "Solo").inTab("Solo", 45)
                .copiesOfName(1, null).build());

        // An offline-mode server where no account has a signed skin: the check says nothing.
        assert valid(observation(id, "Cracked").inTab("Cracked", 45)
                .nobodyHasSkins().build());

        // Two copies where the tab entry names *this* one: this is the real player.
        assert valid(observation(id, "Notch").inTab("Notch", 45)
                .copiesOfName(2, id).build());
    }

    /**
     * Honest limit: a bot that is in the tab list, correctly named, uniquely identified, visible
     * and handed a plausible ping is indistinguishable from a player by static signals alone.
     */
    private static void theLimitOfStaticSignals() {
        UUID id = UUID.randomUUID();
        assert valid(observation(id, "Legit_Player").inTab("Legit_Player", 42)
                .build()) : "documented blind spot, not a passing grade";

        // The common case, a bot the server never pings, is still caught.
        assert bot(observation(id, "Legit_Player").inTab("Legit_Player", 0).build(),
                DetectionReason.ZERO_PING);

        // Unless the server pings nobody, in which case the signal carries no information.
        assert valid(observation(id, "Legit_Player").inTab("Legit_Player", 0)
                .pingsNobody().build());
    }

    // ---- helpers -------------------------------------------------------------

    private static boolean valid(Observation obs) {
        EnumSet<DetectionReason> reasons = AntiBot.evaluate(obs);
        if (!reasons.isEmpty()) {
            System.out.println("expected VALID but got " + reasons + " for " + obs);
            return false;
        }
        return true;
    }

    private static boolean bot(Observation obs, DetectionReason expected) {
        EnumSet<DetectionReason> reasons = AntiBot.evaluate(obs);
        if (!reasons.contains(expected)) {
            System.out.println("expected " + expected + " but got " + reasons + " for " + obs);
            return false;
        }
        return true;
    }

    private static Builder observation(UUID uuid, String name) {
        return new Builder(uuid, name);
    }

    /** Defaults to the hardest case for the detector: a clean, unremarkable player. */
    private static final class Builder {
        private final UUID uuid;
        private final String name;
        private boolean inTab = true;
        private boolean listed = true;
        private boolean hasSkin = true;
        private boolean othersHaveSkin = true;
        private String tabName;
        private int latency = 45;
        private boolean othersReportPing = true;
        private boolean duplicateUuid;
        private boolean duplicateName;
        private int trackedTicks = 200;
        private boolean invisibleSpawn;
        private boolean invisible;

        private Builder(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
            this.tabName = name;
        }

        Builder inTab(String tabName, int latency) {
            this.inTab = true;
            this.tabName = tabName;
            this.latency = latency;
            return this;
        }

        Builder notInTab() {
            this.inTab = false;
            this.listed = false;
            this.tabName = null;
            this.latency = 0;
            return this;
        }

        /** Has a profile, so the entity can spawn, but is hidden from the drawn tab list. */
        Builder unlisted() {
            this.listed = false;
            return this;
        }

        Builder noSkin() {
            this.hasSkin = false;
            return this;
        }

        /** An offline-mode server where nobody carries a signed textures property. */
        Builder nobodyHasSkins() {
            this.hasSkin = false;
            this.othersHaveSkin = false;
            return this;
        }

        /** {@code tabOwner} is the uuid the tab list gives this name; ours means we are real. */
        Builder copiesOfName(int copies, UUID tabOwner) {
            this.duplicateName = copies > 1 && (tabOwner == null || !tabOwner.equals(uuid));
            return this;
        }

        Builder copiesOfUuid(int copies) {
            this.duplicateUuid = copies > 1;
            return this;
        }

        Builder spawnedInvisible() {
            this.invisibleSpawn = true;
            this.invisible = true;
            return this;
        }

        Builder invisibleNow() {
            this.invisible = true;
            return this;
        }

        /** A server that reports 0 ms for every player, so ping says nothing. */
        Builder pingsNobody() {
            this.othersReportPing = false;
            return this;
        }

        /** Inside the window before the server's first latency update would have arrived. */
        Builder justJoined() {
            this.trackedTicks = 20;
            return this;
        }

        Observation build() {
            return new Observation(uuid, name, inTab, listed, tabName, latency, othersReportPing,
                    hasSkin, othersHaveSkin, duplicateUuid, duplicateName,
                    trackedTicks, invisibleSpawn, invisible);
        }
    }

}
