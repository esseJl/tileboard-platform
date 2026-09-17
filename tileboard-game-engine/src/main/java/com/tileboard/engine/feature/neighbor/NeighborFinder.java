package com.tileboard.engine.feature.neighbor;

import com.tileboard.serial.board.Position;

import java.util.ArrayList;
import java.util.List;

/**
 * Returns the valid neighbours of a position on a bounded grid.
 * Thread-safe (immutable after construction).
 */
public final class NeighborFinder {

    private final int       width;
    private final int       height;
    private final Adjacency adjacency;

    // Direction arrays: {dRow, dCol}
    private static final int[][] FOUR  = {{-1,0},{1,0},{0,-1},{0,1}};
    private static final int[][] DIAG  = {{-1,-1},{-1,1},{1,-1},{1,1}};
    private static final int[][] EIGHT;

    static {
        EIGHT = new int[8][2];
        System.arraycopy(FOUR, 0, EIGHT, 0, 4);
        System.arraycopy(DIAG, 0, EIGHT, 4, 4);
    }

    public NeighborFinder(int width, int height, Adjacency adjacency) {
        this.width     = width;
        this.height    = height;
        this.adjacency = adjacency;
    }

    public List<Position> of(Position position) {
        return of(position.row(), position.col());
    }

    public List<Position> of(int row, int col) {
        int[][] dirs = switch (adjacency) {
            case FOUR_WAY      -> FOUR;
            case EIGHT_WAY     -> EIGHT;
            case DIAGONAL_ONLY -> DIAG;
        };
        List<Position> result = new ArrayList<>(dirs.length);
        for (int[] d : dirs) {
            int r = row + d[0], c = col + d[1];
            if (r >= 0 && r < height && c >= 0 && c < width) {
                result.add(new Position(r, c));
            }
        }
        return result;
    }

    /** All positions reachable from {@code start} via BFS (flood-fill). */
    public List<Position> connectedRegion(
            Position start,
            java.util.function.Predicate<Position> passable) {

        List<Position>       visited = new ArrayList<>();
        java.util.ArrayDeque<Position> queue   = new java.util.ArrayDeque<>();
        java.util.Set<Position>        seen    = new java.util.HashSet<>();

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