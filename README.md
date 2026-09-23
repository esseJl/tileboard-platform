# Tileboard Platform - Comprehensive Platform Documentation

> **Tileboard Platform** is a complete system for controlling an LED tile board (m x n, max 255 tiles) over serial and running interactive games on it. It consists of three Maven modules: serial protocol, game engine, and Spring Boot backend application.

---

## Table of Contents
1. [Platform Introduction](#platform-introduction)
2. [Overall Architecture](#overall-architecture)
3. [Modules](#modules)
4. [Prerequisites](#prerequisites)
5. [Quick Start](#quick-start)
6. [Typical Workflow](#typical-workflow)
7. [Practical Example - SequentialTouchGame](#practical-example)
8. [Animations](#animations)
9. [Concurrency and Thread-Safety Across Platform](#concurrency)
10. [Tests and Build](#tests-and-build)
11. [Repository Structure](#repository-structure)
12. [Roadmap](#roadmap)

---

## Platform Introduction

Tileboard Platform was built to solve these problems:

- **Communication with LED Tile Board hardware** over serial port (115200-8-N-1) with a noise-resistant framing protocol (`0xFC … '#'` frames, resync + plausibility ceiling)
- **Hardware abstraction:** Game code never knows whether jSerialComm, RXTX, or a Mock is used (`SerialTransport` interface; single seam `SerialGatewayConfig`)
- **Production-ready game engine:** Scoring, health, levels, combos, timers, touch history, neighbor finding, patterns, waves, memory, reaction stats, graph queries, animations, SSE — framework-free core with an optional Spring adapter
- **Spring Boot backend:** REST API for device configuration, port management, game control, and real-time SSE streaming, with a localized (Persian) error catalog
- **Extensibility:** Adding a new game is just a `@Bean` (auto-registered by `TileboardEngineAutoConfiguration`), without changing engine or protocol

### Key Features

- ✅ **Transport-agnostic:** Protocol has zero dependency on a serial library (jSerialComm `2.11.0` is `optional`; the app pulls it in)
- ✅ **Framework-free core:** Game engine works without Spring; the Spring layer (`spring` package + `AutoConfiguration.imports`) is an optional adapter
- ✅ **Thread-safe:** `ConcurrentHashMap`, `AtomicReference`/`AtomicLong`/`AtomicBoolean`, `ReentrantLock`, `synchronized`, CAS, `CopyOnWriteArrayList`, `Semaphore` — each documented per class
- ✅ **Built-in animations:** `countdown` (+ win: 4 types, lose: 4 types, standby: 4 types, all cooperative-cancellation based)
- ✅ **Event-driven:** `GameEventBus` with `BLOCK` / `DROP_OLDEST` (default) policies; SSE to the frontend with `SseGameEvent` JSON payloads
- ✅ **Production-ready:** Per-session TTL (30 m in app, 1 h engine default), connect rollback, idempotent (dis)connect, `INTRODUCTION`/`START`/`STOP` hardware protocol, Actuator (`health,info`), Swagger starter, Micrometer gauges (when a `MeterRegistry` exists)
- ✅ **Testable:** Mock `SerialTransport`, concurrency tests, unit tests in every module; hardware suite is opt-in and skipped without a port

---

## Overall Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│  Frontend (Web / Mobile)                                                │
│  HTTP REST + SSE (EventSource on /api/v1/stream/board)                  │
├─────────────────────────────────────────────────────────────────────────┤
│  tileboard-app (Spring Boot 3.3.4, Java 17)                             │
│  ├─ Controllers: Device (/api/v1/device), SerialPort, Game, Stream      │
│  ├─ Services: DeviceConfig (AtomicReference), SerialConnection (sync),  │
│  │            SseBoardStateBroadcaster, Messages (fixed-fa i18n)         │
│  ├─ GlobalExceptionHandler → ApiResponse{message fa, debugMessage en}   │
│  └─ GameBeansConfig: SequentialTouchGame @Bean (default 3x3)            │
├─────────────────────────────────────────────────────────────────────────┤
│  tileboard-game-engine (Framework-free + Spring AutoConfig)             │
│  ├─ GameEngineImpl (ConcurrentHashMap, AtomicReference CAS, reaper)     │
│  ├─ GameSessionImpl (BoardChannel, FeatureBundle, GameState, tick)      │
│  ├─ BoardChannel (ReentrantLock + gatewayWriteLock + coalescing)        │
│  ├─ Features: ScoreSystem (CHM+AtomicInt), GameTimer (volatile+Atomic)  │
│  ├─ AnimationSystem (AtomicLong generation, SingleThreadExecutor)       │
│  ├─ GameEventBusImpl (COWAL, ArrayDeque+ReentrantLock+Semaphore)        │
│  └─ Spring: AutoConfiguration, GameEngineManager, SseGameEventPublisher │
├─────────────────────────────────────────────────────────────────────────┤
│  tileboard-serial-protocol (Framework-free, transport-agnostic)         │
│  ├─ Board<T> (generic), Position (record), TileCodec<T>, TileTouchCodec │
│  ├─ Protocol: DefaultFrameCodec (synchronized, resync, 4096 ceiling)    │
│  ├─ Transport: SerialTransport (interface), JSerialComm impl (optional) │
│  ├─ Gateway: TileGatewayClient (COWAL, writeLock, callbackExecutor)     │
│  └─ Errors: LocalizableException (errorCode + args) hierarchy           │
├─────────────────────────────────────────────────────────────────────────┤
│  Hardware: LED Tile Board (m x n, max 255 tiles) over Serial 115200     │
└─────────────────────────────────────────────────────────────────────────┘
```

**Data Flow:**

1. `TileGatewayClient` reads bytes from `SerialTransport` (single daemon callback thread)
2. `DefaultFrameCodec.decode` converts bytes to `Frame`s (stateful, synchronized, resync)
3. `EngineFrameRouter` decodes `DATA_IN` payloads to `Board<Boolean>` touches (with chunk reassembly, `TileCodec.booleanState()`)
4. `TouchFrameRouter` scans the touch board and routes `TileEvent`s (`TOUCH` per touched cell, `RELEASE` per untouched cell) to the exclusive-owner `GameSessionImpl`
5. `GameSessionImpl.handleTileEvent` (only while `RUNNING`) records touch history + reaction speed, then calls `game.onTileEvent`
6. Game calls `ctx.setTile` / `publishBoard` / `fillBoard` → `BoardChannel` (coalescing) → `TileGatewayClient.sendBoard(DATA_OUT/SET)` → `DefaultFrameCodec.encode` → `SerialTransport.write` → hardware
7. Simultaneously `eventBus.publish(BOARD_UPDATED with SessionSnapshot)` → `SseGameEventPublisher`/`GameEventSseEmitter` → `SseEmitter` (`BOARD_UPDATE` event) → frontend

---

## Modules

### 1. tileboard-serial-protocol

**Responsibility:** Pure serial protocol library, no framework dependency (only `slf4j-api` mandatory).

**Key Classes:**
- `Board<T>`: Generic mutable grid (`width/height/area`, `get/set`, `fill`, `forEach`, `positionsWhere`, `copy`, `toWireBytes`/`fromWireBytes`)
- `Position`: `(row, col)` record (non-negative validation)
- `TileCodec<T>` + `TileEncoder`/`TileDecoder`: Bridge between domain and wire (`of`, `identity`, `booleanState`)
- `TileTouchCodec`: Canonical touch-state codec (`0x00`/`0x01`)
- `ProtocolConstants`, `Frame`, `FrameEncoder`/`FrameDecoder`, `Command` (0–8), `CommandType` (0–5): Framing
- `DefaultFrameCodec`: Stateless encode (65535 guard) + stateful synchronized decode with resynchronization (4096 ceiling)
- `SerialTransport`, `SerialPortRegistry`, `SerialPortConfig` (115200-8-N-1, 50 ms timeouts), `SerialPortInfo`, `Parity`, `FlowControl`, `DataListener`: Hardware abstraction
- `JSerialCommTransport`, `JSerialCommPortRegistry`: Ready-made impl (`optional` dependency)
- `TileGatewayClient`: High-level client (thread-safe: COWAL listeners, `writeLock`, single daemon callback executor, 2-arg handshake, robust `close()`)
- `HandshakeCoordinator`, `DeviceAddress` (255-tile ceiling), `AddressResolver`, `SequenceValidator`, `SequentialIdSequenceValidator`: Addressing handshake
- `LocalizableException` + `ProtocolException`/`InvalidFrameException`/`BoardException`/`SerialTransportException`/`PortNotFoundException`: Localizable error hierarchy

**Full docs:** [tileboard-serial-protocol/README.md](tileboard-serial-protocol/README.md) (comprehensive) and [tileboard-serial-protocol/README.en.md](tileboard-serial-protocol/README.en.md) (concise).

### 2. tileboard-game-engine

**Responsibility:** Production-ready, framework-free game engine with rich features (+ optional Spring adapter).

**Key Classes:**
- `Game`, `GameDescriptor` (builder, exact board-size match), `GameContext`/`CoreGameContext`/`BoardContext`/`SessionControl`/`FeatureProvider`, `GameState` (synchronized bag), `GameStatus` (`IDLE…STOPPED`), `GameResult`, `SessionSnapshot` (SSE payload): Game contract
- `GameEngine`/`GameEngineImpl` (exclusive-owner CAS, per-session TTL reaper, teardown pool), `GameSession`/`GameSessionImpl` (tick thread, `START`/`STOP` protocol, exactly-once finish), `GameRegistry`/`DefaultGameRegistry`/`GameFactory`: Lifecycle
- `BoardChannel` + `BoardFrameBroadcaster`/`BoardFrameListener`: Board publishing with coalescing semantics
- `FeatureBundle` (14 features): `ScoreSystem`, `HealthSystem`, `LevelSystem`, `ComboTracker`, `GameTimer`, `TouchHistory`, `TouchAnalyzer`, `BoardFeature`, `NeighborFinder` (`of(...)`), `PatternMatcher`, `RandomFeature`, `WaveGenerator`, `MemoryFeature`, `ReactionSpeedTracker`, `GraphFeature`, `AnimationSystem`
- `AnimationSystem`: countdown + win/lose/standby (4+4+4 types) with generation-based cooperative cancellation
- `GameEvent`/`GameEventType` (12 types, 5 currently emitted), `GameEventBus`/`GameEventBusImpl` (`BLOCK`/`DROP_OLDEST`), `SubscriptionOptions`: Thread-safe event bus
- `ColorTileCodec`, `EngineFrameRouter` (reassembly), `TouchFrameRouter` (exclusive-owner routing): Frame routing
- `SseGameEvent`, `SseGameEventType`, `GameEventSseEmitter` (heartbeat, per-client queue): SSE DTO layer
- `TileboardEngineAutoConfiguration`, `TileboardEngineProperties` (`tileboard.engine`, TTL default 1 h), `GameEngineManager` (volatile + synchronized + null-before-close), `GatewayConnectedEvent`/`GatewayDisconnectedEvent`, `SseGameEventPublisher`, `GameEngineMetricsBinder` (3 Micrometer gauges): Spring layer

**Full docs:** [tileboard-game-engine/README.md](tileboard-game-engine/README.md)

### 3. tileboard-app

**Responsibility:** Spring Boot backend app bridging hardware to web.

**Key Classes:**
- `TileboardApplication`: main (`@SpringBootApplication` + `@ConfigurationPropertiesScan`)
- `TileboardProperties` (`tileboard.serial`), `DeviceConfiguration` (≤255 tiles), `SerialGatewayConfig` (single jSerialComm seam), `GeneralConfiguration` (CORS): Config
- `DeviceController` (`/api/v1/device`), `SerialPortController` (`/api/v1/ports/{role}/assign`, `/status`, `/connect`, `/disconnect`), `GameController` (`/api/v1/games…`), `StreamController` (`/api/v1/stream/board[…]`): REST + SSE API
- `DeviceConfigurationRequest/Response`, `AssignPortRequest({portName})`, `SerialPortResponse`, `ConnectionStatusResponse({state,inPort,outPort})`, `GameDescriptorResponse`, `StartGameRequest({gameId, players:[{name,role}]})`, `PlayerRequest`, `GameSessionResponse({sessionId,gameId,status})`, `ApiResponse({status,message,data,extra,debugMessage})`, `ApiResponses`, `Status`: DTOs
- `DeviceConfigurationService`/`InMemoryDeviceConfigurationService`, `SerialConnectionManager`/`DefaultSerialConnectionManager` (+ `PortRole`, `PortAssignment`, `ConnectionState`), `BoardStateBroadcaster`/`SseBoardStateBroadcaster` (`board-frame` events): Services
- `Messages` (fixed-`fa`), `ApiException` + 5 subclasses, `GlobalExceptionHandler` (409/404/502/400/500 mapping): i18n error handling
- `GameBeansConfig`, `SequentialTouchGame` (default 3×3): Sample tutorial game

**Full docs:** [tileboard-app/README.md](tileboard-app/README.md)

---

## Prerequisites

- **Java 17+** (`maven.compiler.source/target = 17`, engine uses `release = 17`)
- **Maven 3.8+**
- **Tileboard board** connected via USB (or a Mock `SerialTransport` — all unit tests run hardware-less)
- **Git**

---

## Quick Start

### 1. Clone and Build

```bash
git clone <repo-url>
cd tileboard-platform
mvn clean install -DskipTests
```

### 2. Run Backend

```bash
cd tileboard-app
mvn spring-boot:run
# or
java -jar target/tileboard-app-1.0.0.jar
# with prod profile:
java -jar target/tileboard-app-1.0.0.jar --spring.profiles.active=prod
```

App runs on `http://localhost:8080`.

- Swagger UI: springdoc starter (`2.6.0`) default path
- Health: `http://localhost:8080/actuator/health` (only `health,info` exposed)

### 3. Quick Test Without Hardware (Mock)

All unit tests pass without hardware:

```bash
mvn test
```

Only `TileboardHardwareIT` needs a real board (failsafe-based, skipped without a port):

```bash
mvn verify -P hardware-tests -pl tileboard-serial-protocol -Dtileboard.hardware.port=COM3
```

---

## Typical Workflow

### Step 1: Configure Device

```bash
curl -X POST http://localhost:8080/api/v1/device \
  -H "Content-Type: application/json" \
  -d '{"width":3,"height":3}'
```

### Step 2: List and Assign Ports

Role is a **path variable** (`IN`/`OUT`); the body carries only `portName`:

```bash
curl http://localhost:8080/api/v1/ports

curl -X POST http://localhost:8080/api/v1/ports/OUT/assign \
  -H "Content-Type: application/json" \
  -d '{"portName":"COM3"}'

curl -X POST http://localhost:8080/api/v1/ports/IN/assign \
  -H "Content-Type: application/json" \
  -d '{"portName":"COM3"}'
```

### Step 3: Connect

```bash
curl -X POST http://localhost:8080/api/v1/ports/connect
```

Logs (for a 3×3 device):

```
Enabling id handshake for a 3x3 board (minimumSequence=3)
Tile board gateway connected (input=COM3, output=COM3)
sent INTRODUCTION to hardware (3X3 board)
Game engine bound to the newly connected tile gateway (3x3)
```

### Step 4: List Games

```bash
curl http://localhost:8080/api/v1/games
```

Response includes `sequential-touch` (sample game, sized to the startup device config, default 3×3).

### Step 5: Start Game

`role` is required (`SOLO` for single-player):

```bash
curl -X POST http://localhost:8080/api/v1/games/sessions \
  -H "Content-Type: application/json" \
  -d '{"gameId":"sequential-touch","players":[{"name":"Ali","role":"SOLO"}]}'
```

Response: `{status, data: {sessionId, gameId, status}}` — scores/players are observed via SSE, not this DTO.

### Step 6: SSE for Events

```bash
curl -N -H "Accept: text/event-stream" http://localhost:8080/api/v1/stream/board
# or scoped to one session:
curl -N -H "Accept: text/event-stream" http://localhost:8080/api/v1/stream/board/{sessionId}
```

Or in JS (note the SSE event names are `BOARD_UPDATE` / `SESSION_LIFECYCLE`, and `data` is `SseGameEvent` JSON):

```javascript
const es = new EventSource('/api/v1/stream/board');
es.addEventListener('BOARD_UPDATE', e => console.log(JSON.parse(e.data).data.board));
es.addEventListener('SESSION_LIFECYCLE', e => { console.log('Lifecycle', JSON.parse(e.data)); es.close(); });
```

---

## Practical Example

### SequentialTouchGame - Summary

This game (default 3×3, `tileboard-app/.../game/SequentialTouchGame.java`) implements the full tutorial scenario:

- **Each tile lights up sequentially with a color:** positions list row-major, each tile lit with the next color from a 7-color palette (RED, GREEN, BLUE, YELLOW, PINK, LIGHT_BLUE, WHITE)
- **As soon as touched, player scores and the next tile's turn comes:** `ctx.scores().add(playerId, 10)`, current tile OFF, next tile lit (`TOUCH`/`HOLD` handled; `RELEASE` ignored)
- **Until all tiles are lit and touched:** index runs to `positions.size()` (9 on 3×3)
- **Then the game ends:** `ctx.winSession(players)` after the win animation (`FINISHED` with winners)
- **Animations:**
  - `standby` (BREATHING) 2 seconds before start (infinite animation → timeout → `cancelCurrent()`)
  - `countdown` (3→2→1 + green blink, 1000 ms/digit) before start
  - `lose` (FADE_TO_RED) for a wrong touch, `lose` (DESCENDING_CURTAIN) on the 90-second timeout
  - `win` (RADIAL_BURST) on victory
- **Error path:** `onError` plays `PULSE_RED` (2 s) then `stopSession()`

**Full code:** `tileboard-app/src/main/java/com/tileboard/app/game/SequentialTouchGame.java`

**Bean registration:** `tileboard-app/src/main/java/com/tileboard/app/game/GameBeansConfig.java` (sizes the game from the startup device config, default 3×3 — restart after changing device size)

**Full step-by-step docs:** "Comprehensive Game Creation Tutorial" section in [tileboard-app/README.md](tileboard-app/README.md)

### Player Perspective Flow (3×3)

1. **Standby (BREATHING):** corners/border breathe blue (2 seconds)
2. **Countdown:** digits 3 (red) → 2 (yellow) → 1 (green) → green blink 3× (GO!)
3. **Game:** tile (0,0) lights red
4. Player touches (0,0) → +10 points, (0,0) off, (0,1) lights green
5. Player touches (0,1) → +10 points, (0,1) off, (0,2) lights blue
6. … until (2,2)
7. Wrong tile touched → FADE_TO_RED short animation → correct tile re-lights
8. 90 seconds pass → DESCENDING_CURTAIN → loss (`FINISHED`, no winners)
9. All 9 tiles touched in order → RADIAL_BURST → win

---

## Animations

### Types

| Category | API | Types | Description |
|------|-----|-------|-------|
| **Countdown** | `playCountdown([ms])` (default 1000 ms) | — | <3×5 boards: full-board RED→BLUE→GREEN; larger: centered 5×3 digits 3/2/1 (red/yellow/green) + 3× green blink |
| **Win** | `playWinAnimation([type])` (default RADIAL_BURST) | `RADIAL_BURST`, `RAINBOW_SWEEP`, `SPARKLE`, `FIREWORKS` | Victory animation |
| **Lose** | `playLoseAnimation([type])` (default FADE_TO_RED) | `FADE_TO_RED`, `DESCENDING_CURTAIN`, `CRUMBLE`, `PULSE_RED` | Defeat animation |
| **Standby** | `playStandbyAnimation([type])` (default BREATHING) | `BREATHING`, `CORNER_PULSE`, `WAVE_BORDER`, `RANDOM_TWINKLE` | Idle, infinite until cancelled |

### Usage

```java
// Countdown before start (1000 ms per digit here, as in SequentialTouchGame)
ctx.animations().playCountdown(1000).join();

// Win
ctx.animations().playWinAnimation(AnimationSystem.WinAnimationType.RADIAL_BURST)
    .thenRun(() -> ctx.winSession(ctx.players()));

// Lose (short feedback, then re-light)
ctx.animations().playLoseAnimation(AnimationSystem.LoseAnimationType.FADE_TO_RED)
    .thenRun(() -> lightCurrentTile(ctx));

// Standby 2 seconds (TimeoutException is the expected exit path)
try {
    ctx.animations().playStandbyAnimation(AnimationSystem.StandbyAnimationType.BREATHING)
        .get(2, TimeUnit.SECONDS);
} catch (Exception e) {
    ctx.animations().cancelCurrent();
}
```

**Technical implementation:** All animations run on a single daemon thread (`tileboard-animation`), with an `AtomicLong generation` for cooperative cancellation (`RunToken`: `sleep` returns false / `pause`+`show` throw when superseded). Each new animation cancels the previous one. Each returns a `CompletableFuture` (normal / cancelled / exceptional) that can be chained; `shutdown()` at session end cancels and stops the executor.

**Full docs:** AnimationSystem section in [tileboard-game-engine/README.md](tileboard-game-engine/README.md) and animations section in [tileboard-app/README.md](tileboard-app/README.md)

---

## Concurrency

This platform is highly concurrent and all sensitive sections are thread-safe:

| Section | Technique | Description |
|-----|--------|-------|
| `DefaultFrameCodec.decode` | `synchronized` + `ByteArrayOutputStream` + `finally` trim | Stateful buffer, resync (+4096 ceiling); trim-always prevents wedging |
| `TileGatewayClient` | `CopyOnWriteArrayList` + `writeLock` + single daemon `callbackExecutor` | Lock-free dispatch iteration, serialized wire writes, per-listener catch+log; caller executors never shut down |
| `TileGatewayClient.start/close` | `synchronized`, idempotent | Listener attached only with an input transport; each close step runs despite earlier failures |
| `JSerialCommTransport.setDataListener` | `synchronized` + `volatile` handle | Prevents listener leak on concurrent calls |
| `GameEngineImpl` | `ConcurrentHashMap` + `AtomicReference` CAS + `AtomicBoolean` + reaper/teardown pools | `exclusiveSessionId` ownership, per-session TTL tasks, post-close callback guard |
| `SessionLifecycle` | `AtomicReference` + CAS loop (`IDLE→RUNNING`, `RUNNING/PAUSED→final`) | `finishSession` runs exactly once |
| `BoardChannel` | `ReentrantLock` + `gatewayWriteLock` + re-read snapshot | Coalescing: latest consistent state sent; broadcaster dispatch outside the write lock |
| `GameState` | `synchronized` methods + `HashMap` | Thread-safe bag + unmodifiable snapshots |
| `ScoreSystem` | `ConcurrentHashMap` + `AtomicInteger` (`computeIfAbsent` + `addAndGet`) | Lock-free scoring; `onChange` on caller thread |
| `GameTimer` | `volatile` + `AtomicReference` + `AtomicBoolean` | Visibility without locking; each expiry callback fires exactly once |
| `AnimationSystem` | `AtomicLong generation` + `runLock` + `SingleThreadExecutor` + `CompletableFuture` | Cooperative cancellation; superseded futures cancel |
| `GameEventBusImpl` | `CopyOnWriteArrayList` + (`LinkedBlockingQueue` \| `ArrayDeque`+`ReentrantLock`+`Semaphore`) + `AtomicLong` | `BLOCK` (200 ms timeout) and `DROP_OLDEST` policies; per-subscription daemon drain loop |
| `TouchFrameRouter`/`EngineFrameRouter` | lock-guarded reassembly buffer | Chunk reassembly with timeout; exclusive-owner-only routing |
| `DefaultSerialConnectionManager` | `synchronized` + `EnumMap` + rollback + idempotent (dis)connect | Leak-free connect, dual topologies, handshake-before-start |
| `InMemoryDeviceConfigurationService` | `AtomicReference` | Single-value thread-safety without synchronized |
| `GameEngineManager` | `volatile` + `synchronized` + null-before-close + `@PreDestroy` | Race-free (re)binding; never exposes a half-closed engine |
| `SseBoardStateBroadcaster` | `CopyOnWriteArrayList` | Dead SSE emitters dropped on write failure |

**Full docs for each:** See "Deep Dive" sections in each module's README.

---

## Tests and Build

### Build Whole Platform

```bash
mvn clean install -DskipTests
```

### Tests (all hardware-less)

```bash
mvn test
# or for one module:
mvn test -pl tileboard-serial-protocol
mvn test -pl tileboard-game-engine
mvn test -pl tileboard-app
```

Module suites: protocol (`BoardTest`, `DefaultFrameCodecTest`, `TileGatewayClientTest`, `HandshakeCoordinatorTest`, `BoardFrameListenerTest`), engine (codec/core/event/feature tests incl. concurrency tests), app (`TileboardApplicationTests`, `TileboardPropertiesTest`, `ControllerUnitTest`, `InMemoryDeviceGeneralConfigurationServiceTest`, `DefaultSerialConnectionManagerTest`).

### Hardware Tests (opt-in, skipped without a port)

```bash
mvn verify -P hardware-tests -pl tileboard-serial-protocol -Dtileboard.hardware.port=COM3
# optional: -Dtileboard.hardware.width=3 -Dtileboard.hardware.height=3 -Dtileboard.hardware.baud=115200 ...
```

(`TileboardHardwareIT` runs via maven-failsafe, so it needs `verify`, not `test`.)

### Run Backend

```bash
cd tileboard-app
mvn spring-boot:run
```

### Package

```bash
mvn clean package -DskipTests
java -jar tileboard-app/target/tileboard-app-1.0.0.jar
```

---

## Repository Structure

```
tileboard-platform/
├── pom.xml (parent, packaging pom; modules: serial-protocol, game-engine, app)
├── README.md (this file)
├── tileboard-serial-protocol/
│   ├── pom.xml (java 17, slf4j-api, jSerialComm optional, junit 5.11.0, failsafe hardware-tests profile)
│   ├── README.md (comprehensive protocol docs)
│   ├── README.en.md (concise protocol docs)
│   └── src/main/java/com/tileboard/serial/
│       ├── board/ (Board, Position, TileCodec, TileEncoder, TileDecoder)
│       ├── protocol/ (Frame, FrameEncoder/Decoder, DefaultFrameCodec, ProtocolConstants, Command, CommandType, TileTouchCodec)
│       ├── transport/ (SerialTransport, SerialPortRegistry, SerialPortConfig, SerialPortInfo, Parity, FlowControl, DataListener)
│       ├── transport/jserialcomm/ (JSerialCommTransport, JSerialCommPortRegistry)
│       ├── gateway/ (TileGatewayClient, FrameListener, BoardListener, BoardFrameListener)
│       ├── gateway/handshake/ (HandshakeCoordinator, DeviceAddress, AddressResolver, SequenceValidator, SequentialIdSequenceValidator)
│       ├── exception/ (ProtocolException, InvalidFrameException, BoardException, SerialTransportException, PortNotFoundException)
│       └── support/error/ (LocalizableException)
├── tileboard-game-engine/
│   ├── pom.xml (java 17; jackson, spring-boot-autoconfigure optional, spring-webmvc, micrometer, junit 5.10.2, mockito, awaitility)
│   ├── README.md (comprehensive engine docs)
│   └── src/main/java/com/tileboard/engine/
│       ├── core/ (Game, GameDescriptor, GameContext family, GameEngine(Impl), GameSession(Impl), GameState, GameStatus, SessionLifecycle, GameResult, SessionSnapshot, GameRegistry, BoardChannel, BoardFrameBroadcaster/Listener, TouchFrameRouter, FeatureBundle)
│       ├── feature/ (ScoreSystem, HealthSystem, LevelSystem, ComboTracker, GameTimer, TouchHistory, TouchAnalyzer, BoardFeature, PatternMatcher, RandomFeature, WaveGenerator, MemoryFeature, ReactionSpeedTracker, GraphFeature, AnimationSystem, AnimationRegistry, BoardAnimation)
│       ├── feature/neighbor/ (Adjacency, GridTopology, NeighborFinder)
│       ├── event/ (GameEvent, GameEventType, GameEventBus, GameEventBusImpl, GameEventListener, SubscriptionOptions, EventOverflowPolicy)
│       ├── model/ (Player, PlayerRole, Team, TileColor, TileEvent, TileEventType, TouchSequence)
│       ├── codec/ (ColorTileCodec, EngineFrameRouter)
│       ├── sse/ (GameEventSseEmitter, SseGameEvent, SseGameEventType)
│       ├── spring/ (TileboardEngineAutoConfiguration, TileboardEngineProperties, GameEngineManager, GatewayConnected/DisconnectedEvent, SseGameEventPublisher, GameEngineMetricsBinder)
│       └── exception/ (GameEngineException, GameNotFoundException, GameSessionException, EngineNotReadyException)
└── tileboard-app/
    ├── pom.xml (spring-boot 3.3.4 parent; web, validation, actuator, springdoc 2.6.0, jSerialComm 2.11.0)
    ├── README.md (comprehensive app docs + game tutorial)
    └── src/main/java/com/tileboard/app/
        ├── TileboardApplication.java
        ├── config/ (TileboardProperties, DeviceConfiguration, SerialGatewayConfig, GeneralConfiguration)
        ├── controller/ (DeviceController, SerialPortController, GameController, StreamController)
        ├── dto/ (ApiResponse, ApiResponses, Status, DeviceConfigurationRequest/Response, AssignPortRequest, SerialPortResponse, ConnectionStatusResponse, GameDescriptorResponse, StartGameRequest, PlayerRequest, GameSessionResponse)
        ├── service/device/ (DeviceConfigurationService, InMemoryDeviceConfigurationService)
        ├── service/serial/ (SerialConnectionManager, DefaultSerialConnectionManager, PortRole, PortAssignment, ConnectionState, SerialPortSummary)
        ├── service/streaming/ (BoardStateBroadcaster, SseBoardStateBroadcaster)
        ├── exception/ (ApiException + 5 subclasses), exception/handler/ (GlobalExceptionHandler)
        ├── i18n/ (Messages) + resources (application.yml, application-prod.yml, messages[_fa].properties)
        └── game/ (SequentialTouchGame, GameBeansConfig)
```

---

## Roadmap

- [x] Transport-agnostic serial protocol (framing, gateway, handshake, localizable errors)
- [x] Production-ready game engine (14 features, animations, event bus, SSE DTOs, Spring adapter)
- [x] Spring Boot backend (device/ports/games REST, SSE streaming, Persian error catalog)
- [x] win/lose/standby/countdown animations (4+4+4 types + scalable countdown)
- [x] Sample game SequentialTouchGame (3×3 default, device-sized)
- [x] Micrometer gauges (`tileboard.engine.active_sessions`, `tileboard.eventbus.*` — bound when a `MeterRegistry` exists; Prometheus endpoint not exposed)
- [ ] Web frontend (React/Vue) for board display and game control
- [ ] Raw board-mirror SSE endpoint on top of `SseBoardStateBroadcaster` (seam is ready, unexposed)
- [ ] Persistence for DeviceConfiguration (DB)
- [ ] Authentication and security (Spring Security)
- [ ] More games (Color Match, Memory, Reaction, …)
- [ ] Multi-board support (engine is single-exclusive-owner today)
- [ ] Feature→bus event wiring (`SCORE_CHANGED`, `LEVEL_UP`, … are reserved but not yet published)
- [ ] Docker and Kubernetes deployment (sample Dockerfile in app README)

---

## Contributing

1. Fork it
2. Create new branch (`git checkout -b feature/amazing-game`)
3. Commit (`git commit -m 'Add amazing game'`)
4. Push (`git push origin feature/amazing-game`)
5. Create Pull Request

---

## License

Internal - Tileboard Platform Team

---

## Authors

Tileboard Platform Team

---

## FAQ

**Q: Can I test without hardware?**

A: Yes — every unit test in all three modules runs hardware-less (including a Mock-`SerialTransport` pattern documented in the protocol README). Only `TileboardHardwareIT` needs a board: `mvn verify -P hardware-tests -pl tileboard-serial-protocol -Dtileboard.hardware.port=COM3`.

**Q: How to create a new game?**

A: See "Comprehensive Game Creation Tutorial" in [tileboard-app/README.md](tileboard-app/README.md). Summary: implement `Game` (stateless — state in `ctx.state()`), register as a `@Bean` (auto-registered by the engine), make sure `boardSize` matches the device config. For per-session construction state use `registry.register(descriptor, factory)`.

**Q: How do animations work?**

A: Single daemon thread + `AtomicLong` generation + cooperative `RunToken` + `CompletableFuture` chaining. See the AnimationSystem section in [tileboard-game-engine/README.md](tileboard-game-engine/README.md) and the animations section in [tileboard-app/README.md](tileboard-app/README.md).

**Q: What are the SSE event names?**

A: The SSE wire names are `SseGameEventType`: `SESSION_LIFECYCLE`, `BOARD_UPDATE`, `TICK`, `SCORE_UPDATE`, `GAME_STATE`, `CUSTOM` — each carrying `SseGameEvent` JSON (`{sessionId, gameId, type, data: SessionSnapshot, timestamp}`) on `GET /api/v1/stream/board[/{sessionId}]`.

**Q: How to understand concurrency?**

A: Each README has a "Deep Dive" section explaining the concurrency patterns with code quotes taken from the actual implementation.

---

**Version:** 1.0.0  
**Java:** 17+  
**Spring Boot:** 3.3.4 (app) / 3.3.2 (engine Spring adapter)  
**Date:** 2026-09-23
