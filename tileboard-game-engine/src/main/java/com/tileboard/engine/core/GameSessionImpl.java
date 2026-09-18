package com.tileboard.engine.core;

import com.tileboard.engine.event.GameEventBus;
import com.tileboard.engine.event.GameEventType;
import com.tileboard.engine.event.GameEventBusImpl;
import com.tileboard.engine.exception.GameSessionException;
import com.tileboard.engine.feature.*;
import com.tileboard.engine.feature.neighbor.Adjacency;
import com.tileboard.engine.feature.neighbor.NeighborFinder;
import com.tileboard.engine.model.Player;
import com.tileboard.engine.model.TileColor;
import com.tileboard.engine.model.TileEvent;
import com.tileboard.engine.codec.ColorTileCodec;
import com.tileboard.serial.board.Board;
import com.tileboard.serial.board.TileCodec;
import com.tileboard.serial.gateway.TileGatewayClient;
import com.tileboard.serial.protocol.Command;
import com.tileboard.serial.protocol.CommandType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The canonical, thread-safe implementation of both {@link GameSession} and
 * {@link GameContext}. One instance is created per {@link GameEngine#startGame} call.
 */
public final class GameSessionImpl implements GameSession, GameContext {

    private static final Logger log = LoggerFactory.getLogger(GameSessionImpl.class);

    // ── Identity ──────────────────────────────────────────────────────────
    private final String                  sessionId;
    private final Game                    game;
    private final List<Player>            players;
    private final TileGatewayClient       gateway;
    private final TileCodec<TileColor>    colorCodec;
    private final Instant                 startedAt = Instant.now();
    private final AnimationSystem         animationSystem;

    // ── State ─────────────────────────────────────────────────────────────
    private final AtomicReference<GameStatus> status = new AtomicReference<>(GameStatus.IDLE);
    private volatile GameResult result;

    // ── Shared board buffer (write-lock protected) ────────────────────────
    private final Board<TileColor>  boardBuffer;
    private final Object            boardWriteLock = new Object();

    // ── Built-in features ────────────────────────────────────────────────
    private final GameState              gameState     = new GameState();
    private final ScoreSystem            scoreSystem;
    private final HealthSystem           healthSystem;
    private final LevelSystem            levelSystem;
    private final ComboTracker           comboTracker;
    private final GameTimer              gameTimer;
    private final TouchHistory           touchHistory;
    private final TouchAnalyzer          touchAnalyzer;
    private final BoardFeature           boardFeature;
    private final NeighborFinder         neighborFinder;
    private final PatternMatcher         patternMatcher;
    private final RandomFeature          randomFeature;
    private final WaveGenerator          waveGenerator;
    private final MemoryFeature          memoryFeature;
    private final ReactionSpeedTracker   reactionSpeed;
    private final GraphFeature           graphFeature;

    // ── Events ────────────────────────────────────────────────────────────
    private final GameEventBus eventBus;

    // ── Tick executor ────────────────────────────────────────────────────
    private final ScheduledExecutorService tickExecutor;
    private ScheduledFuture<?>             tickFuture;

    public GameSessionImpl(
            String sessionId,
            Game game,
            List<Player> players,
            TileGatewayClient gateway,
            Duration tickInterval,
            GameEventBus sharedEventBus
    ) {
        this.sessionId   = Objects.requireNonNull(sessionId);
        this.game        = Objects.requireNonNull(game);
        this.players     = List.copyOf(Objects.requireNonNull(players));
        this.gateway     = Objects.requireNonNull(gateway);
        this.colorCodec  = ColorTileCodec.instance();
        this.eventBus    = sharedEventBus;
        int w = game.descriptor().requiredWidth();
        int h = game.descriptor().requiredHeight();
        this.boardBuffer = new Board<>(w, h, TileColor.OFF);

        // ── Initialise built-in features ──────────────────────────────────
        this.scoreSystem   = new ScoreSystem(players);
        this.healthSystem  = new HealthSystem(players);
        this.levelSystem   = new LevelSystem();
        this.comboTracker  = new ComboTracker();
        this.gameTimer     = new GameTimer();
        this.touchHistory  = new TouchHistory(sessionId);
        this.touchAnalyzer = new TouchAnalyzer(touchHistory);
        this.boardFeature  = new BoardFeature(w, h);
        this.neighborFinder= new NeighborFinder(w, h, Adjacency.FOUR_WAY);
        this.patternMatcher= new PatternMatcher();
        this.randomFeature = new RandomFeature(w, h);
        this.waveGenerator = new WaveGenerator(w, h, this::publishBoard);
        this.memoryFeature = new MemoryFeature();
        this.reactionSpeed = new ReactionSpeedTracker();
        this.graphFeature  = new GraphFeature(w, h);
        this.animationSystem = new AnimationSystem(w, h, this::publishBoard);

        this.tickExecutor  = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tileboard-tick-" + sessionId);
            t.setDaemon(true);
            return t;
        });

        if (tickInterval != null && !tickInterval.isZero()) {
            long millis = tickInterval.toMillis();
            this.tickFuture = tickExecutor.scheduleAtFixedRate(
                    this::runTick, millis, millis, TimeUnit.MILLISECONDS);
        }
    }

    // ── Package-private start (called by engine after construction) ───────

    void start() {
        if (!status.compareAndSet(GameStatus.IDLE, GameStatus.RUNNING)) {
            throw new GameSessionException("Session " + sessionId + " already started");
        }
        gameTimer.start();
        try {
            game.onStart(this);
            eventBus.publish(com.tileboard.engine.event.GameEvent.of(
                    GameEventType.SESSION_STARTED, sessionId, game.descriptor().gameId(), Map.of()));
            log.info("Session {} started for game '{}'", sessionId, game.descriptor().gameId());
        } catch (RuntimeException e) {
            log.error("onStart threw in session {}", sessionId, e);
            forceStop();
        }
    }

    /** Called by the engine's DATA_IN listener for every touch frame. */
    public void handleTileEvent(TileEvent event) {
        if (status.get() != GameStatus.RUNNING) return;
        touchHistory.record(event);
        reactionSpeed.record(event);
        try {
            game.onTileEvent(this, event);
        } catch (RuntimeException e) {
            log.warn("onTileEvent threw in session {}", sessionId, e);
            try { game.onError(this, e); } catch (RuntimeException ex) {
                log.error("onError also threw in session {}", sessionId, ex);
                forceStop();
            }
        }
    }

    private void runTick() {
        if (status.get() != GameStatus.RUNNING) return;
        try {
            game.onTick(this);
            eventBus.publish(com.tileboard.engine.event.GameEvent.of(
                    GameEventType.TICK, sessionId, game.descriptor().gameId(), snapshotForSse()));
        } catch (RuntimeException e) {
            log.warn("onTick threw in session {}", sessionId, e);
            try { game.onError(this, e); } catch (RuntimeException ex) {
                log.error("onError threw during tick handling in session {}", sessionId, ex);
                forceStop();
            }
        }
    }

    // ── GameContext ───────────────────────────────────────────────────────

    @Override public String         sessionId()   { return sessionId; }
    @Override public GameDescriptor descriptor()  { return game.descriptor(); }
    @Override public List<Player>   players()     { return players; }
    @Override public GameState      state()       { return gameState; }
    @Override public int            boardWidth()  { return game.descriptor().requiredWidth(); }
    @Override public int            boardHeight() { return game.descriptor().requiredHeight(); }

    @Override
    public void publishBoard(Board<TileColor> board) {
        synchronized (boardWriteLock) {
            board.forEach(boardBuffer::set);
            gateway.sendBoard(Command.DATA_OUT, CommandType.SET, board, colorCodec);
        }
        eventBus.publish(com.tileboard.engine.event.GameEvent.of(
                GameEventType.BOARD_UPDATED, sessionId, game.descriptor().gameId(),
                Map.of("boardSnapshot", "sent")));
    }

    @Override
    public void setTile(int row, int col, TileColor color) {
        synchronized (boardWriteLock) {
            boardBuffer.set(row, col, color);
            gateway.sendBoard(Command.DATA_OUT, CommandType.SET, boardBuffer, colorCodec);
        }
    }

    @Override
    public void fillBoard(TileColor color) {
        synchronized (boardWriteLock) {
            boardBuffer.fill(color);
            gateway.sendBoard(Command.DATA_OUT, CommandType.SET, boardBuffer, colorCodec);
        }
    }

    @Override
    public Board<TileColor> newBoard() {
        return new Board<>(boardWidth(), boardHeight(), TileColor.OFF);
    }

    // ── Features ──────────────────────────────────────────────────────────

    @Override public ScoreSystem           scores()        { return scoreSystem;   }
    @Override public HealthSystem          health()        { return healthSystem;  }
    @Override public LevelSystem           levels()        { return levelSystem;   }
    @Override public ComboTracker          combos()        { return comboTracker;  }
    @Override public GameTimer             timer()         { return gameTimer;     }
    @Override public TouchHistory          touchHistory()  { return touchHistory;  }
    @Override public TouchAnalyzer         touchAnalyzer() { return touchAnalyzer; }
    @Override public BoardFeature          board()         { return boardFeature;  }
    @Override public NeighborFinder        neighbors()     { return neighborFinder;}
    @Override public PatternMatcher        patterns()      { return patternMatcher;}
    @Override public RandomFeature         random()        { return randomFeature; }
    @Override public WaveGenerator         waves()         { return waveGenerator; }
    @Override public MemoryFeature         memory()        { return memoryFeature; }
    @Override public ReactionSpeedTracker  reactionSpeed() { return reactionSpeed; }
    @Override public GraphFeature          graph()         { return graphFeature;  }
    @Override public GameEventBus          eventBus()      { return eventBus;      }
    @Override public AnimationSystem       animations()    { return animationSystem; }
    // ── Session control ───────────────────────────────────────────────────

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
    public GameStatus status() { return status.get(); }

    // ── GameSession ───────────────────────────────────────────────────────

    @Override public String gameId() { return game.descriptor().gameId(); }

    @Override
    public Optional<GameResult> result() { return Optional.ofNullable(result); }

    @Override
    public void stop() { forceStop(); }

    // ── Internal helpers ──────────────────────────────────────────────────

    private void finishSession(GameStatus finalStatus, List<Player> winners) {
        if (!status.compareAndSet(GameStatus.RUNNING, finalStatus) &&
                !status.compareAndSet(GameStatus.PAUSED,  finalStatus)) {
            return; // already finished or stopped
        }
        gameTimer.stop();
        cancelTick();
        animationSystem.shutdown();

        result = new GameResult(
                sessionId,
                game.descriptor().gameId(),
                finalStatus,
                winners,
                scoreSystem.allScores(),
                gameTimer.elapsed(),
                Instant.now()
        );

        try {
            game.onStop(this, result);
        } catch (RuntimeException e) {
            log.warn("onStop threw in session {}", sessionId, e);
        }

        fillBoard(TileColor.OFF);

        eventBus.publish(com.tileboard.engine.event.GameEvent.of(
                finalStatus == GameStatus.FINISHED ? GameEventType.SESSION_FINISHED : GameEventType.SESSION_STOPPED,
                sessionId, game.descriptor().gameId(), snapshotForSse()));

        log.info("Session {} ended with status={}, winners={}", sessionId, finalStatus, winners);
    }

    private void forceStop() {
        finishSession(GameStatus.STOPPED, List.of());
    }

    private void cancelTick() {
        if (tickFuture != null) tickFuture.cancel(false);
        tickExecutor.shutdown();
    }

    private Map<String, Object> snapshotForSse() {
        Map<String, Object> snap = new HashMap<>();
        snap.put("scores",  scoreSystem.allScores());
        snap.put("level",   levelSystem.currentLevel());
        snap.put("status",  status.get().name());
        snap.put("elapsed", gameTimer.elapsed().toSeconds());
        return Collections.unmodifiableMap(snap);
    }
}