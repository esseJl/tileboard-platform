package com.tileboard.engine.core;

import com.tileboard.engine.codec.EngineFrameRouter;
import com.tileboard.engine.event.GameEventBus;
import com.tileboard.engine.event.GameEventType;
import com.tileboard.engine.exception.GameSessionException;
import com.tileboard.engine.model.Player;
import com.tileboard.serial.gateway.TileGatewayClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class GameEngineImpl implements GameEngine, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GameEngineImpl.class);

    private final GameRegistry registry;
    private final TileGatewayClient gateway;
    private final GameEventBus eventBus;
    private final Duration tickInterval;
    private final Duration sessionTtl;
    private final int touchHistoryMaxSize;
    private final int boardWidth;
    private final int boardHeight;

    private final Map<String, GameSessionImpl> activeSessions = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> reaperTasks = new ConcurrentHashMap<>();
    private final AtomicReference<String> exclusiveSessionId = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private final ScheduledExecutorService sessionReaper;
    private final ExecutorService teardownExecutor;

    public GameEngineImpl(GameRegistry registry, TileGatewayClient gateway, GameEventBus eventBus,
                          Duration tickInterval, Duration sessionTtl, Duration frameReassemblyTimeout,
                          int touchHistoryMaxSize, int boardWidth, int boardHeight) {
        this.registry = Objects.requireNonNull(registry);
        this.gateway = Objects.requireNonNull(gateway);
        this.eventBus = Objects.requireNonNull(eventBus);
        this.tickInterval = tickInterval != null ? tickInterval : Duration.ofMillis(100);
        this.sessionTtl = (sessionTtl != null && !sessionTtl.isZero()) ? sessionTtl : Duration.ofHours(1);
        this.touchHistoryMaxSize = touchHistoryMaxSize > 0 ? touchHistoryMaxSize : 2_000;
        this.boardWidth = boardWidth;
        this.boardHeight = boardHeight;
        Duration effectiveReassembly = (frameReassemblyTimeout != null && !frameReassemblyTimeout.isZero())
                ? frameReassemblyTimeout : Duration.ofMillis(500);

        this.sessionReaper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tileboard-session-reaper");
            t.setDaemon(true);
            return t;
        });
        this.teardownExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "tileboard-session-teardown");
            t.setDaemon(true);
            return t;
        });

        TouchFrameRouter router = new TouchFrameRouter(
                id -> Optional.ofNullable(activeSessions.get(id)), this::exclusiveOwner);

        // Guarded so a listener callback fired after close() never touches a torn-down engine (see Bug #4).
        gateway.addFrameListener(new EngineFrameRouter(boardWidth, boardHeight, board -> {
            if (!closed.get()) router.route(board);
        }, effectiveReassembly));
    }

    private static void validatePlayers(GameDescriptor descriptor, List<Player> players) {
        int count = players.size();
        if (count < descriptor.minPlayers() || count > descriptor.maxPlayers()) {
            throw new GameSessionException("Game '%s' requires %d..%d players, but got %d"
                    .formatted(descriptor.gameId(), descriptor.minPlayers(), descriptor.maxPlayers(), count));
        }
        Set<String> ids = new HashSet<>();
        for (Player player : players) {
            Objects.requireNonNull(player, "players contains null");
            if (!ids.add(player.id())) throw new GameSessionException("Duplicate player id: " + player.id());
        }
    }

    private void validateBoardSize(GameDescriptor descriptor) {
        if (descriptor.requiredWidth() != boardWidth || descriptor.requiredHeight() != boardHeight) {
            throw new GameSessionException(
                    "Game '%s' requires a %dx%d board, but the connected gateway reports %dx%d"
                            .formatted(descriptor.gameId(), descriptor.requiredWidth(), descriptor.requiredHeight(),
                                    boardWidth, boardHeight));
        }
    }


    @Override
    public String startGame(String gameId, List<Player> players) {
        Objects.requireNonNull(players, "players");
        Game game = registry.instantiate(gameId);
        GameDescriptor descriptor = game.descriptor();
        validateBoardSize(descriptor);
        validatePlayers(descriptor, players);

        String sessionId = UUID.randomUUID().toString();
        if (!exclusiveSessionId.compareAndSet(null, sessionId)) {
            throw new GameSessionException("Cannot start game '%s': board is already owned by session %s"
                    .formatted(gameId, exclusiveSessionId.get()));
        }

        GameSessionImpl session;
        try {
            session = new GameSessionImpl(sessionId, game, players, gateway, tickInterval, eventBus,
                    teardownExecutor, touchHistoryMaxSize, this::handleSessionTerminated);
        } catch (RuntimeException e) {
            exclusiveSessionId.compareAndSet(sessionId, null);
            throw e;
        }
        activeSessions.put(sessionId, session);

        ScheduledFuture<?> reaper = sessionReaper.schedule(() -> {
            GameStatus status = session.status();
            if (status == GameStatus.RUNNING || status == GameStatus.PAUSED) {
                log.warn("Session {} TTL ({}) exceeded, forcing cleanup", sessionId, sessionTtl);
                session.stop(); // synchronously triggers finishSession -> handleSessionTerminated
            }
        }, sessionTtl.toMillis(), TimeUnit.MILLISECONDS);
        reaperTasks.put(sessionId, reaper);

        try {
            session.start();
        } catch (RuntimeException e) {
            // Defensive only: GameSessionImpl.start() already invokes forceStop() -> finishSession()
            // -> handleSessionTerminated() synchronously on failure, so this is a safety net for
            // any future code path that might throw without going through forceStop().
            handleSessionTerminated(session);
            throw e;
        }
        return sessionId;
    }

    private void handleSessionTerminated(GameSessionImpl session) {
        String sessionId = session.sessionId();
        activeSessions.remove(sessionId);
        exclusiveSessionId.compareAndSet(sessionId, null);
        ScheduledFuture<?> reaper = reaperTasks.remove(sessionId);
        if (reaper != null) reaper.cancel(false);
    }

    @Override
    public boolean stopGame(String sessionId) {
        GameSessionImpl s = activeSessions.get(sessionId);
        if (s == null) {
            log.warn("stopGame() called with unknown or already-finished sessionId={}", sessionId);
            return false;
        }
        s.stop();
        return true;
    }

    public Optional<String> exclusiveOwner() {
        return Optional.ofNullable(exclusiveSessionId.get());
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
        if (!closed.compareAndSet(false, true)) return;
        activeSessions.values().forEach(GameSession::stop);
        sessionReaper.shutdown();
        teardownExecutor.shutdown();
        try {
            if (!sessionReaper.awaitTermination(5, TimeUnit.SECONDS)) sessionReaper.shutdownNow();
            if (!teardownExecutor.awaitTermination(5, TimeUnit.SECONDS)) teardownExecutor.shutdownNow();
        } catch (InterruptedException e) {
            sessionReaper.shutdownNow();
            teardownExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}