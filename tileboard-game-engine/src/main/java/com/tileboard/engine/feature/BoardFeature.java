package com.tileboard.engine.feature;

import com.tileboard.engine.model.TileColor;
import com.tileboard.serial.board.Board;
import com.tileboard.serial.board.Position;

import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Utility operations on a {@link Board}:
 * finding tiles by color, computing paths, checking win conditions, etc.
 */
public final class BoardFeature {

    private final int width;
    private final int height;

    public BoardFeature(int width, int height) {
        this.width  = width;
        this.height = height;
    }

    /** All positions where the tile matches {@code predicate}. */
    public List<Position> find(Board<TileColor> board, Predicate<TileColor> predicate) {
        return board.positionsWhere(predicate);
    }

    public List<Position> findByColor(Board<TileColor> board, TileColor color) {
        return board.positionsWhere(color::equals);
    }

    /** {@code true} if every tile on the board satisfies {@code predicate}. */
    public boolean allMatch(Board<TileColor> board, Predicate<TileColor> predicate) {
        return board.positionsWhere(predicate.negate()).isEmpty();
    }

    /** {@code true} if no tile satisfies {@code predicate}. */
    public boolean noneMatch(Board<TileColor> board, Predicate<TileColor> predicate) {
        return board.positionsWhere(predicate).isEmpty();
    }

    /** Number of tiles with {@code color}. */
    public long countByColor(Board<TileColor> board, TileColor color) {
        return findByColor(board, color).size();
    }

    /** Checks whether {@code position} is within the board bounds. */
    public boolean isValid(Position position) {
        return position.row() >= 0 && position.row() < height
                && position.col() >= 0 && position.col() < width;
    }

    /**
     * Returns the Manhattan distance between two positions.
     */
    public int manhattanDistance(Position a, Position b) {
        return Math.abs(a.row() - b.row()) + Math.abs(a.col() - b.col());
    }

    /**
     * Returns the Chebyshev (king-move) distance between two positions.
     */
    public int chebyshevDistance(Position a, Position b) {
        return Math.max(Math.abs(a.row() - b.row()), Math.abs(a.col() - b.col()));
    }
}