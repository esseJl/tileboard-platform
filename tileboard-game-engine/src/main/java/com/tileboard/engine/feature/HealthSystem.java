package com.tileboard.engine.feature;

import com.tileboard.engine.model.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe health / lives tracking. Health is clamped to [0, maxHealth].
 */
public final class HealthSystem {

    private final Map<String, AtomicInteger> health    = new ConcurrentHashMap<>();
    private final Map<String, Integer>       maxHealth = new ConcurrentHashMap<>();

    public HealthSystem(List<Player> players) {
        this(players, 3);
    }

    public HealthSystem(List<Player> players, int defaultMaxHealth) {
        players.forEach(p -> {
            health.put(p.id(), new AtomicInteger(defaultMaxHealth));
            maxHealth.put(p.id(), defaultMaxHealth);
        });
    }

    public int current(String playerId) { return getOrCreate(playerId).get(); }

    public int max(String playerId) { return maxHealth.getOrDefault(playerId, 3); }

    /** Decrements health by 1. Returns {@code true} if the player is still alive. */
    public boolean damage(String playerId) { return damage(playerId, 1); }

    /** Decrements health by {@code amount}. Returns {@code true} if still alive. */
    public boolean damage(String playerId, int amount) {
        int next = getOrCreate(playerId).updateAndGet(h -> Math.max(0, h - amount));
        return next > 0;
    }

    public void heal(String playerId, int amount) {
        int max = maxHealth.getOrDefault(playerId, 3);
        getOrCreate(playerId).updateAndGet(h -> Math.min(max, h + amount));
    }

    public boolean isAlive(String playerId) { return current(playerId) > 0; }

    public boolean allDead() {
        return health.values().stream().allMatch(a -> a.get() <= 0);
    }

    public void resetAll(int value) {
        health.forEach((id, a) -> {
            int max = maxHealth.getOrDefault(id, value);
            a.set(Math.min(value, max));
        });
    }

    private AtomicInteger getOrCreate(String playerId) {
        return health.computeIfAbsent(playerId, k -> new AtomicInteger(3));
    }
}