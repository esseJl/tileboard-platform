package com.tileboard.engine.feature;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class ComboTracker {

    private final AtomicReference<ComboState> state = new AtomicReference<>(ComboState.INITIAL);
    private volatile long timeoutMillis = 2_000L;

    public void setComboTimeout(long millis) {
        this.timeoutMillis = millis;
    }

    /**
     * Atomically applies one hit and returns the resulting combo count.
     */
    public int hit() {
        long now = System.nanoTime();
        ComboState next = state.updateAndGet(prev -> {
            boolean expired = prev.lastHitNanos() > 0
                    && TimeUnit.NANOSECONDS.toMillis(now - prev.lastHitNanos()) > timeoutMillis;
            int base = expired ? 0 : prev.combo();
            int combo = base + 1;
            int max = Math.max(prev.maxCombo(), combo);
            return new ComboState(combo, max, now);
        });
        return next.combo();
    }

    public void reset() {
        state.updateAndGet(prev -> new ComboState(0, prev.maxCombo(), prev.lastHitNanos()));
    }

    public int current() {
        return state.get().combo();
    }

    public int max() {
        return state.get().maxCombo();
    }

    public int multiplier(int threshold) {
        return 1 + (current() / Math.max(1, threshold));
    }

    private record ComboState(int combo, int maxCombo, long lastHitNanos) {
        static final ComboState INITIAL = new ComboState(0, 0, 0L);
    }
}