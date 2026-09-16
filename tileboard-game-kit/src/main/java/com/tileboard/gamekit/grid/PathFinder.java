package com.tileboard.gamekit.grid;

import com.tileboard.gamekit.model.TilePosition;
import java.util.*;
import java.util.function.Predicate;

/** Breadth-first shortest path on a rectangular board. */
public final class PathFinder {
    private PathFinder() {}

    public static Optional<List<TilePosition>> shortestPath(
            TilePosition start, TilePosition goal, int width, int height,
            NeighborMode mode, Predicate<TilePosition> walkable) {
        Objects.requireNonNull(start); Objects.requireNonNull(goal);
        Objects.requireNonNull(mode); Objects.requireNonNull(walkable);
        if (start.equals(goal)) return Optional.of(List.of(start));
        Queue<TilePosition> queue = new ArrayDeque<>();
        Map<TilePosition, TilePosition> previous = new HashMap<>();
        queue.add(start); previous.put(start, null);

        while (!queue.isEmpty()) {
            TilePosition current = queue.remove();
            for (TilePosition next : Neighbors.of(current, width, height, mode)) {
                if (!walkable.test(next) || previous.containsKey(next)) continue;
                previous.put(next, current);
                if (next.equals(goal)) return Optional.of(reconstruct(previous, goal));
                queue.add(next);
            }
        }
        return Optional.empty();
    }

    private static List<TilePosition> reconstruct(Map<TilePosition, TilePosition> previous, TilePosition goal) {
        LinkedList<TilePosition> path = new LinkedList<>();
        for (TilePosition p = goal; p != null; p = previous.get(p)) path.addFirst(p);
        return List.copyOf(path);
    }
}
