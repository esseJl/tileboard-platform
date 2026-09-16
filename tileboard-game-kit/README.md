# Tileboard Game Kit

Reusable, framework-free Java 17 building blocks for a **physical tileboard-color-touched-game** platform.

The kit is deliberately independent of Spring Boot, REST, serial ports, databases and UI. A game should contain **rules**, not infrastructure. The application/session layer owns lifecycle, hardware and delivery; this module supplies the reusable mechanics.

## Design goals

- Java 17
- Clean Code / SOLID
- immutable value objects and immutable snapshots
- thread-safe mutable trackers
- small functional interfaces
- dependency injection through constructor parameters
- injectable random source and clock
- deterministic unit testing
- no global game registry or switch statement
- no game-specific branching inside the kit
- safe validation and bounded state
- reusable across Spring, desktop, CLI or another JVM host

## Capabilities

The kit directly supports the platform concepts:

| Capability | Building block |
|---|---|
| touched tile | `TouchEvent`, `TileObservation` |
| touch count | `TouchTracker.count()` |
| touch order | `TouchSequence`, `TileObservation.sequence()` |
| time between touches | `TileObservation.sincePreviousTouch()` |
| tile position | `TilePosition` |
| tile color / target color | `TileObservation.tileColor()/targetColor()` |
| target matching | `TileObservation.matchesTarget()` |
| movement pattern | `MovementPattern`, `Patterns` |
| path | `TouchTracker.path()`, `PathFinder` |
| neighbors | `Neighbors` |
| connected nodes | `ConnectedComponents` |
| score | `ScoreBoard` |
| health/lives | `HealthTracker` |
| win/loss/draw/cancel | `Outcome` |
| combo | `ComboTracker` |
| level | `LevelProgression` |
| timer/countdown | `GameClock` |
| random | `RandomSource` |
| waves | `Patterns.wave()` |
| memory | touch history + application-defined reveal state |
| reaction speed | touch timestamp/delta + `RhythmWindow` |
| music/rhythm | `RhythmWindow` |
| puzzle | `GameGenre.PUZZLE`, grid/path/components |
| arcade | `GameGenre.ARCADE`, movement patterns |
| educational | `GameGenre.EDUCATIONAL` |
| educational movement | `GameGenre.EDUCATIONAL_MOTOR` |
| multiplayer | `ScoreBoard`, playerId on `TouchEvent`, genres |
| functional rules | `TouchRule`, `WinCondition`, `ScorePolicy` |

## Architecture

```text
                  +----------------------+
                  |    tileboard-app     |
                  | Spring / REST / SSE  |
                  +----------+-----------+
                             |
                  +----------v-----------+
                  |      Game class      |
                  | only game-specific   |
                  | rules and rendering  |
                  +----------+-----------+
                             |
                  +----------v-----------+
                  |   tileboard-game-kit |
                  | mechanics + policies |
                  +----------------------+
                             |
                  +----------v-----------+
                  | serial-protocol      |
                  | hardware transport   |
                  +----------------------+
```

### Important boundary

A concrete game should **not**:

- open a serial port
- read a database
- create a Spring bean
- create an HTTP client
- own a global executor
- depend on another game
- parse REST requests

It may:

- consume touch observations
- maintain game-specific state
- use the kit's trackers/patterns/rules
- publish a board through the application's `GameContext`
- schedule through the application's clock/context

## Existing platform integration

Your current `tileboard-app` already references:

```xml
<dependency>
    <groupId>com.tileboard</groupId>
    <artifactId>tileboard-game-kit</artifactId>
    <version>1.0.0</version>
</dependency>
```

and the application imports these kit types:

```java
com.tileboard.gamekit.catalog.GameGenre
com.tileboard.gamekit.pattern.MovementPattern
com.tileboard.gamekit.pattern.Patterns
com.tileboard.gamekit.state.HealthTracker
com.tileboard.gamekit.state.Outcome
com.tileboard.gamekit.time.Cancellable
com.tileboard.gamekit.time.GameClock
com.tileboard.gamekit.time.RandomSource
```

This module implements those APIs and adds the broader reusable platform primitives.

## Adding the module to the reactor

Add this line to the root `pom.xml`:

```xml
<module>tileboard-game-kit</module>
```

Recommended order:

```xml
<modules>
    <module>tileboard-serial-protocol</module>
    <module>tileboard-game-kit</module>
    <module>tileboard-app</module>
</modules>
```

Then:

```bash
mvn clean verify
```

If the kit is developed separately:

```bash
cd tileboard-game-kit
mvn clean verify
mvn install
```

## Production game recipe

A new game should normally have:

```text
games/
└── mygame/
    ├── MyGame.java
    ├── MyGameFactory.java
    └── MyGameTuning.java
```

### 1. Define metadata

Use the application's `GameDefinition` and tag it with the kit's `GameGenre`.

```java
private static final GameDefinition DEFINITION = new GameDefinition(
    "reaction-path",
    "Reaction Path",
    "Touch the requested tiles in order as quickly as possible.",
    Set.of(GameGenre.ARCADE, GameGenre.EDUCATIONAL_MOTOR)
);
```

### 2. Inject dependencies

Do not create randomness/timing infrastructure inside the game.

```java
ReactionPathGame(
    RandomSource random,
    GameClock clock,
    ...
)
```

In production Spring wiring, inject `RandomSource` and the session clock/context. In tests, use:

```java
RandomSource.seeded(42L)
```

and a fake clock.

### 3. Track every touch

```java
TouchTracker tracker = new TouchTracker(
    event -> currentColor(event.position()),
    event -> targetColor(event.position())
);

TileObservation observation = tracker.record(event);

long reaction = observation.sincePreviousTouch().toMillis();
int order = (int) observation.sequence();
boolean correct = observation.matchesTarget();
```

The game now has, without duplicating bookkeeping:

- touched position
- touch count
- order
- time between touches
- current/target color
- complete path

### 4. Use reusable rules

```java
TouchRule correctTarget = TileObservation::matchesTarget;

if (correctTarget.test(observation)) {
    combo.hit();
    score.add(event.playerId(), 100);
} else {
    combo.miss();
    health.damage(1);
}
```

### 5. Use movement patterns

```java
MovementPattern pattern = Patterns.rotatingBuiltins(random);
List<List<Position>> frames = pattern.framesFor(width, height);
```

Available built-ins:

- rows
- columns
- diagonal
- wave
- random tiles
- randomized selection of built-ins

A game can define a new pattern without modifying the kit:

```java
MovementPattern spiral = (width, height) -> ...;
```

### 6. Use paths and neighbours

```java
Optional<List<TilePosition>> path = PathFinder.shortestPath(
    start,
    goal,
    width,
    height,
    NeighborMode.ORTHOGONAL,
    walkable::contains
);
```

For connected puzzle groups:

```java
List<Set<TilePosition>> groups =
    ConnectedComponents.find(width, height,
        NeighborMode.EIGHT_WAY,
        activeTiles::contains);
```

## Multiplayer model

`TouchEvent` carries a `playerId`:

```java
new TouchEvent(position, Instant.now(), "player-2");
```

`ScoreBoard` keeps scores independently:

```java
scores.add("player-1", 100);
scores.add("player-2", 50);
Map<String, Long> snapshot = scores.snapshot();
```

For team games, use a stable team ID as the score key or place a team mapper in the application/game layer. The kit intentionally does not impose a particular multiplayer protocol.

## Timer / countdown

Games should use the session/application clock rather than `Thread.sleep`.

```java
clock.scheduleOnce(Duration.ofSeconds(3), this::beginRound);

clock.scheduleAtFixedRate(
    Duration.ofMillis(100),
    this::tick
);
```

For production, the supplied `SystemGameClock` is available for standalone use. In the Spring application, the existing `GameContext` can remain the lifecycle owner.

Every scheduled callback must be treated as concurrent with hardware callbacks. Protect game-specific mutable state with a lock or another explicit concurrency strategy.

## Thread-safety rule

The physical gateway callback, REST thread and scheduler can run concurrently.

Recommended game pattern:

```java
private final Object lock = new Object();

public void onPlayerInput(...) {
    synchronized (lock) {
        // mutate game state
    }
}

private void tick() {
    synchronized (lock) {
        // mutate game state
    }
}

public void stop() {
    synchronized (lock) {
        // cancel and invalidate state
    }
}
```

The kit's mutable trackers (`TouchSequence`, `TouchTracker`, `HealthTracker`, `ScoreBoard`, `ComboTracker`) are thread-safe. Value objects and snapshots are immutable.

## Randomness

Never use an uncontrolled `new Random()` in game logic.

Production:

```java
RandomSource random = RandomSource.threadLocal();
```

Deterministic tests:

```java
RandomSource random = RandomSource.seeded(1234L);
```

For even stricter tests, implement the functional interface yourself.

## Reaction / rhythm games

`RhythmWindow` represents a target timing and tolerance:

```java
RhythmWindow window =
    new RhythmWindow(Duration.ofMillis(500), Duration.ofMillis(120));

RhythmWindow.Accuracy accuracy = window.accuracy(actualDelta);
```

Results:

```text
PERFECT
GOOD
OK
MISS
```

A game may map these to score/combo values through `ScorePolicy`.

## Validation

Use `BoardDimensions`, `GameArguments`, and constructor validation at boundaries.

Do not allow invalid board dimensions, negative health, invalid levels, blank player IDs or empty random collections to propagate into a running session.

## Error-handling policy

The kit uses:

- `IllegalArgumentException` for invalid programmer/configuration input
- `NullPointerException` only through explicit `Objects.requireNonNull` contracts
- immutable snapshots at public read boundaries

The application layer should translate domain failures into its API error model (`ProblemDetail` in the current platform).

Do not catch `Exception` in a game and silently continue. A scheduler callback should be isolated/logged by the lifecycle owner.

## Testing strategy

Test the kit independently from hardware:

```bash
mvn test
```

Test concrete games with:

1. deterministic `RandomSource`
2. fake/in-memory `GameClock`
3. fake board output sink
4. synthetic `TouchEvent`
5. assertions on state/output
6. concurrency tests for touch/tick/stop races

The serial hardware should be covered separately by integration tests.

## Recommended game taxonomy

### Puzzle

Examples:

- Color Match
- Memory Pair
- Connected Groups
- Path Finder
- Tile Flood
- Sequence Puzzle

### Arcade

Examples:

- Jump/Avoid
- Wave Runner
- Whack-a-Tile
- Reaction Rush
- Target Chase

### Educational

Examples:

- Number Hunt
- Alphabet Hunt
- Math Path
- Shape Match
- Geography Grid

### Educational Motor

Examples:

- Simon-style movement
- Follow-the-path
- Direction training
- Left/right reaction
- Full-body sequence

### Multiplayer

Examples:

- Territory
- Race
- Team Relay
- Duel
- Cooperative Memory

### Rhythm

Examples:

- Beat Touch
- Rhythm Path
- Perfect Timing
- Musical Simon

## Production checklist

Before a game is released:

- [ ] Game has no direct hardware dependency
- [ ] Game has no database dependency
- [ ] Game has no static mutable state
- [ ] Game state is protected from concurrent touch/tick/stop callbacks
- [ ] All timers are cancellable
- [ ] `stop()` is idempotent
- [ ] no callback publishes after the game is stopped
- [ ] random behavior can be seeded in tests
- [ ] board dimensions are validated
- [ ] every player has a stable ID
- [ ] score/health/combo cannot overflow unexpectedly
- [ ] win/loss/draw/cancel transitions are explicit
- [ ] every rule has unit tests
- [ ] edge cases for 1x1, 1xN and Nx1 boards are tested
- [ ] timeout behavior is tested
- [ ] duplicate touches are tested
- [ ] simultaneous touches are tested
- [ ] malformed hardware input is tested outside the game
- [ ] game can be stopped and restarted without leaked scheduler tasks
- [ ] Maven `clean verify` passes
- [ ] Javadoc/source artifacts can be generated

## API compatibility note

`MovementPattern` intentionally uses the existing platform's
`com.tileboard.serial.board.Position` because the current `Game` implementations
already render directly into the serial-protocol `Board<T>`. The higher-level
game-kit APIs use `TilePosition` for framework-independent domain calculations.

This gives the project a clean migration path without forcing the hardware
model into every future game.

## Versioning

The module follows semantic versioning expectations:

- breaking public API change -> major version
- backward-compatible feature -> minor version
- bug/security fix -> patch version

Do not change wire-protocol semantics from this module. Hardware protocol
compatibility belongs to `tileboard-serial-protocol`.

## License

Define the repository's project license before public distribution.
