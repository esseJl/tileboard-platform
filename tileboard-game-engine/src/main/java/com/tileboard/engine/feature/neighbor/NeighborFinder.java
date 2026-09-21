package com.tileboard.engine.feature.neighbor;

import com.tileboard.serial.board.Position;

import java.util.*;
import java.util.function.Predicate;

/**
 * Returns the valid neighbours of a position on a bounded grid.
 * Thread-safe (immutable after construction).
 */
public final class NeighborFinder {

    private final GridTopology topology;
    private final Adjacency adjacency;

    public NeighborFinder(int width, int height, Adjacency adjacency) {
        this.topology = new GridTopology(width, height);
        this.adjacency = adjacency;
    }

    public List<Position> of(Position position) {
        return of(position.row(), position.col());
    }

    public List<Position> of(int row, int col) {
        return topology.neighbors(row, col, adjacency);
    }

    public List<Position> connectedRegion(Position start, Predicate<Position> passable) {
        List<Position> visited = new ArrayList<>();
        ArrayDeque<Position> queue = new ArrayDeque<>();
        Set<Position> seen = new HashSet<>();
        queue.add(start);
        seen.add(start);

        while (!queue.isEmpty()) {
            Position cur = queue.poll();
            visited.add(cur);
            for (Position nb : of(cur)) {
                if (!seen.contains(nb) && passable.test(nb)) {
                    seen.add(nb);
                    queue.add(nb);
                }
            }
        }
        return visited;
    }
}