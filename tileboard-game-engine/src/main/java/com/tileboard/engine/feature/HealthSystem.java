package com.tileboard.engine.feature;

import com.tileboard.engine.model.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe health / lives tracking. Health is clamped to [0, maxHealth].
 */
public final class HealthSystem {

    private final Map<String, AtomicInteger> health = new ConcurrentHashMap<>();
    private final Map<String, Integer> maxHealth = new ConcurrentHashMap<>();
    /**
     * Bug fix: this used to be a constructor parameter only, never stored.
     * {@link #getOrCreate(String)} therefore always fell back to a hard-coded
     * {@code 3} for any player id not already present in {@code players} at
     * construction time (e.g. a late joiner, or a caller passing a typo'd
     * id), silently ignoring whatever value was passed here. It is now kept
     * so every lazily-created player is consistent with the configured
     * default.
     */
    private final int defaultMaxHealth;
    private final Runnable onChange;

    public HealthSystem(List<Player> players) {
        this(players, 3);
    }

    public HealthSystem(List<Player> players, int defaultMaxHealth) {
        this(players, defaultMaxHealth, () -> {
        });
    }

    /**
     * @param onChange invoked (on the caller's thread) every time any player's health
     *                 actually changes, so callers such as the engine can publish a
     *                 {@code HEALTH_CHANGED} game event.
     */
    public HealthSystem(List<Player> players, int defaultMaxHealth, Runnable onChange) {
        this.defaultMaxHealth = defaultMaxHealth;
        this.onChange = Objects.requireNonNull(onChange, "onChange");
        players.forEach(p -> {
            health.put(p.id(), new AtomicInteger(defaultMaxHealth));
            maxHealth.put(p.id(), defaultMaxHealth);
        });
    }

    public int current(String playerId) {
        return getOrCreate(playerId).get();
    }

    public int max(String playerId) {
        return maxHealth.getOrDefault(playerId, defaultMaxHealth);
    }

    /**
     * Decrements health by 1. Returns {@code true} if the player is still alive.
     */
    public boolean damage(String playerId) {
        return damage(playerId, 1);
    }

    /**
     * Decrements health by {@code amount}. Returns {@code true} if still alive.
     */
    public boolean damage(String playerId, int amount) {
        AtomicInteger counter = getOrCreate(playerId);
        int before = counter.get();
        int next = counter.updateAndGet(h -> Math.max(0, h - amount));
        if (next != before) onChange.run();
        return next > 0;
    }

    public void heal(String playerId, int amount) {
        int max = max(playerId);
        AtomicInteger counter = getOrCreate(playerId);
        int before = counter.get();
        int next = counter.updateAndGet(h -> Math.min(max, h + amount));
        if (next != before) onChange.run();
    }

    public boolean isAlive(String playerId) {
        return current(playerId) > 0;
    }

    public boolean allDead() {
        return health.values().stream().allMatch(a -> a.get() <= 0);
    }

    public void resetAll(int value) {
        health.forEach((id, a) -> {
            int max = maxHealth.getOrDefault(id, value);
            a.set(Math.min(value, max));
        });
        onChange.run();
    }

    private AtomicInteger getOrCreate(String playerId) {
        maxHealth.putIfAbsent(playerId, defaultMaxHealth);
        return health.computeIfAbsent(playerId, k -> new AtomicInteger(defaultMaxHealth));
    }
}