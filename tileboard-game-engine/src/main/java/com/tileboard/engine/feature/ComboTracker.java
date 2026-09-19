package com.tileboard.engine.feature;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks consecutive successful actions (combo chain) with optional timeout.
 */
public final class ComboTracker {

    private final AtomicInteger combo = new AtomicInteger(0);
    private final AtomicInteger maxCombo = new AtomicInteger(0);
    private final AtomicLong lastHit = new AtomicLong(0L);
    private volatile long timeoutMillis = 2_000L;

    public void setComboTimeout(long millis) {
        this.timeoutMillis = millis;
    }

    /**
     * Records a hit. Auto-resets if the combo timeout elapsed since the last hit.
     */
    public int hit() {
        long now = System.nanoTime();
        long last = lastHit.get();
        if (last > 0) {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(now - last);
            if (elapsedMs > timeoutMillis) {
                combo.set(0);
            }
        }
        lastHit.set(now);
        int c = combo.incrementAndGet();
        maxCombo.updateAndGet(m -> Math.max(m, c));
        return c;
    }

    public void reset() {
        combo.set(0);
    }

    public int current() {
        return combo.get();
    }

    public int max() {
        return maxCombo.get();
    }

    /**
     * Multiplier derived from the combo count: {@code 1 + (combo / threshold)}.
     * E.g. threshold=5: combo 5 → ×2, combo 10 → ×3.
     */
    public int multiplier(int threshold) {
        return 1 + (combo.get() / Math.max(1, threshold));
    }
}