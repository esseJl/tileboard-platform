package com.tileboard.gamekit.state;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The built-in "health" capability - a bounded life/hit-point counter that
 * exposes {@link Outcome#LOST} once depleted, e.g. a limited number of
 * lives in a reflex game ({@code JumpGame}'s "lives"). Backed by a single
 * {@link AtomicInteger}: {@link #damage} and {@link #heal} are safe to call
 * concurrently from a touch-input thread and a clock-tick thread without
 * external locking.
 */
public final class HealthTracker {

    private final int maxHealth;
    private final AtomicInteger health;

    public HealthTracker(int maxHealth) {
        if (maxHealth <= 0) {
            throw new IllegalArgumentException("maxHealth must be > 0, got " + maxHealth);
        }
        this.maxHealth = maxHealth;
        this.health = new AtomicInteger(maxHealth);
    }

    public int current() {
        return health.get();
    }

    public int max() {
        return maxHealth;
    }

    /** Reduces health by {@code amount} (clamped at zero) and returns the health remaining. */
    public int damage(int amount) {
        return health.updateAndGet(current -> Math.max(0, current - amount));
    }

    /** Increases health by {@code amount} (clamped at {@link #max()}) and returns the health remaining. */
    public int heal(int amount) {
        return health.updateAndGet(current -> Math.min(maxHealth, current + amount));
    }

    public boolean isDepleted() {
        return health.get() <= 0;
    }

    /** {@link Outcome#LOST} once depleted, {@link Outcome#IN_PROGRESS} otherwise - health alone never produces {@link Outcome#WON}. */
    public Outcome outcome() {
        return isDepleted() ? Outcome.LOST : Outcome.IN_PROGRESS;
    }
}
