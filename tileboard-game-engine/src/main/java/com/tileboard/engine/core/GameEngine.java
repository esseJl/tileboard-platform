package com.tileboard.engine.core;

import com.tileboard.engine.model.Player;

import java.util.List;
import java.util.Optional;

/**
 * The primary entry-point for application code.
 *
 * <pre>{@code
 * String sessionId = engine.startGame("color-match", List.of(Player.solo("Alice")));
 * engine.stopGame(sessionId);
 * engine.listGames();          // all registered games
 * engine.activeSession(sid);  // query a running session
 * }</pre>
 */
public interface GameEngine {

    /**
     * Starts a new session of the game identified by {@code gameId}.
     *
     * @return the new session id
     * @throws com.tileboard.engine.exception.GameNotFoundException if the game is not registered
     * @throws com.tileboard.engine.exception.GameSessionException  if a session cannot be created
     */
    String startGame(String gameId, List<Player> players);

    /**
     * Immediately stops the session identified by {@code sessionId}.
     *
     * @return
     */
    boolean stopGame(String sessionId);

    Optional<GameSession> activeSession(String sessionId);

    List<GameSession> activeSessions();

    /**
     * Returns the registry – used by Spring controllers to list available games.
     */
    GameRegistry registry();
}