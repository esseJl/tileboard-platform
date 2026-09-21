package com.tileboard.engine.feature;

import com.tileboard.engine.model.TileColor;
import com.tileboard.serial.board.Board;

import java.util.function.Consumer;

@FunctionalInterface
public interface BoardAnimation {
    /**
     * Runs until {@code token.sleep(...)} returns false (cancelled) or the effect completes naturally.
     */
    void run(AnimationSystem.RunToken token, int width, int height, Consumer<Board<TileColor>> publisher);
}
