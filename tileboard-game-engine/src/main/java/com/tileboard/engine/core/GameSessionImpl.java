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
import com.tileboard.serial.protocol.Command;
import com.tileboard.serial.protocol.CommandType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class GameSessionImpl implements GameSession, GameContext {

    private static final Logger log = LoggerFactory.getLogger(GameSessionImpl.class);
    /**
     * How many of the most recent touches to expose (newest first) in each SSE snapshot.
     */
    private static final int RECENT_TOUCHES_LIMIT = 5;
    private static final double NANOS_PER_MILLI = 1_000_000.0;

    private final String sessionId;
    private final String tickThreadName;
    private final Game game;
    private final List<Player> players;

    private final BoardChannel boardChannel;
    private final GameState gameState = new GameState();
    private final FeatureBundle features;
    private final GameEventBus eventBus;

    private final ScheduledExecutorService tickExecutor;
    private final ScheduledFuture<?> tickFuture;
    private final ExecutorService teardownExecutor;
    private final SessionLifecycle lifecycle = new SessionLifecycle();
    private final Consumer<GameSessionImpl> onTerminated;
    private final TileGatewayClient gateway;
    private volatile GameResult result;

    public GameSessionImpl(String sessionId, Game game, List<Player> players,
                           TileGatewayClient gateway, Duration tickInterval, GameEventBus sharedEventBus,
                           ExecutorService teardownExecutor, int touchHistoryMaxSize, Consumer<GameSessionImpl> onTerminated) {
        this(sessionId, game, players, gateway, tickInterval, sharedEventBus,
                teardownExecutor, touchHistoryMaxSize, onTerminated, null);
    }

    public GameSessionImpl(String sessionId, Game game, List<Player> players,
                           TileGatewayClient gateway, Duration tickInterval, GameEventBus sharedEventBus,
                           ExecutorService teardownExecutor, int touchHistoryMaxSize, Consumer<GameSessionImpl> onTerminated,
                           BoardFrameBroadcaster boardFrameBroadcaster) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.teardownExecutor = Objects.requireNonNull(teardownExecutor, "teardownExecutor");
        this.tickThreadName = "tileboard-tick-" + sessionId;
        this.game = Objects.requireNonNull(game, "game");
        this.players = List.copyOf(Objects.requireNonNull(players, "players"));
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.eventBus = Objects.requireNonNull(sharedEventBus, "sharedEventBus");
        this.onTerminated = onTerminated;
        int w = game.descriptor().requiredWidth();
        int h = game.descriptor().requiredHeight();
        this.boardChannel = new BoardChannel(w, h, gateway, ColorTileCodec.instance(), boardFrameBroadcaster, sessionId);
        this.features = FeatureBundle.create(w, h, players, sessionId, touchHistoryMaxSize, this::publishBoard);
        if (tickInterval != null && !tickInterval.isZero()) {
            this.tickExecutor = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, tickThreadName));
            long millis = tickInterval.toMillis();
            this.tickFuture = tickExecutor.scheduleAtFixedRate(this::runTick, millis, millis, TimeUnit.MILLISECONDS);
        } else {
            this.tickExecutor = null;
            this.tickFuture = null;
        }
    }


    void start() {
        if (!lifecycle.start()) throw new GameSessionException("Session " + sessionId + " already started");
        features.timer().start();
        try {
            try {
                gateway.send(Command.START, CommandType.SET);
                log.info("sent START to hardware for session: {}", sessionId);
            } catch (RuntimeException e) {
                log.warn("failed to sent START command for session: {} (gateway may be disconnected)", sessionId);
            }
            game.onStart(this);
            eventBus.publish(GameEvent.of(GameEventType.SESSION_STARTED, sessionId, gameId(), snapshotForSse()));
            log.info("Session {} started for game '{}'", sessionId, gameId());
        } catch (RuntimeException e) {
            log.error("onStart threw in session {}", sessionId, e);
            forceStop();
            throw new GameSessionException(
                    "Game '" + gameId() + "' failed to start (session " + sessionId + ")", e);
        }
    }

    public void handleTileEvent(TileEvent event) {
        if (lifecycle.current() != GameStatus.RUNNING) return;
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
        if (lifecycle.current() != GameStatus.RUNNING) return;
        try {
            features.timer().checkExpiry(); // built-in: fire countdown expiry callback automatically every tick
            game.onTick(this);
            eventBus.publish(GameEvent.of(GameEventType.TICK, sessionId, gameId(), snapshotForSse()));
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
        // Previously silent: a single-tile change never reached SSE subscribers until the
        // next TICK. Now every real board mutation is observable immediately, matching
        // publishBoard()'s behaviour.
        eventBus.publish(GameEvent.of(GameEventType.BOARD_UPDATED, sessionId, gameId(), snapshotForSse()));
    }

    @Override
    public void fillBoard(TileColor color) {
        boardChannel.fill(color);
        eventBus.publish(GameEvent.of(GameEventType.BOARD_UPDATED, sessionId, gameId(), snapshotForSse()));
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
        return lifecycle.current();
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
        // SessionLifecycle.finish() is a CAS -> this block runs EXACTLY ONCE per session,
        // so it's safe to treat onTerminated as an idempotent, single-shot hook.
        if (!lifecycle.finish(finalStatus)) return;

        features.timer().stop();
        cancelTick();
        features.closeAll();

        GameResult finalResult = new GameResult(sessionId, gameId(), finalStatus, winners,
                features.scores().allScores(), features.timer().elapsed(), Instant.now());
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

        try {
            gateway.send(Command.STOP, CommandType.SET);
            log.info("sent STOP to hardware for session: {}", sessionId);
        } catch (RuntimeException e) {
            log.warn("Could not send STOP on session end (gateway may be disconnected)");
        }

        // Critical, reliable, synchronous cleanup - MUST NOT depend on the best-effort event bus.
        if (onTerminated != null) {
            try {
                onTerminated.accept(this);
            } catch (RuntimeException e) {
                log.error("onTerminated callback failed for session {}", sessionId, e);
            }
        }

        // Best-effort notification for external observers (SSE, metrics...). Losing this
        // event is acceptable for observers, but must never be relied on for engine state.
        // finalResult is passed explicitly here (not read off this.result) so it is only
        // ever attached to this exact publish — see snapshotForSse(GameResult) javadoc.
        eventBus.publish(GameEvent.of(
                finalStatus == GameStatus.FINISHED ? GameEventType.SESSION_FINISHED : GameEventType.SESSION_STOPPED,
                sessionId, gameId(), snapshotForSse(finalResult)));

        log.info("Session {} ended with status={}, winners={}", sessionId, finalStatus, winners);
    }

    private void forceStop() {
        finishSession(GameStatus.STOPPED, List.of());
    }

    private void cancelTick() {
        if (tickFuture != null) tickFuture.cancel(false);
        if (tickExecutor == null) return;

        tickExecutor.shutdown();
        if (Thread.currentThread().getName().equals(tickThreadName)) {
            teardownExecutor.execute(this::awaitTickExecutorTermination);
        } else {
            awaitTickExecutorTermination();
        }
    }

    private void awaitTickExecutorTermination() {
        try {
            if (!tickExecutor.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                log.warn("Session {} tick executor forced shutdownNow() after timeout", sessionId);
                tickExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            tickExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private SessionSnapshot snapshotForSse() {
        return snapshotForSse(null);
    }

    /**
     * @param report the full end-of-game report to embed, or {@code null} for
     *               every in-progress event. Passed explicitly (rather than
     *               read off {@code this.result}) so that only the one true
     *               terminal publish in {@link #finishSession} ever carries
     *               it — including the intermediate {@code BOARD_UPDATED}
     *               that {@link #finishSession} itself triggers via
     *               {@code fillBoard(OFF)} *after* {@code this.result} has
     *               already been assigned, which must still report {@code null}.
     */
    private SessionSnapshot snapshotForSse(GameResult report) {
        GameTimer timer = features.timer();
        Duration countdownTotal = timer.countdownDuration();

        return SessionSnapshot.builder()
                .scores(features.scores().allScores())
                .level(features.levels().currentLevel())
                .status(lifecycle.current().name())
                .elapsedSeconds(timer.elapsed().toSeconds())
                .board(readableBoard())
                .remainingSeconds(timer.hasCountdown() ? timer.remaining().toSeconds() : null)
                .countdownTotalSeconds(countdownTotal != null ? countdownTotal.toSeconds() : null)
                .health(features.health().snapshot())
                .recentTouches(recentTouchesForSse())
                .totalTouches(features.touchHistory().totalTouches())
                .combo(new SessionSnapshot.ComboInfo(features.combos().current(), features.combos().max()))
                .reaction(reactionInfoForSse())
                .report(report)
                .build();
    }

    /**
     * The most recent touches, newest first, mapped to the JSON-friendly
     * {@link SessionSnapshot.TouchInfo} shape.
     */
    private List<SessionSnapshot.TouchInfo> recentTouchesForSse() {
        return features.touchHistory().activeTouches().stream()
                .map(e -> new SessionSnapshot.TouchInfo(
                        e.position().row(), e.position().col(), e.type().name(), e.occurredAt()))
                .toList();
    }

    private SessionSnapshot.ReactionInfo reactionInfoForSse() {
        ReactionSpeedTracker reaction = features.reactionSpeed();
        long count = reaction.reactionCount();
        if (count == 0) return SessionSnapshot.ReactionInfo.EMPTY;
        double lastMs = reaction.lastReaction().toNanos() / NANOS_PER_MILLI;
        double bestMs = reaction.bestReaction().toNanos() / NANOS_PER_MILLI;
        double averageMs = reaction.averageReactionMillis().orElse(0.0);
        return new SessionSnapshot.ReactionInfo(lastMs, bestMs, averageMs, count);
    }

    /**
     * Renders the current board as a row-major grid of {@link TileColor} names
     * (e.g. {@code "RED"}, {@code "OFF"}) so it's legible in raw JSON without
     * anyone needing to decode the wire protocol.
     */
    private List<List<String>> readableBoard() {
        int h = boardHeight();
        int w = boardWidth();
        List<List<String>> rows = new ArrayList<>(h);
        for (int r = 0; r < h; r++) {
            rows.add(new ArrayList<>(Collections.nCopies(w, TileColor.OFF.name())));
        }
        boardChannel.snapshot().forEach((row, col, tile) -> rows.get(row).set(col, tile.name()));
        return rows;
    }
}