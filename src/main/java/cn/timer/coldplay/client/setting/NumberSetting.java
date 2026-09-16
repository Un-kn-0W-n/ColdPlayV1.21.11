package cn.timer.coldplay.client.setting;

import java.util.Objects;
import java.util.function.DoubleConsumer;

public final class NumberSetting extends Setting<Double> {
    private final double minimum;
    private final double maximum;
    private final double increment;
    private final String unit;
    private final DoubleConsumer changeCallback;
    private double value;

    public NumberSetting(String name, double value, double minimum, double maximum, double increment) {
        this(name, value, minimum, maximum, increment, "", ignored -> {
        });
    }

    public NumberSetting(String name, double value, double minimum, double maximum, double increment,
                         String unit, DoubleConsumer changeCallback) {
        super(name);
        if (!Double.isFinite(minimum) || !Double.isFinite(maximum) || minimum > maximum || increment <= 0.0) {
            throw new IllegalArgumentException("Invalid number setting range");
        }
        this.minimum = minimum;
        this.maximum = maximum;
        this.increment = increment;
        this.unit = Objects.requireNonNull(unit, "unit");
        this.changeCallback = Objects.requireNonNull(changeCallback, "changeCallback");
        this.value = normalize(value);
    }

    @Override
    public Double get() {
        return value;
    }

    @Override
    public void set(Double value) {
        double normalized = normalize(value);
        if (normalized == this.value) {
            return;
        }
        this.value = normalized;
        changeCallback.accept(normalized);
    }

    private double normalize(Double value) {
        if (value == null || !Double.isFinite(value)) {
            throw new IllegalArgumentException("Number setting must be finite");
        }
        double clamped = Math.clamp(value, minimum, maximum);
        return Math.clamp(minimum + Math.round((clamped - minimum) / increment) * increment, minimum, maximum);
    }

    public double minimum() {
        return minimum;
    }

    public double maximum() {
        return maximum;
    }

    public double increment() {
        return increment;
    }

    public String unit() {
        return unit;
    }
}
