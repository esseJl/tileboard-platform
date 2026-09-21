package com.tileboard.engine.feature;

import com.tileboard.engine.model.TileEvent;
import com.tileboard.serial.board.Position;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReactionSpeedTrackerTest {
    @Test
    void recordsOnlyAfterStimulus() {
        ReactionSpeedTracker tracker = new ReactionSpeedTracker();
        tracker.record(TileEvent.touch(new Position(0, 0), "s1")); // no stimulus yet
        assertEquals(0, tracker.reactionCount());

        tracker.stimulus();
        tracker.record(TileEvent.touch(new Position(0, 0), "s1"));
        assertEquals(1, tracker.reactionCount());
        assertTrue(tracker.averageReactionMillis().isPresent());
    }

    @Test
    void bestReactionTracksMinimum() {
        ReactionSpeedTracker tracker = new ReactionSpeedTracker();
        tracker.stimulus();
        tracker.record(TileEvent.touch(new Position(0, 0), "s1"));
        Duration first = tracker.lastReaction();
        tracker.stimulus();
        tracker.record(TileEvent.touch(new Position(0, 0), "s1"));
        assertTrue(tracker.bestReaction().compareTo(first.plusSeconds(1)) < 0);
    }

    @Test
    void resetClearsAllStats() {
        ReactionSpeedTracker tracker = new ReactionSpeedTracker();
        tracker.stimulus();
        tracker.record(TileEvent.touch(new Position(0, 0), "s1"));
        tracker.reset();
        assertEquals(Duration.ZERO, tracker.lastReaction());
        assertEquals(Duration.ZERO, tracker.bestReaction());
        assertEquals(0, tracker.reactionCount());
        assertTrue(tracker.averageReactionMillis().isEmpty());
    }
}
