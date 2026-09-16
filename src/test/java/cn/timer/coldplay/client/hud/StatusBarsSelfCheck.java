package cn.timer.coldplay.client.hud;

public final class StatusBarsSelfCheck {
    private StatusBarsSelfCheck() {
    }

    public static void main(String[] args) {
        // An empty bar draws nothing; any fill keeps at least the rounded end caps.
        assert StatusBars.fillWidth(0.0f, 90, 11) == 0;
        assert StatusBars.fillWidth(-1.0f, 90, 11) == 0;
        assert StatusBars.fillWidth(Float.NaN, 90, 11) == 0;
        assert StatusBars.fillWidth(0.01f, 90, 11) == 11;
        assert StatusBars.fillWidth(0.5f, 90, 11) == 45;
        assert StatusBars.fillWidth(1.0f, 90, 11) == 90;
        assert StatusBars.fillWidth(2.0f, 90, 11) == 90;

        // The golden apple bar drains against the peak since it was last empty.
        assert StatusBars.trackAbsorption(0.0f, 4.0f) == 4.0f;
        assert StatusBars.trackAbsorption(4.0f, 2.0f) == 4.0f;
        assert StatusBars.trackAbsorption(4.0f, 8.0f) == 8.0f;
        assert StatusBars.trackAbsorption(8.0f, 0.0f) == 0.0f;
    }
}
