# Tileboard Application — Game Engine Developer Guide

This document is the implementation guide for developers who want to **use the Tileboard Game Engine, understand the existing games, or add a new game**.

The application is a Spring Boot layer on top of the `tileboard-serial-protocol` library. The protocol library hides serial-port details, frame encoding/decoding, tile addressing, and board serialization. The game engine consumes that abstraction and exposes a small, hardware-independent API to game implementations.

> **Audience:** Java/Spring Boot developers extending the Tileboard platform.
>
> **Java:** 17+
>
> **Spring Boot:** 3.3.x in the current project.
>
> **Important:** A game must not communicate with the serial port directly. A game publishes `Board<T>` objects through `GameContext`; the platform is responsible for sending them to the physical board.

---

## 1. Architecture

The relevant runtime flow is:

```text
Physical Tile/Touch Board
          │
          │ Serial
          ▼
tileboard-serial-protocol
  ├── SerialTransport
  ├── FrameEncoder / FrameDecoder
  ├── TileGatewayClient
  └── BoardFrameListener
          │
          │ DATA_IN: Board<Boolean>
          ▼
GameSessionManager
          │
          │ onPlayerInput(...)
          ▼
       Game<T>
          │
          │ context.publish(Board<T>)
          ▼
GameContext<T>
          │
          ├──────────────► TileGatewayClient.sendBoard(...)
          │                         │
          │                         ▼
          │                   Physical board
          │
          └──────────────► BoardStateBroadcaster
                                      │
                                      ▼
                                  SSE clients
```

The important architectural rule is:

```text
Game
  knows:
    - its own rules
    - its own state
    - Board<T>
    - GameContext<T>

Game
  does NOT know:
    - serial ports
    - SerialTransport
    - TileGatewayClient
    - HTTP
    - Spring controllers
    - SSE
    - another game's implementation
```

This separation is intentional. It means a game can be unit-tested without real hardware.

---

# 2. Game Engine Packages

The game engine is located under:

```text
com.tileboard.app.gameengine
```

Current structure:

```text
gameengine/
├── Game.java
├── GameContext.java
├── GameDefinition.java
├── GameFactory.java
├── GameMode.java
├── GameRegistry.java
├── GameSessionManager.java
├── TileColor.java
├── TileColors.java
├── Cancellable.java
│
├── dto/
│   ├── GameDefinitionResponse.java
│   ├── GameSessionStatusResponse.java
│   └── StartGameRequest.java
│
├── example/
│   ├── TouchEchoGame.java
│   ├── TouchEchoGameFactory.java
│   ├── RandomColorTouchGame.java
│   └── RandomColorTouchGameFactory.java
│
└── games/
    ├── jump/
    │   ├── JumpGame.java
    │   ├── JumpGameFactory.java
    │   ├── JumpPattern.java
    │   ├── JumpPatterns.java
    │   └── JumpTuning.java
    │
    └── colormatch/
        ├── ColorMatchGame.java
        ├── ColorMatchGameFactory.java
        └── ColorMatchTuning.java
```

---

# 3. Core API

## 3.1 `Game<T>`

Source:

```text
com.tileboard.app.gameengine.Game
```

This is the main extension point.

```java
public interface Game<T> {

    GameDefinition definition();

    TileCodec<T> tileCodec();

    void start(GameContext<T> context);

    void onPlayerInput(Board<Boolean> touchedTiles);

    default void stop() {
    }
}
```

### Generic parameter `T`

`T` represents the type used by the game for **output/display tiles**.

Examples:

```text
Game<Boolean>
Game<TileColor>
Game<MyCustomTileState>
```

Input is deliberately different:

```java
Board<Boolean>
```

The physical controller reports touch state, therefore the game always receives a Boolean board:

```text
false = not touched
true  = touched
```

Output can be any type that has a `TileCodec<T>`.

For example:

```java
Game<TileColor>
```

can publish:

```java
Board<TileColor>
```

while:

```java
Game<Boolean>
```

can publish:

```java
Board<Boolean>
```

### Lifecycle

A game normally experiences:

```text
create
  ↓
start(context)
  ↓
zero or more onPlayerInput(...)
  ↓
optional scheduled ticks
  ↓
stop()
```

`start()` is called once for the session.

`onPlayerInput()` is called whenever the hardware reports touch input.

`stop()` is called when:

- the user stops the game;
- another game replaces the current game;
- the gateway disconnects.

A game should release/cancel its own resources in `stop()`.

---

# 4. `GameContext<T>`

`GameContext` is the controlled platform API exposed to a game.

It provides exactly three important capabilities:

1. publish an output board;
2. read elapsed game time;
3. schedule periodic work.

## 4.1 Publishing a board

```java
context.publish(board);
```

The game does not send bytes.

The platform performs:

```text
Board<T>
   ↓
TileCodec<T>
   ↓
byte[]
   ↓
TileGatewayClient
   ↓
DATA_OUT frame
   ↓
physical board
```

The same board is also sent to the live board broadcaster.

### Example

```java
Board<TileColor> board =
        new Board<>(context.width(), context.height(), TileColor.OFF);

board.set(0, 0, TileColor.RED);

context.publish(board);
```

---

## 4.2 Board dimensions

Use:

```java
context.width()
context.height()
```

rather than hard-coding the board dimensions.

Example:

```java
int width = context.width();
int height = context.height();
```

This allows the same game to run on different board sizes.

---

## 4.3 Game mode

Use:

```java
context.mode()
```

when the game needs to know whether it is running in:

```java
GameMode.EASY
GameMode.NORMAL
GameMode.HARD
```

Usually it is cleaner to resolve the mode into a small tuning object in the factory:

```java
GameTuning tuning = GameTuning.forMode(mode);
```

rather than spreading `GameMode` checks throughout the game implementation.

---

## 4.4 Elapsed time

```java
Duration elapsed = context.elapsed();
```

This is the duration since `Game.start(context)` was called.

Example:

```java
if (context.elapsed().compareTo(Duration.ofMinutes(2)) >= 0) {
    // round has ended
}
```

---

## 4.5 Scheduling periodic work

Games that have animations, timers, countdowns, or timeouts can use:

```java
Cancellable task = context.scheduleAtFixedRate(
        Duration.ofMillis(500),
        this::tick
);
```

The returned `Cancellable` can be stored and cancelled:

```java
task.cancel();
```

The context owns the underlying scheduler and shuts it down when the session ends.

A game therefore must **not** create its own `ScheduledExecutorService` unless there is a very specific reason.

---

# 5. `Cancellable`

```java
@FunctionalInterface
public interface Cancellable {
    void cancel();
}
```

It represents a scheduled task that the game can cancel.

Typical pattern:

```java
private Cancellable ticking;

@Override
public void start(GameContext<TileColor> context) {
    this.context = context;
    this.ticking = context.scheduleAtFixedRate(
            Duration.ofMillis(500),
            this::tick
    );
}

@Override
public void stop() {
    if (ticking != null) {
        ticking.cancel();
        ticking = null;
    }
    context = null;
}
```

Even though `GameContext.close()` also shuts down the scheduler, games should cancel their own logical timers when they finish early.

---

# 6. `GameDefinition`

```java
public record GameDefinition(
        String id,
        String displayName,
        String description
) {}
```

This is metadata, not game state.

Example:

```java
new GameDefinition(
        "jump",
        "Jump",
        "Avoid the moving band and survive the round."
);
```

### Fields

| Field | Purpose |
|---|---|
| `id` | Stable machine-readable identifier |
| `displayName` | Human-readable game name |
| `description` | Description for UI/game selection |

The `id` becomes part of the REST URL:

```text
POST /api/v1/games/jump/start
```

### ID rules

Use a stable lowercase identifier such as:

```text
color-match
jump
reaction-time
snake
tic-tac-toe
```

Do not change the ID casually because clients may store or call it directly.

---

# 7. `GameFactory`

A `GameFactory` creates a **fresh game instance for every session**.

```java
public interface GameFactory {

    String gameId();

    GameDefinition definition();

    Game<?> create(GameMode mode, int width, int height);
}
```

A factory is normally a Spring component:

```java
@Component
class MyGameFactory implements GameFactory {
    ...
}
```

This is how a game becomes discoverable by the platform.

---

# 8. `GameRegistry`

`GameRegistry` receives every Spring-managed `GameFactory`:

```java
public GameRegistry(List<GameFactory> factories)
```

It converts them into:

```text
gameId → GameFactory
```

It provides:

```java
listDefinitions()
getFactory(gameId)
```

### Important design property

There is no central switch:

```java
switch (gameId) {
    case "jump":
    case "color-match":
    ...
}
```

Adding a game does not require changing `GameRegistry`.

This follows the Open/Closed Principle.

---

# 9. `GameSessionManager`

This is the orchestration layer.

It owns the currently active game:

```java
private volatile Game<?> activeGame;
```

Responsibilities:

1. verify that the gateway is connected;
2. verify board dimensions are configured;
3. stop the previous game;
4. bind the touch listener to the current board dimensions;
5. obtain the requested `GameFactory`;
6. create a new game;
7. send the protocol `START` command;
8. create `GameContext`;
9. start the game;
10. route touch input to the active game;
11. stop the game and its context;
12. react to gateway disconnects.

A concrete game should not perform any of these tasks itself.

---

# 10. Input Flow

When a player touches the board:

```text
Hardware
  ↓
DATA_IN frame
  ↓
TileGatewayClient
  ↓
BoardFrameListener<Boolean>
  ↓
Board<Boolean>
  ↓
GameSessionManager.handleTouchInput(...)
  ↓
activeGame.onPlayerInput(...)
```

The game receives a fully decoded board.

Example:

```java
@Override
public void onPlayerInput(Board<Boolean> touchedTiles) {

    List<Position> touched =
            touchedTiles.positionsWhere(Boolean.TRUE::equals);

    for (Position position : touched) {
        // process touch
    }
}
```

No serial bytes should be parsed inside the game.

---

# 11. Finding Touched Tiles

The serial library's `Board` API is the standard way to find touches:

```java
List<Position> touched =
        touchedTiles.positionsWhere(Boolean.TRUE::equals);
```

A `Position` contains:

```java
row()
col()
```

Example:

```java
for (Position position : touched) {
    int row = position.row();
    int column = position.col();

    System.out.println(
            "Touched: row=" + row + ", col=" + column
    );
}
```

Coordinates are zero-based.

For an `8 x 8` board:

```text
row:    0..7
column: 0..7
```

---

# 12. Output Tile Types

## 12.1 `TileColor`

The application defines:

```java
public enum TileColor {
    OFF(0),
    RED(1),
    GREEN(2),
    BLUE(3),
    PINK(4),
    LIGHT_BLUE(5),
    WHITE(6);
}
```

The numeric values are protocol values and must not be changed without considering firmware compatibility.

## 12.2 `TileColors`

`TileColors` provides the shared codec:

```java
TileCodec<TileColor> codec = TileColors.codec();
```

Every color-based game should normally use:

```java
@Override
public TileCodec<TileColor> tileCodec() {
    return TileColors.codec();
}
```

Do not create a second color numbering system in every game.

---

# 13. REST API

The game controller is:

```text
com.tileboard.app.gameengine.GameController
```

Base path:

```text
/api/v1/games
```

## List games

```http
GET /api/v1/games
```

Example response:

```json
[
  {
    "id": "touch-echo",
    "displayName": "Touch Echo",
    "description": "Reference example..."
  },
  {
    "id": "random-color-touch",
    "displayName": "Random Color Touch",
    "description": "..."
  },
  {
    "id": "jump",
    "displayName": "Jump",
    "description": "..."
  },
  {
    "id": "color-match",
    "displayName": "Color Match",
    "description": "..."
  }
]
```

The exact list depends on which `GameFactory` beans are available.

## Get session status

```http
GET /api/v1/games/session
```

Example:

```json
{
  "active": true
}
```

## Start a game

```http
POST /api/v1/games/{gameId}/start
Content-Type: application/json
```

Body:

```json
{
  "mode": "NORMAL"
}
```

Supported modes:

```text
EASY
NORMAL
HARD
```

Example:

```bash
curl -X POST \
  http://localhost:8080/api/v1/games/jump/start \
  -H 'Content-Type: application/json' \
  -d '{"mode":"NORMAL"}'
```

## Stop a game

```http
POST /api/v1/games/stop
```

Starting another game automatically stops the currently active game first.

---

# 14. Existing Game: Touch Echo

Source:

```text
gameengine/example/TouchEchoGame.java
gameengine/example/TouchEchoGameFactory.java
```

This is the smallest complete game implementation.

Its behavior is intentionally simple:

```text
touch input
    ↓
publish the same board
```

It uses:

```java
Game<Boolean>
```

and:

```java
TileCodec.booleanState()
```

### Important implementation

```java
@Override
public void onPlayerInput(Board<Boolean> touchedTiles) {
    GameContext<Boolean> currentContext = context;

    if (currentContext != null) {
        currentContext.publish(touchedTiles);
    }
}
```

This is the recommended starting point for understanding the engine.

### When to use this example

Use `TouchEchoGame` as the reference when:

- learning the lifecycle;
- creating a minimal game;
- testing hardware input;
- verifying the gateway integration.

---

# 15. Existing Game: Random Color Touch

Source:

```text
gameengine/example/RandomColorTouchGame.java
gameengine/example/RandomColorTouchGameFactory.java
```

This example demonstrates a game where input and output use different tile types.

Input:

```java
Board<Boolean>
```

Output:

```java
Board<TileColor>
```

When a tile is touched, the game assigns a random color to it.

Untouched tiles preserve their previous color.

Conceptually:

```text
OFF OFF OFF OFF
OFF OFF OFF OFF
      ↓ touch (1,2)
OFF OFF RED OFF
OFF OFF OFF OFF
```

Then another touch:

```text
OFF BLUE RED OFF
OFF OFF OFF OFF
```

The board is persistent.

### Key implementation idea

```java
List<Position> touched =
        touchedTiles.positionsWhere(Boolean.TRUE::equals);

for (Position position : touched) {
    board.set(position, randomColor());
}

context.publish(board);
```

This is a good example of maintaining game state between input events.

---

# 16. Existing Game: Jump

Source:

```text
gameengine/games/jump/
```

Classes:

```text
JumpGame
JumpGameFactory
JumpPattern
JumpPatterns
JumpTuning
```

## Rules

A colored band moves across the board.

The player must avoid touching the band.

Each collision removes one life.

```text
touch band
   ↓
livesRemaining--
   ↓
0 lives?
   ├── yes → LOST
   └── no  → continue
```

If the player survives the full round:

```text
WON
```

The final board is:

```text
WHITE = win
RED   = loss
```

## Difficulty

Current tuning:

| Mode | Tick | Lives | Round |
|---|---:|---:|---:|
| EASY | 900 ms | 5 | 3 min |
| NORMAL | 600 ms | 4 | 2 min |
| HARD | 350 ms | 3 | 1 min |

These values are defined in `JumpTuning`.

## Patterns

`JumpPattern` is a second extension point inside the Jump game.

```java
@FunctionalInterface
public interface JumpPattern {

    List<List<Position>> framesFor(int width, int height);
}
```

A frame is:

```text
List<Position>
```

representing all tiles illuminated at one step.

Built-in patterns:

```java
JumpPatterns.row()
JumpPatterns.column()
JumpPatterns.mainDiagonal()
JumpPatterns.antiDiagonal()
JumpPatterns.rotating()
```

### Row

```text
frame 0: █ █ █ █
frame 1: . . . .
frame 2: . . . .
```

then:

```text
frame 1: █ █ █ █
```

### Column

```text
frame 0: █ . . .
frame 1: . █ . .
frame 2: . . █ .
...
```

### Main diagonal

Uses:

```java
row - col == offset
```

### Anti-diagonal

Uses:

```java
row + col == sum
```

### Rotating

`rotating()` combines:

```text
row
column
main diagonal
anti diagonal
```

in a randomly shuffled order each time `framesFor()` is called.

---

# 17. Adding a New Jump Pattern

You can add a pattern without changing `JumpGame`.

Example: a checkerboard-like pattern could be represented as multiple frames.

```java
public static JumpPattern checker() {
    return (width, height) -> List.of(
            positionsWhere(width, height,
                    (row, col) -> (row + col) % 2 == 0),

            positionsWhere(width, height,
                    (row, col) -> (row + col) % 2 != 0)
    );
}
```

The game itself does not need to know how the positions were calculated.

This is an example of separating:

```text
geometry
```

from:

```text
game rules
```

---

# 18. Existing Game: Color Match

Source:

```text
gameengine/games/colormatch/
```

Classes:

```text
ColorMatchGame
ColorMatchGameFactory
ColorMatchTuning
```

## Rules

A number of color pairs are randomly placed.

At the beginning:

```text
all pairs visible
```

After the reveal period:

```text
all cards hidden
```

The player touches two tiles.

If colors match:

```text
pair remains visible
```

If they do not match:

```text
both are hidden again after a short delay
```

When every pair is solved:

```text
WON
```

## Difficulty

Current configuration:

| Mode | Pairs | Reveal |
|---|---:|---:|
| EASY | 3 | 4 sec |
| NORMAL | 4 | 3 sec |
| HARD | 5 | 2 sec |

The actual pair count is clamped to the board capacity and available palette.

The available pair palette is:

```text
RED
GREEN
BLUE
PINK
LIGHT_BLUE
```

`WHITE` is used for face-down tiles.

`OFF` is used for non-participating cells.

---

# 19. Recommended New Game Structure

For a new game named `ReactionGame`, create:

```text
gameengine/games/reaction/
├── ReactionGame.java
├── ReactionGameFactory.java
└── ReactionTuning.java       # optional
```

If the game has reusable geometry:

```text
├── ReactionPattern.java      # optional
└── ReactionPatterns.java     # optional
```

If the game has complex state:

```text
├── ReactionPhase.java
├── ReactionState.java
└── ReactionTuning.java
```

Do not create classes merely for symmetry. Keep the implementation as small as the rules allow.

---

# 20. Step-by-Step: Implementing a New Game

Suppose we want:

> Reaction Game: one random tile lights green. The player must touch it before the timeout. A successful touch produces a new target.

## Step 1 — Create the game class

```java
final class ReactionGame implements Game<TileColor> {

    private final GameDefinition definition;
    private final int width;
    private final int height;

    private GameContext<TileColor> context;

    ReactionGame(
            GameDefinition definition,
            int width,
            int height
    ) {
        this.definition = definition;
        this.width = width;
        this.height = height;
    }

    @Override
    public GameDefinition definition() {
        return definition;
    }

    @Override
    public TileCodec<TileColor> tileCodec() {
        return TileColors.codec();
    }

    @Override
    public void start(GameContext<TileColor> context) {
        this.context = context;

        Board<TileColor> board =
                new Board<>(width, height, TileColor.OFF);

        board.set(0, 0, TileColor.GREEN);

        context.publish(board);
    }

    @Override
    public void onPlayerInput(Board<Boolean> touchedTiles) {
        if (context == null) {
            return;
        }

        List<Position> touched =
                touchedTiles.positionsWhere(Boolean.TRUE::equals);

        if (!touched.isEmpty()) {
            // Process the reaction.
        }
    }

    @Override
    public void stop() {
        context = null;
    }
}
```

This is already a valid `Game`.

---

# 21. Step 2 — Create the Factory

```java
@Component
class ReactionGameFactory implements GameFactory {

    private static final GameDefinition DEFINITION =
            new GameDefinition(
                    "reaction",
                    "Reaction",
                    "Touch the highlighted tile as quickly as possible."
            );

    @Override
    public String gameId() {
        return DEFINITION.id();
    }

    @Override
    public GameDefinition definition() {
        return DEFINITION;
    }

    @Override
    public Game<?> create(
            GameMode mode,
            int width,
            int height
    ) {
        return new ReactionGame(
                DEFINITION,
                width,
                height
        );
    }
}
```

That is enough to register the game.

No changes are required in:

```text
GameRegistry
GameController
GameSessionManager
serial layer
```

provided the factory is discovered by Spring component scanning.

---

# 22. Step 3 — Start the New Game

Start the application and call:

```bash
curl -X POST \
  http://localhost:8080/api/v1/games/reaction/start \
  -H 'Content-Type: application/json' \
  -d '{"mode":"NORMAL"}'
```

The game should now be listed by:

```bash
curl http://localhost:8080/api/v1/games
```

---

# 23. Step 4 — Add Game State

Real games usually need state.

Example:

```java
private Position target;
private int score;
private int lives;
```

When state can be accessed by both:

```text
onPlayerInput()
```

and:

```text
scheduled tick()
```

those methods can run on different threads.

Use synchronization or another explicit concurrency strategy.

A simple and reliable pattern is:

```java
private final Object lock = new Object();
```

and:

```java
@Override
public void onPlayerInput(Board<Boolean> touchedTiles) {
    synchronized (lock) {
        // mutate game state
    }
}

private void tick() {
    synchronized (lock) {
        // mutate game state
    }
}
```

This is the strategy used by `JumpGame` and `ColorMatchGame`.

---

# 24. Threading Model

A developer must understand this before writing a timed game.

There are at least three relevant execution contexts:

```text
HTTP request thread
    │
    └── GameSessionManager.startGame()
            └── Game.start()

Serial gateway callback thread
    │
    └── GameSessionManager.handleTouchInput()
            └── Game.onPlayerInput()

Game session clock thread
    │
    └── GameContext scheduled task
            └── game.tick()
```

Therefore:

```java
start()
onPlayerInput()
tick()
```

should not assume that they execute on the same thread.

## Recommended approach

If state is shared:

```java
private final Object lock = new Object();
```

Then:

```java
synchronized (lock) {
    // state transition
}
```

Do not rely only on `volatile` when multiple fields must change atomically.

---

# 25. State Machine Design

For anything more complicated than a tiny game, model phases explicitly.

Color Match uses:

```java
private enum Phase {
    REVEAL_ALL,
    AWAIT_PICKS,
    EVALUATING,
    WON
}
```

This is preferable to many unrelated Boolean flags such as:

```java
boolean started;
boolean revealing;
boolean waiting;
boolean evaluating;
boolean finished;
```

A state machine makes legal transitions clear:

```text
REVEAL_ALL
    ↓ timeout
AWAIT_PICKS
    ↓ first touch
AWAIT_PICKS + firstPick
    ↓ second touch
EVALUATING
    ├── match → AWAIT_PICKS
    ├── mismatch → AWAIT_PICKS
    └── all solved → WON
```

For a new game, consider defining:

```text
enum Phase
```

when the game has multiple phases.

---

# 26. Output Rendering Pattern

A useful pattern is to keep rendering in one method:

```java
private void publishCurrentState() {

    Board<TileColor> board =
            new Board<>(width, height, TileColor.OFF);

    // Convert game state into visual state.

    context.publish(board);
}
```

Then game logic changes state and calls:

```java
publishCurrentState();
```

This keeps:

```text
game rules
```

separate from:

```text
board rendering
```

Color Match follows this approach.

---

# 27. Timing Pattern

For a game with a periodic clock:

```java
private static final Duration TICK =
        Duration.ofMillis(100);

private Cancellable ticking;

@Override
public void start(GameContext<TileColor> context) {
    synchronized (lock) {
        this.context = context;

        publishCurrentState();

        ticking = context.scheduleAtFixedRate(
                TICK,
                this::tick
        );
    }
}

private void tick() {
    synchronized (lock) {
        if (/* finished */) {
            return;
        }

        // Advance state.
        publishCurrentState();
    }
}
```

When the game finishes:

```java
private void finish() {
    if (ticking != null) {
        ticking.cancel();
        ticking = null;
    }

    // Publish final state.
}
```

And always clean up in:

```java
@Override
public void stop() {
    synchronized (lock) {
        if (ticking != null) {
            ticking.cancel();
            ticking = null;
        }

        context = null;
    }
}
```

---

# 28. Game Tuning

If a game has difficulty-specific values, prefer a tuning record.

Example:

```java
record ReactionTuning(
        Duration targetTimeout,
        Duration nextTargetDelay,
        int lives
) {

    static ReactionTuning forMode(GameMode mode) {
        return switch (mode) {
            case EASY ->
                    new ReactionTuning(
                            Duration.ofSeconds(3),
                            Duration.ofMillis(500),
                            5
                    );

            case NORMAL ->
                    new ReactionTuning(
                            Duration.ofSeconds(2),
                            Duration.ofMillis(300),
                            3
                    );

            case HARD ->
                    new ReactionTuning(
                            Duration.ofMillis(900),
                            Duration.ofMillis(150),
                            2
                    );
        };
    }
}
```

Then the factory resolves it:

```java
return new ReactionGame(
        DEFINITION,
        ReactionTuning.forMode(mode),
        width,
        height
);
```

This keeps the actual game class focused on rules.

---

# 29. Testing a New Game

A new game should be testable without a physical board.

Create:

```text
src/test/java/com/tileboard/app/gameengine/games/reaction/
    ReactionGameTest.java
```

Capture published boards:

```java
List<Board<TileColor>> published =
        new CopyOnWriteArrayList<>();

GameContext<TileColor> context =
        new GameContext<>(
                4,
                4,
                GameMode.NORMAL,
                published::add
        );
```

Start the game:

```java
game.start(context);
```

Then simulate input:

```java
Board<Boolean> touched =
        new Board<>(4, 4, false);

touched.set(1, 2, true);

game.onPlayerInput(touched);
```

Now assert the resulting board.

This pattern is already used by:

```text
JumpGameTest
ColorMatchGameTest
```

---

# 30. Test the Rules, Not the Serial Layer

Do not test this inside every game:

```text
START_BYTE
SEPARATOR
payload length
END_BYTE
```

Those concerns belong to:

```text
tileboard-serial-protocol
```

Game tests should verify:

```text
touch → state transition → rendered board
```

Examples:

```text
Jump:
  touching band removes life
  zero lives produces loss
  surviving duration produces win

Color Match:
  first pick is revealed
  matching pair stays visible
  mismatch is evaluated
  all pairs solved produces win
```

This keeps tests fast and meaningful.

---

# 31. Common Mistakes to Avoid

## Do not access the serial client

Bad:

```java
TileGatewayClient client = ...;
client.send(...);
```

A game should only use:

```java
context.publish(board);
```

## Do not parse raw bytes

Bad:

```java
byte[] input = ...;
```

Use:

```java
Board<Boolean>
```

## Do not create a REST controller for every game

The existing:

```text
GameController
```

already exposes all registered games.

## Do not modify `GameRegistry` for every game

The factory is automatically discovered.

## Do not create a global singleton game instance

A factory must create:

```text
new game instance
```

for every session.

Game state must not leak from one session into another.

## Do not hard-code board dimensions

Use:

```java
context.width()
context.height()
```

or the dimensions passed to the constructor.

## Do not leave scheduled tasks running

Always cancel game-owned tasks in:

```java
stop()
```

and when a terminal state is reached.

## Do not use `volatile` as a replacement for synchronization

If multiple state fields form one atomic transition, use a lock or another proper concurrency mechanism.

---

# 32. Class Reference

| Class | Responsibility |
|---|---|
| `Game<T>` | Main game extension contract |
| `GameContext<T>` | Platform capabilities available to a game |
| `GameDefinition` | Game metadata |
| `GameFactory` | Creates new game instances |
| `GameRegistry` | Discovers and indexes factories |
| `GameSessionManager` | Owns active-game lifecycle and input/output wiring |
| `GameMode` | Common difficulty selector |
| `Cancellable` | Handle for cancelling scheduled work |
| `TileColor` | Application color model and wire codes |
| `TileColors` | Shared `TileColor` ↔ wire codec |
| `GameController` | REST API for game discovery and lifecycle |
| `GameDefinitionResponse` | REST representation of game metadata |
| `GameSessionStatusResponse` | REST representation of session state |
| `StartGameRequest` | REST request containing `GameMode` |
| `TouchEchoGame` | Minimal game implementation |
| `TouchEchoGameFactory` | Factory for Touch Echo |
| `RandomColorTouchGame` | Persistent random-color example |
| `RandomColorTouchGameFactory` | Factory for Random Color Touch |
| `JumpGame` | Timed avoidance/reflex game |
| `JumpGameFactory` | Factory for Jump |
| `JumpPattern` | Geometry extension point for Jump |
| `JumpPatterns` | Built-in Jump geometries |
| `JumpTuning` | Jump difficulty configuration |
| `ColorMatchGame` | Memory/pairs game |
| `ColorMatchGameFactory` | Factory for Color Match |
| `ColorMatchTuning` | Color Match difficulty configuration |

---

# 33. Existing Games at a Glance

| Game ID | Output | Timed | Main concept |
|---|---|---:|---|
| `touch-echo` | `Boolean` | No | Minimal input/output example |
| `random-color-touch` | `TileColor` | No | Persistent game state |
| `jump` | `TileColor` | Yes | Animation + collision + lives |
| `color-match` | `TileColor` | Yes | State machine + timer + memory |

These four examples intentionally cover the main implementation styles a future developer is likely to need.

---

# 34. Recommended Development Workflow

When implementing a new game:

### 1. Define the rules

Write down:

```text
states
inputs
outputs
timers
win condition
lose condition
score
lives
```

before coding.

### 2. Choose the output tile type

Usually:

```java
TileColor
```

is sufficient.

Use another type only if the game genuinely needs another representation.

### 3. Create the game

Implement:

```java
Game<T>
```

### 4. Create the factory

Implement:

```java
GameFactory
```

and annotate it:

```java
@Component
```

### 5. Add tuning

If difficulty changes values, create:

```text
YourGameTuning
```

### 6. Add unit tests

Test:

```text
start
input
state transitions
timers
win
lose
stop
```

### 7. Run the application

Make sure the new game appears in:

```http
GET /api/v1/games
```

### 8. Test through the REST API

```http
POST /api/v1/games/{id}/start
```

### 9. Test with hardware

Only after the game logic works with unit tests.

---

# 35. End-to-End Example

After configuring the device and connecting the serial gateway:

```bash
# List available games
curl http://localhost:8080/api/v1/games

# Start Jump
curl -X POST \
  http://localhost:8080/api/v1/games/jump/start \
  -H 'Content-Type: application/json' \
  -d '{"mode":"NORMAL"}'

# Check session
curl http://localhost:8080/api/v1/games/session

# Stop
curl -X POST \
  http://localhost:8080/api/v1/games/stop
```

The game implementation never needs to know which serial port was assigned.

---

# 36. Relationship with `tileboard-serial-protocol`

The game engine is intentionally above the protocol library.

The protocol library provides:

```text
SerialPortRegistry
SerialTransport
Frame
DefaultFrameCodec
Board
Position
TileCodec
TileGatewayClient
BoardFrameListener
```

The game engine mainly consumes:

```text
Board<T>
Position
TileCodec<T>
```

and indirectly uses:

```text
TileGatewayClient
```

through `GameSessionManager`.

For complete hardware/protocol documentation, see:

```text
tileboard-serial-protocol/README.en.md
```

That document should be treated as the authoritative guide for:

- serial transport;
- frame protocol;
- commands;
- board serialization;
- tile codecs;
- gateway;
- handshake;
- hardware integration.

---

# 37. Design Principles

The current game engine intentionally follows these principles:

### Single Responsibility

```text
Game
    → rules

GameFactory
    → construction

GameRegistry
    → discovery

GameSessionManager
    → lifecycle/integration

GameContext
    → platform capabilities
```

### Open/Closed Principle

Adding a game means adding:

```text
Game
GameFactory
```

without modifying the central engine.

### Dependency Inversion

Game code depends on:

```text
GameContext
Board
TileCodec
```

instead of:

```text
serial implementation
REST implementation
Spring infrastructure
```

### Testability

Game logic can be executed with an in-memory:

```java
GameContext
```

and captured boards.

### Hardware independence

A game can be developed and tested without the physical Tileboard.

---

# 38. Extension Checklist

Before submitting a new game, verify:

- [ ] Implements `Game<T>`.
- [ ] Has a unique stable `gameId`.
- [ ] Has a `GameFactory`.
- [ ] Factory is a Spring `@Component`.
- [ ] Factory creates a fresh instance.
- [ ] `tileCodec()` matches the output tile type.
- [ ] No serial-port access exists in game code.
- [ ] No REST code exists in game code.
- [ ] Board dimensions are not hard-coded.
- [ ] `start()` initializes all state.
- [ ] `onPlayerInput()` handles touch input safely.
- [ ] Timed games use `GameContext.scheduleAtFixedRate()`.
- [ ] Shared mutable state is protected against concurrent access.
- [ ] Scheduled tasks are cancelled when appropriate.
- [ ] `stop()` releases game resources.
- [ ] Win/lose/terminal states cannot continue mutating the game.
- [ ] Unit tests cover the important state transitions.
- [ ] Game appears in `GET /api/v1/games`.
- [ ] Game can be started through `POST /api/v1/games/{gameId}/start`.

---

# 39. Summary

The intended way to develop a new Tileboard game is deliberately small:

```java
@Component
class MyGameFactory implements GameFactory {
    // describe and construct the game
}
```

and:

```java
final class MyGame implements Game<TileColor> {

    @Override
    public void start(GameContext<TileColor> context) {
        // initialize
    }

    @Override
    public void onPlayerInput(Board<Boolean> touchedTiles) {
        // apply player input
    }

    @Override
    public void stop() {
        // clean up
    }

    @Override
    public TileCodec<TileColor> tileCodec() {
        return TileColors.codec();
    }
}
```

Everything else is provided by the platform.

The key mental model is:

```text
                 GAME ENGINE
                      │
         ┌────────────┴────────────┐
         │                         │
   Board<Boolean>             Board<T>
     input/touch                output
         │                         │
         ▼                         ▼
      Game<T> ──────────────► GameContext<T>
                                  │
                                  ▼
                         Tileboard platform
                                  │
                                  ▼
                    tileboard-serial-protocol
                                  │
                                  ▼
                            Serial hardware
```

If a new game's implementation needs to know about a serial port, HTTP endpoint, frame header, or `TileGatewayClient`, that is a strong indication that the abstraction boundary is being bypassed.
