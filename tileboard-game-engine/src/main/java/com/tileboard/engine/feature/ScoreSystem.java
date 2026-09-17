package com.tileboard.engine.feature;

import com.tileboard.engine.model.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe score tracking for all players in a session.
 * Supports add, subtract, multiply, reset and per-player retrieval.
 */
public final class ScoreSystem {

    private final Map<String, AtomicInteger> scores = new ConcurrentHashMap<>();

    public ScoreSystem(List<Player> players) {
        players.forEach(p -> scores.put(p.id(), new AtomicInteger(0)));
    }

    /** Adds {@code delta} to {@code playerId}'s score. Returns the new score. */
    public int add(String playerId, int delta) {
        return getOrCreate(playerId).addAndGet(delta);
    }

    public int subtract(String playerId, int delta) {
        return add(playerId, -delta);
    }

    public int get(String playerId) {
        return getOrCreate(playerId).get();
    }

    /** Sets a player's score to an exact value. */
    public void set(String playerId, int value) {
        getOrCreate(playerId).set(value);
    }

    public void reset(String playerId) { set(playerId, 0); }

    public void resetAll() { scores.values().forEach(a -> a.set(0)); }

    /** Returns the player id with the highest score, or empty if no players. */
    public Optional<String> leader() {
        return scores.entrySet().stream()
                .max(Comparator.comparingInt(e -> e.getValue().get()))
                .map(Map.Entry::getKey);
    }

    /** Snapshot of all scores (playerId → score). */
    public Map<String, Integer> allScores() {
        Map<String, Integer> snap = new LinkedHashMap<>();
        scores.forEach((id, a) -> snap.put(id, a.get()));
        return Collections.unmodifiableMap(snap);
    }

    private AtomicInteger getOrCreate(String playerId) {
        return scores.computeIfAbsent(playerId, k -> new AtomicInteger(0));
    }
}