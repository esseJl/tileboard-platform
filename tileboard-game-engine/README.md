# tileboard-game-engine - Comprehensive Game Engine Documentation

> **Module Mission:** A production-ready, transport-agnostic, framework-free game engine built on top of `tileboard-serial-protocol`. It provides rich built-in features (scoring, health, levels, combos, patterns, timers, neighbors, SSE streaming, animations) while staying framework-free at its core; a Spring Boot auto-configuration layer is included as an optional adapter.

---

## Table of Contents
1. [Overall Architecture](#overall-architecture)
2. [Package Structure](#package-structure)
3. [Core Concepts - Game, GameDescriptor, GameContext](#core-concepts)
4. [GameEngine and GameSession - Lifecycle](#gameengine-and-gamesession---lifecycle)
5. [BoardChannel - Board Publishing with Coalescing](#boardchannel---board-publishing-with-coalescing)
6. [FeatureBundle - Ready-Made Features](#featurebundle---ready-made-features)
7. [AnimationSystem - win/lose/standby/countdown Animations](#animationsystem)
8. [EventBus - Thread-Safe Event System](#eventbus---thread-safe-event-system)
9. [EngineFrameRouter and TouchFrameRouter - Frame Routing](#engineframerouter-and-touchframerouter)
10. [SSE - Streaming to Frontend](#sse---streaming-to-frontend)
11. [Spring Layer - AutoConfiguration](#spring-layer---autoconfiguration)
12. [Step-by-Step Game Creation Tutorial](#step-by-step-game-creation-tutorial)
13. [Deep Dive - Concurrency](#deep-dive---concurrency)
14. [Tests](#tests)
15. [Dependencies](#dependencies)

---

## Overall Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│  Game (your code)                                                │
│  implements GameLifecycle { onStart, onTileEvent, onTick, onStop }│
├──────────────────────────────────────────────────────────────────┤
│  GameContext (passed to every lifecycle method)                  │
│  ├─ BoardContext: publishBoard, setTile, fillBoard, newBoard     │
│  ├─ SessionControl: winSession, loseSession, stopSession, state  │
│  ├─ FeatureProvider (14 features: scores, timer, animations, …) │
│  └─ eventBus()                                                   │
├──────────────────────────────────────────────────────────────────┤
│  GameEngineImpl                                                   │
│  ├─ GameRegistry (register/instantiate games)                   │
│  ├─ activeSessions: ConcurrentHashMap<sessionId, GameSessionImpl>│
│  ├─ exclusiveSessionId: AtomicReference (only one session owns board)│
│  ├─ sessionReaper: ScheduledExecutorService (per-session TTL)    │
│  ├─ teardownExecutor: CachedThreadPool (tick shutdown offload)   │
│  └─ TouchFrameRouter + EngineFrameRouter (frame → TileEvent)     │
├──────────────────────────────────────────────────────────────────┤
│  GameSessionImpl                                                  │
│  ├─ BoardChannel (ReentrantLock + gatewayWriteLock)              │
│  ├─ FeatureBundle (ScoreSystem, HealthSystem, AnimationSystem…)  │
│  ├─ GameState (synchronized HashMap)                             │
│  ├─ tickExecutor: ScheduledExecutorService (onTick every tick)   │
│  └─ lifecycle: SessionLifecycle (CAS state machine IDLE→…)       │
├──────────────────────────────────────────────────────────────────┤
│  TileGatewayClient (from serial-protocol)                         │
│  └─ SerialTransport → Hardware                                   │
└──────────────────────────────────────────────────────────────────┘
```

**Stateless Game Contract:** When you register a `Game` via `registry.register(game)`, a **single instance** is reused across all future sessions of that `gameId` (similar to Servlet singleton model — `DefaultGameRegistry` stores `() -> game` as the factory). So you **must not** keep mutable state in instance fields. All session-scoped data must be stored in `ctx.state()` (fresh `GameState` per session) or in feature objects inside `FeatureBundle` (also per-session). If you genuinely need per-session construction state, register with `registry.register(descriptor, factory)` which creates a brand-new instance per session via your `GameFactory`.

---

## Package Structure

| Package | Responsibility |
|------|---------|
| `core` | Core contracts: `Game`, `GameDescriptor`, `GameContext`, `CoreGameContext`, `BoardContext`, `SessionControl`, `FeatureProvider` (+ `ScoringFeatures`, `BoardFeatures`, `PlayerFeedbackFeatures`, `PresentationFeatures`, `UtilityFeatures`), `GameEngine`, `GameEngineImpl`, `GameSession`, `GameSessionImpl`, `GameState`, `GameStatus`, `SessionLifecycle`, `GameResult`, `SessionSnapshot`, `GameRegistry`, `DefaultGameRegistry`, `GameFactory`, `BoardChannel`, `BoardFrameBroadcaster`, `BoardFrameListener`, `TouchFrameRouter`, `FeatureBundle` |
| `feature` | Ready-made features: `ScoreSystem`, `HealthSystem`, `LevelSystem`, `ComboTracker`, `GameTimer`, `TouchHistory`, `TouchAnalyzer`, `BoardFeature`, `PatternMatcher`, `RandomFeature`, `WaveGenerator`, `MemoryFeature`, `ReactionSpeedTracker`, `GraphFeature`, `AnimationSystem`, `AnimationRegistry`, `BoardAnimation` |
| `feature/neighbor` | Grid topology: `Adjacency`, `GridTopology`, `NeighborFinder` |
| `event` | Event Bus: `GameEvent`, `GameEventType`, `GameEventBus`, `GameEventBusImpl`, `GameEventListener`, `SubscriptionOptions`, `EventOverflowPolicy` |
| `model` | Domain model: `Player`, `PlayerRole`, `Team`, `TileColor`, `TileEvent`, `TileEventType`, `TouchSequence` |
| `codec` | Color codec and frame routing: `ColorTileCodec`, `EngineFrameRouter` |
| `sse` | SSE DTOs and emitter: `SseGameEvent`, `SseGameEventType`, `GameEventSseEmitter` |
| `spring` | Spring integration: `TileboardEngineAutoConfiguration`, `TileboardEngineProperties`, `GameEngineManager`, `GatewayConnectedEvent`, `GatewayDisconnectedEvent`, `SseGameEventPublisher`, `GameEngineMetricsBinder` |
| `exception` | Exceptions: `GameEngineException`, `GameNotFoundException`, `GameSessionException`, `EngineNotReadyException` (all `LocalizableException`-based) |

---

## Core Concepts

### GameDescriptor - Static Game Metadata

```java
public record GameDescriptor(String gameId, String displayName, String category,
                             String description, int requiredWidth, int requiredHeight,
                             int minPlayers, int maxPlayers) { ... }

// Usage (builder defaults: category="GENERAL", description="", boardSize 8x8, players 1..1):
GameDescriptor desc = GameDescriptor.builder("my-game", "My Awesome Game")
    .category("ARCADE")
    .description("Game description")
    .boardSize(8, 8)
    .players(1, 4)
    .build();
```

- The compact constructor validates: non-null strings, `requiredWidth/Height > 0`, `minPlayers >= 1`, `maxPlayers >= minPlayers`.
- `requiredWidth/Height` must match the connected board, otherwise `GameEngineImpl.validateBoardSize` throws `GameSessionException` (`game.board_size_mismatch`).
- `min/maxPlayers` are checked in `validatePlayers` (`game.player_count_out_of_range`), and duplicate player ids are rejected (`game.duplicate_player_id`).

### Game - Contract You Implement

```java
public interface Game extends GameLifecycle {
    GameDescriptor descriptor();
}

public interface GameLifecycle {
    void onStart(GameContext ctx);
    void onTileEvent(GameContext ctx, TileEvent event);
    default void onTick(GameContext ctx) {}
    default void onStop(GameContext ctx, GameResult result) {}
    default void onError(GameContext ctx, Throwable error) { ctx.stopSession(); }
}
```

Thread placement (see `GameSessionImpl`):
- `onStart`: runs on the thread calling `engine.startGame()` (usually an HTTP request thread).
- `onTileEvent`: runs on the gateway callback executor thread (via `TouchFrameRouter` → `GameSessionImpl.handleTileEvent`), and only while the session is `RUNNING` (other states are dropped silently). `RELEASE` events are delivered too — games that only care about presses must filter on `event.type()` themselves.
- `onTick`: runs on the session's own tick thread (`tileboard-tick-<sessionId>`), every `tickInterval` (default 100 ms); no tick thread exists at all when `tickInterval` is null/zero.
- `onStop`: runs on whichever thread triggered `finishSession` (game thread, tick thread, TTL reaper, or HTTP thread calling `stopGame`).
- `onError`: called by `handleGameError` when `onTileEvent`/`onTick` throws; the default just stops the session (no logging — the engine already logged the original exception). If `onError` itself throws, the engine logs and force-stops.

### GameContext - Game's Window to Engine

```java
public interface GameContext extends CoreGameContext, FeatureProvider {
    GameEventBus eventBus();
}
public interface CoreGameContext extends BoardContext, SessionControl {
    GameEventBus eventBus();
    ScoreSystem scores();
}
public interface BoardContext {
    void publishBoard(Board<TileColor> board);
    void setTile(int row, int col, TileColor color);
    void fillBoard(TileColor color);
    Board<TileColor> newBoard();
    int boardWidth();
    int boardHeight();
}
public interface SessionControl {
    String sessionId();
    GameStatus status();
    List<Player> players();
    GameDescriptor descriptor();
    GameState state();
    void winSession(List<Player> winners);
    void loseSession();
    void stopSession();
}
// FeatureProvider extends 5 grouped interfaces:
public interface ScoringFeatures       { ScoreSystem scores(); ComboTracker combos(); LevelSystem levels(); }
public interface BoardFeatures         { BoardFeature board(); NeighborFinder neighbors(); PatternMatcher patterns(); GraphFeature graph(); }
public interface PlayerFeedbackFeatures { HealthSystem health(); ReactionSpeedTracker reactionSpeed(); TouchHistory touchHistory(); TouchAnalyzer touchAnalyzer(); }
public interface PresentationFeatures  { WaveGenerator waves(); AnimationSystem animations(); }
public interface UtilityFeatures       { RandomFeature random(); MemoryFeature memory(); GameTimer timer(); }
```

Every board mutation (`publishBoard`/`setTile`/`fillBoard`) both writes to hardware via `BoardChannel` **and** publishes a `BOARD_UPDATED` event with a fresh `SessionSnapshot`, so SSE subscribers observe single-tile changes immediately (not just on the next `TICK`).

### GameState - Thread-Safe Bag for Session State

```java
public final class GameState {
    private final Map<String, Object> store = new HashMap<>();
    public synchronized <T> void put(String key, T value) { ... }            // key must be non-null
    public synchronized <T> Optional<T> get(String key, Class<T> type) { ... } // ClassCastException on wrong type
    public synchronized <T> T getOrDefault(String key, Class<T> type, T defaultValue) { ... }
    public synchronized boolean containsKey(String key) { ... }
    public synchronized void remove(String key) { ... }
    public synchronized void clear() { ... }
    public synchronized Map<String, Object> snapshot() { ... } // unmodifiable copy
}
```

All methods are `synchronized` on the instance, so the tick thread and the callback thread can both access state safely.

### GameStatus, GameResult, GameSession

```java
public enum GameStatus { IDLE, RUNNING, PAUSED, FINISHED, STOPPED }

public record GameResult(String sessionId, String gameId, GameStatus finalStatus, List<Player> winners,
                         Map<String, Integer> scoreByPlayerId, Duration duration, Instant finishedAt) {
    public boolean hasWinner() { return !winners.isEmpty(); }
}

public interface GameSession {
    String sessionId();
    String gameId();
    GameStatus status();
    Optional<GameResult> result(); // present only after finish
    void stop();
}
```

`winSession(winners)` finishes with `FINISHED` and the given winners; `loseSession()` finishes with `FINISHED` and an empty winners list (so "lost" = finished without winners — check `hasWinner()`); `stopSession()` finishes with `STOPPED`.

### Player, PlayerRole, TileColor, TileEvent

```java
public record Player(String id, String name, PlayerRole role) {
    public static Player of(String name, PlayerRole role) { ... } // random UUID id
    public static Player solo(String name) { return of(name, PlayerRole.SOLO); }
}
public enum PlayerRole { SOLO, PLAYER_ONE, PLAYER_TWO, TEAM_A, TEAM_B, SPECTATOR }
public record Team(String name, List<Player> players) { public int size() { ... } }

public enum TileColor { OFF(0), RED(1), GREEN(2), BLUE(3), PINK(4), LIGHT_BLUE(5), YELLOW(6), WHITE(7);
    public int wireCode() { ... }
    public static TileColor fromWireCode(int code) { ... } } // unknown codes map to OFF

public record TileEvent(Position position, TileEventType type, Instant occurredAt, String sessionId) {
    public static TileEvent touch(Position p, String sessionId) { ... }
    public static TileEvent release(Position p, String sessionId) { ... }
}
public enum TileEventType { TOUCH, RELEASE, HOLD }
```

Note: the engine itself only ever emits `TOUCH` and `RELEASE` (see `TouchFrameRouter`); `HOLD` exists for games/hand-rolled producers (and `TouchHistory` treats it like `TOUCH` for active-touch tracking).

---

## GameEngine and GameSession - Lifecycle

### GameRegistry / DefaultGameRegistry

```java
public interface GameRegistry {
    void register(Game game);                              // singleton: same instance every session
    void register(GameDescriptor descriptor, GameFactory factory); // factory: fresh instance per session
    Optional<GameDescriptor> find(String gameId);
    List<GameDescriptor> listAll();                        // sorted by displayName
    Game instantiate(String gameId);                       // throws GameNotFoundException
    boolean isRegistered(String gameId);
}
```

`DefaultGameRegistry` is backed by a `ConcurrentHashMap`, so registration and lookup are thread-safe. `register` is idempotent per `gameId` (re-registering overwrites).

### GameEngineImpl

```java
public final class GameEngineImpl implements GameEngine, AutoCloseable {
    private final Map<String, GameSessionImpl> activeSessions = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> reaperTasks = new ConcurrentHashMap<>();
    private final AtomicReference<String> exclusiveSessionId = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final ScheduledExecutorService sessionReaper;   // "tileboard-session-reaper", daemon
    private final ExecutorService teardownExecutor;         // "tileboard-session-teardown", cached, daemon

    public String startGame(String gameId, List<Player> players) {
        Game game = registry.instantiate(gameId);           // throws GameNotFoundException
        validateBoardSize(game.descriptor());               // game.board_size_mismatch
        validatePlayers(game.descriptor(), players);        // player_count / duplicate_player_id
        String sessionId = UUID.randomUUID().toString();
        if (!exclusiveSessionId.compareAndSet(null, sessionId))
            throw new GameSessionException("game.board_already_owned", ...);
        GameSessionImpl session;
        try {
            session = new GameSessionImpl(sessionId, game, players, gateway, tickInterval, eventBus,
                    teardownExecutor, touchHistoryMaxSize, this::handleSessionTerminated, boardFrameBroadcaster);
        } catch (RuntimeException e) {
            exclusiveSessionId.compareAndSet(sessionId, null); // rollback
            throw e;
        }
        activeSessions.put(sessionId, session);
        ScheduledFuture<?> reaper = sessionReaper.schedule(() -> {
            GameStatus status = session.status();
            if (status == RUNNING || status == PAUSED) {
                log.warn("Session {} TTL ({}) exceeded, forcing cleanup", sessionId, sessionTtl);
                session.stop(); // synchronously triggers finishSession -> handleSessionTerminated
            }
        }, sessionTtl.toMillis(), MILLISECONDS);
        reaperTasks.put(sessionId, reaper);
        try { session.start(); }
        catch (RuntimeException e) { handleSessionTerminated(session); throw e; } // safety net
        return sessionId;
    }

    private void handleSessionTerminated(GameSessionImpl session) {
        activeSessions.remove(session.sessionId());
        exclusiveSessionId.compareAndSet(session.sessionId(), null);
        ScheduledFuture<?> reaper = reaperTasks.remove(session.sessionId());
        if (reaper != null) reaper.cancel(false);
    }

    public boolean stopGame(String sessionId) {
        GameSessionImpl s = activeSessions.get(sessionId);
        if (s == null) { log.warn("stopGame() called with unknown or already-finished sessionId={}", sessionId); return false; }
        s.stop();
        return true;
    }
}
```

**Concurrency notes:**
- `activeSessions`/`reaperTasks` are `ConcurrentHashMap` — safe for concurrent access from HTTP threads, the callback thread, and the reaper thread.
- `exclusiveSessionId` is an `AtomicReference` with `compareAndSet` — only one session owns the board at a time, without a global synchronized block. Rollback uses `compareAndSet(sessionId, null)` so a failed start never clears a *different* session's ownership.
- `sessionReaper`: single-thread scheduled daemon executor; each session gets its own TTL task (`sessionTtl`, engine default 1 hour, `tileboard-app` overrides it to 30 minutes via `application.yml`). A session that outlives its TTL is force-stopped, so a forgotten session can never leak.
- `teardownExecutor`: cached daemon pool used to offload tick-executor shutdown when `stop()` is called from the tick thread itself (a thread must never `awaitTermination` on its own executor).
- `closed`: `AtomicBoolean` — `close()` runs once; the `EngineFrameRouter` callback is guarded by `closed.get()` so a frame arriving after `close()` never touches a torn-down engine.
- Effective defaults when null/zero is passed: `tickInterval` 100 ms, `sessionTtl` 1 hour, `frameReassemblyTimeout` 500 ms, `touchHistoryMaxSize` 2000.

### GameSessionImpl

```java
public final class GameSessionImpl implements GameSession, GameContext {
    private final BoardChannel boardChannel;   // sized from game.descriptor() requiredWidth/Height
    private final GameState gameState = new GameState();
    private final FeatureBundle features;      // FeatureBundle.create(w, h, players, sessionId, maxSize, this::publishBoard)
    private final ScheduledExecutorService tickExecutor;  // "tileboard-tick-<sessionId>", null when ticks disabled
    private final ScheduledFuture<?> tickFuture;
    private final SessionLifecycle lifecycle = new SessionLifecycle();
    private volatile GameResult result;

    void start() {
        if (!lifecycle.start()) throw new GameSessionException("game.session_already_started", ...);
        features.timer().start();
        try {
            try { gateway.send(Command.START, CommandType.SET); }
            catch (RuntimeException e) { log.warn("failed to sent START command ..."); }
            game.onStart(this);
            eventBus.publish(SESSION_STARTED with snapshotForSse());
        } catch (RuntimeException e) {
            forceStop();
            throw new GameSessionException("game.start_failed", ..., e);
        }
    }

    public void handleTileEvent(TileEvent event) {
        if (lifecycle.current() != RUNNING) return;
        features.touchHistory().record(event);
        features.reactionSpeed().record(event);
        try { game.onTileEvent(this, event); } catch (RuntimeException e) { handleGameError(e); }
    }

    private void runTick() {
        if (lifecycle.current() != RUNNING) return;
        try {
            features.timer().checkExpiry();   // fires countdown expiry automatically every tick
            game.onTick(this);
            eventBus.publish(TICK with snapshotForSse());
        } catch (RuntimeException e) { handleGameError(e); }
    }

    private void finishSession(GameStatus finalStatus, List<Player> winners) {
        if (!lifecycle.finish(finalStatus)) return; // CAS -> runs EXACTLY ONCE
        features.timer().stop();
        cancelTick();
        features.closeAll(); // waves.close() + animations.shutdown()
        GameResult finalResult = new GameResult(sessionId, gameId(), finalStatus, winners,
                features.scores().allScores(), features.timer().elapsed(), Instant.now());
        this.result = finalResult;
        try { game.onStop(this, finalResult); } catch (RuntimeException e) { log.warn(...) }
        try { fillBoard(OFF); } catch (RuntimeException e) { log.warn(...) } // also emits BOARD_UPDATED (report=null)
        try { gateway.send(Command.STOP, CommandType.SET); } catch (RuntimeException e) { log.warn(...) }
        if (onTerminated != null) try { onTerminated.accept(this); } catch (RuntimeException e) { log.error(...) }
        eventBus.publish((finalStatus == FINISHED ? SESSION_FINISHED : SESSION_STOPPED) with snapshotForSse(finalResult));
    }
}
```

Notes:
- Hardware protocol: `START`/`SET` is sent on session start, `STOP`/`SET` on session end (both best-effort with warn logs).
- `snapshotForSse(GameResult report)` builds the `SessionSnapshot` carried by every event; only the single terminal publish carries the non-null `report` — even the intermediate `BOARD_UPDATED` from the final `fillBoard(OFF)` reports `null`.
- `cancelTick()`: cancels the tick future and shuts down the tick executor; when called *from* the tick thread, the blocking `awaitTermination` (500 ms, then `shutdownNow()`) is offloaded to `teardownExecutor` to avoid self-join deadlock.
- `SessionLifecycle`: `IDLE → RUNNING` via `start()`; `finish(target)` CAS-loops from `RUNNING`/`PAUSED` to the final status and returns `false` for any later call — so concurrent `winSession`/`loseSession`/`stopSession`/TTL-reaper invocations finish the session exactly once.

---

## BoardChannel - Board Publishing with Coalescing

```java
public final class BoardChannel {
    private final ReentrantLock stateLock = new ReentrantLock();
    private final Object gatewayWriteLock = new Object();
    private final Board<TileColor> buffer;          // logical state, guarded by stateLock
    private Board<TileColor> lastSentBoard;         // guarded by gatewayWriteLock

    public BoardChannel(int width, int height, TileGatewayClient gateway, TileCodec<TileColor> codec)
    public BoardChannel(int width, int height, TileGatewayClient gateway, TileCodec<TileColor> codec,
                        BoardFrameBroadcaster frameBroadcaster, String sessionId) // nullable broadcaster/id

    public Board<TileColor> publish(Board<TileColor> board) { copy into buffer under stateLock; sendLatest(); return snapshot; }
    public Board<TileColor> setTile(int row, int col, TileColor color) { ... }
    public Board<TileColor> fill(TileColor color) { ... }
    public void tryClear() { try { fill(OFF); } catch (RuntimeException ignored) {} } // teardown-safe
    public Board<TileColor> newEmptyBoard() { return new Board<>(width, height, OFF); }
    public Board<TileColor> snapshot() { return buffer.copy(); } // under stateLock, no hardware I/O

    private Board<TileColor> sendLatest() {
        Board<TileColor> latest;
        boolean actuallySent;
        synchronized (gatewayWriteLock) {
            latest = snapshot(); // re-read AFTER acquiring the lock!
            if (latest.equals(lastSentBoard)) actuallySent = false;
            else { gateway.sendBoard(DATA_OUT, SET, latest, codec); lastSentBoard = latest; actuallySent = true; }
        }
        if (actuallySent && frameBroadcaster != null) frameBroadcaster.dispatch(sessionId, latest); // outside the lock
        return latest;
    }
}
```

**Why snapshot twice?** This is intentional and called **coalescing semantics**:

1. Thread A calls `setTile(0,0,RED)` → changes `buffer` to RED, takes snapshot (RED)
2. Before A reaches `sendLatest()`, Thread B calls `setTile(0,1,GREEN)` → changes `buffer` to (RED,GREEN)
3. A enters `sendLatest()`, but instead of sending its older snapshot (only RED), it re-reads `snapshot()` which is (RED,GREEN) → the most recent consistent state is sent

This means callers must not assume the `Board` returned by `setTile`/`fill`/`publish` is byte-for-byte identical to what was transmitted. The gateway only ever needs the latest board, and this avoids sending superseded frames out of order.

**Equality caveat:** the dedup check uses `Board.equals`, and `Board` does not override `equals` (identity comparison), so in practice consecutive distinct snapshots are always transmitted. The re-read/coalescing behavior above still holds.

**Role of locks:**
- `stateLock` (ReentrantLock): protects `buffer` (mutable Board)
- `gatewayWriteLock` (synchronized Object): serializes writes to the wire, prevents byte interleaving
- `frameBroadcaster.dispatch` deliberately runs *outside* `gatewayWriteLock`: SSE fan-out to many subscribers must never stall the hardware write path. Only actually-transmitted frames are dispatched (suppressed duplicates never reach listeners).

**BoardFrameBroadcaster / BoardFrameListener:** a framework-free, application-lifetime pub/sub (`CopyOnWriteArrayList`, `subscribe` returns an unsubscribe `Runnable`, best-effort dispatch that logs and skips throwing listeners). Listener methods run on the sender's thread after the write lock is released, so implementations must be fast/non-blocking and never throw.

---

## FeatureBundle - Ready-Made Features

`FeatureBundle` is a record holding all per-session features:

```java
public record FeatureBundle(
    ScoreSystem scores, HealthSystem health, LevelSystem levels, ComboTracker combos,
    GameTimer timer, TouchHistory touchHistory, TouchAnalyzer touchAnalyzer,
    BoardFeature board, NeighborFinder neighbors, PatternMatcher patterns,
    RandomFeature random, WaveGenerator waves, MemoryFeature memory,
    ReactionSpeedTracker reactionSpeed, GraphFeature graph, AnimationSystem animations) {

    static FeatureBundle create(int w, int h, List<Player> players, String sessionId,
                                int touchHistoryMaxSize, Consumer<Board<TileColor>> publisher) {
        TouchHistory th = new TouchHistory(sessionId, touchHistoryMaxSize);
        return new FeatureBundle(
            new ScoreSystem(players), new HealthSystem(players), new LevelSystem(), new ComboTracker(),
            new GameTimer(), th, new TouchAnalyzer(th),
            new BoardFeature(w, h), new NeighborFinder(w, h, FOUR_WAY), new PatternMatcher(),
            new RandomFeature(w, h), new WaveGenerator(w, h, publisher), new MemoryFeature(),
            new ReactionSpeedTracker(), new GraphFeature(w, h), new AnimationSystem(w, h, publisher));
    }

    void closeAll() { waves.close(); animations.shutdown(); }
}
```

Notes: every feature is constructed with default (no-op) `onChange` hooks — the `SCORE_CHANGED`/`LEVEL_UP`/… event types exist on the bus but no feature currently publishes them (reserved for future wiring). `closeAll()` is called exactly once from `finishSession`.

### ScoreSystem - Thread-Safe Scoring

```java
public final class ScoreSystem {
    private final Map<String, AtomicInteger> scores = new ConcurrentHashMap<>();
    public ScoreSystem(List<Player> players) { this(players, () -> {}); }
    public ScoreSystem(List<Player> players, Runnable onChange) { ... } // called on caller's thread when any score changes
    public int add(String playerId, int delta) { ... }      // onChange only when delta != 0
    public int subtract(String playerId, int delta) { return add(playerId, -delta); }
    public int get(String playerId) { ... }
    public void set(String playerId, int value) { ... }     // always fires onChange
    public void reset(String playerId) { set(playerId, 0); }
    public void resetAll() { ... }                          // zeroes known players, fires onChange once
    public Optional<String> leader() { ... }                // id with highest score
    public Map<String, Integer> allScores() { ... }         // unmodifiable LinkedHashMap snapshot
}
```

- `ConcurrentHashMap` + `AtomicInteger` → `add()` is thread-safe without a global lock (CAS inside `AtomicInteger`).
- `computeIfAbsent` guarantees that two threads concurrently resolving a new playerId create only one `AtomicInteger`.

### HealthSystem - Player Health

```java
public HealthSystem(List<Player> players) / (List<Player> players, int defaultMaxHealth) / (..., Runnable onChange)
public int current(String playerId);   public int max(String playerId);
public boolean damage(String playerId);                 // 1 damage; returns true if this killed the player
public boolean damage(String playerId, int amount);
public void heal(String playerId, int amount);          // clamped to max
public boolean isAlive(String playerId);
public boolean allDead();
public void resetAll(int value);
public record Status(int current, int max) {}
public Map<String, Status> snapshot();
```

### LevelSystem - Levels and Speed

```java
public LevelSystem() / public LevelSystem(Runnable onChange)
public int currentLevel();   // starts at 1
public int advance();        // level + 1
public void setLevel(int lvl);
public void setSpeedScaler(IntUnaryOperator scaler);
public int currentSpeed();   // scaler applied to currentLevel
public void reset();         // back to level 1
```

### ComboTracker - Combos

```java
public ComboTracker() / public ComboTracker(Runnable onChange)
public void setComboTimeout(long millis);
public int hit();            // combo + 1 (timeout-aware)
public void reset();
public int current();
public int max();            // best combo this session
public int multiplier(int threshold);
```

### GameTimer - Wall-Clock Timer

```java
public final class GameTimer {
    private final AtomicReference<Runnable> onExpire = new AtomicReference<>();
    private final AtomicBoolean expiryNotified = new AtomicBoolean(false);
    private final Runnable engineExpiryNotifier;   // default no-op; fires once per expiry for future TIMER_EXPIRED wiring
    private volatile Instant startedAt;
    private volatile Instant stoppedAt;
    private volatile Duration countdownTarget;

    public void start() { startedAt = now; stoppedAt = null; expiryNotified.set(false); }
    public void stop() { if (startedAt != null && stoppedAt == null) stoppedAt = now; }
    public void startCountdown(Duration duration, Runnable onExpireCallback) { countdownTarget = duration; onExpire.set(cb); start(); }
    public Duration elapsed() { ... }
    public Duration remaining() { ... }            // ZERO when no countdown or when expired
    public boolean isExpired() { return countdownTarget != null && remaining().isZero(); }
    public boolean hasCountdown() { return countdownTarget != null; } // distinguishes "never started" from "ran out"
    public Duration countdownDuration() { return countdownTarget; }   // null when no countdown
    public void checkExpiry() {
        if (!isExpired()) return;
        if (expiryNotified.compareAndSet(false, true)) engineExpiryNotifier.run();
        Runnable cb = onExpire.getAndSet(null);
        if (cb != null) cb.run();
    }
    public void reset() { startedAt = stoppedAt = countdownTarget = null; onExpire.set(null); expiryNotified.set(false); }
}
```

- `volatile` fields give lock-free cross-thread visibility.
- `expiryNotified.compareAndSet(false, true)` + `onExpire.getAndSet(null)` guarantee both the engine notifier and the game callback fire **exactly once**, even under concurrent `checkExpiry()` calls.
- `checkExpiry()` is called every tick by `GameSessionImpl.runTick()` → automatic countdown without a separate thread.

### TouchHistory / TouchAnalyzer - Touch History

```java
public TouchHistory(String sessionId) / (String sessionId, int maxSize)  // engine default maxSize 2000
public void record(TileEvent event);          // TOUCH/HOLD mark active; RELEASE clears; counts totalTouches
public int totalTouches();
public Optional<TileEvent> last();
public Optional<Position> lastTouchedPosition();
public List<TileEvent> activeTouches();       // currently pressed (TOUCH/HOLD not yet RELEASEd)
public List<TileEvent> recent(int limit);     // newest-first history slice
public TouchSequence sequence();
public List<Position> positionOrder();
public Set<Position> distinctPositions();
public void reset();
```

`TouchAnalyzer(history)`: `averageInterTouchGap()`, `matchesSequence(pattern)`, `allUnique()`, `lastReactionTime()`.

### NeighborFinder / GridTopology / Adjacency - Neighbors

```java
public enum Adjacency { FOUR_WAY, EIGHT_WAY, DIAGONAL_ONLY }
public NeighborFinder(int width, int height, Adjacency adjacency) // FeatureBundle uses FOUR_WAY
public List<Position> of(Position position);
public List<Position> of(int row, int col);
public List<Position> connectedRegion(Position start, Predicate<Position> passable); // BFS flood-fill
```

`GridTopology(w, h)`: `inBounds(row, col)`, `neighbors(row, col, adjacency)`. Immutable after construction → thread-safe.

### BoardFeature - Board Queries

`BoardFeature(w, h)`: `find(board, predicate)`, `findByColor(board, color)`, `allMatch(...)`, `noneMatch(...)`, `countByColor(board, color)`, `isValid(position)`, `manhattanDistance(a, b)`, `chebyshevDistance(a, b)`.

### PatternMatcher - Pattern Matching

`tailMatches(actual, pattern)`, `exactMatch(actual, pattern)`, `containsSequence(actual, pattern)`, `cyclicMatch(actual, pattern)` — all pure functions over `List<Position>`.

### RandomFeature - Random Helpers

`RandomFeature(w, h[, seed])`: `randomPosition()`, `randomPositions(count)`, `randomColor(exclude...)`, `pick(list)`, `chance(probability)`, `reseed(seed)`.

### WaveGenerator - Wave Effects

`WaveGenerator(w, h, boardPublisher)`: `sweepDown(color, delayMs)`, `ripple(center, color, delayMs)`, `blink(on, off, times, intervalMs)` (each returns `CompletableFuture<Void>`), `cancelCurrent()`, `close()` (called by `FeatureBundle.closeAll()`).

### MemoryFeature - Memory Games

`setTarget(sequence)`, `addInput(position)`, `isComplete()`, `isCorrectSoFar()`, `isFullyCorrect()`, `targetLength()`, `inputLength()`, `resetInput()`, `target()`, plus static `MemoryEvaluator.isCorrectSoFar/isFullyCorrect`.

### ReactionSpeedTracker - Reaction Stats

`stimulus()` (mark stimulus time), `record(event)` (called automatically by `GameSessionImpl.handleTileEvent`), `lastReaction()`, `bestReaction()`, `averageReactionMillis()`, `reactionCount()`, `reset()`.

### GraphFeature - Paths and Regions

`GraphFeature(w, h)`: `shortestPath(...)` (BFS shortest path), `connectedComponents(...)`.

### AnimationSystem / AnimationRegistry / BoardAnimation

`AnimationSystem` is detailed in the next section. `AnimationRegistry`/`BoardAnimation` are supporting types for registering custom board animations.

---

## AnimationSystem

One of the most complex classes in the engine. Exactly one animation runs at a time; starting a new one cooperatively cancels the previous one.

### Overall Design

```java
public final class AnimationSystem {
    private final ExecutorService executor = newSingleThreadExecutor(daemon, "tileboard-animation");
    private final Random rng;
    private final AtomicLong generation = new AtomicLong(0);
    private final Object runLock = new Object();
    private volatile Future<?> currentTask;
    private volatile CompletableFuture<Void> currentResult;

    private CompletableFuture<Void> run(Consumer<RunToken> body) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        synchronized (runLock) {
            if (currentTask != null) currentTask.cancel(true);
            if (currentResult != null) currentResult.cancel(false);
            long myGen = generation.incrementAndGet();
            RunToken token = new RunToken(myGen);
            currentResult = result;
            try {
                currentTask = executor.submit(() -> {
                    try {
                        body.accept(token);
                        if (token.isCancelled()) result.cancel(false);
                        else result.complete(null);
                    } catch (AnimationCancelledException cancelled) { result.cancel(false); }
                    catch (RuntimeException e) { result.completeExceptionally(e); }
                    finally { Thread.interrupted(); } // clear interrupt status for thread reuse
                });
            } catch (RejectedExecutionException e) { result.completeExceptionally(e); }
        }
        return result;
    }

    public void cancelCurrent() {  // idempotent no-op when idle
        synchronized (runLock) {
            generation.incrementAndGet();
            if (currentTask != null) currentTask.cancel(true);
            if (currentResult != null) currentResult.cancel(false);
            currentTask = null; currentResult = null;
        }
    }

    public void shutdown() { cancelCurrent(); executor.shutdownNow(); executor.awaitTermination(1, SECONDS); }
}
```

**Generation-based cooperative cancellation:**
- Each new animation does `generation.incrementAndGet()` and creates a `RunToken` with that generation.
- `RunToken.isCancelled()` checks `generation.get() != myGeneration` → a newer animation (or `cancelCurrent()`) has superseded this one.
- `sleep(ms)` returns `false` when cancelled; `pause(ms)`/`show(board)` throw the cheap, stacktrace-less `AnimationCancelledException`, which `run` translates into a *cancelled* future (never a normal completion, never an error).
- `shutdown()` (called via `FeatureBundle.closeAll()` at session end) cancels and stops the executor.

**CompletableFuture contract:** completes normally when the body finishes, completes as *cancelled* when superseded/cancelled/shut down, completes exceptionally only on unexpected `RuntimeException` (or `RejectedExecutionException` after shutdown). This allows chaining: `playCountdown().thenRun(() -> startGame())`. There is also a package-visible constructor accepting a seeded `Random` for deterministic tests.

### Animation Types

```java
public CompletableFuture<Void> playCountdown()                 // 1000 ms per digit
public CompletableFuture<Void> playCountdown(long digitDurationMs)
public CompletableFuture<Void> playWinAnimation()              // default RADIAL_BURST
public CompletableFuture<Void> playWinAnimation(WinAnimationType type)
public CompletableFuture<Void> playLoseAnimation()             // default FADE_TO_RED
public CompletableFuture<Void> playLoseAnimation(LoseAnimationType type)
public CompletableFuture<Void> playStandbyAnimation()          // default BREATHING
public CompletableFuture<Void> playStandbyAnimation(StandbyAnimationType type)

public enum WinAnimationType { RADIAL_BURST, RAINBOW_SWEEP, SPARKLE, FIREWORKS }
public enum LoseAnimationType { FADE_TO_RED, DESCENDING_CURTAIN, CRUMBLE, PULSE_RED }
public enum StandbyAnimationType { BREATHING, CORNER_PULSE, WAVE_BORDER, RANDOM_TWINKLE }
```

#### Countdown - Before Game Start

Boards smaller than 3 wide or 5 tall get `playSimpleCountdown`: the whole board lights **RED → BLUE → GREEN** (one `digitDurationMs` each), then clears. Larger boards get `playScalableCountdown`: centered 5×3 digit patterns for **3 (RED) → 2 (YELLOW) → 1 (GREEN)**, then 3× green blink (150 ms on / 150 ms off).

#### Win - Victory

- `RADIAL_BURST` (default): expanding 2-cell ring bands from the center through YELLOW/GREEN/BLUE/PINK/LIGHT_BLUE (100 ms per radius), 500 ms hold, clear.
- `RAINBOW_SWEEP`: two left-to-right column sweeps through a 5-color rainbow (80 ms per column), clear.
- `SPARKLE`: 15 cycles of 5–9 random YELLOW/WHITE/LIGHT_BLUE sparks (120 ms per cycle), clear.
- `FIREWORKS`: 3 fireworks at random interior positions, each expanding rings radius 0–3 (100 ms per ring) + 200 ms hold, clear.

#### Lose - Defeat

- `FADE_TO_RED` (default): 3 phases of random 30 % RED fill (300 ms each), full RED, 1 s hold, clear.
- `DESCENDING_CURTAIN`: RED rows accumulate top-to-bottom (200 ms per row), 500 ms hold, clear.
- `CRUMBLE`: full YELLOW 300 ms, then shuffled per-tile crumble to RED (showing ~20 % of steps at 50 ms), 500 ms hold, clear.
- `PULSE_RED`: 4× full-RED 200 ms / clear 200 ms.

#### Standby - Idle (Infinite Until Cancelled)

All four loop forever and only exit via cooperative cancellation (`sleep` returning false / `pause`/`show` throwing):
- `BREATHING` (default): alternating BLUE/LIGHT_BLUE — corners (500 ms), then full border on ≥3×3 boards (500 ms). Note it publishes via `boardPublisher` directly.
- `CORNER_PULSE`: cycling GREEN/BLUE/PINK/YELLOW 3×3 blocks in successive corners (300 ms each).
- `WAVE_BORDER`: period-3 marching LIGHT_BLUE border pattern (200 ms per step; offset wraps modulo 3 so endless runs never overflow).
- `RANDOM_TWINKLE`: every 300 ms, 2–4 random WHITE twinkles.

Typical "idle before start" usage:

```java
try {
    ctx.animations().playStandbyAnimation(StandbyAnimationType.BREATHING).get(2, TimeUnit.SECONDS);
} catch (TimeoutException e) {
    ctx.animations().cancelCurrent();
}
```

### RunToken

```java
public final class RunToken {   // methods are package-visible by design
    boolean isCancelled() { return generation.get() != myGeneration; }
    boolean sleep(long ms) {   // false when cancelled (or interrupted); clears nothing
        if (isCancelled()) return false;
        if (ms > 0) try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        return !isCancelled();
    }
    void pause(long ms) { if (!sleep(ms)) throw new AnimationCancelledException(); }
    void show(Board<TileColor> board) {
        if (isCancelled()) throw new AnimationCancelledException();
        boardPublisher.accept(board);
    }
    void clear() { show(new Board<>(width, height, OFF)); }
}
```

---

## EventBus - Thread-Safe Event System

### Design

```java
public interface GameEventBus {
    void publish(GameEvent event);
    Runnable subscribe(GameEventListener listener);                                   // everything
    Runnable subscribe(GameEventType type, GameEventListener listener);               // one type
    Runnable subscribeSession(String sessionId, GameEventListener listener);         // one session
    Runnable subscribe(GameEventListener listener, SubscriptionOptions options);     // full control
}   // every subscribe returns an unsubscribe Runnable

public record GameEvent(String id, GameEventType type, String sessionId, String gameId,
                        SessionSnapshot payload, Instant occurredAt) {
    public static GameEvent of(GameEventType type, String sessionId, String gameId, SessionSnapshot payload) { ... }
}
public enum GameEventType {
    SESSION_STARTED, SESSION_FINISHED, SESSION_STOPPED,
    TILE_TOUCHED, BOARD_UPDATED, SCORE_CHANGED, LEVEL_UP, HEALTH_CHANGED, COMBO_HIT,
    TIMER_EXPIRED, TICK, CUSTOM
}
public record SubscriptionOptions(GameEventType filterType, String filterSessionId,
                                  int queueCapacity, EventOverflowPolicy policy) {
    public static SubscriptionOptions defaults(int queueCapacity) { ... } // DROP_OLDEST, no filter
    public SubscriptionOptions withPolicy(...) / withType(...) / withSession(...) / withQueueCapacity(...)
}
public enum EventOverflowPolicy { BLOCK, DROP_OLDEST }
```

**Which events are actually emitted today?** `GameSessionImpl` publishes exactly five: `SESSION_STARTED`, `BOARD_UPDATED` (on every `publishBoard`/`setTile`/`fillBoard`), `TICK`, `SESSION_FINISHED`, `SESSION_STOPPED`. The remaining types (`TILE_TOUCHED`, `SCORE_CHANGED`, `LEVEL_UP`, `HEALTH_CHANGED`, `COMBO_HIT`, `TIMER_EXPIRED`, `CUSTOM`) are defined for future feature wiring — the feature `onChange` hooks document them, but `FeatureBundle.create` currently wires no-op hooks, so subscribing to them yields nothing yet.

```java
public final class GameEventBusImpl implements GameEventBus, AutoCloseable {
    private final CopyOnWriteArrayList<Subscription> subscriptions = new CopyOnWriteArrayList<>();
    private final int defaultQueueCapacity;       // 256 via AutoConfiguration
    private final EventOverflowPolicy defaultPolicy; // DROP_OLDEST via AutoConfiguration
    private final Duration blockTimeout;          // 200 ms default
    private final AtomicLong droppedEvents = new AtomicLong();

    public GameEventBusImpl() { this(256, DROP_OLDEST); }
    public void publish(GameEvent event) {
        for (Subscription sub : subscriptions) if (sub.matches(event)) sub.offer(event);
    }
    // subscribe(...) creates a Subscription (single daemon worker "tileboard-eventbus-subscriber"),
    // starts its drainLoop, and returns an unsubscribe Runnable that removes + stops it.
    public long droppedEventCount() { ... }
    public int subscriberCount() { ... }
    public void close() { subscriptions.forEach(Subscription::stop); subscriptions.clear(); }

    private final class Subscription {
        // BLOCK -> LinkedBlockingQueue(capacity); DROP_OLDEST -> ArrayDeque + ringLock + ringAvailable
        private void offerBlocking(GameEvent event) {
            if (!blockingQueue.offer(event, blockTimeoutNanos, NANOSECONDS)) {
                log.warn("Subscriber did not drain within {}ns; dropping event {}", ...);
                droppedEvents.incrementAndGet();
            }
        }
        private void offerDropOldest(GameEvent event) {
            ringLock.lock();
            try {
                while (ring.size() >= options.queueCapacity()) {
                    if (ring.pollFirst() != null) { droppedEvents.incrementAndGet(); ringAvailable.tryAcquire(); }
                }
                ring.addLast(event);
            } finally { ringLock.unlock(); }
            ringAvailable.release();
        }
        private GameEvent takeDropOldest(long timeoutMs) throws InterruptedException {
            if (!ringAvailable.tryAcquire(timeoutMs, MILLISECONDS)) return null;
            ringLock.lock();
            try { return ring.pollFirst(); }
            finally { ringLock.unlock(); }
        }
        void drainLoop() {
            while (running) {
                GameEvent event = (policy == BLOCK) ? blockingQueue.poll(1, SECONDS) : takeDropOldest(1000);
                if (event == null) continue;
                try { listener.onEvent(event); } catch (Throwable t) { log.warn(...) } // never dies
            }
        }
        void stop() { running = false; worker.shutdownNow(); }
    }
}
```

### Two Overflow Policies

1. **BLOCK:** `LinkedBlockingQueue` with bounded capacity. `offer` waits up to `blockTimeout` (default 200 ms). If the subscriber still hasn't drained, the event is dropped and `droppedEvents` incremented — the publisher is never blocked forever.
2. **DROP_OLDEST (default):** `ArrayDeque` + `ReentrantLock` + `Semaphore`.
   - `ringAvailable` counts items in the ring: every add `release()`s, every take `acquire()`s.
   - When full, the oldest item (`pollFirst`) is evicted and `tryAcquire()` consumes its permit, so `availablePermits() == ring.size()` always holds and the consumer never wakes up to an empty ring.

**Why Semaphore + Deque instead of BlockingQueue for DROP_OLDEST?** Because `LinkedBlockingQueue` cannot drop the oldest while appending the newest. `ArrayDeque` allows that but is not thread-safe, hence the `ReentrantLock` + `Semaphore` combination.

**CopyOnWriteArrayList for subscriptions:** add/remove is rare, `publish` per event is frequent — COWAL is lock-free for reading.

**drainLoop:** each subscription owns a single-thread daemon worker that polls (1 s timeout) and delivers to the listener; a throwing listener is caught and logged, so one buggy subscriber can never kill the bus.

---

## EngineFrameRouter and TouchFrameRouter

### EngineFrameRouter

A `FrameListener` (constructor: `width, height, touchBoardConsumer, reassemblyTimeout` — timeout must be positive) that intercepts `DATA_IN` frames and decodes them into `Board<Boolean>` touch state:

- Empty payloads are ignored.
- Payload of exactly `width*height` bytes → decoded immediately with `TileCodec.booleanState()` (any buffered partial data is discarded with a warning).
- Larger payloads → discarded with a warning and the partial buffer reset.
- Smaller payloads → treated as chunks of one board: appended under an internal lock; a chunk that would overflow resets the buffer ("resynchronising"); when the buffer reaches exactly `width*height`, it is decoded and forwarded. A partial buffer older than `reassemblyTimeout` (engine default 500 ms) is dropped as stale on the next chunk.

Decode failures are logged and dropped, never thrown to the gateway thread.

### TouchFrameRouter

Converts a complete `Board<Boolean>` into `TileEvent`s and delivers them **only to the current exclusive owner** session:

```java
public final class TouchFrameRouter {
    public TouchFrameRouter(Function<String, Optional<GameSessionImpl>> sessionLookup,
                            Supplier<Optional<String>> exclusiveOwnerSupplier) { ... }

    public void route(Board<Boolean> touchBoard) {
        exclusiveOwnerSupplier.get().flatMap(sessionLookup).ifPresent(session -> deliver(session, touchBoard));
    }

    private void deliver(GameSessionImpl session, Board<Boolean> touchBoard) {
        for (Position pos : touchBoard.positionsWhere(TRUE::equals))   // every lit cell...
            if (inSessionBounds) session.handleTileEvent(TileEvent.touch(pos, sessionId));
        for (Position pos : touchBoard.positionsWhere(FALSE::equals))  // ...and every unlit cell
            if (inSessionBounds) session.handleTileEvent(TileEvent.release(pos, sessionId));
    }
}
```

Notes: every `DATA_IN` board produces a full scan — one `TOUCH` per touched cell plus one `RELEASE` per untouched cell (positions outside the session's board are skipped). Games that only care about presses must filter `event.type() == TOUCH` (and optionally `HOLD`) themselves. There is no per-session fan-out: with a single exclusive board owner, routing to anyone else would be wrong.

---

## SSE - Streaming to Frontend

```
GameSessionImpl.publishBoard()/setTile()/fillBoard()/runTick()/finishSession()
  → eventBus.publish(GameEvent with SessionSnapshot)
  → SseGameEventPublisher/GameEventSseEmitter → SseEmitter → HTTP client (text/event-stream)
```

- **`SseGameEventPublisher`** (in the `spring` package, also a `@Component`): `forSession(sessionId)`, `forEventType(type)`, `global()` — thin delegates to `GameEventSseEmitter.build(...)`.
- **`GameEventSseEmitter`** (in the `sse` package): builds an infinite-timeout (`0L`) `SseEmitter` with a per-client bus subscription (queue capacity 32, `DROP_OLDEST`, optional type/session filter) and a `: ping` comment heartbeat every 15 seconds (`tileboard-sse-heartbeat` scheduler). Any write failure tears down the subscription and completes the emitter; completion/timeout/error callbacks all release resources idempotently via `AtomicReference.getAndSet(null)`.
- **SSE contract:** `SseGameEvent(sessionId, gameId, type, data: SessionSnapshot, timestamp)` serialized with Jackson (`JavaTimeModule`, ISO dates, never timestamps). The SSE *event name* is the `SseGameEventType`, mapped from the internal type:

| Internal `GameEventType` | SSE `SseGameEventType` |
|------|------|
| `TICK` | `TICK` |
| `BOARD_UPDATED` | `BOARD_UPDATE` |
| `SCORE_CHANGED` | `SCORE_UPDATE` |
| `SESSION_STARTED`, `SESSION_FINISHED`, `SESSION_STOPPED` | `SESSION_LIFECYCLE` |
| anything else | `GAME_STATE` |

`SseGameEventType`: `GAME_STATE, BOARD_UPDATE, SCORE_UPDATE, SESSION_LIFECYCLE, TICK, CUSTOM`.

### SessionSnapshot - The SSE Payload

Every event carries a full `SessionSnapshot` (13 fields, defensively copied into unmodifiable views — safe to hand across threads):

```java
public record SessionSnapshot(
    Map<String, Integer> scores, int level, String status, long elapsedSeconds,
    List<List<String>> board,                    // row-major TileColor names ("RED", "OFF", ...)
    Long remainingSeconds, Long countdownTotalSeconds,  // null when no countdown is active
    Map<String, HealthSystem.Status> health,
    List<TouchInfo> recentTouches, long totalTouches,   // recentTouches from activeTouches()
    ComboInfo combo, ReactionInfo reaction,
    GameResult report) {                         // null until the terminal event
    public record TouchInfo(int row, int col, String eventType, Instant occurredAt) {}
    public record ComboInfo(int current, int max) { public static final ComboInfo EMPTY = ...; }
    public record ReactionInfo(Double lastMs, Double bestMs, Double averageMs, long count) {
        public static final ReactionInfo EMPTY = new ReactionInfo(null, null, null, 0L); }
    public static Builder builder() { ... }      // every feature field defaults to empty/not-applicable
    // + legacy 4-arg and 5-arg constructors (board empty / features empty) for backward compatibility
}
```

Feature-derived fields are best-effort: a session that never starts a countdown reports `remainingSeconds == null` rather than omitting the field, so consumers always deserialize the same shape.

---

## Spring Layer - AutoConfiguration

```java
@AutoConfiguration
@EnableConfigurationProperties(TileboardEngineProperties.class)
public class TileboardEngineAutoConfiguration {

    @Bean @ConditionalOnMissingBean
    public GameEventBusImpl gameEventBus(TileboardEngineProperties props) {
        return new GameEventBusImpl(props.getEventBusQueueCapacity(), DROP_OLDEST);
    }

    @Bean @ConditionalOnMissingBean
    public BoardFrameBroadcaster boardFrameBroadcaster() { return new BoardFrameBroadcaster(); }

    @Bean @ConditionalOnMissingBean
    public GameRegistry gameRegistry(@Autowired(required = false) List<Game> games) {
        GameRegistry registry = new DefaultGameRegistry();
        if (games == null || games.isEmpty()) log.warn("No Game beans found ...");
        else games.forEach(game -> { registry.register(game); log.info("Auto-registered game: '{}' ({})", ...) });
        return registry;
    }

    @Bean @ConditionalOnMissingBean @DependsOn("gameEventBus")
    public GameEngineManager gameEngineManager(GameRegistry registry, GameEventBus eventBus,
            BoardFrameBroadcaster boardFrameBroadcaster, TileboardEngineProperties props) {
        return new GameEngineManager(registry, eventBus, boardFrameBroadcaster, props);
    }

    @Bean @ConditionalOnMissingBean
    public SseGameEventPublisher sseGameEventPublisher(GameEventBus eventBus,
            ScheduledExecutorService tileboardSseHeartbeatScheduler) { ... }

    @Bean @ConditionalOnBean(MeterRegistry.class)
    public GameEngineMetricsBinder gameEngineMetricsBinder(MeterRegistry registry, GameEngineManager manager, GameEventBusImpl bus) { ... }

    @Bean(destroyMethod = "shutdown") @ConditionalOnMissingBean(name = "tileboardSseHeartbeatScheduler")
    public ScheduledExecutorService tileboardSseHeartbeatScheduler() { ... } // daemon "tileboard-sse-heartbeat"
}
```

Registered via `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

- All beans are `@ConditionalOnMissingBean` → defining your own bean makes auto-config back off. (Note `SseGameEventPublisher` is additionally annotated `@Component`, so in a component-scanned app the `@Bean` method backs off in favor of the component.)
- `gameRegistry` auto-registers every `Game` bean in the context → defining a game as `@Bean`/`@Component` is enough for it to appear in listings.
- `GameEngineMetricsBinder` is only created when a Micrometer `MeterRegistry` exists, and binds three gauges: `tileboard.engine.active_sessions`, `tileboard.eventbus.dropped_events`, `tileboard.eventbus.subscribers`.

### GameEngineManager - Bridge Between Spring and Framework-Free Engine

```java
public class GameEngineManager {
    private volatile GameEngineImpl engine;
    // tickInterval, sessionTtl, frameReassemblyTimeout, touchHistoryMaxSize copied from props at construction

    public GameEngineManager(GameRegistry registry, GameEventBus eventBus, TileboardEngineProperties props)
    public GameEngineManager(GameRegistry registry, GameEventBus eventBus,
                             BoardFrameBroadcaster boardFrameBroadcaster, TileboardEngineProperties props)

    @EventListener
    public synchronized void onGatewayConnected(GatewayConnectedEvent event) {
        if (engine != null) { log.warn("... already bound ..."); shutdownCurrentEngine(); }
        engine = new GameEngineImpl(registry, event.client(), eventBus, boardFrameBroadcaster,
                tickInterval, sessionTtl, frameReassemblyTimeout, touchHistoryMaxSize,
                event.boardWidth(), event.boardHeight());
        log.info("Game engine bound to the newly connected tile gateway ({}x{})", ...);
    }

    @EventListener
    public synchronized void onGatewayDisconnected(GatewayDisconnectedEvent event) { shutdownCurrentEngine(); }

    private void shutdownCurrentEngine() {
        GameEngineImpl current = this.engine;
        if (current == null) { log.debug("... nothing to do"); return; }
        this.engine = null; // visible to current()/require() immediately, BEFORE the slow close()
        List<GameSession> sessions = current.activeSessions();
        try { current.close(); } catch (RuntimeException e) { log.warn(...) }
        log.info("Game engine unbound{}", sessions.isEmpty() ? "" : " (" + sessions.size() + " active session(s) stopped)");
    }

    public synchronized Optional<GameEngine> current() { return Optional.ofNullable(engine); }
    public synchronized GameEngine require() { if (engine == null) throw new EngineNotReadyException(); return engine; }

    @PreDestroy
    public synchronized void shutdownOnContextClose() { shutdownCurrentEngine(); }
}
```

**Concurrency notes:**
- `engine` is `volatile` (lock-free visibility) with `synchronized` writers — connect/disconnect can never race.
- `shutdownCurrentEngine` is null-safe and idempotent → duplicate/out-of-order disconnect events are safe no-ops.
- `engine = null` *before* `close()` → `current()`/`require()` never observe a half-closed engine.
- `@PreDestroy` guarantees the engine and its daemon threads are released when the Spring context stops, even if no disconnect event ever arrived.
- `GatewayConnectedEvent(client, boardWidth, boardHeight)` / `GatewayDisconnectedEvent()` live in this module's `spring` package (not in the app) so both app and engine share one definition without a circular dependency.

### TileboardEngineProperties

```java
@ConfigurationProperties(prefix = "tileboard.engine")
public final class TileboardEngineProperties {
    private Duration tickInterval = Duration.ofMillis(100);
    private Duration sessionTtl = Duration.ofHours(1);       // NOTE: 1 hour here; tileboard-app overrides to 30m
    private Duration frameReassemblyTimeout = Duration.ofMillis(500);
    private int eventBusQueueCapacity = 256;
    private int touchHistoryMaxSize = 2_000;
    // standard getters/setters
}
```

### Engine Exceptions

All extend `GameEngineException` (which extends `LocalizableException` from `tileboard-serial-protocol`, so they carry `errorCode`/`args` for `tileboard-app`'s i18n handler):

| Exception | errorCode | Meaning |
|------|------|------|
| `EngineNotReadyException` | `engine.not_ready` | `require()` with no bound gateway |
| `GameNotFoundException` | `game.not_found` (arg: gameId) | `instantiate()` of an unregistered game |
| `GameSessionException` | `game.*` (several) | start/validation/session failures |
| `GameEngineException` | (base) | catch-all parent |

---

## Step-by-Step Game Creation Tutorial

### Step 1: Create Game Class

```java
public class MyFirstGame implements Game {

    private final GameDescriptor descriptor = GameDescriptor.builder("my-first-game", "My First Game")
        .category("TUTORIAL")
        .description("My first game")
        .boardSize(8, 8)   // MUST match the connected device configuration
        .players(1, 1)
        .build();

    @Override public GameDescriptor descriptor() { return descriptor; }

    @Override
    public void onStart(GameContext ctx) {
        ctx.fillBoard(TileColor.OFF);
        ctx.scores().resetAll();
        ctx.setTile(0, 0, TileColor.GREEN);
    }

    @Override
    public void onTileEvent(GameContext ctx, TileEvent event) {
        if (event.type() != TileEventType.TOUCH) return; // RELEASE events are delivered too
        ctx.setTile(event.position().row(), event.position().col(), TileColor.RED);
        String playerId = ctx.players().get(0).id();
        ctx.scores().add(playerId, 1);

        if (ctx.scores().get(playerId) >= 10) {
            ctx.animations().playWinAnimation().thenRun(() -> ctx.winSession(ctx.players()));
        }
    }

    @Override
    public void onStop(GameContext ctx, GameResult result) {
        ctx.fillBoard(TileColor.OFF);
    }
}
```

Statelessness reminder: no mutable instance fields — per-session data goes to `ctx.state()`.

### Step 2: Register as Bean

```java
@Configuration
public class MyGameConfig {
    @Bean
    public Game myFirstGame() {
        return new MyFirstGame();
    }
}
```

Or with `@Component` on the game class. For per-session construction state, register a factory instead: `registry.register(descriptor, MyFirstGame::new)`.

### Step 3: Start Game via REST

`PlayerRequest` requires both `name` and `role` (`SOLO, PLAYER_ONE, PLAYER_TWO, TEAM_A, TEAM_B, SPECTATOR`):

```bash
curl -X POST http://localhost:8080/api/v1/games/sessions \
  -H "Content-Type: application/json" \
  -d '{"gameId":"my-first-game","players":[{"name":"Ali","role":"SOLO"}]}'
```

### Advanced Example - With Animations

```java
@Override
public void onStart(GameContext ctx) {
    ctx.fillBoard(TileColor.OFF);

    // 1. Standby 2 seconds (infinite animation -> TimeoutException -> cancel)
    try {
        ctx.animations().playStandbyAnimation(StandbyAnimationType.BREATHING).get(2, TimeUnit.SECONDS);
    } catch (Exception e) {
        ctx.animations().cancelCurrent();
    }

    // 2. Countdown (1000 ms per digit default)
    ctx.animations().playCountdown(800).join();

    // 3. Real game start
    ctx.state().put("score", 0);
    ctx.fillBoard(TileColor.BLUE);

    // 4. 30 second timer (fires via tick thread calling checkExpiry)
    ctx.timer().startCountdown(Duration.ofSeconds(30), () -> {
        ctx.animations().playLoseAnimation(LoseAnimationType.FADE_TO_RED)
            .thenRun(() -> ctx.loseSession());
    });
}

@Override
public void onTileEvent(GameContext ctx, TileEvent event) {
    if (event.type() != TileEventType.TOUCH) return;
    // game logic...

    // On loss:
    ctx.animations().playLoseAnimation(LoseAnimationType.DESCENDING_CURTAIN)
        .thenRun(() -> ctx.loseSession());

    // On win:
    ctx.animations().playWinAnimation(WinAnimationType.FIREWORKS)
        .thenRun(() -> ctx.winSession(ctx.players()));
}
```

### Using Other Features

```java
// NeighborFinder (methods are of(...), not findNeighbors)
List<Position> neighbors = ctx.neighbors().of(event.position());
neighbors.forEach(n -> ctx.setTile(n.row(), n.col(), TileColor.YELLOW));

// Random
Position randomPos = ctx.random().randomPosition();
TileColor randomColor = ctx.random().randomColor();

// PatternMatcher (pure functions over position lists)
if (ctx.patterns().tailMatches(ctx.touchHistory().positionOrder(), targetPattern)) { ... }

// ComboTracker
ctx.combos().hit();
if (ctx.combos().current() >= 5) { /* combo bonus */ }

// HealthSystem
boolean killed = ctx.health().damage(playerId, 10);
if (!ctx.health().isAlive(playerId)) ctx.loseSession();

// LevelSystem
ctx.levels().advance();

// TouchHistory
int total = ctx.touchHistory().totalTouches();
List<TileEvent> lastFive = ctx.touchHistory().recent(5);
```

---

## Deep Dive - Concurrency

### 1. GameEngineImpl.exclusiveSessionId - AtomicReference CAS

```java
private final AtomicReference<String> exclusiveSessionId = new AtomicReference<>();

public String startGame(...) {
    String sessionId = UUID.randomUUID().toString();
    if (!exclusiveSessionId.compareAndSet(null, sessionId)) {
        throw new GameSessionException("game.board_already_owned", ...);
    }
    try {
        session = new GameSessionImpl(...);
    } catch (RuntimeException e) {
        exclusiveSessionId.compareAndSet(sessionId, null); // rollback only our own claim
        throw e;
    }
}
```

- `compareAndSet(null, sessionId)` is atomic: of two threads calling `startGame` concurrently, exactly one succeeds.
- Exclusive board ownership without a global synchronized block.
- Rollback with `compareAndSet(sessionId, null)` frees ownership only if it still holds *this* sessionId.

### 2. SessionLifecycle - CAS State Machine

```java
final class SessionLifecycle {
    private final AtomicReference<GameStatus> status = new AtomicReference<>(GameStatus.IDLE);

    boolean start() { return status.compareAndSet(GameStatus.IDLE, GameStatus.RUNNING); }

    boolean finish(GameStatus target) {
        GameStatus prev;
        do {
            prev = status.get();
            if (prev != GameStatus.RUNNING && prev != GameStatus.PAUSED) return false; // already finished
        } while (!status.compareAndSet(prev, target));
        return true;
    }

    GameStatus current() { return status.get(); }
}
```

- `finish` with a CAS loop guarantees only one thread transitions to the final status, even if `winSession`, `loseSession`, `stopSession` and the TTL reaper fire concurrently.
- Idempotent: a second `finish` returns `false` and `finishSession` returns immediately.

### 3. GameEventBusImpl - Semaphore + Deque for DROP_OLDEST

Detailed in the EventBus section. Key points:

- `ringLock` (ReentrantLock) protects `ring` (ArrayDeque)
- `ringAvailable` (Semaphore) counts items; evicting the oldest consumes its permit so `availablePermits() == ring.size()` always holds
- `droppedEvents` (AtomicLong) counts drops (exposed via `droppedEventCount()` and the `tileboard.eventbus.dropped_events` Micrometer gauge)

### 4. BoardChannel - ReentrantLock + gatewayWriteLock + coalescing

Detailed in the BoardChannel section. `stateLock` guards the buffer, `gatewayWriteLock` serializes wire writes, and `sendLatest()` re-reads the snapshot for coalescing. Broadcaster dispatch happens outside the write lock.

### 5. AnimationSystem - generation + CompletableFuture + SingleThreadExecutor

Detailed in the AnimationSystem section. `generation` (AtomicLong) + `runLock` (synchronized) + `SingleThreadExecutor` + per-animation `CompletableFuture`.

### 6. ScoreSystem - ConcurrentHashMap + AtomicInteger

```java
private final Map<String, AtomicInteger> scores = new ConcurrentHashMap<>();

public int add(String playerId, int delta) {
    int result = getOrCreate(playerId).addAndGet(delta);
    if (delta != 0) onChange.run();
    return result;
}

private AtomicInteger getOrCreate(String playerId) {
    return scores.computeIfAbsent(playerId, k -> new AtomicInteger(0));
}
```

- `ConcurrentHashMap` is thread-safe for concurrent read/write.
- `computeIfAbsent` is atomic: concurrent creators for a new playerId yield a single `AtomicInteger`.
- `AtomicInteger.addAndGet` is CAS-based, no global lock. `onChange` runs on the caller's thread.

### 7. GameTimer - volatile + AtomicReference + AtomicBoolean

```java
private final AtomicReference<Runnable> onExpire = new AtomicReference<>();
private final AtomicBoolean expiryNotified = new AtomicBoolean(false);
private volatile Instant startedAt;
private volatile Instant stoppedAt;
private volatile Duration countdownTarget;

public void checkExpiry() {
    if (!isExpired()) return;
    if (expiryNotified.compareAndSet(false, true)) engineExpiryNotifier.run();
    Runnable cb = onExpire.getAndSet(null);
    if (cb != null) cb.run();
}
```

- `volatile` gives cross-thread visibility without locking.
- `compareAndSet` + `getAndSet(null)` guarantee each expiry callback fires exactly once, even under concurrent `checkExpiry` calls.

---

## Tests

```bash
mvn test -pl tileboard-game-engine
```

Actual test classes (JUnit 5 `5.10.2`, Mockito `5.11.0`, Awaitility `4.2.1`):

- `codec`: `ColorTileCodecTest`, `EngineFrameRouterTest`, `EngineFrameRouterReassemblyTest`
- `core`: `BoardChannelConcurrencyTest`, `CoreRegistryAndModelTest`, `GameEngineManagerTest`, `GameSessionImplTest`, `SessionLifecycleTest`, `SessionSnapshotTest`, `TouchFrameRouterTest`
- `event`: `GameEventBusImplTest`, `GameEventBusImplTest2`, `GameEventBusImplConcurrencyTest`
- `feature`: `AnimationSystemCancellationTest`, `ComboTrackerTest`, `ComboTrackerConcurrencyTest`, `FeatureSystemsTest`, `GameTimerTest`, `GameTimerConcurrencyTest`, `GraphFeatureTest`, `HealthSystemTest`, `LevelSystemTest`, `MemoryFeatureTest`, `NeighborFinderTest`, `RandomFeatureTest`, `ReactionSpeedTrackerTest`, `ScoreSystemTest`, `SpatialAndReactionFeatureTest`, `TouchHistoryTest`, `WaveGeneratorTest`

---

## Dependencies

```xml
<dependency>
    <groupId>com.tileboard</groupId>
    <artifactId>tileboard-serial-protocol</artifactId>
    <version>1.0.0</version>
</dependency>
```

- `slf4j-api` (`2.0.16`): logging facade
- `jackson-databind` (`2.17.2`) + `jackson-datatype-jsr310` (`2.22.1`): SSE JSON serialization
- `spring-boot-autoconfigure` + `spring-boot-configuration-processor` (`3.3.2`, both `optional`): only the auto-config layer
- `spring-webmvc` (`6.1.13`, compile): `SseEmitter`
- `jakarta.annotation-api` (`3.0.0`, compile): `@PreDestroy`
- `micrometer-core` (`1.17.0`, compile): `GameEngineMetricsBinder` gauges (binder itself is `@ConditionalOnBean(MeterRegistry.class)`)
- Test: `mockito-junit-jupiter`, `awaitility`, `junit-jupiter`, `slf4j-simple`

Requires Java 17+ (`maven-compiler-plugin` `release = 17`).

---

**Author:** Tileboard Platform Team  
**Version:** 1.0.0  
**Java:** 17+
