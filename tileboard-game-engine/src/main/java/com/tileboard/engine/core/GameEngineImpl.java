package com.tileboard.engine.core;

import com.tileboard.engine.codec.EngineFrameRouter;
import com.tileboard.engine.event.GameEventBus;
import com.tileboard.engine.event.GameEventType;
import com.tileboard.engine.exception.GameSessionException;
import com.tileboard.engine.model.Player;
import com.tileboard.engine.model.TileEvent;
import com.tileboard.serial.board.Board;
import com.tileboard.serial.board.Position;
import com.tileboard.serial.gateway.TileGatewayClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe implementation of {@link GameEngine}.
 */
public final class GameEngineImpl implements GameEngine, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GameEngineImpl.class);

    private final GameRegistry registry;
    private final TileGatewayClient gateway;
    private final GameEventBus eventBus;
    private final Duration tickInterval;

    private final Map<String, GameSessionImpl> activeSessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sessionReaper =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread t = new Thread(runnable, "tileboard-session-reaper");
                t.setDaemon(true);
                return t;
            });


    public GameEngineImpl(GameRegistry registry, TileGatewayClient gateway,
                          GameEventBus eventBus, Duration tickInterval, int boardWidth, int boardHeight) {
        this.registry = Objects.requireNonNull(registry);
        this.gateway = Objects.requireNonNull(gateway);
        this.eventBus = Objects.requireNonNull(eventBus);
        this.tickInterval = tickInterval != null ? tickInterval : Duration.ofMillis(100);

        TouchFrameRouter router = new TouchFrameRouter(activeSessions::values);
        gateway.addFrameListener(
                new EngineFrameRouter(boardWidth, boardHeight, router::route));
    }

    private static void validatePlayers(GameDescriptor descriptor, List<Player> players) {
        int count = players.size();

        if (count < descriptor.minPlayers() || count > descriptor.maxPlayers()) {
            throw new GameSessionException(
                    "Game '%s' requires %d..%d players, but got %d"
                            .formatted(descriptor.gameId(), descriptor.minPlayers(), descriptor.maxPlayers(), count)
            );
        }
        Set<String> ids = new HashSet<>();

        for (Player player : players) {
            Objects.requireNonNull(player, "players contains null");
            if (!ids.add(player.id())) {
                throw new GameSessionException("Duplicate player id: " + player.id());
            }
        }
    }

    @Override
    public String startGame(String gameId, List<Player> players) {
        Objects.requireNonNull(players, "players");
        Game game = registry.instantiate(gameId);

        validatePlayers(game.descriptor(), players);

        String sessionId = UUID.randomUUID().toString();
        GameSessionImpl session =
                new GameSessionImpl(sessionId, game, players, gateway, tickInterval, eventBus);

        activeSessions.put(sessionId, session);

        ScheduledFuture<?> reaper = sessionReaper.schedule(() -> {
            GameSessionImpl s = activeSessions.remove(sessionId);
            if (s != null) {
                log.warn("Session {} TTL exceeded, forcing cleanup", sessionId);
                s.stop();
            }
        }, 1, TimeUnit.HOURS);

        Runnable[] unsubscribeRef = new Runnable[1];
        AtomicBoolean cleaned = new AtomicBoolean(false);
        Runnable cleanup = () -> {
            if (!cleaned.compareAndSet(false, true)) return;
            activeSessions.remove(sessionId);
            reaper.cancel(false);
            if (unsubscribeRef[0] != null) unsubscribeRef[0].run();
        };

        Runnable unsubscribe = eventBus.subscribe(event -> {
            if (event.sessionId().equals(sessionId) &&
                    (event.type() == GameEventType.SESSION_FINISHED || event.type() == GameEventType.SESSION_STOPPED)) {
                cleanup.run();
            }
        });
        unsubscribeRef[0] = unsubscribe;
        session.start();
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
    public GameRegistry registry() {
        return registry;
    }

    @Override
    public void close() {
        activeSessions.values().forEach(GameSession::stop);
        sessionReaper.shutdown();
        try {
            if (!sessionReaper.awaitTermination(5, TimeUnit.SECONDS))
                sessionReaper.shutdownNow();
        } catch (InterruptedException e) {
            sessionReaper.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}