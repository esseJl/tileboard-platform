package com.tileboard.gamekit.pattern;

import com.tileboard.gamekit.time.RandomSource;
import com.tileboard.serial.board.Position;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

/**
 * The built-in {@link MovementPattern} shapes, covering "الگوی حرکت"
 * (movement pattern), "مسیر" (path) and "wave". Every method is a pure
 * function of {@code (width, height)} (or of a supplied {@link RandomSource}
 * / waypoint list) - none holds per-game state.
 */
public final class Patterns {

    private Patterns() {
    }

    /** One frame per row, top to bottom - the whole row lights up at once. */
    public static MovementPattern row() {
        return (width, height) -> IntStream.range(0, height)
                .mapToObj(row -> wholeRow(width, row))
                .toList();
    }

    /** One frame per column, left to right - the whole column lights up at once. */
    public static MovementPattern column() {
        return (width, height) -> IntStream.range(0, width)
                .mapToObj(col -> wholeColumn(height, col))
                .toList();
    }

    /** Diagonal band running top-left to bottom-right, swept left to right. */
    public static MovementPattern mainDiagonal() {
        return (width, height) -> IntStream.range(-(width - 1), height)
                .mapToObj(offset -> positionsWhere(width, height, (row, col) -> row - col == offset))
                .toList();
    }

    /** Diagonal band running top-right to bottom-left, swept left to right. */
    public static MovementPattern antiDiagonal() {
        return (width, height) -> IntStream.range(0, width + height - 1)
                .mapToObj(sum -> positionsWhere(width, height, (row, col) -> row + col == sum))
                .toList();
    }

    /**
     * A single-cell "path" pattern: one frame per waypoint, in the order
     * given - e.g. a route a token walks along, or the sequence a
     * memory/Simon-style game must be touched back in. {@code waypoints}
     * must be non-empty.
     */
    public static MovementPattern path(List<Position> waypoints) {
        if (waypoints.isEmpty()) {
            throw new IllegalArgumentException("waypoints must not be empty");
        }
        List<List<Position>> frames = waypoints.stream().map(List::of).toList();
        return (width, height) -> frames;
    }

    /**
     * A sine-shaped band of {@code thickness} rows sweeping left to right,
     * its vertical center following {@code amplitude * sin(2*pi*col/wavelength)}
     * around the vertical middle of the board - the built-in "wave" shape.
     */
    public static MovementPattern wave(double amplitude, double wavelength, int thickness) {
        if (thickness < 1) {
            throw new IllegalArgumentException("thickness must be >= 1, got " + thickness);
        }
        return (width, height) -> {
            List<List<Position>> frames = new ArrayList<>(width);
            double centerRow = (height - 1) / 2.0;
            for (int col = 0; col < width; col++) {
                double waveCenter = centerRow + amplitude * Math.sin(2 * Math.PI * col / wavelength);
                int centerRowInt = (int) Math.round(waveCenter);
                List<Position> frame = new ArrayList<>(thickness);
                for (int r = centerRowInt - thickness / 2; r <= centerRowInt + (thickness - 1) / 2; r++) {
                    if (r >= 0 && r < height) {
                        frame.add(new Position(r, col));
                    }
                }
                frames.add(frame.isEmpty() ? List.of(new Position(clamp(centerRowInt, 0, height - 1), col)) : frame);
            }
            return frames;
        };
    }

    /**
     * A single-cell token performing a random walk of {@code steps} steps,
     * starting at the board's center - the built-in "random" pattern, e.g.
     * for a target that wanders unpredictably. Re-rolled fresh every time
     * {@link MovementPattern#framesFor} is called (it is not a constant
     * sequence), since a random pattern that always produced the same walk
     * for a given board size would not actually be random across sessions.
     */
    public static MovementPattern randomWalk(RandomSource random, int steps) {
        if (steps < 1) {
            throw new IllegalArgumentException("steps must be >= 1, got " + steps);
        }
        return (width, height) -> {
            List<List<Position>> frames = new ArrayList<>(steps);
            int row = height / 2;
            int col = width / 2;
            frames.add(List.of(new Position(row, col)));
            int[][] moves = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
            for (int i = 1; i < steps; i++) {
                int[] move = moves[random.nextInt(moves.length)];
                row = clamp(row + move[0], 0, height - 1);
                col = clamp(col + move[1], 0, width - 1);
                frames.add(List.of(new Position(row, col)));
            }
            return frames;
        };
    }

    /**
     * Chains a repertoire of patterns in a random order (re-shuffled every
     * time {@link MovementPattern#framesFor} is called), so the player
     * faces a different, unpredictable sequence each round instead of
     * always the same fixed one.
     */
    public static MovementPattern rotating(RandomSource random, List<MovementPattern> repertoire) {
        List<MovementPattern> fixed = List.copyOf(repertoire);
        return (width, height) -> {
            List<MovementPattern> shuffled = new ArrayList<>(fixed);
            shuffle(shuffled, random);
            return shuffled.stream()
                    .flatMap(pattern -> pattern.framesFor(width, height).stream())
                    .toList();
        };
    }

    /** {@link #rotating(RandomSource, List)} pre-loaded with the four straight-line built-ins. */
    public static MovementPattern rotatingBuiltins(RandomSource random) {
        return rotating(random, List.of(row(), column(), mainDiagonal(), antiDiagonal()));
    }

    private static void shuffle(List<?> list, RandomSource random) {
        for (int i = list.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            Collections.swap(list, i, j);
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
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
