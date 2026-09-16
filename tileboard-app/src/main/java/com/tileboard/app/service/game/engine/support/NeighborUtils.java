package com.tileboard.app.service.game.engine.support;

import com.tileboard.serial.board.Board;
import com.tileboard.serial.board.Position;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Built-in neighbour / graph helpers for path, connected-component and
 * movement-pattern games.
 */
public final class NeighborUtils {

    private NeighborUtils() {
    }

    public enum Direction {
        UP(-1, 0),
        DOWN(1, 0),
        LEFT(0, -1),
        RIGHT(0, 1),
        UP_LEFT(-1, -1),
        UP_RIGHT(-1, 1),
        DOWN_LEFT(1, -1),
        DOWN_RIGHT(1, 1);

        private final int dRow;
        private final int dCol;

        Direction(int dRow, int dCol) {
            this.dRow = dRow;
            this.dCol = dCol;
        }

        public int dRow() {
            return dRow;
        }

        public int dCol() {
            return dCol;
        }
    }

    public static final Direction[] ORTHOGONAL = {
            Direction.UP, Direction.DOWN, Direction.LEFT, Direction.RIGHT
    };

    public static final Direction[] DIAGONAL = {
            Direction.UP_LEFT, Direction.UP_RIGHT, Direction.DOWN_LEFT, Direction.DOWN_RIGHT
    };

    public static final Direction[] ALL_8 = {
            Direction.UP, Direction.DOWN, Direction.LEFT, Direction.RIGHT,
            Direction.UP_LEFT, Direction.UP_RIGHT, Direction.DOWN_LEFT, Direction.DOWN_RIGHT
    };

    public static List<Position> neighbors(int width, int height, Position p, Direction[] dirs) {
        List<Position> result = new ArrayList<>(dirs.length);
        for (Direction d : dirs) {
            int r = p.row() + d.dRow();
            int c = p.col() + d.dCol();
            if (r >= 0 && r < height && c >= 0 && c < width) {
                result.add(new Position(r, c));
            }
        }
        return result;
    }

    public static List<Position> orthogonalNeighbors(int width, int height, Position p) {
        return neighbors(width, height, p, ORTHOGONAL);
    }

    public static List<Position> allNeighbors(int width, int height, Position p) {
        return neighbors(width, height, p, ALL_8);
    }

    /**
     * Flood-fill / connected component of positions that satisfy the predicate,
     * starting from {@code seed}. Useful for "connected nodes" / region games.
     */
    public static <T> List<Position> connectedComponent(
            Board<T> board,
            Position seed,
            Predicate<T> belongs,
            Direction[] dirs
    ) {
        int w = board.width();
        int h = board.height();
        boolean[][] visited = new boolean[h][w];
        List<Position> component = new ArrayList<>();
        List<Position> stack = new ArrayList<>();
        stack.add(seed);

        while (!stack.isEmpty()) {
            Position cur = stack.remove(stack.size() - 1);
            if (visited[cur.row()][cur.col()]) {
                continue;
            }
            if (!belongs.test(board.get(cur))) {
                continue;
            }
            visited[cur.row()][cur.col()] = true;
            component.add(cur);
            for (Position n : neighbors(w, h, cur, dirs)) {
                if (!visited[n.row()][n.col()]) {
                    stack.add(n);
                }
            }
        }
        return component;
    }
}
