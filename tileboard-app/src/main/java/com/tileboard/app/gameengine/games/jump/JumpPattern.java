package com.tileboard.app.gameengine.games.jump;

import com.tileboard.serial.board.Position;

import java.util.List;

/**
 * A shape of "band" that sweeps across the board in {@link JumpGame} - a
 * row, a column, a diagonal, or anything else that can be expressed as a
 * sequence of frames. Precomputing the whole sweep up front (rather than
 * deriving "what lights up at step N" on every tick) keeps {@link JumpGame}
 * itself completely ignorant of geometry: it only ever bounces an index
 * back and forth through {@link #framesFor(int, int)}'s result.
 *
 * <p>This is the entire extension point for adding a new sweep shape - see
 * {@link JumpPatterns} for the built-in ones. A brand-new shape is a single
 * static method returning a lambda; {@link JumpGame}, {@code JumpTuning} and
 * the REST layer never need to change.
 */
@FunctionalInterface
public interface JumpPattern {

    /**
     * The full back-and-forth-free sweep for a board of the given size, as
     * an ordered list of frames (one frame = the set of positions lit at
     * that point in the sweep). Never empty, and no frame is empty, for any
     * {@code width, height >= 1}.
     */
    List<List<Position>> framesFor(int width, int height);
}
