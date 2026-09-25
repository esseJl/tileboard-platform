package com.tileboard.engine.feature.anim.lose;

import com.tileboard.engine.feature.*;
import com.tileboard.engine.model.TileColor;
import com.tileboard.serial.board.Board;

public final class DescendingCurtainAnimation implements BoardAnimation {
    @Override
    public void run(AnimationSystem.RunToken token, AnimationContext ctx) {
        int width = ctx.width(), height = ctx.height();
        for (int row = 0; row < height; row++) {
            Board<TileColor> board = new Board<>(width, height, TileColor.OFF);
            for (int r = 0; r <= row; r++)
                for (int col = 0; col < width; col++)
                    board.set(r, col, TileColor.RED);
            token.show(board);
            token.pause(200);
        }
        token.pause(500);
        BoardEffects.clearBoard(width, height, ctx.publisher());
    }
}