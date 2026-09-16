package com.tileboard.gamekit.geometry;

import com.tileboard.serial.board.Position;

import java.util.ArrayList;
import java.util.List;

/**
 * The built-in "neighbors" capability: which cells are adjacent to a given
 * cell, for a given board size. A pure function of {@code (position, width,
 * height)} - no state, so a single constant instance (e.g.
 * {@link #fourDirections()}) is reused across every session and board size.
 *
 * <p>This is also the extension point for {@link Connectivity} (flood fill,
 * shortest path, "are these two cells connected?") - any {@code Neighbors}
 * implementation, built-in or custom, works with every method there.
 */
@FunctionalInterface
public interface Neighbors {

    /** The in-bounds cells adjacent to {@code position} on a {@code width x height} board. Never contains {@code position} itself. */
    List<Position> of(Position position, int width, int height);

    /** Up/down/left/right - the common "rook move" adjacency. */
    static Neighbors fourDirections() {
        return offsets(new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}});
    }

    /** Up/down/left/right plus the four diagonals - "king move" adjacency. */
    static Neighbors eightDirections() {
        return offsets(new int[][]{
                {-1, -1}, {-1, 0}, {-1, 1},
                {0, -1}, {0, 1},
                {1, -1}, {1, 0}, {1, 1}
        });
    }

    /** Builds a {@code Neighbors} from a fixed set of {@code (rowOffset, colOffset)} pairs, e.g. a knight's move. */
    static Neighbors offsets(int[][] rowColOffsets) {
        int[][] copy = rowColOffsets.clone();
        return (position, width, height) -> {
            List<Position> result = new ArrayList<>(copy.length);
            for (int[] offset : copy) {
                int row = position.row() + offset[0];
                int col = position.col() + offset[1];
                if (row >= 0 && row < height && col >= 0 && col < width) {
                    result.add(new Position(row, col));
                }
            }
            return result;
        };
    }
}
