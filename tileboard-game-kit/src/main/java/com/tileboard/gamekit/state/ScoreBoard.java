package com.tileboard.gamekit.state;

import java.util.HashMap;
import java.util.Map;

/** Thread-safe per-player score store. */
public final class ScoreBoard {
    private final Map<String, Long> scores = new HashMap<>();

    public synchronized long add(String playerId, long delta) {
        requirePlayer(playerId);
        long value = Math.addExact(scores.getOrDefault(playerId, 0L), delta);
        scores.put(playerId, value);
        return value;
    }

    public synchronized long set(String playerId, long score) {
        requirePlayer(playerId);
        scores.put(playerId, score);
        return score;
    }

    public synchronized long get(String playerId) {
        requirePlayer(playerId);
        return scores.getOrDefault(playerId, 0L);
    }

    public synchronized Map<String, Long> snapshot() {
        return Map.copyOf(scores);
    }

    public synchronized void reset() { scores.clear(); }

    private static void requirePlayer(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("playerId must not be blank");
    }
}
