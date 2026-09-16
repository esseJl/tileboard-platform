package com.tileboard.gamekit.state;

/** Thread-safe bounded health/lives counter. */
public final class HealthTracker {
    private final int maximum;
    private int current;

    public HealthTracker(int maximum) {
        if (maximum < 1) throw new IllegalArgumentException("maximum must be >= 1");
        this.maximum = maximum;
        this.current = maximum;
    }

    public synchronized int current() { return current; }
    public int maximum() { return maximum; }
    public synchronized boolean isDepleted() { return current == 0; }

    public synchronized int damage(int amount) {
        requirePositive(amount);
        int before = current;
        current = Math.max(0, current - amount);
        return before - current;
    }

    public synchronized int heal(int amount) {
        requirePositive(amount);
        int before = current;
        current = Math.min(maximum, current + amount);
        return current - before;
    }

    public synchronized void reset() { current = maximum; }

    private static void requirePositive(int value) {
        if (value < 1) throw new IllegalArgumentException("amount must be >= 1");
    }
}
