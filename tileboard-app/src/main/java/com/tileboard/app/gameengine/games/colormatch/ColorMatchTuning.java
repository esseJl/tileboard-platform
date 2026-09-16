package com.tileboard.app.gameengine.games.colormatch;

import com.tileboard.app.gameengine.GameMode;
import com.tileboard.app.gameengine.TileColor;

import java.time.Duration;

/**
 * How many color pairs are in play and how long they stay visible, for a
 * given {@link GameMode} and board size. {@code pairCount} is clamped to
 * whatever the board and the available palette can actually support, so a
 * small board never gets asked to place more pairs than it has tiles for.
 */
record ColorMatchTuning(int pairCount, Duration revealAllDuration, Duration evaluateDelay) {

    /** Colors used for pairs. {@link TileColor#WHITE} is reserved for face-down tiles and {@link TileColor#OFF} for non-participating ones. */
    static final TileColor[] PALETTE = {
            TileColor.RED, TileColor.GREEN, TileColor.BLUE, TileColor.PINK, TileColor.LIGHT_BLUE
    };

    static ColorMatchTuning forMode(GameMode mode, int boardArea) {
        int desiredPairs = switch (mode) {
            case EASY -> 3;
            case NORMAL -> 4;
            case HARD -> 5;
        };
        Duration revealAllDuration = switch (mode) {
            case EASY -> Duration.ofSeconds(4);
            case NORMAL -> Duration.ofSeconds(3);
            case HARD -> Duration.ofSeconds(2);
        };
        int maxPairsBoardCanHold = boardArea / 2;
        int pairCount = Math.max(1, Math.min(desiredPairs, Math.min(maxPairsBoardCanHold, PALETTE.length)));
        return new ColorMatchTuning(pairCount, revealAllDuration, Duration.ofMillis(800));
    }
}
