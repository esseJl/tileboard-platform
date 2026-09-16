package com.tileboard.gamekit.model;

import java.util.Objects;

/** Immutable zero-based row/column coordinate. */
public record TilePosition(int row, int column) {
    public TilePosition {
        if (row < 0 || column < 0) {
            throw new IllegalArgumentException("row and column must be >= 0");
        }
    }

    public int manhattanDistance(TilePosition other) {
        Objects.requireNonNull(other, "other");
        return Math.abs(row - other.row) + Math.abs(column - other.column);
    }
}
