package com.tileboard.gamekit.pattern;

import com.tileboard.serial.board.Position;

import java.util.List;

/**
 * The built-in "movement pattern" / "path" capability: any sequence of
 * board frames a game can step through over time - a sweeping band, a
 * moving target, a snake's body, a rhythm-game marker travelling along a
 * fixed route. A pure function of board size, so a single constant
 * instance (see {@link Patterns}) is reused across every session and board
 * size without being re-created per game.
 *
 * <p>This is the entire extension point for adding a new movement shape: a
 * static method returning a lambda. Any game stepping an index back and
 * forth (or forward-only, or looping) through {@link #framesFor(int, int)}'s
 * result never needs to know the shape's geometry.
 *
 * @see Patterns
 */
@FunctionalInterface
public interface MovementPattern {

    /**
     * The full sequence of frames for a board of the given size, as an
     * ordered list (one frame = the set of positions active at that point
     * in the sequence). Never empty, and no frame is empty, for any
     * {@code width, height >= 1}.
     */
    List<List<Position>> framesFor(int width, int height);
}
