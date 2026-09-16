package cn.timer.coldplay.client.util;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ReadOnlyScoreInfo;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

/** Player health from a HEARTS sidebar, else the below-name score, else vanilla health. */
public final class HealthResolver {

    private HealthResolver() {
    }

    public static float resolve(LivingEntity entity) {
        if (entity instanceof Player player) {
            Float scored = fromScoreboard(player.level().getScoreboard(), player);
            if (scored != null) {
                return scored;
            }
        }
        return entity.getHealth();
    }

    /** The sidebar when it renders hearts, else the below-name score; null when neither is shown. */
    public static Float fromScoreboard(Scoreboard scoreboard, ScoreHolder holder) {
        Float sidebar = fromSlot(scoreboard, holder, DisplaySlot.SIDEBAR, true);
        return sidebar != null ? sidebar : fromSlot(scoreboard, holder, DisplaySlot.BELOW_NAME, false);
    }

    /**
     * A sidebar counting anything but hearts is not health, so {@code requireHearts} rejects it.
     * An untracked holder reads as null rather than zero, which would look like a dead player.
     */
    private static Float fromSlot(Scoreboard scoreboard, ScoreHolder holder, DisplaySlot slot,
                                  boolean requireHearts) {
        Objective objective = scoreboard.getDisplayObjective(slot);
        if (objective == null
                || requireHearts && objective.getRenderType() != ObjectiveCriteria.RenderType.HEARTS) {
            return null;
        }
        ReadOnlyScoreInfo score = scoreboard.getPlayerScoreInfo(holder, objective);
        return score == null ? null : (float) score.value();
    }
}
