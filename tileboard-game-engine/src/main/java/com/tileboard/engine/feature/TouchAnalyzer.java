package com.tileboard.engine.feature;

import com.tileboard.engine.model.TouchSequence;
import com.tileboard.serial.board.Position;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Stateless analytical queries over a {@link TouchHistory}. All methods read
 * from the live history at call time so they always reflect the latest state.
 */
public final class TouchAnalyzer {

    private final TouchHistory history;

    public TouchAnalyzer(TouchHistory history) { this.history = history; }

    /** Average gap between consecutive touches over the whole session. */
    public Duration averageInterTouchGap() {
        return history.sequence().averageGap();
    }

    /**
     * Checks whether the player has touched positions in exactly the order
     * prescribed by {@code pattern}. Useful for "swipe" or "sequence" puzzles.
     */
    public boolean matchesSequence(List<Position> pattern) {
        List<Position> actual = history.positionOrder();
        if (actual.size() < pattern.size()) return false;
        List<Position> tail = actual.subList(actual.size() - pattern.size(), actual.size());
        return tail.equals(pattern);
    }

    /** Returns {@code true} if no tile was touched more than once. */
    public boolean allUnique() {
        List<Position> order = history.positionOrder();
        return order.stream().distinct().count() == order.size();
    }

    /**
     * The "reaction time" for the last touch: time from the previous touch to
     * the most recent one. Empty if fewer than two touches have occurred.
     */
    public Optional<Duration> lastReactionTime() {
        TouchSequence seq = history.sequence();
        if (seq.size() < 2) return Optional.empty();
        return Optional.of(seq.gapBetween(seq.size() - 2));
    }
}