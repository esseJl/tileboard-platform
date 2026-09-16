package com.tileboard.gamekit.state;

/** Thread-safe combo counter with a configurable break threshold. */
public final class ComboTracker {
    private final int breakAfterMisses;
    private int combo;
    private int misses;

    public ComboTracker(int breakAfterMisses) {
        if (breakAfterMisses < 1) throw new IllegalArgumentException("breakAfterMisses must be >= 1");
        this.breakAfterMisses = breakAfterMisses;
    }

    public synchronized int hit() {
        misses = 0;
        return ++combo;
    }

    public synchronized int miss() {
        misses++;
        if (misses >= breakAfterMisses) combo = 0;
        return combo;
    }

    public synchronized int current() { return combo; }
    public synchronized void reset() { combo = 0; misses = 0; }
}
