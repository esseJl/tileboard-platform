package com.tileboard.engine.feature;

import com.tileboard.serial.board.Position;

import java.util.List;

/**
 * Checks whether a list of touched positions matches a predefined pattern.
 * Patterns are just ordered lists of {@link Position}s — simple to define
 * in game code, powerful enough for rhythm, memory and educational games.
 */
public final class PatternMatcher {

    /**
     * Returns {@code true} if {@code actual} ends with {@code pattern}
     * (tail match). This covers "last N touches were in the right order"
     * without requiring the player to start from the beginning.
     */
    public boolean tailMatches(List<Position> actual, List<Position> pattern) {
        if (pattern.isEmpty()) return true;
        if (actual.size() < pattern.size()) return false;
        return actual.subList(actual.size() - pattern.size(), actual.size())
                .equals(pattern);
    }

    /** Exact full match. */
    public boolean exactMatch(List<Position> actual, List<Position> pattern) {
        return actual.equals(pattern);
    }

    /**
     * Returns {@code true} if {@code actual} contains {@code pattern} as a
     * contiguous sub-sequence.
     */
    public boolean containsSequence(List<Position> actual, List<Position> pattern) {
        if (pattern.isEmpty()) return true;
        int n = actual.size(), m = pattern.size();
        if (n < m) return false;
        for (int i = 0; i <= n - m; i++) {
            if (actual.subList(i, i + m).equals(pattern)) return true;
        }
        return false;
    }

    /**
     * Cyclic match: wraps around the pattern. Useful for circular board games.
     */
    public boolean cyclicMatch(List<Position> actual, List<Position> pattern) {
        if (pattern.isEmpty()) return true;
        int m = pattern.size();
        if (actual.size() < m) return false;
        for (int start = 0; start < m; start++) {
            boolean match = true;
            for (int i = 0; i < m; i++) {
                if (!actual.get(actual.size() - m + i)
                        .equals(pattern.get((start + i) % m))) {
                    match = false; break;
                }
            }
            if (match) return true;
        }
        return false;
    }
}