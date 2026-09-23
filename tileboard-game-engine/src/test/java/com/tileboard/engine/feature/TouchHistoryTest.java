package com.tileboard.engine.feature;

import com.tileboard.engine.model.TileEvent;
import com.tileboard.serial.board.Position;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TouchHistoryTest {
    @Test
    void ringBufferDropsOldestOnOverflow() {
        TouchHistory history = new TouchHistory("s1", 2);
        history.record(TileEvent.touch(new Position(0, 0), "s1"));
        history.record(TileEvent.touch(new Position(0, 1), "s1"));
        history.record(TileEvent.touch(new Position(0, 2), "s1"));
        assertEquals(2, history.positionOrder().size());
        assertEquals(new Position(0, 2), history.lastTouchedPosition().orElseThrow());
        assertEquals(3, history.totalTouches());
    }

    @Test
    void constructorRejectsNonPositiveMaxSize() {
        assertThrows(IllegalArgumentException.class, () -> new TouchHistory("s1", 0));
    }

    @Test
    void resetClearsHistory() {
        TouchHistory history = new TouchHistory("s1");
        history.record(TileEvent.touch(new Position(0, 0), "s1"));
        history.reset();
        assertTrue(history.positionOrder().isEmpty());
        assertEquals(0, history.totalTouches());
    }

    @Test
    void recentReturnsNewestFirstLimitedAndCapped() {
        TouchHistory history = new TouchHistory("s1");
        for (int i = 0; i < 3; i++) {
            history.record(TileEvent.touch(new Position(0, i), "s1"));
        }

        var top2 = history.recent(2);
        assertEquals(2, top2.size());
        assertEquals(new Position(0, 2), top2.get(0).position(), "element 0 must be the very last touch");
        assertEquals(new Position(0, 1), top2.get(1).position());

        // Asking for more than what's recorded must not throw or pad the result.
        assertEquals(3, history.recent(10).size());
    }

    @Test
    void recentIsEmptyWhenNothingRecordedOrLimitIsZero() {
        TouchHistory history = new TouchHistory("s1");
        assertTrue(history.recent(5).isEmpty());

        history.record(TileEvent.touch(new Position(0, 0), "s1"));
        assertTrue(history.recent(0).isEmpty());
    }

    @Test
    void recentRejectsNegativeLimit() {
        TouchHistory history = new TouchHistory("s1");
        assertThrows(IllegalArgumentException.class, () -> history.recent(-1));
    }
}