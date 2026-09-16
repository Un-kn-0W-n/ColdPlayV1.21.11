package cn.timer.coldplay.client.module.impl.combat;

public final class AntiBotSelfCheck {
    private AntiBotSelfCheck() {
    }

    public static void main(String[] args) {
        AntiBot.Classification unknown = AntiBot.Classification.UNKNOWN;
        AntiBot.Classification valid = AntiBot.Classification.VALID;
        AntiBot.Classification bot = AntiBot.Classification.BOT;

        assert AntiBot.nextClassification(unknown, true, 0, 0, 0) == bot;
        assert !AntiBot.suspicious(false, 1);
        assert AntiBot.suspicious(false, 2);
        assert AntiBot.suspicious(true, 0);
        assert AntiBot.nextClassification(unknown, false, 1, 0, 0) == unknown;
        assert AntiBot.nextClassification(unknown, false, 2, 0, 0) == bot;
        assert AntiBot.nextClassification(unknown, false, 0, 2, 0) == valid;
        assert AntiBot.nextClassification(valid, false, 0, 0, 1) == valid;
        assert AntiBot.nextClassification(valid, false, 0, 0, 4) == valid;
        assert AntiBot.nextClassification(valid, false, 0, 0, 5) == unknown;
        assert AntiBot.nextClassification(bot, false, 0, 2, 0) == bot;

        assert AntiBot.allows(true, valid);
        assert !AntiBot.allows(true, unknown);
        assert !AntiBot.allows(true, bot);
        assert AntiBot.allows(false, unknown);
        assert AntiBot.allows(false, bot);
    }
}
