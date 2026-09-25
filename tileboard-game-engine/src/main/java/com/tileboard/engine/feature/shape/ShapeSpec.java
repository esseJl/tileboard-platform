package com.tileboard.engine.feature.shape;

import com.tileboard.engine.model.TileColor;

/**
 * Immutable geometric description of a shape (position-independent).
 */
public record ShapeSpec(ShapeType type, ShapeStyle style, int width, int height,
                        int thickness, TileColor color, boolean diagonal) {

    public static ShapeSpec square(int size, ShapeStyle style, TileColor color) {
        return new ShapeSpec(ShapeType.SQUARE, style, size, size, 1, color, false);
    }

    public static ShapeSpec rectangle(int w, int h, ShapeStyle style, TileColor color) {
        return new ShapeSpec(ShapeType.RECTANGLE, style, w, h, 1, color, false);
    }

    public static ShapeSpec diamond(int radius, ShapeStyle style, TileColor color) {
        return new ShapeSpec(ShapeType.DIAMOND, style, radius, radius, 1, color, false);
    }

    public static ShapeSpec circle(int radius, ShapeStyle style, TileColor color) {
        return new ShapeSpec(ShapeType.CIRCLE, style, radius, radius, 1, color, false);
    }

    public static ShapeSpec lineHorizontal(int length, TileColor color) {
        return new ShapeSpec(ShapeType.LINE, ShapeStyle.FILLED, length, 1, 1, color, false);
    }

    public static ShapeSpec lineVertical(int length, TileColor color) {
        return new ShapeSpec(ShapeType.LINE, ShapeStyle.FILLED, 1, length, 1, color, false);
    }

    public static ShapeSpec lineDiagonal(int length, TileColor color) {
        return new ShapeSpec(ShapeType.LINE, ShapeStyle.FILLED, length, length, 1, color, true);
    }

    public ShapeSpec withThickness(int t) {
        return new ShapeSpec(type, style, width, height, t, color, diagonal);
    }

    public ShapeSpec withColor(TileColor c) {
        return new ShapeSpec(type, style, width, height, thickness, c, diagonal);
    }
}