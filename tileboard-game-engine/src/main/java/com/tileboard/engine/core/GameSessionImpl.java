package com.tileboard.engine.core;

import com.tileboard.engine.event.GameEvent;
import com.tileboard.engine.event.GameEventBus;
import com.tileboard.engine.event.GameEventType;
import com.tileboard.engine.exception.GameSessionException;
import com.tileboard.engine.feature.*;
import com.tileboard.engine.feature.neighbor.NeighborFinder;
import com.tileboard.engine.model.Player;
import com.tileboard.engine.model.TileColor;
import com.tileboard.engine.model.TileEvent;
import com.tileboard.engine.codec.ColorTileCodec;
import com.tileboard.serial.board.Board;
import com.tileboard.serial.gateway.TileGatewayClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class GameSessionImpl implements GameSession, GameContext {

    private static final Logger log = LoggerFactory.getLogger(GameSessionImpl.class);

    private final String sessionId;
    private final Game game;
    private final List<Player> players;

    private final BoardChannel boardChannel;

    private final AtomicReference<GameStatus> status = new AtomicReference<>(GameStatus.IDLE);
    private final GameState gameState = new GameState();

    private final FeatureBundle features;

    private final GameEventBus eventBus;

    private final ScheduledExecutorService tickExecutor;
    private final ScheduledFuture<?> tickFuture;
    private volatile GameResult result;

    public GameSessionImpl(String sessionId, Game game, List<Player> players,
                           TileGatewayClient gateway, Duration tickInterval, GameEventBus sharedEventBus) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.game = Objects.requireNonNull(game, "game");
        this.players = List.copyOf(Objects.requireNonNull(players, "players"));
        Objects.requireNonNull(gateway, "gateway");
        this.eventBus = Objects.requireNonNull(sharedEventBus, "sharedEventBus");

        int w = game.descriptor().requiredWidth();
        int h = game.descriptor().requiredHeight();
        this.boardChannel = new BoardChannel(w, h, gateway, ColorTileCodec.instance());
        this.features = FeatureBundle.create(w, h, players, sessionId, this::publishBoard);

        if (tickInterval != null && !tickInterval.isZero()) {
            this.tickExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "tileboard-tick-" + sessionId);
                t.setDaemon(true);
                return t;
            });
            long millis = tickInterval.toMillis();
            this.tickFuture = tickExecutor.scheduleAtFixedRate(
                    this::runTick, millis, millis, TimeUnit.MILLISECONDS);
        } else {
            this.tickExecutor = null;
            this.tickFuture = null;
        }
    }

    void start() {
        if (!status.compareAndSet(GameStatus.IDLE, GameStatus.RUNNING)) {
            throw new GameSessionException("Session " + sessionId + " already started");
        }
        features.timer().start();
        try {
            game.onStart(this);
            eventBus.publish(GameEvent.of(
                    GameEventType.SESSION_STARTED, sessionId, game.descriptor().gameId(), snapshotForSse()));
            log.info("Session {} started for game '{}'", sessionId, game.descriptor().gameId());
        } catch (RuntimeException e) {
            log.error("onStart threw in session {}", sessionId, e);
            forceStop();
            throw new GameSessionException(
                    "Game '" + game.descriptor().gameId() + "' failed to start (session " + sessionId + ")", e);
        }
    }

    public void handleTileEvent(TileEvent event) {
        if (status.get() != GameStatus.RUNNING) return;
        features.touchHistory().record(event);
        features.reactionSpeed().record(event);
        try {
            game.onTileEvent(this, event);
        } catch (RuntimeException e) {
            log.warn("onTileEvent threw in session {}", sessionId, e);
            handleGameError(e);
        }
    }

    private void runTick() {
        if (status.get() != GameStatus.RUNNING) return;
        try {
            game.onTick(this);
            eventBus.publish(GameEvent.of(
                    GameEventType.TICK, sessionId, game.descriptor().gameId(), snapshotForSse()));
        } catch (RuntimeException e) {
            log.warn("onTick threw in session {}", sessionId, e);
            handleGameError(e);
        }
    }

    private void handleGameError(RuntimeException e) {
        try {
            game.onError(this, e);
        } catch (RuntimeException ex) {
            log.error("onError also threw in session {}", sessionId, ex);
            forceStop();
        }
    }

    // ── GameContext ──────────────────────────────────────────────────
    @Override
    public String sessionId() {
        return sessionId;
    }

    @Override
    public GameDescriptor descriptor() {
        return game.descriptor();
    }

    @Override
    public List<Player> players() {
        return players;
    }

    @Override
    public GameState state() {
        return gameState;
    }

    @Override
    public int boardWidth() {
        return game.descriptor().requiredWidth();
    }

    @Override
    public int boardHeight() {
        return game.descriptor().requiredHeight();
    }

    @Override
    public void publishBoard(Board<TileColor> board) {
        boardChannel.publish(board);
        eventBus.publish(GameEvent.of(GameEventType.BOARD_UPDATED, sessionId, gameId(), snapshotForSse()));
    }

    @Override
    public void setTile(int row, int col, TileColor color) {
        boardChannel.setTile(row, col, color);
    }

    @Override
    public void fillBoard(TileColor color) {
        boardChannel.fill(color);
    }

    @Override
    public Board<TileColor> newBoard() {
        return boardChannel.newEmptyBoard();
    }

    @Override
    public ScoreSystem scores() {
        return features.scores();
    }

    @Override
    public HealthSystem health() {
        return features.health();
    }

    @Override
    public LevelSystem levels() {
        return features.levels();
    }

    @Override
    public ComboTracker combos() {
        return features.combos();
    }

    @Override
    public GameTimer timer() {
        return features.timer();
    }

    @Override
    public TouchHistory touchHistory() {
        return features.touchHistory();
    }

    @Override
    public TouchAnalyzer touchAnalyzer() {
        return features.touchAnalyzer();
    }

    @Override
    public BoardFeature board() {
        return features.board();
    }

    @Override
    public NeighborFinder neighbors() {
        return features.neighbors();
    }

    @Override
    public PatternMatcher patterns() {
        return features.patterns();
    }

    @Override
    public RandomFeature random() {
        return features.random();
    }

    @Override
    public WaveGenerator waves() {
        return features.waves();
    }

    @Override
    public MemoryFeature memory() {
        return features.memory();
    }

    @Override
    public ReactionSpeedTracker reactionSpeed() {
        return features.reactionSpeed();
    }

    @Override
    public GraphFeature graph() {
        return features.graph();
    }

    @Override
    public AnimationSystem animations() {
        return features.animations();
    }

    @Override
    public GameEventBus eventBus() {
        return eventBus;
    }

    @Override
    public void winSession(List<Player> winners) {
        finishSession(GameStatus.FINISHED, List.copyOf(winners));
    }

    @Override
    public void loseSession() {
        finishSession(GameStatus.FINISHED, List.of());
    }

    @Override
    public void stopSession() {
        forceStop();
    }

    @Override
    public GameStatus status() {
        return status.get();
    }

    @Override
    public String gameId() {
        return game.descriptor().gameId();
    }

    @Override
    public Optional<GameResult> result() {
        return Optional.ofNullable(result);
    }

    @Override
    public void stop() {
        forceStop();
    }

    private void finishSession(GameStatus finalStatus, List<Player> winners) {
        GameStatus prev;
        do {
            prev = status.get();
            if (prev != GameStatus.RUNNING && prev != GameStatus.PAUSED) return;
        } while (!status.compareAndSet(prev, finalStatus));

        features.timer().stop();
        cancelTick();
        features.closeAll();

        GameResult finalResult = new GameResult(
                sessionId, game.descriptor().gameId(), finalStatus, winners,
                features.scores().allScores(), features.timer().elapsed(), Instant.now()
        );
        this.result = finalResult;

        try {
            game.onStop(this, finalResult);
        } catch (RuntimeException e) {
            log.warn("onStop threw in session {}", sessionId, e);
        }
        try {
            fillBoard(TileColor.OFF);
        } catch (RuntimeException e) {
            log.warn("Could not clear board on session end (gateway may be disconnected): {}", e.getMessage());
        }

        eventBus.publish(GameEvent.of(
                finalStatus == GameStatus.FINISHED ? GameEventType.SESSION_FINISHED : GameEventType.SESSION_STOPPED,
                sessionId, game.descriptor().gameId(), snapshotForSse()));

        log.info("Session {} ended with status={}, winners={}", sessionId, finalStatus, winners);
    }

    private void forceStop() {
        finishSession(GameStatus.STOPPED, List.of());
    }

    private void cancelTick() {
        if (tickFuture != null) tickFuture.cancel(false);
        if (tickExecutor != null) {
            tickExecutor.shutdown();
            try {
                if (!tickExecutor.awaitTermination(500, TimeUnit.MILLISECONDS)) tickExecutor.shutdownNow();
            } catch (InterruptedException e) {
                tickExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private SessionSnapshot snapshotForSse() {
        return new SessionSnapshot(
                features.scores().allScores(),
                features.levels().currentLevel(),
                status.get().name(),
                features.timer().elapsed().toSeconds()
        );
    }
}