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
}