package com.tileboard.engine.feature.anim.standby;

import com.tileboard.engine.feature.*;
import com.tileboard.engine.model.TileColor;
import com.tileboard.serial.board.Board;

public final class RandomTwinkleAnimation implements BoardAnimation {
    @Override
    public void run(AnimationSystem.RunToken token, AnimationContext ctx) {
        int width = ctx.width(), height = ctx.height();
        while (token.sleep(300)) {
            Board<TileColor> board = new Board<>(width, height, TileColor.OFF);
            int twinkles = 2 + ctx.rng().nextInt(3);
            for (int i = 0; i < twinkles; i++)
                board.set(ctx.rng().nextInt(height), ctx.rng().nextInt(width), TileColor.WHITE);
            token.show(board);
        }
    }
}