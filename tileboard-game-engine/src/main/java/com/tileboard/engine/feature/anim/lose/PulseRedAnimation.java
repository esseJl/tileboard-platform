package com.tileboard.engine.feature.anim.lose;

import com.tileboard.engine.feature.*;
import com.tileboard.engine.model.TileColor;
import com.tileboard.serial.board.Board;

public final class PulseRedAnimation implements BoardAnimation {
    @Override
    public void run(AnimationSystem.RunToken token, AnimationContext ctx) {
        for (int pulse = 0; pulse < 4; pulse++) {
            token.show(new Board<>(ctx.width(), ctx.height(), TileColor.RED));
            token.pause(200);
            BoardEffects.clearBoard(ctx.width(), ctx.height(), ctx.publisher());
            token.pause(200);
        }
    }
}