package com.tileboard.app.gameengine.games.jump;

import com.tileboard.serial.board.Position;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

/**
 * The built-in {@link JumpPattern} shapes. Every method here is a pure
 * function of {@code (width, height)} - none of them hold any per-game
 * state, so a single constant instance (e.g. {@link #row()}) is reused
 * across every session and every board size without needing to be
 * re-created per game.
 */
public final class JumpPatterns {

    private JumpPatterns() {
    }

    /** One frame per row, top to bottom - the whole row lights up at once. */
    public static JumpPattern row() {
        return (width, height) -> IntStream.range(0, height)
                .mapToObj(row -> wholeRow(width, row))
                .toList();
    }

    /** One frame per column, left to right - the whole column lights up at once. */
    public static JumpPattern column() {
        return (width, height) -> IntStream.range(0, width)
                .mapToObj(col -> wholeColumn(height, col))
                .toList();
    }

    /** Diagonal band running top-left to bottom-right, swept left to right. */
    public static JumpPattern mainDiagonal() {
        return (width, height) -> IntStream.range(-(width - 1), height)
                .mapToObj(offset -> positionsWhere(width, height, (row, col) -> row - col == offset))
                .toList();
    }

    /** Diagonal band running top-right to bottom-left, swept left to right. */
    public static JumpPattern antiDiagonal() {
        return (width, height) -> IntStream.range(0, width + height - 1)
                .mapToObj(sum -> positionsWhere(width, height, (row, col) -> row + col == sum))
                .toList();
    }

    /**
     * Chains {@link #row()}, {@link #column()}, {@link #mainDiagonal()} and
     * {@link #antiDiagonal()} in a random order (re-shuffled every time a
     * game starts), so the player faces a different, unpredictable shape
     * each round instead of always the same single sweep.
     */
    public static JumpPattern rotating() {
        return (width, height) -> {
            List<JumpPattern> repertoire = new ArrayList<>(List.of(row(), column(), mainDiagonal(), antiDiagonal()));
            Collections.shuffle(repertoire, ThreadLocalRandom.current());
            return repertoire.stream()
                    .flatMap(pattern -> pattern.framesFor(width, height).stream())
                    .toList();
        };
    }

    private static List<Position> wholeRow(int width, int row) {
        return IntStream.range(0, width).mapToObj(col -> new Position(row, col)).toList();
    }

    private static List<Position> wholeColumn(int height, int col) {
        return IntStream.range(0, height).mapToObj(row -> new Position(row, col)).toList();
    }

    private static List<Position> positionsWhere(int width, int height, CellPredicate predicate) {
        List<Position> positions = new ArrayList<>();
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                if (predicate.test(row, col)) {
                    positions.add(new Position(row, col));
                }
            }
        }
        return positions;
    }

    @FunctionalInterface
    private interface CellPredicate {
        boolean test(int row, int col);
    }
}
