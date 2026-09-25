package com.tileboard.engine.feature.shape;

import com.tileboard.engine.feature.AnimationContext;
import com.tileboard.engine.feature.AnimationSystem;
import com.tileboard.engine.feature.BoardAnimation;
import com.tileboard.engine.model.TileColor;
import com.tileboard.serial.board.Board;

import java.util.ArrayList;
import java.util.List;

/**
 * Drives several {@link ShapeMotionSpec} entities on one shared frame clock, so a jump game
 * can animate player + obstacles + effects together inside the single AnimationSystem slot.
 */
public final class CompositeShapeAnimation implements BoardAnimation {

    private final List<Entity> entities = new ArrayList<>();
    private final long totalDurationMs;
    private final int fps;
    public CompositeShapeAnimation(long totalDurationMs, int fps) {
        this.totalDurationMs = totalDurationMs;
        this.fps = fps;
    }

    public CompositeShapeAnimation add(ShapeMotionSpec spec) {
        return add(spec, 0);
    }

    public CompositeShapeAnimation add(ShapeMotionSpec spec, long startDelayMs) {
        double distance = Math.max(1, spec.trajectory().lengthCells(spec.frames()));
        double duration = spec.frames() * spec.speed().frameDelayMs(distance, spec.frames());
        entities.add(new Entity(spec, startDelayMs, duration));
        return this;
    }

    @Override
    public void run(AnimationSystem.RunToken token, AnimationContext ctx) {
        int width = ctx.width(), height = ctx.height();
        long frameDelay = 1000L / fps;
        long elapsed = 0;

        while (elapsed <= totalDurationMs) {
            Board<TileColor> board = new Board<>(width, height, TileColor.OFF);
            for (Entity e : entities) {
                long local = elapsed - e.startDelayMs();
                if (local < 0) continue;
                double t = e.durationMs() <= 0 ? 1 : Math.min(1.0, local / e.durationMs());
                ShapePainter.paint(board, width, height, e.spec().shape(), e.spec().trajectory().at(t));
            }
            token.show(board);
            if (!token.sleep(frameDelay)) return;
            elapsed += frameDelay;
        }
        token.clear();
    }

    public record Entity(ShapeMotionSpec spec, long startDelayMs, double durationMs) {
    }
}