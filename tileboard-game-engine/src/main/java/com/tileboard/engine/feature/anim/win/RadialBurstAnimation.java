package com.tileboard.engine.feature.anim.win;

import com.tileboard.engine.feature.*;
import com.tileboard.engine.model.TileColor;
import com.tileboard.serial.board.Position;

public final class RadialBurstAnimation implements BoardAnimation {
    private static final TileColor[] COLORS =
            {TileColor.YELLOW, TileColor.GREEN, TileColor.BLUE, TileColor.PINK, TileColor.LIGHT_BLUE};

    @Override
    public void run(AnimationSystem.RunToken token, AnimationContext ctx) {
        int width = ctx.width(), height = ctx.height();
        Position center = new Position(height / 2, width / 2);
        int maxRadius = Math.max(Math.max(center.row(), height - 1 - center.row()),
                Math.max(center.col(), width - 1 - center.col())) + 2;

        for (int radius = 0; radius <= maxRadius; radius++) {
            token.show(BoardEffects.ringBandBoard(width, height, center, COLORS[radius % COLORS.length], radius));
            token.pause(100);
        }
        token.pause(500);
        token.clear();
    }
}