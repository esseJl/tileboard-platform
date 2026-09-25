package com.tileboard.engine.feature.anim.countdown;

import com.tileboard.engine.feature.*;
import com.tileboard.engine.model.TileColor;
import com.tileboard.serial.board.Board;

public final class ScalableCountdownAnimation implements BoardAnimation {
    @Override
    public void run(AnimationSystem.RunToken token, AnimationContext ctx) {
        long digitDurationMs = ctx.param("digitDurationMs", 1000L);
        for (int digit = 3; digit >= 1; digit--) {
            token.show(BoardEffects.renderDigit(ctx.width(), ctx.height(), digit));
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