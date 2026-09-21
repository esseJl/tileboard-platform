package com.tileboard.engine.feature.neighbor;


import com.tileboard.serial.board.Position;

import java.util.ArrayList;
import java.util.List;

public final class GridTopology {
    private static final int[][] FOUR = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
    private static final int[][] DIAG = {{-1, -1}, {-1, 1}, {1, -1}, {1, 1}};
    private static final int[][] EIGHT = concat(FOUR, DIAG);

    private final int width;
    private final int height;

    public GridTopology(int width, int height) {
        this.width = width;
        this.height = height;
    }

    private static int[][] concat(int[][] a, int[][] b) {
        int[][] out = new int[a.length + b.length][];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    public boolean inBounds(int row, int col) {
        return row >= 0 && row < height && col >= 0 && col < width;
    }

    public List<Position> neighbors(int row, int col, Adjacency adjacency) {
        int[][] dirs = switch (adjacency) {
            case FOUR_WAY -> FOUR;
            case EIGHT_WAY -> EIGHT;
            case DIAGONAL_ONLY -> DIAG;
        };
        List<Position> result = new ArrayList<>(dirs.length);
        for (int[] d : dirs) {
            int r = row + d[0], c = col + d[1];
            if (inBounds(r, c)) result.add(new Position(r, c));
        }
        return result;
    }
}

