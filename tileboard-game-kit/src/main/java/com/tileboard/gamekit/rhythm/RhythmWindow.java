package com.tileboard.gamekit.rhythm;

import java.time.Duration;

/** Immutable timing window for music/rhythm/reaction games. */
public record RhythmWindow(Duration center, Duration tolerance) {
    public RhythmWindow {
        if (center.isNegative() || tolerance.isNegative()) throw new IllegalArgumentException("durations must be >= 0");
    }

    public long errorMillis(Duration actual) {
        return Math.abs(actual.minus(center).toMillis());
    }

    public boolean contains(Duration actual) {
        return errorMillis(actual) <= tolerance.toMillis();
    }

    public Accuracy accuracy(Duration actual) {
        long error = errorMillis(actual);
        long t = tolerance.toMillis();
        if (error <= Math.max(1, t / 4)) return Accuracy.PERFECT;
        if (error <= Math.max(1, t / 2)) return Accuracy.GOOD;
        if (error <= t) return Accuracy.OK;
        return Accuracy.MISS;
    }

    public enum Accuracy { PERFECT, GOOD, OK, MISS }
}
