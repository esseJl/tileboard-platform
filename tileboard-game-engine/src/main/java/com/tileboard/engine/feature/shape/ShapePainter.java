package com.tileboard.engine.feature.shape;

import com.tileboard.engine.model.TileColor;
import com.tileboard.serial.board.Board;
import com.tileboard.serial.board.Position;

public final class ShapePainter {
    private ShapePainter() {
    }

    public static void paint(Board<TileColor> board, int width, int height, ShapeSpec spec, Position center) {
        for (Position p : ShapeRasterizer.rasterize(spec, center)) {
            if (p.row() >= 0 && p.row() < height && p.col() >= 0 && p.col() < width) {
                board.set(p.row(), p.col(), spec.color());
            }
        }
    }
}