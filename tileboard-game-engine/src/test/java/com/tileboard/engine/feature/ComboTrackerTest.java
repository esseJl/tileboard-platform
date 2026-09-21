package com.tileboard.engine.feature;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ComboTrackerTest {

    @Test
    void hitsWithinTimeoutIncreaseCombo() {
        ComboTracker tracker = new ComboTracker();
        tracker.setComboTimeout(10_000);
        assertEquals(1, tracker.hit());
        assertEquals(2, tracker.hit());
        assertEquals(2, tracker.current());
        assertEquals(2, tracker.max());
    }

    @Test
    void resetKeepsMaxButZeroesCurrent() {
        ComboTracker tracker = new ComboTracker();
        tracker.hit();
        tracker.hit();
        tracker.reset();
        assertEquals(0, tracker.current());
        assertEquals(2, tracker.max());
    }

    @Test
    void expiredComboRestartsFromOne() throws InterruptedException {
        ComboTracker tracker = new ComboTracker();
        tracker.setComboTimeout(1);
        tracker.hit();
        Thread.sleep(20);
        assertEquals(1, tracker.hit());
    }

    @Test
    void multiplierScalesWithThreshold() {
        ComboTracker tracker = new ComboTracker();
        tracker.setComboTimeout(10_000);
        for (int i = 0; i < 5; i++) tracker.hit();
        assertEquals(1 + 5 / 2, tracker.multiplier(2));
    }
}
