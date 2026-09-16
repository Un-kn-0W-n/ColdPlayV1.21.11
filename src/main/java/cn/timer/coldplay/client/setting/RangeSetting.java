package cn.timer.coldplay.client.setting;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

public final class RangeSetting extends Setting<RangeSetting.Value> {
    private final int allowedMinimum;
    private final int allowedMaximum;
    private final int increment;
    private int lower;
    private int upper;

    public RangeSetting(String name, int lower, int upper, int allowedMinimum, int allowedMaximum, int increment) {
        super(name);
        if (allowedMinimum > allowedMaximum || increment <= 0) {
            throw new IllegalArgumentException("Invalid range setting bounds");
        }
        this.allowedMinimum = allowedMinimum;
        this.allowedMaximum = allowedMaximum;
        this.increment = increment;
        set(new Value(lower, upper));
    }

    @Override
    public Value get() {
        return new Value(lower, upper);
    }

    @Override
    public void set(Value value) {
        Objects.requireNonNull(value, "value");
        int first = snap(value.minimum());
        int second = snap(value.maximum());
        lower = Math.min(first, second);
        upper = Math.max(first, second);
    }

    public int lower() {
        return lower;
    }

    public int upper() {
        return upper;
    }

    public int allowedMinimum() {
        return allowedMinimum;
    }

    public int allowedMaximum() {
        return allowedMaximum;
    }

    public int increment() {
        return increment;
    }

    public void setLower(int value) {
        lower = Math.min(snap(value), upper);
    }

    public void setUpper(int value) {
        upper = Math.max(snap(value), lower);
    }

    public int sampleMillis() {
        return lower == upper ? lower : (int) ThreadLocalRandom.current().nextLong(lower, (long) upper + 1L);
    }

    private int snap(int value) {
        int clamped = Math.clamp(value, allowedMinimum, allowedMaximum);
        long steps = Math.round((clamped - (long) allowedMinimum) / (double) increment);
        return (int) Math.clamp(allowedMinimum + steps * increment, (long) allowedMinimum, (long) allowedMaximum);
    }

    public record Value(int minimum, int maximum) {
    }
}
