package com.tileboard.engine.feature.shape;

/**
 * Speed expressed as cells-per-second so it's independent of board size and frame count.
 */
public final class AnimationSpeed {
    public static final AnimationSpeed SLOW = new AnimationSpeed(3);
    public static final AnimationSpeed NORMAL = new AnimationSpeed(6);
    public static final AnimationSpeed FAST = new AnimationSpeed(12);
    public static final AnimationSpeed TURBO = new AnimationSpeed(24);

    private final double cellsPerSecond;

    public AnimationSpeed(double cellsPerSecond) {
        if (cellsPerSecond <= 0) throw new IllegalArgumentException("cellsPerSecond must be > 0");
        this.cellsPerSecond = cellsPerSecond;
    }

    public double cellsPerSecond() {
        return cellsPerSecond;
    }

    public long frameDelayMs(double distanceCells, int frames) {
        double totalMs = (distanceCells / cellsPerSecond) * 1000.0;
        return Math.max(8L, Math.round(totalMs / Math.max(1, frames)));
    }

    public int suggestedFrames(double distanceCells) {
        return Math.max(1, (int) Math.round(distanceCells));
    }
}