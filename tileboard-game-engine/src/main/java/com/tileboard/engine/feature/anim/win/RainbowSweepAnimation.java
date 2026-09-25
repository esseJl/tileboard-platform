package com.tileboard.engine.feature.anim.win;

import com.tileboard.engine.feature.*;
import com.tileboard.engine.model.TileColor;
import com.tileboard.serial.board.Board;

public final class RainbowSweepAnimation implements BoardAnimation {
    private static final TileColor[] RAINBOW = {TileColor.RED, TileColor.YELLOW, TileColor.GREEN, TileColor.BLUE, TileColor.PINK};

    @Override
    public void run(AnimationSystem.RunToken token, AnimationContext ctx) {
        int width = ctx.width(), height = ctx.height();
        for (int sweep = 0; sweep < 2; sweep++) {
            for (int col = 0; col < width; col++) {
                Board<TileColor> board = new Board<>(width, height, TileColor.OFF);
                for (int c = 0; c <= col; c++) {
                    TileColor color = RAINBOW[(c + sweep * width) % RAINBOW.length];
                    for (int row = 0; row < height; row++) board.set(row, c, color);
                }
                token.show(board);
                token.pause(80);
            }
        }
        BoardEffects.clearBoard(width, height, ctx.publisher());
    }
}