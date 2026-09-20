package com.tileboard.engine.core;

import com.tileboard.engine.model.TileEvent;
import com.tileboard.serial.board.Board;
import com.tileboard.serial.board.Position;

import java.util.Collection;
import java.util.Optional;
import java.util.function.Supplier;

public final class TouchFrameRouter {
    private final Supplier<Collection<GameSessionImpl>> sessionsSupplier;
    private final Supplier<Optional<String>> exclusiveOwnerSupplier;

    public TouchFrameRouter(Supplier<Collection<GameSessionImpl>> sessionsSupplier, Supplier<Optional<String>> exclusiveOwnerSupplier) {
        this.sessionsSupplier = sessionsSupplier;
        this.exclusiveOwnerSupplier = exclusiveOwnerSupplier;
    }

    public void route(Board<Boolean> touchBoard) {
        String ownerId = exclusiveOwnerSupplier.get().orElse(null);
        if (ownerId == null) return; // no session owns the hardware right now — ignore stray touches

        for (GameSessionImpl session : sessionsSupplier.get()) {
            if (!session.sessionId().equals(ownerId)) continue; // never cross-deliver touches
            int w = session.boardWidth();
            int h = session.boardHeight();
            for (Position pos : touchBoard.positionsWhere(Boolean.TRUE::equals)) {
                if (pos.row() < h && pos.col() < w) {
                    session.handleTileEvent(TileEvent.touch(pos, session.sessionId()));
                }
            }
        }
    }
}
