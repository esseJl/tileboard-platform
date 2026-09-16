package com.tileboard.app.service.game.engine.pattern;

import com.tileboard.app.service.game.engine.support.NeighborUtils;
import com.tileboard.serial.board.Position;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Built-in random path / wave / pattern generators used by memory, arcade
 * and educational-motor games.
 */
public final class PathGenerator {

    private final int width;
    private final int height;
    private final Random random;

    public PathGenerator(int width, int height) {
        this(width, height, ThreadLocalRandom.current());
    }

    public PathGenerator(int width, int height, Random random) {
        this.width = width;
        this.height = height;
        this.random = random;
    }

    /**
     * Generates a simple self-avoiding random walk of the requested length
     * using orthogonal moves only.
     */
    public List<Position> randomOrthogonalPath(int length) {
        List<Position> path = new ArrayList<>(length);
        Position start = new Position(random.nextInt(height), random.nextInt(width));
        path.add(start);

        while (path.size() < length) {
            Position last = path.get(path.size() - 1);
            List<Position> candidates = NeighborUtils.orthogonalNeighbors(width, height, last);
            candidates.removeIf(path::contains);
            if (candidates.isEmpty()) {
                break;
            }
            path.add(candidates.get(random.nextInt(candidates.size())));
        }
        return path;
    }

    /**
     * Generates a straight row, column or diagonal "wave" segment that can be
     * animated by the caller (arcade / jump style).
     */
    public List<Position> line(int startRow, int startCol, NeighborUtils.Direction dir, int length) {
        List<Position> line = new ArrayList<>(length);
        int r = startRow;
        int c = startCol;
        for (int i = 0; i < length; i++) {
            if (r < 0 || r >= height || c < 0 || c >= width) {
                break;
            }
            line.add(new Position(r, c));
            r += dir.dRow();
            c += dir.dCol();
        }
        return line;
    }

    public Position randomPosition() {
        return new Position(random.nextInt(height), random.nextInt(width));
    }

    public List<Position> randomPositions(int count) {
        List<Position> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            result.add(randomPosition());
        }
        return result;
    }
}
