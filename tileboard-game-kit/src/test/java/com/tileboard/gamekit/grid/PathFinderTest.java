package com.tileboard.gamekit.grid;

import com.tileboard.gamekit.model.TilePosition;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class PathFinderTest {
    @Test
    void findsShortestOrthogonalPath() {
        var blocked = Set.of(new TilePosition(0,1), new TilePosition(1,1));
        var path = PathFinder.shortestPath(
                new TilePosition(0,0),
                new TilePosition(2,2),
                3, 3, NeighborMode.ORTHOGONAL,
                p -> !blocked.contains(p)).orElseThrow();

        assertEquals(new TilePosition(0,0), path.get(0));
        assertEquals(new TilePosition(2,2), path.get(path.size()-1));
    }
}
