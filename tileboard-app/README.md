# tileboard-app - Comprehensive Spring Boot Backend Documentation

> **Module Mission:** Spring Boot application that drives an LED tile board over serial. Built on top of `tileboard-serial-protocol` and `tileboard-game-engine`. Includes REST API for device configuration, serial port management, game control, and SSE streaming. This module is the bridge between hardware and the web world.

---

## Table of Contents
1. [Overall Architecture and Platform Position](#overall-architecture)
2. [Tech Stack](#tech-stack)
3. [Package Structure](#package-structure)
4. [Configuration - application.yml and TileboardProperties](#configuration)
5. [DeviceConfiguration - Board Geometry](#deviceconfiguration)
6. [SerialGatewayConfig - Hardware Abstraction](#serialgatewayconfig)
7. [Services - Business Layer](#services)
8. [Controllers - REST API](#controllers)
9. [SSE Streaming](#sse-streaming)
10. [GameEngineManager - Spring and Engine Bridge](#gameenginemanager)
11. [Error Handling - GlobalExceptionHandler and i18n](#error-handling)
12. [Step-by-Step Run and API Usage Tutorial](#step-by-step-tutorial)
13. [Comprehensive Game Creation Tutorial - SequentialTouchGame Practical Example](#comprehensive-game-tutorial)
14. [Using win/lose/standby/countdown Animations](#using-animations)
15. [Deep Dive - Concurrency and Complex Logic](#deep-dive)
16. [Tests and Execution](#tests-and-execution)
17. [Full API Reference](#full-api-reference)

---

## Overall Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│  Frontend / Mobile App / curl                                           │
│  HTTP REST + SSE                                                        │
├─────────────────────────────────────────────────────────────────────────┤
│  Controllers (Spring MVC)                                               │
│  ├─ DeviceController: GET/POST /api/v1/device                           │
│  ├─ SerialPortController: GET /api/v1/ports, POST /{role}/assign,       │
│  │                         GET /status, POST /connect, /disconnect      │
│  ├─ GameController: GET /api/v1/games, /sessions CRUD                   │
│  └─ StreamController: GET /api/v1/stream/board[/{sessionId}] (SSE)      │
├─────────────────────────────────────────────────────────────────────────┤
│  Services                                                               │
│  ├─ DeviceConfigurationService (AtomicReference)                        │
│  │   └─ InMemoryDeviceConfigurationService                              │
│  ├─ SerialConnectionManager (synchronized, EnumMap)                     │
│  │   └─ DefaultSerialConnectionManager                                  │
│  ├─ BoardStateBroadcaster (SSE, "board-frame" events)                   │
│  │   └─ SseBoardStateBroadcaster (wired to BoardFrameBroadcaster;       │
│  │      currently no controller exposes it — see SSE section)            │
│  └─ Messages (fixed-fa i18n) + GlobalExceptionHandler                   │
├─────────────────────────────────────────────────────────────────────────┤
│  GameEngineManager (@EventListener, volatile, synchronized)              │
│  ├─ onGatewayConnected → new GameEngineImpl                             │
│  └─ onGatewayDisconnected → close engine                                │
├─────────────────────────────────────────────────────────────────────────┤
│  tileboard-game-engine                                                  │
│  ├─ GameRegistry (auto-registers @Bean Game)                            │
│  ├─ GameEngineImpl, GameSessionImpl, BoardChannel, AnimationSystem      │
│  └─ GameEventBusImpl, SseGameEventPublisher, SessionSnapshot            │
├─────────────────────────────────────────────────────────────────────────┤
│  tileboard-serial-protocol                                              │
│  ├─ TileGatewayClient, DefaultFrameCodec, Board<T>                      │
│  └─ JSerialCommTransport, JSerialCommPortRegistry                       │
├─────────────────────────────────────────────────────────────────────────┤
│  Hardware: LED Tile Board (m x n, max 255 tiles) over Serial (115200)   │
└─────────────────────────────────────────────────────────────────────────┘
```

**Typical Data Flow:**

1. Operator configures device: `POST /api/v1/device {width, height}`
2. Lists serial ports: `GET /api/v1/ports`
3. Assigns ports: `POST /api/v1/ports/OUT/assign {portName}` (+ optionally `IN`)
4. Connects: `POST /api/v1/ports/connect` → `DefaultSerialConnectionManager.connect()` → `TileGatewayClient` created → handshake enabled → `GatewayConnectedEvent` published → `GameEngineManager` creates new `GameEngineImpl` → `client.start()` → `INTRODUCTION`/`SET` sent
5. Lists games: `GET /api/v1/games` (from `GameRegistry` — works even before connect)
6. Starts game: `POST /api/v1/games/sessions {gameId, players:[{name, role}]}` → `GameEngine.startGame()` → `GameSessionImpl` created → `START`/`SET` sent → game's `onStart()` called → `SESSION_STARTED` event
7. Connects SSE: `GET /api/v1/stream/board` (or `/board/{sessionId}`) → `SESSION_LIFECYCLE`/`BOARD_UPDATE`/`TICK`/… events in real time
8. Player touches tiles → `TileGatewayClient` receives `DATA_IN` frame → `EngineFrameRouter` reassembles → `Board<Boolean>` → `TouchFrameRouter` routes to exclusive owner → `GameSessionImpl.handleTileEvent` (records history + reaction speed) → `game.onTileEvent` (both `TOUCH` and `RELEASE` are delivered)
9. Game wins/loses/stops → `finishSession` (exactly once via CAS) → `game.onStop` → board cleared → `STOP`/`SET` sent → `SESSION_FINISHED`/`SESSION_STOPPED` event → engine releases the board

---

## Tech Stack

- **Java 17**, **Spring Boot 3.3.4** (parent), **Spring MVC**, **Spring Actuator** (health, info), **spring-boot-starter-validation**
- **jSerialComm 2.11.0** for serial communication (declared here — it is `optional` in the protocol library, and the app is the module that talks to real hardware)
- **springdoc-openapi 2.6.0** (`springdoc-openapi-starter-webmvc-ui`) — Swagger UI at the springdoc default path
- **Jackson** for JSON (via `spring-boot-starter-web` + engine's `jackson-databind`/`jsr310`)
- **SLF4J** for logging
- **Maven** for build

---

## Package Structure

| Package | Responsibility |
|------|---------|
| `com.tileboard.app` | `TileboardApplication` (main, `@SpringBootApplication` + `@ConfigurationPropertiesScan`) |
| `config` | `TileboardProperties` (`tileboard.serial`), `DeviceConfiguration`, `SerialGatewayConfig`, `GeneralConfiguration` (CORS filter, `@EnableWebMvc`) |
| `controller` | REST controllers: `DeviceController`, `SerialPortController`, `GameController`, `StreamController` |
| `dto` | API DTOs: `ApiResponse`, `ApiResponses`, `Status`, `DeviceConfigurationRequest/Response`, `AssignPortRequest`, `SerialPortResponse`, `ConnectionStatusResponse`, `GameDescriptorResponse`, `StartGameRequest`, `PlayerRequest`, `GameSessionResponse` |
| `service.device` | `DeviceConfigurationService` + `InMemoryDeviceConfigurationService` |
| `service.serial` | `SerialConnectionManager` + `DefaultSerialConnectionManager`, `PortRole`, `PortAssignment`, `ConnectionState`, `SerialPortSummary` |
| `service.streaming` | `BoardStateBroadcaster` + `SseBoardStateBroadcaster` |
| `exception` | `ApiException` + subclasses (`DeviceNotConfiguredException`, `GatewayNotConnectedException`, `NoActiveGameException`, `PortsNotAssignedException`, `SerialPortOperationException`) + `handler.GlobalExceptionHandler` |
| `i18n` | `Messages` (fixed-`fa` `MessageSource` wrapper) |
| `game` | Sample game: `SequentialTouchGame` (default 3×3) + `GameBeansConfig` (`@Bean` registration) |

---

## Configuration

### application.yml

```yaml
spring:
  application:
    name: tileboard-game-engine   # NOTE: actual value in this repo (historical name)

server:
  port: 8080

tileboard:
  serial:
    baud-rate: 115200
    data-bits: 8
    stop-bits: 1
    read-timeout-millis: 50
    write-timeout-millis: 50
    handshake-min-sequence: 0  # 0 = auto (max(2, min(width,height)))
  engine:
    tick-interval: 100ms
    session-ttl: 30m           # overrides the engine default of 1h
    frame-reassembly-timeout: 500ms
    event-bus-queue-capacity: 256
    touch-history-max-size: 2000

management:
  endpoints:
    web:
      exposure:
        include: health,info

logging:
  level:
    com.tileboard: DEBUG
```

### application-prod.yml

```yaml
# Activated with --spring.profiles.active=prod (or SPRING_PROFILES_ACTIVE=prod)
logging:
  level:
    root: INFO
    com.tileboard: INFO
```

**Why DEBUG in dev?** Because `JSerialCommTransport` logs TX/RX bytes in hex at DEBUG level, useful for protocol debugging but noisy in production.

**Locale note:** user-facing messages are always Persian because `Messages.APP_LOCALE` is hardcoded to `fa` in code. There is intentionally no `spring.mvc.locale*` setting in `application.yml` — the locale is fixed in one place (`Messages`), not via Spring's locale resolver.

### TileboardProperties

```java
@ConfigurationProperties(prefix = "tileboard.serial")
public record TileboardProperties(
    int baudRate, int dataBits, int stopBits,
    int readTimeoutMillis, int writeTimeoutMillis,
    int handshakeMinSequence) {

    public TileboardProperties {
        if (baudRate <= 0) baudRate = 115_200;
        if (dataBits <= 0) dataBits = 8;
        if (stopBits <= 0) stopBits = 1;
        if (readTimeoutMillis <= 0) readTimeoutMillis = 50;
        if (writeTimeoutMillis <= 0) writeTimeoutMillis = 50;
        if (handshakeMinSequence < 0) handshakeMinSequence = 0;
    }
}
```

- `record` with compact constructor for defaults (`0`/negative → sensible default; `handshakeMinSequence = 0` means "auto").
- Enabled via `@ConfigurationPropertiesScan` in `TileboardApplication` (no `@EnableConfigurationProperties` needed).

---

## DeviceConfiguration

```java
public record DeviceConfiguration(int width, int height) {
    public DeviceConfiguration {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException(...);
        if (width * height > 255) throw new IllegalArgumentException("width * height must be <= 255 (protocol addressing limit), ...");
    }
    public int tileCount() { return width * height; }
}
```

- Physical geometry of board: how many tiles wide and tall.
- Limit 255 comes from `DeviceAddress` encoding the total tile count in one byte (protocol ceiling: both `totalTiles` and `tilesPerRow` must fit in `[1, 255]`).
- This is the one piece of information every other module (handshake `max(2, min(w,h))`, game engine board size) needs before doing anything useful.
- DTO validation mirrors it: `DeviceConfigurationRequest(width, height)` with `@Min(1)`/`@Max(255)` on both fields.

---

## SerialGatewayConfig

```java
@Configuration
public class SerialGatewayConfig {
    @Bean
    public SerialPortRegistry serialPortRegistry() {
        return new JSerialCommPortRegistry();
    }
}
```

**This is the only place in the whole app that knows `JSerialCommPortRegistry` is used.** If you want to swap serial library (or build a Mock for a hardware-less demo), you only change this Bean. The rest of the code only knows the `SerialPortRegistry`/`SerialTransport` interfaces.

---

## Services

### DeviceConfigurationService

```java
public interface DeviceConfigurationService {
    Optional<DeviceConfiguration> current();
    DeviceConfiguration configure(int width, int height);
    default boolean isConfigured() { return current().isPresent(); }
}

@Service
public class InMemoryDeviceConfigurationService implements DeviceConfigurationService {
    private final AtomicReference<DeviceConfiguration> configuration = new AtomicReference<>();

    @Override public Optional<DeviceConfiguration> current() { return Optional.ofNullable(configuration.get()); }

    @Override
    public DeviceConfiguration configure(int width, int height) {
        DeviceConfiguration updated = new DeviceConfiguration(width, height);
        configuration.set(updated);
        return updated;
    }
}
```

- `AtomicReference` → thread-safe without synchronized, because it holds just one value.
- `Optional` for "not yet configured" state.
- TODO in code: replace with a DB-backed implementation later — all consumers only know the interface.

### SerialConnectionManager

```java
public interface SerialConnectionManager {
    List<SerialPortSummary> listAvailablePorts();
    void assign(PortRole role, String portName);
    PortAssignment currentAssignment();
    ConnectionState connectionState();
    void connect();     // no-op if already connected; throws PortsNotAssignedException without OUT
    void disconnect();  // publishes GatewayDisconnectedEvent
}

public enum PortRole { IN, OUT }
public enum ConnectionState { DISCONNECTED, CONNECTED }
public record PortAssignment(Optional<String> inPort, Optional<String> outPort) {
    public static PortAssignment empty() { ... }
    public boolean isOutAssigned() { return outPort.isPresent(); }
}
public record SerialPortSummary(String systemName, String description) {}
```

#### DefaultSerialConnectionManager - Implementation

```java
@Service
public class DefaultSerialConnectionManager implements SerialConnectionManager {
    private final SerialPortRegistry portRegistry;
    private final TileboardProperties properties;
    private final DeviceConfigurationService deviceConfigurationService;
    private final ApplicationEventPublisher eventPublisher;

    private final Map<PortRole, String> assignedPorts = new EnumMap<>(PortRole.class);
    private final Map<PortRole, SerialTransport> openTransports = new EnumMap<>(PortRole.class);
    private TileGatewayClient client;

    @Override public List<SerialPortSummary> listAvailablePorts() {
        return portRegistry.listPorts().stream()
            .map(SerialPortInfo::systemName).distinct()
            .map(name -> new SerialPortSummary(name, describe(name)))
            .toList();
    }

    @Override public synchronized void assign(PortRole role, String portName) {
        assignedPorts.put(role, portName);
    }

    @Override public synchronized PortAssignment currentAssignment() { ... }
    @Override public synchronized ConnectionState connectionState() {
        return client != null ? CONNECTED : DISCONNECTED;
    }

    @Override public synchronized void connect() {
        if (client != null) return; // idempotent
        if (deviceConfigurationService.current().isEmpty()) {
            log.info("Device not Configured - can not connect."); // logged, NOT thrown here
        }
        String outPort = assignedPorts.get(OUT);
        String inPort = assignedPorts.get(IN);
        if (outPort == null) throw new PortsNotAssignedException();

        SerialPortConfig config = SerialPortConfig.builder()
            .baudRate(properties.baudRate()).dataBits(...).stopBits(...)
            .parity(Parity.NONE)
            .readTimeoutMillis(...).writeTimeoutMillis(...).build();

        Map<PortRole, SerialTransport> openedThisAttempt = new EnumMap<>(PortRole.class);
        TileGatewayClient newClient;
        boolean success = false;
        try {
            TileGatewayClient.Builder builder = TileGatewayClient.builder();
            if (inPort != null && inPort.equals(outPort)) {
                SerialTransport shared = openPort(outPort, config);
                openedThisAttempt.put(OUT, shared);
                builder.transport(shared);
            } else {
                SerialTransport outTransport = openPort(outPort, config);
                openedThisAttempt.put(OUT, outTransport);
                builder.outputTransport(outTransport);
                if (inPort != null) {
                    SerialTransport inTransport = openPort(inPort, config);
                    openedThisAttempt.put(IN, inTransport);
                    builder.inputTransport(inTransport);
                } else {
                    log.warn("No IN port assigned - connecting OUTPUT ONLY ...");
                }
            }
            newClient = builder.build();
            enableHandshakeIfDeviceKnown(newClient);
            success = true;
        } finally {
            if (!success) closeQuietly(openedThisAttempt.values());
        }

        openTransports.putAll(openedThisAttempt);
        this.client = newClient;
        // NOTE: .get() below throws NoSuchElementException when no device is configured,
        // so in practice the device MUST be configured before connect:
        eventPublisher.publishEvent(new GatewayConnectedEvent(newClient,
            deviceConfigurationService.current().get().width(),
            deviceConfigurationService.current().get().height()));
        newClient.start();
        log.info("Tile board gateway connected (input={}, output={})", inPort, outPort);
        try {
            DeviceConfiguration device = deviceConfigurationService.current().get();
            newClient.send(Command.INTRODUCTION, CommandType.SET);
            log.info("sent INTRODUCTION to hardware ({}X{} board)", device.width(), device.height());
        } catch (RuntimeException e) {
            log.warn("failed to send INTRODUCTION command (gateway may have closed)");
        }
    }

    private void enableHandshakeIfDeviceKnown(TileGatewayClient gatewayClient) {
        deviceConfigurationService.current().ifPresentOrElse(
            device -> {
                int minimumSequence = properties.handshakeMinSequence() > 0
                    ? properties.handshakeMinSequence()
                    : Math.max(2, Math.min(device.width(), device.height()));
                log.info("Enabling id handshake for a {}x{} board (minimumSequence={})", ...);
                gatewayClient.enableIdHandshake(
                    () -> DeviceAddress.forBoard(device.width(), device.height()),
                    new SequentialIdSequenceValidator(minimumSequence));
            },
            () -> log.warn("Connecting without a device configuration - the id handshake will not ..."));
    }

    @Override public synchronized void disconnect() {
        if (client == null) return; // idempotent
        try {
            try { client.send(Command.STOP, CommandType.SET); log.info("sent STOP to hardware on disconnected."); }
            catch (RuntimeException e) { log.warn("Failed to sned STOP on disconnected: {}", e.getMessage()); }
            client.close();
        } finally {
            client = null;
            openTransports.clear();
            log.info("Tile board gateway disconnected");
            eventPublisher.publishEvent(new GatewayDisconnectedEvent());
        }
    }
}
```

**Behavior notes (exactly as coded):**

1. **synchronized on mutating methods:** `assign`, `currentAssignment`, `connectionState`, `connect`, `disconnect` are all `synchronized`. These are operator-driven admin operations, so a plain monitor is enough.
2. **EnumMap:** for `assignedPorts` and `openTransports` — array-backed, optimal for enum keys.
3. **Two topologies transparently:**
   - IN and OUT same name → one shared `SerialTransport` opened once, `builder.transport(shared)` (full-duplex).
   - Different names → two transports. Only OUT → loud warning that the client is OUTPUT ONLY (no touches/handshake will ever be received).
4. **Rollback on failed connect:** `openedThisAttempt` + `success` flag + `finally { if (!success) closeQuietly(...) }` — a failed `connect` never leaks an OS port handle that would break the next attempt. `openPort` wraps any failure in `SerialPortOperationException` (HTTP 502).
5. **Handshake before start:** `enableHandshakeIfDeviceKnown(newClient)` runs before `newClient.start()`, so the board's initial `ID`/`CLEAR` frames are never dropped. Auto `minimumSequence = max(2, min(width, height))`.
6. **Device must be configured first:** when unconfigured, `connect()` only *logs* at the top — but then `deviceConfigurationService.current().get()` at event-publish time throws `NoSuchElementException`. So the practical rule is: **configure the device before connecting** (the tutorial below does exactly that).
7. **Hardware protocol on (dis)connect:** `INTRODUCTION`/`SET` is sent after connect; `STOP`/`SET` is sent (best-effort) before disconnect. Note the exact log shapes: `Tile board gateway connected (input=…, output=…)` and `sent INTRODUCTION to hardware ({}X{} board)`.
8. **Event publishing:** `GatewayConnectedEvent(client, width, height)` wakes `GameEngineManager`; `GatewayDisconnectedEvent` unbinds it. Both event types live in the engine module to avoid a circular dependency.

### BoardStateBroadcaster (SSE board frames)

```java
public interface BoardStateBroadcaster {
    SseEmitter subscribe();                 // registers a subscriber, returns its emitter
    void broadcast(byte[] flatBoardBytes);   // row-major frame (Board.toWireBytes) to every subscriber
}

@Service
public class SseBoardStateBroadcaster implements BoardStateBroadcaster {
    private static final String EVENT_NAME = "board-frame";
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseBoardStateBroadcaster(BoardFrameBroadcaster boardFrameBroadcaster) {
        // every actually-transmitted board frame is fanned out automatically:
        boardFrameBroadcaster.subscribe((sessionId, board) ->
            broadcast(board.toWireBytes(ColorTileCodec.instance())));
    }

    @Override public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(ex -> emitters.remove(emitter));
        emitters.add(emitter);
        return emitter;
    }

    @Override public void broadcast(byte[] flatBoardBytes) {
        if (emitters.isEmpty()) return;
        int[] unsignedTiles = toUnsignedInts(flatBoardBytes); // byte -> 0..255 int
        for (SseEmitter emitter : emitters) {
            try { emitter.send(SseEmitter.event().name("board-frame").data(unsignedTiles)); }
            catch (IOException | RuntimeException e) { emitters.remove(emitter); } // drop dead clients
        }
    }
}
```

Important wiring fact: this broadcaster is fully functional (subscribed to the application-lifetime `BoardFrameBroadcaster`), but **no controller currently injects it** — `StreamController` serves game events via `SseGameEventPublisher` instead. It is the intended seam for a future raw-board-mirror endpoint (SSE today, WebSocket tomorrow) without touching the engine.

### ApiResponse envelope

```java
public record ApiResponse(Status status, String message, Object data, Object extra, String debugMessage) { ... }
public enum Status { SUCCESS, INFO, WARNING, ERROR }
```

- `message`: localized (Persian) user-facing text.
- `debugMessage`: raw English diagnostic for developers (logs, dev tools, bug reports) — never shown directly to end users; `null` on plain successes.
- `extra`: optional third payload slot (currently unused by controllers).
- `ApiResponses` factory: `ok(...)`, `info(...)`, `warning(...)`, `error(...)`, `badRequest`, `unauthorized`, `forbidden`, `notFound`, `conflict`, `internalServerError`, `badGateway` (each with an optional `debugMessage` overload).

---

## Controllers

All controllers return the `ApiResponse` envelope — **except** `GET /api/v1/games/sessions/{sessionId}`, which returns a raw `GameSessionResponse`.

### DeviceController — `/api/v1/device`

```
GET  /api/v1/device
→ 200 {status:SUCCESS, message:"Device successfully configured.", data:{width, height, tileCount}}
→ 409 when nothing configured yet (DeviceNotConfiguredException)

POST /api/v1/device
Body: { "width": 3, "height": 3 }   (@Min(1) @Max(255) on both — 400 on violation)
→ 200 {status:SUCCESS, message:"Device successfully configured.", data:{width, height, tileCount}}
```

Note the path is singular `/api/v1/device` (not `/devices/...`), with the operation on the bare resource (no `/configure` suffix).

### SerialPortController — `/api/v1/ports` (produces JSON)

```
GET  /api/v1/ports
→ 200 {status:SUCCESS, message:"<N> ports are available",
       data:[{systemName:"COM3", description:"USB Serial Port"}, ...]}

POST /api/v1/ports/{role}/assign        # role is a PATH variable: IN or OUT (invalid → 400 type-mismatch)
Body: { "portName": "COM3" }            # only portName; @NotBlank
→ 200 {status:SUCCESS}                  # empty success, no echo of the assignment

GET  /api/v1/ports/status
→ 200 {status:SUCCESS, data:{state:"CONNECTED"|"DISCONNECTED", inPort:"COM3"|null, outPort:"COM3"|null}}

POST /api/v1/ports/connect
→ 200 (same ConnectionStatusResponse body as /status)
→ 409 ports.not_assigned when OUT was never assigned

POST /api/v1/ports/disconnect
→ 200 (same ConnectionStatusResponse body as /status; idempotent)
```

There is **no** `GET /ports/assignment` endpoint and no `POST /ports/assign` with a `role` body field — assignment is `POST /ports/{role}/assign` with `{portName}` only. `disconnect` returns `200` with the status body (not `204`).

### GameController — `/api/v1/games` (produces JSON)

```
GET  /api/v1/games
→ 200 {status:SUCCESS, data:[{gameId:"sequential-touch", displayName:"Sequential Touch Challenge",
     category:"TUTORIAL", description:"...", requiredWidth:3, requiredHeight:3, minPlayers:1, maxPlayers:1}]}
# Works even before the board is connected (served from GameRegistry).

POST /api/v1/games/sessions
Body: { "gameId": "sequential-touch",
        "players": [{ "name": "Ali", "role": "SOLO" }] }  # role is REQUIRED (@NotNull)
→ 200 {status:SUCCESS, data:{sessionId:"uuid", gameId:"sequential-touch", status:"RUNNING"}}
→ 409 engine.not_ready when no gateway is connected (EngineNotReadyException via require())
→ 404 game.not_found for unknown gameId; 409 for player-count/board-size/board-busy violations

GET  /api/v1/games/sessions
→ 200 {status:SUCCESS, data:[{sessionId, gameId, status}, ...]}
# Empty list (not an error) when the engine is disconnected.

GET  /api/v1/games/sessions/{sessionId}
→ 200 {sessionId, gameId, status}     # RAW body, NOT wrapped in ApiResponse
→ 409 game.no_active when missing (NoActiveGameException — note: 409, not 404)

POST /api/v1/games/sessions/{sessionId}/stop
→ 204 No Content
→ 409 when the session doesn't exist; 409 engine.not_ready when disconnected
```

- `PlayerRequest(name, role)`: `name` `@NotBlank`, `role` `@NotNull PlayerRole`. Valid roles: `SOLO, PLAYER_ONE, PLAYER_TWO, TEAM_A, TEAM_B, SPECTATOR`.
- `GameSessionResponse` carries only `(sessionId, gameId, status)` — scores/players are observed via SSE `SessionSnapshot`, not via this DTO.

### StreamController — `/api/v1/stream` (SSE)

```
GET /api/v1/stream/board                 (Accept: text/event-stream) → all sessions' events
GET /api/v1/stream/board/{sessionId}     (Accept: text/event-stream) → one session's events
```

Both delegate to `SseGameEventPublisher` (`global()` / `forSession(sessionId)`). There is **no** `/api/v1/games/events` endpoint. See the SSE section for event names and payload shape.

---

## SSE Streaming

Two SSE mechanisms exist in the codebase:

**1. Game events (exposed via `StreamController`, powered by the engine).**
- Endpoints: `GET /api/v1/stream/board`, `GET /api/v1/stream/board/{sessionId}`.
- Infinite-timeout `SseEmitter`, per-client bus subscription (capacity 32, `DROP_OLDEST`), `: ping` heartbeat comment every 15 s.
- Each event: SSE event *name* = `SseGameEventType`, `data` = JSON `SseGameEvent(sessionId, gameId, type, data: SessionSnapshot, timestamp)`:

| SSE event name | Triggered by (internal type) |
|------|------|
| `SESSION_LIFECYCLE` | `SESSION_STARTED`, `SESSION_FINISHED`, `SESSION_STOPPED` |
| `BOARD_UPDATE` | `BOARD_UPDATED` (every `publishBoard`/`setTile`/`fillBoard`) |
| `TICK` | `TICK` (every `tickInterval` while `RUNNING`) |
| `SCORE_UPDATE` | `SCORE_CHANGED` (reserved — not emitted by any feature today) |
| `GAME_STATE` / `CUSTOM` | fallback / custom |

```javascript
const es = new EventSource('/api/v1/stream/board');
es.addEventListener('BOARD_UPDATE', e => console.log(JSON.parse(e.data)));       // SseGameEvent JSON
es.addEventListener('SESSION_LIFECYCLE', e => { console.log(JSON.parse(e.data)); es.close(); });
```

**2. Raw board frames (`SseBoardStateBroadcaster`, currently unexposed).**
- `subscribe()` returns a `Long.MAX_VALUE`-timeout emitter; `broadcast(byte[])` sends SSE event `board-frame` with an `int[]` of unsigned tile wire codes.
- It already receives every actually-transmitted frame via `BoardFrameBroadcaster`, but no controller injects it yet — the seam is ready for a future board-mirror endpoint.

---

## GameEngineManager

Lives in `tileboard-game-engine` (`com.tileboard.engine.spring`), but its behavior is what makes the app's game endpoints work:

- `volatile GameEngineImpl engine`, `synchronized` `@EventListener` methods for `GatewayConnectedEvent`/`GatewayDisconnectedEvent` (event types also live in the engine module, so app and engine share one definition).
- `current(): Optional<GameEngine>`, `require(): GameEngine` (throws `EngineNotReadyException` → HTTP 409 when no gateway is connected).
- `shutdownCurrentEngine()` is null-safe/idempotent and nulls `engine` **before** the slow `close()`, so no thread ever observes a half-closed engine.
- `@PreDestroy shutdownOnContextClose()` releases everything when Spring stops.
- Engine tuning comes from `TileboardEngineProperties` (`tileboard.engine.*` in `application.yml`): tick 100 ms, TTL 30 m, reassembly 500 ms, bus capacity 256, touch-history 2000.

---

## Error Handling

```java
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler {
    private final Messages messages; // fixed-fa MessageSource wrapper

    private ResponseEntity<ApiResponse> respond(HttpStatus status, LocalizableException ex) {
        String localized = messages.resolve(ex.errorCode(), ex.args(), ex.getMessage());
        return ApiResponses.error(localized, ex.getMessage(), status);
    }

    @ExceptionHandler(ApiException.class) → respond(ex.status(), ex)               // status pinned on the exception
    @ExceptionHandler(EngineNotReadyException.class) → 409
    @ExceptionHandler(GameNotFoundException.class) → 404
    @ExceptionHandler(GameSessionException.class) → 409
    @ExceptionHandler(GameEngineException.class) → 502                            // catch-all for other engine failures
    @ExceptionHandler({SerialTransportException, ProtocolException, BoardException}) → 502
    @ExceptionHandler(MethodArgumentNotValidException.class) → 400                // "validation.failed (field: msg; ...)"
    @ExceptionHandler(MethodArgumentTypeMismatchException.class) → 400            // e.g. bad {role} path variable
    @ExceptionHandler(HttpMessageNotReadableException.class) → 400                // empty/malformed JSON
    @ExceptionHandler(IllegalArgumentException.class) → 400
    @ExceptionHandler(AsyncRequestTimeoutException.class) → void (debug log)       // SSE timeouts
    @ExceptionHandler(AsyncRequestNotUsableException.class) → void (debug log)     // SSE client gone
    @ExceptionHandler(Exception.class) → 500 (null when response already committed, e.g. mid-SSE)
}
```

Actual status mapping for the app's own exceptions (each pins its status in its constructor):

| Exception | Status | errorCode |
|------|------|------|
| `DeviceNotConfiguredException` | 409 CONFLICT | `device.not_configured` |
| `GatewayNotConnectedException` | 409 CONFLICT | `gateway.not_connected` (defined but never thrown today — readiness is signaled by `EngineNotReadyException`) |
| `NoActiveGameException` | 409 CONFLICT | `game.no_active` (note: 409, not 404) |
| `PortsNotAssignedException` | 409 CONFLICT | `ports.not_assigned` |
| `SerialPortOperationException` | 502 BAD_GATEWAY | passed through, e.g. `serial.port_operation_failed` |

**i18n:** every error body is `{status: ERROR, message: <Persian>, data: null, extra: null, debugMessage: <raw English>}`. `message` is resolved from `messages_fa.properties` (fallback `messages.properties`, identical content) via `Messages.resolve(errorCode, args, fallback)` — a missing key degrades to the raw English message instead of a 500. Bean-validation messages use `{key}` placeholders resolved against the same catalog.

---

## Step-by-Step Tutorial

### Prerequisites

- Java 17+, Maven 3.8+
- Tileboard board connected via USB (or a Mock `SerialTransport` for hardware-less tests — unit tests need no hardware)

### Step 1: Build

```bash
git clone <repo>
cd tileboard-platform
mvn clean install -DskipTests
```

### Step 2: Run

```bash
cd tileboard-app
mvn spring-boot:run
# or
java -jar target/tileboard-app-1.0.0.jar

# with prod profile:
java -jar target/tileboard-app-1.0.0.jar --spring.profiles.active=prod
```

App runs on `http://localhost:8080`.

- Swagger UI: springdoc default (starter `2.6.0` is on the classpath)
- Actuator: `http://localhost:8080/actuator/health` (only `health,info` are exposed)

### Step 3: Configure Device

```bash
curl -X POST http://localhost:8080/api/v1/device \
  -H "Content-Type: application/json" \
  -d '{"width":3,"height":3}'
```

Response:
```json
{
  "status": "SUCCESS",
  "message": "Device successfully configured.",
  "data": { "width": 3, "height": 3, "tileCount": 9 },
  "extra": null,
  "debugMessage": null
}
```

Current configuration is readable at any time via `GET /api/v1/device` (409 before the first configure).

### Step 4: List Ports

```bash
curl http://localhost:8080/api/v1/ports
```

Response:
```json
{
  "status": "SUCCESS",
  "message": "2 ports are available",
  "data": [
    { "systemName": "COM3", "description": "USB Serial Port" },
    { "systemName": "COM4", "description": "USB Serial Port" }
  ]
}
```

### Step 5: Assign Ports

Role is a **path variable** (`IN`/`OUT`), the body carries only `portName`. For a single full-duplex port (common case), assign the same port to both roles:

```bash
curl -X POST http://localhost:8080/api/v1/ports/OUT/assign \
  -H "Content-Type: application/json" \
  -d '{"portName":"COM3"}'

curl -X POST http://localhost:8080/api/v1/ports/IN/assign \
  -H "Content-Type: application/json" \
  -d '{"portName":"COM3"}'
```

Two half-duplex adapters:

```bash
curl -X POST http://localhost:8080/api/v1/ports/OUT/assign -H "Content-Type: application/json" -d '{"portName":"COM3"}'
curl -X POST http://localhost:8080/api/v1/ports/IN/assign -H "Content-Type: application/json" -d '{"portName":"COM4"}'
```

### Step 6: Connect

```bash
curl -X POST http://localhost:8080/api/v1/ports/connect
```

Response:
```json
{
  "status": "SUCCESS",
  "data": { "state": "CONNECTED", "inPort": "COM3", "outPort": "COM3" }
}
```

Logs should show (for a 3×3 device):
```
Enabling id handshake for a 3x3 board (minimumSequence=3)
Tile board gateway connected (input=COM3, output=COM3)
sent INTRODUCTION to hardware (3X3 board)
Game engine bound to the newly connected tile gateway (3x3)
```

(`minimumSequence` is `max(2, min(3,3)) = 3` in auto mode. Connection state is also readable any time via `GET /api/v1/ports/status`.)

### Step 7: List Games

```bash
curl http://localhost:8080/api/v1/games
```

Response:
```json
{
  "status": "SUCCESS",
  "data": [
    {
      "gameId": "sequential-touch",
      "displayName": "Sequential Touch Challenge",
      "category": "TUTORIAL",
      "description": "Tiles light up sequentially; touch it to score and advance to next tile.",
      "requiredWidth": 3,
      "requiredHeight": 3,
      "minPlayers": 1,
      "maxPlayers": 1
    }
  ]
}
```

(The `requiredWidth/Height` mirror the device configuration active at startup, defaulting to 3×3 — see `GameBeansConfig`.)

### Step 8: Start Game

`role` is required (`SOLO` for single-player):

```bash
curl -X POST http://localhost:8080/api/v1/games/sessions \
  -H "Content-Type: application/json" \
  -d '{
    "gameId": "sequential-touch",
    "players": [{"name":"Ali","role":"SOLO"}]
  }'
```

Response:
```json
{
  "status": "SUCCESS",
  "data": { "sessionId": "a1b2c3d4-...", "gameId": "sequential-touch", "status": "RUNNING" }
}
```

At this moment on the board:
1. Standby animation (BREATHING) — 2 seconds
2. Countdown animation (3→2→1 + green blink) — ~3.9 s at 1000 ms/digit
3. First tile lights up (e.g., (0,0) red)

### Step 9: SSE - Real-time Events

In another terminal:

```bash
curl -N -H "Accept: text/event-stream" http://localhost:8080/api/v1/stream/board
# or for one session:
curl -N -H "Accept: text/event-stream" http://localhost:8080/api/v1/stream/board/{sessionId}
```

Or with JS in the browser:

```javascript
const eventSource = new EventSource('/api/v1/stream/board');
eventSource.addEventListener('BOARD_UPDATE', e => {
  const sseEvent = JSON.parse(e.data); // {sessionId, gameId, type, data: SessionSnapshot, timestamp}
  console.log('Board updated:', sseEvent.data.board);
});
eventSource.addEventListener('SESSION_LIFECYCLE', e => {
  console.log('Lifecycle:', JSON.parse(e.data));
  eventSource.close();
});
```

### Step 10: Play (3×3 = 9 tiles)

- Touch the lit tile → +10 points, tile off, next tile lights
- If a wrong tile is touched → FADE_TO_RED short animation, then the correct tile re-lights (RELEASE events are ignored)
- If 90 seconds pass → DESCENDING_CURTAIN animation and loss
- If all 9 tiles are touched in order → RADIAL_BURST animation and win

### Step 11: Stop Game / Inspect Session

```bash
curl http://localhost:8080/api/v1/games/sessions/{sessionId}   # raw {sessionId, gameId, status}
curl -X POST http://localhost:8080/api/v1/games/sessions/{sessionId}/stop  # 204
```

### Step 12: Disconnect

```bash
curl -X POST http://localhost:8080/api/v1/ports/disconnect   # 200 + status body (sends STOP first)
```

---

## Comprehensive Game Tutorial

This section explains step-by-step how **SequentialTouchGame** (default 3×3, device-sized via `GameBeansConfig`) is built, including all animations. It quotes the actual code in `src/main/java/com/tileboard/app/game/`.

### Game Scenario

> Each tile lights up sequentially with a color; as soon as it is touched, the player gets points and the next tile's turn comes, until all tiles are lit and touched, then the game ends. Also use lose, win, stand-by animations in the game, and before game start use a countDown animation.

### Step 1: Create Game Class

File: `src/main/java/com/tileboard/app/game/SequentialTouchGame.java`

```java
public class SequentialTouchGame implements Game {

    private static final String KEY_POSITIONS = "sequential.positions";
    private static final String KEY_INDEX = "sequential.index";
    private static final String KEY_TOTAL = "sequential.total";

    private static final TileColor[] PALETTE = {
        TileColor.RED, TileColor.GREEN, TileColor.BLUE,
        TileColor.YELLOW, TileColor.PINK, TileColor.LIGHT_BLUE, TileColor.WHITE
    };

    private final GameDescriptor descriptor;   // the ONLY field — the game is stateless otherwise

    public SequentialTouchGame() {
        this.descriptor = GameDescriptor.builder("sequential-touch", "Sequential Touch Challenge")
            .category("TUTORIAL")
            .description("Tiles light up sequentially; touch it to score and advance. Includes countdown, standby, win and lose animations.")
            .boardSize(3, 3)   // default 3x3
            .players(1, 1)
            .build();
    }

    public SequentialTouchGame(int width, int height) {
        this.descriptor = GameDescriptor.builder("sequential-touch", "Sequential Touch Challenge")
            .category("TUTORIAL")
            .description("Tiles light up sequentially; touch it to score and advance to next tile.")
            .boardSize(width, height)
            .players(1, 1)
            .build();
    }

    @Override public GameDescriptor descriptor() { return descriptor; }
```

### Step 2: Implement onStart - Including standby and countdown

```java
    @Override
    public void onStart(GameContext ctx) {
        ctx.fillBoard(TileColor.OFF);
        ctx.scores().resetAll();
        ctx.state().clear();

        // 1. Standby animation: BREATHING for 2 seconds (infinite until cancelled)
        try {
            ctx.animations().playStandbyAnimation(AnimationSystem.StandbyAnimationType.BREATHING)
                .get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            ctx.animations().cancelCurrent();
        }

        // 2. Countdown animation: 3 -> 2 -> 1 -> green blink (1000 ms per digit)
        try {
            ctx.animations().playCountdown(1000).join();
        } catch (Exception e) {
            log.warn("[{}] Countdown interrupted", ctx.sessionId(), e);
        }

        // 3. List all positions row-major
        List<Position> allPositions = new ArrayList<>();
        for (int r = 0; r < ctx.boardHeight(); r++)
            for (int c = 0; c < ctx.boardWidth(); c++)
                allPositions.add(new Position(r, c));

        ctx.state().put(KEY_POSITIONS, allPositions);
        ctx.state().put(KEY_INDEX, 0);
        ctx.state().put(KEY_TOTAL, allPositions.size());

        // 4. Global timer: 90 seconds, then lose (checked every tick by runTick -> checkExpiry)
        ctx.timer().startCountdown(Duration.ofSeconds(90), () -> {
            ctx.animations().playLoseAnimation(AnimationSystem.LoseAnimationType.DESCENDING_CURTAIN)
                .thenRun(() -> ctx.loseSession());
        });

        // 5. Light first tile
        lightCurrentTile(ctx);
    }

    private void lightCurrentTile(GameContext ctx) {
        @SuppressWarnings("unchecked")
        List<Position> positions = ctx.state().get(KEY_POSITIONS, List.class).orElse(List.of());
        int index = ctx.state().getOrDefault(KEY_INDEX, Integer.class, 0);
        if (index < 0 || index >= positions.size()) return;

        Position pos = positions.get(index);
        TileColor color = PALETTE[index % PALETTE.length];
        ctx.setTile(pos.row(), pos.col(), color);   // BoardChannel: stateLock + gatewayWriteLock
    }
```

**Concurrency notes in onStart:**

- `onStart` runs on the thread calling `startGame` (usually the HTTP request thread), so the blocking `get(2, SECONDS)` and `join()` do not stall the tick thread.
- `ctx.state()` is `GameState` (all methods `synchronized`) → thread-safe.
- `ctx.animations()` runs a single animation at a time with generation-based cancellation.
- `ctx.timer()` is `GameTimer` (`volatile` + `AtomicReference`/`AtomicBoolean`, exactly-once expiry).

### Step 3: Implement onTileEvent - Core Game Logic

```java
    @Override
    public void onTileEvent(GameContext ctx, TileEvent event) {
        // Only called while RUNNING (checked in GameSessionImpl.handleTileEvent),
        // which already recorded touchHistory + reactionSpeed.

        @SuppressWarnings("unchecked")
        List<Position> positions = ctx.state().get(KEY_POSITIONS, List.class).orElse(List.of());
        int currentIndex = ctx.state().getOrDefault(KEY_INDEX, Integer.class, 0);
        if (positions.isEmpty() || currentIndex >= positions.size()) return; // already finished

        Position expected = positions.get(currentIndex);
        Position touched = null;
        if (event.type() == TileEventType.TOUCH || event.type() == TileEventType.HOLD) {
            touched = event.position();   // RELEASE events are ignored (touched stays null)
        }

        if (Objects.nonNull(touched) && touched.equals(expected)) {
            handleCorrectTouch(ctx, currentIndex, positions);
        } else if (Objects.nonNull(touched)) {
            handleWrongTouch(ctx);
        }
    }

    private void handleCorrectTouch(GameContext ctx, int currentIndex, List<Position> positions) {
        String playerId = ctx.players().get(0).id();
        int newScore = ctx.scores().add(playerId, 10);   // ConcurrentHashMap + AtomicInteger

        Position justTouched = positions.get(currentIndex);
        ctx.setTile(justTouched.row(), justTouched.col(), TileColor.OFF);

        int nextIndex = currentIndex + 1;
        ctx.state().put(KEY_INDEX, nextIndex);

        if (nextIndex >= positions.size()) handleWin(ctx);
        else lightCurrentTile(ctx);
    }

    private void handleWrongTouch(GameContext ctx) {
        ctx.animations().playLoseAnimation(AnimationSystem.LoseAnimationType.FADE_TO_RED)
            .thenRun(() -> lightCurrentTile(ctx));   // thenRun runs on the animation thread; setTile is thread-safe
    }

    private void handleWin(GameContext ctx) {
        ctx.timer().stop();
        ctx.animations().playWinAnimation(AnimationSystem.WinAnimationType.RADIAL_BURST)
            .thenRun(() -> ctx.winSession(ctx.players()));  // finishSession CAS runs exactly once
    }
```

### Step 4: Implement onStop and onError

```java
    @Override
    public void onStop(GameContext ctx, GameResult result) {
        // GameResult fields: sessionId, gameId, finalStatus, winners, scoreByPlayerId, duration, finishedAt
        log.info("[{}] SequentialTouchGame onStop - status={}, scores={}",
            ctx.sessionId(), result.finalStatus(), result.scoreByPlayerId().get(0));
        try {
            ctx.fillBoard(TileColor.OFF);
        } catch (Exception e) {
            log.warn("[{}] Could not clear board on stop (gateway may be disconnected)", ctx.sessionId());
        }
        ctx.animations().cancelCurrent();
    }

    @Override
    public void onError(GameContext ctx, Throwable error) {
        log.error("[{}] Game error", ctx.sessionId(), error);
        try {
            ctx.animations().playLoseAnimation(AnimationSystem.LoseAnimationType.PULSE_RED)
                .get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[{}] Lose animation interrupted on error", ctx.sessionId());
        } finally {
            ctx.stopSession();
        }
    }
}
```

### Step 5: Register as Spring Bean

File: `src/main/java/com/tileboard/app/game/GameBeansConfig.java`

```java
@Configuration
public class GameBeansConfig {

    private final DeviceConfigurationService deviceConfigService;

    public GameBeansConfig(DeviceConfigurationService deviceConfigService) {
        this.deviceConfigService = deviceConfigService;
    }

    @Bean
    public Game sequentialTouchGame() {
        int width = 3, height = 3;   // default when no device configured yet
        var current = deviceConfigService.current();
        if (current.isPresent()) {
            DeviceConfiguration cfg = current.get();
            width = cfg.width();
            height = cfg.height();
        }
        return new SequentialTouchGame(width, height);
    }
}
```

**Why this works?** Because `TileboardEngineAutoConfiguration.gameRegistry()` auto-registers all beans of type `Game`:

```java
@Bean
@ConditionalOnMissingBean
public GameRegistry gameRegistry(@Autowired(required = false) List<Game> games) {
    GameRegistry registry = new DefaultGameRegistry();
    if (games == null || games.isEmpty()) log.warn("No Game beans found ...");
    else games.forEach(game -> {
        registry.register(game);
        log.info("Auto-registered game: '{}' ({})", ...);
    });
    return registry;
}
```

So just defining the game as a `@Bean` makes it appear in `GET /api/v1/games`.

**Startup-sizing caveat:** the bean is created once at startup, so the game's `requiredWidth/Height` reflect the device configuration *at startup time* (3×3 when unconfigured — which in practice means "configure the 3×3 device", since `validateBoardSize` requires an exact match). Restart the app after changing the device size.

### Step 6: Build and Run

```bash
mvn clean install -DskipTests
cd tileboard-app
mvn spring-boot:run
```

### Step 7: Test Game

```bash
curl -X POST http://localhost:8080/api/v1/device -H "Content-Type: application/json" -d '{"width":3,"height":3}'
curl http://localhost:8080/api/v1/ports
curl -X POST http://localhost:8080/api/v1/ports/OUT/assign -H "Content-Type: application/json" -d '{"portName":"COM3"}'
curl -X POST http://localhost:8080/api/v1/ports/IN/assign -H "Content-Type: application/json" -d '{"portName":"COM3"}'
curl -X POST http://localhost:8080/api/v1/ports/connect
curl http://localhost:8080/api/v1/games
curl -X POST http://localhost:8080/api/v1/games/sessions -H "Content-Type: application/json" -d '{"gameId":"sequential-touch","players":[{"name":"Ali","role":"SOLO"}]}'
curl -N -H "Accept: text/event-stream" http://localhost:8080/api/v1/stream/board
```

### Full Game Flow from Player Perspective (3×3)

1. **Standby (BREATHING):** corners/border breathe blue (2 seconds) — idle state
2. **Countdown:** digits 3 (red) → 2 (yellow) → 1 (green), then green blink 3× (GO!)
3. **Game:** tile (0,0) lights red
4. Player touches (0,0) → +10 points, (0,0) off, (0,1) lights green
5. Player touches (0,1) → +10 points, (0,1) off, (0,2) lights blue
6. … until (2,2)
7. Wrong tile touched → FADE_TO_RED short animation → correct tile re-lights (releasing a tile does nothing)
8. 90 seconds pass → DESCENDING_CURTAIN → loss (`FINISHED` with no winners)
9. All 9 tiles touched in order → RADIAL_BURST → win (`FINISHED` with the player as winner)

---

## Using Animations

All animation behavior lives in the engine's `AnimationSystem` (single daemon thread `tileboard-animation`, generation-based cooperative cancellation, `CompletableFuture` chaining). In the app they are reached via `GameContext.animations()`, whose `boardPublisher` is `GameSessionImpl::publishBoard` → `BoardChannel` → `TileGatewayClient.sendBoard`.

### Available Animations

#### Countdown

```java
ctx.animations().playCountdown()      // 1000 ms per digit
ctx.animations().playCountdown(700)   // custom duration
```

- Boards smaller than 3×5: whole board lights RED → BLUE → GREEN.
- Larger boards: centered 5×3 digits 3 (red) → 2 (yellow) → 1 (green), then 3× green blink (150 ms on/off).

#### Win

```java
public enum WinAnimationType {
    RADIAL_BURST,    // colored ring bands expanding from center (default)
    RAINBOW_SWEEP,   // two rainbow column sweeps
    SPARKLE,         // 15 cycles of random sparkles
    FIREWORKS        // 3 fireworks at random interior points
}

ctx.animations().playWinAnimation() // default RADIAL_BURST
ctx.animations().playWinAnimation(WinAnimationType.FIREWORKS)
```

#### Lose

```java
public enum LoseAnimationType {
    FADE_TO_RED,          // random fill to full red (default)
    DESCENDING_CURTAIN,   // red rows accumulate top-to-bottom
    CRUMBLE,              // yellow board crumbles to red tile-by-tile
    PULSE_RED             // 4x red pulse blinks
}

ctx.animations().playLoseAnimation()
ctx.animations().playLoseAnimation(LoseAnimationType.CRUMBLE)
```

#### Standby

```java
public enum StandbyAnimationType {
    BREATHING,      // corners/border breathing blue (default)
    CORNER_PULSE,   // cycling colored 3x3 corner blocks
    WAVE_BORDER,    // marching border wave
    RANDOM_TWINKLE  // random white twinkles
}

ctx.animations().playStandbyAnimation()
ctx.animations().playStandbyAnimation(StandbyAnimationType.WAVE_BORDER)
```

**Standby animations run infinitely until cancelled.** The "idle before start" pattern:

```java
try {
    ctx.animations().playStandbyAnimation(StandbyAnimationType.BREATHING)
        .get(2, TimeUnit.SECONDS); // TimeoutException after 2 seconds — expected
} catch (Exception e) {
    ctx.animations().cancelCurrent(); // cancel the still-running animation
}
```

### Technical Implementation of Animations

```java
// Inside AnimationSystem (simplified)
private final AtomicLong generation = new AtomicLong(0);

private CompletableFuture<Void> run(Consumer<RunToken> body) {
    synchronized (runLock) {
        if (currentTask != null) currentTask.cancel(true);
        if (currentResult != null) currentResult.cancel(false);
        long myGen = generation.incrementAndGet();
        RunToken token = new RunToken(myGen);
        // submit body to the single-thread executor...
    }
}

public final class RunToken {
    boolean isCancelled() { return generation.get() != myGeneration; }
    boolean sleep(long ms) { ... }   // false when cancelled/interrupted
    void pause(long ms) { if (!sleep(ms)) throw new AnimationCancelledException(); }
    void show(Board<TileColor> board) {
        if (isCancelled()) throw new AnimationCancelledException();
        boardPublisher.accept(board);
    }
}
```

Animations cooperatively check for cancellation and exit cleanly (cancelled future) instead of being force-killed. `shutdown()` (via `FeatureBundle.closeAll()` at session end) cancels and stops the executor. See the engine README for the full per-animation timings.

### Using Animations in the Spring App

Animations are per-session objects — use them inside games via `ctx.animations()`. There is intentionally no admin endpoint that plays animations outside a session.

---

## Deep Dive

### 1. DefaultSerialConnectionManager - synchronized + rollback + dual topology

**Problem:** `connect()` may be called concurrently (two admins at once). Opening ports may partially fail (first opens, second throws).

**Solution:**

- `synchronized` on `connect()`, `disconnect()`, `assign()`, `currentAssignment()`, `connectionState()` → one thread mutates state at a time.
- `openedThisAttempt` + `success` flag + `finally` rollback → transports opened by a failed attempt are closed, so no OS handle leaks.
- `EnumMap` for `assignedPorts`/`openTransports` → array-backed, optimal for enum keys.
- Shared-transport detection: IN == OUT name → opened once, `builder.transport(shared)`.
- `INTRODUCTION` after connect / `STOP` before disconnect (best-effort, warn on failure).

### 2. InMemoryDeviceConfigurationService - AtomicReference

```java
private final AtomicReference<DeviceConfiguration> configuration = new AtomicReference<>();

public Optional<DeviceConfiguration> current() {
    return Optional.ofNullable(configuration.get());
}

public DeviceConfiguration configure(int width, int height) {
    DeviceConfiguration updated = new DeviceConfiguration(width, height);
    configuration.set(updated);
    return updated;
}
```

- `AtomicReference` is thread-safe for a single value without synchronized.
- `get()`/`set()` are atomic with cross-thread visibility.
- `Optional` models "not yet configured" (null).

### 3. GameEngineManager - volatile + synchronized + null-before-close

```java
private volatile GameEngineImpl engine;

@EventListener
public synchronized void onGatewayConnected(GatewayConnectedEvent event) {
    if (engine != null) { log.warn("... already bound ..."); shutdownCurrentEngine(); }
    engine = new GameEngineImpl(registry, event.client(), eventBus, boardFrameBroadcaster, ...);
}

private void shutdownCurrentEngine() {
    GameEngineImpl current = this.engine;
    if (current == null) { log.debug("... nothing to do"); return; }
    this.engine = null; // immediately visible, BEFORE the slow close()
    try { current.close(); } catch (RuntimeException e) { log.warn(...) }
}

public synchronized Optional<GameEngine> current() { return Optional.ofNullable(engine); }
public synchronized GameEngine require() { if (engine == null) throw new EngineNotReadyException(); return engine; }

@PreDestroy
public synchronized void shutdownOnContextClose() { shutdownCurrentEngine(); }
```

- `volatile` + `synchronized` writers → connect/disconnect races are impossible.
- `engine = null` before `close()` → no thread ever observes a half-closed engine.
- Null-safe/idempotent → duplicate disconnects are safe no-ops.

### 4. BoardChannel - ReentrantLock + gatewayWriteLock + coalescing

Detailed in the game engine README. Summary: `stateLock` guards the buffer, `gatewayWriteLock` serializes wire writes, `sendLatest()` re-reads the snapshot (coalescing), and `BoardFrameBroadcaster` dispatch happens outside the write lock.

### 5. GameState - synchronized HashMap

```java
public final class GameState {
    private final Map<String, Object> store = new HashMap<>();
    public synchronized <T> void put(String key, T value) { ... }
    public synchronized <T> Optional<T> get(String key, Class<T> type) { ... }
    public synchronized <T> T getOrDefault(String key, Class<T> type, T defaultValue) { ... }
    public synchronized boolean containsKey(String key) { ... }
    public synchronized void remove(String key) { ... }
    public synchronized void clear() { ... }
    public synchronized Map<String, Object> snapshot() { ... } // unmodifiable copy
}
```

Plain `HashMap` with `synchronized` methods → safe for concurrent tick-thread/callback-thread access.

### 6. AnimationSystem - generation + CompletableFuture + SingleThreadExecutor

Detailed in the game engine README: `AtomicLong generation`, `runLock`, single daemon thread, per-animation `CompletableFuture` (normal/cancelled/exceptional).

### 7. ScoreSystem - ConcurrentHashMap + AtomicInteger

```java
private final Map<String, AtomicInteger> scores = new ConcurrentHashMap<>();
public int add(String playerId, int delta) {
    int result = getOrCreate(playerId).addAndGet(delta);
    if (delta != 0) onChange.run();
    return result;
}
private AtomicInteger getOrCreate(String playerId) { return scores.computeIfAbsent(playerId, k -> new AtomicInteger(0)); }
```

- `ConcurrentHashMap` + `computeIfAbsent` (atomic) + CAS-based `addAndGet` → no global lock; `onChange` fires on the caller's thread.

### 8. GameTimer - volatile + AtomicReference + AtomicBoolean

```java
private final AtomicReference<Runnable> onExpire = new AtomicReference<>();
private final AtomicBoolean expiryNotified = new AtomicBoolean(false);
private volatile Instant startedAt;
public void checkExpiry() {
    if (!isExpired()) return;
    if (expiryNotified.compareAndSet(false, true)) engineExpiryNotifier.run();
    Runnable cb = onExpire.getAndSet(null);
    if (cb != null) cb.run();
}
```

- `volatile` for visibility without locking.
- `compareAndSet` + `getAndSet(null)` → each expiry callback fires exactly once.

### 9. CORS Filter - FilterRegistrationBean

```java
@EnableWebMvc
@Configuration
public class GeneralConfiguration implements WebMvcConfigurer {
    @Bean
    public FilterRegistrationBean<CorsFilter> simpleCorsFilter() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Collections.singletonList("*"));
        config.setAllowedMethods(Collections.singletonList("*"));
        config.setAllowedHeaders(Collections.singletonList("*"));
        source.registerCorsConfiguration("/**", config);
        FilterRegistrationBean<CorsFilter> bean = new FilterRegistrationBean<>(new CorsFilter(source));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }
}
```

- `HIGHEST_PRECEDENCE` → CORS is evaluated before any other filter.
- `*` origins/methods/headers → convenient for development; restrict for production.

---

## Tests and Execution

### Tests

```bash
mvn test -pl tileboard-app
```

Actual test classes:

- `TileboardApplicationTests`: `contextLoads`
- `TileboardPropertiesTest`: record defaults (115200/8/1/50/50/0)
- `ControllerUnitTest`: pure unit tests (Mockito, no MockMvc) for `DeviceController`, `SerialPortController`, `GameController` (device read/update, port list/assign/status/connect/disconnect, game list/start/sessions/get/stop + disconnected-engine cases)
- `InMemoryDeviceGeneralConfigurationServiceTest`: configure/current/isConfigured behavior
- `DefaultSerialConnectionManagerTest`: distinct-port listing, assignment reporting, OUT-required connect, idempotent disconnect

### Execution

```bash
mvn spring-boot:run -pl tileboard-app
# from the module directory:
cd tileboard-app && mvn spring-boot:run
# or packaged:
mvn clean package -DskipTests
java -jar tileboard-app/target/tileboard-app-1.0.0.jar

# with prod profile
java -jar tileboard-app/target/tileboard-app-1.0.0.jar --spring.profiles.active=prod

# with custom port
java -jar tileboard-app/target/tileboard-app-1.0.0.jar --server.port=9090
```

### Docker (Optional)

```dockerfile
FROM openjdk:17-jdk-slim
COPY target/tileboard-app-1.0.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app.jar"]
```

```bash
docker build -t tileboard-app .
docker run -p 8080:8080 --device=/dev/ttyUSB0 tileboard-app
```

---

## Full API Reference

### Device (`/api/v1/device`)

| Method | Path | Body | Response |
|--------|------|------|----------|
| POST | /api/v1/device | {width, height} | `ApiResponse{data: DeviceConfigurationResponse}` |
| GET | /api/v1/device | - | `ApiResponse{data: DeviceConfigurationResponse}` or 409 |

### Ports (`/api/v1/ports`)

| Method | Path | Body | Response |
|--------|------|------|----------|
| GET | /api/v1/ports | - | `ApiResponse{message: "N ports are available", data: [SerialPortResponse]}` |
| POST | /api/v1/ports/{role}/assign | {portName} (`role` = IN/OUT path var) | `ApiResponse` empty success |
| GET | /api/v1/ports/status | - | `ApiResponse{data: ConnectionStatusResponse{state, inPort, outPort}}` |
| POST | /api/v1/ports/connect | - | same as /status (409 without OUT) |
| POST | /api/v1/ports/disconnect | - | same as /status (200, idempotent) |

### Games (`/api/v1/games`)

| Method | Path | Body | Response |
|--------|------|------|----------|
| GET | /api/v1/games | - | `ApiResponse{data: [GameDescriptorResponse]}` |
| POST | /api/v1/games/sessions | {gameId, players:[{name, role}]} | `ApiResponse{data: GameSessionResponse{sessionId, gameId, status}}` |
| GET | /api/v1/games/sessions | - | `ApiResponse{data: [GameSessionResponse]}` (empty when disconnected) |
| GET | /api/v1/games/sessions/{id} | - | **raw** `GameSessionResponse` (no envelope) or 409 |
| POST | /api/v1/games/sessions/{id}/stop | - | 204 |

### Stream (SSE, `text/event-stream`)

| Method | Path | Response |
|--------|------|----------|
| GET | /api/v1/stream/board | SSE: `SESSION_LIFECYCLE`/`BOARD_UPDATE`/`TICK`/… (`SseGameEvent` JSON) |
| GET | /api/v1/stream/board/{sessionId} | SSE for one session |

### Actuator

| Method | Path |
|--------|------|
| GET | /actuator/health |
| GET | /actuator/info |

### Error envelope

Every error: `{status: ERROR, message: <Persian>, data: null, extra: null, debugMessage: <raw English>}` with the status from the exception (409/404/502/400/500 — see the Error Handling section).

---

## Summary

This application:

1. **Abstracts hardware:** Only knows `SerialPortRegistry`/`SerialTransport` interfaces, not jSerialComm (single seam: `SerialGatewayConfig`).
2. **Is thread-safe:** Correctly uses `AtomicReference`, `synchronized`, `ConcurrentHashMap`, `volatile`, CAS — documented per class above.
3. **Is extensible:** Adding a new game is just a `@Bean` (auto-registered by the engine).
4. **Is production-ready:** Per-session TTL, connect rollback, idempotent (dis)connect, `INTRODUCTION`/`START`/`STOP` hardware protocol, CORS, Actuator (`health,info`), Swagger starter, prod logging profile, localized error catalog.
5. **Is educational:** Sample game `SequentialTouchGame` (3×3 default) demonstrates standby/countdown/win/lose animations and the concurrency patterns.

For more questions, see the READMEs of the `tileboard-serial-protocol` and `tileboard-game-engine` modules.

---

**Author:** Tileboard Platform Team  
**Version:** 1.0.0  
**Java:** 17+  
**Spring Boot:** 3.3.4
