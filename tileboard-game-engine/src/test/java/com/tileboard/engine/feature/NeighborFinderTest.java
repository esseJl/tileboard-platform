package com.tileboard.engine.feature;

import com.tileboard.engine.feature.neighbor.Adjacency;
import com.tileboard.engine.feature.neighbor.NeighborFinder;
import com.tileboard.serial.board.Position;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NeighborFinderTest {
    @Test
    void fourWayCornerHasTwoNeighbors() {
        NeighborFinder finder = new NeighborFinder(3, 3, Adjacency.FOUR_WAY);
        assertEquals(2, finder.of(0, 0).size());
    }

    @Test
    void eightWayCenterHasEightNeighbors() {
        NeighborFinder finder = new NeighborFinder(3, 3, Adjacency.EIGHT_WAY);
        assertEquals(8, finder.of(1, 1).size());
    }

    @Test
    void connectedRegionFloodFillsPassableArea() {
        NeighborFinder finder = new NeighborFinder(3, 3, Adjacency.FOUR_WAY);
        List<Position> region = finder.connectedRegion(new Position(0, 0), p -> true);
        assertEquals(9, region.size());
    }
}