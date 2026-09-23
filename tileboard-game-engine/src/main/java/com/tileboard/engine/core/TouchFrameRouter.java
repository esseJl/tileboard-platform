package com.tileboard.engine.core;

import com.tileboard.engine.model.TileEvent;
import com.tileboard.serial.board.Board;
import com.tileboard.serial.board.Position;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

public final class TouchFrameRouter {

    private final Function<String, Optional<GameSessionImpl>> sessionLookup;
    private final Supplier<Optional<String>> exclusiveOwnerSupplier;

    public TouchFrameRouter(Function<String, Optional<GameSessionImpl>> sessionLookup,
                            Supplier<Optional<String>> exclusiveOwnerSupplier) {
        this.sessionLookup = sessionLookup;
        this.exclusiveOwnerSupplier = exclusiveOwnerSupplier;
    }

    public void route(Board<Boolean> touchBoard) {
        exclusiveOwnerSupplier.get()
                .flatMap(sessionLookup)
                .ifPresent(session -> deliver(session, touchBoard));
    }

    private void deliver(GameSessionImpl session, Board<Boolean> touchBoard) {
        int w = session.boardWidth();
        int h = session.boardHeight();
        for (Position pos : touchBoard.positionsWhere(Boolean.TRUE::equals)) {
            if (pos.row() < h && pos.col() < w) {
                session.handleTileEvent(TileEvent.touch(pos, session.sessionId()));
            }
        }
        for (Position pos : touchBoard.positionsWhere(Boolean.FALSE::equals)) {
            if (pos.row() < h && pos.col() < w) {
                session.handleTileEvent(TileEvent.release(pos, session.sessionId()));
            }
        }
    }
}