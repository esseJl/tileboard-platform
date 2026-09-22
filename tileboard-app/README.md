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
9. [SSE Streaming - BoardStateBroadcaster](#sse-streaming)
10. [GameEngineManager - Spring and Engine Bridge](#gameenginemanager)
11. [Error Handling - GlobalExceptionHandler](#error-handling)
12. [Step-by-Step Run and API Usage Tutorial](#step-by-step-tutorial)
13. [Comprehensive Game Creation Tutorial - SequentialTouchGame Practical Example](#comprehensive-game-tutorial)
14. [Using win/lose/standby/countdown Animations](#using-animations)
15. [Deep Dive - Concurrency and Complex Logic](#deep-dive)
16. [Tests and Execution](#tests-and-execution)

---

## Overall Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│  Frontend / Mobile App / curl                                           │
│  HTTP REST + SSE                                                        │
├─────────────────────────────────────────────────────────────────────────┤
│  Controllers (Spring MVC)                                               │
│  ├─ DeviceController: POST /api/v1/devices/configure                    │
│  ├─ SerialPortController: GET /api/v1/ports, POST /assign, /connect     │
│  ├─ GameController: GET /api/v1/games, POST /sessions                   │
│  └─ StreamController: GET /api/v1/stream/board, /api/v1/games/events    │
├─────────────────────────────────────────────────────────────────────────┤
│  Services                                                               │
│  ├─ DeviceConfigurationService (AtomicReference)                        │
│  │   └─ InMemoryDeviceConfigurationService                              │
│  ├─ SerialConnectionManager (synchronized, EnumMap)                     │
│  │   └─ DefaultSerialConnectionManager                                  │
│  └─ BoardStateBroadcaster (SSE)                                         │
│      └─ SseBoardStateBroadcaster                                        │
├─────────────────────────────────────────────────────────────────────────┤
│  GameEngineManager (@EventListener)                                     │
│  ├─ onGatewayConnected → new GameEngineImpl                             │
│  └─ onGatewayDisconnected → close engine                                │
├─────────────────────────────────────────────────────────────────────────┤
│  tileboard-game-engine                                                  │
│  ├─ GameRegistry (auto-registers @Bean Game)                            │
│  ├─ GameEngineImpl, GameSessionImpl, BoardChannel, AnimationSystem      │
│  └─ GameEventBusImpl, SseGameEventPublisher                             │
├─────────────────────────────────────────────────────────────────────────┤
│  tileboard-serial-protocol                                              │
│  ├─ TileGatewayClient, DefaultFrameCodec, Board<T>                      │
│  └─ JSerialCommTransport, JSerialCommPortRegistry                       │
├─────────────────────────────────────────────────────────────────────────┤
│  Hardware: LED Tile Board (m x n) over Serial (115200 baud)             │
└─────────────────────────────────────────────────────────────────────────┘
```

**Typical Data Flow:**

1. Operator configures device: `POST /devices/configure {width, height}`
2. Lists serial ports: `GET /ports`
3. Assigns ports: `POST /ports/assign {role, portName}`
4. Connects: `POST /ports/connect` → `DefaultSerialConnectionManager.connect()` → `TileGatewayClient` created → `GatewayConnectedEvent` published → `GameEngineManager` creates new `GameEngineImpl`
5. Lists games: `GET /games` (from `GameRegistry`)
6. Starts game: `POST /games/sessions {gameId, players}` → `GameEngine.startGame()` → `GameSessionImpl` created → game's `onStart()` called
7. Connects SSE: `GET /stream/board` or `/games/events` → board and scores and events real-time
8. Player touches tiles → `TileGatewayClient` receives `DATA_IN` frame → `EngineFrameRouter` → `TouchFrameRouter` → `GameSessionImpl.handleTileEvent` → `game.onTileEvent`
9. Game wins/loses → `winSession`/`loseSession` → `finishSession` → `GameResult` → board off → SSE `SESSION_FINISHED`

---

## Tech Stack

- **Java 17**, **Spring Boot 3.3.4**, **Spring MVC**, **Spring Actuator**
- **jSerialComm 2.11.0** for serial communication
- **springdoc-openapi 2.6.0** for Swagger UI
- **Jackson** for JSON
- **SLF4J** for logging
- **Maven** for build

---

## Package Structure

| Package | Responsibility |
|------|---------|
| `com.tileboard.app` | `TileboardApplication` (main) |
| `config` | `TileboardProperties`, `DeviceConfiguration`, `SerialGatewayConfig`, `GeneralConfiguration` (CORS) |
| `controller` | REST controllers: `DeviceController`, `SerialPortController`, `GameController`, `StreamController` |
| `dto` | API DTOs: `DeviceConfigurationRequest`, `AssignPortRequest`, `StartGameRequest`, `GameSessionResponse`, `ApiResponse`, ... |
| `service.device` | `DeviceConfigurationService` + `InMemoryDeviceConfigurationService` |
| `service.serial` | `SerialConnectionManager` + `DefaultSerialConnectionManager`, `PortRole`, `ConnectionState` |
| `service.streaming` | `BoardStateBroadcaster` + `SseBoardStateBroadcaster` |
| `exception` | `ApiException` and subclasses + `GlobalExceptionHandler` |
| `game` | **Sample games**: `SequentialTouchGame`, `GameBeansConfig` (new) |

---

## Configuration

### application.yml

```yaml
spring:
  application:
    name: tileboard-game-engine

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
    session-ttl: 30m
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
# Activated with --spring.profiles.active=prod
logging:
  level:
    root: INFO
    com.tileboard: INFO
```

**Why DEBUG in dev?** Because `JSerialCommTransport` logs TX/RX bytes in hex at DEBUG level, useful for protocol debugging but noisy in production.

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

- `record` with compact constructor for defaults
- Enabled via `@ConfigurationPropertiesScan` in `TileboardApplication`

---

## DeviceConfiguration

```java
public record DeviceConfiguration(int width, int height) {
    public DeviceConfiguration {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException(...);
        if (width * height > 255) throw new IllegalArgumentException("width*height must be <=255 (protocol limit)");
    }
    public int tileCount() { return width*height; }
}
```

- Physical geometry of board: how many tiles wide and tall
- Limit 255 comes from `DeviceAddress` encoding total tile count in one byte (protocol ceiling)
- This is the one piece of information every other module (handshake, game engine) needs before doing anything useful

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

**This is the only place in the whole app that knows `JSerialCommPortRegistry` is used.** If you want to swap serial library (or build a Mock for hardware-less demo), you only change this Bean. Rest of code only knows `SerialPortRegistry` interface.

---

## Services

### DeviceConfigurationService

```java
public interface DeviceConfigurationService {
    Optional<DeviceConfiguration> current();
    DeviceConfiguration configure(int width, int height);
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

- `AtomicReference` -> thread-safe without synchronized, because it holds just one value
- `Optional` for "not yet configured" state
- TODO: Replace with DB-backed implementation in future, since all consumers only know interface

### SerialConnectionManager

```java
public interface SerialConnectionManager {
    List<SerialPortSummary> listAvailablePorts();
    void assign(PortRole role, String portName);
    PortAssignment currentAssignment();
    ConnectionState connectionState();
    void connect();
    void disconnect();
}

public enum PortRole { IN, OUT }
public enum ConnectionState { CONNECTED, DISCONNECTED }
public record PortAssignment(Optional<String> inPort, Optional<String> outPort) {}
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

    @Override public synchronized ConnectionState connectionState() {
        return client != null ? CONNECTED : DISCONNECTED;
    }

    @Override public synchronized void connect() {
        if (client != null) return; // idempotent
        String outPort = assignedPorts.get(OUT);
        String inPort = assignedPorts.get(IN);
        if (outPort == null) throw new PortsNotAssignedException();

        SerialPortConfig config = SerialPortConfig.builder()
            .baudRate(properties.baudRate()).dataBits(...).build();

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
                    log.warn("No IN port assigned - OUTPUT ONLY. Touches will never be received.");
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
        if (deviceConfigurationService.current().isPresent()) {
            eventPublisher.publishEvent(new GatewayConnectedEvent(newClient, width, height));
            newClient.start();
        } else {
            throw new DeviceNotConfiguredException();
        }
    }

    private void enableHandshakeIfDeviceKnown(TileGatewayClient gatewayClient) {
        deviceConfigurationService.current().ifPresentOrElse(
            device -> {
                int minSeq = properties.handshakeMinSequence() > 0 ? properties.handshakeMinSequence() : Math.max(2, Math.min(device.width(), device.height()));
                gatewayClient.enableIdHandshake(() -> DeviceAddress.forBoard(device.width(), device.height()), new SequentialIdSequenceValidator(minSeq));
            },
            () -> log.warn("Connecting without device config - handshake will not be enabled until reconfigured"));
    }

    @Override public synchronized void disconnect() {
        if (client == null) return;
        try { client.close(); } finally {
            client = null;
            openTransports.clear();
            eventPublisher.publishEvent(new GatewayDisconnectedEvent());
        }
    }
}
```

**Concurrency and complex logic notes:**

1. **synchronized on mutating methods:** `assign`, `connectionState`, `connect`, `disconnect` are all `synchronized`. Since these are admin operations (operator-driven) and shouldn't be called concurrently from many threads, simple synchronized is enough and avoids complexity of other locks.

2. **EnumMap:** For `assignedPorts` and `openTransports`, `EnumMap` is used which is optimized for enum keys (internal array, not hash).

3. **Two topologies transparently:**
   - If IN and OUT have same name -> one shared `SerialTransport` opened and used with `builder.transport(shared)` (full-duplex)
   - If separate -> two separate transports opened
   - If only OUT assigned -> warning logged that it's "OUTPUT ONLY" and touches will never be received. Better loud and explicit than silently half-working.

4. **Rollback on failed connect:**
   ```java
   Map<PortRole, SerialTransport> openedThisAttempt = new EnumMap<>();
   boolean success = false;
   try {
       // open ports
       success = true;
   } finally {
       if (!success) closeQuietly(openedThisAttempt.values());
   }
   ```
   - `openedThisAttempt` only holds transports opened in this attempt
   - If opening second port fails, `finally` closes first transport so OS handle doesn't leak. Without this, a failed `connect` would hold port handle open forever with nothing referencing it, and next `connect` attempt would fail again trying to reopen same physical port.

5. **Handshake and start ordering:**
   ```java
   enableHandshakeIfDeviceKnown(newClient); // first register listeners
   // ...
   newClient.start(); // then open input pipe
   ```
   - If `start()` called first, board's very first frames (e.g., initial ID/CLEAR handshake request) could arrive before `HandshakeCoordinator` is registered and since `TileGatewayClient.dispatch()` only notifies listeners registered by the time a frame is decoded, those frames would be silently dropped.

6. **Event publishing:** After client built, `GatewayConnectedEvent` published which wakes `GameEngineManager` to build engine. Then `client.start()` called so data starts flowing.

7. **closeQuietly:** Even if `close()` of one transport throws, other transports are closed.

### BoardStateBroadcaster

```java
public interface BoardStateBroadcaster {
    void broadcast(Board<TileColor> board);
}

@Service
public class SseBoardStateBroadcaster implements BoardStateBroadcaster {
    private final SseGameEventPublisher ssePublisher;
    // ...
}
```

This service broadcasts board to all connected SSE clients.

---

## Controllers

### DeviceController

```
POST /api/v1/devices/configure
Body: { "width": 8, "height": 8 }
Response: { "width": 8, "height": 8, "tileCount": 64 }

GET /api/v1/devices/configuration
Response: { "width": 8, "height": 8, ... } or 404 if not configured
```

- `DeviceConfigurationRequest` with validation (`@Min(1)`, `@Max(255)`)
- `DeviceConfigurationResponse` from `DeviceConfiguration`

### SerialPortController

```
GET /api/v1/ports
Response: [{ "systemName": "COM3", "description": "USB Serial Port" }, ...]

POST /api/v1/ports/assign
Body: { "role": "OUT", "portName": "COM3" }  # role = IN or OUT
Response: { "inPort": "COM3", "outPort": "COM3" }

GET /api/v1/ports/assignment
Response: { "inPort": "...", "outPort": "..." }

POST /api/v1/ports/connect
Response: { "status": "CONNECTED", "inPort": "...", "outPort": "..." }

POST /api/v1/ports/disconnect
Response: 204 No Content

GET /api/v1/ports/status
Response: { "status": "CONNECTED" or "DISCONNECTED", "assignment": {...} }
```

### GameController

```
GET /api/v1/games
Response: [{ "gameId": "sequential-touch", "displayName": "Sequential Touch Challenge", "category": "TUTORIAL", ... }, ...]
# Works even before board connected (from GameRegistry)

POST /api/v1/games/sessions
Body: { "gameId": "sequential-touch", "players": [{ "name": "Ali" }] }
Response: { "sessionId": "uuid", "gameId": "sequential-touch", "status": "RUNNING", "players": [...], "scores": {...} }

GET /api/v1/games/sessions
Response: list of active sessions

GET /api/v1/games/sessions/{sessionId}
Response: single session

POST /api/v1/games/sessions/{sessionId}/stop
Response: 204
```

- `engineManager.require()` -> if engine not yet bound (board not connected), throws `EngineNotReadyException` which `GlobalExceptionHandler` converts to 409 Conflict
- `StartGameRequest` with validation

### StreamController

```
GET /api/v1/stream/board
Accept: text/event-stream
Event: data: {"board": [[0,1,0,...], ...], "timestamp": "..."}

GET /api/v1/games/events
Accept: text/event-stream
Event: event: SESSION_STARTED, BOARD_UPDATED, SCORE_UPDATED, TICK, SESSION_FINISHED
       data: {...}
```

- Uses Spring `SseEmitter`
- Heartbeat every 15 seconds to prevent proxy timeout

---

## Error Handling

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DeviceNotConfiguredException.class)
    public ResponseEntity<ApiResponse<?>> handleDeviceNotConfigured(...) {
        return ResponseEntity.status(400).body(ApiResponse.error("Device not configured"));
    }

    @ExceptionHandler(PortsNotAssignedException.class)
    public ResponseEntity<ApiResponse<?>> handlePortsNotAssigned(...) { 400 }

    @ExceptionHandler(GatewayNotConnectedException.class)
    public ResponseEntity<ApiResponse<?>> handleGatewayNotConnected(...) { 409 }

    @ExceptionHandler(EngineNotReadyException.class)
    public ResponseEntity<ApiResponse<?>> handleEngineNotReady(...) { 409 }

    @ExceptionHandler(NoActiveGameException.class)
    public ResponseEntity<ApiResponse<?>> handleNoActiveGame(...) { 404 }

    @ExceptionHandler(SerialPortOperationException.class)
    public ResponseEntity<ApiResponse<?>> handleSerialPortOp(...) { 500 }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<?>> handleValidation(...) { 400 + details }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<?>> handleGeneric(...) { 500 }
}
```

All errors return same `ApiResponse` format:

```json
{
  "status": "ERROR",
  "message": "Error description",
  "data": null
}
```

---

## Step-by-Step Tutorial

### Prerequisites

- Java 17+
- Maven 3.8+
- Tileboard board connected via USB (or Mock for testing without hardware)

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

Swagger UI: `http://localhost:8080/swagger-ui.html`

Actuator: `http://localhost:8080/actuator/health`

### Step 3: Configure Device

```bash
curl -X POST http://localhost:8080/api/v1/devices/configure \
  -H "Content-Type: application/json" \
  -d '{"width":8,"height":8}'
```

Response:
```json
{
  "status": "SUCCESS",
  "data": { "width": 8, "height": 8, "tileCount": 64 }
}
```

### Step 4: List Ports

```bash
curl http://localhost:8080/api/v1/ports
```

Response:
```json
{
  "status": "SUCCESS",
  "data": [
    { "systemName": "COM3", "description": "USB Serial Port" },
    { "systemName": "COM4", "description": "USB Serial Port" }
  ]
}
```

### Step 5: Assign Ports

If your board has single full-duplex port (common):

```bash
curl -X POST http://localhost:8080/api/v1/ports/assign \
  -H "Content-Type: application/json" \
  -d '{"role":"OUT","portName":"COM3"}'

curl -X POST http://localhost:8080/api/v1/ports/assign \
  -H "Content-Type: application/json" \
  -d '{"role":"IN","portName":"COM3"}'
```

If you have two half-duplex adapters:

```bash
curl -X POST http://localhost:8080/api/v1/ports/assign -d '{"role":"OUT","portName":"COM3"}'
curl -X POST http://localhost:8080/api/v1/ports/assign -d '{"role":"IN","portName":"COM4"}'
```

### Step 6: Connect

```bash
curl -X POST http://localhost:8080/api/v1/ports/connect
```

Response:
```json
{
  "status": "SUCCESS",
  "data": { "status": "CONNECTED", "inPort": "COM3", "outPort": "COM3" }
}
```

Logs should show:
```
Enabling id handshake for a 8x8 board (minimumSequence=2)
Tile board gateway connected (in=COM3, out=COM3)
Game engine bound to the newly connected tile gateway (8x8)
```

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
      "description": "Tiles light up sequentially; touch to score...",
      "requiredWidth": 8,
      "requiredHeight": 8,
      "minPlayers": 1,
      "maxPlayers": 1
    }
  ]
}
```

### Step 8: Start Game

```bash
curl -X POST http://localhost:8080/api/v1/games/sessions \
  -H "Content-Type: application/json" \
  -d '{
    "gameId": "sequential-touch",
    "players": [{"name":"Ali"}]
  }'
```

Response:
```json
{
  "status": "SUCCESS",
  "data": {
    "sessionId": "a1b2c3d4-...",
    "gameId": "sequential-touch",
    "status": "RUNNING",
    "players": [{"id":"...","name":"Ali"}],
    "scores": {"...":0}
  }
}
```

At this moment on board:
1. Standby animation (BREATHING) 2 seconds
2. Countdown animation (3→2→1) ~2.1 seconds
3. First tile lights up (e.g., (0,0) red)

### Step 9: SSE - Real-time Board View

In another terminal:

```bash
curl -N -H "Accept: text/event-stream" http://localhost:8080/api/v1/games/events
```

Or with JS in browser:

```javascript
const eventSource = new EventSource('/api/v1/games/events');
eventSource.addEventListener('BOARD_UPDATED', e => {
  const data = JSON.parse(e.data);
  console.log('Board updated:', data);
});
eventSource.addEventListener('SESSION_FINISHED', e => {
  console.log('Game finished:', JSON.parse(e.data));
  eventSource.close();
});
```

### Step 10: Play

- Touch lit tile → +10 points, tile off, next tile lights
- If wrong tile touched → FADE_TO_RED short animation, then correct tile re-lights
- If 90 seconds pass → DESCENDING_CURTAIN animation and loss
- If all 64 tiles touched sequentially → RADIAL_BURST animation and win

### Step 11: Stop Game

```bash
curl -X POST http://localhost:8080/api/v1/games/sessions/{sessionId}/stop
```

### Step 12: Disconnect

```bash
curl -X POST http://localhost:8080/api/v1/ports/disconnect
```

---

## Comprehensive Game Tutorial

This is the most important section and explains step-by-step how to build **SequentialTouchGame** that includes all requested animations.

### Game Scenario

> Each tile lights up sequentially with a color; as soon as it is touched, player gets points and next tile's turn comes, until all tiles are lit and touched, then game ends. Also use lose, win, stand-by animations in game and before game start use countDown animation.

### Step 1: Create Game Class

File: `src/main/java/com/tileboard/app/game/SequentialTouchGame.java`

```java
package com.tileboard.app.game;

import com.tileboard.engine.core.Game;
import com.tileboard.engine.core.GameContext;
import com.tileboard.engine.core.GameDescriptor;
import com.tileboard.engine.core.GameResult;
import com.tileboard.engine.feature.AnimationSystem;
import com.tileboard.engine.model.TileColor;
import com.tileboard.engine.model.TileEvent;
import com.tileboard.serial.board.Position;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class SequentialTouchGame implements Game {

    private static final Logger log = LoggerFactory.getLogger(SequentialTouchGame.class);

    private static final String KEY_POSITIONS = "sequential.positions";
    private static final String KEY_INDEX = "sequential.index";

    private static final TileColor[] PALETTE = {
        TileColor.RED, TileColor.GREEN, TileColor.BLUE,
        TileColor.YELLOW, TileColor.PINK, TileColor.LIGHT_BLUE, TileColor.WHITE
    };

    private final GameDescriptor descriptor;

    public SequentialTouchGame() {
        this.descriptor = GameDescriptor.builder("sequential-touch", "Sequential Touch Challenge")
            .category("TUTORIAL")
            .description("Tiles light up sequentially; touch to score and advance. Includes countdown, standby, win and lose animations.")
            .boardSize(8, 8)
            .players(1, 1)
            .build();
    }

    public SequentialTouchGame(int width, int height) {
        this.descriptor = GameDescriptor.builder("sequential-touch", "Sequential Touch Challenge")
            .category("TUTORIAL")
            .description("Tiles light up sequentially; touch to score and advance.")
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
        log.info("[{}] Game onStart - board {}x{}", ctx.sessionId(), ctx.boardWidth(), ctx.boardHeight());

        ctx.fillBoard(TileColor.OFF);
        ctx.scores().resetAll();
        ctx.state().clear();

        // 1. Standby animation: BREATHING for 2 seconds
        // This animation is infinite until cancelled
        try {
            log.info("[{}] Playing STANDBY (BREATHING) for 2 seconds...", ctx.sessionId());
            ctx.animations().playStandbyAnimation(AnimationSystem.StandbyAnimationType.BREATHING)
                .get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            ctx.animations().cancelCurrent();
            log.info("[{}] Standby cancelled, moving to countdown", ctx.sessionId());
        }

        // 2. Countdown animation: 3 -> 2 -> 1 -> green blink
        // playCountdown runs on animation SingleThreadExecutor
        // join() waits until countdown ends
        try {
            log.info("[{}] Playing COUNTDOWN...", ctx.sessionId());
            ctx.animations().playCountdown(700).join(); // 700ms per digit
        } catch (Exception e) {
            log.warn("[{}] Countdown interrupted", ctx.sessionId(), e);
        }

        // 3. List all positions row-major
        List<Position> allPositions = new ArrayList<>();
        for (int r = 0; r < ctx.boardHeight(); r++) {
            for (int c = 0; c < ctx.boardWidth(); c++) {
                allPositions.add(new Position(r, c));
            }
        }

        ctx.state().put(KEY_POSITIONS, allPositions);
        ctx.state().put(KEY_INDEX, 0);

        // 4. Global timer: if not finished in 90 seconds, lose
        // GameTimer uses AtomicReference<Runnable> for onExpire
        // checkExpiry() called every tick (100ms) by GameSessionImpl.runTick()
        ctx.timer().startCountdown(Duration.ofSeconds(90), () -> {
            log.info("[{}] Timer expired - LOST", ctx.sessionId());
            ctx.animations().playLoseAnimation(AnimationSystem.LoseAnimationType.DESCENDING_CURTAIN)
                .thenRun(() -> ctx.loseSession());
        });

        // 5. Light first tile
        lightCurrentTile(ctx);

        log.info("[{}] Game started with {} tiles", ctx.sessionId(), allPositions.size());
    }

    private void lightCurrentTile(GameContext ctx) {
        @SuppressWarnings("unchecked")
        List<Position> positions = ctx.state().get(KEY_POSITIONS, List.class).orElse(List.of());
        int index = ctx.state().getOrDefault(KEY_INDEX, Integer.class, 0);
        if (index < 0 || index >= positions.size()) return;

        Position pos = positions.get(index);
        TileColor color = PALETTE[index % PALETTE.length];

        // BoardChannel.setTile is thread-safe:
        // - stateLock (ReentrantLock) for internal buffer
        // - gatewayWriteLock (synchronized) for serializing writes on wire
        ctx.setTile(pos.row(), pos.col(), color);
    }
```

**Concurrency notes in onStart:**

- `onStart` runs on thread calling `startGame` (usually HTTP request thread). So `get(2, SECONDS)` and `join()` blocking is fine because it doesn't block tick thread.
- `ctx.state()` is `GameState` where all methods are `synchronized` -> thread-safe
- `ctx.animations()` is `AnimationSystem` that has only one animation at a time with generation-based cancellation
- `ctx.timer()` is `GameTimer` with `volatile` and `AtomicReference`

### Step 3: Implement onTileEvent - Core Game Logic

```java
    @Override
    public void onTileEvent(GameContext ctx, TileEvent event) {
        // Only called when RUNNING (check in GameSessionImpl.handleTileEvent)

        @SuppressWarnings("unchecked")
        List<Position> positions = ctx.state().get(KEY_POSITIONS, List.class).orElse(List.of());
        int currentIndex = ctx.state().getOrDefault(KEY_INDEX, Integer.class, 0);

        if (positions.isEmpty() || currentIndex >= positions.size()) return;

        Position expected = positions.get(currentIndex);
        Position touched = event.position();

        log.debug("[{}] Touch at {} - expected {}", ctx.sessionId(), touched, expected);

        if (touched.equals(expected)) {
            handleCorrectTouch(ctx, currentIndex, positions);
        } else {
            handleWrongTouch(ctx);
        }
    }

    private void handleCorrectTouch(GameContext ctx, int currentIndex, List<Position> positions) {
        String playerId = ctx.players().get(0).id();

        // ScoreSystem uses ConcurrentHashMap<String, AtomicInteger>
        // add() with AtomicInteger.addAndGet is thread-safe
        int newScore = ctx.scores().add(playerId, 10);
        log.info("[{}] Correct! Tile {}/{} touched, score={}", ctx.sessionId(), currentIndex+1, positions.size(), newScore);

        Position justTouched = positions.get(currentIndex);
        ctx.setTile(justTouched.row(), justTouched.col(), TileColor.OFF);

        int nextIndex = currentIndex + 1;
        ctx.state().put(KEY_INDEX, nextIndex);

        if (nextIndex >= positions.size()) {
            handleWin(ctx);
        } else {
            lightCurrentTile(ctx);
        }
    }

    private void handleWrongTouch(GameContext ctx) {
        log.info("[{}] Wrong tile touched!", ctx.sessionId());

        ctx.animations().playLoseAnimation(AnimationSystem.LoseAnimationType.FADE_TO_RED)
            .thenRun(() -> lightCurrentTile(ctx));
    }

    private void handleWin(GameContext ctx) {
        log.info("[{}] All tiles touched! WINS", ctx.sessionId());
        ctx.timer().stop();

        // Win animation: RADIAL_BURST
        // Then winSession which triggers finishSession in GameSessionImpl
        // finishSession with CAS guarantees it runs only once
        ctx.animations().playWinAnimation(AnimationSystem.WinAnimationType.RADIAL_BURST)
            .thenRun(() -> ctx.winSession(ctx.players()));
    }
```

### Step 4: Implement onStop and onError

```java
    @Override
    public void onStop(GameContext ctx, GameResult result) {
        log.info("[{}] onStop - status={}, scores={}", ctx.sessionId(), result.finalStatus(), result.finalScores());
        try {
            ctx.fillBoard(TileColor.OFF);
        } catch (Exception e) {
            log.warn("[{}] Could not clear board on stop", ctx.sessionId());
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
        int width = 8, height = 8;
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

**Why this works?** Because `TileboardEngineAutoConfiguration.gameRegistry()` auto-registers all Beans of type `Game`:

```java
@Bean
public GameRegistry gameRegistry(@Autowired(required=false) List<Game> games) {
    GameRegistry registry = new DefaultGameRegistry();
    if (games!=null) games.forEach(game -> {
        registry.register(game);
        log.info("Auto-registered game: '{}' ({})", game.descriptor().displayName(), game.descriptor().gameId());
    });
    return registry;
}
```

So just defining game as `@Bean` makes it appear in `GET /api/v1/games`.

### Step 6: Build and Run

```bash
mvn clean install -DskipTests
cd tileboard-app
mvn spring-boot:run
```

### Step 7: Test Game

```bash
curl -X POST http://localhost:8080/api/v1/devices/configure -H "Content-Type: application/json" -d '{"width":8,"height":8}'
curl http://localhost:8080/api/v1/ports
curl -X POST http://localhost:8080/api/v1/ports/assign -H "Content-Type: application/json" -d '{"role":"OUT","portName":"COM3"}'
curl -X POST http://localhost:8080/api/v1/ports/assign -H "Content-Type: application/json" -d '{"role":"IN","portName":"COM3"}'
curl -X POST http://localhost:8080/api/v1/ports/connect
curl http://localhost:8080/api/v1/games
curl -X POST http://localhost:8080/api/v1/games/sessions -H "Content-Type: application/json" -d '{"gameId":"sequential-touch","players":[{"name":"Ali"}]}'
curl -N -H "Accept: text/event-stream" http://localhost:8080/api/v1/games/events
```

### Full Game Flow from Player Perspective

1. **Standby (BREATHING):** Board corners blink blue (2 seconds) - idle state
2. **Countdown:** Whole board red (3) -> yellow (2) -> green (1) -> green blink 3 times (GO!)
3. **Game:** Tile (0,0) lights red
4. Player touches (0,0) -> +10 points, (0,0) off, (0,1) lights green
5. Player touches (0,1) -> +10 points, (0,1) off, (0,2) lights blue
6. ... until (7,7)
7. If wrong tile touched -> FADE_TO_RED short animation -> correct tile re-lights
8. If 90 seconds pass -> DESCENDING_CURTAIN -> loss
9. If all 64 tiles correctly touched -> RADIAL_BURST -> win

---

## Using Animations

### Available Animations

#### Countdown

```java
ctx.animations().playCountdown() // default 1000ms per digit
ctx.animations().playCountdown(700) // custom 700ms
```

- If board smaller than 3x5: whole board lights red, yellow, green (simple)
- If larger: digits 3,2,1 rendered with 5x3 pattern centered then green blink

#### Win

```java
public enum WinAnimationType {
    RADIAL_BURST,    // colored wave from center outward
    RAINBOW_SWEEP,   // rainbow column sweep
    SPARKLE,         // random sparkles
    FIREWORKS        // fireworks at random points
}

ctx.animations().playWinAnimation() // default RADIAL_BURST
ctx.animations().playWinAnimation(WinAnimationType.FIREWORKS)
```

#### Lose

```java
public enum LoseAnimationType {
    FADE_TO_RED,          // fade to red with noise
    DESCENDING_CURTAIN,   // red curtain from top
    CRUMBLE,              // crumble from yellow to red
    PULSE_RED             // red pulse blink
}

ctx.animations().playLoseAnimation()
ctx.animations().playLoseAnimation(LoseAnimationType.CRUMBLE)
```

#### Standby

```java
public enum StandbyAnimationType {
    BREATHING,      // corners and border breathing blue
    CORNER_PULSE,   // colored pulse in corners
    WAVE_BORDER,    // wave on border
    RANDOM_TWINKLE  // random white twinkle
}

ctx.animations().playStandbyAnimation()
ctx.animations().playStandbyAnimation(StandbyAnimationType.WAVE_BORDER)
```

**Special feature of standby:** These animations run infinitely until cancelled. To use as "idle before start":

```java
try {
    ctx.animations().playStandbyAnimation(StandbyAnimationType.BREATHING)
        .get(2, TimeUnit.SECONDS); // run 2 seconds
} catch (TimeoutException e) {
    ctx.animations().cancelCurrent(); // cancel
}
```

### Technical Implementation of Animations

All animations run on a `SingleThreadExecutor` named `tileboard-animation`. Each new animation cancels previous one with generation-based cooperative cancellation pattern (detailed in game engine README).

```java
// Inside AnimationSystem
private final AtomicLong generation = new AtomicLong(0);

private CompletableFuture<Void> run(Consumer<RunToken> body) {
    synchronized (runLock) {
        if (currentTask!=null) currentTask.cancel(true);
        if (currentResult!=null) currentResult.cancel(false);
        long myGen = generation.incrementAndGet();
        RunToken token = new RunToken(myGen);
        // submit to executor...
    }
}

public final class RunToken {
    boolean isCancelled() { return generation.get() != myGeneration; }
    boolean sleep(long ms) { if (isCancelled()) return false; Thread.sleep(ms); return !isCancelled(); }
    void pause(long ms) { if (!sleep(ms)) throw new AnimationCancelledException(); }
    void show(Board<TileColor> board) { if (isCancelled()) throw new AnimationCancelledException(); boardPublisher.accept(board); }
}
```

Animations cooperatively check if cancelled and if so exit cleanly without force-killing thread.

### Using Animations in Spring App

In Spring app, animations are available via `GameContext.animations()` which is created in `FeatureBundle` and its `boardPublisher` is same as `BoardChannel.publish` which eventually goes to `TileGatewayClient.sendBoard`.

For use outside games (e.g., in admin controller for board testing):

```java
@RestController
public class AdminAnimationController {

    private final GameEngineManager engineManager;

    @PostMapping("/api/v1/admin/animations/countdown")
    public void playCountdown() {
        GameEngine engine = engineManager.require();
        // get active session or create temp session for testing
        // ...
    }
}
```

But recommended to use animations only inside games, because `AnimationSystem` is per-session.

---

## Deep Dive

### 1. DefaultSerialConnectionManager - synchronized + rollback + dual topology

**Problem:** `connect()` may be called concurrently from multiple threads (two admins at same time). Also opening ports may partially fail (first port opens, second fails).

**Solution:**

- `synchronized` on `connect()`, `disconnect()`, `assign()` -> only one thread can change state at a time
- `openedThisAttempt` + `finally` rollback -> if any step fails, all transports opened in this attempt are closed so OS handle doesn't leak
- `EnumMap` for `assignedPorts` -> optimized for enum keys
- `shared transport` detection: if IN and OUT same name, opened only once

```java
Map<PortRole, SerialTransport> openedThisAttempt = new EnumMap<>();
boolean success = false;
try {
    if (inPort != null && inPort.equals(outPort)) {
        SerialTransport shared = openPort(outPort, config);
        openedThisAttempt.put(OUT, shared);
        builder.transport(shared);
    } else {
        // open OUT and IN separately
    }
    newClient = builder.build();
    enableHandshakeIfDeviceKnown(newClient);
    success = true;
} finally {
    if (!success) closeQuietly(openedThisAttempt.values());
}
```

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

- `AtomicReference` thread-safe without synchronized for single value
- `get()` and `set()` both atomic and visible across threads
- `Optional` for "not yet configured" (null)

### 3. GameEngineManager - volatile + synchronized + null-before-close

```java
private volatile GameEngineImpl engine;

@EventListener
public synchronized void onGatewayConnected(GatewayConnectedEvent event) {
    if (engine != null) {
        log.warn("engine already bound - stopping");
        shutdownCurrentEngine();
    }
    engine = new GameEngineImpl(...);
}

private void shutdownCurrentEngine() {
    GameEngineImpl current = this.engine;
    if (current == null) return;
    this.engine = null; // immediately visible
    try { current.close(); } catch (RuntimeException e) { log.warn }
}

public synchronized Optional<GameEngine> current() {
    return Optional.ofNullable(engine);
}
```

- `volatile` for `engine` -> lock-free visibility for reads, but writes synchronized
- `synchronized` for writes -> prevents race between concurrent connect and disconnect
- `engine = null` before `close()` -> `current()`/`require()` never see half-closed engine (if we close then null, between those moments another thread could get half-closed engine)
- `shutdownCurrentEngine` null-safe and idempotent -> duplicate disconnect events don't NPE

### 4. BoardChannel - ReentrantLock + gatewayWriteLock + coalescing

Detailed in game engine README. Summary:

- `stateLock` (ReentrantLock) protects `buffer`
- `gatewayWriteLock` (synchronized Object) serializes writes on wire
- `sendLatest()` re-reads `snapshot()` -> coalescing semantics: latest consistent state sent, not old

### 5. GameState - synchronized HashMap

```java
public final class GameState {
    private final Map<String, Object> store = new HashMap<>();
    public synchronized <T> void put(String key, T value) { store.put(key, value); }
    public synchronized <T> Optional<T> get(String key, Class<T> type) { return Optional.of(type.cast(store.get(key))); }
}
```

- Plain `HashMap` with `synchronized` methods -> thread-safe for concurrent access from tick thread and callback thread
- `snapshot()` returns unmodifiable copy for SSE

### 6. AnimationSystem - generation + CompletableFuture + SingleThreadExecutor

Detailed in game engine README.

### 7. ScoreSystem - ConcurrentHashMap + AtomicInteger

```java
private final Map<String, AtomicInteger> scores = new ConcurrentHashMap<>();
public int add(String playerId, int delta) { return getOrCreate(playerId).addAndGet(delta); }
private AtomicInteger getOrCreate(String playerId) { return scores.computeIfAbsent(playerId, k -> new AtomicInteger(0)); }
```

- `ConcurrentHashMap` thread-safe for concurrent read/write
- `computeIfAbsent` atomic
- `AtomicInteger.addAndGet` with CAS, no global lock

### 8. GameTimer - volatile + AtomicReference

```java
private final AtomicReference<Runnable> onExpire = new AtomicReference<>();
private volatile Instant startedAt;
public void checkExpiry() {
    if (!isExpired()) return;
    Runnable cb = onExpire.getAndSet(null);
    if (cb!=null) cb.run();
}
```

- `volatile` for visibility without lock
- `getAndSet(null)` guarantees callback runs only once

### 9. CORS Filter - FilterRegistrationBean

```java
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
```

- `HIGHEST_PRECEDENCE` -> CORS checked before any other filter
- `*` for origins, methods, headers -> easy for development, should be restricted in production

---

## Tests and Execution

### Tests

```bash
mvn test -pl tileboard-app
```

- `TileboardApplicationTests`: contextLoads
- `TileboardPropertiesTest`: defaults and validation
- `ControllerUnitTest`: controller unit tests with MockMvc
- `InMemoryDeviceGeneralConfigurationServiceTest`: AtomicReference test
- `DefaultSerialConnectionManagerTest`: connect/disconnect, rollback, dual topology

### Execution

```bash
mvn spring-boot:run -pl tileboard-app
# or
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

### Device

| Method | Path | Body | Response |
|--------|------|------|----------|
| POST | /api/v1/devices/configure | {width, height} | DeviceConfigurationResponse |
| GET | /api/v1/devices/configuration | - | DeviceConfigurationResponse or 404 |

### Ports

| Method | Path | Body | Response |
|--------|------|------|----------|
| GET | /api/v1/ports | - | List<SerialPortResponse> |
| POST | /api/v1/ports/assign | {role, portName} | PortAssignment |
| GET | /api/v1/ports/assignment | - | PortAssignment |
| POST | /api/v1/ports/connect | - | ConnectionStatusResponse |
| POST | /api/v1/ports/disconnect | - | 204 |
| GET | /api/v1/ports/status | - | ConnectionStatusResponse |

### Games

| Method | Path | Body | Response |
|--------|------|------|----------|
| GET | /api/v1/games | - | List<GameDescriptorResponse> |
| POST | /api/v1/games/sessions | {gameId, players} | GameSessionResponse |
| GET | /api/v1/games/sessions | - | List<GameSessionResponse> |
| GET | /api/v1/games/sessions/{id} | - | GameSessionResponse |
| POST | /api/v1/games/sessions/{id}/stop | - | 204 |

### Stream

| Method | Path | Response |
|--------|------|----------|
| GET | /api/v1/stream/board | SSE Board updates |
| GET | /api/v1/games/events | SSE Game events |

### Actuator

| Method | Path |
|--------|------|
| GET | /actuator/health |
| GET | /actuator/info |

---

## Summary

This application:

1. **Abstracts hardware:** Only knows `SerialPortRegistry` interface, not jSerialComm
2. **Is thread-safe:** Correctly uses `AtomicReference`, `synchronized`, `ConcurrentHashMap`, `volatile`, `CAS`
3. **Is extensible:** Adding new game is just a `@Bean`
4. **Is production-ready:** TTL for sessions, rollback for connect, idempotent disconnect, CORS, Actuator, Swagger, configurable logging
5. **Is educational:** Sample game `SequentialTouchGame` shows all animations and concurrency patterns

For more questions, see READMEs of `tileboard-serial-protocol` and `tileboard-game-engine` modules.

---

**Author:** Tileboard Platform Team  
**Version:** 1.0.0  
**Java:** 17+  
**Spring Boot:** 3.3.4
