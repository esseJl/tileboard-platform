package com.tileboard.engine.feature;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntUnaryOperator;

/**
 * Tracks the current level and exposes hooks for difficulty scaling.
 */
public final class LevelSystem {

    private final AtomicInteger level = new AtomicInteger(1);
    private volatile IntUnaryOperator speedScaler = lvl -> Math.max(50, 1000 - (lvl - 1) * 100);

    public int currentLevel() { return level.get(); }

    public int advance() { return level.incrementAndGet(); }

    public void setLevel(int lvl) {
        if (lvl < 1) throw new IllegalArgumentException("level must be >= 1");
        level.set(lvl);
    }

    /**
     * Configures how speed (e.g. tick interval in ms) is derived from level.
     * Default: {@code 1000 - (level-1)*100}, clamped to 50 ms.
     */
    public void setSpeedScaler(IntUnaryOperator scaler) { this.speedScaler = scaler; }

    /** Returns the speed value for the current level. */
    public int currentSpeed() { return speedScaler.applyAsInt(level.get()); }

    public void reset() { level.set(1); }
}