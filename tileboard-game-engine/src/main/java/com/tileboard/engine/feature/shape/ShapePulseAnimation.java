package com.tileboard.engine.feature.shape;

import com.tileboard.engine.feature.AnimationContext;
import com.tileboard.engine.feature.AnimationSystem;
import com.tileboard.engine.feature.BoardAnimation;
import com.tileboard.engine.model.TileColor;
import com.tileboard.serial.board.Board;
import com.tileboard.serial.board.Position;

/**
 * Grows/shrinks a shape's radius at a fixed position — ripples, combo pulses, danger warnings.
 */
public final class ShapePulseAnimation implements BoardAnimation {
    private final Position center;
    private final ShapeType type;
    private final ShapeStyle style;
    private final TileColor color;
    private final int minRadius, maxRadius, cycles;
    private final long frameDelayMs;
    private final boolean pingPong;

    public ShapePulseAnimation(Position center, ShapeType type, ShapeStyle style, TileColor color,
                               int minRadius, int maxRadius, int cycles, long frameDelayMs, boolean pingPong) {
        this.center = center;
        this.type = type;
        this.style = style;
        this.color = color;
        this.minRadius = minRadius;
        this.maxRadius = maxRadius;
        this.cycles = cycles;
        this.frameDelayMs = frameDelayMs;
        this.pingPong = pingPong;
    }

    @Override
    public void run(AnimationSystem.RunToken token, AnimationContext ctx) {
        int width = ctx.width(), height = ctx.height();
        for (int cycle = 0; cycle < cycles; cycle++) {
            for (int r = minRadius; r <= maxRadius; r++) {
                paintRadius(token, width, height, r);
                if (!token.sleep(frameDelayMs)) return;
            }
            if (pingPong) {
                for (int r = maxRadius; r >= minRadius; r--) {
                    paintRadius(token, width, height, r);
                    if (!token.sleep(frameDelayMs)) return;
                }
            }
        }
        token.clear();
    }

    private void paintRadius(AnimationSystem.RunToken token, int width, int height, int radius) {
        ShapeSpec spec = new ShapeSpec(type, style, radius * 2 + 1, radius * 2 + 1, 1, color, false);
        Board<TileColor> board = new Board<>(width, height, TileColor.OFF);
        ShapePainter.paint(board, width, height, spec, center);
        token.show(board);
    }
}