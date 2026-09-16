package com.tileboard.app.gameengine.example;

import com.tileboard.app.gameengine.Game;
import com.tileboard.app.gameengine.GameContext;
import com.tileboard.app.gameengine.GameDefinition;
import com.tileboard.serial.board.Board;
import com.tileboard.serial.board.TileCodec;

/**
 * Minimal reference {@link Game}: whatever tile the player touches lights
 * up and stays lit until touched again (the board just mirrors whatever
 * {@code DATA_IN} reports). It exists purely to exercise the full pipeline
 * end to end - real games (score tracking, timers, win/lose conditions,
 * board patterns) belong in their own module built the same way, once
 * that scope is picked back up.
 */
final class TouchEchoGame implements Game<Boolean> {

    private final GameDefinition definition;
    private final int width;
    private final int height;
    private volatile GameContext<Boolean> context;

    TouchEchoGame(GameDefinition definition, int width, int height) {
        this.definition = definition;
        this.width = width;
        this.height = height;
    }

    @Override
    public GameDefinition definition() {
        return definition;
    }

    @Override
    public TileCodec<Boolean> tileCodec() {
        return TileCodec.booleanState();
    }

    @Override
    public void start(GameContext<Boolean> context) {
        this.context = context;
        context.publish(new Board<>(width, height, false));
    }

    @Override
    public void onPlayerInput(Board<Boolean> touchedTiles) {
        // The touch board and the display board share the same shape here,
        // so whatever was reported as touched is mirrored straight back.
        GameContext<Boolean> currentContext = context;
        if (currentContext != null) {
            currentContext.publish(touchedTiles);
        }
    }

    @Override
    public void stop() {
        context = null;
    }
}
