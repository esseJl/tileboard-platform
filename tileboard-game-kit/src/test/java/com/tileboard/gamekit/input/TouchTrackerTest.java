package com.tileboard.gamekit.input;

import com.tileboard.gamekit.model.TilePosition;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TouchTrackerTest {
    @Test
    void recordsOrderCountTimingAndTargetMatch() {
        TouchTracker tracker = new TouchTracker(
                e -> "RED",
                e -> e.position().row() == 0 ? "RED" : "BLUE");

        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        tracker.record(new TouchEvent(new TilePosition(0, 0), t0, "p1"));
        var second = tracker.record(new TouchEvent(
                new TilePosition(1, 0), t0.plusMillis(250), "p1"));

        assertEquals(2, tracker.count());
        assertEquals(2, second.sequence());
        assertEquals(250, second.sincePreviousTouch().toMillis());
        assertFalse(second.matchesTarget());
        assertEquals(List.of(new TilePosition(0,0), new TilePosition(1,0)), tracker.path());
    }
}
