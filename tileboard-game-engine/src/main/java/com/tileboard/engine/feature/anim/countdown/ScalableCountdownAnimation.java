package com.tileboard.engine.feature.anim.countdown;

import com.tileboard.engine.feature.*;
import com.tileboard.engine.model.TileColor;
import com.tileboard.serial.board.Board;

import java.util.Map;

public final class ScalableCountdownAnimation implements BoardAnimation {
    
    private static final Map<Integer, int[][]> FONT = Map.of(
            1, new int[][]{
                    {0, 1, 0},
                    {1, 1, 0},
                    {0, 1, 0},
                    {0, 1, 0},
                    {1, 1, 1}
            },
            2, new int[][]{
                    {1, 1, 1},
                    {0, 0, 1},
                    {1, 1, 1},
                    {1, 0, 0},
                    {1, 1, 1}
            },
            3, new int[][]{
                    {1, 1, 1},
                    {0, 0, 1},
                    {1, 1, 1},
                    {0, 0, 1},
                    {1, 1, 1}
            }
    );

    private static final Map<Integer, TileColor> DIGIT_COLORS = Map.of(
            3, TileColor.RED,
            2, TileColor.BLUE,
            1, TileColor.GREEN
    );

    private static Board<TileColor> renderDigit(int width, int height, int digit, TileColor color) {
        int[][] pattern = FONT.get(digit);
        if (pattern == null) {
            throw new IllegalArgumentException("No font glyph for digit: " + digit);
        }

        int patternRows = pattern.length;      // 5
        int patternCols = pattern[0].length;   // 3

        int scale = Math.max(1, Math.min(width / patternCols, height / patternRows));

        int renderWidth = patternCols * scale;
        int renderHeight = patternRows * scale;
        int offsetX = Math.max(0, (width - renderWidth) / 2);
        int offsetY = Math.max(0, (height - renderHeight) / 2);

        Board<TileColor> board = new Board<>(width, height, TileColor.OFF);
        for (int r = 0; r < patternRows; r++) {
            for (int c = 0; c < patternCols; c++) {
                if (pattern[r][c] == 0) continue;
                for (int dy = 0; dy < scale; dy++) {
                    int y = offsetY + r * scale + dy;
                    if (y < 0 || y >= height) continue;
                    for (int dx = 0; dx < scale; dx++) {
                        int x = offsetX + c * scale + dx;
                        if (x < 0 || x >= width) continue;
                        board.set(x, y, color);
                    }
                }
            }
        }
        return board;
    }

    @Override
    public void run(AnimationSystem.RunToken token, AnimationContext ctx) {
        long digitDurationMs = ctx.param("digitDurationMs", 1000L);
        for (int digit = 3; digit >= 1; digit--) {
            token.show(renderDigit(ctx.width(), ctx.height(), digit, DIGIT_COLORS.get(digit)));
            if (!token.sleep(digitDurationMs)) return;
        }
        for (int i = 0; i < 3; i++) {
            token.show(new Board<>(ctx.width(), ctx.height(), TileColor.GREEN));
            if (!token.sleep(150)) return;
            token.clear();
            if (!token.sleep(150)) return;
        }
    }
}