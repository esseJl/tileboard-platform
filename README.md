# Tileboard Platform - Comprehensive Platform Documentation

> **Tileboard Platform** is a complete system for controlling an LED tile board (m × n, maximum 255 tiles) over serial and running interactive games on it. The repository contains three Maven modules: a transport-agnostic serial protocol, a framework-free game engine, and a Spring Boot backend application (REST + SSE + JPA/Hibernate persistence).

---

## Table of Contents

1. [Platform Introduction](#platform-introduction)
2. [Overall Architecture](#overall-architecture)
3. [Modules](#modules)
4. [Prerequisites](#prerequisites)
5. [Quick Start](#quick-start)
6. [Application Configuration](#application-configuration)
7. [Persistence, Settings and Cache](#persistence-settings-and-cache)
8. [Serial Connection Lifecycle](#serial-connection-lifecycle)
9. [REST and SSE API](#rest-and-sse-api)
10. [Typical Workflow](#typical-workflow)
11. [Practical Example - SequentialTouchGame](#practical-example)
12. [Animations](#animations)
13. [Concurrency and Thread-Safety Across the Platform](#concurrency)
14. [Tests and Build](#tests-and-build)
15. [Repository Structure](#repository-structure)
16. [Roadmap](#roadmap)
17. [FAQ](#faq)

---

## Platform Introduction

Tileboard Platform was built to solve these problems:

- **Communication with LED Tile Board hardware** over serial (115200-8-N-1 by default) with a noise-resistant framing protocol (`0xFC … '#'` frames, resynchronization, and a plausibility ceiling).
- **Hardware abstraction:** game code does not know whether jSerialComm, another implementation, or a mock is being used. The Spring application has one hardware seam: `SerialGatewayConfig`.
- **Production-ready game engine:** scoring, health, levels, combos, timers, touch history, neighbor finding, patterns, waves, memory, reaction statistics, graph queries, animations, event bus, and SSE; the core itself does not require Spring.
- **Spring Boot backend:** REST APIs for device configuration, serial port management, and game sessions, plus real-time SSE streaming and Persian error messages.
- **Durable configuration:** JPA/Hibernate creates and updates one generic `app_settings` table in an embedded SQLite database. Device geometry and port assignment survive a restart when the JPA settings store is enabled (the current default); an in-memory store is available for demos and tests.
- **Verified serial health:** the application checks that the ports belonging to the live session still exist on the host, exposes that result through Actuator, releases a lost gateway, and can reconnect it automatically.
- **Extensibility:** adding a game is normally just adding a Spring `@Bean`; the engine auto-registers all `Game` beans without changes to the protocol.

### Key Features

- ✅ **Transport-agnostic:** the protocol library has no required serial-library dependency; jSerialComm `2.11.0` is optional there and is supplied by the application.
- ✅ **Framework-free core:** the game engine works without Spring; `com.tileboard.engine.spring` is an optional adapter discovered through `AutoConfiguration.imports`.
- ✅ **Thread-safe:** the implementation uses `ConcurrentHashMap`, atomics, CAS, `ReentrantLock`, `synchronized`, `CopyOnWriteArrayList`, semaphores, and coalescing board writes where appropriate.
- ✅ **Built-in animations:** countdown plus four win, four lose, and four standby animation types, all with cooperative cancellation.
- ✅ **Event-driven:** `GameEventBus` supports `BLOCK` and `DROP_OLDEST` policies; the Spring adapter exposes game events as SSE with `SseGameEvent` JSON payloads.
- ✅ **Safe serial lifecycle:** connect rollback, shared or separate IN/OUT transports, handshake-before-start, idempotent connect/disconnect, `INTRODUCTION`/`START`/`STOP` commands, and best-effort cleanup.
- ✅ **Link monitoring:** configurable host-port scan cache, loss confirmation count, `serialLink` health component, and a watchdog that releases a vanished gateway.
- ✅ **Auto-reconnect:** startup recovery when all configuration is present, periodic retry after an unexpected loss, and an explicit disconnect that permanently disarms retries until the next explicit connect.
- ✅ **Persistent settings:** one `app_settings` row per typed setting, JSON serialization, `@Version` optimistic locking, one retry on a concurrent write, and a Caffeine read cache.
- ✅ **Observability:** Actuator `health`/`info`, liveness/readiness probes, Swagger UI from springdoc, and optional Micrometer engine gauges when a `MeterRegistry` exists.
- ✅ **Testable:** mock serial transports, concurrency tests, Spring context tests, link-health tests, and an opt-in hardware integration test.

---

## Overall Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│  Frontend / Mobile App / curl                                           │
│  HTTP REST + SSE (EventSource on /api/v1/stream/board)                  │
├─────────────────────────────────────────────────────────────────────────┤
│  tileboard-app (Spring Boot 3.3.4, Java 17)                             │
│  ├─ Controllers: Device, SerialPort, Game, Stream                       │
│  ├─ Services: settings-backed device config, serial lifecycle,          │
│  │            verified link monitor, auto-reconnect, SSE                  │
│  ├─ Settings: SettingsService = Jpa | InMemory                          │
│  ├─ Health: SerialLinkHealthIndicator → /actuator/health                │
│  ├─ GlobalExceptionHandler → ApiResponse{message, debugMessage}         │
│  └─ GameBeansConfig: SequentialTouchGame @Bean (startup-sized)          │
├─────────────────────────────────────────────────────────────────────────┤
│  Persistence: JPA/Hibernate (ddl-auto=update)                           │
│  ├─ SQLite file: app.db (relative to the process working directory)     │
│  ├─ app_settings(setting_key PK, value_json, updated_at, version)       │
│  └─ Read cache: Caffeine (cache "settings", TTL/max-size configurable)  │
├─────────────────────────────────────────────────────────────────────────┤
│  tileboard-game-engine (framework-free + Spring auto-configuration)      │
│  ├─ GameEngineImpl (exclusive owner, TTL reaper, teardown pool)         │
│  ├─ GameSessionImpl (BoardChannel, FeatureBundle, tick loop)             │
│  ├─ BoardChannel (coalescing and serialized gateway writes)              │
│  ├─ Features: scoring, timer, health, levels, combos, memory, ...       │
│  ├─ AnimationSystem (generation cancellation, daemon executor)          │
│  └─ Spring: GameEngineManager, event publisher, metrics binder           │
├─────────────────────────────────────────────────────────────────────────┤
│  tileboard-serial-protocol (framework-free, transport-agnostic)         │
│  ├─ Board<T>, Position, TileCodec, TileTouchCodec                      │
│  ├─ Protocol: Frame codec, command types, resynchronization             │
│  ├─ Transport: SerialTransport and optional jSerialComm implementation   │
│  └─ Gateway: TileGatewayClient and ID-addressing handshake               │
├─────────────────────────────────────────────────────────────────────────┤
│  Hardware: LED Tile Board (m × n, maximum 255 tiles) over serial         │
└─────────────────────────────────────────────────────────────────────────┘
```

**Data Flow:**

1. `TileGatewayClient` reads bytes from `SerialTransport` on its daemon callback executor.
2. `DefaultFrameCodec.decode` turns the byte stream into protocol `Frame`s with stateful, synchronized decoding and resynchronization.
3. `EngineFrameRouter` reassembles chunks and decodes `DATA_IN` payloads into a `Board<Boolean>` touch board.
4. `TouchFrameRouter` scans the board and routes `TileEvent`s to the session that exclusively owns the board.
5. `GameSessionImpl.handleTileEvent` records touch history and reaction speed while the session is `RUNNING`, then calls the game.
6. A game calls `ctx.setTile`, `publishBoard`, or `fillBoard`; `BoardChannel` coalesces the latest consistent state and sends it through the gateway to the hardware.
7. The same board/session changes are published to `GameEventBus`, converted by `SseGameEventPublisher`, and delivered to `SseEmitter` clients.
8. Device and port configuration writes go through `SettingsService` to JSON in `app_settings`; reads use the Caffeine cache in the JPA implementation.
9. `SerialLinkMonitor` independently verifies the host port list. After consecutive confirmed loss it releases the gateway and publishes `GatewayDisconnectedEvent` so the engine closes.
10. `SerialAutoReconnector` periodically retries an armed connection after the hardware returns; an explicit `/disconnect` clears the armed intent.

---

## Modules

### 1. `tileboard-serial-protocol`

**Responsibility:** pure serial protocol and transport library with no Spring dependency.

**Key classes:**

- `Board<T>`: generic mutable grid with dimensions, indexing, fill, iteration, filtering, copying, and wire conversion.
- `Position`: validated `(row, col)` record.
- `TileCodec<T>`, `TileEncoder`, `TileDecoder`, and `TileTouchCodec`: domain-to-wire conversion, including canonical `0x00`/`0x01` touch states.
- `ProtocolConstants`, `Frame`, `FrameEncoder`, `FrameDecoder`, `Command`, and `CommandType`: framing and command model.
- `DefaultFrameCodec`: stateless encoding with a 65535-byte guard and synchronized stateful decoding with resynchronization and a 4096-byte plausibility ceiling.
- `SerialTransport`, `SerialPortRegistry`, `SerialPortConfig`, `SerialPortInfo`, `Parity`, `FlowControl`, and `DataListener`: hardware abstraction.
- `JSerialCommTransport` and `JSerialCommPortRegistry`: ready-made optional jSerialComm implementation.
- `TileGatewayClient`: high-level gateway with copy-on-write listeners, serialized writes, a daemon callback executor, ID handshake support, and robust close behavior.
- `HandshakeCoordinator`, `DeviceAddress`, `AddressResolver`, `SequenceValidator`, and `SequentialIdSequenceValidator`: board addressing handshake.
- `LocalizableException` plus protocol, board, port, and transport exception types.

**Full documentation:** [tileboard-serial-protocol/README.md](tileboard-serial-protocol/README.md) and the concise [README.en.md](tileboard-serial-protocol/README.en.md).

### 2. `tileboard-game-engine`

**Responsibility:** production-ready, framework-free game engine with an optional Spring adapter.

**Key classes:**

- `Game`, `GameDescriptor`, `GameContext`/`CoreGameContext`/`BoardContext`, `SessionControl`, `FeatureProvider`, `GameState`, `GameStatus`, `GameResult`, and `SessionSnapshot`: game contract and session data.
- `GameEngine`/`GameEngineImpl`: game lookup, exclusive board ownership, per-session TTL reaper, and teardown.
- `GameSession`/`GameSessionImpl`: session lifecycle, tick loop, `START`/`STOP` protocol, event routing, and exactly-once finish.
- `GameRegistry`/`DefaultGameRegistry`/`GameFactory`: game registration and construction.
- `BoardChannel`, `BoardFrameBroadcaster`, and `BoardFrameListener`: coalesced board publishing.
- `FeatureBundle`: scoring, health, levels, combos, timers, touch history, touch analysis, board helpers, neighbors, patterns, randomness, waves, memory, reaction tracking, graph queries, and animations.
- `AnimationSystem`: countdown, win/lose/standby animations with generation-based cooperative cancellation.
- `GameEvent`, `GameEventType`, `GameEventBus`, `GameEventBusImpl`, and `SubscriptionOptions`: bounded, thread-safe event delivery.
- `ColorTileCodec`, `EngineFrameRouter`, and `TouchFrameRouter`: board frame decoding, reassembly, and exclusive-owner routing.
- `SseGameEvent`, `SseGameEventType`, and `GameEventSseEmitter`: SSE DTOs, event names, heartbeat, and per-client queues.
- `TileboardEngineAutoConfiguration`, `TileboardEngineProperties`, `GameEngineManager`, gateway lifecycle events, `SseGameEventPublisher`, and `GameEngineMetricsBinder`: Spring integration.

**Full documentation:** [tileboard-game-engine/README.md](tileboard-game-engine/README.md).

### 3. `tileboard-app`

**Responsibility:** Spring Boot backend that bridges the serial gateway and game engine to HTTP clients.

**Key classes:**

- `TileboardApplication`: `@SpringBootApplication` and `@ConfigurationPropertiesScan` entry point.
- Configuration: `TileboardProperties`, `DeviceConfiguration`, `SerialGatewayConfig`, `GeneralConfiguration` (permissive CORS), `CacheConfig`, `CacheSettingsProperties`, `SerialMonitorConfig`, `SerialMonitorProperties`, `SerialAutoReconnectConfig`, and `SerialAutoReconnectProperties`.
- Controllers: `DeviceController`, `SerialPortController`, `GameController`, and `StreamController`.
- DTOs: `ApiResponse`, `ApiResponses`, `Status`, device/port DTOs, game descriptor/session DTOs, `StartGameRequest`, and `PlayerRequest`.
- Device service: `DeviceConfigurationService` and `SettingsBackedDeviceConfigurationService`.
- Serial service: `SerialConnectionManager`, `DefaultSerialConnectionManager`, `PortRole`, `PortAssignment`, `ConnectionState`, `LinkCondition`, `SerialLinkStatus`, `SerialPortSummary`, `SerialLinkMonitor`, and `SerialAutoReconnector`.
- Streaming: `BoardStateBroadcaster` and `SseBoardStateBroadcaster` (`board-frame` events; the raw-board seam is currently not exposed by a controller).
- Settings: `SettingsService`, `SettingKey<T>`, `SettingKeys`, `InMemorySettingsService`, `JpaSettingsService`, `SettingsPersistenceException`, `SettingsSerializationConfig`, `ApplicationSetting`, and `SettingRepository`.
- Health and errors: `SerialLinkHealthIndicator`, `Messages`, `ApiException` subclasses, and `GlobalExceptionHandler`.
- Sample game: `GameBeansConfig` and `SequentialTouchGame`.

**Full application documentation:** [tileboard-app/README.md](tileboard-app/README.md).

---

## Prerequisites

- **Java 17+** (`maven.compiler.source/target = 17`).
- **Maven 3.8+**.
- **Git**.
- An LED tile board connected over USB for live operation. All regular tests run without hardware using mocks; only `TileboardHardwareIT` needs a board.
- No database server is required. The app uses the SQLite JDBC driver and creates `app.db` automatically in the process working directory.

---

## Quick Start

### 1. Build the platform

```bash
git clone <repo-url>
cd tileboard-platform
mvn clean install -DskipTests
```

### 2. Run the backend

From the repository root:

```bash
mvn spring-boot:run -pl tileboard-app
```

Or from the module directory:

```bash
cd tileboard-app
mvn spring-boot:run
```

Or run the packaged jar:

```bash
java -jar tileboard-app/target/tileboard-app-1.0.0.jar
# The prod profile currently mainly changes logging; it also explicitly selects SQLite and JPA settings.
java -jar tileboard-app/target/tileboard-app-1.0.0.jar --spring.profiles.active=prod
```

The server listens on `http://localhost:8080`.

- Swagger UI is provided by `springdoc-openapi-starter-webmvc-ui` (`2.6.0`) at the springdoc default path.
- Actuator health: `http://localhost:8080/actuator/health`.
- Exposed Actuator endpoints: `health` and `info`.
- Liveness/readiness probes: `/actuator/health/liveness` and `/actuator/health/readiness`.
- The default SQLite URL is `jdbc:sqlite:app.db`, so the file is relative to the directory from which the JVM is started. The repository `.gitignore` excludes common `app.db` locations.

### 3. Select the in-memory settings store when needed

The checked-in `application.yml` uses `tileboard.settings.store: jpa`, which preserves settings in SQLite. For a disposable run:

```bash
mvn spring-boot:run -pl tileboard-app \
  -Dspring-boot.run.arguments="--tileboard.settings.store=memory"

# Or with a packaged jar:
java -jar tileboard-app/target/tileboard-app-1.0.0.jar \
  --tileboard.settings.store=memory
```

With `memory`, device geometry and port assignments are lost when the process exits. The database may still be initialized by JPA, but this store does not write settings to it.

### 4. Test without hardware

```bash
mvn test
```

Only the opt-in hardware suite requires a port:

```bash
mvn verify -P hardware-tests -pl tileboard-serial-protocol \
  -Dtileboard.hardware.port=COM3
```

---

## Application Configuration

`TileboardApplication` enables `@ConfigurationPropertiesScan`, so the typed records below are bound directly from YAML, environment variables, or command-line properties.

### Current defaults in `tileboard-app/src/main/resources/application.yml`

| Property | Default | Purpose |
|---|---:|---|
| `spring.application.name` | `tileboard-game-engine` | Spring application name currently used by the app |
| `spring.datasource.url` | `jdbc:sqlite:app.db` | SQLite database file; relative to the JVM working directory |
| `spring.datasource.driver-class-name` | `org.sqlite.JDBC` | SQLite JDBC driver |
| `spring.jpa.database-platform` | `org.hibernate.community.dialect.SQLiteDialect` | Hibernate SQLite dialect |
| `spring.jpa.hibernate.ddl-auto` | `update` | Hibernate creates/extends mapped schema; no Flyway migration is used |
| `spring.jpa.open-in-view` | `false` | Disables Open Session in View |
| `server.port` | `8080` | HTTP port |
| `tileboard.settings.store` | `jpa` | `jpa` persists settings; `memory` uses a `ConcurrentHashMap` |
| `tileboard.cache.settings-ttl-seconds` | `300` | Caffeine settings-cache write TTL |
| `tileboard.cache.settings-max-size` | `100` | Maximum settings-cache entries |
| `tileboard.serial.baud-rate` | `115200` | Serial line speed |
| `tileboard.serial.data-bits` | `8` | Serial data bits |
| `tileboard.serial.stop-bits` | `1` | Serial stop bits |
| `tileboard.serial.read-timeout-millis` | `50` | Serial read timeout |
| `tileboard.serial.write-timeout-millis` | `50` | Serial write timeout |
| `tileboard.serial.handshake-min-sequence` | `0` | Auto mode; uses `max(2, min(width, height))` |
| `tileboard.serial-monitor.enabled` | `true` | Enables the verified link watchdog and health component |
| `tileboard.serial-monitor.interval` | `5s` | Fixed delay between watchdog checks |
| `tileboard.serial-monitor.scan-cache-ttl` | `1s` | Reuse duration for host port enumeration |
| `tileboard.serial-monitor.loss-confirmations` | `2` | Consecutive missing-port checks before teardown |
| `tileboard.serial-monitor.not-connected-is-down` | `true` | Maps no live session to Actuator `DOWN`; `false` maps it to `UNKNOWN` |
| `tileboard.serial-auto-reconnect.enabled` | `true` | Enables the auto-reconnect scheduler and startup listener |
| `tileboard.serial-auto-reconnect.interval` | `1m` | Fixed delay between reconnect attempts |
| `tileboard.engine.tick-interval` | `100ms` | Game-session tick interval |
| `tileboard.engine.session-ttl` | `30m` | Application override for inactive-session cleanup |
| `tileboard.engine.frame-reassembly-timeout` | `500ms` | Chunk reassembly timeout |
| `tileboard.engine.event-bus-queue-capacity` | `256` | Event bus queue capacity |
| `tileboard.engine.touch-history-max-size` | `2000` | Maximum touch-history entries |
| `management.endpoints.web.exposure.include` | `health,info` | Actuator endpoints exposed over HTTP |
| `management.endpoint.health.show-details` | `always` | Includes component details in health output |
| `management.endpoint.health.probes.enabled` | `true` | Enables liveness/readiness groups |
| `logging.level.com.tileboard` | `DEBUG` | Verbose development logging, including serial diagnostics |

`CacheSettingsProperties` clamps non-positive cache TTL and size values back to `300` and `100`. `SerialMonitorProperties` clamps an invalid interval to 5 seconds and a loss-confirmation count below 1 to 1. `SerialAutoReconnectProperties` falls back to a one-minute interval for a missing or non-positive value.

### Production profile

`application-prod.yml` is activated with `--spring.profiles.active=prod` or `SPRING_PROFILES_ACTIVE=prod`. It explicitly selects the same SQLite `app.db` URL, `SQLiteDialect`, `ddl-auto: update`, and `tileboard.settings.store: jpa`, and changes logging to `INFO`. Serial, engine, Actuator, and cache settings are inherited from the base file.

Standard Spring property overrides can be used without changing the repository, for example:

```bash
SPRING_DATASOURCE_URL='jdbc:sqlite:/var/lib/tileboard/app.db' \
SPRING_PROFILES_ACTIVE=prod \
java -jar tileboard-app/target/tileboard-app-1.0.0.jar
```

### Important deployment notes

- CORS is currently permissive: all origins, methods, and headers are allowed, with credentials disabled. Restrict `GeneralConfiguration` before exposing the app to an untrusted network.
- There is no authentication or Spring Security configuration yet.
- The default `DEBUG` logging is useful during serial protocol development but should normally be overridden to `INFO` in long-running deployments.
- A live serial handle cannot be persisted or restored after a JVM restart. Only operator configuration is durable.

---

## Persistence, Settings and Cache

### Storage model

The app keeps small pieces of configuration behind one typed interface rather than creating a table for every setting:

```
Controller → Service → SettingsService.set/get(SettingKey<T>)
                                      │
                         ┌────────────┴────────────┐
                         │                         │
             InMemorySettingsService       JpaSettingsService
             ConcurrentHashMap              Caffeine → JPA → SQLite
             process-local                  app_settings table
```

The database stack is deliberately simple and matches the checked-in code:

| Concern | Implementation |
|---|---|
| JPA provider | Hibernate through `spring-boot-starter-data-jpa` |
| Schema owner | Hibernate `spring.jpa.hibernate.ddl-auto: update` |
| Database | SQLite through `org.xerial:sqlite-jdbc` |
| Dialect | `org.hibernate.community.dialect.SQLiteDialect` |
| Settings entity | `ApplicationSetting` mapped to `app_settings` |
| Repository | `SettingRepository extends JpaRepository<ApplicationSetting, String>` |
| Read cache | Caffeine cache named `settings` |
| Migrations | None in the current application; there is no Flyway dependency or migration directory |

Hibernate creates or extends the table from the entity on startup. It does not provide a migration history, and changing/removing columns is not a substitute for a production migration process. A future schema-evolution strategy should be introduced before making incompatible entity changes.

The mapped table is approximately:

```sql
CREATE TABLE app_settings (
    setting_key VARCHAR(200) NOT NULL PRIMARY KEY,
    value_json  TEXT         NOT NULL,
    updated_at  TIMESTAMP    NOT NULL,
    version     BIGINT       NOT NULL
);
```

`value_json` is opaque JSON. `version` is managed by JPA `@Version`, allowing the service to detect a concurrent update.

### `SettingsService`

```java
public interface SettingsService {
    <T> Optional<T> get(SettingKey<T> key);
    <T> T getOrDefault(SettingKey<T> key);
    <T> void set(SettingKey<T> key, T value);
    void clear(SettingKey<?> key);
    boolean isSet(SettingKey<?> key);
}
```

`SettingKey<T>` is a record containing a stable string id, the Java type used for JSON conversion, and an optional default value. It is intentionally not an enum: adding a setting is an additive constant in `SettingKeys`.

Current keys are:

| Key id | Java type | Default | Example JSON |
|---|---|---|---|
| `device.configuration` | `DeviceConfiguration` | none; absent means not configured | `{"width":3,"height":3}` |
| `serial.port-assignment` | `PortAssignment` | `PortAssignment.empty()` | `{"inPort":"COM3","outPort":"COM3"}` |

The key id is a storage contract: add new ids, but do not rename or reuse existing ids.

### JPA and in-memory implementations

- `JpaSettingsService` is active when `tileboard.settings.store=jpa` or when the property is absent. Reads are cache-first and cache `Optional.empty()` as well. `set` serializes with the settings-only `ObjectMapper`, persists the entity, retries once after `OptimisticLockingFailureException`, and evicts the key in `finally`. `clear` deletes and evicts. `isSet` queries the repository directly.
- `InMemorySettingsService` is active only for `tileboard.settings.store=memory`. It uses a `ConcurrentHashMap`; values disappear on restart and no database read/write is performed by the settings service.
- Any unsupported value, such as `redis`, matches neither conditional bean and intentionally fails application startup instead of silently selecting an unsafe fallback.
- `SettingsSerializationConfig` supplies a dedicated mapper with `Jdk8Module`, `JavaTimeModule`, and `FAIL_ON_UNKNOWN_PROPERTIES=false`. Serialization problems are wrapped in `SettingsPersistenceException` and reach the generic HTTP 500 handler.

### What survives a restart?

| State | Stored? | Restart behavior |
|---|---|---|
| Device geometry | Yes with `store=jpa`; process-local with `memory` | Used by `GET /api/v1/device` and by game registration on startup |
| IN/OUT port assignment | Yes with `store=jpa`; process-local with `memory` | Available for the next connection |
| Open OS handles and `TileGatewayClient` | No | Must be opened again; auto-reconnect can do this when armed |
| Game sessions, scores, board state, SSE subscribers | No | Deliberately in memory |

### Adding a new setting

```java
public static final SettingKey<ThemeSettings> THEME =
        SettingKey.of("ui.theme", ThemeSettings.class, ThemeSettings.defaults());

settingsService.set(SettingKeys.THEME, requested);
ThemeSettings theme = settingsService.getOrDefault(SettingKeys.THEME);
```

No new table or repository is required. A genuinely new relational table would require an explicit schema-evolution plan; the current app does not include Flyway or SQL migrations.

---

## Serial Connection Lifecycle

### Assignment and connection topology

`SerialPortController` delegates all lifecycle work to `DefaultSerialConnectionManager`:

- `OUT` is required before connecting; `IN` is optional.
- If IN and OUT have the same name, one shared full-duplex `SerialTransport` is opened through `builder.transport(...)`.
- If they differ, separate input and output transports are opened.
- If only OUT is assigned, the app connects output-only and logs a warning: board touches and the ID handshake cannot be received. For a normal full-duplex controller, assign the same port to both roles.
- Assignments are persisted through `SettingsService`; changing an assignment while connected does not replace the current live session and takes effect on the next connect.

### Connect and disconnect behavior

`connect()` is synchronized and idempotent, but it does not blindly trust a remembered client:

1. If an old session exists, the host port list is checked and a lost session is released.
2. The manager requires an OUT assignment and a configured device before opening any port.
3. It builds `SerialPortConfig` from `tileboard.serial.*`, using `Parity.NONE`.
4. Every transport opened during this attempt is tracked. If any later step fails, all opened transports are closed in a rollback path.
5. The ID handshake is enabled before `TileGatewayClient.start()`.
6. A `GatewayConnectedEvent(client, width, height)` is published; the engine binds to the gateway.
7. The client starts and receives `INTRODUCTION` with `CommandType.SET` as a best-effort hardware initialization command.

When `handshake-min-sequence` is `0`, the minimum sequence is derived as `max(2, min(width, height))`; a positive configured value overrides it.

`disconnect()` is also synchronized and idempotent. It first disarms auto-reconnect, sends `STOP` best-effort, closes the gateway and every tracked transport, clears the live session, and publishes `GatewayDisconnectedEvent` even if cleanup encounters an exception.

### Verified link status

The manager stores the live session in a volatile immutable `LinkSession`. `linkStatus()` is safe for health probes and does not wait behind a slow connect/disconnect. It rate-limits OS port enumeration with `scan-cache-ttl`.

| `LinkCondition` | Meaning | Coarse `ConnectionState` |
|---|---|---|
| `NOT_CONNECTED` | No live gateway session exists | `DISCONNECTED` |
| `HEALTHY` | Every live-session port is still visible on the host | `CONNECTED` |
| `LINK_LOST` | At least one live-session port disappeared | `DISCONNECTED` |
| `UNVERIFIED` | The host port list could not be read | `CONNECTED` (not a claim of health) |

A failed enumeration is never treated as proof of link loss. A successful scan that misses a live port is reported with `missingPorts`, `connectedSince`, `checkedAt`, and an English diagnostic detail.

### Link monitor and automatic recovery

Two independent scheduled components handle different responsibilities:

1. **`SerialLinkMonitor`** runs every 5 seconds by default. It calls `linkStatus()` and resets its counter for any condition other than `LINK_LOST`. After two consecutive lost checks by default, it calls `releaseIfLinkLost()`. The manager performs a fresh scan, closes the dead gateway only when the scan succeeds and still misses a port, and publishes `GatewayDisconnectedEvent`. The counter prevents one transient enumeration failure from tearing down a live session.
2. **`SerialAutoReconnector`** runs every minute by default. At `ApplicationReadyEvent`, it arms itself and immediately attempts a connection only when the device is configured and both IN and OUT assignments are present. An explicit `POST /api/v1/ports/connect` arms the intent as well (OUT is still the only required port for that request). While armed, `reconnectIfNeeded()` waits until the required host ports are visible and retries operational failures without failing the scheduler.

An explicit `POST /api/v1/ports/disconnect` clears the armed intent, including when there is no live session. This prevents the scheduler from undoing an operator's deliberate disconnect. The next explicit connect arms it again.

### Actuator health

`SerialLinkHealthIndicator` is exposed as the `serialLink` component under `/actuator/health`:

| Condition | Actuator status |
|---|---|
| `HEALTHY` | `UP` |
| `LINK_LOST` | `DOWN` |
| `UNVERIFIED` | `UNKNOWN` |
| `NOT_CONNECTED` | `DOWN` by default, or `UNKNOWN` when `not-connected-is-down=false` |

The response includes `state`, `condition`, `checkedAt`, live IN/OUT ports, `connectedSince`, `missingPorts`, and a reason when applicable. Use liveness/readiness endpoints for container probes; the serial-link condition is intentionally not a reason to restart a container because a disconnected board is an operator/hardware event, not an application crash.

---

## REST and SSE API

### Response envelope

Most REST endpoints return:

```java
record ApiResponse(
    Status status,          // SUCCESS, INFO, WARNING, ERROR
    String message,
    Object data,
    Object extra,
    String debugMessage
) {}
```

Success responses are created by `ApiResponses`. Error responses have `status: ERROR`, a user-facing message, and the raw English diagnostic in `debugMessage`. Error localization is fixed to Persian (`fa`) by `Messages`; missing translation keys fall back to the exception message. Validation messages supplied by DTO annotations are included in the diagnostic text.

### Device: `/api/v1/device`

```text
GET  /api/v1/device
POST /api/v1/device       body: {"width":3,"height":3}
```

The request has `@Min(1)`/`@Max(255)` validation on each dimension, and `DeviceConfiguration` additionally enforces `width × height <= 255`. A successful response contains:

```json
{
  "status": "SUCCESS",
  "message": "Device successfully configured.",
  "data": {"width": 3, "height": 3, "tileCount": 9},
  "extra": null,
  "debugMessage": null
}
```

`GET` returns HTTP 409 with `device.not_configured` before the first configuration.

### Ports: `/api/v1/ports`

| Method | Path | Body / result |
|---|---|---|
| `GET` | `/api/v1/ports` | Lists `{systemName, description}` for every visible host port |
| `POST` | `/api/v1/ports/{role}/assign` | Body `{portName}`; `role` is `IN` or `OUT` |
| `GET` | `/api/v1/ports/status` | `{state, inPort, outPort}` |
| `POST` | `/api/v1/ports/connect` | Connects and returns the same status shape |
| `POST` | `/api/v1/ports/disconnect` | Sends STOP best-effort, disconnects, and returns the same status shape |

`OUT` must be assigned before connect; `IN` may be omitted. The status response returns the live-verified coarse state but the port names come from the persisted assignment, so an assignment changed during a live session can differ from the session ports until reconnect.

### Games: `/api/v1/games`

| Method | Path | Result |
|---|---|---|
| `GET` | `/api/v1/games` | `ApiResponse.data` contains registered `GameDescriptorResponse` values; available before serial connect |
| `POST` | `/api/v1/games/sessions` | Starts a game from `{gameId, players:[{name, role}]}` |
| `GET` | `/api/v1/games/sessions` | Lists active sessions; returns an empty list when the engine is disconnected |
| `GET` | `/api/v1/games/sessions/{sessionId}` | Returns raw `GameSessionResponse`, not an `ApiResponse` envelope |
| `POST` | `/api/v1/games/sessions/{sessionId}/stop` | Stops the session and returns `204 No Content` |

`PlayerRequest.role` is required. The engine validates game existence, player count, exact board size, and exclusive board ownership. Starting a session without a connected gateway returns `engine.not_ready` (HTTP 409).

The current application registers `sequential-touch` with one player (`SOLO`). `GameBeansConfig` creates this bean once at startup using the persisted device dimensions, or 3×3 when no device configuration exists. Restart the application after changing board geometry so the descriptor is rebuilt with the new size.

### Game-event SSE: `/api/v1/stream`

```text
GET /api/v1/stream/board
GET /api/v1/stream/board/{sessionId}
```

Both endpoints produce `text/event-stream` and delegate to `SseGameEventPublisher`. The SSE event name is an `SseGameEventType` and the JSON data is an `SseGameEvent` containing the session/game identifiers, event type, `SessionSnapshot`, and timestamp.

Common event names are:

- `SESSION_LIFECYCLE`: session started, finished, or stopped.
- `BOARD_UPDATE`: a board frame was published.
- `TICK`: periodic session tick.
- `SCORE_UPDATE`, `GAME_STATE`, and `CUSTOM`: supported by the DTO layer; some are reserved or depend on the event source.

```javascript
const events = new EventSource('/api/v1/stream/board');

events.addEventListener('BOARD_UPDATE', event => {
  const message = JSON.parse(event.data);
  console.log(message.data.board);
});

events.addEventListener('SESSION_LIFECYCLE', event => {
  console.log(JSON.parse(event.data));
});
```

### Raw board-frame broadcaster

`SseBoardStateBroadcaster` subscribes to the engine's `BoardFrameBroadcaster` and can fan out every transmitted frame as an SSE event named `board-frame`. It converts wire bytes to unsigned `int[]` values and removes dead emitters. It is an extension seam only: no current controller exposes it, so the public stream endpoints above are game-event streams.

### Actuator endpoints

```text
GET /actuator/health
GET /actuator/info
GET /actuator/health/liveness
GET /actuator/health/readiness
```

Only `health` and `info` are exposed by the current configuration, and health details are shown unconditionally. See [Actuator health](#actuator-health) for `serialLink` behavior.

### Error status mapping

| Exception / failure | HTTP status | Error code |
|---|---:|---|
| `DeviceNotConfiguredException` | 409 | `device.not_configured` |
| `PortsNotAssignedException` | 409 | `ports.not_assigned` |
| `NoActiveGameException` | 409 | `game.no_active` |
| `EngineNotReadyException` | 409 | `engine.not_ready` |
| `GameSessionException` | 409 | engine-provided code |
| `GameNotFoundException` | 404 | `game.not_found` |
| `SerialPortOperationException` | 502 | `serial.port_operation_failed` |
| Protocol/transport/board exception | 502 | library-provided code |
| Validation, malformed JSON, type mismatch, illegal argument | 400 | validation/request code |
| Unhandled exception | 500 | `server.internal_error` |

`GatewayNotConnectedException` exists in the application hierarchy but the game endpoints currently use the engine's `EngineNotReadyException` instead. `SettingsPersistenceException` has no dedicated handler and therefore uses the generic 500 path.

---

## Typical Workflow

The following workflow assumes a 3×3 full-duplex board on `COM3` and the default JPA settings store.

### Step 1: Configure device geometry

```bash
curl -X POST http://localhost:8080/api/v1/device \
  -H 'Content-Type: application/json' \
  -d '{"width":3,"height":3}'
```

### Step 2: Discover and assign ports

```bash
curl http://localhost:8080/api/v1/ports

# A shared full-duplex port is assigned to both roles.
curl -X POST http://localhost:8080/api/v1/ports/OUT/assign \
  -H 'Content-Type: application/json' \
  -d '{"portName":"COM3"}'
curl -X POST http://localhost:8080/api/v1/ports/IN/assign \
  -H 'Content-Type: application/json' \
  -d '{"portName":"COM3"}'
```

For separate half-duplex ports, use different names for IN and OUT. Assignment is persisted immediately; no connection is opened by an assignment request.

### Step 3: Connect

```bash
curl -X POST http://localhost:8080/api/v1/ports/connect
```

A successful connection normally produces logs similar to:

```text
Enabling id handshake for a 3x3 board (minimumSequence=3)
Tile board gateway connected (input=COM3, output=COM3)
sent INTRODUCTION to hardware (3X3 board)
```

If all settings are already present at startup, the auto-reconnector may attempt this before the manual request. `POST /api/v1/ports/connect` remains idempotent.

### Step 4: Inspect games and start one

```bash
curl http://localhost:8080/api/v1/games

curl -X POST http://localhost:8080/api/v1/games/sessions \
  -H 'Content-Type: application/json' \
  -d '{"gameId":"sequential-touch","players":[{"name":"Ali","role":"SOLO"}]}'
```

The response contains `{sessionId, gameId, status}` in `data`. Scores and board snapshots arrive over SSE.

### Step 5: Subscribe to events

```bash
curl -N -H 'Accept: text/event-stream' \
  http://localhost:8080/api/v1/stream/board

# Or scope the stream to one session:
curl -N -H 'Accept: text/event-stream' \
  http://localhost:8080/api/v1/stream/board/{sessionId}
```

### Step 6: Stop the session and disconnect

```bash
curl http://localhost:8080/api/v1/games/sessions/{sessionId}
curl -X POST http://localhost:8080/api/v1/games/sessions/{sessionId}/stop
curl -X POST http://localhost:8080/api/v1/ports/disconnect
```

The last request disarms auto-reconnect. Re-plugging a board after an unexpected loss does not require a new assignment; the armed scheduler will retry, or an explicit connect can be used.

---

## Practical Example

### `SequentialTouchGame` summary

`tileboard-app/src/main/java/com/tileboard/app/game/SequentialTouchGame.java` is a stateless tutorial game. Per-session data is kept in `GameContext.state()` rather than mutable fields on the singleton Spring bean.

- The board is cleared and the `BREATHING` standby animation runs for two seconds.
- Countdown runs before play.
- All positions are visited in row-major order.
- The current tile is lit from a seven-color palette: red, green, blue, yellow, pink, light blue, white.
- A correct `TOUCH` or `HOLD` awards 10 points, turns the tile off, and lights the next tile.
- A wrong touch plays `FADE_TO_RED` and then relights the expected tile; `RELEASE` is ignored.
- After the final tile, `RADIAL_BURST` plays and the player wins.
- A 90-second timer plays `DESCENDING_CURTAIN` and ends the session as a loss.
- On an error, `PULSE_RED` is attempted before the session stops.

### Player perspective on a 3×3 board

1. Standby breathing animation for two seconds.
2. Countdown 3 → 2 → 1 plus green blink.
3. Tile `(0,0)` lights red.
4. Correct touches award 10 points and advance the sequence.
5. Wrong touches show red feedback but do not reduce the score.
6. Nine correct touches produce a win animation.
7. Timeout produces a lose animation and a finished session without winners.

**Game registration:** `GameBeansConfig` defines one `@Bean Game`. The engine auto-configuration registers it. Since that bean is created at startup, changing the persisted geometry requires an application restart.

**Full game tutorial:** [tileboard-app/README.md](tileboard-app/README.md).

---

## Animations

All animations are reached from `GameContext.animations()` and run on a single daemon executor. A generation counter cancels the previous animation cooperatively, and each operation returns a `CompletableFuture`.

| Category | API / default | Available types |
|---|---|---|
| Countdown | `playCountdown()`; 1000 ms per digit | Scalable board countdown |
| Win | `playWinAnimation()`; `RADIAL_BURST` | `RADIAL_BURST`, `RAINBOW_SWEEP`, `SPARKLE`, `FIREWORKS` |
| Lose | `playLoseAnimation()`; `FADE_TO_RED` | `FADE_TO_RED`, `DESCENDING_CURTAIN`, `CRUMBLE`, `PULSE_RED` |
| Standby | `playStandbyAnimation()`; `BREATHING` | `BREATHING`, `CORNER_PULSE`, `WAVE_BORDER`, `RANDOM_TWINKLE` |

Example:

```java
ctx.animations().playCountdown(1000).join();

ctx.animations()
    .playWinAnimation(AnimationSystem.WinAnimationType.RADIAL_BURST)
    .thenRun(() -> ctx.winSession(ctx.players()));

try {
    ctx.animations()
        .playStandbyAnimation(AnimationSystem.StandbyAnimationType.BREATHING)
        .get(2, TimeUnit.SECONDS);
} catch (Exception expected) {
    ctx.animations().cancelCurrent();
}
```

See [tileboard-game-engine/README.md](tileboard-game-engine/README.md) for the implementation and timing details.

---

## Concurrency and Thread-Safety Across the Platform

| Component | Technique | Guarantee / behavior |
|---|---|---|
| `DefaultFrameCodec` | synchronized stateful buffer, resynchronization, finally-trim | Malformed/noisy input cannot permanently wedge the decoder |
| `TileGatewayClient` | copy-on-write listeners, write lock, one callback executor | Listener iteration and serial writes are safe and ordered |
| `DefaultSerialConnectionManager` | synchronized mutations, volatile immutable session, rollback | No half-connected session; failed opens close every transport |
| Port scanning | short cache plus `ReentrantLock.tryLock()` | Health probes do not pile up slow host enumerations |
| Link verification | immutable `SerialLinkStatus`, fresh scan before teardown | A failed scan never destroys a live session |
| `SerialLinkMonitor` | `AtomicInteger` consecutive-loss counter | A link is released only after configured confirmation checks |
| `SerialAutoReconnector` | manager monitor and volatile armed intent | Admin disconnect and scheduler reconnect cannot race into an unwanted reconnect |
| `GameEngineImpl` | concurrent maps, atomic ownership, reaper and teardown pools | Board ownership and session cleanup remain race-safe |
| `SessionLifecycle` | atomic state and CAS | Finish logic runs exactly once |
| `BoardChannel` | state lock, gateway write lock, snapshot re-read | Latest consistent board state is sent without interleaved frames |
| `GameState` | synchronized methods over a `HashMap` | Game callbacks and tick thread can share session state |
| `ScoreSystem` | `ConcurrentHashMap` and `AtomicInteger` | Concurrent score updates are atomic |
| `GameTimer` | volatile values and atomics | Expiry callback is visible and fires once |
| `AnimationSystem` | generation counter, run lock, single executor, futures | New animations cooperatively cancel old ones |
| `GameEventBusImpl` | copy-on-write subscribers, bounded queues, locks, semaphore | `BLOCK` and `DROP_OLDEST` overflow policies are bounded |
| Frame routers | lock-guarded reassembly state | Partial input frames are reassembled with timeout handling |
| `JpaSettingsService` | transactions, Caffeine, `@Version`, eviction | Cache-first reads, negative caching, one optimistic-lock retry |
| `InMemorySettingsService` | `ConcurrentHashMap` | Process-local settings without a database |
| `GameEngineManager` | volatile reference, synchronized rebinding, null-before-close | No caller observes a half-closed engine |
| `SseBoardStateBroadcaster` | `CopyOnWriteArrayList` | Dead SSE emitters are removed on send failure |

The module READMEs contain deeper implementation walkthroughs and code excerpts.

---

## Tests and Build

### Build and unit tests

```bash
mvn clean install -DskipTests
mvn test
```

Run a single module:

```bash
mvn test -pl tileboard-serial-protocol
mvn test -pl tileboard-game-engine
mvn test -pl tileboard-app
```

The application test suite includes:

- `TileboardApplicationTests`: boots the Spring context and verifies the JPA/SQLite setup.
- `TileboardPropertiesTest`: checks serial-property defaults.
- `ControllerUnitTest`: device, port, game, session, and disconnected-engine controller behavior.
- `SerialLinkHealthIndicatorTest`: healthy, lost, unverified, not-connected, and failing-manager health states.
- `DefaultSerialConnectionManagerTest`: port discovery, assignment, required OUT behavior, cleanup, and idempotency.
- `SerialLinkMonitorTest`: consecutive-loss confirmation and exception isolation.
- `SerialAutoReconnectorTest`: startup arming, missing configuration, and scheduler failure isolation.
- `InMemoryDeviceGeneralConfigurationServiceTest`: device geometry limits.

All of these run without physical hardware.

### Hardware integration test

`TileboardHardwareIT` is executed by Maven Failsafe and is opt-in:

```bash
mvn verify -P hardware-tests -pl tileboard-serial-protocol \
  -Dtileboard.hardware.port=COM3 \
  -Dtileboard.hardware.width=3 \
  -Dtileboard.hardware.height=3 \
  -Dtileboard.hardware.baud=115200
```

Use `verify`, not only `test`, because the integration test is bound to Failsafe.

### Package and run

```bash
mvn clean package -DskipTests
java -jar tileboard-app/target/tileboard-app-1.0.0.jar
```

---

## Repository Structure

```
tileboard-platform/
├── pom.xml (parent reactor: serial-protocol, game-engine, app)
├── README.md (this file)
├── tileboard-serial-protocol/
│   ├── pom.xml
│   ├── README.md / README.en.md
│   └── src/main/java/com/tileboard/serial/
│       ├── board/ (Board, Position, TileCodec, TileEncoder, TileDecoder)
│       ├── protocol/ (Frame, codecs, ProtocolConstants, Command, CommandType)
│       ├── transport/ (interfaces, port configuration, metadata)
│       ├── transport/jserialcomm/ (JSerialComm implementations)
│       ├── gateway/ (TileGatewayClient and listeners)
│       ├── gateway/handshake/ (address and sequence handshake)
│       ├── exception/ (protocol, board, port, transport exceptions)
│       └── support/error/ (LocalizableException)
├── tileboard-game-engine/
│   ├── pom.xml
│   ├── README.md
│   └── src/main/java/com/tileboard/engine/
│       ├── core/ (game contract, engine, sessions, board channel, state)
│       ├── feature/ (scoring, timer, health, levels, combos, memory, graph, animations, ...)
│       ├── feature/neighbor/ (grid topology and neighbors)
│       ├── event/ (event bus and overflow policies)
│       ├── model/ (players, teams, colors, tile events)
│       ├── codec/ (color codec and engine frame router)
│       ├── sse/ (SSE emitter and DTOs)
│       ├── spring/ (auto-configuration, manager, events, publisher, metrics)
│       └── exception/ (engine exceptions)
└── tileboard-app/
    ├── pom.xml (Spring Boot 3.3.4; web, validation, actuator, JPA, cache, SQLite, springdoc, jSerialComm)
    ├── README.md (comprehensive application documentation)
    ├── app.db (created at runtime and ignored by Git)
    └── src/main/
        ├── java/com/tileboard/app/
        │   ├── TileboardApplication.java
        │   ├── config/ (serial, cache, CORS, monitor, auto-reconnect properties/config)
        │   ├── controller/ (device, ports, games, SSE stream)
        │   ├── dto/ (API envelope, device/port/game request and response records)
        │   ├── health/ (SerialLinkHealthIndicator)
        │   ├── service/device/ (settings-backed device configuration)
        │   ├── service/serial/ (connection manager, link status, monitor, auto-reconnector)
        │   ├── service/streaming/ (raw board SSE broadcaster seam)
        │   ├── settings/ (typed JPA/in-memory settings services)
        │   │   ├── conf/ (settings-only ObjectMapper)
        │   │   └── persistence/ (ApplicationSetting and repository)
        │   ├── exception/ (API exceptions and global handler)
        │   ├── i18n/ (Persian message catalog wrapper)
        │   └── game/ (GameBeansConfig and SequentialTouchGame)
        └── resources/
            ├── application.yml / application-prod.yml
            ├── messages.properties / messages_fa.properties
            └── (no db/migration directory in the current application)
```

---

## Roadmap

- [x] Transport-agnostic serial protocol (framing, gateway, handshake, localizable errors).
- [x] Production-ready game engine (features, animations, event bus, SSE DTOs, Spring adapter).
- [x] Spring Boot backend (device/ports/games REST, SSE streaming, Persian error catalog).
- [x] Device-sized `SequentialTouchGame` with countdown, standby, win, and lose animations.
- [x] Verified serial health with missing-port detection and Actuator details.
- [x] Link-loss watchdog with configurable confirmation count and gateway release.
- [x] Startup and scheduled serial auto-reconnect with explicit-disconnect disarming.
- [x] Durable generic settings through JPA/Hibernate, SQLite, `@Version`, and Caffeine.
- [x] Optional in-memory settings store for disposable development/test runs.
- [ ] Introduce a versioned schema migration strategy before incompatible entity changes.
- [ ] Web frontend (React/Vue) for board display and game control.
- [ ] Expose the raw board-mirror SSE endpoint backed by `SseBoardStateBroadcaster`.
- [ ] Settings admin API (list/clear keys over HTTP).
- [ ] Distributed settings store and cluster-safe cache (Redis or etcd behind `SettingsService`).
- [ ] Authentication and security (Spring Security).
- [ ] More games (Color Match, Memory, Reaction, and others).
- [ ] Multi-board support; the engine is currently single-exclusive-owner.
- [ ] Publish more feature events such as `SCORE_CHANGED` and `LEVEL_UP` from feature systems.
- [ ] Docker and Kubernetes deployment guidance.

---

## Contributing

1. Fork the repository.
2. Create a feature branch.
3. Add tests and update the relevant module README when behavior changes.
4. Run `mvn test`.
5. Open a pull request.

---

## License

Internal - Tileboard Platform Team

---

## Authors

Tileboard Platform Team

---

## FAQ

**Q: Can I test without hardware?**

A: Yes. Unit tests in all three modules run hardware-less. Only `TileboardHardwareIT` needs a board and a configured port; run it through the `hardware-tests` profile with `mvn verify`.

**Q: How do I create a new game?**

A: Implement `Game`, keep per-session state in `ctx.state()`, register the implementation as a Spring `@Bean`, and make its `GameDescriptor` board size match the connected device. See the game tutorial in [tileboard-app/README.md](tileboard-app/README.md).

**Q: How do I configure a full-duplex serial board?**

A: Assign the same system port to both `OUT` and `IN`. Assigning only OUT creates an output-only connection; touches and the inbound handshake cannot be received.

**Q: What happens when the USB serial adapter is unplugged?**

A: `serialLink` changes to `DOWN` after the host scan sees a missing session port. The watchdog waits for the configured number of consecutive confirmations (two by default), releases the gateway, and unbinds the game engine. If auto-reconnect is armed, the scheduler retries after the port returns. Use `POST /api/v1/ports/disconnect` to stop retries intentionally.

**Q: Where is my configuration stored?**

A: Device geometry and IN/OUT assignment are stored as JSON in the `app_settings` table when `tileboard.settings.store=jpa` (the checked-in default), using SQLite `app.db` and a Caffeine read cache. `memory` keeps them only in a `ConcurrentHashMap`. Live serial handles, game sessions, and SSE subscribers are never persisted.

**Q: Does the app use Flyway or H2?**

A: No. The current `tileboard-app/pom.xml` contains SQLite and Hibernate's community dialect, while the schema is managed with `spring.jpa.hibernate.ddl-auto=update`. There is no Flyway dependency, SQL migration directory, or H2 dependency in the current Spring app.

**Q: How do I add a persisted setting?**

A: Add an append-only `SettingKey<T>` constant to `SettingKeys` and use `SettingsService`. No new table is needed because values are stored as opaque JSON in `app_settings`. Plan a real migration strategy before introducing incompatible schema changes.

**Q: What are the SSE event names?**

A: Public game streams use `SESSION_LIFECYCLE`, `BOARD_UPDATE`, `TICK`, `SCORE_UPDATE`, `GAME_STATE`, and `CUSTOM` as defined by the engine's SSE layer. The separate raw-board broadcaster uses `board-frame` but is not currently exposed through a controller.

**Q: How do I understand the concurrency model?**

A: Read the concurrency table above and the “Deep Dive” sections in [tileboard-app/README.md](tileboard-app/README.md) and [tileboard-game-engine/README.md](tileboard-game-engine/README.md).

---

**Version:** 1.0.0
**Java:** 17+
**Spring Boot:** 3.3.4
**Persistence:** JPA/Hibernate (`ddl-auto: update`) + SQLite + Caffeine cache
**Date:** 2026-09-24
