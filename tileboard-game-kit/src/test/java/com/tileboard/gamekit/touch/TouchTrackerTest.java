package com.tileboard.gamekit.touch;

import com.tileboard.serial.board.Position;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TouchTrackerTest {

    @Test
    void recordsCountOrderAndTiming() throws InterruptedException {
        TouchTracker tracker = new TouchTracker();
        Position a = new Position(0, 0);
        Position b = new Position(1, 1);

        TouchEvent first = tracker.record(a);
        Thread.sleep(5);
        TouchEvent second = tracker.record(b);
        TouchEvent third = tracker.record(a);

        assertEquals(3, tracker.totalTouches());
        assertEquals(2, tracker.touchCountAt(a));
        assertEquals(1, tracker.touchCountAt(b));
        assertEquals(List.of(first, second, third), tracker.history());
        assertEquals(1, first.sequenceNumber());
        assertEquals(3, third.sequenceNumber());
        assertTrue(second.sinceLastTouch().toMillis() >= 5);
        assertEquals(third, tracker.lastTouch().orElseThrow());
    }

    @Test
    void recordAllPreservesOrder() {
        TouchTracker tracker = new TouchTracker();
        List<Position> touched = List.of(new Position(0, 0), new Position(0, 1), new Position(0, 2));

        List<TouchEvent> events = tracker.recordAll(touched);

        assertEquals(touched, events.stream().map(TouchEvent::position).toList());
        assertEquals(3, tracker.totalTouches());
    }
}
