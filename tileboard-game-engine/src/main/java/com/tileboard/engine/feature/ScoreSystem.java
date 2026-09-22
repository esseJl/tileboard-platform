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
    private final Runnable onChange;

    public ScoreSystem(List<Player> players) {
        this(players, () -> {
        });
    }

    /**
     * @param onChange invoked (on the caller's thread) every time any player's score
     *                 actually changes, so callers such as the engine can publish a
     *                 {@code SCORE_CHANGED} game event. Never invoked with a null-safe
     *                 no-op unless explicitly passed.
     */
    public ScoreSystem(List<Player> players, Runnable onChange) {
        this.onChange = Objects.requireNonNull(onChange, "onChange");
        players.forEach(p -> scores.put(p.id(), new AtomicInteger(0)));
    }

    /**
     * Adds {@code delta} to {@code playerId}'s score. Returns the new score.
     */
    public int add(String playerId, int delta) {
        int result = getOrCreate(playerId).addAndGet(delta);
        if (delta != 0) onChange.run();
        return result;
    }

    public int subtract(String playerId, int delta) {
        return add(playerId, -delta);
    }

    public int get(String playerId) {
        return getOrCreate(playerId).get();
    }

    /**
     * Sets a player's score to an exact value.
     */
    public void set(String playerId, int value) {
        getOrCreate(playerId).set(value);
        onChange.run();
    }

    public void reset(String playerId) {
        set(playerId, 0);
    }

    public void resetAll() {
        scores.values().forEach(a -> a.set(0));
        onChange.run();
    }

    /**
     * Returns the player id with the highest score, or empty if no players.
     */
    public Optional<String> leader() {
        return scores.entrySet().stream()
                .max(Comparator.comparingInt(e -> e.getValue().get()))
                .map(Map.Entry::getKey);
    }

    /**
     * Snapshot of all scores (playerId → score).
     */
    public Map<String, Integer> allScores() {
        Map<String, Integer> snap = new LinkedHashMap<>();
        scores.forEach((id, a) -> snap.put(id, a.get()));
        return Collections.unmodifiableMap(snap);
    }

    private AtomicInteger getOrCreate(String playerId) {
        return scores.computeIfAbsent(playerId, k -> new AtomicInteger(0));
    }
}