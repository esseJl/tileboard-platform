package com.tileboard.engine.feature;

import com.tileboard.serial.board.Position;

import java.util.*;

/**
 * Treats the board as a graph: connected components, shortest paths,
 * spanning trees. Useful for puzzle and maze game types.
 */
public final class GraphFeature {

    private final int width;
    private final int height;

    public GraphFeature(int width, int height) {
        this.width  = width;
        this.height = height;
    }

    /**
     * BFS shortest path from {@code from} to {@code to}.
     * {@code passable} decides which positions can be traversed.
     * Returns an empty list if no path exists.
     */
    public List<Position> shortestPath(
            Position from,
            Position to,
            java.util.function.Predicate<Position> passable) {

        if (!passable.test(from) || !passable.test(to)) return List.of();

        Map<Position, Position> parent = new HashMap<>();
        Queue<Position> queue = new ArrayDeque<>();
        parent.put(from, null);
        queue.add(from);

        int[][] dirs = {{-1,0},{1,0},{0,-1},{0,1}};

        while (!queue.isEmpty()) {
            Position cur = queue.poll();
            if (cur.equals(to)) {
                return reconstructPath(parent, to);
            }
            for (int[] d : dirs) {
                int r = cur.row() + d[0], c = cur.col() + d[1];
                if (r < 0 || r >= height || c < 0 || c >= width) continue;
                Position nb = new Position(r, c);
                if (parent.containsKey(nb) || !passable.test(nb)) continue;
                parent.put(nb, cur);
                queue.add(nb);
            }
        }
        return List.of();
    }

    /** All connected components of {@code passable} positions. */
    public List<List<Position>> connectedComponents(
            java.util.function.Predicate<Position> passable) {

        Set<Position>       visited    = new HashSet<>();
        List<List<Position>> components = new ArrayList<>();

        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                Position p = new Position(r, c);
                if (visited.contains(p) || !passable.test(p)) continue;
                List<Position> component = bfs(p, passable, visited);
                components.add(component);
            }
        }
        return components;
    }

    private List<Position> bfs(
            Position start,
            java.util.function.Predicate<Position> passable,
            Set<Position> visited) {

        List<Position>       result = new ArrayList<>();
        Queue<Position>      queue  = new ArrayDeque<>();
        int[][] dirs = {{-1,0},{1,0},{0,-1},{0,1}};

        queue.add(start);
        visited.add(start);

        while (!queue.isEmpty()) {
            Position cur = queue.poll();
            result.add(cur);
            for (int[] d : dirs) {
                int r = cur.row() + d[0], c = cur.col() + d[1];
                if (r < 0 || r >= height || c < 0 || c >= width) continue;
                Position nb = new Position(r, c);
                if (visited.contains(nb) || !passable.test(nb)) continue;
                visited.add(nb);
                queue.add(nb);
            }
        }
        return result;
    }

    private List<Position> reconstructPath(Map<Position, Position> parent, Position to) {
        LinkedList<Position> path = new LinkedList<>();
        for (Position cur = to; cur != null; cur = parent.get(cur)) {
            path.addFirst(cur);
        }
        return List.copyOf(path);
    }
}