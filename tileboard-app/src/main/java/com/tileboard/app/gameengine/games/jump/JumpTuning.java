package com.tileboard.app.gameengine.games.jump;

import com.tileboard.app.gameengine.GameMode;

import java.time.Duration;

/**
 * How fast the band moves, how many mistakes are tolerated, and how long a
 * round lasts, for a given {@link GameMode}. Kept as one small record and
 * one mapping function rather than fields scattered across {@link JumpGame}
 * so the difficulty curve is visible and changeable in a single place; the
 * previous implementation instead read this kind of tuning from a database
 * table per game/mode pair, which this project's README explicitly defers
 * (no persistence layer yet) - swapping this for a
 * {@code @ConfigurationProperties}- or repository-backed lookup later only
 * touches {@link #forMode}.
 */
record JumpTuning(Duration tickInterval, int lives, Duration roundDuration) {

    static JumpTuning forMode(GameMode mode) {
        return switch (mode) {
            case EASY -> new JumpTuning(Duration.ofMillis(900), 5, Duration.ofMinutes(3));
            case NORMAL -> new JumpTuning(Duration.ofMillis(600), 4, Duration.ofMinutes(2));
            case HARD -> new JumpTuning(Duration.ofMillis(350), 3, Duration.ofMinutes(1));
        };
    }
}
