package com.tileboard.engine.feature;

import com.tileboard.engine.model.TileColor;
import com.tileboard.serial.board.Board;
import com.tileboard.serial.board.Position;

import java.util.Random;
import java.util.function.Consumer;

public final class BoardEffects {
    private BoardEffects() {
    }

    public static Board<TileColor> ringBoard(int width, int height, Position center, TileColor color, int radius) {
        Board<TileColor> board = new Board<>(width, height, TileColor.OFF);
        board.forEach((row, col, tile) -> {
            int dist = Math.max(Math.abs(row - center.row()), Math.abs(col - center.col()));
            if (dist == radius) board.set(row, col, color);
        });
        return board;
    }

    public static Board<TileColor> ringBandBoard(int width, int height, Position center, TileColor color, int outerRadius) {
        Board<TileColor> board = new Board<>(width, height, TileColor.OFF);
        board.forEach((row, col, tile) -> {
            int dist = Math.max(Math.abs(row - center.row()), Math.abs(col - center.col()));
            if (dist <= outerRadius && dist >= outerRadius - 1) board.set(row, col, color);
        });
        return board;
    }

    public static void clearBoard(int width, int height, Consumer<Board<TileColor>> publisher) {
        publisher.accept(new Board<>(width, height, TileColor.OFF));
    }

    public static int randomInterior(int size, Random rng) {
        return size > 2 ? 1 + rng.nextInt(size - 2) : rng.nextInt(size);
    }

    public static void paintCorners(Board<TileColor> board, int width, int height, TileColor color) {
        board.set(0, 0, color);
        if (width > 1) board.set(0, width - 1, color);
        if (height > 1) board.set(height - 1, 0, color);
        if (height > 1 && width > 1) board.set(height - 1, width - 1, color);
    }

    public static void paintBorder(Board<TileColor> board, int width, int height, TileColor color) {
        for (int col = 0; col < width; col++) {
            board.set(0, col, color);
            board.set(height - 1, col, color);
        }
        for (int row = 0; row < height; row++) {
            board.set(row, 0, color);
            board.set(row, width - 1, color);
        }
    }

    public static boolean inBounds(int width, int height, int r, int c) {
        return r >= 0 && r < height && c >= 0 && c < width;
    }

    public static boolean[][] digitPattern(int digit) {
        return switch (digit) {
            case 1 ->
                    new boolean[][]{{false, true, false}, {true, true, false}, {false, true, false}, {false, true, false}, {true, true, true}};
            case 2 ->
                    new boolean[][]{{true, true, true}, {false, false, true}, {true, true, true}, {true, false, false}, {true, true, true}};
            case 3 ->
                    new boolean[][]{{true, true, true}, {false, false, true}, {true, true, true}, {false, false, true}, {true, true, true}};
            default -> new boolean[5][3];
        };
    }

    public static Board<TileColor> renderDigit(int width, int height, int digit) {
        Board<TileColor> board = new Board<>(width, height, TileColor.OFF);
        boolean[][] pattern = digitPattern(digit);
        int startRow = (height - pattern.length) / 2;
        int startCol = (width - pattern[0].length) / 2;
        TileColor color = switch (digit) {
            case 3 -> TileColor.RED;
            case 2 -> TileColor.YELLOW;
            case 1 -> TileColor.GREEN;
            default -> TileColor.WHITE;
        };
        for (int r = 0; r < pattern.length; r++)
            for (int c = 0; c < pattern[0].length; c++)
                if (pattern[r][c] && inBounds(width, height, startRow + r, startCol + c))
                    board.set(startRow + r, startCol + c, color);
        return board;
    }
}