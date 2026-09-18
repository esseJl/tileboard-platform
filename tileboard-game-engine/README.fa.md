
## README_FA.md (فارسی)

```markdown
# موتور بازی تایل‌بورد (Tileboard Game Engine)

یک موتور بازی تولید-آماده (production-ready)، مستقل از لایه انتقال داده، ساخته شده بر روی پروتکل سریال تایل‌بورد.

## 📋 فهرست مطالب

- [معرفی](#معرفی)
- [فلسفه طراحی](#فلسفه-طراحی)
- [ویژگی‌های کلیدی](#ویژگیهای-کلیدی)
- [نصب و راه‌اندازی](#نصب-و-راهاندازی)
- [راهنمای سریع](#راهنمای-سریع)
- [سیستم انیمیشن](#سیستم-انیمیشن)
- [ویژگی‌های Built-in](#ویژگیهای-built-in)
- [مثال‌های کامل](#مثالهای-کامل)
- [معماری](#معماری)
- [تست‌ها](#تستها)
- [مشارکت](#مشارکت)

---

## 🎯 معرفی

**Tileboard Game Engine** یک فریمورک انعطاف‌پذیر برای ساخت بازی‌های تعاملی روی صفحات کاشی‌های لمسی LED است. این موتور به شما امکان می‌دهد تا بدون نگرانی از جزئیات سخت‌افزاری و ارتباط سریال، تمرکز خود را روی منطق بازی بگذارید.

### چرا این کتابخانه؟

1. **جداسازی کامل concerns**: منطق بازی، سخت‌افزار، و ارتباطات کاملاً از هم جدا هستند
2. **Framework-free در هسته**: هیچ وابستگی اجباری به Spring یا هر فریمورک دیگری
3. **Production-ready**: Thread-safe، مقیاس‌پذیر، و آماده برای محیط‌های واقعی
4. **Rich feature set**: امتیازدهی، سلامت، سطوح، combo، الگوها، و... از قبل آماده
5. **SSE Streaming**: پخش رویدادهای بازی به صورت real-time به مشتری‌ها
6. **سیستم انیمیشن پیشرفته**: انیمیشن‌های آماده برای شروع، برد، باخت و حالت آماده‌به‌کار

---

## 🧠 فلسفه طراحی

### اصول بنیادین

#### 1. **تفکیک مسئولیت‌ها (Separation of Concerns)**
```
┌─────────────────────────────────────────────────────────────┐
│                        Game Layer                            │
│  (منطق بازی - بدون آگاهی از سخت‌افزار)                      │
├─────────────────────────────────────────────────────────────┤
│                       Engine Layer                           │
│  (مدیریت session، event bus، features)                      │
├─────────────────────────────────────────────────────────────┤
│                      Protocol Layer                          │
│  (تبدیل Board<TileColor> به پروتکل سریال)                   │
├─────────────────────────────────────────────────────────────┤
│                     Hardware Layer                           │
│  (ارتباط UART، تایل‌بورد فیزیکی)                            │
└─────────────────────────────────────────────────────────────┘
```

#### 2. **Event-Driven Architecture**
تمام ارتباطات از طریق یک **GameEventBus** مرکزی انجام می‌شود:
- بازی رویداد منتشر می‌کند (امتیاز تغییر کرد، سطح بالا رفت)
- موتور این رویدادها را به SSE emitters، logger، و... می‌رساند
- کاملاً decoupled - اضافه کردن subscriber جدید نیازی به تغییر بازی ندارد

#### 3. **Immutability & Thread Safety**
- تمام model objects (Player, TileEvent, GameResult) immutable هستند
- Built-in features از concurrent collections استفاده می‌کنند
- هر session یک thread جداگانه برای tick loop دارد

#### 4. **Dependency Inversion**
```java
// بازی به interface وابسته است، نه implementation
public interface Game {
    void onStart(GameContext ctx);  // ← ctx همه چیز را فراهم می‌کند
    void onTileEvent(GameContext ctx, TileEvent event);
}

// موتور implementation تزریق می‌کند
GameContext ctx = new GameSessionImpl(...);
game.onStart(ctx);
```

#### 5. **Extensibility دون Modification**
افزودن feature جدید:
```java
// فقط یک class جدید بسازید
public class MyCustomFeature {
    public void doSomething() { ... }
}

// در GameSessionImpl رجیستر کنید
private final MyCustomFeature myFeature = new MyCustomFeature();

@Override
public MyCustomFeature myFeature() { return myFeature; }
```

---

## ✨ ویژگی‌های کلیدی

### 🎨 سیستم انیمیشن (جدید!)

یک سیستم کامل برای نمایش انیمیشن‌های حرفه‌ای در حالت‌های مختلف بازی:

#### **انیمیشن شمارش معکوس**
```java
// نمایش 3، 2، 1 قبل از شروع بازی
ctx.animations().playCountdown()
    .thenRun(() -> {
        // بازی شروع شد
        startGame();
    });
```

**ویژگی‌ها:**
- مقیاس‌پذیر نسبت به سایز بورد (حداقل 3×5)
- برای بوردهای کوچکتر از حالت ساده استفاده می‌کند
- رنگ‌بندی: قرمز (3)، زرد (2)، سبز (1)
- فلش سبز پایانی برای نشان دادن شروع

#### **انیمیشن‌های برد** (4 نوع)

```java
// انفجار شعاعی از مرکز به بیرون
ctx.animations().playWinAnimation(WinAnimationType.RADIAL_BURST);

// موج رنگین‌کمانی
ctx.animations().playWinAnimation(WinAnimationType.RAINBOW_SWEEP);

// درخشش تصادفی
ctx.animations().playWinAnimation(WinAnimationType.SPARKLE);

// آتش‌بازی
ctx.animations().playWinAnimation(WinAnimationType.FIREWORKS);
```

#### **انیمیشن‌های باخت** (4 نوع)

```java
// محو شدن تدریجی به قرمز
ctx.animations().playLoseAnimation(LoseAnimationType.FADE_TO_RED);

// پرده نزولی قرمز
ctx.animations().playLoseAnimation(LoseAnimationType.DESCENDING_CURTAIN);

// فروپاشی
ctx.animations().playLoseAnimation(LoseAnimationType.CRUMBLE);

// پالس قرمز
ctx.animations().playLoseAnimation(LoseAnimationType.PULSE_RED);
```

#### **انیمیشن‌های حالت آماده‌به‌کار** (4 نوع)

```java
// تنفس آرام (برای حالت idle)
ctx.animations().playStandbyAnimation(StandbyAnimationType.BREATHING);

// پالس گوشه‌ها
ctx.animations().playStandbyAnimation(StandbyAnimationType.CORNER_PULSE);

// موج مرزی
ctx.animations().playStandbyAnimation(StandbyAnimationType.WAVE_BORDER);

// چشمک تصادفی
ctx.animations().playStandbyAnimation(StandbyAnimationType.RANDOM_TWINKLE);
```

### 🎮 ویژگی‌های Built-in

#### **ScoreSystem** - سیستم امتیازدهی
```java
ctx.scores().add(playerId, 10);           // اضافه کردن امتیاز
ctx.scores().get(playerId);               // دریافت امتیاز فعلی
ctx.scores().leader();                    // بازیکن برتر
ctx.scores().allScores();                 // همه امتیازها
```

#### **HealthSystem** - سیستم سلامت/جان
```java
ctx.health().damage(playerId, 1);         // کاهش یک جان
ctx.health().heal(playerId, 1);           // افزایش یک جان
ctx.health().isAlive(playerId);           // زنده است؟
ctx.health().allDead();                   // همه مردند؟
```

#### **LevelSystem** - سیستم سطوح
```java
ctx.levels().advance();                   // سطح بعدی
ctx.levels().currentLevel();              // سطح فعلی
ctx.levels().currentSpeed();              // سرعت بر اساس سطح
ctx.levels().setSpeedScaler(lvl -> ...);  // تنظیم سرعت
```

#### **ComboTracker** - ردیابی combo
```java
int combo = ctx.combos().hit();           // ثبت یک hit موفق
int multiplier = ctx.combos().multiplier(5); // ضریب بر اساس combo
ctx.combos().reset();                     // ریست کردن
```

#### **GameTimer** - تایمر بازی
```java
ctx.timer().start();                      // شروع تایمر
ctx.timer().elapsed();                    // زمان سپری شده
ctx.timer().startCountdown(Duration.ofSeconds(60), () -> {
    ctx.loseSession();                    // پایان زمان
});
```

#### **PatternMatcher** - تشخیص الگو
```java
List<Position> pattern = List.of(
    new Position(0, 0),
    new Position(0, 1),
    new Position(1, 1)
);

boolean matched = ctx.patterns().tailMatches(
    ctx.touchHistory().positionOrder(),
    pattern
);
```

#### **NeighborFinder** - پیدا کردن همسایه‌ها
```java
List<Position> neighbors = ctx.neighbors()
    .find(new Position(3, 3));            // همسایه‌های 4-way

List<Position> diagonal = ctx.neighbors()
    .findDiagonal(new Position(3, 3));    // همسایه‌های مورب
```

#### **WaveGenerator** - تولید موج‌های بصری
```java
ctx.waves().sweepDown(TileColor.BLUE, 100);
ctx.waves().ripple(center, TileColor.GREEN, 80);
ctx.waves().blink(TileColor.RED, TileColor.OFF, 3, 200);
```

#### **MemoryFeature** - بازی‌های حافظه
```java
ctx.memory().setTarget(sequence);
ctx.memory().addInput(position);
ctx.memory().isFullyCorrect();
```

#### **ReactionSpeedTracker** - سنجش سرعت واکنش
```java
ctx.reactionSpeed().stimulus();           // نمایش محرک
// بازیکن لمس می‌کند...
Duration reaction = ctx.reactionSpeed().lastReaction();
```

#### **GraphFeature** - عملیات گراف
```java
List<Position> path = ctx.graph().shortestPath(
    from, to, pos -> board.get(pos) != TileColor.OFF
);

List<List<Position>> components = ctx.graph()
    .connectedComponents(pos -> ...);
```

---

## 🚀 نصب و راه‌اندازی

### Maven

```xml
<dependency>
    <groupId>com.tileboard</groupId>
    <artifactId>tileboard-game-engine</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Gradle

```gradle
implementation 'com.tileboard:tileboard-game-engine:1.0.0'
```

### پیش‌نیازها

- Java 17 یا بالاتر
- `tileboard-serial-protocol:1.0.0`
- (اختیاری) Spring Boot 3.3.2+ برای auto-configuration

---

## ⚡ راهنمای سریع

### 1. ساخت یک بازی ساده

```java
package com.example.games;

import com.tileboard.engine.core.*;
import com.tileboard.engine.model.*;
import org.springframework.stereotype.Component;

@Component  // ← اگر Spring استفاده می‌کنید
public class SimpleColorMatch implements Game {

    private static final GameDescriptor DESCRIPTOR = GameDescriptor
            .builder("color-match", "تطبیق رنگ")
            .category("PUZZLE")
            .description("تایل‌های هم‌رنگ را لمس کنید")
            .boardSize(8, 8)
            .players(1, 1)
            .build();

    @Override
    public GameDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void onStart(GameContext ctx) {
        // نمایش شمارش معکوس
        ctx.animations().playCountdown().thenRun(() -> {
            // یک تایل تصادفی روشن کنید
            Position target = ctx.random().randomPosition();
            ctx.state().put("target", target);
            ctx.setTile(target.row(), target.col(), TileColor.GREEN);
            
            // تایمر 10 ثانیه
            ctx.timer().startCountdown(Duration.ofSeconds(10), () -> {
                ctx.loseSession();
            });
        });
    }

    @Override
    public void onTileEvent(GameContext ctx, TileEvent event) {
        Position target = ctx.state().get("target", Position.class).orElse(null);
        
        if (event.position().equals(target)) {
            // درست زد!
            ctx.scores().add("player", 10);
            
            // تایل بعدی
            Position newTarget = ctx.random().randomPosition();
            ctx.state().put("target", newTarget);
            ctx.fillBoard(TileColor.OFF);
            ctx.setTile(newTarget.row(), newTarget.col(), TileColor.GREEN);
            
            // بررسی برد
            if (ctx.scores().get("player") >= 50) {
                ctx.winSession(List.of(Player.solo("player")));
            }
        } else {
            // اشتباه زد
            ctx.health().damage("player");
            if (!ctx.health().isAlive("player")) {
                ctx.loseSession();
            }
        }
    }

    @Override
    public void onStop(GameContext ctx, GameResult result) {
        if (result.hasWinner()) {
            ctx.animations().playWinAnimation();
        } else {
            ctx.animations().playLoseAnimation();
        }
    }
}
```

### 2. راه‌اندازی موتور (Spring Boot)

```java
@SpringBootApplication
public class TileboardApp {
    public static void main(String[] args) {
        SpringApplication.run(TileboardApp.class, args);
    }
}
```

```yaml
# application.yml
tileboard:
  engine:
    tick-interval: 100ms
```

### 3. شروع بازی از Controller

```java
@RestController
@RequestMapping("/api/games")
public class GameController {

    private final GameEngineManager engineManager;

    public GameController(GameEngineManager engineManager) {
        this.engineManager = engineManager;
    }

    @PostMapping("/start")
    public ResponseEntity<StartGameResponse> startGame(
            @RequestBody StartGameRequest request) {
        
        GameEngine engine = engineManager.require();
        
        String sessionId = engine.startGame(
            request.gameId(),
            List.of(Player.solo(request.playerName()))
        );
        
        return ResponseEntity.ok(new StartGameResponse(sessionId));
    }

    @PostMapping("/{sessionId}/stop")
    public ResponseEntity<Void> stopGame(@PathVariable String sessionId) {
        engineManager.current().ifPresent(engine -> 
            engine.stopGame(sessionId)
        );
        return ResponseEntity.ok().build();
    }

    @GetMapping("/available")
    public ResponseEntity<List<GameDescriptor>> listGames() {
        GameEngine engine = engineManager.require();
        return ResponseEntity.ok(engine.registry().listAll());
    }

    @GetMapping("/{sessionId}/events")
    public SseEmitter streamEvents(@PathVariable String sessionId) {
        GameEventBus eventBus = ...; // از Spring context
        return GameEventSseEmitter.forSession(sessionId, eventBus);
    }
}
```

---

## 🎬 سیستم انیمیشن - راهنمای کامل

### مفاهیم کلیدی

#### 1. **Non-blocking Execution**
تمام انیمیشن‌ها روی یک thread جداگانه اجرا می‌شوند:

```java
CompletableFuture<Void> future = ctx.animations().playCountdown();

// کد شما بلافاصله ادامه می‌یابد
doSomethingElse();

// منتظر بمانید تا انیمیشن تمام شود
future.get();

// یا callback تعریف کنید
future.thenRun(() -> System.out.println("انیمیشن تمام شد!"));
```

#### 2. **Cancellation**
انیمیشن‌ها قابل لغو هستند:

```java
CompletableFuture<Void> standby = ctx.animations()
    .playStandbyAnimation(StandbyAnimationType.BREATHING);

// بعد از 5 ثانیه لغو کنید
Thread.sleep(5000);
ctx.animations().cancelCurrent();
```

#### 3. **Sequential Chaining**
انیمیشن‌ها را به صورت زنجیره‌ای اجرا کنید:

```java
ctx.animations().playCountdown()
    .thenCompose(v -> ctx.animations().playWinAnimation())
    .thenRun(() -> ctx.fillBoard(TileColor.OFF));
```

### مثال‌های پیشرفته

#### **بازی با انیمیشن‌های سفارشی**

```java
@Override
public void onStart(GameContext ctx) {
    // شمارش معکوس سریع‌تر
    ctx.animations().playCountdown(500)  // هر عدد 500ms
        .thenRun(() -> {
            initializeGame(ctx);
        });
}

@Override
public void onTick(GameContext ctx) {
    // بررسی زمان باقی‌مانده
    if (ctx.timer().remaining().getSeconds() <= 5) {
        // فلش قرمز هشدار
        if (ctx.timer().remaining().toMillis() % 1000 < 500) {
            ctx.fillBoard(TileColor.RED);
        } else {
            ctx.fillBoard(TileColor.OFF);
        }
    }
}

@Override
public void onStop(GameContext ctx, GameResult result) {
    if (result.hasWinner()) {
        // انتخاب تصادفی انیمیشن برد
        WinAnimationType[] types = WinAnimationType.values();
        WinAnimationType randomType = types[
            (int) (Math.random() * types.length)
        ];
        ctx.animations().playWinAnimation(randomType);
    } else {
        // انیمیشن باخت مخصوص
        ctx.animations().playLoseAnimation(LoseAnimationType.CRUMBLE)
            .thenRun(() -> {
                // نمایش امتیاز نهایی روی بورد
                displayScore(ctx, result);
            });
    }
}
```

#### **انیمیشن Standby در حلقه**

```java
public class IdleScreenManager {
    
    private CompletableFuture<Void> currentStandby;
    
    public void startIdleMode(GameContext ctx) {
        runStandbyLoop(ctx);
    }
    
    private void runStandbyLoop(GameContext ctx) {
        if (ctx.status() != GameStatus.RUNNING) {
            return;  // بازی شروع شده، متوقف کن
        }
        
        // انتخاب تصادفی انیمیشن
        StandbyAnimationType[] types = StandbyAnimationType.values();
        StandbyAnimationType type = types[
            (int) (Math.random() * types.length)
        ];
        
        currentStandby = ctx.animations().playStandbyAnimation(type);
        
        // بعد از تمام شدن، انیمیشن بعدی
        currentStandby.thenRun(() -> {
            try {
                Thread.sleep(2000);  // وقفه بین انیمیشن‌ها
                runStandbyLoop(ctx);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }
    
    public void stop() {
        if (currentStandby != null) {
            currentStandby.cancel(true);
        }
    }
}
```

---

## 📚 مثال‌های کامل

### بازی Snake (مار)

```java
@Component
public class SnakeGame implements Game {
    
    @Override
    public void onStart(GameContext ctx) {
        ctx.animations().playCountdown().thenRun(() -> {
            // مقداردهی اولیه مار
            List<Position> snake = new ArrayList<>();
            snake.add(new Position(4, 4));
            ctx.state().put("snake", snake);
            ctx.state().put("direction", "RIGHT");
            
            // قرار دادن غذا
            spawnFood(ctx);
            
            // شروع حرکت خودکار
            ctx.timer().start();
        });
    }
    
    @Override
    public void onTick(GameContext ctx) {
        if (ctx.timer().elapsed().toMillis() % 500 != 0) return;
        
        List<Position> snake = ctx.state().get("snake", List.class).get();
        String direction = ctx.state().get("direction", String.class).get();
        
        // محاسبه موقعیت سر جدید
        Position head = snake.get(0);
        Position newHead = moveInDirection(head, direction);
        
        // بررسی برخورد
        if (isOutOfBounds(newHead, ctx) || snake.contains(newHead)) {
            ctx.loseSession();
            return;
        }
        
        // بررسی خوردن غذا
        Position food = ctx.state().get("food", Position.class).get();
        if (newHead.equals(food)) {
            snake.add(0, newHead);
            spawnFood(ctx);
            ctx.scores().add("player", 10);
        } else {
            snake.add(0, newHead);
            snake.remove(snake.size() - 1);
        }
        
        // رسم بورد
        drawBoard(ctx, snake, food);
    }
    
    @Override
    public void onTileEvent(GameContext ctx, TileEvent event) {
        // تغییر جهت بر اساس لمس
        Position touch = event.position();
        List<Position> snake = ctx.state().get("snake", List.class).get();
        Position head = snake.get(0);
        
        String newDirection = calculateDirection(head, touch);
        ctx.state().put("direction", newDirection);
    }
    
    private void drawBoard(GameContext ctx, List<Position> snake, Position food) {
        Board<TileColor> board = ctx.newBoard();
        
        // رسم بدن مار
        for (int i = 1; i < snake.size(); i++) {
            Position p = snake.get(i);
            board.set(p.row(), p.col(), TileColor.GREEN);
        }
        
        // رسم سر مار
        Position head = snake.get(0);
        board.set(head.row(), head.col(), TileColor.YELLOW);
        
        // رسم غذا
        board.set(food.row(), food.col(), TileColor.RED);
        
        ctx.publishBoard(board);
    }
}
```

### بازی Memory (حافظه)

```java
@Component
public class MemoryGame implements Game {
    
    @Override
    public void onStart(GameContext ctx) {
        ctx.animations().playCountdown().thenRun(() -> {
            // تولید توالی تصادفی
            int level = ctx.levels().currentLevel();
            List<Position> sequence = ctx.random().randomPositions(3 + level);
            ctx.memory().setTarget(sequence);
            
            // نمایش توالی
            showSequence(ctx, sequence).thenRun(() -> {
                ctx.state().put("playerTurn", true);
            });
        });
    }
    
    private CompletableFuture<Void> showSequence(
            GameContext ctx, 
            List<Position> sequence) {
        
        return CompletableFuture.runAsync(() -> {
            for (Position pos : sequence) {
                ctx.setTile(pos.row(), pos.col(), TileColor.BLUE);
                sleep(800);
                ctx.fillBoard(TileColor.OFF);
                sleep(200);
            }
        });
    }
    
    @Override
    public void onTileEvent(GameContext ctx, TileEvent event) {
        if (!ctx.state().get("playerTurn", Boolean.class).orElse(false)) {
            return;
        }
        
        ctx.memory().addInput(event.position());
        
        // فیدبک بصری
        ctx.setTile(event.position().row(), event.position().col(), 
                    TileColor.GREEN);
        
        if (!ctx.memory().isCorrectSoFar()) {
            // اشتباه!
            ctx.fillBoard(TileColor.RED);
            ctx.health().damage("player");
            
            if (!ctx.health().isAlive("player")) {
                ctx.loseSession();
            } else {
                // شروع دوباره
                ctx.memory().resetInput();
                showSequence(ctx, ctx.memory().target());
            }
        } else if (ctx.memory().isComplete()) {
            // کامل شد!
            ctx.levels().advance();
            ctx.scores().add("player", 100 * ctx.levels().currentLevel());
            
            // انیمیشن موفقیت
            ctx.waves().blink(TileColor.GREEN, TileColor.OFF, 3, 200);
            
            // سطح بعدی
            onStart(ctx);
        }
    }
}
```

---

## 🏗️ معماری

### Lifecycle یک Session

```
1. Application calls engineManager.require().startGame()
                    ↓
2. Engine creates GameSessionImpl
                    ↓
3. GameSessionImpl calls game.onStart(ctx)
                    ↓
4. [RUNNING] Tick loop starts, touch events routed
                    ↓
5. Game logic calls ctx.winSession() / ctx.loseSession()
                    ↓
6. Engine calls game.onStop(ctx, result)
                    ↓
7. Session marked FINISHED/STOPPED, cleanup
```

### Event Flow

```
Hardware Touch
      ↓
TileGatewayClient receives DATA_IN frame
      ↓
EngineFrameRouter decodes to Board<Boolean>
      ↓
GameEngineImpl routes to all active sessions
      ↓
GameSessionImpl.handleTileEvent()
      ↓
game.onTileEvent(ctx, event)
      ↓
Game logic updates state, publishes events
      ↓
GameEventBus notifies subscribers
      ↓
SseEmitter sends JSON to web clients
```

---

## 🧪 تست‌ها

### اجرای تست‌ها

```bash
mvn test
```

### ساختار تست‌ها

```
src/test/java/
├── com/tileboard/engine/
│   ├── core/
│   │   ├── GameEngineTest.java
│   │   ├── GameSessionTest.java
│   │   └── GameStateTest.java
│   ├── feature/
│   │   ├── AnimationSystemTest.java      ← تست‌های انیمیشن
│   │   ├── ScoreSystemTest.java
│   │   ├── HealthSystemTest.java
│   │   ├── PatternMatcherTest.java
│   │   └── ...
│   └── event/
│       └── GameEventBusTest.java
```

### مثال تست سفارشی

```java
class MyGameTest {
    
    private MockGameContext ctx;
    private MyGame game;
    
    @BeforeEach
    void setUp() {
        ctx = new MockGameContext(8, 8);
        game = new MyGame();
    }
    
    @Test
    void testWinCondition() {
        game.onStart(ctx);
        
        // شبیه‌سازی لمس‌ها
        for (int i = 0; i < 10; i++) {
            game.onTileEvent(ctx, TileEvent.touch(
                new Position(0, i % 8), "session"
            ));
        }
        
        assertEquals(GameStatus.FINISHED, ctx.status());
        assertTrue(ctx.result().hasWinner());
    }
    
    @Test
    void testAnimationsTriggered() {
        game.onStart(ctx);
        
        // بررسی شمارش معکوس فراخوانی شد
        verify(ctx.animations()).playCountdown();
        
        // شبیه‌سازی برد
        ctx.winSession(List.of(Player.solo("test")));
        game.onStop(ctx, ctx.result().get());
        
        // بررسی انیمیشن برد فراخوانی شد
        verify(ctx.animations()).playWinAnimation(any());
    }
}
```

---

## 🤝 مشارکت

### گزارش باگ

مشکلی پیدا کردید؟ یک [Issue](https://github.com/yourrepo/issues) باز کنید.

### Feature Request

ایده‌ای دارید؟ در [Discussions](https://github.com/yourrepo/discussions) مطرح کنید.

### Pull Request

1. Fork کنید
2. Branch جدید بسازید (`git checkout -b feature/amazing-feature`)
3. Commit کنید (`git commit -m 'Add amazing feature'`)
4. Push کنید (`git push origin feature/amazing-feature`)
5. Pull Request باز کنید

---


