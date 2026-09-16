package com.tileboard.gamekit.pattern;

import com.tileboard.serial.board.Position;
import java.util.List;

/** A named sequence of board frames; each frame is a set/list of active positions. */
@FunctionalInterface
public interface MovementPattern {
    List<List<Position>> framesFor(int width, int height);
}
