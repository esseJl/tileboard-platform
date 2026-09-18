
## README.md (English)

```markdown
# Tileboard Game Engine

A production-ready, transport-agnostic game engine built on top of tileboard-serial-protocol, providing a rich set of built-in features while staying framework-free at its core.

## 📋 Table of Contents

- [Introduction](#introduction)
- [Design Philosophy](#design-philosophy)
- [Key Features](#key-features)
- [Installation](#installation)
- [Quick Start](#quick-start)
- [Animation System](#animation-system)
- [Built-in Features](#built-in-features)
- [Complete Examples](#complete-examples)
- [Architecture](#architecture)
- [Testing](#testing)
- [Contributing](#contributing)

---

## 🎯 Introduction

**Tileboard Game Engine** is a flexible framework for building interactive games on LED touch tile boards. This engine allows you to focus on game logic without worrying about hardware details and serial communication.

### Why This Library?

1. **Complete Separation of Concerns**: Game logic, hardware, and communication are completely decoupled
2. **Framework-free Core**: No mandatory dependency on Spring or any other framework
3. **Production-ready**: Thread-safe, scalable, and ready for real-world environments
4. **Rich Feature Set**: Scoring, health, levels, combos, patterns, and more out of the box
5. **SSE Streaming**: Real-time game event streaming to clients
6. **Advanced Animation System**: Ready-made animations for start, win, lose, and standby states

---

## 🧠 Design Philosophy

### Core Principles

#### 1. **Separation of Concerns**
```
┌─────────────────────────────────────────────────────────────┐
│                        Game Layer                            │
│  (Game logic - hardware agnostic)                           │
├─────────────────────────────────────────────────────────────┤
│                       Engine Layer                           │
│  (Session management, event bus, features)                  │
├─────────────────────────────────────────────────────────────┤
│                      Protocol Layer                          │
│  (Board<TileColor> to serial protocol conversion)           │
├─────────────────────────────────────────────────────────────┤
│                     Hardware Layer                           │
│  (UART communication, physical tileboard)                   │
└─────────────────────────────────────────────────────────────┘
```

#### 2. **Event-Driven Architecture**
All communication flows through a central **GameEventBus**:
- Game publishes events (score changed, level up)
- Engine routes these to SSE emitters, loggers, etc.
- Fully decoupled - adding new subscribers requires no game changes

#### 3. **Immutability & Thread Safety**
- All model objects (Player, TileEvent, GameResult) are immutable
- Built-in features use concurrent collections
- Each session has a dedicated thread for tick loop

#### 4. **Dependency Inversion**
```java
// Game depends on interfaces, not implementations
public interface Game {
    void onStart(GameContext ctx);  // ← ctx provides everything
    void onTileEvent(GameContext ctx, TileEvent event);
}

// Engine injects implementation
GameContext ctx = new GameSessionImpl(...);
game.onStart(ctx);
```

#### 5. **Extensibility without Modification**
Adding a new feature:
```java
// Just create a new class
public class MyCustomFeature {
    public void doSomething() { ... }
}

// Register in GameSessionImpl
private final MyCustomFeature myFeature = new MyCustomFeature();

@Override
public MyCustomFeature myFeature() { return myFeature; }
```

---

## ✨ Key Features

### 🎨 Animation System (New!)

A complete system for displaying professional animations in different game states:

#### **Countdown Animation**
```java
// Display 3, 2, 1 before game start
ctx.animations().playCountdown()
    .thenRun(() -> {
        // Game started
        startGame();
    });
```

**Features:**
- Scalable to board size (minimum 3×5)
- Uses simple mode for smaller boards
- Color coding: red (3), yellow (2), green (1)
- Final green flash to indicate start

#### **Win Animations** (4 types)

```java
// Radial burst from center outward
ctx.animations().playWinAnimation(WinAnimationType.RADIAL_BURST);

// Rainbow wave
ctx.animations().playWinAnimation(WinAnimationType.RAINBOW_SWEEP);

// Random sparkle
ctx.animations().playWinAnimation(WinAnimationType.SPARKLE);

// Fireworks
ctx.animations().playWinAnimation(WinAnimationType.FIREWORKS);
```

#### **Lose Animations** (4 types)

```java
// Gradual fade to red
ctx.animations().playLoseAnimation(LoseAnimationType.FADE_TO_RED);

// Descending red curtain
ctx.animations().playLoseAnimation(LoseAnimationType.DESCENDING_CURTAIN);

// Crumble effect
ctx.animations().playLoseAnimation(LoseAnimationType.CRUMBLE);

// Red pulse
ctx.animations().playLoseAnimation(LoseAnimationType.PULSE_RED);
```

#### **Standby Animations** (4 types)

```java
// Gentle breathing (for idle state)
ctx.animations().playStandbyAnimation(StandbyAnimationType.BREATHING);

// Corner pulse
ctx.animations().playStandbyAnimation(StandbyAnimationType.CORNER_PULSE);

// Border wave
ctx.animations().playStandbyAnimation(StandbyAnimationType.WAVE_BORDER);

// Random twinkle
ctx.animations().playStandbyAnimation(StandbyAnimationType.RANDOM_TWINKLE);
```

### 🎮 Built-in Features

#### **ScoreSystem** - Score tracking
```java
ctx.scores().add(playerId, 10);           // Add score
ctx.scores().get(playerId);               // Get current score
ctx.scores().leader();                    // Get leader
ctx.scores().allScores();                 // All scores
```

#### **HealthSystem** - Health/lives tracking
```java
ctx.health().damage(playerId, 1);         // Reduce one life
ctx.health().heal(playerId, 1);           // Add one life
ctx.health().isAlive(playerId);           // Is alive?
ctx.health().allDead();                   // All dead?
```

#### **LevelSystem** - Level management
```java
ctx.levels().advance();                   // Next level
ctx.levels().currentLevel();              // Current level
ctx.levels().currentSpeed();              // Speed based on level
ctx.levels().setSpeedScaler(lvl -> ...);  // Set speed scaler
```

#### **ComboTracker** - Combo tracking
```java
int combo = ctx.combos().hit();           // Record successful hit
int multiplier = ctx.combos().multiplier(5); // Multiplier from combo
ctx.combos().reset();                     // Reset combo
```

#### **GameTimer** - Game timer
```java
ctx.timer().start();                      // Start timer
ctx.timer().elapsed();                    // Elapsed time
ctx.timer().startCountdown(Duration.ofSeconds(60), () -> {
    ctx.loseSession();                    // Time's up
});
```

#### **PatternMatcher** - Pattern detection
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

#### **NeighborFinder** - Find neighbors
```java
List<Position> neighbors = ctx.neighbors()
    .find(new Position(3, 3));            // 4-way neighbors

List<Position> diagonal = ctx.neighbors()
    .findDiagonal(new Position(3, 3));    // Diagonal neighbors
```

#### **WaveGenerator** - Visual wave effects
```java
ctx.waves().sweepDown(TileColor.BLUE, 100);
ctx.waves().ripple(center, TileColor.GREEN, 80);
ctx.waves().blink(TileColor.RED, TileColor.OFF, 3, 200);
```

#### **MemoryFeature** - Memory games
```java
ctx.memory().setTarget(sequence);
ctx.memory().addInput(position);
ctx.memory().isFullyCorrect();
```

#### **ReactionSpeedTracker** - Reaction speed measurement
```java
ctx.reactionSpeed().stimulus();           // Show stimulus
// Player touches...
Duration reaction = ctx.reactionSpeed().lastReaction();
```

#### **GraphFeature** - Graph operations
```java
List<Position> path = ctx.graph().shortestPath(
    from, to, pos -> board.get(pos) != TileColor.OFF
);

List<List<Position>> components = ctx.graph()
    .connectedComponents(pos -> ...);
```

---

## 🚀 Installation

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

### Prerequisites

- Java 17 or higher
- `tileboard-serial-protocol:1.0.0`
- (Optional) Spring Boot 3.3.2+ for auto-configuration

---

## ⚡ Quick Start

### 1. Create a Simple Game

```java
package com.example.games;

import com.tileboard.engine.core.*;
import com.tileboard.engine.model.*;
import org.springframework.stereotype.Component;

@Component  // ← If using Spring
public class SimpleColorMatch implements Game {

    private static final GameDescriptor DESCRIPTOR = GameDescriptor
            .builder("color-match", "Color Match")
            .category("PUZZLE")
            .description("Touch tiles of the same color")
            .boardSize(8, 8)
            .players(1, 1)
            .build();

    @Override
    public GameDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void onStart(GameContext ctx) {
        // Display countdown
        ctx.animations().playCountdown().thenRun(() -> {
            // Light up a random tile
            Position target = ctx.random().randomPosition();
            ctx.state().put("target", target);
            ctx.setTile(target.row(), target.col(), TileColor.GREEN);
            
            // 10 second timer
            ctx.timer().startCountdown(Duration.ofSeconds(10), () -> {
                ctx.loseSession();
            });
        });
    }

    @Override
    public void onTileEvent(GameContext ctx, TileEvent event) {
        Position target = ctx.state().get("target", Position.class).orElse(null);
        
        if (event.position().equals(target)) {
            // Correct hit!
            ctx.scores().add("player", 10);
            
            // Next tile
            Position newTarget = ctx.random().randomPosition();
            ctx.state().put("target", newTarget);
            ctx.fillBoard(TileColor.OFF);
            ctx.setTile(newTarget.row(), newTarget.col(), TileColor.GREEN);
            
            // Check win
            if (ctx.scores().get("player") >= 50) {
                ctx.winSession(List.of(Player.solo("player")));
            }
        } else {
            // Wrong hit
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

### 2. Setup Engine (Spring Boot)

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

### 3. Start Game from Controller

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
        GameEventBus eventBus = ...; // From Spring context
        return GameEventSseEmitter.forSession(sessionId, eventBus);
    }
}
```

---

## 🎬 Animation System - Complete Guide

### Key Concepts

#### 1. **Non-blocking Execution**
All animations run on a separate thread:

```java
CompletableFuture<Void> future = ctx.animations().playCountdown();

// Your code continues immediately
doSomethingElse();

// Wait for animation to complete
future.get();

// Or define a callback
future.thenRun(() -> System.out.println("Animation finished!"));
```

#### 2. **Cancellation**
Animations are cancellable:

```java
CompletableFuture<Void> standby = ctx.animations()
    .playStandbyAnimation(StandbyAnimationType.BREATHING);

// Cancel after 5 seconds
Thread.sleep(5000);
ctx.animations().cancelCurrent();
```

#### 3. **Sequential Chaining**
Chain animations sequentially:

```java
ctx.animations().playCountdown()
    .thenCompose(v -> ctx.animations().playWinAnimation())
    .thenRun(() -> ctx.fillBoard(TileColor.OFF));
```

### Advanced Examples

#### **Game with Custom Animations**

```java
@Override
public void onStart(GameContext ctx) {
    // Faster countdown
    ctx.animations().playCountdown(500)  // Each digit 500ms
        .thenRun(() -> {
            initializeGame(ctx);
        });
}

@Override
public void onTick(GameContext ctx) {
    // Check remaining time
    if (ctx.timer().remaining().getSeconds() <= 5) {
        // Red warning flash
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
        // Random win animation
        WinAnimationType[] types = WinAnimationType.values();
        WinAnimationType randomType = types[
            (int) (Math.random() * types.length)
        ];
        ctx.animations().playWinAnimation(randomType);
    } else {
        // Custom lose animation
        ctx.animations().playLoseAnimation(LoseAnimationType.CRUMBLE)
            .thenRun(() -> {
                // Display final score on board
                displayScore(ctx, result);
            });
    }
}
```

#### **Standby Animation Loop**

```java
public class IdleScreenManager {
    
    private CompletableFuture<Void> currentStandby;
    
    public void startIdleMode(GameContext ctx) {
        runStandbyLoop(ctx);
    }
    
    private void runStandbyLoop(GameContext ctx) {
        if (ctx.status() != GameStatus.RUNNING) {
            return;  // Game started, stop
        }
        
        // Random animation selection
        StandbyAnimationType[] types = StandbyAnimationType.values();
        StandbyAnimationType type = types[
            (int) (Math.random() * types.length)
        ];
        
        currentStandby = ctx.animations().playStandbyAnimation(type);
        
        // After completion, next animation
        currentStandby.thenRun(() -> {
            try {
                Thread.sleep(2000);  // Pause between animations
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

## 📚 Complete Examples

### Snake Game

```java
@Component
public class SnakeGame implements Game {
    
    @Override
    public void onStart(GameContext ctx) {
        ctx.animations().playCountdown().thenRun(() -> {
            // Initialize snake
            List<Position> snake = new ArrayList<>();
            snake.add(new Position(4, 4));
            ctx.state().put("snake", snake);
            ctx.state().put("direction", "RIGHT");
            
            // Spawn food
            spawnFood(ctx);
            
            // Start automatic movement
            ctx.timer().start();
        });
    }
    
    @Override
    public void onTick(GameContext ctx) {
        if (ctx.timer().elapsed().toMillis() % 500 != 0) return;
        
        List<Position> snake = ctx.state().get("snake", List.class).get();
        String direction = ctx.state().get("direction", String.class).get();
        
        // Calculate new head position
        Position head = snake.get(0);
        Position newHead = moveInDirection(head, direction);
        
        // Check collision
        if (isOutOfBounds(newHead, ctx) || snake.contains(newHead)) {
            ctx.loseSession();
            return;
        }
        
        // Check food consumption
        Position food = ctx.state().get("food", Position.class).get();
        if (newHead.equals(food)) {
            snake.add(0, newHead);
            spawnFood(ctx);
            ctx.scores().add("player", 10);
        } else {
            snake.add(0, newHead);
            snake.remove(snake.size() - 1);
        }
        
        // Draw board
        drawBoard(ctx, snake, food);
    }
    
    @Override
    public void onTileEvent(GameContext ctx, TileEvent event) {
        // Change direction based on touch
        Position touch = event.position();
        List<Position> snake = ctx.state().get("snake", List.class).get();
        Position head = snake.get(0);
        
        String newDirection = calculateDirection(head, touch);
        ctx.state().put("direction", newDirection);
    }
    
    private void drawBoard(GameContext ctx, List<Position> snake, Position food) {
        Board<TileColor> board = ctx.newBoard();
        
        // Draw snake body
        for (int i = 1; i < snake.size(); i++) {
            Position p = snake.get(i);
            board.set(p.row(), p.col(), TileColor.GREEN);
        }
        
        // Draw snake head
        Position head = snake.get(0);
        board.set(head.row(), head.col(), TileColor.YELLOW);
        
        // Draw food
        board.set(food.row(), food.col(), TileColor.RED);
        
        ctx.publishBoard(board);
    }
    
    private Position moveInDirection(Position pos, String dir) {
        return switch (dir) {
            case "UP"    -> new Position(pos.row() - 1, pos.col());
            case "DOWN"  -> new Position(pos.row() + 1, pos.col());
            case "LEFT"  -> new Position(pos.row(), pos.col() - 1);
            case "RIGHT" -> new Position(pos.row(), pos.col() + 1);
            default      -> pos;
        };
    }
    
    private boolean isOutOfBounds(Position pos, GameContext ctx) {
        return pos.row() < 0 || pos.row() >= ctx.boardHeight() ||
               pos.col() < 0 || pos.col() >= ctx.boardWidth();
    }
    
    private void spawnFood(GameContext ctx) {
        Position food;
        List<Position> snake = ctx.state().get("snake", List.class).get();
        do {
            food = ctx.random().randomPosition();
        } while (snake.contains(food));
        
        ctx.state().put("food", food);
    }
    
    private String calculateDirection(Position from, Position to) {
        int dr = to.row() - from.row();
        int dc = to.col() - from.col();
        
        if (Math.abs(dr) > Math.abs(dc)) {
            return dr > 0 ? "DOWN" : "UP";
        } else {
            return dc > 0 ? "RIGHT" : "LEFT";
        }
    }
    
    @Override
    public void onStop(GameContext ctx, GameResult result) {
        if (result.hasWinner()) {
            ctx.animations().playWinAnimation(WinAnimationType.RAINBOW_SWEEP);
        } else {
            ctx.animations().playLoseAnimation(LoseAnimationType.CRUMBLE);
        }
    }
    
    @Override
    public GameDescriptor descriptor() {
        return GameDescriptor.builder("snake", "Snake Game")
            .category("ARCADE")
            .description("Classic snake game on tileboard")
            .boardSize(8, 8)
            .players(1, 1)
            .build();
    }
}
```

### Memory Game

```java
@Component
public class MemoryGame implements Game {
    
    @Override
    public void onStart(GameContext ctx) {
        ctx.animations().playCountdown().thenRun(() -> {
            // Generate random sequence
            int level = ctx.levels().currentLevel();
            List<Position> sequence = ctx.random().randomPositions(3 + level);
            ctx.memory().setTarget(sequence);
            
            // Show sequence
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
        
        // Visual feedback
        ctx.setTile(event.position().row(), event.position().col(), 
                    TileColor.GREEN);
        
        if (!ctx.memory().isCorrectSoFar()) {
            // Wrong!
            ctx.fillBoard(TileColor.RED);
            ctx.health().damage("player");
            
            if (!ctx.health().isAlive("player")) {
                ctx.loseSession();
            } else {
                // Restart
                ctx.memory().resetInput();
                showSequence(ctx, ctx.memory().target());
            }
        } else if (ctx.memory().isComplete()) {
            // Complete!
            ctx.levels().advance();
            ctx.scores().add("player", 100 * ctx.levels().currentLevel());
            
            // Success animation
            ctx.waves().blink(TileColor.GREEN, TileColor.OFF, 3, 200);
            
            // Next level
            onStart(ctx);
        }
    }
    
    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    @Override
    public GameDescriptor descriptor() {
        return GameDescriptor.builder("memory", "Memory Game")
            .category("PUZZLE")
            .description("Remember and repeat the sequence")
            .boardSize(8, 8)
            .players(1, 1)
            .build();
    }
}
```

---

## 🏗️ Architecture

### Session Lifecycle

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

### Component Diagram

```
┌───────────────────────────────────────────────────────────────┐
│                     Application Layer                         │
│  ┌─────────────┐  ┌─────────────┐  ┌──────────────┐          │
│  │ REST        │  │ WebSocket   │  │ Game         │          │
│  │ Controllers │  │ Handlers    │  │ Implementations │        │
│  └─────────────┘  └─────────────┘  └──────────────┘          │
└───────────────────────────────────────────────────────────────┘
                            ↓
┌───────────────────────────────────────────────────────────────┐
│                    Engine Core Layer                          │
│  ┌──────────────────┐         ┌──────────────────┐           │
│  │ GameEngineManager│ ←────→  │ GameEventBus     │           │
│  └──────────────────┘         └──────────────────┘           │
│           ↓                            ↓                      │
│  ┌──────────────────┐         ┌──────────────────┐           │
│  │ GameRegistry     │         │ SSE Emitters     │           │
│  └──────────────────┘         └──────────────────┘           │
│           ↓                                                   │
│  ┌──────────────────────────────────────────┐                │
│  │        GameSessionImpl (GameContext)     │                │
│  │  ┌────────────────────────────────────┐  │                │
│  │  │ Built-in Features:                 │  │                │
│  │  │ • ScoreSystem  • HealthSystem      │  │                │
│  │  │ • LevelSystem  • ComboTracker      │  │                │
│  │  │ • GameTimer    • PatternMatcher    │  │                │
│  │  │ • AnimationSystem (NEW!)           │  │                │
│  │  └────────────────────────────────────┘  │                │
│  └──────────────────────────────────────────┘                │
└───────────────────────────────────────────────────────────────┘
                            ↓
┌───────────────────────────────────────────────────────────────┐
│                   Protocol Layer                              │
│  ┌──────────────────┐         ┌──────────────────┐           │
│  │ Board<TileColor> │ ←────→  │ ColorTileCodec   │           │
│  └──────────────────┘         └──────────────────┘           │
│           ↓                            ↓                      │
│  ┌──────────────────────────────────────────┐                │
│  │      TileGatewayClient                   │                │
│  │  (tileboard-serial-protocol)             │                │
│  └──────────────────────────────────────────┘                │
└───────────────────────────────────────────────────────────────┘
                            ↓
┌───────────────────────────────────────────────────────────────┐
│                   Hardware Layer                              │
│  ┌──────────────────────────────────────────┐                │
│  │      Serial Port (UART)                  │                │
│  │      ↓                                   │                │
│  │      Physical Tileboard Device           │                │
│  └──────────────────────────────────────────┘                │
└───────────────────────────────────────────────────────────────┘
```

---

## 🧪 Testing

### Running Tests

```bash
mvn test
```

### Test Structure

```
src/test/java/
├── com/tileboard/engine/
│   ├── core/
│   │   ├── GameEngineTest.java
│   │   ├── GameSessionTest.java
│   │   └── GameStateTest.java
│   ├── feature/
│   │   ├── AnimationSystemTest.java      ← Animation tests
│   │   ├── ScoreSystemTest.java
│   │   ├── HealthSystemTest.java
│   │   ├── PatternMatcherTest.java
│   │   └── ...
│   └── event/
│       └── GameEventBusTest.java
```

### Custom Test Example

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
        
        // Simulate touches
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
        
        // Verify countdown was called
        verify(ctx.animations()).playCountdown();
        
        // Simulate win
        ctx.winSession(List.of(Player.solo("test")));
        game.onStop(ctx, ctx.result().get());
        
        // Verify win animation was called
        verify(ctx.animations()).playWinAnimation(any());
    }
}
```

### Integration Test Example

```java
@SpringBootTest
class GameEngineIntegrationTest {

    @Autowired
    private GameEngineManager engineManager;
    
    @Autowired
    private GameRegistry registry;
    
    @Autowired
    private ApplicationEventPublisher eventPublisher;
    
    private TileGatewayClient mockGateway;
    
    @BeforeEach
    void setup() {
        mockGateway = mock(TileGatewayClient.class);
        
        // Simulate gateway connection
        eventPublisher.publishEvent(
            new GatewayConnectedEvent(mockGateway)
        );
    }
    
    @Test
    void testFullGameLifecycle() throws Exception {
        // Register a test game
        registry.register(new SimpleColorMatch());
        
        // Start game
        GameEngine engine = engineManager.require();
        String sessionId = engine.startGame(
            "color-match",
            List.of(Player.solo("TestPlayer"))
        );
        
        // Verify session is active
        assertTrue(engine.activeSession(sessionId).isPresent());
        
        // Wait for countdown
        Thread.sleep(3500);
        
        // Simulate touches to win
        GameSession session = engine.activeSession(sessionId).get();
        // ... trigger win condition ...
        
        // Verify game ended
        Thread.sleep(1000);
        assertEquals(GameStatus.FINISHED, session.status());
        assertTrue(session.result().isPresent());
    }
    
    @AfterEach
    void teardown() {
        eventPublisher.publishEvent(new GatewayDisconnectedEvent());
    }
}
```

---

## 📊 Performance Considerations

### Thread Model

- **Main Thread**: REST controllers, Spring context
- **Serial Reader Thread**: Owned by `TileGatewayClient`
- **Event Bus Thread**: Single-threaded executor for event dispatch
- **Per-Session Tick Thread**: One daemon thread per active session
- **Animation Thread**: One daemon thread per `AnimationSystem`

### Memory Management

- Each session holds:
    - One `Board<TileColor>` buffer (~64 bytes for 8×8)
    - Feature state (scores, health, combos, etc.) ~1-2 KB
    - Touch history (grows with gameplay, typically < 10 KB)

- Recommended max concurrent sessions: 10-20 (hardware dependent)

### Optimization Tips

```java
// ✅ Good: Reuse board buffer
Board<TileColor> board = ctx.newBoard();
board.set(0, 0, TileColor.RED);
ctx.publishBoard(board);

// ❌ Bad: Create new board every tick
ctx.publishBoard(new Board<>(8, 8, TileColor.RED));

// ✅ Good: Batch board updates
for (Position p : positions) {
    board.set(p.row(), p.col(), TileColor.BLUE);
}
ctx.publishBoard(board);  // Single serial write

// ❌ Bad: Multiple serial writes
for (Position p : positions) {
    ctx.setTile(p.row(), p.col(), TileColor.BLUE);  // Each call = one write
}
```

---

## 🔧 Advanced Configuration

### Custom Tick Interval

```yaml
tileboard:
  engine:
    tick-interval: 50ms  # Faster ticks for action games
```

### Custom Event Bus Executor

```java
@Configuration
public class CustomEngineConfig {
    
    @Bean
    @Primary
    public GameEventBus customEventBus() {
        Executor executor = Executors.newFixedThreadPool(2);
        return new GameEventBusImpl(executor);
    }
}
```

### Custom Animation Speeds

```java
public class FastPacedGame implements Game {
    
    @Override
    public void onStart(GameContext ctx) {
        // Quick countdown (200ms per digit)
        ctx.animations().playCountdown(200)
            .thenRun(() -> startGame(ctx));
    }
    
    @Override
    public void onStop(GameContext ctx, GameResult result) {
        if (result.hasWinner()) {
            // Fast sparkle animation
            CompletableFuture.runAsync(() -> {
                for (int i = 0; i < 10; i++) {
                    Board<TileColor> board = ctx.newBoard();
                    // ... custom fast sparkle logic ...
                    ctx.publishBoard(board);
                    Thread.sleep(50);
                }
            });
        }
    }
}
```

---

## 🐛 Troubleshooting

### Common Issues

#### 1. **EngineNotReadyException**

```
Exception: The game engine is not ready: no serial gateway is currently connected
```

**Solution**: Ensure the serial gateway is connected before starting games.

```java
// Check connection status
if (engineManager.current().isEmpty()) {
    // Connect gateway first via /api/ports/* endpoints
    return ResponseEntity.status(503)
        .body("Gateway not connected");
}
```

#### 2. **Animations Not Showing**

**Possible causes**:
- Board size too small (minimum 3×5 for countdown)
- Animation cancelled before completion
- Board updates not reaching hardware

**Debug**:
```java
CompletableFuture<Void> anim = ctx.animations().playCountdown();
anim.exceptionally(ex -> {
    log.error("Animation failed", ex);
    return null;
});
```

#### 3. **Touch Events Not Received**

**Check**:
- Gateway is connected and started
- `EngineFrameRouter` is registered as frame listener
- Session is in RUNNING state

**Debug**:
```java
@Override
public void onTileEvent(GameContext ctx, TileEvent event) {
    log.info("Touch received: {}", event.position());
    // ... rest of logic
}
```

#### 4. **Memory Leak in Long-Running Sessions**

**Solution**: Clear touch history periodically

```java
@Override
public void onTick(GameContext ctx) {
    // Clear history every 1000 touches
    if (ctx.touchHistory().totalTouches() > 1000) {
        ctx.touchHistory().reset();
    }
}
```

---

## 🤝 Contributing

### Reporting Bugs

Found a bug? Open an [Issue](https://github.com/yourrepo/issues).

### Feature Requests

Have an idea? Start a [Discussion](https://github.com/yourrepo/discussions).

### Pull Requests

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Write tests for your changes
4. Commit your changes (`git commit -m 'Add amazing feature'`)
5. Push to the branch (`git push origin feature/amazing-feature`)
6. Open a Pull Request

### Code Style

- Follow Java naming conventions
- Add Javadoc for public APIs
- Write unit tests (aim for >80% coverage)
- Keep classes focused (Single Responsibility Principle)

---

