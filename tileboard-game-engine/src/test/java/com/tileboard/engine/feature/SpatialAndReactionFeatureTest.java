package com.tileboard.engine.feature;

import com.tileboard.engine.feature.neighbor.Adjacency;
import com.tileboard.engine.feature.neighbor.NeighborFinder;
import com.tileboard.engine.model.TileEvent;
import com.tileboard.engine.model.TileEventType;
import com.tileboard.serial.board.Position;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SpatialAndReactionFeatureTest {

    @Test
    void neighborFinderSupportsAllAdjacencyModesAndFloodFill() {
        Position center = new Position(1,1);
        assertEquals(4, new NeighborFinder(3,3, Adjacency.FOUR_WAY).of(center).size());
        assertEquals(8, new NeighborFinder(3,3, Adjacency.EIGHT_WAY).of(center).size());
        assertEquals(4, new NeighborFinder(3,3, Adjacency.DIAGONAL_ONLY).of(center).size());
        assertEquals(2, new NeighborFinder(3,3, Adjacency.FOUR_WAY).of(0,0).size());

        NeighborFinder finder = new NeighborFinder(3,3, Adjacency.FOUR_WAY);
        Set<Position> blocked = Set.of(new Position(1,0), new Position(1,1), new Position(1,2));
        List<Position> region = finder.connectedRegion(new Position(0,0), p -> !blocked.contains(p));
        assertEquals(Set.of(new Position(0,0), new Position(0,1), new Position(0,2)), Set.copyOf(region));
    }

    @Test
    void reactionTrackerIgnoresEventsWithoutStimulusAndConsumesEachStimulusOnce() throws Exception {
        ReactionSpeedTracker tracker = new ReactionSpeedTracker();
        TileEvent event = new TileEvent(new Position(0,0), TileEventType.TOUCH, Instant.now(), "s");
        tracker.record(event);
        assertEquals(0, tracker.reactionCount());
        assertTrue(tracker.averageReactionMillis().isEmpty());

        tracker.stimulus();
        Thread.sleep(2);
        tracker.record(new TileEvent(new Position(0,1), TileEventType.TOUCH, Instant.now(), "s"));
        assertEquals(1, tracker.reactionCount());
        assertFalse(tracker.lastReaction().isZero());
        assertEquals(tracker.lastReaction(), tracker.bestReaction());
        assertTrue(tracker.averageReactionMillis().isPresent());

        tracker.record(event);
        assertEquals(1, tracker.reactionCount());
        tracker.reset();
        assertEquals(0, tracker.reactionCount());
        assertTrue(tracker.lastReaction().isZero());
        assertTrue(tracker.bestReaction().isZero());
    }
}
