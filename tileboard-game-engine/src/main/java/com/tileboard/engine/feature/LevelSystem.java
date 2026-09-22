package com.tileboard.engine.feature;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntUnaryOperator;

/**
 * Tracks the current level and exposes hooks for difficulty scaling.
 */
public final class LevelSystem {

    private final AtomicInteger level = new AtomicInteger(1);
    private final Runnable onChange;
    private volatile IntUnaryOperator speedScaler = lvl -> Math.max(50, 1000 - (lvl - 1) * 100);

    public LevelSystem() {
        this(() -> {
        });
    }

    /**
     * @param onChange invoked (on the caller's thread) every time the level actually
     *                 changes, so callers such as the engine can publish a
     *                 {@code LEVEL_UP} game event.
     */
    public LevelSystem(Runnable onChange) {
        this.onChange = java.util.Objects.requireNonNull(onChange, "onChange");
    }

    public int currentLevel() {
        return level.get();
    }

    public int advance() {
        int result = level.incrementAndGet();
        onChange.run();
        return result;
    }

    public void setLevel(int lvl) {
        if (lvl < 1) throw new IllegalArgumentException("level must be >= 1");
        int previous = level.getAndSet(lvl);
        if (previous != lvl) onChange.run();
    }

    /**
     * Configures how speed (e.g. tick interval in ms) is derived from level.
     * Default: {@code 1000 - (level-1)*100}, clamped to 50 ms.
     */
    public void setSpeedScaler(IntUnaryOperator scaler) {
        this.speedScaler = scaler;
    }

    /**
     * Returns the speed value for the current level.
     */
    public int currentSpeed() {
        return speedScaler.applyAsInt(level.get());
    }

    public void reset() {
        level.set(1);
    }
}