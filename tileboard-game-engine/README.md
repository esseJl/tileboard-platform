# tileboard-game-engine - مستندات جامع موتور بازی

> **ماموریت ماژول:** یک موتور بازی **production-ready**، **transport-agnostic** و **framework-free** که روی `tileboard-serial-protocol` ساخته شده. امکانات غنی (امتیاز، جان، لول، کمبو، پترن، تایمر، همسایه، SSE، انیمیشن) را بدون وابستگی به فریم‌ورک خاصی فراهم می‌کند؛ یک لایه Spring Boot auto-configuration به صورت optional وجود دارد.

---

## فهرست مطالب
1. [معماری کلی](#معماری-کلی)
2. [ساختار پکیج‌ها](#ساختار-پکیجها)
3. [مفاهیم هسته - Game، GameDescriptor، GameContext](#مفاهیم-هسته)
4. [GameEngine و GameSession - چرخه حیات](#gameengine-و-gamesession---چرخه-حیات)
5. [BoardChannel - انتشار برد با coalescing](#boardchannel---انتشار-برد-با-coalescing)
6. [FeatureBundle - تمام قابلیت‌های آماده](#featurebundle---تمام-قابلیتهای-آماده)
7. [AnimationSystem - انیمیشن‌های win/lose/standby/countdown](#animationsystem---انیمیشنهای-winlosestandbycountdown)
8. [EventBus - سیستم رویداد thread-safe](#eventbus---سیستم-رویداد-thread-safe)
9. [EngineFrameRouter و TouchFrameRouter - مسیریابی فریم‌ها](#engineframerouter-و-touchframerouter)
10. [SSE - استریم به فرانت‌اند](#sse---استریم-به-فرانتاند)
11. [لایه Spring - AutoConfiguration](#لایه-spring---autoconfiguration)
12. [آموزش گام به گام ساخت بازی](#آموزش-گام-به-گام-ساخت-بازی)
13. [بررسی کدهای پیچیده - Concurrency](#بررسی-کدهای-پیچیده---concurrency)
14. [تست‌ها](#تستها)

---

## معماری کلی

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

**اصل stateless بودن Game:** وقتی یک `Game` را با `registry.register(game)` ثبت می‌کنید، **یک instance** برای تمام session های آینده آن `gameId` reuse می‌شود (مثل Servlet). پس **نباید** state قابل تغییر در فیلدهای instance نگه دارید. تمام state جلسه باید در `ctx.state()` (که برای هر session تازه ساخته می‌شود) یا در feature های داخل `FeatureBundle` (که آن‌ها هم per-session هستند) ذخیره شود. اگر واقعا نیاز به ساخت instance جدید برای هر session دارید، از `registry.register(descriptor, factory)` استفاده کنید.

---

## ساختار پکیج‌ها

| پکیج | مسئولیت |
|------|---------|
| `core` | قراردادهای اصلی: `Game`, `GameDescriptor`, `GameContext`, `GameEngine`, `GameSession`, `BoardChannel`, `FeatureBundle` |
| `feature` | قابلیت‌های آماده: `ScoreSystem`, `HealthSystem`, `LevelSystem`, `ComboTracker`, `GameTimer`, `AnimationSystem`, `TouchHistory`, `NeighborFinder`, `WaveGenerator`, `PatternMatcher`, `RandomFeature`, `MemoryFeature`, `ReactionSpeedTracker`, `GraphFeature` |
| `feature/neighbor` | توپولوژی گرید: `Adjacency`, `GridTopology`, `NeighborFinder` |
| `event` | Event Bus: `GameEvent`, `GameEventType`, `GameEventBus`, `GameEventBusImpl`, `SubscriptionOptions`, `EventOverflowPolicy` |
| `model` | مدل دامنه: `Player`, `TileColor`, `TileEvent`, `TileEventType`, `TouchSequence`, `Team` |
| `codec` | Codec های رنگ و مسیریابی فریم: `ColorTileCodec`, `EngineFrameRouter` |
| `sse` | SSE: `GameEventSseEmitter`, `SseGameEvent`, `SseGameEventType` |
| `spring` | ادغام Spring: `TileboardEngineAutoConfiguration`, `TileboardEngineProperties`, `GameEngineManager`, `SseGameEventPublisher` |
| `exception` | استثناها |

---

## مفاهیم هسته

### GameDescriptor - متادیتای استاتیک بازی

```java
public record GameDescriptor(String gameId, String displayName, String category, 
                             String description, int requiredWidth, int requiredHeight,
                             int minPlayers, int maxPlayers) {

    public static Builder builder(String gameId, String displayName) { ... }
}

// استفاده:
GameDescriptor desc = GameDescriptor.builder("my-game", "My Awesome Game")
    .category("ARCADE")
    .description("توضیح بازی")
    .boardSize(8, 8)
    .players(1, 4)
    .build();
```

- `requiredWidth/Height` باید با برد متصل مطابقت داشته باشد، وگرنه `GameEngineImpl.validateBoardSize` خطا می‌دهد.
- `min/maxPlayers` در `validatePlayers` چک می‌شود (duplicate player id هم چک می‌شود).

### Game - قراردادی که باید پیاده کنید

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

- `onStart`: روی thread ای که `engine.startGame()` را صدا زده اجرا می‌شود (معمولا HTTP request thread)
- `onTileEvent`, `onTick`, `onError`, `onStop`: روی callback executor تایل‌گیت‌وی (یا tick executor) اجرا می‌شوند
- **مهم:** `onTileEvent` فقط وقتی `GameStatus=RUNNING` است صدا زده می‌شود (چک در `GameSessionImpl.handleTileEvent`)

### GameContext - پنجره بازی به موتور

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

### GameState - کیف thread-safe برای state جلسه

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

تمام متدها `synchronized` روی خود instance هستند، پس tick thread و callback thread می‌توانند همزمان به state دسترسی داشته باشند بدون race condition.

---

## GameEngine و GameSession - چرخه حیات

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

**نکات concurrency:**
- `activeSessions` از `ConcurrentHashMap` است → thread-safe برای خواندن/نوشتن همزمان از HTTP thread ها و callback thread
- `exclusiveSessionId` از `AtomicReference` با `compareAndSet` → تضمین می‌کند فقط یک session در یک لحظه مالک برد باشد، بدون نیاز به synchronized block سراسری. `compareAndSet(null, sessionId)` فقط وقتی موفق است که هیچ session دیگری مالک نباشد.
- `sessionReaper`: یک `SingleThreadScheduledExecutor` daemon که TTL جلسه را چک می‌کند. اگر جلسه بیش از `sessionTtl` (پیش‌فرض 30 دقیقه) زنده بماند، force stop می‌شود. این از leak جلسه در صورت فراموشی client جلوگیری می‌کند.
- `teardownExecutor`: `CachedThreadPool` daemon برای کارهای teardown که نباید tick thread را بلاک کنند.
- `closed`: `AtomicBoolean` برای جلوگیری از double close و برای guard کردن callback های دیررس بعد از close (Bug #4).

**TouchFrameRouter:** فریم‌های `DATA_IN` را به `TileEvent` تبدیل می‌کند و به session مربوطه (یا exclusive owner) می‌فرستد.

**EngineFrameRouter:** یک wrapper دور `BoardFrameListener` که reassembly timeout را مدیریت می‌کند (اگر تایل‌های لمس شده در چند فریم تکه‌تکه بیایند).

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
        if (!lifecycle.finish(finalStatus)) return; // CAS → فقط یک بار اجرا
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

**SessionLifecycle:** یک state machine با CAS (compareAndSet) که تضمین می‌کند `finishSession` دقیقا یک بار اجرا شود، حتی اگر همزمان از چند thread صدا زده شود (مثلا هم `winSession` از بازی و هم TTL reaper).

**tickExecutor:** `SingleThreadScheduledExecutor` با نام `tileboard-tick-<sessionId>` که هر `tickInterval` (پیش‌فرض 100ms) `runTick()` را صدا می‌زند. `tickInterval=0` یعنی بدون tick.

---

## BoardChannel - انتشار برد با coalescing

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
            Board<TileColor> latest = snapshot(); // دوباره می‌خواند!
            if (latest.equals(lastSentBoard)) return latest;
            gateway.sendBoard(DATA_OUT, SET, latest, codec);
            lastSentBoard = latest;
            return latest;
        }
    }
}
```

**چرا دو بار snapshot؟** این عمدی است و **coalescing semantics** نام دارد:

1. Thread A `setTile(0,0,RED)` را صدا می‌زند → `buffer` را به RED تغییر می‌دهد، snapshot می‌گیرد (RED)
2. قبل از اینکه A به `sendLatest()` برسد، Thread B `setTile(0,1,GREEN)` را صدا می‌زند → `buffer` را به (RED,GREEN) تغییر می‌دهد
3. A وارد `sendLatest()` می‌شود، اما به جای ارسال snapshot قدیمی (فقط RED)، دوباره `snapshot()` می‌خواند که (RED,GREEN) است → آخرین وضعیت سازگار ارسال می‌شود

این یعنی caller نباید فرض کند Board ای که `setTile` برمی‌گرداند دقیقا همان چیزی است که روی سیم رفته. گیت‌وی فقط به آخرین برد نیاز دارد، و این از ارسال فریم‌های منسوخ شده و out-of-order جلوگیری می‌کند.

**نقش lock ها:**
- `stateLock` (ReentrantLock): از `buffer` (Board mutable) محافظت می‌کند
- `gatewayWriteLock` (synchronized Object): write ها روی سیم را سریالایز می‌کند، جلوگیری از interleave شدن بایت‌ها

---

## FeatureBundle - تمام قابلیت‌های آماده

`FeatureBundle` یک record است که تمام feature های per-session را نگه می‌دارد:

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

### ScoreSystem - thread-safe امتیاز

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

- `ConcurrentHashMap` + `AtomicInteger` → `add()` بدون lock سراسری thread-safe است (CAS داخل AtomicInteger)
- `computeIfAbsent` تضمین می‌کند اگر playerId جدید باشد، AtomicInteger جدید ساخته شود

### HealthSystem - جان بازیکنان

مشابه ScoreSystem اما با منطق جان (0 تا maxHealth)، damage، heal.

### LevelSystem - لول

ساده: `currentLevel`، `nextLevel()`، `setLevel()`.

### ComboTracker - کمبو

تعداد لمس‌های درست پشت سر هم را می‌شمارد، با timeout قابل تنظیم.

### GameTimer - تایمر دیواری

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

- `volatile` برای `startedAt`, `stoppedAt`, `countdownTarget` → خواندن بدون lock اما visibility تضمین شده بین thread ها
- `AtomicReference` برای `onExpire` با `getAndSet(null)` → تضمین می‌کند callback فقط یک بار اجرا شود، حتی اگر `checkExpiry()` همزمان از دو thread صدا زده شود (هرچند در عمل فقط از tick thread صدا زده می‌شود)
- `checkExpiry()` هر tick توسط `GameSessionImpl.runTick()` صدا زده می‌شود → countdown خودکار بدون نیاز به thread جدا

### TouchHistory - تاریخچه لمس‌ها

یک لیست با حداکثر سایز (configurable، پیش‌فرض 2000) که تمام `TileEvent` ها را نگه می‌دارد. برای تحلیل، undo، یا نمایش آمار.

### NeighborFinder - همسایه‌یابی

```java
public class NeighborFinder {
    public List<Position> findNeighbors(Position pos) { 4-way or 8-way }
    public List<Position> findNeighbors(Position pos, Adjacency adjacency) { ... }
}
```

- `Adjacency.FOUR_WAY` (بالا، پایین، چپ، راست) یا `EIGHT_WAY` (شامل قطری)
- `GridTopology` مرزها را مدیریت می‌کند (wrap یا نه)

### WaveGenerator - موج

یک افکت موجی که از یک نقطه شروع می‌شود و به بیرون گسترش می‌یابد. از `BoardChannel` برای انتشار استفاده می‌کند.

### PatternMatcher - تطبیق الگو

بررسی می‌کند آیا یک الگوی خاص (مثلا خط، مربع، L شکل) روی برد لمس شده است.

### RandomFeature - تصادفی

متدهای کمکی برای انتخاب موقعیت تصادفی، رنگ تصادفی، shuffle.

### AnimationSystem - در بخش بعدی به تفصیل

---

## AnimationSystem - انیمیشن‌های win/lose/standby/countdown

این یکی از پیچیده‌ترین کلاس‌های موتور است و تمام انیمیشن‌های درخواستی شما را پیاده می‌کند.

### طراحی کلی

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

**الگوی generation-based cooperative cancellation:**

- هر انیمیشن جدید `generation.incrementAndGet()` می‌کند و `RunToken` با آن generation می‌سازد.
- `RunToken.isCancelled()` چک می‌کند `generation.get() != myGeneration` → اگر true، یعنی یک انیمیشن جدیدتر شروع شده و این انیمیشن منسوخ شده.
- `RunToken.sleep(ms)` و `show(board)` قبل از انجام کار `isCancelled()` را چک می‌کنند. اگر cancel شده باشد، `sleep` false برمی‌گرداند و `show` و `pause` `AnimationCancelledException` پرتاب می‌کنند (یک exception ارزان بدون stacktrace).
- این exception داخل `run` catch می‌شود و future به حالت cancelled می‌رود، نه failed.
- `cancelCurrent()` هم generation را increment می‌کند و Future را cancel می‌کند (interrupt).

**چرا این الگو؟** چون انیمیشن‌ها روی یک `SingleThreadExecutor` اجرا می‌شوند، و ما نمی‌خواهیم thread را با force kill متوقف کنیم (که ممکن است board را در حالت ناقص رها کند). به جای آن، انیمیشن به صورت cooperative چک می‌کند که آیا cancel شده و اگر بله، خودش به صورت تمیز خارج می‌شود.

**CompletableFuture:** هر انیمیشن یک `CompletableFuture<Void>` برمی‌گرداند که:
- وقتی انیمیشن به صورت طبیعی تمام شود، complete می‌شود
- وقتی supersede شود (انیمیشن جدید شروع شود)، cancelled می‌شود
- وقتی exception دهد، exceptionally complete می‌شود

این به بازی اجازه می‌دهد انیمیشن‌ها را chain کند: `playCountdown().thenRun(() -> startGame())`

### انواع انیمیشن

#### Countdown - قبل از شروع بازی

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
        token.show(new Board<>(width, height, colors[3-i])); // کل برد یک رنگ
        if (!token.sleep(digitDurationMs)) return;
    }
    token.clear();
}

private void playScalableCountdown(RunToken token, long digitDurationMs) {
    for (int digit=3; digit>=1; digit--) {
        token.show(renderDigit(digit)); // رندر رقم با الگوی 5x3
        if (!token.sleep(digitDurationMs)) return;
    }
    for (int i=0; i<3; i++) { // چشمک سبز پایان
        token.show(new Board<>(width, height, GREEN));
        if (!token.sleep(150)) return;
        token.clear();
        if (!token.sleep(150)) return;
    }
}
```

- `renderDigit`: الگوی boolean[][] برای هر رقم (1,2,3) را به مرکز برد می‌کشد با رنگ متفاوت (RED برای 3، YELLOW برای 2، GREEN برای 1)

#### Win - برد

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

#### Lose - باخت

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

#### Standby - حالت انتظار (بی‌نهایت تا cancel)

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
        // ... سایر مرزها
        token.show(board);
        token.pause(200);
    }
}
```

**نکته:** `BREATHING` و سایر standby ها حلقه بی‌نهایت دارند (`for(;;)` یا `while(true)`) و فقط وقتی `token.isCancelled()` true شود یا `sleep` false برگرداند خارج می‌شوند. این یعنی تا وقتی `cancelCurrent()` یا یک انیمیشن جدید صدا زده نشود، برای همیشه اجرا می‌شوند. برای همین در مثال بازی نمونه، ما `get(2, SECONDS)` می‌کنیم که بعد از 2 ثانیه TimeoutException می‌دهد و سپس `cancelCurrent()` می‌کنیم.

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

## EventBus - سیستم رویداد thread-safe

### طراحی

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
        private final BlockingQueue<GameEvent> blockingQueue; // برای BLOCK policy
        private final ArrayDeque<GameEvent> ring; // برای DROP_OLDEST
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
                        ringAvailable.tryAcquire(); // permit مربوط به آیتم حذف شده را مصرف کن
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

### دو سیاست overflow

1. **BLOCK:** از `LinkedBlockingQueue` با ظرفیت محدود استفاده می‌کند. `offer` با timeout بلاک می‌کند (پیش‌فرض 200ms). اگر subscriber در این زمان تخلیه نکرد، event drop می‌شود و `droppedEvents` increment می‌شود. این برای subscriber های کند که نباید event از دست بدهند اما نباید publisher را برای همیشه بلاک کنند مناسب است.

2. **DROP_OLDEST (پیش‌فرض):** از `ArrayDeque` + `ReentrantLock` + `Semaphore` استفاده می‌کند.
   - `ring` خود Deque است که با `ringLock` محافظت می‌شود
   - `ringAvailable` یک Semaphore است که تعداد آیتم‌های داخل ring را می‌شمارد. هر بار که آیتم اضافه می‌شود `release()`, هر بار که آیتم برداشته می‌شود `acquire()`
   - وقتی ring پر است، قدیمی‌ترین آیتم (`pollFirst`) حذف می‌شود و `tryAcquire()` صدا زده می‌شود تا permit مربوطه مصرف شود (تا `availablePermits()` همیشه با `ring.size()` برابر بماند)
   - این از `BlockingQueue` کارآمدتر است چون Deque اجازه حذف از اول را می‌دهد (برای DROP_OLDEST)

**چرا Semaphore + Deque به جای BlockingQueue برای DROP_OLDEST؟** چون `LinkedBlockingQueue` فقط از یک انتها حذف می‌کند و نمی‌تواند قدیمی‌ترین را drop کند در حالی که جدیدترین را نگه دارد. `ArrayDeque` این را ممکن می‌کند اما thread-safe نیست، پس با `ReentrantLock` و `Semaphore` ترکیب شده.

**CopyOnWriteArrayList برای subscriptions:** مشابه TileGatewayClient، اضافه/حذف subscription نادر است اما `publish` مکرر است. COWAL برای خواندن بدون lock بهینه است.

**drainLoop:** هر subscription یک `SingleThreadExecutor` دارد که در حلقه `take` می‌کند و به listener تحویل می‌دهد. اگر listener exception دهد، catch می‌شود و حلقه ادامه می‌یابد (یک listener buggy کل bus را down نمی‌کند).

---

## EngineFrameRouter و TouchFrameRouter

### EngineFrameRouter

یک `FrameListener` که `DATA_IN` را با `ColorTileCodec` decode می‌کند و به صورت `Board<Boolean>` (یا `Board<TileColor>`) درمی‌آورد. همچنین reassembly timeout را مدیریت می‌کند: اگر یک لمس در چند فریم تکه‌تکه بیاید، تا `frameReassemblyTimeout` (پیش‌فرض 500ms) صبر می‌کند تا فریم‌های بعدی بیایند و سپس یک `Board` کامل می‌سازد.

### TouchFrameRouter

`Board<Boolean>` را به `TileEvent` تبدیل می‌کند و به session درست route می‌کند:

```java
public class TouchFrameRouter {
    private final Function<String, Optional<GameSessionImpl>> sessionLookup;
    private final Supplier<Optional<String>> exclusiveOwner;

    public void route(Board<Boolean> touchBoard) {
        List<Position> touched = touchBoard.positionsWhere(Boolean.TRUE::equals);
        for (Position pos : touched) {
            // اگر sessionId در payload باشد، به آن session بفرست
            // وگرنه به exclusive owner (تنها session فعال) بفرست
            GameSessionImpl session = exclusiveOwner.flatMap(sessionLookup).orElse(...);
            if (session!=null) session.handleTileEvent(TileEvent.touch(pos, session.sessionId()));
        }
    }
}
```

---

## SSE - استریم به فرانت‌اند

```
GameSessionImpl.publishBoard() → eventBus.publish(BOARD_UPDATED)
                            → SseGameEventPublisher → SseEmitter → HTTP client
```

- `SseGameEventPublisher`: به `GameEventBus` subscribe می‌شود و هر `GameEvent` را به `SseGameEvent` تبدیل می‌کند و به تمام `SseEmitter` های متصل می‌فرستد
- `GameEventSseEmitter`: wrapper دور Spring `SseEmitter` با heartbeat
- `SseGameEventType`: `SESSION_STARTED`, `BOARD_UPDATED`, `SCORE_UPDATED`, `TICK`, `SESSION_FINISHED`...

---

## لایه Spring - AutoConfiguration

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

- تمام Bean ها `@ConditionalOnMissingBean` هستند → اگر شما Bean خودتان را تعریف کنید، auto-config عقب می‌نشیند
- `gameRegistry` تمام `Game` Bean های موجود در context را auto-register می‌کند → کافی است بازی را به عنوان `@Bean` یا `@Component` تعریف کنید
- `GameEngineManager` بعد از `gameEventBus` ساخته می‌شود (`@DependsOn`)

### GameEngineManager - پل بین Spring و موتور framework-free

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
        this.engine = null; // فوری visible به current()/require()
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

**نکات concurrency:**
- `engine` volatile → خواندن بدون synchronized visibility دارد، اما نوشتن با synchronized
- `shutdownCurrentEngine` null-safe و idempotent → اگر دو بار disconnect بیاید، NPE نمی‌دهد
- `engine = null` قبل از `close()` → `current()`/`require()` هرگز یک engine نیمه-bسته را نمی‌بینند
- `@PreDestroy` تضمین می‌کند engine و thread های daemon آن هنگام توقف Spring context آزاد شوند، حتی اگر هیچ disconnect event ای نیامده باشد

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

قابل تنظیم در `application.yml`.

---

## آموزش گام به گام ساخت بازی

### گام 1: کلاس بازی را بساز

```java
public class MyFirstGame implements Game {

    private final GameDescriptor descriptor = GameDescriptor.builder("my-first-game", "My First Game")
        .category("TUTORIAL")
        .description("اولین بازی من")
        .boardSize(8, 8)
        .players(1, 1)
        .build();

    @Override public GameDescriptor descriptor() { return descriptor; }

    @Override
    public void onStart(GameContext ctx) {
        ctx.fillBoard(TileColor.OFF);
        ctx.scores().resetAll();
        // کل برد را سبز کن
        ctx.fillBoard(TileColor.GREEN);
    }

    @Override
    public void onTileEvent(GameContext ctx, TileEvent event) {
        // هر تایل لمس شده را قرمز کن و امتیاز بده
        ctx.setTile(event.position().row(), event.position().col(), TileColor.RED);
        ctx.scores().add(ctx.players().get(0).id(), 1);

        // اگر 10 امتیاز گرفت، برد
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

### گام 2: به عنوان Bean ثبت کن

```java
@Configuration
public class MyGameConfig {
    @Bean
    public Game myFirstGame() {
        return new MyFirstGame();
    }
}
```

یا با `@Component`:

```java
@Component
public class MyFirstGame implements Game { ... }
```

### گام 3: بازی را شروع کن (REST)

```bash
curl -X POST http://localhost:8080/api/v1/games/sessions \
  -H "Content-Type: application/json" \
  -d '{"gameId":"my-first-game","players":[{"name":"Ali"}]}'
```

### مثال پیشرفته - با انیمیشن‌ها

```java
@Override
public void onStart(GameContext ctx) {
    ctx.fillBoard(TileColor.OFF);

    // 1. Standby 2 ثانیه
    try {
        ctx.animations().playStandbyAnimation(StandbyAnimationType.BREATHING).get(2, SECONDS);
    } catch (Exception e) {
        ctx.animations().cancelCurrent();
    }

    // 2. Countdown
    ctx.animations().playCountdown(800).join();

    // 3. شروع بازی واقعی
    ctx.state().put("score", 0);
    ctx.fillBoard(TileColor.BLUE);

    // 4. تایمر 30 ثانیه
    ctx.timer().startCountdown(Duration.ofSeconds(30), () -> {
        ctx.animations().playLoseAnimation(LoseAnimationType.FADE_TO_RED)
            .thenRun(() -> ctx.loseSession());
    });
}

@Override
public void onTileEvent(GameContext ctx, TileEvent event) {
    // منطق بازی...

    // اگر باخت:
    ctx.animations().playLoseAnimation(LoseAnimationType.DESCENDING_CURTAIN)
        .thenRun(() -> ctx.loseSession());

    // اگر برد:
    ctx.animations().playWinAnimation(WinAnimationType.FIREWORKS)
        .thenRun(() -> ctx.winSession(ctx.players()));
}
```

### استفاده از Feature های دیگر

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
if (ctx.combos().currentCombo() >= 5) { /* جایزه کمبو */ }

// HealthSystem
ctx.health().damage(playerId, 10);
if (ctx.health().isDead(playerId)) ctx.loseSession();
```

---

## بررسی کدهای پیچیده - Concurrency

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

- `compareAndSet(null, sessionId)` یک عملیات اتمی است: فقط اگر مقدار فعلی null باشد، آن را به sessionId تغییر می‌دهد و true برمی‌گرداند. اگر در همین لحظه thread دیگری هم startGame کند، یکی موفق می‌شود و دیگری fail.
- این بدون synchronized block سراسری، مالکیت انحصاری برد را تضمین می‌کند.
- rollback با `compareAndSet(sessionId, null)` تضمین می‌کند اگر ساخت session fail شد، مالکیت آزاد شود، اما فقط اگر هنوز مالک همین sessionId باشد (اگر در این فاصله session دیگری مالک شده، rollback نباید مالکیت آن را پاک کند).

### 2. SessionLifecycle - CAS state machine

```java
public class SessionLifecycle {
    private final AtomicReference<GameStatus> status = new AtomicReference<>(GameStatus.CREATED);

    public boolean start() {
        return status.compareAndSet(CREATED, RUNNING);
    }

    public boolean finish(GameStatus finalStatus) {
        while (true) {
            GameStatus current = status.get();
            if (current==FINISHED || current==STOPPED) return false; // قبلا تمام شده
            if (status.compareAndSet(current, finalStatus)) return true;
        }
    }
}
```

- `finish` با حلقه CAS تضمین می‌کند فقط یک thread موفق به تغییر status به FINISHED شود، حتی اگر همزمان `winSession`, `loseSession`, `stopSession` و TTL reaper همزمان صدا زده شوند.
- این idempotent است: دومین فراخوانی `finish` false برمی‌گرداند.

### 3. GameEventBusImpl - Semaphore + Deque برای DROP_OLDEST

توضیح کامل در بخش EventBus داده شد. نکات کلیدی:

- `ringLock` (ReentrantLock) از `ring` (ArrayDeque) محافظت می‌کند
- `ringAvailable` (Semaphore) تعداد آیتم‌ها را می‌شمارد
- هنگام drop قدیمی‌ترین: `pollFirst()` + `tryAcquire()` → permit مربوط به آیتم حذف شده مصرف می‌شود تا `availablePermits() == ring.size()` بماند
- `offerDropOldest` و `takeDropOldest` هر دو از `ringLock` و `ringAvailable` استفاده می‌کنند → thread-safe
- `droppedEvents` (AtomicLong) تعداد drop ها را می‌شمارد (برای متریک)

### 4. BoardChannel - ReentrantLock + gatewayWriteLock + coalescing

توضیح کامل در بخش BoardChannel داده شد.

### 5. AnimationSystem - generation + CompletableFuture + SingleThreadExecutor

توضیح کامل در بخش AnimationSystem داده شد.

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

- `ConcurrentHashMap` برای خواندن/نوشتن همزمان thread-safe است
- `computeIfAbsent` اتمی است: اگر دو thread همزمان برای یک playerId جدید `getOrCreate` کنند، فقط یک AtomicInteger ساخته می‌شود
- `AtomicInteger.addAndGet` با CAS پیاده‌سازی شده، بدون lock سراسری

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

- `volatile` تضمین می‌کند تغییرات `startedAt` و غیره فوری برای thread های دیگر visible باشد (بدون نیاز به synchronized)
- `getAndSet(null)` تضمین می‌کند callback فقط یک بار اجرا شود، حتی اگر `checkExpiry` همزمان از دو thread صدا زده شود

---

## تست‌ها

```bash
mvn test -pl tileboard-game-engine
```

- `ColorTileCodecTest`: تست codec رنگ
- `EngineFrameRouterTest`, `EngineFrameRouterReassemblyTest`: تست مسیریابی و reassembly
- `BoardChannelConcurrencyTest`: تست همزمانی BoardChannel
- `GameSessionImplTest`, `SessionLifecycleTest`: تست lifecycle
- `GameEventBusImplTest`, `GameEventBusImplConcurrencyTest`: تست EventBus
- `AnimationSystemCancellationTest`: تست cancellation انیمیشن
- `FeatureSystemsTest`: تست تمام feature ها (Score, Health, Level, Combo, Timer, ...)
- `TouchFrameRouterTest`, `GameEngineManagerTest`

---

## وابستگی‌ها

```xml
<dependency>
    <groupId>com.tileboard</groupId>
    <artifactId>tileboard-serial-protocol</artifactId>
    <version>1.0.0</version>
</dependency>
```

- `slf4j-api`: logging facade
- `jackson-databind`: برای SSE JSON serialization
- `spring-boot-autoconfigure` (optional): فقط برای auto-config layer
- `micrometer-core`: برای متریک‌ها (optional)

---

**نویسنده:** تیم Tileboard Platform  
**نسخه:** 1.0.0  
**جاوا:** 17+
