package com.tileboard.engine.feature.anim.win;

import com.tileboard.engine.feature.*;
import com.tileboard.engine.model.TileColor;
import com.tileboard.serial.board.Position;

public final class FireworksAnimation implements BoardAnimation {
    private static final TileColor[] COLORS =
            {TileColor.RED, TileColor.YELLOW, TileColor.GREEN, TileColor.BLUE, TileColor.PINK, TileColor.LIGHT_BLUE};

    @Override
    public void run(AnimationSystem.RunToken token, AnimationContext ctx) {
        int width = ctx.width(), height = ctx.height();
        for (int firework = 0; firework < 3; firework++) {
            Position center = new Position(BoardEffects.randomInterior(height, ctx.rng()), BoardEffects.randomInterior(width, ctx.rng()));
            TileColor color = COLORS[ctx.rng().nextInt(COLORS.length)];
            for (int radius = 0; radius <= 3; radius++) {
                token.show(BoardEffects.ringBoard(width, height, center, color, radius));
                token.pause(100);
            }
            token.pause(200);
        }
        token.clear();
    }
}