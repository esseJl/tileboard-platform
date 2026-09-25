package com.tileboard.engine.feature.anim.win;

import com.tileboard.engine.feature.*;
import com.tileboard.engine.model.TileColor;
import com.tileboard.serial.board.Board;

public final class SparkleAnimation implements BoardAnimation {
    private static final TileColor[] COLORS = {TileColor.YELLOW, TileColor.WHITE, TileColor.LIGHT_BLUE};

    @Override
    public void run(AnimationSystem.RunToken token, AnimationContext ctx) {
        int width = ctx.width(), height = ctx.height();
        for (int cycle = 0; cycle < 15; cycle++) {
            Board<TileColor> board = new Board<>(width, height, TileColor.OFF);
            int sparks = 5 + (cycle % 5);
            for (int i = 0; i < sparks; i++)
                board.set(ctx.rng().nextInt(height), ctx.rng().nextInt(width), COLORS[ctx.rng().nextInt(COLORS.length)]);
            token.show(board);
            if (!token.sleep(120)) return;
        }
        token.clear();
    }
}