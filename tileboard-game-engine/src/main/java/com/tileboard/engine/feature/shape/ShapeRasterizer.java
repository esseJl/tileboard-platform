package com.tileboard.engine.feature.shape;

import com.tileboard.serial.board.Position;

import java.util.HashSet;
import java.util.Set;

public final class ShapeRasterizer {
    private ShapeRasterizer() {
    }

    public static Set<Position> rasterize(ShapeSpec spec, Position center) {
        return switch (spec.type()) {
            case SQUARE, RECTANGLE -> rasterizeRect(spec, center);
            case DIAMOND -> rasterizeDiamond(spec, center);
            case CIRCLE -> rasterizeCircle(spec, center);
            case LINE -> rasterizeLine(spec, center);
        };
    }

    private static Set<Position> rasterizeRect(ShapeSpec spec, Position center) {
        Set<Position> cells = new HashSet<>();
        int halfW = spec.width() / 2, halfH = spec.height() / 2;
        for (int dr = -halfH; dr <= halfH; dr++) {
            for (int dc = -halfW; dc <= halfW; dc++) {
                boolean border = Math.abs(dr) >= halfH - (spec.thickness() - 1)
                        || Math.abs(dc) >= halfW - (spec.thickness() - 1);
                if (spec.style() == ShapeStyle.FILLED || border) {
                    cells.add(new Position(center.row() + dr, center.col() + dc));
                }
            }
        }
        return cells;
    }

    private static Set<Position> rasterizeDiamond(ShapeSpec spec, Position center) {
        Set<Position> cells = new HashSet<>();
        int radius = Math.max(spec.width(), spec.height());
        for (int dr = -radius; dr <= radius; dr++) {
            for (int dc = -radius; dc <= radius; dc++) {
                int dist = Math.abs(dr) + Math.abs(dc);
                boolean onRing = dist <= radius && dist > radius - spec.thickness();
                if (spec.style() == ShapeStyle.FILLED ? dist <= radius : onRing) {
                    cells.add(new Position(center.row() + dr, center.col() + dc));
                }
            }
        }
        return cells;
    }

    private static Set<Position> rasterizeCircle(ShapeSpec spec, Position center) {
        Set<Position> cells = new HashSet<>();
        int radius = Math.max(spec.width(), spec.height());
        double outer = radius + 0.5, inner = radius - spec.thickness() + 0.5;
        for (int dr = -radius; dr <= radius; dr++) {
            for (int dc = -radius; dc <= radius; dc++) {
                double dist = Math.sqrt(dr * dr + dc * dc);
                boolean include = spec.style() == ShapeStyle.FILLED ? dist <= outer : (dist <= outer && dist > inner);
                if (include) cells.add(new Position(center.row() + dr, center.col() + dc));
            }
        }
        return cells;
    }

    private static Set<Position> rasterizeLine(ShapeSpec spec, Position center) {
        Set<Position> cells = new HashSet<>();
        if (spec.diagonal()) {
            int len = Math.max(spec.width(), spec.height());
            int half = len / 2;
            for (int i = -half; i <= half; i++) cells.add(new Position(center.row() + i, center.col() + i));
        } else if (spec.width() >= spec.height()) {
            int half = spec.width() / 2;
            for (int i = -half; i <= half; i++)
                for (int t = 0; t < spec.thickness(); t++)
                    cells.add(new Position(center.row() + t, center.col() + i));
        } else {
            int half = spec.height() / 2;
            for (int i = -half; i <= half; i++)
                for (int t = 0; t < spec.thickness(); t++)
                    cells.add(new Position(center.row() + i, center.col() + t));
        }
        return cells;
    }
}