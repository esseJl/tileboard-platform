package com.tileboard.engine.core;

import com.tileboard.engine.model.TileEvent;
import com.tileboard.serial.board.Board;
import com.tileboard.serial.board.Position;

import java.util.Collection;
import java.util.function.Supplier;

public final class TouchFrameRouter {
    private final Supplier<Collection<GameSessionImpl>> sessionsSupplier;

    public TouchFrameRouter(Supplier<Collection<GameSessionImpl>> sessionsSupplier) {
        this.sessionsSupplier = sessionsSupplier;
    }

    public void route(Board<Boolean> touchBoard) {
        for (GameSessionImpl session : sessionsSupplier.get()) {
            int w = session.boardWidth();
            int h = session.boardHeight();
            for (Position pos : touchBoard.positionsWhere(Boolean.TRUE::equals)) {
                if (pos.row() < h && pos.col() < w) {
                    session.handleTileEvent(
                            TileEvent.touch(pos, session.sessionId()));
                }
            }
        }
    }
}
