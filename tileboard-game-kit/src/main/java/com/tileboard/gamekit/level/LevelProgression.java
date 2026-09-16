package com.tileboard.gamekit.level;

import java.util.Objects;
import java.util.function.IntPredicate;

/** Small immutable level progression policy. */
public record LevelProgression(int startLevel, IntPredicate advanceWhen) {
    public LevelProgression {
        if (startLevel < 1) throw new IllegalArgumentException("startLevel must be >= 1");
        Objects.requireNonNull(advanceWhen, "advanceWhen");
    }

    public int nextLevel(int currentLevel, int successes) {
        if (currentLevel < 1) throw new IllegalArgumentException("currentLevel must be >= 1");
        return advanceWhen.test(successes) ? currentLevel + 1 : currentLevel;
    }
}
