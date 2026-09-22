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

---

## Overall Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│  Game (your code)                                                │
│  implements GameLifecycle { onStart, onTileEvent, onTick, onStop }│
├──────────────────────────────────────────────────────────────────┤
│  GameContext (passed to every lifecycle method)                  │
│  ├─ BoardContext: publishBoard, setTile, fillBoard, newBoard     │
│  ├─ SessionControl: winSession, loseSession, state, players      │
│  └─ FeatureProvider: scores(), health(), timer(), animations()…  │
├──────────────────────────────────────────────────────────────────┤
│  GameEngineImpl                                                   │
│  ├─ GameRegistry (list/register/instantiate games)               │
│  ├─ activeSessions: ConcurrentHashMap<sessionId, GameSessionImpl>│
│  ├─ exclusiveSessionId: AtomicReference (only one session owns board)│
│  ├─ sessionReaper: ScheduledExecutorService (TTL)                │
│  └─ TouchFrameRouter + EngineFrameRouter (frame → TileEvent)     │
├──────────────────────────────────────────────────────────────────┤
│  GameSessionImpl                                                  │
│  ├─ BoardChannel (ReentrantLock + gatewayWriteLock)              │
│  ├─ FeatureBundle (ScoreSystem, HealthSystem, AnimationSystem…)  │
│  ├─ GameState (synchronized HashMap)                             │
│  ├─ tickExecutor: ScheduledExecutorService (onTick every 100ms)  │
│  └─ lifecycle: SessionLifecycle (CAS state machine)              │
├──────────────────────────────────────────────────────────────────┤
│  TileGatewayClient (from serial-protocol)                         │
│  └─ SerialTransport → Hardware                                   │
└──────────────────────────────────────────────────────────────────┘
```

**Stateless Game Contract:** When you register a `Game` via `registry.register(game)`, a **single instance** is reused across all future sessions of that `gameId` (similar to Servlet singleton model). So you **must not** keep mutable state in instance fields. All session-scoped data must be stored in `ctx.state()` (fresh `GameState` per session) or in feature objects inside `FeatureBundle` (also per-session). If you genuinely need per-instance construction state, register with `registry.register(descriptor, factory)` which creates a brand-new instance per session.

---

## Package Structure

| Package | Responsibility |
|------|---------|
| `core` | Core contracts: `Game`, `GameDescriptor`, `GameContext`, `GameEngine`, `GameSession`, `BoardChannel`, `FeatureBundle` |
| `feature` | Ready-made features: `ScoreSystem`, `HealthSystem`, `LevelSystem`, `ComboTracker`, `GameTimer`, `AnimationSystem`, `TouchHistory`, `NeighborFinder`, `WaveGenerator`, `PatternMatcher`, `RandomFeature`, `MemoryFeature`, `ReactionSpeedTracker`, `GraphFeature` |
| `feature/neighbor` | Grid topology: `Adjacency`, `GridTopology`, `NeighborFinder` |
| `event` | Event Bus: `GameEvent`, `GameEventType`, `GameEventBus`, `GameEventBusImpl`, `SubscriptionOptions`, `EventOverflowPolicy` |
| `model` | Domain model: `Player`, `TileColor`, `TileEvent`, `TileEventType`, `TouchSequence`, `Team` |
| `codec` | Color codecs and frame routing: `ColorTileCodec`, `EngineFrameRouter` |
| `sse` | SSE: `GameEventSseEmitter`, `SseGameEvent`, `SseGameEventType` |
| `spring` | Spring integration: `TileboardEngineAutoConfiguration`, `TileboardEngineProperties`, `GameEngineManager`, `SseGameEventPublisher` |
| `exception` | Exceptions |

---

## Core Concepts

### GameDescriptor - Static Game Metadata

```java
public record GameDescriptor(String gameId, String displayName, String category, 
                             String description, int requiredWidth, int requiredHeight,
                             int minPlayers, int maxPlayers) {

    public static Builder builder(String gameId, String displayName) { ... }
}

// Usage:
GameDescriptor desc = GameDescriptor.builder("my-game", "My Awesome Game")
    .category("ARCADE")
    .description("Game description")
    .boardSize(8, 8)
    .players(1, 4)
    .build();
```

- `requiredWidth/Height` must match connected board, otherwise `GameEngineImpl.validateBoardSize` throws.
- `min/maxPlayers` checked in `validatePlayers` (duplicate player id also checked).

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

- `onStart`: Runs on thread calling `engine.startGame()` (usually HTTP request thread)
- `onTileEvent`, `onTick`, `onError`, `onStop`: Run on gateway callback executor (or tick executor)
- **Important:** `onTileEvent` only called when `GameStatus=RUNNING` (check in `GameSessionImpl.handleTileEvent`)

### GameContext - Game's Window to Engine

```java
public interface GameContext extends CoreGameContext, FeatureProvider {
    GameEventBus eventBus();
}
public interface CoreGameContext extends BoardContext, SessionControl {
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
public interface FeatureProvider {
    ScoreSystem scores(); HealthSystem health(); LevelSystem levels();
    ComboTracker combos(); GameTimer timer(); TouchHistory touchHistory();
    TouchAnalyzer touchAnalyzer(); BoardFeature board(); NeighborFinder neighbors();
    PatternMatcher patterns(); RandomFeature random(); WaveGenerator waves();
    MemoryFeature memory(); ReactionSpeedTracker reactionSpeed(); GraphFeature graph();
    AnimationSystem animations();
}
```

### GameState - Thread-Safe Bag for Session State

```java
public final class GameState {
    private final Map<String, Object> store = new HashMap<>();
    public synchronized <T> void put(String key, T value) { ... }
    public synchronized <T> Optional<T> get(String key, Class<T> type) { ... }
    public synchronized <T> T getOrDefault(String key, Class<T> type, T defaultValue) { ... }
    public synchronized void remove(String key) { ... }
    public synchronized void clear() { ... }
    public synchronized Map<String, Object> snapshot() { unmodifiable copy }
}
```

All methods are `synchronized` on instance, so tick thread and callback thread can both access state safely.

---

## GameEngine and GameSession - Lifecycle

### GameEngineImpl

```java
public final class GameEngineImpl implements GameEngine, AutoCloseable {
    private final Map<String, GameSessionImpl> activeSessions = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> reaperTasks = new ConcurrentHashMap<>();
    private final AtomicReference<String> exclusiveSessionId = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final ScheduledExecutorService sessionReaper;
    private final ExecutorService teardownExecutor;

    public String startGame(String gameId, List<Player> players) {
        Game game = registry.instantiate(gameId);
        validateBoardSize(descriptor);
        validatePlayers(descriptor, players);
        String sessionId = UUID.randomUUID().toString();
        if (!exclusiveSessionId.compareAndSet(null, sessionId)) 
            throw new GameSessionException("board already owned by " + exclusiveSessionId.get());
        GameSessionImpl session;
        try {
            session = new GameSessionImpl(sessionId, game, players, gateway, tickInterval, eventBus, ...);
        } catch (RuntimeException e) {
            exclusiveSessionId.compareAndSet(sessionId, null);
            throw e;
        }
        activeSessions.put(sessionId, session);
        ScheduledFuture<?> reaper = sessionReaper.schedule(() -> {
            if (status==RUNNING||PAUSED) session.stop();
        }, sessionTtl.toMillis(), MILLISECONDS);
        reaperTasks.put(sessionId, reaper);
        try { session.start(); } catch (RuntimeException e) { handleSessionTerminated(session); throw e; }
        return sessionId;
    }

    private void handleSessionTerminated(GameSessionImpl session) {
        activeSessions.remove(session.sessionId());
        exclusiveSessionId.compareAndSet(session.sessionId(), null);
        ScheduledFuture<?> reaper = reaperTasks.remove(session.sessionId());
        if (reaper!=null) reaper.cancel(false);
    }

    public boolean stopGame(String sessionId) {
        GameSessionImpl s = activeSessions.get(sessionId);
        if (s==null) return false;
        s.stop();
        return true;
    }
}
```

**Concurrency notes:**
- `activeSessions` is `ConcurrentHashMap` -> thread-safe for concurrent read/write from HTTP threads and callback thread
- `exclusiveSessionId` is `AtomicReference` with `compareAndSet` -> guarantees only one session owns board at a time, without global synchronized block. `compareAndSet(null, sessionId)` only succeeds if current value is null.
- `sessionReaper`: `SingleThreadScheduledExecutor` daemon checking session TTL. If session lives longer than `sessionTtl` (default 30 minutes), force stops it. Prevents session leak if client forgets.
- `teardownExecutor`: `CachedThreadPool` daemon for teardown tasks that shouldn't block tick thread.
- `closed`: `AtomicBoolean` to prevent double close and to guard late callbacks after close (Bug #4).

**TouchFrameRouter:** Converts `DATA_IN` frames to `TileEvent` and sends to correct session (or exclusive owner).

**EngineFrameRouter:** Wrapper around `BoardFrameListener` managing reassembly timeout (if touch tiles arrive fragmented in multiple frames).

### GameSessionImpl

```java
public final class GameSessionImpl implements GameSession, GameContext {
    private final BoardChannel boardChannel;
    private final GameState gameState = new GameState();
    private final FeatureBundle features;
    private final ScheduledExecutorService tickExecutor;
    private final ScheduledFuture<?> tickFuture;
    private final SessionLifecycle lifecycle = new SessionLifecycle();

    void start() {
        if (!lifecycle.start()) throw new GameSessionException("already started");
        features.timer().start();
        try {
            game.onStart(this);
            eventBus.publish(SESSION_STARTED);
        } catch (RuntimeException e) {
            forceStop();
            throw new GameSessionException(..., e);
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
            features.timer().checkExpiry();
            game.onTick(this);
            eventBus.publish(TICK);
        } catch (RuntimeException e) { handleGameError(e); }
    }

    private void finishSession(GameStatus finalStatus, List<Player> winners) {
        if (!lifecycle.finish(finalStatus)) return; // CAS -> only once
        features.timer().stop();
        cancelTick();
        features.closeAll();
        GameResult result = new GameResult(..., features.scores().allScores(), timer.elapsed(), now());
        this.result = result;
        try { game.onStop(this, result); } catch (RuntimeException e) { log.warn }
        try { fillBoard(OFF); } catch (RuntimeException e) { log.warn }
        if (onTerminated!=null) try { onTerminated.accept(this); } catch (RuntimeException e) { log.error }
        eventBus.publish(SESSION_FINISHED);
    }
}
```

**SessionLifecycle:** A state machine with CAS (compareAndSet) guaranteeing `finishSession` runs exactly once, even if called concurrently from multiple threads (e.g., `winSession` from game and TTL reaper at same time).

**tickExecutor:** `SingleThreadScheduledExecutor` named `tileboard-tick-<sessionId>` calling `runTick()` every `tickInterval` (default 100ms). `tickInterval=0` means no ticks.

---

## BoardChannel - Board Publishing with Coalescing

```java
public final class BoardChannel {
    private final ReentrantLock stateLock = new ReentrantLock();
    private final Object gatewayWriteLock = new Object();
    private final Board<TileColor> buffer;
    private Board<TileColor> lastSentBoard;

    public Board<TileColor> setTile(int row, int col, TileColor color) {
        Board<TileColor> snapshot;
        stateLock.lock();
        try { buffer.set(row, col, color); snapshot = buffer.copy(); }
        finally { stateLock.unlock(); }
        sendLatest();
        return snapshot;
    }

    private Board<TileColor> sendLatest() {
        synchronized (gatewayWriteLock) {
            Board<TileColor> latest = snapshot(); // reads again!
            if (latest.equals(lastSentBoard)) return latest;
            gateway.sendBoard(DATA_OUT, SET, latest, codec);
            lastSentBoard = latest;
            return latest;
        }
    }
}
```

**Why snapshot twice?** This is intentional and called **coalescing semantics**:

1. Thread A calls `setTile(0,0,RED)` -> changes `buffer` to RED, takes snapshot (RED)
2. Before A reaches `sendLatest()`, Thread B calls `setTile(0,1,GREEN)` -> changes `buffer` to (RED,GREEN)
3. A enters `sendLatest()`, but instead of sending old snapshot (only RED), it re-reads `snapshot()` which is (RED,GREEN) -> most recent consistent state is sent

This means caller must not assume Board returned by `setTile` is byte-for-byte identical to what was transmitted. Gateway only ever needs latest board, and this avoids sending superseded frames out of order.

**Role of locks:**
- `stateLock` (ReentrantLock): protects `buffer` (mutable Board)
- `gatewayWriteLock` (synchronized Object): serializes writes to wire, prevents byte interleaving

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
}
```

### ScoreSystem - Thread-Safe Scoring

```java
public final class ScoreSystem {
    private final Map<String, AtomicInteger> scores = new ConcurrentHashMap<>();
    public ScoreSystem(List<Player> players) { players.forEach(p -> scores.put(p.id(), new AtomicInteger(0))); }
    public int add(String playerId, int delta) { return getOrCreate(playerId).addAndGet(delta); }
    public int get(String playerId) { return getOrCreate(playerId).get(); }
    public void set(String playerId, int value) { getOrCreate(playerId).set(value); }
    public Optional<String> leader() { max by value }
    public Map<String, Integer> allScores() { snapshot unmodifiable }
    private AtomicInteger getOrCreate(String playerId) { computeIfAbsent }
}
```

- `ConcurrentHashMap` + `AtomicInteger` -> `add()` is thread-safe without global lock (CAS inside AtomicInteger)
- `computeIfAbsent` guarantees if two threads concurrently `getOrCreate` for new playerId, only one AtomicInteger is created

### HealthSystem - Player Health

Similar to ScoreSystem but with health logic (0 to maxHealth), damage, heal.

### LevelSystem - Levels

Simple: `currentLevel`, `nextLevel()`, `setLevel()`.

### ComboTracker - Combos

Counts consecutive correct touches with configurable timeout.

### GameTimer - Wall-Clock Timer

```java
public final class GameTimer {
    private final AtomicReference<Runnable> onExpire = new AtomicReference<>();
    private volatile Instant startedAt;
    private volatile Instant stoppedAt;
    private volatile Duration countdownTarget;

    public void start() { startedAt=now(); stoppedAt=null; }
    public void stop() { if (startedAt!=null && stoppedAt==null) stoppedAt=now(); }
    public void startCountdown(Duration duration, Runnable onExpireCallback) {
        countdownTarget=duration; onExpire.set(callback); start();
    }
    public Duration elapsed() { between startedAt and (stoppedAt or now) }
    public Duration remaining() { countdownTarget - elapsed(), or ZERO }
    public boolean isExpired() { countdownTarget!=null && remaining().isZero() }
    public void checkExpiry() { if (isExpired()) { Runnable cb = onExpire.getAndSet(null); if (cb!=null) cb.run(); } }
}
```

- `volatile` for `startedAt`, `stoppedAt`, `countdownTarget` -> lock-free reads but guaranteed visibility across threads
- `AtomicReference` for `onExpire` with `getAndSet(null)` -> guarantees callback runs only once, even if `checkExpiry()` called concurrently from two threads (though in practice only called from tick thread)
- `checkExpiry()` called every tick by `GameSessionImpl.runTick()` -> automatic countdown without separate thread

### TouchHistory - Touch History

A list with max size (configurable, default 2000) holding all `TileEvent`s. For analytics, undo, or stats display.

### NeighborFinder - Neighbor Finding

```java
public class NeighborFinder {
    public List<Position> findNeighbors(Position pos) { 4-way or 8-way }
    public List<Position> findNeighbors(Position pos, Adjacency adjacency) { ... }
}
```

- `Adjacency.FOUR_WAY` (up, down, left, right) or `EIGHT_WAY` (including diagonal)
- `GridTopology` manages borders (wrap or not)

### WaveGenerator - Wave Effect

A wave effect starting from a point and expanding outward. Uses `BoardChannel` for publishing.

### PatternMatcher - Pattern Matching

Checks if a specific pattern (e.g., line, square, L-shape) has been touched on board.

### RandomFeature - Random Helpers

Helpers for random position, random color, shuffle.

### AnimationSystem - Detailed in next section

---

## AnimationSystem

This is one of the most complex classes in the engine and implements all requested animations.

### Overall Design

```java
public final class AnimationSystem {
    private final int width, height;
    private final Consumer<Board<TileColor>> boardPublisher;
    private final ExecutorService executor = newSingleThreadExecutor(daemon, "tileboard-animation");
    private final Random rng;
    private final AtomicLong generation = new AtomicLong(0);
    private final Object runLock = new Object();
    private volatile Future<?> currentTask;
    private volatile CompletableFuture<Void> currentResult;

    private CompletableFuture<Void> run(Consumer<RunToken> body) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        synchronized (runLock) {
            Future<?> prevTask = currentTask;
            CompletableFuture<Void> prevResult = currentResult;
            if (prevTask!=null) prevTask.cancel(true);
            if (prevResult!=null) prevResult.cancel(false);
            long myGen = generation.incrementAndGet();
            RunToken token = new RunToken(myGen);
            currentResult = result;
            try {
                Future<?> submitted = executor.submit(() -> {
                    try {
                        body.accept(token);
                        if (token.isCancelled()) result.cancel(false);
                        else result.complete(null);
                    } catch (AnimationCancelledException cancelled) {
                        result.cancel(false);
                    } catch (RuntimeException e) {
                        result.completeExceptionally(e);
                    } finally { Thread.interrupted(); }
                });
                currentTask = submitted;
            } catch (RejectedExecutionException e) { result.completeExceptionally(e); }
        }
        return result;
    }

    public void cancelCurrent() {
        synchronized (runLock) {
            generation.incrementAndGet();
            if (currentTask!=null) currentTask.cancel(true);
            if (currentResult!=null) currentResult.cancel(false);
            currentTask=null; currentResult=null;
        }
    }
}
```

**Generation-based cooperative cancellation pattern:**

- Each new animation does `generation.incrementAndGet()` and creates `RunToken` with that generation.
- `RunToken.isCancelled()` checks `generation.get() != myGeneration` -> if true, a newer animation started and this one is obsolete.
- `RunToken.sleep(ms)` and `show(board)` check `isCancelled()` before work. If cancelled, `sleep` returns false and `show` and `pause` throw `AnimationCancelledException` (cheap exception without stacktrace).
- This exception is caught inside `run` and future goes to cancelled state, not failed.
- `cancelCurrent()` also increments generation and cancels Future (interrupt).

**Why this pattern?** Because animations run on a `SingleThreadExecutor`, and we don't want to force-kill thread (which could leave board in incomplete state). Instead, animation cooperatively checks if cancelled and if so, exits cleanly.

**CompletableFuture:** Each animation returns `CompletableFuture<Void>` that:
- Completes normally when animation finishes naturally
- Is cancelled when superseded (new animation starts)
- Completes exceptionally if it throws

This allows chaining: `playCountdown().thenRun(() -> startGame())`

### Animation Types

#### Countdown - Before Game Start

```java
public CompletableFuture<Void> playCountdown(long digitDurationMs) {
    return run(token -> {
        if (width<3 || height<5) playSimpleCountdown(token, digitDurationMs);
        else playScalableCountdown(token, digitDurationMs);
    });
}

private void playSimpleCountdown(RunToken token, long digitDurationMs) {
    TileColor[] colors = {RED, YELLOW, GREEN};
    for (int i=3; i>0; i--) {
        token.show(new Board<>(width, height, colors[3-i])); // whole board one color
        if (!token.sleep(digitDurationMs)) return;
    }
    token.clear();
}

private void playScalableCountdown(RunToken token, long digitDurationMs) {
    for (int digit=3; digit>=1; digit--) {
        token.show(renderDigit(digit)); // render digit with 5x3 pattern
        if (!token.sleep(digitDurationMs)) return;
    }
    for (int i=0; i<3; i++) { // green blink at end
        token.show(new Board<>(width, height, GREEN));
        if (!token.sleep(150)) return;
        token.clear();
        if (!token.sleep(150)) return;
    }
}
```

- `renderDigit`: boolean[][] pattern for each digit (1,2,3) drawn centered with different color (RED for 3, YELLOW for 2, GREEN for 1)

#### Win - Victory

```java
public enum WinAnimationType { RADIAL_BURST, RAINBOW_SWEEP, SPARKLE, FIREWORKS }

private void playRadialBurst(RunToken token) {
    Position center = new Position(height/2, width/2);
    TileColor[] colors = {YELLOW, GREEN, BLUE, PINK, LIGHT_BLUE};
    int maxRadius = ...;
    for (int radius=0; radius<=maxRadius; radius++) {
        token.show(ringBandBoard(center, colors[radius%colors.length], radius));
        token.pause(100);
    }
    token.pause(500);
    token.clear();
}

private void playRainbowSweep(RunToken token) {
    TileColor[] rainbow = {RED, YELLOW, GREEN, BLUE, PINK};
    for (int sweep=0; sweep<2; sweep++) {
        for (int col=0; col<width; col++) {
            Board<TileColor> board = new Board<>(width, height, OFF);
            for (int c=0; c<=col; c++) {
                TileColor color = rainbow[(c + sweep*width) % rainbow.length];
                for (int row=0; row<height; row++) board.set(row, c, color);
            }
            token.show(board);
            token.pause(80);
        }
    }
    clearBoard();
}

private void playSparkle(RunToken token) {
    TileColor[] colors = {YELLOW, WHITE, LIGHT_BLUE};
    for (int cycle=0; cycle<15; cycle++) {
        Board<TileColor> board = new Board<>(width, height, OFF);
        int sparks = 5 + (cycle%5);
        for (int i=0; i<sparks; i++) board.set(rng.nextInt(height), rng.nextInt(width), colors[rng.nextInt(colors.length)]);
        token.show(board);
        if (!token.sleep(120)) return;
    }
    token.clear();
}
```

#### Lose - Defeat

```java
public enum LoseAnimationType { FADE_TO_RED, DESCENDING_CURTAIN, CRUMBLE, PULSE_RED }

private void playFadeToRed(RunToken token) {
    Board<TileColor> board = new Board<>(width, height, OFF);
    for (int phase=0; phase<3; phase++) {
        for (int row=0; row<height; row++) for (int col=0; col<width; col++)
            if (rng.nextDouble()<0.3) board.set(row, col, RED);
        token.show(board.copy());
        if (!token.sleep(300)) return;
    }
    board.fill(RED);
    token.show(board);
    if (token.sleep(1000)) token.clear();
}

private void playDescendingCurtain(RunToken token) {
    for (int row=0; row<height; row++) {
        Board<TileColor> board = new Board<>(width, height, OFF);
        for (int r=0; r<=row; r++) for (int col=0; col<width; col++) board.set(r, col, RED);
        token.show(board);
        token.pause(200);
    }
    token.pause(500);
    clearBoard();
}
```

#### Standby - Idle (Infinite Until Cancelled)

```java
public enum StandbyAnimationType { BREATHING, CORNER_PULSE, WAVE_BORDER, RANDOM_TWINKLE }

private void playBreathing(RunToken token) {
    TileColor[] breathColors = {BLUE, LIGHT_BLUE};
    for (int cycle=0; !token.isCancelled(); cycle++) {
        Board<TileColor> board = new Board<>(width, height, OFF);
        TileColor color = breathColors[cycle%2];
        paintCorners(board, color);
        boardPublisher.accept(board);
        if (!token.sleep(500)) return;
        if (width>=3 && height>=3) {
            paintBorder(board, color);
            boardPublisher.accept(board);
            if (!token.sleep(500)) return;
        }
    }
}

private void playWaveBorder(RunToken token) {
    TileColor color = LIGHT_BLUE;
    for (int offset=0; ; offset=(offset+1)%3) {
        Board<TileColor> board = new Board<>(width, height, OFF);
        for (int col=0; col<width; col++) if ((col+offset)%3==0) board.set(0, col, color);
        // ... other borders
        token.show(board);
        token.pause(200);
    }
}
```

**Note:** `BREATHING` and other standbys have infinite loops (`for(;;)` or `while(true)`) and only exit when `token.isCancelled()` true or `sleep` returns false. That means they run forever until `cancelCurrent()` or a new animation starts. That's why in sample game we do `get(2, SECONDS)` which throws TimeoutException after 2 seconds and then we `cancelCurrent()`.

### RunToken

```java
public final class RunToken {
    private final long myGeneration;
    boolean isCancelled() { return generation.get() != myGeneration; }
    boolean sleep(long ms) {
        if (isCancelled()) return false;
        if (ms>0) try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
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
public final class GameEventBusImpl implements GameEventBus, AutoCloseable {
    private final CopyOnWriteArrayList<Subscription> subscriptions = new CopyOnWriteArrayList<>();
    private final int defaultQueueCapacity;
    private final EventOverflowPolicy defaultPolicy;
    private final Duration blockTimeout;
    private final AtomicLong droppedEvents = new AtomicLong();

    public void publish(GameEvent event) {
        for (Subscription sub : subscriptions) if (sub.matches(event)) sub.offer(event);
    }

    public Runnable subscribe(GameEventListener listener, SubscriptionOptions options) {
        Subscription sub = new Subscription(listener, options, blockTimeout.toNanos());
        subscriptions.add(sub);
        sub.start();
        return () -> { subscriptions.remove(sub); sub.stop(); };
    }

    private final class Subscription {
        private final GameEventListener listener;
        private final SubscriptionOptions options;
        private final BlockingQueue<GameEvent> blockingQueue; // for BLOCK policy
        private final ArrayDeque<GameEvent> ring; // for DROP_OLDEST
        private final ReentrantLock ringLock = new ReentrantLock();
        private final Semaphore ringAvailable = new Semaphore(0);
        private final ExecutorService worker = newSingleThreadExecutor(daemon, "tileboard-eventbus-subscriber");
        private volatile boolean running = true;

        void offer(GameEvent event) {
            if (options.policy()==BLOCK) offerBlocking(event);
            else offerDropOldest(event);
        }

        private void offerBlocking(GameEvent event) {
            try {
                if (!blockingQueue.offer(event, blockTimeoutNanos, NANOSECONDS)) {
                    log.warn("Subscriber did not drain within {}ns; dropping event {}", blockTimeoutNanos, event.type());
                    droppedEvents.incrementAndGet();
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        private void offerDropOldest(GameEvent event) {
            ringLock.lock();
            try {
                while (ring.size() >= options.queueCapacity()) {
                    if (ring.pollFirst()!=null) {
                        droppedEvents.incrementAndGet();
                        ringAvailable.tryAcquire(); // consume permit for evicted item
                    }
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
                try {
                    GameEvent event = (options.policy()==BLOCK) 
                        ? blockingQueue.poll(1, SECONDS) 
                        : takeDropOldest(1000);
                    if (event==null) continue;
                    try { listener.onEvent(event); } catch (Throwable t) { log.warn }
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
        }
    }
}
```

### Two Overflow Policies

1. **BLOCK:** Uses `LinkedBlockingQueue` with limited capacity. `offer` blocks with timeout (default 200ms). If subscriber doesn't drain in time, event dropped and `droppedEvents` incremented. For slow subscribers that shouldn't lose events but shouldn't block publisher forever.

2. **DROP_OLDEST (default):** Uses `ArrayDeque` + `ReentrantLock` + `Semaphore`.
   - `ring` is Deque protected by `ringLock`
   - `ringAvailable` is Semaphore counting items in ring. Each add does `release()`, each remove does `acquire()`
   - When ring full, oldest item (`pollFirst`) removed and `tryAcquire()` called to consume its permit (so `availablePermits()` always equals `ring.size()`)
   - More efficient than `BlockingQueue` because Deque allows removal from head (for DROP_OLDEST)

**Why Semaphore + Deque instead of BlockingQueue for DROP_OLDEST?** Because `LinkedBlockingQueue` only removes from one end and cannot drop oldest while keeping newest. `ArrayDeque` allows that but is not thread-safe, so combined with `ReentrantLock` and `Semaphore`.

**CopyOnWriteArrayList for subscriptions:** Similar to TileGatewayClient, add/remove subscription rare but `publish` frequent. COWAL is lock-free for reading.

**drainLoop:** Each subscription has `SingleThreadExecutor` looping `take` and delivering to listener. If listener throws, caught and loop continues (one buggy listener doesn't bring down whole bus).

---

## EngineFrameRouter and TouchFrameRouter

### EngineFrameRouter

A `FrameListener` decoding `DATA_IN` with `ColorTileCodec` into `Board<Boolean>` (or `Board<TileColor>`). Also manages reassembly timeout: if a touch arrives fragmented in multiple frames, waits up to `frameReassemblyTimeout` (default 500ms) for subsequent frames then builds complete `Board`.

### TouchFrameRouter

Converts `Board<Boolean>` to `TileEvent` and routes to correct session:

```java
public class TouchFrameRouter {
    private final Function<String, Optional<GameSessionImpl>> sessionLookup;
    private final Supplier<Optional<String>> exclusiveOwner;

    public void route(Board<Boolean> touchBoard) {
        List<Position> touched = touchBoard.positionsWhere(Boolean.TRUE::equals);
        for (Position pos : touched) {
            GameSessionImpl session = exclusiveOwner.flatMap(sessionLookup).orElse(...);
            if (session!=null) session.handleTileEvent(TileEvent.touch(pos, session.sessionId()));
        }
    }
}
```

---

## SSE - Streaming to Frontend

```
GameSessionImpl.publishBoard() → eventBus.publish(BOARD_UPDATED)
                            → SseGameEventPublisher → SseEmitter → HTTP client
```

- `SseGameEventPublisher`: Subscribes to `GameEventBus` and converts each `GameEvent` to `SseGameEvent` and sends to all connected `SseEmitter`s
- `GameEventSseEmitter`: Wrapper around Spring `SseEmitter` with heartbeat
- `SseGameEventType`: `SESSION_STARTED`, `BOARD_UPDATED`, `SCORE_UPDATED`, `TICK`, `SESSION_FINISHED`...

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
    public GameRegistry gameRegistry(@Autowired(required=false) List<Game> games) {
        GameRegistry registry = new DefaultGameRegistry();
        if (games!=null) games.forEach(game -> { registry.register(game); log.info("Auto-registered {}", game.descriptor().gameId()); });
        return registry;
    }

    @Bean @ConditionalOnMissingBean @DependsOn("gameEventBus")
    public GameEngineManager gameEngineManager(GameRegistry registry, GameEventBus eventBus, TileboardEngineProperties props) {
        return new GameEngineManager(registry, eventBus, props);
    }

    @Bean @ConditionalOnMissingBean
    public SseGameEventPublisher sseGameEventPublisher(GameEventBus eventBus, ScheduledExecutorService heartbeatScheduler) { ... }

    @Bean @ConditionalOnBean(MeterRegistry.class)
    public GameEngineMetricsBinder gameEngineMetricsBinder(...) { ... }

    @Bean(destroyMethod="shutdown") @ConditionalOnMissingBean(name="tileboardSseHeartbeatScheduler")
    public ScheduledExecutorService tileboardSseHeartbeatScheduler() {
        return newSingleThreadScheduledExecutor(daemon, "tileboard-sse-heartbeat");
    }
}
```

- All beans are `@ConditionalOnMissingBean` -> if you define your own Bean, auto-config backs off
- `gameRegistry` auto-registers all `Game` Beans in context -> just define game as `@Bean` or `@Component`
- `GameEngineManager` built after `gameEventBus` (`@DependsOn`)

### GameEngineManager - Bridge Between Spring and Framework-Free Engine

```java
public class GameEngineManager {
    private volatile GameEngineImpl engine;
    private final GameRegistry registry;
    private final GameEventBus eventBus;
    private final Duration tickInterval, sessionTtl, frameReassemblyTimeout;
    private final int touchHistoryMaxSize;

    @EventListener
    public synchronized void onGatewayConnected(GatewayConnectedEvent event) {
        if (engine!=null) { log.warn("engine already bound - stopping"); shutdownCurrentEngine(); }
        engine = new GameEngineImpl(registry, event.client(), eventBus, tickInterval, sessionTtl, frameReassemblyTimeout, touchHistoryMaxSize, event.boardWidth(), event.boardHeight());
    }

    @EventListener
    public synchronized void onGatewayDisconnected(GatewayDisconnectedEvent event) {
        shutdownCurrentEngine();
    }

    private void shutdownCurrentEngine() {
        GameEngineImpl current = this.engine;
        if (current==null) { log.debug("no engine bound"); return; }
        this.engine = null; // immediately visible
        List<GameSession> sessions = current.activeSessions();
        try { current.close(); } catch (RuntimeException e) { log.warn }
        log.info("Game engine unbound{}", sessions.isEmpty() ? "" : " ("+sessions.size()+" stopped)");
    }

    public synchronized Optional<GameEngine> current() { return Optional.ofNullable(engine); }
    public synchronized GameEngine require() { if (engine==null) throw new EngineNotReadyException(); return engine; }

    @PreDestroy
    public synchronized void shutdownOnContextClose() { shutdownCurrentEngine(); }
}
```

**Concurrency notes:**
- `engine` volatile -> lock-free visibility for reads, but writes synchronized
- `shutdownCurrentEngine` null-safe and idempotent -> duplicate disconnect events don't NPE
- `engine = null` before `close()` -> `current()`/`require()` never see half-closed engine
- `@PreDestroy` guarantees engine and daemon threads freed when Spring context stops, even if no disconnect event ever arrived

### TileboardEngineProperties

```java
@ConfigurationProperties(prefix="tileboard.engine")
public class TileboardEngineProperties {
    private Duration tickInterval = Duration.ofMillis(100);
    private Duration sessionTtl = Duration.ofMinutes(30);
    private Duration frameReassemblyTimeout = Duration.ofMillis(500);
    private int eventBusQueueCapacity = 256;
    private int touchHistoryMaxSize = 2000;
}
```

Configurable in `application.yml`.

---

## Step-by-Step Game Creation Tutorial

### Step 1: Create Game Class

```java
public class MyFirstGame implements Game {

    private final GameDescriptor descriptor = GameDescriptor.builder("my-first-game", "My First Game")
        .category("TUTORIAL")
        .description("My first game")
        .boardSize(8, 8)
        .players(1, 1)
        .build();

    @Override public GameDescriptor descriptor() { return descriptor; }

    @Override
    public void onStart(GameContext ctx) {
        ctx.fillBoard(TileColor.OFF);
        ctx.scores().resetAll();
        ctx.fillBoard(TileColor.GREEN);
    }

    @Override
    public void onTileEvent(GameContext ctx, TileEvent event) {
        ctx.setTile(event.position().row(), event.position().col(), TileColor.RED);
        ctx.scores().add(ctx.players().get(0).id(), 1);

        if (ctx.scores().get(ctx.players().get(0).id()) >= 10) {
            ctx.animations().playWinAnimation().thenRun(() -> ctx.winSession(ctx.players()));
        }
    }

    @Override
    public void onStop(GameContext ctx, GameResult result) {
        ctx.fillBoard(TileColor.OFF);
    }
}
```

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

Or with `@Component`:

```java
@Component
public class MyFirstGame implements Game { ... }
```

### Step 3: Start Game via REST

```bash
curl -X POST http://localhost:8080/api/v1/games/sessions \
  -H "Content-Type: application/json" \
  -d '{"gameId":"my-first-game","players":[{"name":"Ali"}]}'
```

### Advanced Example - With Animations

```java
@Override
public void onStart(GameContext ctx) {
    ctx.fillBoard(TileColor.OFF);

    // 1. Standby 2 seconds
    try {
        ctx.animations().playStandbyAnimation(StandbyAnimationType.BREATHING).get(2, SECONDS);
    } catch (Exception e) {
        ctx.animations().cancelCurrent();
    }

    // 2. Countdown
    ctx.animations().playCountdown(800).join();

    // 3. Real game start
    ctx.state().put("score", 0);
    ctx.fillBoard(TileColor.BLUE);

    // 4. 30 second timer
    ctx.timer().startCountdown(Duration.ofSeconds(30), () -> {
        ctx.animations().playLoseAnimation(LoseAnimationType.FADE_TO_RED)
            .thenRun(() -> ctx.loseSession());
    });
}

@Override
public void onTileEvent(GameContext ctx, TileEvent event) {
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
// NeighborFinder
List<Position> neighbors = ctx.neighbors().findNeighbors(event.position());
neighbors.forEach(n -> ctx.setTile(n.row(), n.col(), TileColor.YELLOW));

// Random
Position randomPos = ctx.random().randomPosition();
TileColor randomColor = ctx.random().randomColor();

// PatternMatcher
if (ctx.patterns().isLine(...)) { ... }

// ComboTracker
ctx.combos().recordHit();
if (ctx.combos().currentCombo() >= 5) { /* combo bonus */ }

// HealthSystem
ctx.health().damage(playerId, 10);
if (ctx.health().isDead(playerId)) ctx.loseSession();
```

---

## Deep Dive - Concurrency

### 1. GameEngineImpl.exclusiveSessionId - AtomicReference CAS

```java
private final AtomicReference<String> exclusiveSessionId = new AtomicReference<>();

public String startGame(...) {
    String sessionId = UUID.randomUUID().toString();
    if (!exclusiveSessionId.compareAndSet(null, sessionId)) {
        throw new GameSessionException("board already owned by " + exclusiveSessionId.get());
    }
    // ...
    try {
        session = new GameSessionImpl(...);
    } catch (RuntimeException e) {
        exclusiveSessionId.compareAndSet(sessionId, null); // rollback
        throw e;
    }
}
```

- `compareAndSet(null, sessionId)` is atomic: only if current value is null, change to sessionId and return true. If two threads call startGame at same time, one succeeds, other fails.
- Without global synchronized block, exclusive board ownership guaranteed.
- Rollback with `compareAndSet(sessionId, null)` ensures if session creation fails, ownership freed, but only if still owner of same sessionId (if another session became owner in meantime, rollback shouldn't clear its ownership).

### 2. SessionLifecycle - CAS State Machine

```java
public class SessionLifecycle {
    private final AtomicReference<GameStatus> status = new AtomicReference<>(GameStatus.CREATED);

    public boolean start() {
        return status.compareAndSet(CREATED, RUNNING);
    }

    public boolean finish(GameStatus finalStatus) {
        while (true) {
            GameStatus current = status.get();
            if (current==FINISHED || current==STOPPED) return false; // already finished
            if (status.compareAndSet(current, finalStatus)) return true;
        }
    }
}
```

- `finish` with CAS loop guarantees only one thread succeeds changing status to FINISHED, even if `winSession`, `loseSession`, `stopSession` and TTL reaper called concurrently.
- Idempotent: second `finish` call returns false.

### 3. GameEventBusImpl - Semaphore + Deque for DROP_OLDEST

Detailed in EventBus section. Key points:

- `ringLock` (ReentrantLock) protects `ring` (ArrayDeque)
- `ringAvailable` (Semaphore) counts items
- On drop oldest: `pollFirst()` + `tryAcquire()` -> permit for evicted item consumed so `availablePermits() == ring.size()` stays true
- Both `offerDropOldest` and `takeDropOldest` use `ringLock` and `ringAvailable` -> thread-safe
- `droppedEvents` (AtomicLong) counts drops (for metrics)

### 4. BoardChannel - ReentrantLock + gatewayWriteLock + coalescing

Detailed in BoardChannel section.

### 5. AnimationSystem - generation + CompletableFuture + SingleThreadExecutor

Detailed in AnimationSystem section.

### 6. ScoreSystem - ConcurrentHashMap + AtomicInteger

```java
private final Map<String, AtomicInteger> scores = new ConcurrentHashMap<>();

public int add(String playerId, int delta) {
    return getOrCreate(playerId).addAndGet(delta);
}

private AtomicInteger getOrCreate(String playerId) {
    return scores.computeIfAbsent(playerId, k -> new AtomicInteger(0));
}
```

- `ConcurrentHashMap` thread-safe for concurrent read/write
- `computeIfAbsent` atomic: if two threads concurrently `getOrCreate` for new playerId, only one AtomicInteger created
- `AtomicInteger.addAndGet` implemented with CAS, no global lock

### 7. GameTimer - volatile + AtomicReference

```java
private final AtomicReference<Runnable> onExpire = new AtomicReference<>();
private volatile Instant startedAt;
private volatile Instant stoppedAt;
private volatile Duration countdownTarget;

public void checkExpiry() {
    if (!isExpired()) return;
    Runnable cb = onExpire.getAndSet(null);
    if (cb!=null) cb.run();
}
```

- `volatile` guarantees changes to `startedAt` etc. immediately visible to other threads (no synchronized needed)
- `getAndSet(null)` guarantees callback runs only once, even if `checkExpiry` called concurrently from two threads

---

## Tests

```bash
mvn test -pl tileboard-game-engine
```

- `ColorTileCodecTest`: color codec
- `EngineFrameRouterTest`, `EngineFrameRouterReassemblyTest`: routing and reassembly
- `BoardChannelConcurrencyTest`: BoardChannel concurrency
- `GameSessionImplTest`, `SessionLifecycleTest`: lifecycle
- `GameEventBusImplTest`, `GameEventBusImplConcurrencyTest`: EventBus
- `AnimationSystemCancellationTest`: animation cancellation
- `FeatureSystemsTest`: all features (Score, Health, Level, Combo, Timer, ...)
- `TouchFrameRouterTest`, `GameEngineManagerTest`

---

## Dependencies

```xml
<dependency>
    <groupId>com.tileboard</groupId>
    <artifactId>tileboard-serial-protocol</artifactId>
    <version>1.0.0</version>
</dependency>
```

- `slf4j-api`: logging facade
- `jackson-databind`: for SSE JSON serialization
- `spring-boot-autoconfigure` (optional): only for auto-config layer
- `micrometer-core`: for metrics (optional)

---

**Author:** Tileboard Platform Team  
**Version:** 1.0.0  
**Java:** 17+
