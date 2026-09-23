package com.tileboard.serial.board;

import static java.util.Objects.hash;

/**
 * A zero-based (row, column) coordinate on a {@link Board}.
 */
public record Position(int row, int col) {

    public Position {
        if (row < 0 || col < 0) {
            throw new IllegalArgumentException("row and col must be >= 0, got row=" + row + ", col=" + col);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Position position)) return false;

        return row == position.row && col == position.col;
    }

    @Override
    public int hashCode() {
        return hash(row, col);
    }
}
