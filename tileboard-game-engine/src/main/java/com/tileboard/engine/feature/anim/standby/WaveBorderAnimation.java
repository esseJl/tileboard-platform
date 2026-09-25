package com.tileboard.engine.feature.anim.standby;

import com.tileboard.engine.feature.*;
import com.tileboard.engine.model.TileColor;
import com.tileboard.serial.board.Board;

public final class WaveBorderAnimation implements BoardAnimation {
    @Override
    public void run(AnimationSystem.RunToken token, AnimationContext ctx) {
        int width = ctx.width(), height = ctx.height();
        TileColor color = TileColor.LIGHT_BLUE;
        for (int offset = 0; ; offset = (offset + 1) % 3) {
            Board<TileColor> board = new Board<>(width, height, TileColor.OFF);
            for (int col = 0; col < width; col++)
                if ((col + offset) % 3 == 0) board.set(0, col, color);
            if (height > 1)
                for (int col = 0; col < width; col++)
                    if ((col + offset + 1) % 3 == 0) board.set(height - 1, col, color);
            for (int row = 0; row < height; row++)
                if ((row + offset) % 3 == 0) board.set(row, 0, color);
            if (width > 1)
                for (int row = 0; row < height; row++)
                    if ((row + offset + 1) % 3 == 0) board.set(row, width - 1, color);
            token.show(board);
            token.pause(200);
        }
    }
}