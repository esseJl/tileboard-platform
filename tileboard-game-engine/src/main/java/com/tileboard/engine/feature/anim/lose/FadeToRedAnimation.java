package com.tileboard.engine.feature.anim.lose;

import com.tileboard.engine.feature.*;
import com.tileboard.engine.model.TileColor;
import com.tileboard.serial.board.Board;

public final class FadeToRedAnimation implements BoardAnimation {
    @Override
    public void run(AnimationSystem.RunToken token, AnimationContext ctx) {
        Board<TileColor> board = new Board<>(ctx.width(), ctx.height(), TileColor.OFF);
        for (int phase = 0; phase < 3; phase++) {
            for (int row = 0; row < ctx.height(); row++)
                for (int col = 0; col < ctx.width(); col++)
                    if (ctx.rng().nextDouble() < 0.3) board.set(row, col, TileColor.RED);
            token.show(board.copy());
            if (!token.sleep(300)) return;
        }
        board.fill(TileColor.RED);
        token.show(board);
        if (token.sleep(1000)) token.clear();
    }
}