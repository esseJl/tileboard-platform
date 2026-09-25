package com.tileboard.engine.feature.anim.standby;

import com.tileboard.engine.feature.*;
import com.tileboard.engine.model.TileColor;
import com.tileboard.serial.board.Board;

public final class BreathingAnimation implements BoardAnimation {
    private static final TileColor[] COLORS = {TileColor.BLUE, TileColor.LIGHT_BLUE};

    @Override
    public void run(AnimationSystem.RunToken token, AnimationContext ctx) {
        int width = ctx.width(), height = ctx.height();
        for (int cycle = 0; !token.isCancelled(); cycle++) {
            Board<TileColor> board = new Board<>(width, height, TileColor.OFF);
            TileColor color = COLORS[cycle % 2];
            BoardEffects.paintCorners(board, width, height, color);
            ctx.publisher().accept(board);
            if (!token.sleep(500)) return;

            if (width >= 3 && height >= 3) {
                BoardEffects.paintBorder(board, width, height, color);
                ctx.publisher().accept(board);
                if (!token.sleep(500)) return;
            }
        }
    }
}