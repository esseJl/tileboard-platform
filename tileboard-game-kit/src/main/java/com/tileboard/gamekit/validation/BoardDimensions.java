package com.tileboard.gamekit.validation;

public record BoardDimensions(int width, int height) {
    public BoardDimensions {
        if (width < 1 || height < 1) throw new IllegalArgumentException("width and height must be >= 1");
    }

    public int size() { return Math.multiplyExact(width, height); }
}
