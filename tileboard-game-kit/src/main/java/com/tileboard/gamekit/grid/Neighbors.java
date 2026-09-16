package com.tileboard.gamekit.grid;

import com.tileboard.gamekit.model.TilePosition;
import java.util.ArrayList;
import java.util.List;

/** Board-neighbour calculations independent of any particular game. */
public final class Neighbors {
    private Neighbors() {}

    public static List<TilePosition> of(TilePosition p, int width, int height, NeighborMode mode) {
        if (width < 1 || height < 1) throw new IllegalArgumentException("invalid board dimensions");
        List<TilePosition> result = new ArrayList<>();
        int[][] dirs = mode == NeighborMode.ORTHOGONAL
                ? new int[][]{{-1,0},{1,0},{0,-1},{0,1}}
                : new int[][]{{-1,0},{1,0},{0,-1},{0,1},{-1,-1},{-1,1},{1,-1},{1,1}};
        for (int[] d : dirs) {
            int r = p.row() + d[0], c = p.column() + d[1];
            if (r >= 0 && r < height && c >= 0 && c < width) result.add(new TilePosition(r, c));
        }
        return List.copyOf(result);
    }
}
