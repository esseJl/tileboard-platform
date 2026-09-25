package com.tileboard.engine.feature.anim.countdown;

import com.tileboard.engine.feature.*;
import com.tileboard.engine.model.TileColor;
import com.tileboard.serial.board.Board;

public final class SimpleCountdownAnimation implements BoardAnimation {
    @Override
    public void run(AnimationSystem.RunToken token, AnimationContext ctx) {
        long digitDurationMs = ctx.param("digitDurationMs", 1000L);
        TileColor[] colors = {TileColor.RED, TileColor.BLUE, TileColor.GREEN};
        for (int i = 3; i > 0; i--) {
            token.show(new Board<>(ctx.width(), ctx.height(), colors[3 - i]));
            if (!token.sleep(digitDurationMs)) return;
        }
        token.clear();
    }
}