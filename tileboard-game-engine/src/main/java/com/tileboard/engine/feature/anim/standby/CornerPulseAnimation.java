package com.tileboard.engine.feature.anim.standby;

import com.tileboard.engine.feature.*;
import com.tileboard.engine.model.TileColor;
import com.tileboard.serial.board.Board;

public final class CornerPulseAnimation implements BoardAnimation {
    private static final TileColor[] COLORS = {TileColor.GREEN, TileColor.BLUE, TileColor.PINK, TileColor.YELLOW};

    @Override
    public void run(AnimationSystem.RunToken token, AnimationContext ctx) {
        int width = ctx.width(), height = ctx.height();
        for (int cycle = 0; ; cycle = (cycle + 1) % COLORS.length) {
            Board<TileColor> board = new Board<>(width, height, TileColor.OFF);
            TileColor color = COLORS[cycle];
            int row = (cycle / 2) * (height - 1);
            int col = (cycle % 2) * (width - 1);
            for (int dr = -1; dr <= 1; dr++)
                for (int dc = -1; dc <= 1; dc++) {
                    int nr = row + dr, nc = col + dc;
                    if (nr >= 0 && nr < height && nc >= 0 && nc < width) board.set(nr, nc, color);
                }
            token.show(board);
            token.pause(300);
        }
    }
}