package com.tileboard.engine.core;

import java.util.Optional;

/**
 * A running (or finished) instance of a {@link Game}. Returned by
 * {@link GameEngine#startGame} and usable to query status and result.
 */
public interface GameSession {

    String sessionId();
    String gameId();
    GameStatus status();

    /** Available only after the session finishes. */
    Optional<GameResult> result();

    /** Immediately stops this session if it is still running. */
    void stop();
}