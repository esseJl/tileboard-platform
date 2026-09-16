package com.tileboard.gamekit.grid;

import com.tileboard.gamekit.model.TilePosition;
import java.util.*;
import java.util.function.Predicate;

/** Finds connected tile groups, useful for flood-fill, territory and puzzle games. */
public final class ConnectedComponents {
    private ConnectedComponents() {}

    public static List<Set<TilePosition>> find(int width, int height,
                                                NeighborMode mode,
                                                Predicate<TilePosition> included) {
        Objects.requireNonNull(mode); Objects.requireNonNull(included);
        Set<TilePosition> remaining = new HashSet<>();
        for (int r = 0; r < height; r++) for (int c = 0; c < width; c++) {
            TilePosition p = new TilePosition(r, c);
            if (included.test(p)) remaining.add(p);
        }

        List<Set<TilePosition>> result = new ArrayList<>();
        while (!remaining.isEmpty()) {
            TilePosition seed = remaining.iterator().next();
            Set<TilePosition> component = new HashSet<>();
            ArrayDeque<TilePosition> queue = new ArrayDeque<>();
            queue.add(seed); remaining.remove(seed);
            while (!queue.isEmpty()) {
                TilePosition current = queue.remove();
                component.add(current);
                for (TilePosition n : Neighbors.of(current, width, height, mode)) {
                    if (remaining.remove(n)) queue.add(n);
                }
            }
            result.add(Set.copyOf(component));
        }
        return List.copyOf(result);
    }
}
