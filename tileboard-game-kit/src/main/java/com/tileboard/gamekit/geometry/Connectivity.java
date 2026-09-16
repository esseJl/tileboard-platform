package com.tileboard.gamekit.geometry;

import com.tileboard.serial.board.Position;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * The built-in "connected nodes" / "path" capability: treats a subset of a
 * board's cells as a graph (nodes = cells in {@code nodes}, edges = whatever
 * {@link Neighbors} implementation is supplied) and answers reachability
 * and shortest-path questions over it with a plain breadth-first search.
 *
 * <p>Stateless and side-effect free - every method takes the graph as a
 * parameter and returns a fresh result, so a single {@code Connectivity}
 * "instance" is never actually needed; all methods are static. Useful for
 * games like "light every tile reachable from the one you touched" or
 * "these two tiles must be connected through only same-colored tiles to
 * score".
 */
public final class Connectivity {

    private Connectivity() {
    }

    /** Every cell reachable from {@code start} by repeatedly stepping to a neighbor that is also in {@code nodes}. Includes {@code start} itself. */
    public static Set<Position> connectedComponent(Position start, Set<Position> nodes, Neighbors neighbors, int width, int height) {
        if (!nodes.contains(start)) {
            return Set.of();
        }
        Set<Position> visited = new LinkedHashSet<>();
        Queue<Position> frontier = new ArrayDeque<>();
        frontier.add(start);
        visited.add(start);
        while (!frontier.isEmpty()) {
            Position current = frontier.poll();
            for (Position candidate : neighbors.of(current, width, height)) {
                if (nodes.contains(candidate) && visited.add(candidate)) {
                    frontier.add(candidate);
                }
            }
        }
        return Collections.unmodifiableSet(visited);
    }

    /** Whether {@code a} and {@code b} are in the same {@link #connectedComponent}. */
    public static boolean areConnected(Position a, Position b, Set<Position> nodes, Neighbors neighbors, int width, int height) {
        return connectedComponent(a, nodes, neighbors, width, height).contains(b);
    }

    /**
     * The shortest sequence of adjacent cells from {@code start} to
     * {@code end}, stepping only through cells in {@code nodes}, or
     * {@link List#of()} if no such path exists. The returned path includes
     * both {@code start} and {@code end}.
     */
    public static List<Position> shortestPath(Position start, Position end, Set<Position> nodes, Neighbors neighbors, int width, int height) {
        if (!nodes.contains(start) || !nodes.contains(end)) {
            return List.of();
        }
        if (start.equals(end)) {
            return List.of(start);
        }
        Map<Position, Position> cameFrom = new HashMap<>();
        Set<Position> visited = new HashSet<>();
        Queue<Position> frontier = new ArrayDeque<>();
        frontier.add(start);
        visited.add(start);
        while (!frontier.isEmpty()) {
            Position current = frontier.poll();
            for (Position candidate : neighbors.of(current, width, height)) {
                if (!nodes.contains(candidate) || !visited.add(candidate)) {
                    continue;
                }
                cameFrom.put(candidate, current);
                if (candidate.equals(end)) {
                    return reconstructPath(cameFrom, start, end);
                }
                frontier.add(candidate);
            }
        }
        return List.of();
    }

    private static List<Position> reconstructPath(Map<Position, Position> cameFrom, Position start, Position end) {
        List<Position> path = new ArrayList<>();
        Position step = end;
        while (!step.equals(start)) {
            path.add(step);
            step = cameFrom.get(step);
        }
        path.add(start);
        Collections.reverse(path);
        return List.copyOf(path);
    }
}
