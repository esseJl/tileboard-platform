package com.tileboard.gamekit.time;

import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The built-in "random" capability: every place in this kit that needs
 * randomness (shuffling a {@link com.tileboard.gamekit.pattern.Patterns#rotating
 * rotating pattern}, dealing a {@link com.tileboard.gamekit.memory.RevealChallenge
 * memory assignment}, picking a random target color) takes a
 * {@code RandomSource} rather than calling {@link ThreadLocalRandom}
 * directly. That keeps randomness swappable: production games use
 * {@link #threadLocal()} (fast, thread-safe, no shared state to
 * synchronize on), while a game's unit test can use {@link #seeded(long)}
 * for a fully deterministic, repeatable sequence.
 */
public interface RandomSource {

    /** A pseudo-random int in {@code [0, bound)}. */
    int nextInt(int bound);

    /** A pseudo-random double in {@code [0.0, 1.0)}. */
    double nextDouble();

    /** A uniformly random element of {@code items}. @throws IllegalArgumentException if {@code items} is empty */
    default <T> T pick(List<T> items) {
        if (items.isEmpty()) {
            throw new IllegalArgumentException("Cannot pick from an empty list");
        }
        return items.get(nextInt(items.size()));
    }

    /**
     * Thread-safe, non-deterministic source backed by {@link ThreadLocalRandom}.
     * Safe to share a single instance across every game session - it holds
     * no mutable state of its own.
     */
    static RandomSource threadLocal() {
        return new RandomSource() {
            @Override
            public int nextInt(int bound) {
                return ThreadLocalRandom.current().nextInt(bound);
            }

            @Override
            public double nextDouble() {
                return ThreadLocalRandom.current().nextDouble();
            }
        };
    }

    /**
     * Deterministic source seeded with {@code seed}, for reproducible game
     * rounds or tests. Backed by a single {@link Random} instance guarded
     * by a lock, since {@link Random} itself is thread-safe but a fixed
     * seed only reproduces a given sequence if callers are serialized.
     */
    static RandomSource seeded(long seed) {
        Random random = new Random(seed);
        Object lock = new Object();
        return new RandomSource() {
            @Override
            public int nextInt(int bound) {
                synchronized (lock) {
                    return random.nextInt(bound);
                }
            }

            @Override
            public double nextDouble() {
                synchronized (lock) {
                    return random.nextDouble();
                }
            }
        };
    }
}
