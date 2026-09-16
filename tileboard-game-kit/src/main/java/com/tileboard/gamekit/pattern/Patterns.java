package com.tileboard.gamekit.pattern;

import com.tileboard.serial.board.Position;
import com.tileboard.gamekit.time.RandomSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Factory for reusable rows, columns, diagonals, paths, waves and randomized patterns. */
public final class Patterns {
    private Patterns() {}

    public static MovementPattern rows() {
        return (w, h) -> {
            validate(w, h);
            List<List<Position>> frames = new ArrayList<>();
            for (int r = 0; r < h; r++) {
                List<Position> row = new ArrayList<>();
                for (int c = 0; c < w; c++) row.add(new Position(r, c));
                frames.add(List.copyOf(row));
            }
            return List.copyOf(frames);
        };
    }

    public static MovementPattern columns() {
        return (w, h) -> {
            validate(w, h);
            List<List<Position>> frames = new ArrayList<>();
            for (int c = 0; c < w; c++) {
                List<Position> col = new ArrayList<>();
                for (int r = 0; r < h; r++) col.add(new Position(r, c));
                frames.add(List.copyOf(col));
            }
            return List.copyOf(frames);
        };
    }

    public static MovementPattern diagonal() {
        return (w, h) -> {
            validate(w, h);
            List<List<Position>> frames = new ArrayList<>();
            for (int sum = 0; sum <= w + h - 2; sum++) {
                List<Position> frame = new ArrayList<>();
                for (int r = 0; r < h; r++) {
                    int c = sum - r;
                    if (c >= 0 && c < w) frame.add(new Position(r, c));
                }
                frames.add(List.copyOf(frame));
            }
            return List.copyOf(frames);
        };
    }

    public static MovementPattern wave() {
        return (w, h) -> {
            validate(w, h);
            List<Position> path = new ArrayList<>();
            for (int r = 0; r < h; r++) {
                if ((r & 1) == 0) for (int c = 0; c < w; c++) path.add(new Position(r, c));
                else for (int c = w - 1; c >= 0; c--) path.add(new Position(r, c));
            }
            return path.stream().map(List::of).toList();
        };
    }

    public static MovementPattern rotatingBuiltins(RandomSource random) {
        Objects.requireNonNull(random, "random");
        List<MovementPattern> builtins = List.of(rows(), columns(), diagonal(), wave());
        return (w, h) -> builtins.get(random.nextInt(builtins.size())).framesFor(w, h);
    }

    public static MovementPattern randomTiles(RandomSource random, int activeTiles, int frames) {
        Objects.requireNonNull(random, "random");
        if (activeTiles < 1 || frames < 1) throw new IllegalArgumentException("activeTiles and frames must be >= 1");
        return (w, h) -> {
            validate(w, h);
            int total = Math.multiplyExact(w, h);
            if (activeTiles > total) throw new IllegalArgumentException("activeTiles exceeds board size");
            List<Position> all = new ArrayList<>();
            for (int r = 0; r < h; r++) for (int c = 0; c < w; c++) all.add(new Position(r, c));
            List<List<Position>> result = new ArrayList<>();
            for (int f = 0; f < frames; f++) {
                List<Position> shuffled = new ArrayList<>(all);
                for (int i = shuffled.size() - 1; i > 0; i--) {
                    int j = random.nextInt(i + 1);
                    Position tmp = shuffled.get(i); shuffled.set(i, shuffled.get(j)); shuffled.set(j, tmp);
                }
                result.add(List.copyOf(shuffled.subList(0, activeTiles)));
            }
            return List.copyOf(result);
        };
    }

    private static void validate(int w, int h) {
        if (w < 1 || h < 1) throw new IllegalArgumentException("width and height must be >= 1");
    }
}
