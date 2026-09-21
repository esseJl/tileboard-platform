package com.tileboard.engine.feature;

import com.tileboard.serial.board.Position;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphFeatureTest {
    @Test
    void shortestPathFindsDirectRoute() {
        GraphFeature graph = new GraphFeature(3, 3);
        List<Position> path = graph.shortestPath(new Position(0, 0), new Position(2, 2), p -> true);
        assertEquals(new Position(0, 0), path.get(0));
        assertEquals(new Position(2, 2), path.get(path.size() - 1));
        assertEquals(5, path.size()); // manhattan distance 4 + start
    }

    @Test
    void unreachableTargetReturnsEmptyPath() {
        GraphFeature graph = new GraphFeature(3, 3);
        List<Position> path = graph.shortestPath(new Position(0, 0), new Position(2, 2),
                p -> p.equals(new Position(0, 0)) || p.equals(new Position(2, 2)));
        assertTrue(path.isEmpty());
    }

    @Test
    void connectedComponentsGroupsIsolatedCells() {
        GraphFeature graph = new GraphFeature(2, 1);
        List<List<Position>> components = graph.connectedComponents(p -> true);
        assertEquals(1, components.size());
        assertEquals(2, components.get(0).size());
    }
}
