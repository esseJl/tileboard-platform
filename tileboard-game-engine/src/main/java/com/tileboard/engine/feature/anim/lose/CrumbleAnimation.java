package com.tileboard.engine.feature.anim.lose;

import com.tileboard.engine.feature.*;
import com.tileboard.engine.model.TileColor;
import com.tileboard.serial.board.Board;
import com.tileboard.serial.board.Position;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CrumbleAnimation implements BoardAnimation {
    @Override
    public void run(AnimationSystem.RunToken token, AnimationContext ctx) {
        int width = ctx.width(), height = ctx.height();
        Board<TileColor> board = new Board<>(width, height, TileColor.YELLOW);
        token.show(board.copy());
        token.pause(300);

        List<Position> positions = new ArrayList<>();
        for (int row = 0; row < height; row++)
            for (int col = 0; col < width; col++)
                positions.add(new Position(row, col));
        Collections.shuffle(positions, ctx.rng());

        for (Position pos : positions) {
            board.set(pos.row(), pos.col(), TileColor.RED);
            if (ctx.rng().nextDouble() < 0.2) {
                token.show(board.copy());
                token.pause(50);
            }
        }
        token.show(board.copy());
        token.pause(500);
        BoardEffects.clearBoard(width, height, ctx.publisher());
    }
}