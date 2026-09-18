package com.tileboard.engine.core;

import com.tileboard.engine.codec.EngineFrameRouter;
import com.tileboard.engine.event.GameEventBus;
import com.tileboard.engine.model.Player;
import com.tileboard.engine.model.TileEvent;
import com.tileboard.serial.board.Board;
import com.tileboard.serial.board.Position;
import com.tileboard.serial.gateway.TileGatewayClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe implementation of {@link GameEngine}.
 */
public final class GameEngineImpl implements GameEngine {

    private static final Logger log = LoggerFactory.getLogger(GameEngineImpl.class);

    private final GameRegistry        registry;
    private final TileGatewayClient   gateway;
    private final GameEventBus        eventBus;
    private final Duration            tickInterval;

    private final Map<String, GameSessionImpl> activeSessions = new ConcurrentHashMap<>();

    public GameEngineImpl(
            GameRegistry registry,
            TileGatewayClient gateway,
            GameEventBus eventBus,
            Duration tickInterval,
            int boardWidth,
            int boardHeight
    ) {
        this.registry     = Objects.requireNonNull(registry);
        this.gateway      = Objects.requireNonNull(gateway);
        this.eventBus     = Objects.requireNonNull(eventBus);
        this.tickInterval = tickInterval != null ? tickInterval : Duration.ofMillis(100);

        // Register the DATA_IN router with the *actual* connected board's dimensions
        gateway.addFrameListener(new EngineFrameRouter(boardWidth, boardHeight, this::routeTouchFrame));
    }

    @Override
    public String startGame(String gameId, List<Player> players) {
        Game game        = registry.instantiate(gameId);
        String sessionId = UUID.randomUUID().toString();

        GameSessionImpl session = new GameSessionImpl(
                sessionId, game, players, gateway, tickInterval, eventBus);

        activeSessions.put(sessionId, session);
        session.start();

        // Cleanup when the session finishes — unsubscribe must be captured and invoked,
        // otherwise the listener leaks on the shared event bus for the engine's lifetime.
        Runnable[] unsubscribeRef = new Runnable[1];
        unsubscribeRef[0] = eventBus.subscribe(event -> {
            if (event.sessionId().equals(sessionId) &&
                    (event.type() == com.tileboard.engine.event.GameEventType.SESSION_FINISHED ||
                            event.type() == com.tileboard.engine.event.GameEventType.SESSION_STOPPED)) {
                activeSessions.remove(sessionId);
                unsubscribeRef[0].run();
            }
        });

        return sessionId;
    }

    @Override
    public void stopGame(String sessionId) {
        GameSessionImpl s = activeSessions.get(sessionId);
        if (s != null) s.stop();
    }

    @Override
    public Optional<GameSession> activeSession(String sessionId) {
        return Optional.ofNullable(activeSessions.get(sessionId));
    }

    @Override
    public List<GameSession> activeSessions() {
        return List.copyOf(activeSessions.values());
    }

    @Override
    public GameRegistry registry() { return registry; }

    // ── Internal: fan-out touch events to all running sessions ────────────

    private void routeTouchFrame(Board<Boolean> touchBoard) {
        activeSessions.forEach((id, session) -> {
            int w = session.boardWidth();
            int h = session.boardHeight();
            for (Position pos : touchBoard.positionsWhere(Boolean.TRUE::equals)) {
                if (pos.row() < h && pos.col() < w) {
                    session.handleTileEvent(TileEvent.touch(pos, id));
                }
            }
        });
    }
}