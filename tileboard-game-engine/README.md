# Tileboard Game Engine

> A production-ready, **transport-agnostic** game engine built on top of `tileboard-serial-protocol`.
> Write a physical LED tile-board game in ~40 lines of plain Java — or wire it into a Spring Boot
> application with REST + SSE in an afternoon.

```
com.tileboard:tileboard-game-engine:1.0.0     (Java 17, jar)
```

This README is three documents in one:

1. **[Quick Start & Tutorials](#4-quick-start)** — how to build games with plain Java and with Spring Boot.
2. **[Core Architecture Deep Dive](#6-core-architecture-deep-dive)** — how the engine actually works, class by class,
   thread by thread.
3. **[Design Philosophy](#3-design-philosophy)** — *why* the code looks the way it does, which trade-offs were taken
   deliberately, and which rules every contributor (and every game author) should follow.

---

## Table of contents

1. [What this library is (and isn't)](#1-what-this-library-is-and-isnt)
2. [Feature matrix](#2-feature-matrix)
3. [Design philosophy](#3-design-philosophy)
4. [Quick start](#4-quick-start)
5. [Spring Boot integration](#5-spring-boot-integration)
6. [Core architecture deep dive](#6-core-architecture-deep-dive)
7. [Built-in features reference](#7-built-in-features-reference)
8. [Event bus & SSE streaming](#8-event-bus--sse-streaming)
9. [Threading model & concurrency contract](#9-threading-model--concurrency-contract)
10. [Tutorials](#10-tutorials)
11. [Configuration reference](#11-configuration-reference)
12. [Extending the engine](#12-extending-the-engine)
13. [Build, dependencies & known gotchas](#13-build-dependencies--known-gotchas)
14. [API cheat sheet](#14-api-cheat-sheet)

---

## 1. What this library is (and isn't)

### It IS

- A **game runtime**: session lifecycle, per-session state, tick loop, touch routing, win/loss handling.
- A **feature toolbox**: scoring, health/lives, levels, combos, timers, touch history & analytics, board utilities,
  neighbour lookup, pattern matching, seeded randomness, wave effects, memory-game support, reaction-speed measurement,
  graph algorithms (BFS / connected components) and a full animation system.
- An **event source**: everything meaningful is published on an in-process `GameEventBus`, which can be bridged to
  Server-Sent Events for a live web dashboard.
- An **optional Spring Boot starter**: one auto-configuration class, three beans, zero required configuration.

### It is NOT

- **Not** a serial/transport library. It never opens a port, never picks a baud rate, never performs a handshake. It
  receives an already-open `TileGatewayClient` from the application.
- **Not** a rendering engine for screens. The "renderer" is a grid of physical tiles; the only output primitive is
  "send this `Board<TileColor>` to the hardware".
- **Not** a Spring library at its core. `com.tileboard.engine.core`, `.feature`, `.event`, `.model`, `.codec` and
  `.exception` compile and run with only `tileboard-serial-protocol` + `slf4j-api` on the classpath.

### Layering

```
┌──────────────────────────────────────────────────────────────────────┐
│  your application (tileboard-app)                                    │
│  REST controllers · port assignment · serial connection lifecycle    │
└───────────────┬──────────────────────────────────┬───────────────────┘
                │ publishes GatewayConnectedEvent   │ @Bean Game
┌───────────────▼──────────────────────────────────▼───────────────────┐
│  tileboard-game-engine  (this library)                               │
│                                                                      │
│  .spring  GameEngineManager · TileboardEngineAutoConfiguration       │  ← optional layer
│  .sse     GameEventSseEmitter · SseGameEvent                         │  ← optional layer
│  ─────────────────────────────────────────────────────────────────   │
│  .core    GameEngine · GameSession · GameContext · GameRegistry      │  ← framework-free
│  .feature AnimationSystem · ScoreSystem · HealthSystem · … 17 total  │
│  .event   GameEventBus · GameEvent · GameEventType                   │
│  .model   Player · TileColor · TileEvent · TouchSequence · Team      │
│  .codec   ColorTileCodec · EngineFrameRouter                         │
└───────────────┬──────────────────────────────────────────────────────┘
                │ FrameListener / sendBoard
┌───────────────▼──────────────────────────────────────────────────────┐
│  tileboard-serial-protocol                                           │
│  TileGatewayClient · Frame · Command · Board<T> · Position · TileCodec│
└──────────────────────────────────────────────────────────────────────┘
```

The **only** seam between the two lower layers is `GatewayConnectedEvent` / `GatewayDisconnectedEvent`. That single
design decision is responsible for most of the rest of the architecture — see
[§3.3 "The engine is a passenger, not a driver"](#33-the-engine-is-a-passenger-not-a-driver).

---

## 2. Feature matrix

| Area                | Class                  | Thread-safe                 | Blocking?  | Notes                                                    |
|---------------------|------------------------|-----------------------------|------------|----------------------------------------------------------|
| Session runtime     | `GameSessionImpl`      | ✅                          | no         | Implements both `GameSession` and `GameContext`          |
| Game catalogue      | `DefaultGameRegistry`  | ✅ (`ConcurrentHashMap`)    | no         | Instance *or* factory registration                       |
| Engine              | `GameEngineImpl`       | ✅                          | no         | Owns the DATA_IN frame router                            |
| Scores              | `ScoreSystem`          | ✅ (`AtomicInteger`)        | no         | Lazily creates unknown player ids at `0`                 |
| Health / lives      | `HealthSystem`         | ✅                          | no         | Clamped to `[0, maxHealth]`, configurable default        |
| Levels & difficulty | `LevelSystem`          | ✅                          | no         | `IntUnaryOperator` speed scaler                          |
| Combos              | `ComboTracker`         | ✅                          | no         | Auto-resets after a 2 s (configurable) gap               |
| Timers              | `GameTimer`            | ✅ (`volatile`)             | no         | Countdown fires exactly once, on tick                    |
| Touch log           | `TouchHistory`         | ✅ (`CopyOnWriteArrayList`) | no         | Unbounded — see [§13.4](#134-known-limitations--gotchas) |
| Touch analytics     | `TouchAnalyzer`        | ✅                          | no         | Stateless queries over the live history                  |
| Board utilities     | `BoardFeature`         | ✅ (immutable)              | no         | find / count / distance / bounds                         |
| Neighbours          | `NeighborFinder`       | ✅ (immutable)              | no         | 4-way, 8-way, diagonal-only + flood fill                 |
| Patterns            | `PatternMatcher`       | ✅ (stateless)              | no         | tail / exact / contains / cyclic                         |
| Randomness          | `RandomFeature`        | ⚠️ (`java.util.Random`)     | no         | Seeded & re-seedable for replays                         |
| Wave effects        | `WaveGenerator`        | ✅                          | **yes**    | Blocking `Thread.sleep` loops — run off-thread           |
| Memory game         | `MemoryFeature`        | ✅ (`synchronizedList`)     | no         | Over-input fails fast instead of crashing                |
| Reaction speed      | `ReactionSpeedTracker` | ✅ (`LongAdder`/atomics)    | no         | Fed automatically by the engine                          |
| Graph               | `GraphFeature`         | ✅ (immutable)              | no         | BFS shortest path, connected components                  |
| Animations          | `AnimationSystem`      | ✅                          | no (async) | Own daemon thread, cooperative cancellation              |
| Events              | `GameEventBusImpl`     | ✅ (`CopyOnWriteArrayList`) | no         | Listeners run on a dedicated thread                      |
| SSE                 | `GameEventSseEmitter`  | ✅                          | no         | One emitter per HTTP client                              |

---

## 3. Design philosophy

These are the rules the code was written against. When you extend the engine, extend it *in this direction*.

### 3.1 Framework-free core, Spring as a thin shell

Every Spring import in this project lives in exactly two packages: `com.tileboard.engine.spring` and
`com.tileboard.engine.sse`. Nothing in `.core`, `.feature`, `.event`, `.model`, `.codec` or `.exception` mentions
Spring. Consequences:

- The engine can be unit-tested with JUnit alone — no `ApplicationContext`, no `@SpringBootTest`, no mocks of Spring
  infrastructure.
- It can be embedded in a plain `main()`, a JavaFX kiosk app, an Android-side JVM, a Quarkus or Micronaut service.
- The Spring layer is `optional=true` in the POM, so consumers that don't use it don't drag `spring-web` in.

**Rule:** if you find yourself adding `org.springframework.*` to a core class, stop. Put the adapter in `.spring`.

### 3.2 The `GameContext` is the whole API surface a game author sees

A `Game` implementation receives exactly one object — `GameContext ctx` — and everything it can possibly do is a method
on that object:

```java
ctx.scores().

add(playerId, 10);
ctx.

setTile(row, col, TileColor.GREEN);
ctx.

animations().

playCountdown();
ctx.

winSession(List.of(winner));
```

This is a deliberate **service-locator / "fat context"** choice. The alternative — injecting 17 collaborators into every
game constructor — would make each game a 60-line wiring exercise and would break the `GameFactory` contract (a factory
must be able to create a game with *no* arguments). The trade-off we accepted:

- ✅ Games are trivially constructible (`MyGame::new`) and trivially testable (implement `GameContext` once in a test
  double, reuse it for every game test).
- ✅ New features become available to *all* existing games the moment they are added to the context — no game needs a
  constructor change.
- ⚠️ The interface is wide (30+ methods). We mitigate this with grouping and naming, not with splitting: session info,
  board I/O, features, event bus, session control.
- ⚠️ A game can call anything, including `stopSession()`. That is intentional — games are trusted first-party code, not
  plugins from untrusted sources.

**Rule:** a new capability for game authors = a new method on `GameContext` + a new field in `GameSessionImpl`. Never a
new constructor parameter on `Game`.

### 3.3 The engine is a passenger, not a driver

The engine **never** owns the serial connection. It is handed an already-started `TileGatewayClient` through
`GatewayConnectedEvent`, and it is told when that client dies through `GatewayDisconnectedEvent`.

Why this matters more than it sounds:

1. **One physical port, one owner.** An earlier revision of this library had its own `serial-port`, `board-width`,
   `board-height` and handshake properties. The result was two independent connections fighting over the same UART.
   Those properties were deleted — `TileboardEngineProperties` now contains exactly one field: `tickInterval`.
2. **Reconnect is a hard reset.** A `TileGatewayClient` cannot be reused after a disconnect. Therefore
   `GameEngineManager` throws the *entire engine* away and builds a new `GameEngineImpl` on every connect event,
   stopping orphaned sessions first. Sessions are cheap; stale transports are not.
3. **The same REST endpoints the app already has become the game engine's power switch.** Connect the board via
   `/api/v1/ports/connect` → engine comes up. Disconnect → engine goes down. No extra endpoints, no extra config.

**Rule:** anything hardware-specific (port names, baud rates, geometry discovery, handshakes, device ids) belongs to the
application. The engine only knows "there is a gateway, and it is *w×h*".

### 3.4 One session = one thread of control (+ one animation thread)

Each `GameSessionImpl` owns a **single-threaded `ScheduledExecutorService`** named `tileboard-tick-<sessionId>`, and
each `AnimationSystem` owns a **single-threaded executor** named `tileboard-animation`.

- A game's `onTick` never runs concurrently with itself → game authors can keep plain `int`/`boolean` fields for
  tick-driven state without locking.
- Animations never interleave with each other → a countdown cannot be painted over by a win animation.
- Both are **daemon** threads, so a forgotten session can never keep the JVM alive.
- Both are shut down in `finishSession` (`cancelTick()` + `animationSystem.shutdown()`), so a session that ends leaks
  nothing.

**Rule:** never create a thread inside a `Game`. Ask the context for a feature, or use `animations()`.

### 3.5 Defensive copies and immutability at every boundary

- `GameSessionImpl` stores `List.copyOf(players)`.
- `GameResult` copies its winner list and score map in its compact constructor, so a result can never change after the
  session ended.
- `GameEvent` copies its payload map — an event published to N listeners cannot be mutated by listener #1.
- `MemoryFeature.setTarget` copies the sequence; `target()` returns the immutable copy.
- `TouchSequence` validates `positions.size() == timestamps.size()` and then copies both.
- `GameState.snapshot()` returns an unmodifiable copy, safe to hand to Jackson on another thread.

**Rule:** if a value crosses a thread boundary or an API boundary, copy it. Records with compact constructors are the
idiomatic way to do that here.

### 3.6 Fail loudly at construction, fail softly at runtime

Two different failure policies, applied consistently:

- **Construction / configuration errors are fatal.** `GameDescriptor` rejects `requiredWidth <= 0`,
  `minPlayers < 1`, `maxPlayers < minPlayers`; `EngineFrameRouter` rejects non-positive geometry; `Player`,
  `GameResult`, `GameEvent`, `TouchSequence` reject `null`. These are programmer errors and must explode immediately,
  with a message naming the offending field.
- **Runtime errors from *game code* are contained.** An exception escaping `onTileEvent` or `onTick` is caught, logged,
  and handed to `game.onError(ctx, e)`. The default `onError` stops the session; a game may override it to recover. If
  `onError` *itself* throws, the session is force-stopped. The serial reader thread and every other session keep
  running.
- **Malformed hardware input is discarded, not fatal.** `EngineFrameRouter` drops a `DATA_IN` payload whose length
  doesn't equal `width*height` and logs a warning. A flaky cable must not take down the JVM.

**Rule:** `throw` in a constructor / record validation, `log.warn` + degrade in a callback.

### 3.7 Publish, don't call

Nothing in the engine reaches out to an HTTP response, a websocket, a database or a metrics registry. Instead it
publishes `GameEvent`s on the `GameEventBus`, and *adapters* subscribe:

- `GameEventSseEmitter` → Server-Sent Events
- `GameEngineImpl`'s own cleanup subscription → removes finished sessions from `activeSessions`
- your code → persistence, analytics, a Discord webhook, anything

The bus dispatches on a dedicated single-threaded executor, so `publish()` is O (1) enqueue and can never block the
serial reader or the tick loop. A listener that throws is logged and skipped; the remaining listeners still run.

**Rule:** if a new subsystem needs to *react* to something, add an event type and a subscriber — not a callback
parameter.

### 3.8 Hardware output has exactly one choke point

Every tile change funnels through `GameSessionImpl`'s `boardWriteLock`:

```java
synchronized (boardWriteLock){
        boardBuffer.

set(row, col, color);                 // mutate the shared buffer
    gateway.

sendBoard(DATA_OUT, SET, boardBuffer, colorCodec);   // one atomic wire write
}
```

Because the tick thread, the touch-callback thread and the animation thread can all want to write at the same time, this
lock guarantees the board on the wire is always a *consistent* snapshot and never a half-updated mix. The shared
`boardBuffer` is also what makes `setTile(r, c, color)` possible without the game tracking the whole board.

**Rule:** never call `gateway.sendBoard(...)` from game code. Use `ctx.setTile` / `ctx.fillBoard` /
`ctx.publishBoard`.

### 3.9 Cancellation must be *cooperative and interrupting*

`AnimationSystem.cancelCurrent()` is the reference implementation of that sentence, and its Javadoc documents the bug it
fixed:

> `CompletableFuture.cancel(true)` **never interrupts** the thread running the task, no matter what you pass as
> `mayInterruptIfRunning`. A "cancelled" countdown therefore kept sleeping and painting for another three seconds,
> racing with the animation that was supposed to replace it.

The correct pattern, which the code now follows, is *both* mechanisms:

```java
cancelRequested.set(true);   // 1. flag: noticed by the next sleep() that isn't currently blocked

Thread t = runningThread;
if(t !=null)t.

interrupt(); // 2. interrupt: wakes a sleep() that IS currently blocked
```

and the worker clears its own state on exit (`runningThread = null; Thread.interrupted();`) so the next task submitted
to the same pooled thread does not inherit a stale interrupt flag. Cancellation is signalled internally by a private
`AnimationCancelledException` with **stack traces disabled**
(`super(null, null, false, false)`) — it is control flow, not an error, and it must be cheap because it can fire
hundreds of times per second.

**Rule:** if you add a long-running, cancellable operation, copy this pattern. A flag alone is not cancellation.

### 3.10 Small, final, single-responsibility classes

Every feature class is `final`, has a private or trivial constructor, no inheritance hierarchy, no annotations, and no
dependency on any other feature class except where explicitly wired (`TouchAnalyzer` → `TouchHistory`). There is exactly
one interface hierarchy in the whole engine (`Game extends GameLifecycle`) and one exception hierarchy. If you cannot
explain a class in one sentence, it is two classes.

---

## 4. Quick start

### 4.1 Add the dependency

```xml

<dependency>
    <groupId>com.tileboard</groupId>
    <artifactId>tileboard-game-engine</artifactId>
    <version>1.0.0</version>
</dependency>
```

> ⚠️ See [§13](#13-build-dependencies--known-gotchas) — the current `pom.xml` in this repository pins
> `jackson-datatype-jsr310:2.22.1`, which does not exist on Maven Central, and declares `spring-webmvc` with
> `compile` scope even though the Spring layer is meant to be optional. Fix both before your first build.

### 4.2 Write a game (plain Java, no Spring)

```java
package com.example.games;

import com.tileboard.engine.core.*;
import com.tileboard.engine.model.*;
import com.tileboard.serial.board.Position;

/** Whack-a-mole: one tile lights up, the player has 2 s to hit it. 10 hits wins. */
public final class WhackAMoleGame implements Game {

    private static final int TARGET_HITS = 10;
    private Position current = new Position(0, 0);   // single-session game; see §6.5 about singletons

    @Override
    public GameDescriptor descriptor() {
        return GameDescriptor.builder("whack-a-mole", "Whack-a-Mole")
                .category("ARCADE")
                .description("Hit the lit tile before it moves.")
                .boardSize(8, 8)
                .players(1, 4)
                .build();
    }

    @Override
    public void onStart(GameContext ctx) {
        ctx.timer().startCountdown(java.time.Duration.ofMinutes(1), ctx::loseSession);
        ctx.fillBoard(TileColor.OFF);
        nextMole(ctx);
    }

    @Override
    public void onTick(GameContext ctx) {
        ctx.timer().checkExpiry();            // fires the countdown callback exactly once
        if (ctx.timer().isExpired()) return;
        // (animate, count down UI, etc.)
    }

    @Override
    public void onTileEvent(GameContext ctx, TileEvent event) {
        Player player = ctx.players().get(0);
        if (!event.position().equals(current)) {
            ctx.health().damage(player.id());
            if (!ctx.health().isAlive(player.id())) {
                ctx.animations().playLoseAnimation();
                ctx.loseSession();
            }
            return;
        }

        int combo = ctx.combos().hit();
        int points = 10 * ctx.combos().multiplier(3);
        ctx.scores().add(player.id(), points);
        ctx.eventBus().publish(com.tileboard.engine.event.GameEvent.of(
                com.tileboard.engine.event.GameEventType.SCORE_CHANGED,
                ctx.sessionId(), ctx.gameId(),
                java.util.Map.of("playerId", player.id(), "score", ctx.scores().get(player.id()))));

        if (ctx.scores().get(player.id()) >= TARGET_HITS * 10) {
            ctx.animations().playWinAnimation();
            ctx.winSession(ctx.players());
            return;
        }
        nextMole(ctx);
    }

    private void nextMole(GameContext ctx) {
        ctx.setTile(current.row(), current.col(), TileColor.OFF);
        current = ctx.random().randomPosition();
        ctx.setTile(current.row(), current.col(), TileColor.YELLOW);
        ctx.reactionSpeed().stimulus();       // next touch is measured as a reaction
    }

    @Override
    public void onStop(GameContext ctx, GameResult result) {
        ctx.fillBoard(TileColor.OFF);
    }
}
```

Note what is *absent*: no threads, no locks, no serial code, no `Board` bookkeeping, no score map, no combo timestamp
logic. All of it is a method call on `ctx`.

### 4.3 Run it without Spring

```java
public static void main(String[] args) {
    TileGatewayClient gateway = /* built by YOUR application: port, baud, handshake */;

    GameEventBus eventBus = new GameEventBusImpl();
    GameRegistry registry = new DefaultGameRegistry();
    registry.register(new WhackAMoleGame());

    GameEngine engine = new GameEngineImpl(
            registry, gateway, eventBus,
            Duration.ofMillis(100),   // tick interval
            8, 8);                    // ACTUAL connected board geometry

    String sessionId = engine.startGame("whack-a-mole", List.of(Player.solo("Alice")));

    eventBus.subscribe(e -> System.out.println(e.type() + " " + e.payload()));

    // ... later
    engine.stopGame(sessionId);
}
```

`GameEngineImpl` registers an `EngineFrameRouter` on the gateway in its constructor, so `DATA_IN` frames become
`TileEvent`s automatically. You do not touch the wire format anywhere.

---

## 5. Spring Boot integration

### 5.1 What auto-configuration gives you

`TileboardEngineAutoConfiguration` is registered via
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, so **putting the jar on the
classpath is the entire installation**. It creates three beans, each `@ConditionalOnMissingBean`:

| Bean                | Type                | Purpose                                                  |
|---------------------|---------------------|----------------------------------------------------------|
| `gameEventBus`      | `GameEventBus`      | Shared bus for all sessions                              |
| `gameRegistry`      | `GameRegistry`      | Auto-populated from **every `Game` bean** in the context |
| `gameEngineManager` | `GameEngineManager` | Binds/unbinds the engine on gateway connect/disconnect   |

Declare your game as a bean and it is registered for free:

```java

@Configuration
public class GamesConfig {
    @Bean
    WhackAMoleGame whackAMole() {
        return new WhackAMoleGame();
    }

    @Bean
    SimonSaysGame simonSays() {
        return new SimonSaysGame();
    }
}
```

Startup log:

```
Auto-registered game: 'Whack-a-Mole' (whack-a-mole)
Registered game 'Whack-a-Mole' (whack-a-mole)
```

> The engine bean itself is deliberately **not** created eagerly: it needs an open gateway, and this library has no
> opinion on serial ports. Inject `GameEngineManager`, not `GameEngine`.

### 5.2 Publishing the connection lifecycle

Your `SerialConnectionManager` (or whatever owns the port) publishes two records:

```java

@Service
public class SerialConnectionService {

    private final ApplicationEventPublisher publisher;
    private TileGatewayClient client;

    public void connect(String portName, int baud, int width, int height) {
        this.client = TileGatewayClient.builder()
                .port(portName).baudRate(baud).start();          // your code, your library
        publisher.publishEvent(new GatewayConnectedEvent(client, width, height));
    }

    public void disconnect() {
        publisher.publishEvent(new GatewayDisconnectedEvent());
        if (client != null) client.close();
    }
}
```

`GameEngineManager` reacts:

- on **connect** → builds a fresh `GameEngineImpl` bound to that client and geometry (stopping any sessions of a
  previous, leaked engine first, with a warning);
- on **disconnect** → stops all active sessions and drops the engine.

Both handlers are `synchronized`, so a rapid disconnect/connect pair can never leave two engines bound.

### 5.3 A complete controller

```java

@RestController
@RequestMapping("/api/v1/games")
public class GameController {

    private final GameEngineManager engineManager;
    private final GameEventBus eventBus;

    public GameController(GameEngineManager engineManager, GameEventBus eventBus) {
        this.engineManager = engineManager;
        this.eventBus = eventBus;
    }

    /** All registered game types — usable before any hardware is connected. */
    @GetMapping
    public List<GameDescriptor> listGames() {
        return engineManager.current()
                .map(e -> e.registry().listAll())
                .orElseGet(() -> List.of());       // or inject GameRegistry directly
    }

    /** @throws EngineNotReadyException → 503 if the board is not connected yet. */
    @PostMapping("/{gameId}/start")
    public Map<String, String> start(@PathVariable String gameId,
                                     @RequestBody(required = false) List<PlayerDto> dtos) {
        List<Player> players = (dtos == null || dtos.isEmpty())
                ? List.of(Player.solo("player-1"))
                : dtos.stream().map(d -> Player.of(d.name(), d.role())).toList();

        String sessionId = engineManager.require().startGame(gameId, players);
        return Map.of("sessionId", sessionId);
    }

    @PostMapping("/sessions/{sessionId}/stop")
    public ResponseEntity<Void> stop(@PathVariable String sessionId) {
        engineManager.require().stopGame(sessionId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/sessions")
    public List<SessionDto> sessions() {
        return engineManager.require().activeSessions().stream()
                .map(s -> new SessionDto(s.sessionId(), s.gameId(), s.status().name()))
                .toList();
    }

    /** Live event stream for one session. */
    @GetMapping(value = "/sessions/{sessionId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String sessionId) {
        return GameEventSseEmitter.forSession(sessionId, eventBus);
    }

    /** Live event stream for the whole engine (dashboards). */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter allEvents() {
        return GameEventSseEmitter.global(eventBus);
    }
}
```

Map `EngineNotReadyException` to a clean HTTP status:

```java

@RestControllerAdvice
public class EngineExceptionHandler {

    @ExceptionHandler(EngineNotReadyException.class)
    public ResponseEntity<Map<String, String>> notReady(EngineNotReadyException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(GameNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(GameNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }
}
```

### 5.4 `application.yml`

```yaml
tileboard:
  engine:
    tick-interval: 100ms     # the ONLY engine property that exists
```

---

## 6. Core architecture deep dive

This section is the "how it's implemented" half of the README. Read it before changing engine internals.

### 6.1 The type hierarchy

```
GameLifecycle (interface, 5 methods, 3 with defaults)
   ▲
   │ extends
Game (interface, + descriptor())
   ▲
   │ implements
YourGame

GameSession (interface: sessionId, gameId, status, result, stop)
GameContext (interface: 30+ methods)
   ▲                ▲
   └────┬───────────┘
        │ implements BOTH
   GameSessionImpl (final class, ~350 lines)
```

`GameSessionImpl` implementing both interfaces is the central trick of the design:

- The **engine and the outside world** see a `GameSession` — a handle to query status and stop.
- The **game author** sees a `GameContext` — the full toolbox.
- They are the same object, so there is no synchronisation problem between "the session's view of itself" and "the
  game's view of the session". There is exactly one source of truth.

`GameSessionImpl` is **never** exposed as `GameContext` to anything but the game, and never as `GameSession` to the
game. The two roles are separated by the interface, not by the object.

### 6.2 Session lifecycle, step by step

```
GameEngine.startGame(gameId, players)
  │
  ├─ registry.instantiate(gameId)          → fresh Game (or the registered singleton)
  ├─ sessionId = UUID.randomUUID()
  ├─ new GameSessionImpl(...)              → status = IDLE
  │     ├─ boardBuffer = new Board<>(w, h, TileColor.OFF)
  │     ├─ 17 features constructed
  │     ├─ AnimationSystem constructed (its own daemon thread)
  │     └─ tickExecutor.scheduleAtFixedRate(this::runTick, interval, interval)
  ├─ activeSessions.put(sessionId, session)
  ├─ session.start()
  │     ├─ CAS IDLE → RUNNING              (throws GameSessionException if already started)
  │     ├─ gameTimer.start()
  │     ├─ game.onStart(this)              ← runs on the CALLER's thread
  │     ├─ publish SESSION_STARTED
  │     └─ on RuntimeException: log.error + forceStop()
  └─ eventBus.subscribe(cleanup)           → removes the session on FINISHED/STOPPED, then unsubscribes itself

runTick()  every interval, on tileboard-tick-<id>
  ├─ return immediately if status != RUNNING
  ├─ game.onTick(this)
  ├─ publish TICK with snapshotForSse()
  └─ on RuntimeException: game.onError(...) → forceStop() if that throws too

routeTouchFrame(touchBoard)  on the gateway's callback thread
  ├─ for each session in activeSessions
  │    └─ for each TRUE position within that session's w×h
  │         └─ session.handleTileEvent(TileEvent.touch(pos, sessionId))
  └─ handleTileEvent: status check → touchHistory.record → reactionSpeed.record → game.onTileEvent

finishSession(finalStatus, winners)   (winSession / loseSession / stopSession / stop / forceStop)
  ├─ CAS RUNNING → finalStatus, or PAUSED → finalStatus; otherwise return (idempotent)
  ├─ gameTimer.stop(); cancelTick(); animationSystem.shutdown()
  ├─ result = new GameResult(..., scoreSystem.allScores(), gameTimer.elapsed(), now)
  ├─ game.onStop(this, result)         (exceptions logged, never propagated)
  ├─ fillBoard(TileColor.OFF)          ← the board is ALWAYS left dark
  └─ publish SESSION_FINISHED or SESSION_STOPPED
```

Key implementation properties:

- **Idempotent termination.** `finishSession` uses `compareAndSet` from both `RUNNING` and `PAUSED`. A game calling
  `winSession()` twice, or `winSession()` followed by an engine `stop()`, produces exactly one `GameResult` and one
  event.
- **The result is computed before `onStop`.** So `onStop` can read `result.winners()` and `result.duration()` and still
  change the board (e.g. play a victory pattern). It cannot change the *scores* recorded in the result — those were
  snapshotted.
- **`onStart` runs on the caller's thread**, not the tick thread. This is documented on `GameLifecycle`: an exception in
  `onStart` propagates as a logged error + force-stopped session, but `startGame` still returns the session id (the
  session simply ends up `STOPPED`). Check `status()` after starting if that matters to you.
- **The tick starts before `start()`.** `runTick` guards on `status == RUNNING`, so the first few ticks are no-ops. This
  ordering avoids a race where a tick could fire before the session was inserted into `activeSessions`.

### 6.3 The frame router: hardware → `TileEvent`

`EngineFrameRouter` implements `FrameListener` and is the only place in the engine that touches raw bytes:

```java
public void onFrame(Frame frame) {
    if (frame.command() != Command.DATA_IN) return;         // ignore everything else
    byte[] payload = frame.payload();
    if (payload.length == 0) return;
    if (payload.length != width * height) {                 // geometry mismatch → discard + warn
        log.warn("Discarding DATA_IN payload of {} bytes: expected {}x{}={} bytes", ...);
        return;
    }
    Board<Boolean> board = Board.fromWireBytes(payload, width, height, TileCodec.booleanState());
    touchBoardConsumer.accept(board);                        // → GameEngineImpl::routeTouchFrame
}
```

Design notes:

- It decodes into `Board<Boolean>` (**touched / not touched**), not into colours. Input and output use different codecs
  on purpose: `TileCodec.booleanState()` inbound, `ColorTileCodec` outbound.
- It is constructed with the **actual connected board's** dimensions (from `GatewayConnectedEvent`), while each session
  uses its **game's** `requiredWidth/Height`. `routeTouchFrame` reconciles the two by bounds-checking every position
  against the session's own geometry — so an 8×8 game running on a 16×16 board only receives touches in its own top-left
  8×8 region.
- Decode failures are caught (`RuntimeException`) and logged. The reader thread survives.

### 6.4 The colour codec

`ColorTileCodec` is the entire mapping between the application palette and the wire:

```java
TileCodec.of(
        color ->(byte)color.

wireCode(),               // encode

wire  ->TileColor.

fromWireCode(wire &0xFF));  // decode, unknown → OFF
```

`TileColor` is an enum with an explicit `wireCode` (OFF=0, RED=1, GREEN=2, BLUE=3, PINK=4, LIGHT_BLUE=5, YELLOW=6,
WHITE=7). Decoding is total: an unknown byte becomes `OFF` rather than throwing, because a corrupted frame should darken
a tile, not crash a session.

**Swapping the palette** (e.g. for hardware with RGB LEDs) means: add enum constants with new wire codes, or write your
own `TileCodec<YourColor>` and your own session implementation. The engine has no hard-coded colour logic anywhere —
features like `RandomFeature.randomColor()` derive the pool from `TileColor.values()` minus `OFF` minus the exclusions
you pass.

### 6.5 The registry

`DefaultGameRegistry` holds `Map<String, Entry>` where `Entry(descriptor, factory)` is a private record.

Two registration modes:

```java
registry.register(game);                                  // singleton: factory returns the same instance
registry.

register(descriptor, MyGame::new);               // per-session: factory creates a fresh instance
```

`register(Game)` is implemented as `register(game.descriptor(), () -> game)`. **This is a trap worth knowing:** a
singleton `Game` shared by two concurrent sessions will share its *instance fields*. Stateless games (all state in
`ctx.state()` / features) are fine; stateful ones must be registered with a factory. The `WhackAMoleGame` above keeps a
`Position current` field and is therefore **only safe as a factory registration** in a multi-session deployment.

`listAll()` sorts by `displayName`, so the catalogue is stable across restarts and map ordering.

### 6.6 The event bus

`GameEventBusImpl` keeps a `CopyOnWriteArrayList<Subscription>`, where

```java
record Subscription(GameEventListener listener, GameEventType filterType, String filterSessionId) {
}
```

- `subscribe(listener)` → both filters `null` (everything)
- `subscribe(type, listener)` → type filter
- `subscribeSession(sessionId, listener)` → session filter
- `matches()` is a two-line null-check-and-compare; filtering happens at dispatch, not at registration.

`publish()` does **not** call listeners inline. It enqueues a single task on a dedicated single-threaded executor
(`tileboard-eventbus`, daemon):

```java
executor.execute(() ->{
        for(
Subscription sub :subscriptions)
        if(

matches(sub, event))
        try{sub.

listener().

onEvent(event); }
        catch(
RuntimeException e){log.

warn("Event listener threw while handling {}",event.type(),e);}
        });
```

Properties you get for free:

- **Ordering:** all events are delivered in publish order (single thread).
- **Isolation:** a throwing listener cannot break another listener, the tick loop, or the serial reader.
- **Back-pressure:** none, deliberately. An unbounded queue means a slow SSE client can grow it. In practice the tick
  interval bounds the event rate (≈10 events/s/session at 100 ms), so this is fine; if you add high-frequency custom
  events, consider a bounded executor via the `GameEventBusImpl(Executor)` constructor.
- **Unsubscription is a `Runnable`** — `() -> subscriptions.remove(sub)`. Records give identity-based `equals`, and the
  same `Subscription` instance is removed, so double-unsubscribe is harmless.

**Self-unsubscribing cleanup.** `GameEngineImpl.startGame` uses the `Runnable[] unsubscribeRef = new Runnable[1]`
trick to let a subscription remove itself:

```java
Runnable[] ref = new Runnable[1];
ref[0]=eventBus.

subscribe(event ->{
        if(event.

sessionId().

equals(sessionId) &&(event.

type() ==SESSION_FINISHED ||event.

type() ==SESSION_STOPPED)){
        activeSessions.

remove(sessionId);

ref[0].

run();                       // unsubscribe from inside the callback
    }
            });
```

This is the standard Java workaround for "a lambda that needs to reference itself". It is safe here because the
`Runnable` is invoked on the bus thread *after* `ref[0]` was assigned. (The same idiom appears in
`GameEventSseEmitter`.)

### 6.7 `GameState`: the type-erased bag

```java
private final Map<String, Object> store = new HashMap<>();

public synchronized <T> void put(String key, T value)

public synchronized <T> Optional<T> get(String key, Class<T> type)

public synchronized <T> T getOrDefault(String key, Class<T> type, T defaultValue)
```

Why a `HashMap` + `synchronized` instead of `ConcurrentHashMap`? Because the class needs *compound* atomicity
(`snapshot()` must be a consistent copy) and the `Class.cast` check must be atomic with the read. All access goes
through synchronized methods, so the plain map is correct and cheaper than a concurrent one under contention.

`Optional<T> get(key, type)` uses `type.cast(v)`, which throws `ClassCastException` — not `Optional.empty()` — when the
stored value has the wrong type. That is intentional: a key collision between two features is a bug, and it should
surface loudly at the call site rather than silently looking like "absent".

Use well-known, namespaced keys: `ctx.state().put("mole.position", pos)`.

### 6.8 The `AnimationSystem`

The most intricate class in the engine. Structure:

```
play*()  →  cancelCurrent()  →  submit(body)
                                   │
                                   └─ CompletableFuture.runAsync(..., animationExecutor)
                                        runningThread = Thread.currentThread();
                                        cancelRequested.set(false);       // new owner of the thread
                                        try     { body.run(); }
                                        catch   (AnimationCancelledException ignored) { }
                                        finally { runningThread = null; Thread.interrupted(); }

body     →  boardPublisher.accept(board); sleep(ms); ...   // boardPublisher == ctx::publishBoard
sleep(ms)→  if (cancelRequested) throw AnimationCancelledException;
            Thread.sleep(ms)  →  on InterruptedException: re-interrupt + throw AnimationCancelledException
            if (cancelRequested) throw AnimationCancelledException;
```

What that buys you:

- **Pre-emption.** Calling `playWinAnimation()` while a countdown is running cancels the countdown *immediately*
  (flag + interrupt) and queues the win animation on the same single thread. No overlapping paints.
- **Clean interrupts.** The flag is cleared by the *new* task, and the interrupt status is cleared on exit, so a pooled
  thread never starts a task already-interrupted.
- **No stack-trace cost.** `AnimationCancelledException` is constructed with
  `super(null, null, /*suppression*/ false, /*writableStackTrace*/ false)`.
- **Bounded shutdown.** `shutdown()` = `cancelCurrent()` + `shutdownNow()` + `awaitTermination(1s)` (restoring the
  interrupt flag if it was itself interrupted). `GameSessionImpl.finishSession` always calls it — before this, the
  executor leaked for the lifetime of the JVM.

Available animations:

| Group     | Method                                      | Variants                                                                                                                                                                                     |
|-----------|---------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Countdown | `playCountdown()`, `playCountdown(digitMs)` | Auto-selects a 5×3 digit renderer when the board is ≥3 wide and ≥5 tall, otherwise full-board colour flashes (red→yellow→green), then a 3× green start flash                                 |
| Win       | `playWinAnimation(type)`                    | `RADIAL_BURST` (Chebyshev rings, colour per radius, 500 ms hold), `RAINBOW_SWEEP` (two column sweeps), `SPARKLE` (15 cycles of random dots), `FIREWORKS` (3 random centres, expanding rings) |
| Lose      | `playLoseAnimation(type)`                   | `FADE_TO_RED` (progressive random red, then full red), `DESCENDING_CURTAIN` (row by row), `CRUMBLE` (shuffled per-tile decay), `PULSE_RED` (4 red pulses)                                    |
| Standby   | `playStandbyAnimation(type)`                | `BREATHING` (corners → border, 20 cycles, blue/light-blue), `CORNER_PULSE` (rotating corner + 3×3 ring), `WAVE_BORDER` (mod-3 travelling border), `RANDOM_TWINKLE` (50 frames of white dots) |

Every method returns `CompletableFuture<Void>` so a game can chain: `ctx.animations().playCountdown().thenRun(...)`
— but note the continuation runs on the animation thread, which is single-threaded and shared with subsequent
animations. Keep continuations short.

Rendering details worth knowing:

- Digits are 5×3 bitmaps centred with `(height - ph) / 2`, `(width - pw) / 2`; out-of-range cells are skipped, so small
  boards degrade instead of throwing.
- `playRadialBurst` computes `maxRadius` from the centre to the farthest corner (Chebyshev) + 2 so the ring always exits
  the board.
- Animations construct a **new `Board` per frame** and hand it to `publishBoard`, which copies it into the session's
  buffer under the write lock. They never mutate the session buffer directly.

### 6.9 `WaveGenerator` vs `AnimationSystem`

They look similar and are not interchangeable:

|              | `WaveGenerator`                                                 | `AnimationSystem`                                      |
|--------------|-----------------------------------------------------------------|--------------------------------------------------------|
| Threading    | **Runs on the calling thread** (blocking `Thread.sleep`)        | Own single daemon thread, async                        |
| Cancellation | None (only responds to interrupt by restoring the flag)         | Cooperative flag + real interrupt                      |
| Effects      | `sweepDown`, `ripple`, `blink` — composable primitives          | Full choreographed sequences                           |
| Use when     | You are already on a background thread and want a simple effect | You want fire-and-forget from `onTileEvent` / `onTick` |

Calling `ctx.waves().blink(...)` from `onTick` will freeze the tick loop. That is documented behaviour, not a bug.

### 6.10 Exception hierarchy

```
RuntimeException
└── GameEngineException            (message, message+cause)
    ├── GameNotFoundException      ("No game registered with id: 'x'")
    ├── GameSessionException       ("Session x already started")
    └── EngineNotReadyException    (final, no-arg: "no serial gateway is currently connected")
```

`EngineNotReadyException` is the engine-layer mirror of the application's `GatewayNotConnectedException` /
`PortsNotAssignedException`, thrown only by `GameEngineManager.require()`. The message tells the operator exactly what
to do: *"Assign and connect the serial ports first."*

---

## 7. Built-in features reference

All features are reached through `GameContext`. Each session gets its **own instances**, so nothing leaks between
concurrent games.

### 7.1 `scores()` — `ScoreSystem`

```java
int newScore = ctx.scores().add(playerId, 10);
ctx.

scores().

subtract(playerId, 5);
ctx.

scores().

set(playerId, 0);
ctx.

scores().

reset(playerId);  ctx.

scores().

resetAll();

Optional<String> leader = ctx.scores().leader();
Map<String, Integer> all = ctx.scores().allScores();   // LinkedHashMap snapshot, unmodifiable
```

Constructed with the session's player list; unknown ids are created lazily at `0` (so a spectator or a typo'd id never
NPEs). `allScores()` is what lands in `GameResult.scoreByPlayerId`.

### 7.2 `health()` — `HealthSystem`

```java
boolean stillAlive = ctx.health().damage(playerId);       // −1
ctx.

health().

damage(playerId, 3);                         // −n, clamped at 0
ctx.

health().

heal(playerId, 2);                           // clamped at max
ctx.

health().

current(playerId);  ctx.

health().

max(playerId);
ctx.

health().

isAlive(playerId);  ctx.

health().

allDead();
ctx.

health().

resetAll(3);
```

Two constructors: `new HealthSystem(players)` defaults `maxHealth = 3`; `new HealthSystem(players, 5)` sets it. The
`defaultMaxHealth` is **stored** and used by the lazy `getOrCreate` path — an earlier revision dropped it and silently
fell back to a hard-coded `3` for late joiners.

### 7.3 `levels()` — `LevelSystem`

```java
ctx.levels().

currentLevel();               // starts at 1

int next = ctx.levels().advance();
ctx.

levels().

setLevel(5);                  // throws IllegalArgumentException if < 1
ctx.

levels().

setSpeedScaler(lvl ->500-lvl *20);
int tickMs = ctx.levels().currentSpeed();  // default: max(50, 1000 - (lvl-1)*100)
ctx.

levels().

reset();
```

The engine does **not** re-schedule the tick executor when the level changes — `currentSpeed()` is a value your game
reads and uses (e.g. as the delay between moles). This keeps the tick period stable and predictable.

### 7.4 `combos()` — `ComboTracker`

```java
ctx.combos().

setComboTimeout(1500);

int chain = ctx.combos().hit();            // auto-resets if the gap exceeded the timeout
ctx.

combos().

current();  ctx.

combos().

max();

int mult = ctx.combos().multiplier(5);     // 1 + combo/5  → combo 5 = ×2, combo 10 = ×3
ctx.

combos().

reset();
```

Timeout defaults to **2000 ms**. `multiplier(threshold)` guards against `threshold <= 0` with `Math.max(1, …)`.

### 7.5 `timer()` — `GameTimer`

```java
ctx.timer().

start();                                     // called automatically by start()
ctx.

timer().

elapsed();                                   // Duration; frozen after stop()
ctx.

timer().

startCountdown(Duration.ofSeconds(30), ()->ctx.

loseSession());
        ctx.

timer().

remaining();  ctx.

timer().

isExpired();
ctx.

timer().

checkExpiry();                               // ← call this in onTick()
ctx.

timer().

stop();  ctx.

timer().

reset();
```

`checkExpiry()` nulls the callback before running it, so it fires **exactly once** even under concurrent ticks. The
engine does not call it for you — a countdown only expires if your `onTick` checks it.

### 7.6 `touchHistory()` / `touchAnalyzer()`

```java
ctx.touchHistory().

totalTouches();

Optional<TileEvent> last = ctx.touchHistory().last();
Optional<Position> lastP = ctx.touchHistory().lastTouchedPosition();
List<Position> order = ctx.touchHistory().positionOrder();
Set<Position> seen = ctx.touchHistory().distinctPositions();
TouchSequence seq = ctx.touchHistory().sequence();     // immutable snapshot
ctx.

touchHistory().

reset();

ctx.

touchAnalyzer().

averageInterTouchGap();       // Duration
ctx.

touchAnalyzer().

matchesSequence(pattern);     // tail match
ctx.

touchAnalyzer().

allUnique();

Optional<Duration> rt = ctx.touchAnalyzer().lastReactionTime();
```

`TouchHistory` is fed by the engine (`handleTileEvent` records *before* calling `game.onTileEvent`), so your game sees
its own touch already in the history. It is a `CopyOnWriteArrayList` — cheap reads, expensive writes; fine for human
touch rates, wrong for a 1 kHz sensor stream.

`TouchSequence.gapBetween(i)` throws `IndexOutOfBoundsException` outside `[0, size()-1]`; `gaps()` and
`averageGap()` handle the `< 2 touches` case by returning empty / `Duration.ZERO`.

### 7.7 `board()` — `BoardFeature`

```java
List<Position> reds = ctx.board().findByColor(board, TileColor.RED);
List<Position> lit = ctx.board().find(board, c -> c != TileColor.OFF);
ctx.

board().

allMatch(board, c ->c ==TileColor.OFF);
        ctx.

board().

noneMatch(board, c ->c ==TileColor.RED);
long n = ctx.board().countByColor(board, TileColor.GREEN);
ctx.

board().

isValid(pos);
ctx.

board().

manhattanDistance(a, b);
ctx.

board().

chebyshevDistance(a, b);
```

Constructed with the session's `w`/`h`; `allMatch` is implemented as "no tile matches the negated predicate", so it
short-circuits nothing but stays allocation-light.

### 7.8 `neighbors()` — `NeighborFinder`

```java
List<Position> ns = ctx.neighbors().of(pos);            // or of(row, col)
List<Position> region = ctx.neighbors().connectedRegion(start, p -> board.get(p) == TileColor.BLUE);
```

The session default is `Adjacency.FOUR_WAY`. Construct your own for other modes:

```java
NeighborFinder eight = new NeighborFinder(w, h, Adjacency.EIGHT_WAY);
NeighborFinder diag = new NeighborFinder(w, h, Adjacency.DIAGONAL_ONLY);
```

Direction tables are `static final int[][]`, bounds-checked per candidate. `connectedRegion` is an iterative BFS
(`ArrayDeque` + `HashSet`) — no recursion, no stack overflow on large boards. Note the start position is included in the
result even if `passable.test(start)` is false; guard that yourself if it matters.

### 7.9 `patterns()` — `PatternMatcher`

```java
ctx.patterns().

tailMatches(actual, pattern);       // last N touches, in order
ctx.

patterns().

exactMatch(actual, pattern);
ctx.

patterns().

containsSequence(actual, pattern);  // contiguous sub-sequence
ctx.

patterns().

cyclicMatch(actual, pattern);       // rotation-invariant, for circular boards
```

Stateless and pure — an empty pattern matches everything (`true`) by definition.

### 7.10 `random()` — `RandomFeature`

```java
Position p = ctx.random().randomPosition();
List<Position> ps = ctx.random().randomPositions(5);   // distinct, shuffled, ≤ w*h
TileColor c = ctx.random().randomColor(TileColor.RED); // excludes OFF and whatever you list
T item = ctx.random().pick(list);                 // NoSuchElementException on empty
boolean yes = ctx.random().chance(0.3);
ctx.

random().

reseed(42L);
```

The default session instance uses `new Random()` (non-reproducible). For deterministic replays or tests, construct
`new RandomFeature(w, h, seed)` and put it in `GameState`, or `reseed()` at session start. `randomColor()` will throw if
you exclude every colour — don't do that.

### 7.11 `memory()` — `MemoryFeature`

```java
ctx.memory().

setTarget(List.of(p1, p2, p3));   // copies + clears player input
        ctx.

memory().

addInput(pos);
ctx.

memory().

isCorrectSoFar();   // prefix match; false (not exception) on over-input
ctx.

memory().

isComplete();       // inputLength >= targetLength
ctx.

memory().

isFullyCorrect();
ctx.

memory().

targetLength();  ctx.

memory().

inputLength();
ctx.

memory().

target();  ctx.

memory().

resetInput();
```

`targetSequence` is `volatile` and `playerInput` is a `synchronizedList`; every multi-step read takes a
`List.copyOf` first, so a concurrent `addInput` cannot produce a torn comparison.

### 7.12 `reactionSpeed()` — `ReactionSpeedTracker`

```java
ctx.reactionSpeed().

stimulus();                       // mark "the tile just lit up"
// ... the engine calls record(event) automatically for the next touch ...
ctx.

reactionSpeed().

lastReaction();                   // Duration
ctx.

reactionSpeed().

bestReaction();

OptionalDouble avgMs = ctx.reactionSpeed().averageReactionMillis();
ctx.

reactionSpeed().

reactionCount();
ctx.

reactionSpeed().

reset();
```

`record` uses `getAndSet(null)`, so one stimulus yields exactly one measurement. Negative durations (clock skew,
out-of-order frames) are clamped to `0`. `bestReaction()` returns `Duration.ZERO` when nothing has been measured — check
`reactionCount()` before interpreting it.

### 7.13 `graph()` — `GraphFeature`

```java
List<Position> path = ctx.graph().shortestPath(from, to, passable);   // empty if unreachable
List<List<Position>> comps = ctx.graph().connectedComponents(passable);
```

BFS over 4-neighbours with a `parent` map, path reconstructed head-first. `shortestPath` returns `List.of()` if either
endpoint is impassable. Ideal for maze generation validation, "is the board still solvable?" checks and flood-fill
puzzles.

### 7.14 `waves()` — `WaveGenerator`

```java
ctx.waves().

sweepDown(TileColor.BLUE, 80);
ctx.

waves().

ripple(new Position(3, 3),TileColor.PINK,100);
        ctx.

waves().

blink(TileColor.WHITE, TileColor.OFF, 3,200);
```

Blocking; each step publishes a `board.copy()`. Run from a game-managed background thread or from an animation
continuation — never from `onTick`.

### 7.15 `animations()` — see [§6.8](#68-the-animationsystem)

### 7.16 `state()` — `GameState`

Your scratchpad. See [§6.7](#67-gamestate-the-type-erased-bag).

### 7.17 `eventBus()` — see [§8](#8-event-bus--sse-streaming)

---

## 8. Event bus & SSE streaming

### 8.1 Event types

| `GameEventType`                                                                                       | Published by                 | Payload                            |
|-------------------------------------------------------------------------------------------------------|------------------------------|------------------------------------|
| `SESSION_STARTED`                                                                                     | `GameSessionImpl.start()`    | `{}`                               |
| `TICK`                                                                                                | `runTick()`                  | `{scores, level, status, elapsed}` |
| `BOARD_UPDATED`                                                                                       | `publishBoard()`             | `{boardSnapshot: "sent"}`          |
| `SESSION_FINISHED`                                                                                    | `finishSession(FINISHED, …)` | `{scores, level, status, elapsed}` |
| `SESSION_STOPPED`                                                                                     | `finishSession(STOPPED, …)`  | same                               |
| `TILE_TOUCHED`, `SCORE_CHANGED`, `LEVEL_UP`, `HEALTH_CHANGED`, `COMBO_HIT`, `TIMER_EXPIRED`, `CUSTOM` | **your game**                | whatever you pass                  |

The engine publishes lifecycle/board/tick events; the *semantic* events (score changed, level up, combo hit) are yours
to publish, because only the game knows what they mean:

```java
ctx.eventBus().

publish(GameEvent.of(
        GameEventType.SCORE_CHANGED, ctx.sessionId(),ctx.

gameId(),
        Map.

of("playerId",id, "score",ctx.scores().

get(id), "delta",10)));
```

`GameEvent.of(...)` fills in a UUID id and `Instant.now()`. The record's compact constructor copies the payload map, so
you may reuse a mutable builder map afterwards.

### 8.2 SSE bridge

`GameEventSseEmitter` is a static factory with three entry points:

```java
GameEventSseEmitter.forSession(sessionId, bus);   // one session
GameEventSseEmitter.

forEventType(type, bus);      // one event type, all sessions
GameEventSseEmitter.

global(bus);                  // everything
```

Each returns an `SseEmitter(Long.MAX_VALUE)` and:

1. subscribes to the bus with the appropriate filter;
2. stores the unsubscribe `Runnable` in a one-element array so the *listener itself* can unsubscribe on I/O failure;
3. registers `onCompletion`, `onTimeout` and `onError` handlers that all unsubscribe.

That triple registration is the leak fix: without `onCompletion`/`onTimeout`, a client that simply closes the tab would
leave its subscription (and its emitter) alive for the lifetime of the JVM.

On the wire, each event is sent with an SSE `id` (the event UUID, so browsers can resume with `Last-Event-ID`), a
`name` (the raw `GameEventType`), and a JSON `data` body of type `SseGameEvent`:

```json
{
  "sessionId": "5f0c…",
  "gameId": "whack-a-mole",
  "type": "SCORE_UPDATE",
  "data": {
    "playerId": "p1",
    "score": 42
  },
  "timestamp": "2026-09-18T12:34:56.789Z"
}
```

`GameEventType` → `SseGameEventType` mapping:

| Engine event                                                           | SSE type            |
|------------------------------------------------------------------------|---------------------|
| `TICK`                                                                 | `TICK`              |
| `BOARD_UPDATED`                                                        | `BOARD_UPDATE`      |
| `SCORE_CHANGED`                                                        | `SCORE_UPDATE`      |
| `SESSION_STARTED` / `SESSION_FINISHED` / `SESSION_STOPPED`             | `SESSION_LIFECYCLE` |
| everything else (`TILE_TOUCHED`, `LEVEL_UP`, `COMBO_HIT`, `CUSTOM`, …) | `GAME_STATE`        |

Serialisation uses a **static, shared `ObjectMapper`** with `JavaTimeModule` registered and
`WRITE_DATES_AS_TIMESTAMPS` disabled — hence ISO-8601 timestamps, and hence the `jackson-datatype-jsr310`
dependency. ObjectMapper is thread-safe after configuration, so one instance serves all emitters.

Client side:

```js
const es = new EventSource('/api/v1/games/sessions/' + sessionId + '/events');
es.addEventListener('SCORE_UPDATE', e => renderScores(JSON.parse(e.data).data));
es.addEventListener('SESSION_LIFECYCLE', e => onEnd(JSON.parse(e.data)));
es.addEventListener('TICK', e => renderTimer(JSON.parse(e.data).data.elapsed));
```

---

## 9. Threading model & concurrency contract

Four threads can touch your game. Know which is which.

| Thread                  | Name                         | Runs                                            | Guarantees                                                                             |
|-------------------------|------------------------------|-------------------------------------------------|----------------------------------------------------------------------------------------|
| Caller of `startGame`   | yours                        | `onStart`                                       | Runs once, before any tick or touch is delivered                                       |
| Gateway callback thread | the protocol library's       | `onTileEvent`, `onError`                        | Never concurrent with itself for one session *if* the gateway serialises its callbacks |
| Tick thread             | `tileboard-tick-<sessionId>` | `onTick`, `onError`                             | `scheduleAtFixedRate` on a single-thread executor → never concurrent with itself       |
| Animation thread        | `tileboard-animation`        | animation bodies + your `thenRun` continuations | Single-thread executor → animations never overlap                                      |
| Event bus thread        | `tileboard-eventbus`         | your listeners (incl. SSE writes)               | Single-thread executor → strictly ordered                                              |

⚠️ **`onTileEvent` and `onTick` can run concurrently with each other.** They live on different threads. Therefore:

- Everything the *engine* gives you (`scores()`, `health()`, `combos()`, `levels()`, `timer()`, `touchHistory()`,
  `memory()`, `state()`, `boardBuffer` writes) is thread-safe by construction.
- Anything **you** keep in a field of your `Game` class is *not*. Either
    - keep it in `ctx.state()` (synchronized), or
    - use atomics, or
    - accept that a stale read is harmless for your use case (a visual-only `Position` field usually is), or
    - register the game with a **factory** so each session owns its own instance and only the tick/touch race remains.

### Memory-visibility summary

| Mechanism                                                   | Where                                                   |
|-------------------------------------------------------------|---------------------------------------------------------|
| `AtomicReference<GameStatus>` + CAS                         | session lifecycle transitions, idempotent termination   |
| `volatile GameResult result`                                | published after the CAS, read via `Optional.ofNullable` |
| `synchronized (boardWriteLock)`                             | every hardware write, buffer + wire as one unit         |
| `ConcurrentHashMap`                                         | `activeSessions`, registry entries, scores, health      |
| `AtomicInteger` / `AtomicLong` / `LongAdder`                | score, health, combo, level, reaction stats             |
| `CopyOnWriteArrayList`                                      | touch history, event-bus subscriptions                  |
| `synchronized` methods over a plain `HashMap`               | `GameState`                                             |
| `volatile` + explicit `Thread.interrupt()`                  | `AnimationSystem` cancellation                          |
| `List.copyOf` / `Map.copyOf` in record compact constructors | every immutable value type                              |

### The one lock you must not hold

`publishBoard`, `setTile` and `fillBoard` hold `boardWriteLock` while calling `gateway.sendBoard(...)`. If your
transport blocks (a full UART buffer, a stuck USB device), the tick thread and the animation thread will queue up behind
it. Never call a blocking transport operation from inside a game callback that also holds another lock, and never add
your own `synchronized` block around a `ctx.setTile(...)` call — you would create a lock-ordering hazard against the
engine's own lock.

---

## 10. Tutorials

### 10.1 Tutorial A — Two-player "Territory" (teams, zones, win condition)

Board split in half; each player colours tiles on their side; first to own 20 tiles wins.

```java
public final class TerritoryGame implements Game {

    private static final int TILES_TO_WIN = 20;

    @Override
    public GameDescriptor descriptor() {
        return GameDescriptor.builder("territory", "Territory")
                .category("STRATEGY").description("Claim tiles on your half of the board.")
                .boardSize(8, 8).players(2, 2).build();
    }

    @Override
    public void onStart(GameContext ctx) {
        // Left half = BLUE (player 0), right half = RED (player 1)
        Board<TileColor> b = ctx.newBoard();
        for (int r = 0; r < ctx.boardHeight(); r++)
            for (int c = 0; c < ctx.boardWidth(); c++)
                b.set(r, c, c < ctx.boardWidth() / 2 ? TileColor.BLUE : TileColor.RED);
        ctx.publishBoard(b);

        ctx.state().put("territory.zoneOf", Map.of(
                ctx.players().get(0).id(), TileColor.BLUE,
                ctx.players().get(1).id(), TileColor.RED));
        ctx.eventBus().publish(GameEvent.of(GameEventType.CUSTOM, ctx.sessionId(), ctx.gameId(),
                Map.of("mode", "territory", "tilesToWin", TILES_TO_WIN)));
    }

    @Override
    public void onTileEvent(GameContext ctx, TileEvent event) {
        Player toucher = closestPlayer(ctx, event.position());
        TileColor mine = ctx.state()
                .get("territory.zoneOf", Map.class)
                .map(m -> (TileColor) m.get(toucher.id()))
                .orElse(TileColor.OFF);

        ctx.setTile(event.position().row(), event.position().col(), mine);
        ctx.scores().add(toucher.id(), 1);
        ctx.combos().hit();

        ctx.eventBus().publish(GameEvent.of(GameEventType.TILE_TOUCHED, ctx.sessionId(), ctx.gameId(),
                Map.of("playerId", toucher.id(),
                        "row", event.position().row(),
                        "col", event.position().col(),
                        "score", ctx.scores().get(toucher.id()))));

        if (ctx.scores().get(toucher.id()) >= TILES_TO_WIN) {
            ctx.animations().playWinAnimation(WinAnimationType.RADIAL_BURST);
            ctx.winSession(List.of(toucher));
        }
    }

    /** Nearest by column distance; ties go to player 0. */
    private Player closestPlayer(GameContext ctx, Position p) {
        int mid = ctx.boardWidth() / 2;
        return p.col() < mid ? ctx.players().get(0) : ctx.players().get(1);
    }
}
```

Concepts demonstrated: `newBoard()` + `publishBoard()`, storing structured state in `GameState`, per-player score,
publishing a `CUSTOM` event with a typed payload, ending with a specific winner.

### 10.2 Tutorial B — "Simon Says" (sequences, memory, tick-driven playback)

The interesting problem here is *playback*: the sequence must be shown one tile at a time with gaps. The naive solution
spawns a thread and calls `Thread.sleep` — which
violates [§3.4](#34-one-session--one-thread-of-control--one-animation-thread). The idiomatic engine solution is a
**tick-driven state machine**: all state lives in `GameState`, and `onTick`
advances it. No threads, no locks, trivially testable.

```java
public final class SimonSaysGame implements Game {

    private static final String SEQ = "simon.sequence";     // List<Position>
    private static final String STEP = "simon.step";         // Integer, see the three states below
    private static final String TICKS = "simon.ticksInStep";  // Integer: sub-tick counter
    private static final int PLAY_NEXT = -1;               // grow the sequence and play it back
    private static final int WAIT = -2;               // playback done, waiting for the player
    private static final int ON_TICKS = 4;                // 4 × 100 ms lit
    private static final int OFF_TICKS = 2;                // 2 × 100 ms dark
    // STEP >= 0 means "currently playing back position STEP"

    @Override
    public GameDescriptor descriptor() {
        return GameDescriptor.builder("simon", "Simon Says")
                .category("MEMORY").description("Repeat the growing sequence.")
                .boardSize(4, 4).players(1, 1).build();
    }

    @Override
    public void onStart(GameContext ctx) {
        ctx.state().put(SEQ, List.of());
        ctx.state().put(STEP, PLAY_NEXT);
        ctx.state().put(TICKS, 0);
        ctx.animations().playCountdown(600);      // async — onStart returns immediately
    }

    @Override
    public void onTick(GameContext ctx) {
        if (!ctx.state().containsKey(SEQ)) return;   // defensive: onStart always sets it
        int step = step(ctx);
        if (step == WAIT) return;               // the player's turn — nothing to do
        if (step == PLAY_NEXT) {
            nextRound(ctx);
            return;
        }
        advancePlayback(ctx);
    }

    private void nextRound(GameContext ctx) {
        @SuppressWarnings("unchecked")
        List<Position> seq = new ArrayList<>((List<Position>) ctx.state().get(SEQ, List.class).orElseThrow());
        seq.add(ctx.random().randomPosition());
        ctx.state().put(SEQ, seq);
        ctx.memory().setTarget(seq);
        ctx.memory().resetInput();
        ctx.levels().setLevel(seq.size());
        ctx.state().put(STEP, 0);
        ctx.state().put(TICKS, 0);
        light(ctx, seq.get(0), true);
    }

    private void advancePlayback(GameContext ctx) {
        @SuppressWarnings("unchecked")
        List<Position> seq = (List<Position>) ctx.state().get(SEQ, List.class).orElseThrow();
        int ticks = ctx.state().get(TICKS, Integer.class).orElse(0) + 1;
        ctx.state().put(TICKS, ticks);

        boolean lit = ticks <= ON_TICKS;
        int limit = lit ? ON_TICKS : ON_TICKS + OFF_TICKS;
        light(ctx, seq.get(step(ctx)), lit);

        if (ticks < limit) return;                // stay in the current sub-phase

        int next = step(ctx) + 1;                 // sub-phase finished → next position
        ctx.state().put(TICKS, 0);
        if (next < seq.size()) {
            ctx.state().put(STEP, next);
        } else {
            ctx.state().put(STEP, WAIT);          // playback done: it's the player's turn
            ctx.reactionSpeed().stimulus();       // measure the first reply
        }
    }

    private void light(GameContext ctx, Position p, boolean on) {
        ctx.setTile(p.row(), p.col(), on ? TileColor.GREEN : TileColor.OFF);
    }

    private int step(GameContext ctx) {
        return ctx.state().get(STEP, Integer.class).orElse(PLAY_NEXT);
    }

    @Override
    public void onTileEvent(GameContext ctx, TileEvent event) {
        if (step(ctx) != WAIT) return;            // ignore everything outside the player's turn

        ctx.memory().addInput(event.position());
        ctx.setTile(event.position().row(), event.position().col(), TileColor.WHITE);

        if (!ctx.memory().isCorrectSoFar()) {     // over-input returns false too — no crash
            ctx.animations().playLoseAnimation(AnimationSystem.LoseAnimationType.FADE_TO_RED);
            ctx.loseSession();
            return;
        }
        if (!ctx.memory().isComplete()) return;

        Player p = ctx.players().get(0);
        ctx.scores().add(p.id(), 10 * ctx.levels().currentLevel());
        ctx.eventBus().publish(GameEvent.of(GameEventType.LEVEL_UP, ctx.sessionId(), ctx.gameId(),
                Map.of("level", ctx.levels().currentLevel(), "score", ctx.scores().get(p.id()))));

        if (ctx.levels().currentLevel() >= 10) {
            ctx.animations().playWinAnimation(AnimationSystem.WinAnimationType.RADIAL_BURST);
            ctx.winSession(List.of(p));
            return;
        }
        // Round cleared: replay the same sequence immediately (it grows on the NEXT round).
        ctx.memory().resetInput();
        ctx.state().put(STEP, 0);
        ctx.state().put(TICKS, 0);
    }
}
```

Concepts demonstrated: `MemoryFeature` as the correctness oracle, `GameState` as the state machine's memory,
`LevelSystem` doubling as round counter, `ReactionSpeedTracker.stimulus()` at the right moment, and
`AnimationSystem` for the parts that genuinely are fire-and-forget.

> **When to use a thread instead.** If an effect must keep running while the game keeps responding to touches at
> full rate, use `ctx.animations()` (its own thread, cancellable) rather than a tick machine. The rule from
> [§3.4](#34-one-session--one-thread-of-control--one-animation-thread) still holds: *never create the thread
> yourself* — extend `AnimationSystem` or drive it from ticks.

### 10.3 Tutorial C — Driving the engine from a REST API + live dashboard

Backend (see [§5.3](#53-a-complete-controller) for the full controller). The dashboard only needs the SSE endpoint:

```html
<!doctype html>
<div id="log"></div>
<script>
    const sid = new URLSearchParams(location.search).get('session');
    const es = new EventSource(`/api/v1/games/sessions/${sid}/events`);
    for (const type of ['SESSION_LIFECYCLE', 'SCORE_UPDATE', 'BOARD_UPDATE', 'TICK', 'GAME_STATE']) {
        es.addEventListener(type, e => {
            const evt = JSON.parse(e.data);
            document.getElementById('log').insertAdjacentHTML('afterbegin',
                    `<div><b>${evt.type}</b> ${evt.timestamp} <code>${JSON.stringify(evt.data)}</code></div>`);
        });
    }
    es.onerror = () => document.getElementById('log').insertAdjacentHTML('afterbegin', '<i>stream closed</i>');
</script>
```

Because the SSE `id` is the engine event UUID, a reconnecting browser sends `Last-Event-ID` and your controller can
replay from a persisted log if you keep one.

### 10.4 Tutorial D — Unit-testing a game without hardware

The engine has no Spring dependency in its core, so a game test needs only a fake `GameContext`:

```java
class WhackAMoleGameTest {

    private FakeContext ctx;      // implements GameContext, records every call
    private WhackAMoleGame game = new WhackAMoleGame();

    @BeforeEach
    void setup() {
        ctx = new FakeContext(game.descriptor(), List.of(Player.solo("Alice")));
        game.onStart(ctx);
    }

    @Test
    void hittingTheMoleScores() {
        Position mole = ctx.lastLitTile();               // read from the fake's board log
        game.onTileEvent(ctx, TileEvent.touch(mole, ctx.sessionId()));
        assertEquals(10, ctx.scores().get("Alice"));
    }

    @Test
    void missingTheMoleCostsHealth() {
        Position wrong = ctx.firstUnlitTile();
        game.onTileEvent(ctx, TileEvent.touch(wrong, ctx.sessionId()));
        assertEquals(2, ctx.health().current("Alice"));  // default max 3
    }

    @Test
    void threeMissesEndsTheSession() {
        for (int i = 0; i < 3; i++)
            game.onTileEvent(ctx, TileEvent.touch(ctx.firstUnlitTile(), ctx.sessionId()));
        assertEquals(GameStatus.FINISHED, ctx.status());
    }
}
```

No gateway is needed at all: `FakeContext` implements `GameContext` and simply records the `setTile` /
`publishBoard` / `fillBoard` calls instead of writing to hardware. It can reuse the **real** feature classes
(`new ScoreSystem(players)`, `new HealthSystem(players)`, …) because they have no dependencies of their own — you only
fake the board output and the session-control methods. That is the practical payoff
of [§3.1](#31-framework-free-core-spring-as-a-thin-shell) and
[§3.2](#32-the-gamecontext-is-the-whole-api-surface-a-game-author-sees).

To test the *engine* itself, use `GameEventBusImpl(Runnable::run)` — the second constructor takes an `Executor`, and a
direct executor makes event assertions synchronous instead of racy:

```java
GameEventBus bus = new GameEventBusImpl(Runnable::run);   // inline dispatch in tests
```

---

## 11. Configuration reference

| Property                         | Type       | Default | Effect                                                                                                                                                              |
|----------------------------------|------------|---------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `tileboard.engine.tick-interval` | `Duration` | `100ms` | Period of `onTick()` for every running session. `0` disables ticking entirely (no `TICK` events, `GameTimer.checkExpiry()` never runs unless you call it yourself). |

That is the complete list. Board geometry comes from `GatewayConnectedEvent`; game geometry comes from each
`GameDescriptor`.

Per-session knobs you set in code instead:

```java
ctx.combos().

setComboTimeout(1500);
ctx.

levels().

setSpeedScaler(lvl ->800-lvl *50);
        new

HealthSystem(players, 5);                     // custom max health
new

NeighborFinder(w, h, Adjacency.EIGHT_WAY);    // custom adjacency
new

RandomFeature(w, h, 1234L);                   // deterministic RNG
```

---

## 12. Extending the engine

### Add a feature available to every game

1. Create `com.tileboard.engine.feature.MyFeature` — `final`, thread-safe, no Spring imports, no dependency on
   `GameSessionImpl`.
2. Add `MyFeature myFeature();` to `GameContext` (grouped under the "Built-in features" banner).
3. Add the field + construction to `GameSessionImpl`'s constructor and the one-line accessor override.
4. If it reacts to touches, feed it from `handleTileEvent` (like `touchHistory` / `reactionSpeed`).
5. If it should shut down, call it from `finishSession`.
6. Add it to the table in [§2](#2-feature-matrix) and a section in [§7](#7-built-in-features-reference).

Do **not** add a parameter to `Game` or `GameFactory` — that breaks every existing game.

### Add an event type

1. Add the constant to `GameEventType` (append at the end; the SSE mapper's `switch` has a `default` arm, so nothing
   breaks).
2. Decide its `SseGameEventType` mapping in `GameEventSseEmitter.toDto` — or let it fall through to `GAME_STATE`.
3. Publish it with `GameEvent.of(type, sessionId, gameId, payload)`. Payload values must be Jackson-serialisable.

### Swap the transport

Implement/obtain a `TileGatewayClient` for your transport (TCP, MQTT, a simulator) and publish
`GatewayConnectedEvent` with it. Nothing in the engine changes. For a **software simulator**, this is the fastest path
to hardware-free development:

```java
TileGatewayClient sim = new InMemoryGatewayClient(8, 8);   // your code
publisher.

publishEvent(new GatewayConnectedEvent(sim, 8,8));
        sim.

injectTouch(new Position(3, 4));                       // → TileEvent in every session
```

### Replace a default bean

Every auto-configured bean is `@ConditionalOnMissingBean`:

```java

@Bean
GameEventBus gameEventBus() {
    return new GameEventBusImpl(myBoundedExecutor);          // e.g. back-pressure
}

@Bean
GameRegistry gameRegistry() {
    GameRegistry r = new DefaultGameRegistry();
    r.register(new WhackAMoleGame().descriptor(), WhackAMoleGame::new);   // factory registration
    return r;
}
```

---

## 13. Build, dependencies & known gotchas

### 13.1 Requirements

- Java **17** (`maven-compiler-plugin` with `<release>17</release>`)
- Maven 3.8+
- `com.tileboard:tileboard-serial-protocol:1.0.0` available in your repository

```bash
mvn clean verify          # compile + run JUnit 5 tests (slf4j-simple as the test logger)
mvn dependency:tree       # check what the optional Spring layer pulls in
```

### 13.2 Dependency map

| Dependency                            | Scope                 | Used by                                                    | Required at runtime?            |
|---------------------------------------|-----------------------|------------------------------------------------------------|---------------------------------|
| `tileboard-serial-protocol`           | compile               | everything                                                 | ✅ yes                          |
| `slf4j-api`                           | compile               | logging across all packages                                | ✅ yes (bring your own binding) |
| `jackson-databind`                    | compile               | SSE payload serialisation                                  | ✅ for `.sse`                   |
| `jackson-datatype-jsr310`             | compile               | ISO-8601 `Instant` in SSE payloads                         | ✅ for `.sse`                   |
| `spring-boot-autoconfigure`           | compile, **optional** | `.spring` auto-config                                      | only if you use Spring Boot     |
| `spring-boot-configuration-processor` | compile, **optional** | `application.yml` metadata for `TileboardEngineProperties` | no (build-time only)            |
| `spring-web`                          | compile, **optional** | `SseEmitter`                                               | only for SSE                    |
| `spring-webmvc`                       | **compile** ⚠️        | servlet MVC integration                                    | only for SSE                    |
| `junit-jupiter`                       | test                  | unit tests                                                 | no                              |
| `slf4j-simple`                        | test                  | log output during tests                                    | no                              |

### 13.3 Two POM issues to fix before your first build

1. **`jackson-datatype-jsr310:2.22.1` does not exist on Maven Central** (the 2.x line tops out at 2.21.x, and Jackson 3
   moved the artifact to the `tools.jackson.datatype` group). Pin it to the same version as
   `jackson-databind` via the existing `${jackson.version}` property:

   ```xml
   <dependency>
       <groupId>com.fasterxml.jackson.datatype</groupId>
       <artifactId>jackson-datatype-jsr310</artifactId>
       <version>${jackson.version}</version>
   </dependency>
   ```

   Mixing Jackson minor versions across `databind` and its modules is a classic source of
   `NoSuchMethodError` at runtime even when the build succeeds.

2. **`spring-webmvc` is declared with `<scope>compile</scope>`**, which contradicts the "Spring is optional" design
   ([§3.1](#31-framework-free-core-spring-as-a-thin-shell)) — every consumer, including non-Spring ones, would inherit
   Spring MVC transitively. Mark it optional (and align its version with `spring-web`, currently
   `6.1.11` vs `6.1.13`):

   ```xml
   <dependency>
       <groupId>org.springframework</groupId>
       <artifactId>spring-webmvc</artifactId>
       <version>${spring.framework.version}</version>
       <optional>true</optional>
   </dependency>
   ```

   The same applies to `jackson-datatype-jsr310` if you want the SSE layer to be genuinely opt-in.

### 13.4 Known limitations & gotchas

| #  | Behaviour                                                                                        | Why / what to do                                                                                                                                                                                                                                                                                                                                                                  |
|----|--------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 1  | **One thread pair per session.** Each session creates a tick executor and an animation executor. | Fine for a handful of concurrent boards; for hundreds, share a `ScheduledExecutorService` across sessions.                                                                                                                                                                                                                                                                        |
| 2  | **`TouchHistory` is unbounded.**                                                                 | A 10-minute session at 10 touches/s ≈ 6 000 entries. Call `ctx.touchHistory().reset()` between rounds, or add a bounded variant.                                                                                                                                                                                                                                                  |
| 3  | **`onTick` and `onTileEvent` can overlap.**                                                      | See [§9](#9-threading-model--concurrency-contract). Keep game fields atomic or in `GameState`.                                                                                                                                                                                                                                                                                    |
| 4  | **Singleton game registration shares state across sessions.**                                    | `registry.register(game)` stores `() -> game`. Use `registry.register(descriptor, MyGame::new)` for stateful games.                                                                                                                                                                                                                                                               |
| 5  | **`onStart` runs on the HTTP/caller thread.**                                                    | Keep it fast (< a few ms). Long intro sequences belong in `animations().playCountdown()`, which is async.                                                                                                                                                                                                                                                                         |
| 6  | **`WaveGenerator` blocks the calling thread.**                                                   | Never call it from `onTick`.                                                                                                                                                                                                                                                                                                                                                      |
| 7  | **The engine does not call `GameTimer.checkExpiry()`.**                                          | Your `onTick` must, or countdowns never fire.                                                                                                                                                                                                                                                                                                                                     |
| 8  | **`setTile` / `fillBoard` do not emit `BOARD_UPDATED`.**                                         | Only `publishBoard` does. Publish a `CUSTOM` event yourself if a dashboard needs per-tile updates.                                                                                                                                                                                                                                                                                |
| 9  | **Session cleanup is asynchronous.** `activeSessions` is pruned by an event-bus subscriber.      | A session that just finished can still appear in `activeSessions()` for a few milliseconds; its `status()` is already terminal, so filter on that. In the rare case where `onStart` throws, `forceStop()` publishes `SESSION_STOPPED` *before* the cleanup subscription is registered — the session then stays in the map with status `STOPPED`. Prune on status if this matters. |
| 10 | **`publishBoard` sends the board you passed, but copies it into the session buffer.**            | So `ctx.setTile(...)` afterwards is consistent with what the hardware last received. Prefer one style per game to avoid confusion.                                                                                                                                                                                                                                                |
| 11 | **Geometry mismatch is silently (warned) dropped.**                                              | If `DATA_IN` payloads are discarded, check that `GatewayConnectedEvent(w, h)` matches the physical board, and that each `GameDescriptor.boardSize(w, h)` is ≤ the board.                                                                                                                                                                                                          |
| 12 | **`RandomFeature` uses `java.util.Random`.**                                                     | Not cryptographic, and `chance()`/`pick()` are individually atomic but not jointly so. Fine for games; do not use for anything security-relevant.                                                                                                                                                                                                                                 |
| 13 | **No PAUSED transition is implemented by the engine.**                                           | `GameStatus.PAUSED` exists and `finishSession` accepts it, but nothing sets it. Implement pause in your game via a `GameState` flag checked in `onTick`/`onTileEvent`.                                                                                                                                                                                                            |
| 14 | **`GameEventSseEmitter` has no heartbeat.**                                                      | Proxies with idle timeouts may drop a silent stream. Schedule a periodic `CUSTOM` event, or a comment ping, if you deploy behind one.                                                                                                                                                                                                                                             |

---

## 14. API cheat sheet

```java
// ── Registration ────────────────────────────────────────────────────────
GameRegistry registry = new DefaultGameRegistry();
registry.

register(new MyGame());                       // singleton
        registry.

register(descriptor, MyGame::new);            // per-session
registry.

find("id"); registry.

listAll(); registry.

isRegistered("id"); registry.

instantiate("id");

// ── Engine ──────────────────────────────────────────────────────────────
GameEngine engine = new GameEngineImpl(registry, gateway, eventBus, Duration.ofMillis(100), 8, 8);
String sid = engine.startGame("id", List.of(Player.solo("Alice")));
engine.

activeSession(sid);  engine.

activeSessions();  engine.

stopGame(sid);  engine.

registry();

// ── Spring ──────────────────────────────────────────────────────────────
publisher.

publishEvent(new GatewayConnectedEvent(client, 8,8));
        publisher.

publishEvent(new GatewayDisconnectedEvent());
GameEngine e = engineManager.require();                // throws EngineNotReadyException
Optional<GameEngine> maybe = engineManager.current();

// ── Game authoring ──────────────────────────────────────────────────────
class MyGame implements Game {
    public GameDescriptor descriptor() {
        return GameDescriptor.builder("id", "Name")
                .category("ARCADE").description("…").boardSize(8, 8).players(1, 4).build();
    }

    public void onStart(GameContext ctx) { …}   // required

    public void onTileEvent(GameContext ctx, TileEvent event) { …}   // required

    public void onTick(GameContext ctx) { …}   // default no-op

    public void onStop(GameContext ctx, GameResult result) { …}   // default no-op

    public void onError(GameContext ctx, Throwable error) {
        ctx.stopSession();
    }  // default
}

// ── Board output ────────────────────────────────────────────────────────
Board<TileColor> b = ctx.newBoard(); b.

set(r, c, TileColor.RED); ctx.

publishBoard(b);
ctx.

setTile(r, c, TileColor.GREEN);  ctx.

fillBoard(TileColor.OFF);

// ── Input ───────────────────────────────────────────────────────────────
event.

position(); event.

type(); event.

occurredAt(); event.

sessionId();
TileEvent.

touch(pos, sid);  TileEvent.

release(pos, sid);

// ── Ending a session ────────────────────────────────────────────────────
ctx.

winSession(List.of(player));ctx.

loseSession();  ctx.

stopSession();
ctx.

status();                     session.

result();   // Optional<GameResult>

// ── Events & SSE ────────────────────────────────────────────────────────
ctx.

eventBus().

publish(GameEvent.of(GameEventType.CUSTOM, sid, gid, Map.of("k", 1)));
Runnable off = bus.subscribe(listener);
Runnable off2 = bus.subscribeSession(sid, listener);
Runnable off3 = bus.subscribe(GameEventType.TICK, listener);
SseEmitter em = GameEventSseEmitter.forSession(sid, bus);
SseEmitter em2 = GameEventSseEmitter.global(bus);

// ── Values ──────────────────────────────────────────────────────────────
Player.

of("Alice",PlayerRole.PLAYER_ONE);  Player.

solo("Alice");
new

Team("Red",List.of(p1, p2));

GameResult(sid, gid, status, winners, scores, duration, finishedAt);

TouchSequence(positions, timestamps).

gaps(); .

averageGap(); .

gapBetween(i);
```

---

## Appendix A — Package index

| Package                                 | Contents                                                                                                                                                                                                                                                   | Spring? |
|-----------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------|
| `com.tileboard.engine.core`             | `Game`, `GameLifecycle`, `GameDescriptor`, `GameFactory`, `GameRegistry`, `DefaultGameRegistry`, `GameEngine`, `GameEngineImpl`, `GameSession`, `GameSessionImpl`, `GameContext`, `GameState`, `GameStatus`, `GameResult`                                  | ❌      |
| `com.tileboard.engine.feature`          | `AnimationSystem`, `ScoreSystem`, `HealthSystem`, `LevelSystem`, `ComboTracker`, `GameTimer`, `TouchHistory`, `TouchAnalyzer`, `BoardFeature`, `PatternMatcher`, `RandomFeature`, `WaveGenerator`, `MemoryFeature`, `ReactionSpeedTracker`, `GraphFeature` | ❌      |
| `com.tileboard.engine.feature.neighbor` | `NeighborFinder`, `Adjacency`                                                                                                                                                                                                                              | ❌      |
| `com.tileboard.engine.event`            | `GameEvent`, `GameEventType`, `GameEventBus`, `GameEventBusImpl`, `GameEventListener`                                                                                                                                                                      | ❌      |
| `com.tileboard.engine.model`            | `Player`, `PlayerRole`, `Team`, `TileColor`, `TileEvent`, `TileEventType`, `TouchSequence`                                                                                                                                                                 | ❌      |
| `com.tileboard.engine.codec`            | `ColorTileCodec`, `EngineFrameRouter`                                                                                                                                                                                                                      | ❌      |
| `com.tileboard.engine.exception`        | `GameEngineException`, `GameNotFoundException`, `GameSessionException`, `EngineNotReadyException`                                                                                                                                                          | ❌      |
| `com.tileboard.engine.spring`           | `TileboardEngineAutoConfiguration`, `TileboardEngineProperties`, `GameEngineManager`, `GatewayConnectedEvent`, `GatewayDisconnectedEvent`                                                                                                                  | ✅      |
| `com.tileboard.engine.sse`              | `GameEventSseEmitter`, `SseGameEvent`, `SseGameEventType`                                                                                                                                                                                                  | ✅      |

## Appendix B — Glossary

| Term             | Meaning in this codebase                                                                              |
|------------------|-------------------------------------------------------------------------------------------------------|
| **Gateway**      | An open `TileGatewayClient` — the transport to one physical board.                                    |
| **Session**      | One running instance of one game, with its own state, features, threads and board buffer.             |
| **Tick**         | One `onTick()` invocation; period = `tileboard.engine.tick-interval`.                                 |
| **Frame**        | A protocol-level packet. `DATA_IN` = board → engine (touches), `DATA_OUT` = engine → board (colours). |
| **Board buffer** | `GameSessionImpl`'s `Board<TileColor>` mirror of what the hardware last received.                     |
| **Feature**      | A stateful helper owned by a session and reachable from `GameContext`.                                |
| **Descriptor**   | Immutable metadata identifying a game type and its board/player requirements.                         |
| **Wire code**    | The single byte representing a colour on the serial protocol.                                         |

---

*Licence and versioning: `1.0.0`, Java 17. When you change engine internals, update §6 and §13.4 in the same commit —
this README is part of the deliverable, not an afterthought.*
