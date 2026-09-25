package com.tileboard.engine.feature.shape;

import com.tileboard.engine.feature.AnimationContext;
import com.tileboard.engine.feature.AnimationSystem;
import com.tileboard.engine.feature.BoardAnimation;
import com.tileboard.engine.model.TileColor;
import com.tileboard.serial.board.Board;
import com.tileboard.serial.board.Position;

import java.util.ArrayDeque;
import java.util.Deque;

public final class ShapeMotionAnimation implements BoardAnimation {
    private final ShapeMotionSpec spec;

    public ShapeMotionAnimation(ShapeMotionSpec spec) {
        this.spec = spec;
    }

    @Override
    public void run(AnimationSystem.RunToken token, AnimationContext ctx) {
        int width = ctx.width(), height = ctx.height();
        double distance = Math.max(1, spec.trajectory().lengthCells(Math.max(spec.frames(), 10)));
        long delay = spec.speed().frameDelayMs(distance, spec.frames());
        Deque<Position> trailPositions = new ArrayDeque<>();

        for (int cycle = 0; cycle < spec.repeat(); cycle++) {
            for (int f = 0; f <= spec.frames(); f++) {
                double t = (double) f / spec.frames();
                Position pos = spec.trajectory().at(t);

                Board<TileColor> board = new Board<>(width, height, TileColor.OFF);
                if (spec.trail()) {
                    trailPositions.addLast(pos);
                    if (trailPositions.size() > spec.trailLength()) trailPositions.removeFirst();
                    for (Position trailPos : trailPositions) {
                        ShapePainter.paint(board, width, height, spec.shape().withColor(spec.trailColor()), trailPos);
                    }
                }
                ShapePainter.paint(board, width, height, spec.shape(), pos);
                token.show(board);
                if (!token.sleep(delay)) return;
            }
        }
        if (spec.clearAtEnd()) token.clear();
    }
}