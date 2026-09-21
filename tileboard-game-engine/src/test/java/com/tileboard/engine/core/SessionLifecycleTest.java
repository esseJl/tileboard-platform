package com.tileboard.engine.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SessionLifecycleTest {
    @Test
    void startSucceedsOnlyOnce() {
        SessionLifecycle lc = new SessionLifecycle();
        assertTrue(lc.start());
        assertFalse(lc.start());
        assertEquals(GameStatus.RUNNING, lc.current());
    }

    @Test
    void finishIsIdempotent() {
        SessionLifecycle lc = new SessionLifecycle();
        lc.start();
        assertTrue(lc.finish(GameStatus.FINISHED));
        assertFalse(lc.finish(GameStatus.FINISHED)); // بار دوم false برمی‌گرداند اما نباید دوباره صدا زده شود
        assertEquals(GameStatus.FINISHED, lc.current());
    }

    @Test
    void cannotFinishBeforeStart() {
        SessionLifecycle lc = new SessionLifecycle();
        assertFalse(lc.finish(GameStatus.STOPPED));
    }
}
