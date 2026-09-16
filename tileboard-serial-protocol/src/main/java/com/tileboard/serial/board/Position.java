package com.tileboard.serial.board;

/**
 * A zero-based (row, column) coordinate on a {@link Board}.
 */
public record Position(int row, int col) {

    public Position {
        if (row < 0 || col < 0) {
            throw new IllegalArgumentException("row and col must be >= 0, got row=" + row + ", col=" + col);
        }
    }
}
