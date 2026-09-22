# Tileboard Platform - Comprehensive Platform Documentation

> **Tileboard Platform** is a complete system for controlling an LED tile board (m x n) over serial and running interactive games on it. It consists of three Maven modules: serial protocol, game engine, and Spring Boot backend application.

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

- **Communication with LED Tile Board hardware** over serial port (115200 baud) with noise-resistant framing protocol
- **Hardware abstraction:** Game code should not know whether jSerialComm, RXTX, or Mock is used
- **Production-ready game engine:** Scoring, health, levels, combos, timers, touch history, neighbor finding, patterns, waves, animations, SSE
- **Spring Boot backend:** REST API for configuration, port management, game control, real-time streaming
- **Extensibility:** Adding a new game is just a `@Bean`, without changing engine or protocol

### Key Features

- ✅ **Transport-agnostic:** Protocol has zero dependency on serial library (jSerialComm optional)
- ✅ **Framework-free core:** Game engine works without Spring, Spring layer is optional
- ✅ **Thread-safe:** All sensitive sections protected with `ConcurrentHashMap`, `AtomicReference`, `ReentrantLock`, `synchronized`, `CAS`
- ✅ **Built-in animations:** `countdown`, `win` (4 types), `lose` (4 types), `standby` (4 types)
- ✅ **Event-driven:** EventBus with two policies `BLOCK` and `DROP_OLDEST`, SSE for frontend
- ✅ **Production-ready:** TTL for sessions, rollback for connect, idempotent disconnect, health check, Swagger
- ✅ **Testable:** Mock Transport, concurrency tests, unit tests for all features

---

## Overall Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│  Frontend (Web / Mobile)                                                │
│  HTTP REST + SSE (EventSource)                                          │
├─────────────────────────────────────────────────────────────────────────┤
│  tileboard-app (Spring Boot 3.3.4, Java 17)                             │
│  ├─ Controllers: Device, SerialPort, Game, Stream                       │
│  ├─ Services: DeviceConfig (AtomicReference), SerialConnection (sync)   │
│  └─ GameEngineManager (EventListener, volatile, sync)                   │
├─────────────────────────────────────────────────────────────────────────┤
│  tileboard-game-engine (Framework-free + Spring AutoConfig)             │
│  ├─ GameEngineImpl (ConcurrentHashMap, AtomicReference, CAS)            │
│  ├─ GameSessionImpl (BoardChannel, FeatureBundle, GameState, tick)      │
│  ├─ BoardChannel (ReentrantLock + gatewayWriteLock + coalescing)        │
│  ├─ FeatureBundle: ScoreSystem (CHM+AtomicInt), Timer (volatile+Atomic) │
│  ├─ AnimationSystem (AtomicLong generation, SingleThreadExecutor)       │
│  ├─ GameEventBusImpl (COWAL, ArrayDeque+ReentrantLock+Semaphore)        │
│  └─ Spring: AutoConfiguration, GameEngineManager, SsePublisher          │
├─────────────────────────────────────────────────────────────────────────┤
│  tileboard-serial-protocol (Framework-free, transport-agnostic)         │
│  ├─ Board<T> (generic), Position (record), TileCodec<T>                 │
│  ├─ Protocol: DefaultFrameCodec (synchronized, resync, 4096 ceiling)    │
│  ├─ Transport: SerialTransport (interface), JSerialComm impl (optional) │
│  └─ Gateway: TileGatewayClient (COWAL, writeLock, callbackExecutor)     │
├─────────────────────────────────────────────────────────────────────────┤
│  Hardware: LED Tile Board (m x n, max 255 tiles) over Serial 115200     │
└─────────────────────────────────────────────────────────────────────────┘
```

**Data Flow:**

1. `TileGatewayClient` reads bytes from `SerialTransport`
2. `DefaultFrameCodec.decode` converts bytes to `Frame` (stateful, resync)
3. `EngineFrameRouter` converts frame to `Board<Boolean>` (touches)
4. `TouchFrameRouter` converts touch board to `TileEvent` and routes to correct `GameSessionImpl`
5. `GameSessionImpl.handleTileEvent` records history and reaction speed and calls `game.onTileEvent`
6. Game calls `ctx.setTile` / `publishBoard` -> `BoardChannel` -> `TileGatewayClient.sendBoard` -> `DefaultFrameCodec.encode` -> `SerialTransport.write` -> hardware
7. Simultaneously `eventBus.publish(BOARD_UPDATED)` -> `SseGameEventPublisher` -> `SseEmitter` -> frontend

---

## Modules

### 1. tileboard-serial-protocol

**Responsibility:** Pure serial protocol library, no framework dependency

**Key Classes:**
- `Board<T>`: Generic mutable grid
- `TileCodec<T>`: Bridge between domain and wire
- `ProtocolConstants`, `Frame`, `Command`, `CommandType`: Framing
- `DefaultFrameCodec`: Stateless encode, stateful decode with resynchronization
- `SerialTransport`, `SerialPortRegistry`: Hardware abstraction
- `JSerialCommTransport`, `JSerialCommPortRegistry`: Ready-made impl (optional)
- `TileGatewayClient`: High-level client (thread-safe, COWAL, writeLock, callbackExecutor)
- `HandshakeCoordinator`, `DeviceAddress`: Addressing handshake

**Full docs:** [tileboard-serial-protocol/README.md](tileboard-serial-protocol/README.md)

### 2. tileboard-game-engine

**Responsibility:** Production-ready, framework-free game engine with rich features

**Key Classes:**
- `Game`, `GameDescriptor`, `GameContext`, `GameState`: Game contract
- `GameEngine`, `GameEngineImpl`, `GameSession`, `GameSessionImpl`: Lifecycle
- `BoardChannel`: Board publishing with coalescing semantics
- `FeatureBundle`: All features (Score, Health, Level, Combo, Timer, TouchHistory, NeighborFinder, WaveGenerator, AnimationSystem, ...)
- `AnimationSystem`: win/lose/standby/countdown animations with generation-based cancellation
- `GameEventBusImpl`: Thread-safe EventBus with BLOCK and DROP_OLDEST policies
- `TileboardEngineAutoConfiguration`, `GameEngineManager`: Spring layer

**Full docs:** [tileboard-game-engine/README.md](tileboard-game-engine/README.md)

### 3. tileboard-app

**Responsibility:** Spring Boot backend app bridging hardware to web

**Key Classes:**
- `TileboardApplication`: main
- `TileboardProperties`, `DeviceConfiguration`, `SerialGatewayConfig`: Config
- `DeviceController`, `SerialPortController`, `GameController`, `StreamController`: REST API
- `DeviceConfigurationService`, `SerialConnectionManager`, `BoardStateBroadcaster`: Services
- `GameBeansConfig`, `SequentialTouchGame`: Sample tutorial game
- `GlobalExceptionHandler`: Error handling

**Full docs:** [tileboard-app/README.md](tileboard-app/README.md)

---

## Prerequisites

- **Java 17+** (project built with `maven.compiler.source=17`)
- **Maven 3.8+**
- **Tileboard board** connected via USB (or without hardware using Mock for tests)
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

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- Health: `http://localhost:8080/actuator/health`

### 3. Quick Test Without Hardware (Mock)

If you don't have a board, you can build a Mock Transport and test without serial. Unit tests need no hardware:

```bash
mvn test
```

All unit tests pass without hardware. Only `TileboardHardwareIT` needs hardware and runs with `hardware-tests` profile:

```bash
mvn verify -P hardware-tests -pl tileboard-serial-protocol
```

---

## Typical Workflow

### Step 1: Configure Device

```bash
curl -X POST http://localhost:8080/api/v1/devices/configure \
  -H "Content-Type: application/json" \
  -d '{"width":8,"height":8}'
```

### Step 2: List and Assign Ports

```bash
curl http://localhost:8080/api/v1/ports

curl -X POST http://localhost:8080/api/v1/ports/assign \
  -H "Content-Type: application/json" \
  -d '{"role":"OUT","portName":"COM3"}'

curl -X POST http://localhost:8080/api/v1/ports/assign \
  -H "Content-Type: application/json" \
  -d '{"role":"IN","portName":"COM3"}'
```

### Step 3: Connect

```bash
curl -X POST http://localhost:8080/api/v1/ports/connect
```

Logs:

```
Enabling id handshake for a 8x8 board (minimumSequence=2)
Tile board gateway connected (in=COM3, out=COM3)
Game engine bound to the newly connected tile gateway (8x8)
```

### Step 4: List Games

```bash
curl http://localhost:8080/api/v1/games
```

Response includes `sequential-touch` (sample game).

### Step 5: Start Game

```bash
curl -X POST http://localhost:8080/api/v1/games/sessions \
  -H "Content-Type: application/json" \
  -d '{"gameId":"sequential-touch","players":[{"name":"Ali"}]}'
```

### Step 6: SSE for Events

```bash
curl -N -H "Accept: text/event-stream" http://localhost:8080/api/v1/games/events
```

Or in JS:

```javascript
const es = new EventSource('/api/v1/games/events');
es.addEventListener('BOARD_UPDATED', e => console.log(JSON.parse(e.data)));
es.addEventListener('SESSION_FINISHED', e => { console.log('Finished', JSON.parse(e.data)); es.close(); });
```

---

## Practical Example

### SequentialTouchGame - Summary

This game implements all your requirements:

- **Each tile lights up sequentially with a color:** Positions list row-major, each tile lights with color from palette
- **As soon as touched, add player score and next tile's turn:** `ctx.scores().add(playerId, 10)` and `ctx.setTile(..., OFF)` and next tile
- **Until all tiles lit and touched:** Index goes up to `positions.size()`
- **Then game ends:** `ctx.winSession(players)` after win animation
- **Animations lose, win, stand-by:**
  - `standby` (BREATHING) 2 seconds before start
  - `countdown` (3→2→1) before start
  - `lose` (FADE_TO_RED) for wrong touch, `DESCENDING_CURTAIN` for timeout
  - `win` (RADIAL_BURST) for victory
- **countDown animation before game start**

**Full code:** `tileboard-app/src/main/java/com/tileboard/app/game/SequentialTouchGame.java`

**Bean registration:** `tileboard-app/src/main/java/com/tileboard/app/game/GameBeansConfig.java`

**Full step-by-step docs:** "Comprehensive Game Creation Tutorial" section in [tileboard-app/README.md](tileboard-app/README.md)

### Player Perspective Flow

1. **Standby (BREATHING):** Board corners blink blue (2 seconds)
2. **Countdown:** Whole board red (3) -> yellow (2) -> green (1) -> green blink 3 times (GO!)
3. **Game:** Tile (0,0) lights red
4. Player touches (0,0) -> +10 points, (0,0) off, (0,1) lights green
5. Player touches (0,1) -> +10 points, (0,1) off, (0,2) lights blue
6. ... until (7,7)
7. If wrong tile touched -> FADE_TO_RED short animation -> correct tile re-lights
8. If 90 seconds pass -> DESCENDING_CURTAIN -> loss
9. If all 64 tiles correctly touched -> RADIAL_BURST -> win

---

## Animations

### Types

| Category | Types | Description |
|------|-------|-------|
| **Countdown** | `playCountdown()` | 3→2→1 with digit rendering or full board color + green blink |
| **Win** | `RADIAL_BURST`, `RAINBOW_SWEEP`, `SPARKLE`, `FIREWORKS` | Victory animation |
| **Lose** | `FADE_TO_RED`, `DESCENDING_CURTAIN`, `CRUMBLE`, `PULSE_RED` | Defeat animation |
| **Standby** | `BREATHING`, `CORNER_PULSE`, `WAVE_BORDER`, `RANDOM_TWINKLE` | Idle infinite until cancelled |

### Usage

```java
// Countdown before start
ctx.animations().playCountdown(700).join();

// Win
ctx.animations().playWinAnimation(AnimationSystem.WinAnimationType.RADIAL_BURST)
    .thenRun(() -> ctx.winSession(ctx.players()));

// Lose
ctx.animations().playLoseAnimation(AnimationSystem.LoseAnimationType.FADE_TO_RED)
    .thenRun(() -> lightCurrentTile(ctx));

// Standby 2 seconds
try {
    ctx.animations().playStandbyAnimation(AnimationSystem.StandbyAnimationType.BREATHING)
        .get(2, TimeUnit.SECONDS);
} catch (TimeoutException e) {
    ctx.animations().cancelCurrent();
}
```

**Technical implementation:** All animations run on a `SingleThreadExecutor`, with `AtomicLong generation` for cooperative cancellation. Each new animation cancels previous one. Returns `CompletableFuture` that can be chained.

**Full docs:** AnimationSystem section in [tileboard-game-engine/README.md](tileboard-game-engine/README.md) and animations section in [tileboard-app/README.md](tileboard-app/README.md)

---

## Concurrency

This platform is highly concurrent and all sensitive sections are thread-safe:

| Section | Technique | Description |
|-----|--------|-------|
| `DefaultFrameCodec.decode` | `synchronized` + `ByteArrayOutputStream` | Stateful buffer, resync logic |
| `TileGatewayClient` | `CopyOnWriteArrayList` + `writeLock` + `callbackExecutor` | Listeners COWAL, writes synchronized, callback on daemon thread |
| `JSerialCommTransport.setDataListener` | `synchronized` | Prevents listener leak |
| `GameEngineImpl` | `ConcurrentHashMap` + `AtomicReference` + `CAS` + `ScheduledExecutor` | exclusiveSessionId with compareAndSet, reaper with TTL |
| `SessionLifecycle` | `AtomicReference` + CAS loop | Finish exactly once |
| `BoardChannel` | `ReentrantLock` + `gatewayWriteLock` + coalescing | stateLock for buffer, gatewayWriteLock for wire, re-read snapshot for coalescing |
| `GameState` | `synchronized` methods + `HashMap` | Thread-safe bag |
| `ScoreSystem` | `ConcurrentHashMap` + `AtomicInteger` | Add with CAS |
| `GameTimer` | `volatile` + `AtomicReference` | Visibility without lock, callback only once |
| `AnimationSystem` | `AtomicLong generation` + `SingleThreadExecutor` + `CompletableFuture` | Cooperative cancellation |
| `GameEventBusImpl` | `CopyOnWriteArrayList` + `ArrayDeque` + `ReentrantLock` + `Semaphore` + `AtomicLong` | Two policies BLOCK and DROP_OLDEST, Deque for dropping oldest |
| `DefaultSerialConnectionManager` | `synchronized` + `EnumMap` + rollback | assign/connect/disconnect synchronized, rollback for leak prevention |
| `InMemoryDeviceConfigurationService` | `AtomicReference` | Thread-safe without synchronized |
| `GameEngineManager` | `volatile` + `synchronized` + null-before-close | Engine volatile, null before close |

**Full docs for each:** See "Deep Dive" section in each module's README.

---

## Tests and Build

### Build Whole Platform

```bash
mvn clean install -DskipTests
```

### Tests

```bash
mvn test
# or for one module:
mvn test -pl tileboard-serial-protocol
mvn test -pl tileboard-game-engine
mvn test -pl tileboard-app
```

### Hardware Tests

```bash
mvn verify -P hardware-tests -pl tileboard-serial-protocol
```

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
├── pom.xml (parent, packaging pom, modules: serial-protocol, game-engine, app)
├── README.md (this file)
├── .gitignore
├── tileboard-serial-protocol/
│   ├── pom.xml
│   ├── README.md (comprehensive protocol docs)
│   └── src/main/java/com/tileboard/serial/
│       ├── board/ (Board, Position, TileCodec)
│       ├── protocol/ (Frame, DefaultFrameCodec, ProtocolConstants)
│       ├── transport/ (SerialTransport, SerialPortRegistry)
│       ├── transport/jserialcomm/ (JSerialComm impl)
│       ├── gateway/ (TileGatewayClient)
│       └── gateway/handshake/ (HandshakeCoordinator)
├── tileboard-game-engine/
│   ├── pom.xml
│   ├── README.md (comprehensive engine docs)
│   └── src/main/java/com/tileboard/engine/
│       ├── core/ (Game, GameEngine, GameSession, BoardChannel)
│       ├── feature/ (ScoreSystem, AnimationSystem, GameTimer, ...)
│       ├── event/ (GameEventBusImpl)
│       ├── model/ (Player, TileColor, TileEvent)
│       ├── codec/ (ColorTileCodec, EngineFrameRouter)
│       ├── sse/ (SseGameEventPublisher)
│       └── spring/ (AutoConfiguration, GameEngineManager)
└── tileboard-app/
    ├── pom.xml
    ├── README.md (comprehensive app docs + game tutorial)
    └── src/main/java/com/tileboard/app/
        ├── TileboardApplication.java
        ├── config/ (TileboardProperties, DeviceConfiguration)
        ├── controller/ (Device, SerialPort, Game, Stream)
        ├── dto/ (Request/Response DTOs)
        ├── service/ (DeviceConfig, SerialConnection, Streaming)
        ├── exception/ (ApiException, GlobalExceptionHandler)
        └── game/ (SequentialTouchGame, GameBeansConfig) <- new
```

---

## Roadmap

- [x] Transport-agnostic serial protocol
- [x] Production-ready game engine with rich features
- [x] Spring Boot backend with REST and SSE
- [x] win/lose/standby/countdown animations
- [x] Sample game SequentialTouchGame
- [ ] Web frontend (React/Vue) for board display and game control
- [ ] Persistence for DeviceConfiguration (DB)
- [ ] Authentication and security (Spring Security)
- [ ] More games (Color Match, Memory, Reaction, ...)
- [ ] Multi-board support
- [ ] Micrometer metrics + Prometheus
- [ ] Docker and Kubernetes deployment

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

A: Yes, all unit tests work without hardware. For integration testing without board, you can build a Mock Transport (example in protocol README).

**Q: How to create a new game?**

A: See "Comprehensive Game Creation Tutorial" in [tileboard-app/README.md](tileboard-app/README.md). Summary: Create a class implementing `Game`, register as `@Bean`, done.

**Q: How do animations work?**

A: See AnimationSystem section in [tileboard-game-engine/README.md](tileboard-game-engine/README.md) and animations section in [tileboard-app/README.md](tileboard-app/README.md)

**Q: How to understand concurrency?**

A: Each README has a "Deep Dive" section explaining all concurrency patterns with full code and explanations.

---

**Version:** 1.0.0  
**Java:** 17+  
**Spring Boot:** 3.3.4  
**Date:** 2026-09-22
