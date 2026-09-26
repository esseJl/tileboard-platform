# tileboard-app - Comprehensive Spring Boot Backend Documentation

> **Module Mission:** Spring Boot application that drives an LED tile board over serial. Built on top of `tileboard-serial-protocol` and `tileboard-game-engine`. Includes REST API for device configuration, serial port management, game control, and SSE streaming, a verified serial-link watchdog with automatic reconnect, an Actuator health component for the link, and durable (JPA/SQLite) application settings. This module is the bridge between hardware and the web world.

---

## Table of Contents
1. [Overall Architecture and Platform Position](#overall-architecture)
2. [Tech Stack](#tech-stack)
3. [Package Structure](#package-structure)
4. [Configuration - application.yml and TileboardProperties](#configuration)
5. [DeviceConfiguration - Board Geometry](#deviceconfiguration)
6. [SerialGatewayConfig - Hardware Abstraction](#serialgatewayconfig)
7. [Services - Business Layer](#services)
8. [Serial Link Verification, Watchdog and Auto-Reconnect](#serial-link-lifecycle)
9. [Health - SerialLinkHealthIndicator and Actuator](#health)
10. [Persistence, Settings and Cache](#persistence-settings-and-cache)
11. [Controllers - REST API](#controllers)
12. [SSE Streaming](#sse-streaming)
13. [GameEngineManager - Spring and Engine Bridge](#gameenginemanager)
14. [Error Handling - GlobalExceptionHandler and i18n](#error-handling)
15. [Step-by-Step Run and API Usage Tutorial](#step-by-step-tutorial)
16. [Comprehensive Game Creation Tutorial - SequentialTouchGame Practical Example](#comprehensive-game-tutorial)
17. [Using win/lose/standby/countdown Animations](#using-animations)
18. [Deep Dive - Concurrency and Complex Logic](#deep-dive)
19. [Tests and Execution](#tests-and-execution)
20. [Full API Reference](#full-api-reference)

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
│  ├─ DeviceConfigurationService (settings-backed, no in-process copy)    │
│  │   └─ SettingsBackedDeviceConfigurationService (persists geometry)    │
│  ├─ SettingsService (SettingKey/SettingKeys registry)                   │
│  │   ├─ InMemorySettingsService (tileboard.settings.store=memory)       │
│  │   └─ JpaSettingsService (store=jpa, default; Caffeine + @Version)    │
│  ├─ SerialConnectionManager (verified link status; assignment lives     │
│  │   in SettingsService) └─ DefaultSerialConnectionManager              │
│  ├─ SerialLinkMonitor (watchdog: releases a gateway whose port is gone) │
│  ├─ SerialAutoReconnector (startup + scheduled reconnect, arm/disarm)   │
│  ├─ BoardStateBroadcaster (SSE, "board-frame" events)                   │
│  │   └─ SseBoardStateBroadcaster (wired to BoardFrameBroadcaster;       │
│  │      currently no controller exposes it — see SSE section)           │
│  └─ Messages (fixed-fa i18n) + GlobalExceptionHandler                   │
├─────────────────────────────────────────────────────────────────────────┤
│  Health: SerialLinkHealthIndicator → /actuator/health component         │
│  "serialLink" (UP/DOWN/UNKNOWN, computed from the verified link status) │
├─────────────────────────────────────────────────────────────────────────┤
│  Persistence (Hibernate ddl-auto=update, no Flyway)                     │
│  ├─ app_settings(setting_key PK, value_json, updated_at, version)       │
│  ├─ SQLite file app.db (jdbc:sqlite:app.db, relative to the JVM cwd)    │
│  ├─ Dialect from hibernate-community-dialects (SQLiteDialect)           │
│  └─ Caffeine cache "settings" (tileboard.cache.*)                       │
├─────────────────────────────────────────────────────────────────────────┤
│  GameEngineManager (@EventListener, volatile, synchronized)             │
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

1. Operator configures device: `POST /api/v1/device {width, height}` → `SettingsBackedDeviceConfigurationService` → `SettingsService.set(DEVICE_CONFIGURATION)` → JSON row in `app_settings` (SQLite `app.db`) + cache eviction; with the checked-in default `tileboard.settings.store=jpa` it survives restarts
2. Lists serial ports: `GET /api/v1/ports`
3. Assigns ports: `POST /api/v1/ports/OUT/assign {portName}` (+ optionally `IN`) → `DefaultSerialConnectionManager.assign()` → `settingsService.set(SERIAL_PORT_ASSIGNMENT, ...)`
4. Connects: `POST /api/v1/ports/connect` → `DefaultSerialConnectionManager.connect()` → requires an assigned OUT port **and** a stored device configuration *before* any port is opened → `TileGatewayClient` created (rollback closes every transport opened by a failed attempt) → handshake enabled → session stored as an immutable `LinkSession` → `GatewayConnectedEvent` published → `GameEngineManager` creates new `GameEngineImpl` → `client.start()` → `INTRODUCTION`/`SET` sent
5. Lists games: `GET /api/v1/games` (from `GameRegistry` — works even before connect)
6. Starts game: `POST /api/v1/games/sessions {gameId, players:[{name, role}]}` → `GameEngine.startGame()` → `GameSessionImpl` created → `START`/`SET` sent → game's `onStart()` called → `SESSION_STARTED` event
7. Connects SSE: `GET /api/v1/stream/board` (or `/board/{sessionId}`) → `SESSION_LIFECYCLE`/`BOARD_UPDATE`/`TICK`/… events in real time
8. Player touches tiles → `TileGatewayClient` receives `DATA_IN` frame → `EngineFrameRouter` reassembles → `Board<Boolean>` → `TouchFrameRouter` routes to exclusive owner → `GameSessionImpl.handleTileEvent` (records history + reaction speed) → `game.onTileEvent` (both `TOUCH` and `RELEASE` are delivered)
9. Game wins/loses/stops → `finishSession` (exactly once via CAS) → `game.onStop` → board cleared → `STOP`/`SET` sent → `SESSION_FINISHED`/`SESSION_STOPPED` event → engine releases the board
10. All the while the link is *verified*, not remembered: `GET /api/v1/ports/status` and `/actuator/health` call `linkStatus()`, which re-checks (through a short scan cache) that every port of the live session is still enumerated by the host OS — so an unplugged adapter shows up as `LINK_LOST`/`DOWN` without anyone calling `/disconnect`
11. `SerialLinkMonitor` (every `tileboard.serial-monitor.interval`, 5 s by default) acts on that verdict: after `loss-confirmations` consecutive `LINK_LOST` checks it calls `releaseIfLinkLost()`, which closes the dead gateway and publishes `GatewayDisconnectedEvent` so the engine unbinds. `SerialAutoReconnector` (every `tileboard.serial-auto-reconnect.interval`, 1 min by default) then brings the link back once the ports reappear — provided it is still *armed* (armed at startup when device + IN + OUT are configured and by every explicit `connect`; cleared only by an explicit `disconnect`)

---

## Tech Stack

- **Java 17**, **Spring Boot 3.3.4** (parent), **Spring MVC** (`spring-boot-starter-web`), **spring-boot-starter-validation** (bean validation on the request DTOs)
- **Spring Actuator** (`spring-boot-starter-actuator`) — `health` + `info` exposed, health details `always`, liveness/readiness probes enabled, plus the custom `serialLink` health component
- **Spring scheduling** — `@EnableScheduling` comes from `SerialMonitorConfig` / `SerialAutoReconnectConfig`, each registering a *fixed-delay* task through `SchedulingConfigurer` (no string-typed `@Scheduled` placeholders)
- **jSerialComm 2.11.0** for serial communication (declared here — it is `optional` in the protocol library, and the app is the module that talks to real hardware)
- **springdoc-openapi 2.6.0** (`springdoc-openapi-starter-webmvc-ui`) — Swagger UI at the springdoc default path
- **Jackson** for JSON (via `spring-boot-starter-web` + engine's `jackson-databind`/`jsr310`), plus a dedicated `settingsObjectMapper` (`Jdk8Module`, `JavaTimeModule`) for persisted settings
- **spring-boot-starter-data-jpa** (Hibernate ORM) with `spring.jpa.hibernate.ddl-auto: update` — Hibernate creates the `app_settings` table (and adds missing columns) from the entity; there is **no** Flyway/SQL migration in this module
- **SQLite** (`org.xerial:sqlite-jdbc`, version managed by the Boot BOM) + `hibernate-community-dialects` (`org.hibernate.community.dialect.SQLiteDialect`) as the only database — `jdbc:sqlite:app.db`, a file relative to the JVM working directory. There is no H2 dependency anywhere in the current app
- **spring-boot-starter-cache** + **Caffeine** for the `settings` read cache (`tileboard.cache.*`)
- **spring-boot-configuration-processor** (`optional`) for the typed `@ConfigurationProperties` records
- **In-house modules:** `com.tileboard:tileboard-serial-protocol:1.0.0` and `com.tileboard:tileboard-game-engine:1.0.0`
- **SLF4J** for logging (`logging.level.com.tileboard: DEBUG` in the base profile, `INFO` in `prod`)
- **Maven** for build (`spring-boot-maven-plugin` produces the runnable jar); **spring-boot-starter-test** (JUnit 5 + Mockito) for tests

---

## Package Structure

| Package | Responsibility |
|------|---------|
| `com.tileboard.app` | `TileboardApplication` (main, `@SpringBootApplication` + `@ConfigurationPropertiesScan`) |
| `config` | `TileboardProperties` (`tileboard.serial`), `DeviceConfiguration`, `SerialGatewayConfig` (the single hardware seam), `GeneralConfiguration` (CORS filter, `@EnableWebMvc`), `CacheConfig` + `CacheSettingsProperties` (`tileboard.cache`, `@EnableCaching`), `SerialMonitorProperties` + `SerialMonitorConfig` (`tileboard.serial-monitor`, `@EnableScheduling`), `SerialAutoReconnectProperties` + `SerialAutoReconnectConfig` (`tileboard.serial-auto-reconnect`, `@EnableScheduling`) |
| `controller` | REST controllers: `DeviceController`, `SerialPortController`, `GameController`, `StreamController` |
| `dto` | API DTOs: `ApiResponse`, `ApiResponses`, `Status`, `DeviceConfigurationRequest/Response`, `AssignPortRequest`, `SerialPortResponse`, `ConnectionStatusResponse`, `GameDescriptorResponse`, `StartGameRequest`, `PlayerRequest`, `GameSessionResponse` |
| `service.device` | `DeviceConfigurationService` + `SettingsBackedDeviceConfigurationService` |
| `service.serial` | `SerialConnectionManager` + `DefaultSerialConnectionManager` (connect/disconnect, verified `linkStatus()`, `releaseIfLinkLost()`, auto-reconnect intent), `PortRole`, `PortAssignment`, `ConnectionState` (coarse), `LinkCondition` (verified verdict), `SerialLinkStatus` (immutable snapshot), `SerialPortSummary`, `SerialLinkMonitor` (watchdog), `SerialAutoReconnector` (startup + scheduled reconnect) |
| `service.streaming` | `BoardStateBroadcaster` + `SseBoardStateBroadcaster` |
| `health` | `SerialLinkHealthIndicator` — the `serialLink` Actuator component built from `linkStatus()` |
| `settings` | `SettingsService` (generic typed store), `SettingKey<T>`, `SettingKeys` (append-only registry), `InMemorySettingsService` (`store=memory`), `JpaSettingsService` (`store=jpa`, default), `SettingsPersistenceException` |
| `settings.conf` | `SettingsSerializationConfig` — the settings-only `ObjectMapper` (`settingsObjectMapper`: `Jdk8Module`, `JavaTimeModule`, `FAIL_ON_UNKNOWN_PROPERTIES=false`) |
| `settings.persistence` | `ApplicationSetting` (`@Entity @Table(name="app_settings")`, `@Version`), `SettingRepository` (`JpaRepository<ApplicationSetting, String>`) |
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
  datasource:
    url: jdbc:sqlite:app.db
    driver-class-name: org.sqlite.JDBC
  jpa:
    database-platform: org.hibernate.community.dialect.SQLiteDialect
    open-in-view: false
    hibernate:
      ddl-auto: update    # Hibernate creates/extends the schema from the entities

server:
  port: 8080

tileboard:
  settings:
    store: jpa    #  dev/demo/test    ---- jpa on production
  cache:
    settings-ttl-seconds: 300
    settings-max-size: 100
  serial:
    baud-rate: 115200
    data-bits: 8
    stop-bits: 1
    read-timeout-millis: 50
    write-timeout-millis: 50
    handshake-min-sequence: 0  # 0 = auto (max(2, min(width,height)))
  serial-monitor:
    enabled: true
    interval: 5s
    scan-cache-ttl: 1s
    loss-confirmations: 2
    not-connected-is-down: true
  engine:
    tick-interval: 100ms
    session-ttl: 30m           # overrides the engine default of 1h
    frame-reassembly-timeout: 500ms
    event-bus-queue-capacity: 256
    touch-history-max-size: 2000

tileboard.serial-auto-reconnect:   # separate top-level key in the file, same prefix style
  enabled: true
  interval: 1m

management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      # Needed so /actuator/health shows the serialLink component (state, ports, reason).
      # This app has no authentication: on an untrusted network use "when-authorized" + Spring Security.
      show-details: always
      # /actuator/health/liveness and /readiness (exclude serialLink -> a missing board never restarts the container)
      probes:
        enabled: true

logging:
  level:
    com.tileboard: DEBUG
```

- **Datasource:** a **SQLite file** DB, `jdbc:sqlite:app.db`, created on first start. The path is *relative*, so the file lands in the directory the JVM was started from (`tileboard-app/` for `mvn spring-boot:run`, the repository root for `mvn spring-boot:run -pl tileboard-app`). Override it without touching the file with standard Spring mechanisms, e.g. `SPRING_DATASOURCE_URL=jdbc:sqlite:/var/lib/tileboard/app.db`. The `.gitignore` excludes the common `app.db` locations (`/app.db`, `/tileboard-app/app.db`, `/data/app.db`, `/tileboard-app/data/app.db`) plus the legacy H2 file paths.
- **`ddl-auto: update`:** Hibernate owns the schema - on startup it creates `app_settings` from `ApplicationSetting` if missing and adds new mapped columns. It never drops or renames anything, so removing/renaming a column or changing a type must be done by hand.
- **`tileboard.settings.store: jpa`:** the **checked-in default** persists settings through `JpaSettingsService`. Set it to `memory` (`--tileboard.settings.store=memory`) for a disposable run: the `ConcurrentHashMap` store loses everything on exit, although JPA/Hibernate still initializes the SQLite file because the datasource is configured unconditionally.
- **`tileboard.cache`:** the Caffeine cache sitting in front of settings reads — `settings-ttl-seconds` bounds how long another instance's write can stay invisible, `settings-max-size` is a safety bound (non-positive values are clamped back to 300/100 by `CacheSettingsProperties`).
- **`tileboard.serial-monitor`:** the verified-link watchdog + health component — `enabled` switches both the `SerialLinkMonitor` bean and its scheduler on/off, `interval` is the fixed *delay* between checks, `scan-cache-ttl` rate-limits OS port enumeration for `/actuator/health` polling, `loss-confirmations` is how many consecutive `LINK_LOST` checks are needed before the session is released, and `not-connected-is-down` decides whether "no session at all" is `DOWN` (HTTP 503) or `UNKNOWN`.
- **`tileboard.serial-auto-reconnect`:** `enabled` switches the scheduler + `ApplicationReadyEvent` listener on/off; `interval` is the fixed *delay* between reconnect attempts. Both are read directly by `@ConditionalOnProperty(matchIfMissing = true)`, so removing the block keeps the defaults (`true`, `1m`).
- **Actuator:** only `health` and `info` are exposed, health details are shown unconditionally (`show-details: always` — this app has no authentication, so use `when-authorized` + Spring Security on an untrusted network), and `probes.enabled: true` adds `/actuator/health/liveness` and `/actuator/health/readiness`, which deliberately exclude `serialLink`.

### application-prod.yml

```yaml
# Activate with --spring.profiles.active=prod (or SPRING_PROFILES_ACTIVE=prod).
#
# The base application.yml keeps DEBUG logging for com.tileboard, which is
# convenient during development but noisy (and, since it can log raw serial
# TX/RX hex frames - see JSerialCommTransport - unnecessarily verbose) for a
# long-running production deployment. This profile only overrides logging;
# everything else (serial settings, exposed actuator endpoints) is inherited
# from application.yml.
spring:
  datasource:
    url: jdbc:sqlite:app.db
    driver-class-name: org.sqlite.JDBC
  jpa:
    database-platform: org.hibernate.community.dialect.SQLiteDialect
    open-in-view: false
    hibernate:
      ddl-auto: update    # Hibernate creates/extends the schema from the entities
logging:
  level:
    root: INFO
    com.tileboard: INFO

tileboard:
  settings:
    store: jpa
  cache:
    settings-ttl-seconds: 300
    settings-max-size: 100
```

- The comment above ("only overrides logging") is the file's own historical wording — the profile now also *restates* the datasource (`jdbc:sqlite:app.db`), the SQLite dialect, `ddl-auto: update`, `tileboard.settings.store: jpa` and the cache tunables, all with exactly the same values as the base file. The only real behavioral change is logging: `root` and `com.tileboard` go from `DEBUG` to `INFO`.
- Everything else is inherited from `application.yml`: `tileboard.serial.*`, `tileboard.serial-monitor.*`, `tileboard.serial-auto-reconnect.*`, `tileboard.engine.*`, the exposed Actuator endpoints and the health probe settings.
- There is no `username`/`password` in either file — SQLite needs none. `org.hibernate.community.dialect.SQLiteDialect` comes from the `hibernate-community-dialects` dependency and must be named explicitly (Hibernate has no auto-detected dialect for SQLite).
- The SQLite URL is relative, so in a container `app.db` is written to the working directory: either mount that directory as a volume or pin an absolute path with `SPRING_DATASOURCE_URL=jdbc:sqlite:/data/app.db` (see [Docker (Optional)](#docker-optional)) so the persisted device geometry and port assignment outlive the container.

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

### SerialMonitorProperties

```java
@ConfigurationProperties(prefix = "tileboard.serial-monitor")
public record SerialMonitorProperties(
        @DefaultValue("5s") Duration interval,
        @DefaultValue("1s") Duration scanCacheTtl,
        @DefaultValue("2") int lossConfirmations,
        @DefaultValue("true") boolean notConnectedIsDown) {

    public SerialMonitorProperties {
        if (interval == null || interval.isZero() || interval.isNegative()) interval = Duration.ofSeconds(5);
        if (scanCacheTtl == null || scanCacheTtl.isNegative())              scanCacheTtl = Duration.ofSeconds(1);
        if (lossConfirmations < 1)                                          lossConfirmations = 1;
    }

    /** Defaults, for code (and tests) that build the manager by hand. */
    public static SerialMonitorProperties defaults() {
        return new SerialMonitorProperties(null, null, 2, true);
    }
}
```

- `interval` is the *fixed delay* between watchdog checks; `scanCacheTtl` is how long one host port enumeration may be reused (it is what keeps `/actuator/health` polling from hammering the OS — and note `scanCacheTtl = 0` is legal, it simply disables reuse, which is exactly what the unit tests use).
- `lossConfirmations` is clamped to at least `1`, so a single flaky enumeration can never tear a live session down by itself.
- `notConnectedIsDown` only affects the Actuator mapping of `NOT_CONNECTED` (`DOWN` → HTTP 503, `UNKNOWN` → HTTP 200), never the watchdog's behavior.
- `defaults()` exists for hand-built instances (tests, plain-Java usage) so the clamping rules are not duplicated.

### SerialAutoReconnectProperties

```java
@ConfigurationProperties(prefix = "tileboard.serial-auto-reconnect")
public record SerialAutoReconnectProperties(@DefaultValue("1m") Duration interval) {

    public SerialAutoReconnectProperties {
        if (interval == null || interval.isZero() || interval.isNegative()) interval = Duration.ofMinutes(1);
    }
}
```

- Only the retry *delay* lives here. The on/off flag (`tileboard.serial-auto-reconnect.enabled`) is read straight from the environment by `@ConditionalOnProperty(... matchIfMissing = true)` on both `SerialAutoReconnector` and `SerialAutoReconnectConfig`, so it is deliberately **not** a record component.
- A missing/zero/negative interval falls back to one minute rather than to a hot retry loop.

### The two schedulers

Both configs are `@EnableScheduling` + `SchedulingConfigurer` and register a **fixed delay** task (never a fixed rate), so a slow OS port enumeration or a slow port open can never overlap with the next run:

```java
// SerialMonitorConfig - one watchdog cycle every tileboard.serial-monitor.interval
registrar.addFixedDelayTask(monitor::checkOnce, properties.interval());

// SerialAutoReconnectConfig - first run one full interval after startup, because the
// startup attempt is made by SerialAutoReconnector.onApplicationReady() instead
registrar.addFixedDelayTask(new FixedDelayTask(reconnector::runOnce, properties.interval(), properties.interval()));
```

Both tasks stay registered for the whole application lifetime and are cheap no-ops while there is nothing to do (no session / auto-reconnect disarmed), so nothing has to be cancelled and re-created at runtime.

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
- It is **persisted**: the record is the value of `SettingKeys.DEVICE_CONFIGURATION` (`"device.configuration"`, stored as `{"width":3,"height":3}` in `app_settings`), so with `tileboard.settings.store=jpa` both `GET /api/v1/device` and `GameBeansConfig`'s startup sizing see the geometry from the previous run.

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
public class SettingsBackedDeviceConfigurationService implements DeviceConfigurationService {
    private final SettingsService settingsService;

    public SettingsBackedDeviceConfigurationService(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @Override
    public Optional<DeviceConfiguration> current() {
        return settingsService.get(SettingKeys.DEVICE_CONFIGURATION);
    }

    @Override
    public DeviceConfiguration configure(int width, int height) {
        DeviceConfiguration configuration = new DeviceConfiguration(width, height);   // validates <= 255 tiles
        settingsService.set(SettingKeys.DEVICE_CONFIGURATION, configuration);
        return configuration;
    }
}
```

- The earlier `InMemoryDeviceConfigurationService` (an `AtomicReference` held in the service) is gone: the geometry now lives in the generic `SettingsService`, so it survives a restart when `tileboard.settings.store=jpa` and disappears with the process when `memory`.
- This class is intentionally *thin*: it knows `SettingsService` and `SettingKeys` — **not** JPA, JSON or caching. Thread-safety and persistence semantics are entirely the store's business.
- `Optional` for "not yet configured" state (surfaced as `DeviceNotConfiguredException` → 409 by `DeviceController.getCurrentConfiguration()` **and** by `DefaultSerialConnectionManager.connect()`), backed by `SettingKey.defaultValue() == null`.
- `isConfigured()` is the default method the auto-reconnector consults at `ApplicationReadyEvent` — it decides whether the link may be brought up without an operator.
- Every consumer still only knows the interface, so swapping the store (memory ↔ jpa ↔ a future Redis) never touches this service or its callers — see [Persistence, Settings and Cache](#persistence-settings-and-cache).

### SerialConnectionManager

```java
public interface SerialConnectionManager {

    /** Every serial port currently visible to the host OS. */
    List<SerialPortSummary> listAvailablePorts();

    /** Assigns portName to role. Takes effect on the next connect(). */
    void assign(PortRole role, String portName);

    PortAssignment currentAssignment();

    /** Live-verified state: CONNECTED only while a session exists AND its ports are still present. */
    ConnectionState connectionState();

    /** Point-in-time verified status; re-checks the host port list (rate-limited), never blocks on connect(). */
    SerialLinkStatus linkStatus();

    /** Releases a session whose port has vanished; re-verifies with a fresh scan, never acts on a failed one. */
    boolean releaseIfLinkLost();

    /** Opens the assigned port(s) and publishes GatewayConnectedEvent. No-op if genuinely connected. */
    void connect();     // PortsNotAssignedException without OUT, DeviceNotConfiguredException without geometry

    /** Explicit (operator) action: disarms auto-reconnect, best-effort STOP, GatewayDisconnectedEvent. */
    void disconnect();

    /** Declares that the link SHOULD be kept up, allowing reconnectIfNeeded() to act. Does not connect. */
    void armAutoReconnect();
    boolean isAutoReconnectArmed();

    /** Scheduler entry point: reconnects only while armed and there is no healthy session. Never throws. */
    boolean reconnectIfNeeded();
}

public enum PortRole { IN, OUT }
public enum ConnectionState { DISCONNECTED, CONNECTED }                        // the coarse answer
public enum LinkCondition { NOT_CONNECTED, HEALTHY, LINK_LOST, UNVERIFIED }    // the verified verdict
public record PortAssignment(Optional<String> inPort, Optional<String> outPort) {
    public static PortAssignment empty() { ... }
    public boolean isOutAssigned() { return outPort.isPresent(); }
    public PortAssignment withRole(PortRole role, String portName) { ... }     // returns a new record
}
public record SerialPortSummary(String systemName, String description) {}

/**
 * Immutable snapshot. inPort/outPort are the ports of the LIVE session (what was actually opened),
 * NOT the persisted assignment - the two differ when the operator re-assigns a port while connected.
 */
public record SerialLinkStatus(
        ConnectionState state, LinkCondition condition,
        String inPort, String outPort, Set<String> missingPorts,
        Instant connectedSince, Instant checkedAt, String detail) {
    public SerialLinkStatus { missingPorts = missingPorts == null ? Set.of() : Set.copyOf(missingPorts); }
    public static SerialLinkStatus notConnected(Instant checkedAt) { ... }
}
```

`LinkCondition` is the finer-grained verdict behind the coarse `ConnectionState`:

| `LinkCondition` | Meaning | Reported `state` |
|---|---|---|
| `NOT_CONNECTED` | no gateway session exists (never connected, or the operator disconnected) | `DISCONNECTED` |
| `HEALTHY` | a session exists and every port it uses is still present on the host | `CONNECTED` |
| `LINK_LOST` | a session exists in memory but at least one of its ports disappeared | `DISCONNECTED` |
| `UNVERIFIED` | a session exists but the host's port list could not be read | `CONNECTED` (not a claim of health) |

#### DefaultSerialConnectionManager - Implementation

```java
@Service
public class DefaultSerialConnectionManager implements SerialConnectionManager {

    private final SerialPortRegistry portRegistry;
    private final TileboardProperties properties;
    private final DeviceConfigurationService deviceConfigurationService;
    private final SettingsService settingsService;
    private final ApplicationEventPublisher eventPublisher;
    private final long scanCacheTtlNanos;         // from SerialMonitorProperties.scanCacheTtl()

    /** Written only while holding this monitor; read lock-free. null = no session. */
    private volatile LinkSession session;

    /** Operator intent: true = the link SHOULD be up, so reconnectIfNeeded() may bring it back. */
    private volatile boolean autoReconnectArmed;

    private volatile PortScan lastScan;           // most recent host enumeration (rate-limits OS scans)
    private final ReentrantLock scanLock = new ReentrantLock();

    @Override
    public List<SerialPortSummary> listAvailablePorts() {
        // ONE OS enumeration for the whole list (it used to re-enumerate once per port).
        Map<String, String> descriptionByName = new LinkedHashMap<>();
        for (SerialPortInfo info : portRegistry.listPorts())
            descriptionByName.putIfAbsent(info.systemName(), info.description());
        return descriptionByName.entrySet().stream()
                .map(e -> new SerialPortSummary(e.getKey(), e.getValue() == null ? "" : e.getValue()))
                .toList();
    }

    @Override public synchronized void assign(PortRole role, String portName) {
        PortAssignment updated = currentAssignment().withRole(role, portName);
        settingsService.set(SettingKeys.SERIAL_PORT_ASSIGNMENT, updated);   // persisted
    }

    /** Not synchronized on purpose: a status read must not wait for a slow connect(). */
    @Override public PortAssignment currentAssignment() {
        return settingsService.getOrDefault(SettingKeys.SERIAL_PORT_ASSIGNMENT);   // PortAssignment.empty() default
    }

    // ------------------------------------------------------------------ verified status (lock-free reads)

    @Override public ConnectionState connectionState() { return linkStatus().state(); }

    @Override public SerialLinkStatus linkStatus() {
        LinkSession live = session;
        Instant now = Instant.now();
        if (live == null) return SerialLinkStatus.notConnected(now);

        PortScan scan = scanPorts();
        if (!scan.ok()) {
            // Cannot prove anything either way: do not claim "healthy", do not claim "lost".
            return statusOf(CONNECTED, UNVERIFIED, live, Set.of(), now,
                    "Host serial port list could not be read: " + scan.error());
        }
        Set<String> missing = missingPorts(live, scan);
        if (missing.isEmpty())
            return statusOf(CONNECTED, HEALTHY, live, Set.of(), now, "All session ports are present on the host.");
        return statusOf(DISCONNECTED, LINK_LOST, live, missing, now,
                "Serial port(s) " + missing + " are no longer present on the host "
                        + "(adapter unplugged or device re-enumerated).");
    }

    /** Returns a recent enumeration, re-scanning at most once per scan-cache-ttl. */
    private PortScan scanPorts() {
        PortScan cached = lastScan;
        if (isFresh(cached)) return cached;
        if (!scanLock.tryLock()) {
            // Another thread is already enumerating (possibly slowly, e.g. Windows + Bluetooth ports).
            // Serve the previous result instead of piling up; only wait if there is none at all.
            if (cached != null) return cached;
            scanLock.lock();
        }
        try {
            cached = lastScan;
            if (isFresh(cached)) return cached;
            PortScan fresh = scanNow();     // forced enumeration; never throws (failure -> PortScan.error)
            lastScan = fresh;
            return fresh;
        } finally { scanLock.unlock(); }
    }

    // ------------------------------------------------------------------ connect / disconnect

    @Override public synchronized void connect() { doConnect(true); }

    /** @param explicit true when an operator asked for the connection: that is a statement of intent,
     *                   so auto-reconnect is (re)armed. The scheduler passes false and therefore can
     *                   never re-arm something an admin switched off. */
    private void doConnect(boolean explicit) {
        if (session != null) {
            // A remembered session is NOT proof of a working link: without this check a stale
            // session would make connect() a no-op forever after a cable pull.
            if (!tearDownIfLinkLost()) {
                if (explicit) autoReconnectArmed = true;
                return;                      // genuinely connected - idempotent
            }
            log.warn("Previous serial link was lost; establishing a new one.");
        }

        PortAssignment assignment = currentAssignment();
        String outPort = assignment.outPort().orElse(null);
        String inPort  = assignment.inPort().orElse(null);
        if (outPort == null) throw new PortsNotAssignedException();               // 409 ports.not_assigned

        // Fail BEFORE any port is opened. (This used to be discovered only after the client had been
        // stored, via Optional.get(), leaving a half-connected state behind.)
        DeviceConfiguration device = deviceConfigurationService.current()
                .orElseThrow(DeviceNotConfiguredException::new);                  // 409 device.not_configured

        if (explicit) autoReconnectArmed = true;   // armed BEFORE opening: if the open fails (port busy,
                                                   // adapter still enumerating) the scheduler keeps retrying

        SerialPortConfig config = SerialPortConfig.builder()
                .baudRate(properties.baudRate()).dataBits(properties.dataBits()).stopBits(properties.stopBits())
                .parity(Parity.NONE)
                .readTimeoutMillis(properties.readTimeoutMillis()).writeTimeoutMillis(properties.writeTimeoutMillis())
                .build();

        Map<PortRole, SerialTransport> openedThisAttempt = new EnumMap<>(PortRole.class);
        TileGatewayClient newClient;
        boolean success = false;
        try {
            TileGatewayClient.Builder builder = TileGatewayClient.builder();
            if (inPort != null && inPort.equals(outPort)) {
                SerialTransport shared = openPort(outPort, config);               // one full-duplex transport
                openedThisAttempt.put(PortRole.OUT, shared);
                builder.transport(shared);
            } else {
                SerialTransport outTransport = openPort(outPort, config);
                openedThisAttempt.put(PortRole.OUT, outTransport);
                builder.outputTransport(outTransport);
                if (inPort != null) {
                    SerialTransport inTransport = openPort(inPort, config);
                    openedThisAttempt.put(PortRole.IN, inTransport);
                    builder.inputTransport(inTransport);
                } else {
                    log.warn("No IN port assigned - connecting OUTPUT ONLY. The board's touches and "
                            + "id handshake will never be received. ...");
                }
            }
            newClient = builder.build();
            enableIdHandshake(newClient, device);   // BEFORE start(), so no initial ID/CLEAR frame is dropped
            success = true;
        } finally {
            if (!success) closeQuietly(openedThisAttempt.values());   // rollback: never leak an OS handle
        }

        LinkSession live = new LinkSession(newClient, Map.copyOf(openedThisAttempt), inPort, outPort, Instant.now());
        this.session = live;
        this.lastScan = null;   // a scan taken before the ports were opened must not judge this session

        try {
            eventPublisher.publishEvent(new GatewayConnectedEvent(newClient, device.width(), device.height()));
            newClient.start();
        } catch (RuntimeException e) {
            // Do not leave a "connected" session behind whose client never started.
            log.error("Gateway failed to come up on '{}'; rolling the connection back", outPort, e);
            tearDown(live, false);
            throw new SerialPortOperationException("serial.port_operation_failed", new Object[]{outPort},
                    "Failed to start the tile gateway on '" + outPort + "'", e);  // 502
        }

        log.info("Tile board gateway connected (input={}, output={})", inPort, outPort);
        try {
            newClient.send(Command.INTRODUCTION, CommandType.SET);
            log.info("sent INTRODUCTION to hardware ({}X{} board)", device.width(), device.height());
        } catch (RuntimeException e) {
            log.warn("failed to send INTRODUCTION command (gateway may have closed)");
        }
    }

    @Override public synchronized void disconnect() {
        // Disarm FIRST and unconditionally - also when there is no live session (e.g. the link was already
        // lost and the admin wants the scheduler to stop retrying). Same monitor as reconnectIfNeeded(),
        // so an in-flight reconnect can never slip in behind this call.
        autoReconnectArmed = false;
        LinkSession live = session;
        if (live == null) return;   // idempotent
        tearDown(live, true);
    }

    /** Caller must hold this monitor. */
    private void tearDown(LinkSession live, boolean sendStop) {
        session = null;   // first: from this instant nobody can observe a half-closed link as connected
        lastScan = null;
        try {
            if (sendStop) {
                try { live.client().send(Command.STOP, CommandType.SET); log.info("sent STOP to hardware on disconnect."); }
                catch (RuntimeException e) { log.warn("Failed to send STOP on disconnect: {}", e.getMessage()); }
            }
            try { live.client().close(); } catch (RuntimeException e) { log.warn("Closing the gateway client failed: {}", ...); }
            closeQuietly(live.transports().values());   // idempotent safety net: never rely on the client
        } finally {
            log.info("Tile board gateway disconnected");
            eventPublisher.publishEvent(new GatewayDisconnectedEvent());   // even when cleanup threw
        }
    }

    // ------------------------------------------------------------------ auto-reconnect (see the next section)

    @Override public synchronized void armAutoReconnect() { autoReconnectArmed = true; }
    @Override public boolean isAutoReconnectArmed() { return autoReconnectArmed; }

    @Override public synchronized boolean reconnectIfNeeded() {
        if (!autoReconnectArmed) return false;                     // never armed, or an admin disconnected
        if (session != null && !tearDownIfLinkLost()) return false; // link is alive - nothing to do

        PortAssignment assignment = currentAssignment();
        String outPort = assignment.outPort().orElse(null);
        if (outPort == null || deviceConfigurationService.current().isEmpty()) {
            log.debug("Auto-reconnect skipped: device or output port is no longer configured");
            return false;
        }

        // Cheap pre-check: while the adapter is still unplugged do not even try to open it.
        PortScan scan = scanNow();
        lastScan = scan;
        if (scan.ok()) {
            Set<String> wanted = new LinkedHashSet<>();
            wanted.add(outPort);
            assignment.inPort().ifPresent(wanted::add);
            wanted.removeAll(scan.names());
            if (!wanted.isEmpty()) { log.debug("Auto-reconnect waiting: port(s) {} not present yet", wanted); return false; }
        }

        try {
            doConnect(false);            // never re-arms by itself
            return session != null;
        } catch (RuntimeException e) {
            // Expected while the hardware is away or busy; the next tick simply tries again.
            log.warn("Auto-reconnect attempt failed: {}", e.getMessage());
            return false;
        }
    }

    @Override public synchronized boolean releaseIfLinkLost() { return tearDownIfLinkLost(); }

    /** Caller must hold this monitor. Forces a fresh scan; acts only on a SUCCESSFUL scan that misses a port. */
    private boolean tearDownIfLinkLost() {
        LinkSession live = session;
        if (live == null) return false;
        PortScan fresh = scanNow();
        lastScan = fresh;
        if (!fresh.ok()) return false;                    // cannot verify -> never destroy a live session
        Set<String> missing = missingPorts(live, fresh);
        if (missing.isEmpty()) return false;
        log.error("Serial link lost: port(s) {} no longer present on the host - releasing the gateway", missing);
        tearDown(live, false);                            // no STOP: the port is gone anyway
        return true;
    }

    private void enableIdHandshake(TileGatewayClient gatewayClient, DeviceConfiguration device) {
        int minimumSequence = properties.handshakeMinSequence() > 0
                ? properties.handshakeMinSequence()
                : Math.max(2, Math.min(device.width(), device.height()));
        log.info("Enabling id handshake for a {}x{} board (minimumSequence={})", ...);
        gatewayClient.enableIdHandshake(
                () -> DeviceAddress.forBoard(device.width(), device.height()),
                new SequentialIdSequenceValidator(minimumSequence));
    }

    private SerialTransport openPort(String portName, SerialPortConfig config) {
        try { return portRegistry.open(portName, config); }
        catch (RuntimeException e) {
            throw new SerialPortOperationException("serial.port_operation_failed", new Object[]{portName},
                    "Failed to open serial port '" + portName + "'", e);   // 502
        }
    }

    /** Test seam (package-private): installs a session without opening real hardware. */
    synchronized void attachSessionForTest(TileGatewayClient client, String inPort, String outPort) { ... }

    /** Immutable description of the one live gateway session. */
    private record LinkSession(TileGatewayClient client, Map<PortRole, SerialTransport> transports,
                               String inPort, String outPort, Instant connectedSince) {
        Set<String> portNames() { ... }   // distinct system names; IN == OUT for a shared full-duplex port
    }

    /** Result of one host port enumeration. error == null means it succeeded. */
    private record PortScan(Set<String> names, long takenAtNanos, String error) {
        boolean ok() { return error == null; }
    }
}
```

**Behavior notes (exactly as coded):**

1. **Two sides, two locking disciplines.** Mutations (`assign`, `connect`, `disconnect`, `releaseIfLinkLost`, `reconnectIfNeeded`, `armAutoReconnect`) are `synchronized` on the service monitor — they are operator/scheduler-driven and rare. The read side (`linkStatus()`, `connectionState()`, `currentAssignment()`) is deliberately **not** synchronized: it reads a `volatile` immutable `LinkSession`, so a health probe or `GET /api/v1/ports/status` never queues behind a slow `connect()`.
2. **Where the assignment lives:** there is no `assignedPorts` map in the service any more — `assign`/`currentAssignment` read and write `SettingKeys.SERIAL_PORT_ASSIGNMENT` through `SettingsService`, so IN/OUT assignment survives a restart (`store=jpa`, the default) exactly like the device geometry. Only the *open* transports stay in memory (inside the immutable `LinkSession`'s `Map<PortRole, SerialTransport>`), because a live OS handle cannot be reattached after a JVM restart. Re-assigning a port while connected does **not** replace the live session; it takes effect on the next `connect()`, which is why `SerialLinkStatus` reports the *session's* ports while `ConnectionStatusResponse` reports the *persisted* names.
3. **Two topologies transparently:**
   - IN and OUT same name → one shared `SerialTransport` opened once, `builder.transport(shared)` (full-duplex).
   - Different names → two transports. Only OUT → loud warning that the client is OUTPUT ONLY (no touches/handshake will ever be received).
4. **Rollback on failed connect — twice.** `openedThisAttempt` + `success` flag + `finally { if (!success) closeQuietly(...) }` covers open/build/handshake failures, so a failed `connect` never leaks an OS port handle that would break the next attempt. A *second* rollback covers a client that was built but failed to come up (`publishEvent`/`start()` threw): `tearDown(live, false)` clears the session and then `SerialPortOperationException` (HTTP 502) is thrown, instead of leaving a "connected" session with a dead client behind. `openPort` wraps any failure in `SerialPortOperationException` too.
5. **Handshake before start:** `enableIdHandshake(newClient, device)` runs before `newClient.start()`, so the board's initial `ID`/`CLEAR` frames are never dropped. Auto `minimumSequence = max(2, min(width, height))`; a positive `tileboard.serial.handshake-min-sequence` overrides it.
6. **Device must be configured first — enforced, not accidental:** `connect()` now fails with `DeviceNotConfiguredException` (409 `device.not_configured`) *before* opening any port. (The earlier implementation only logged at the top and later blew up with `NoSuchElementException` from `Optional.get()` after the client had been stored.) `reconnectIfNeeded()` skips quietly while the device or the OUT port is unconfigured, and logs it at DEBUG.
7. **Hardware protocol on (dis)connect:** `INTRODUCTION`/`SET` is sent after connect; `STOP`/`SET` is sent best-effort before an *explicit* disconnect only — a link that was lost is torn down without `STOP`, because the port is gone anyway. Note the exact log shapes: `Enabling id handshake for a {}x{} board (minimumSequence={})`, `Tile board gateway connected (input=…, output=…)`, `sent INTRODUCTION to hardware ({}X{} board)`, `sent STOP to hardware on disconnect.`, `Tile board gateway disconnected`.
8. **Event publishing:** `GatewayConnectedEvent(client, width, height)` wakes `GameEngineManager`; `GatewayDisconnectedEvent` unbinds it and is published from a `finally`, so it reaches the engine even when cleanup itself throws. Both event types live in the engine module to avoid a circular dependency.
9. **State is verified, not remembered:** `connectionState()` is literally `linkStatus().state()`. A session whose port vanished reports `DISCONNECTED`/`LINK_LOST` even though nothing called `disconnect()`; a *failed* enumeration reports `UNVERIFIED` and stays `CONNECTED`, because "I could not look" is not proof that "the link is gone".
10. **`lastScan` hygiene:** the cache is cleared on connect and on teardown, and every decision that could *destroy* a session (`tearDownIfLinkLost`, the reconnect pre-check) uses a forced `scanNow()` rather than the cache — a scan taken before the ports were opened must never judge the new session.
11. **Auto-reconnect intent:** `autoReconnectArmed` is written under the same monitor as connect/disconnect/reconnect and read lock-free. An explicit `connect()` arms it *before* opening the ports (so a busy port is retried by the scheduler), `disconnect()` disarms it first and unconditionally, and an unexpected link loss never disarms it. `doConnect(false)` from the scheduler can therefore never re-arm something an admin switched off.

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

## Serial Link Lifecycle

A remembered `client != null` says nothing about the cable: a USB-serial adapter that is unplugged leaves a perfectly
"connected" object behind while the board is unreachable. Three components cooperate so that the in-memory session
stays honest, is released when it dies, and comes back when the hardware returns.

```
┌────────────────────────────────────────────────────────────────────────────┐
│ REPORTS - DefaultSerialConnectionManager.linkStatus()                      │
│   volatile LinkSession + host port scan (cached for scan-cache-ttl)        │
│   -> NOT_CONNECTED | HEALTHY | LINK_LOST | UNVERIFIED                      │
│   read by GET /api/v1/ports/status and /actuator/health (serialLink)       │
├────────────────────────────────────────────────────────────────────────────┤
│ RELEASES - SerialLinkMonitor.checkOnce()   every serial-monitor.interval   │
│   consecutiveLost >= loss-confirmations -> releaseIfLinkLost()             │
│   -> gateway closed + GatewayDisconnectedEvent -> the engine unbinds       │
├────────────────────────────────────────────────────────────────────────────┤
│ RESTORES - SerialAutoReconnector    at ApplicationReadyEvent + every       │
│                                     serial-auto-reconnect.interval         │
│   onApplicationReady(): arm + first attempt when device/IN/OUT are stored  │
│   runOnce() -> reconnectIfNeeded() while armed (never re-arms itself)      │
└────────────────────────────────────────────────────────────────────────────┘
```

### SerialLinkMonitor - the watchdog

```java
@Component
@ConditionalOnProperty(prefix = "tileboard.serial-monitor", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SerialLinkMonitor {

    private final SerialConnectionManager connectionManager;
    private final int lossConfirmations;                        // tileboard.serial-monitor.loss-confirmations
    private final AtomicInteger consecutiveLost = new AtomicInteger();

    /** One watchdog cycle. Never throws - a failing check must not cancel the schedule. */
    public void checkOnce() {
        try {
            SerialLinkStatus link = connectionManager.linkStatus();
            if (link.condition() != LinkCondition.LINK_LOST) {
                consecutiveLost.set(0);                         // ANY other condition resets the counter
                return;
            }
            int seen = consecutiveLost.incrementAndGet();
            if (seen < lossConfirmations) {
                log.warn("Serial port(s) {} missing ({}/{} checks) - waiting for confirmation",
                        link.missingPorts(), seen, lossConfirmations);
                return;
            }
            consecutiveLost.set(0);
            if (connectionManager.releaseIfLinkLost()) {
                log.error("Serial link to the tile board was lost (port(s) {}); gateway released. "
                        + "Re-plug the adapter and call POST /api/v1/ports/connect.", link.missingPorts());
            }
        } catch (RuntimeException e) {
            log.error("Serial link check failed", e);            // the schedule survives
        }
    }
}
```

- Scheduled by `SerialMonitorConfig` as a **fixed-delay** task (`tileboard.serial-monitor.interval`, 5 s by default), so a slow host enumeration can never overlap with the next check.
- The `AtomicInteger` counter is what makes one transient enumeration failure harmless: only `LINK_LOST` increments it, everything else (`HEALTHY`, `UNVERIFIED`, `NOT_CONNECTED`) resets it, and `releaseIfLinkLost()` re-verifies with a fresh scan before touching anything.
- Releasing the session publishes `GatewayDisconnectedEvent`, so `GameEngineManager` unbinds the engine and the game endpoints start answering `409 engine.not_ready` instead of writing into a port that no longer exists.
- `tileboard.serial-monitor.enabled=false` removes both the bean and its scheduler.

### SerialAutoReconnector - startup recovery and scheduled retry

```java
@Component
@ConditionalOnProperty(prefix = "tileboard.serial-auto-reconnect", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SerialAutoReconnector {

    /** Runs once the application is fully started. Must never fail the startup. */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        try {
            boolean deviceConfigured = deviceConfigurationService.isConfigured();
            PortAssignment assignment = connectionManager.currentAssignment();
            boolean inAssigned = assignment.inPort().isPresent();
            boolean outAssigned = assignment.outPort().isPresent();

            if (!(deviceConfigured && inAssigned && outAssigned)) {
                log.info("Serial auto-reconnect NOT armed at startup (deviceConfigured={}, inPortAssigned={}, "
                        + "outPortAssigned={}). It arms itself on the first explicit POST /api/v1/ports/connect.",
                        deviceConfigured, inAssigned, outAssigned);
                return;
            }
            connectionManager.armAutoReconnect();
            log.info("Serial auto-reconnect armed (in={}, out={}); attempting initial connection", ...);
            runOnce();                                  // connect immediately, do not wait one interval
        } catch (RuntimeException e) {
            log.error("Serial auto-reconnect startup check failed; it will not be armed until an admin connects", e);
        }
    }

    /** One scheduler cycle. Never throws - a failing attempt must not cancel the schedule. */
    public void runOnce() {
        try {
            if (connectionManager.reconnectIfNeeded()) log.info("Serial link re-established by auto-reconnect");
        } catch (RuntimeException e) {
            log.error("Serial auto-reconnect cycle failed", e);
        }
    }
}
```

- **Startup:** because the settings store is persistent by default, a fully configured deployment (geometry + IN + OUT) reconnects by itself after a restart — no operator request needed. Anything missing means "not armed", and the first explicit `POST /api/v1/ports/connect` arms it (that endpoint itself only *requires* OUT).
- **Scheduled:** `SerialAutoReconnectConfig` registers `runOnce()` as a fixed-delay task whose *first* run is one full interval after startup (the startup attempt is `onApplicationReady()`, not the scheduler).
- **While armed,** `reconnectIfNeeded()` waits until the required host ports are visible again and swallows operational failures (port busy, adapter still enumerating), so the schedule is never cancelled and the next tick simply tries again.
- **Disarmed by intent, not by accident:** only an explicit `disconnect()` clears the flag — a cable pull does not. That is what makes "unplug, re-plug" self-healing while "operator switched the board off on purpose" stays off.
- `tileboard.serial-auto-reconnect.enabled=false` removes both the bean and its scheduler; the manual `POST /api/v1/ports/connect` flow is unchanged either way.

### Unplug / re-plug timeline (defaults: 5 s watchdog, 2 confirmations, 1 min retry)

| Time | Event | Observable effect |
|---|---|---|
| t+0 s | adapter unplugged | nothing yet - the session object still exists |
| t+≤5 s | watchdog check #1 → `LINK_LOST` | `serialLink` = `DOWN` with `missingPorts`, warn log `(1/2 checks)` |
| t+≤10 s | watchdog check #2 → `LINK_LOST` | `releaseIfLinkLost()` → gateway closed, `GatewayDisconnectedEvent`, engine unbound; game endpoints → `409 engine.not_ready` |
| … | adapter re-plugged | port reappears in the host enumeration |
| t+≤60 s | `reconnectIfNeeded()` (still armed) | new session, `GatewayConnectedEvent`, engine rebound, `INTRODUCTION` sent, `serialLink` = `UP` |

An explicit `POST /api/v1/ports/disconnect` at any point clears the armed intent, so the last row never happens until an
operator calls `connect` again.

---

## Health

### SerialLinkHealthIndicator - the `serialLink` Actuator component

```java
@Component
public class SerialLinkHealthIndicator extends AbstractHealthIndicator {

    public SerialLinkHealthIndicator(SerialConnectionManager connectionManager, SerialMonitorProperties monitorProperties) {
        super("Serial link health check failed");   // text used when doHealthCheck itself throws -> DOWN
        ...
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        SerialLinkStatus link = connectionManager.linkStatus();   // verified on EVERY call

        builder.status(statusFor(link))
                .withDetail("state", link.state().name())
                .withDetail("condition", link.condition().name())
                .withDetail("checkedAt", link.checkedAt().toString());

        if (link.outPort() != null)             builder.withDetail("outPort", link.outPort());
        if (link.inPort() != null)              builder.withDetail("inPort", link.inPort());
        if (link.connectedSince() != null)      builder.withDetail("connectedSince", link.connectedSince().toString());
        if (!link.missingPorts().isEmpty())     builder.withDetail("missingPorts", List.copyOf(link.missingPorts()));
        if (link.detail() != null)              builder.withDetail("reason", link.detail());
    }

    private Status statusFor(SerialLinkStatus link) {
        return switch (link.condition()) {
            case HEALTHY        -> Status.UP;
            case LINK_LOST      -> Status.DOWN;
            case UNVERIFIED     -> Status.UNKNOWN;
            case NOT_CONNECTED  -> monitorProperties.notConnectedIsDown() ? Status.DOWN : Status.UNKNOWN;
        };
    }
}
```

| `LinkCondition` | Actuator status | HTTP status of `/actuator/health` |
|---|---|---|
| `HEALTHY` | `UP` | 200 |
| `LINK_LOST` | `DOWN` | 503 |
| `UNVERIFIED` | `UNKNOWN` | 200 |
| `NOT_CONNECTED` | `DOWN`, or `UNKNOWN` when `tileboard.serial-monitor.not-connected-is-down=false` | 503 / 200 |

Example (`GET /actuator/health`, board connected on a shared `COM3`):

```json
{
  "status": "UP",
  "components": {
    "serialLink": {
      "status": "UP",
      "details": {
        "state": "CONNECTED",
        "condition": "HEALTHY",
        "checkedAt": "2026-09-24T12:34:56.789Z",
        "outPort": "COM3",
        "inPort": "COM3",
        "connectedSince": "2026-09-24T12:30:01.123Z",
        "reason": "All session ports are present on the host."
      }
    },
    "db": { "status": "UP" },
    "diskSpace": { "status": "UP" },
    "ping": { "status": "UP" }
  }
}
```

- `management.endpoint.health.show-details: always` is what makes the `details` visible; this app has **no authentication**, so on an untrusted network switch it to `when-authorized` and add Spring Security.
- Extending `AbstractHealthIndicator` means an exception thrown by `linkStatus()` becomes `DOWN` with the `"Serial link health check failed"` message instead of a 500 from the endpoint.
- The indicator is **not** part of the liveness/readiness groups: `/actuator/health/liveness` and `/actuator/health/readiness` (enabled by `management.endpoint.health.probes.enabled: true`) stay `UP` while the board is unplugged, because a missing board is an operator/hardware event, not an application crash — a container must not be restarted for it.
- Polling the endpoint is cheap: `linkStatus()` reuses the host port enumeration for `tileboard.serial-monitor.scan-cache-ttl` (1 s by default) and never waits behind `connect()`/`disconnect()`.

### Other Actuator endpoints

```text
GET /actuator/health            # includes the serialLink component (+ the built-in db, diskSpace, ping)
GET /actuator/info              # exposed, currently empty (no InfoContributor is configured)
GET /actuator/health/liveness   # probe group, excludes serialLink
GET /actuator/health/readiness  # probe group, excludes serialLink
```

Only `health` and `info` are exposed (`management.endpoints.web.exposure.include: health,info`); everything else,
including `metrics`, stays off even though Micrometer is on the classpath through the actuator starter. The engine's
optional `GameEngineMetricsBinder` only binds its gauges when a `MeterRegistry` bean exists.

---

## Persistence, Settings and Cache

The application persists its configuration — and only its configuration — in a single generic
key/value table. Everything configurable (board geometry today, serial port assignment today,
anything added tomorrow) is stored as opaque JSON with a stable string key, through one interface,
so a new setting never means a new table, entity, repository or schema change.

```
DeviceController ──────→ SettingsBackedDeviceConfigurationService ─┐
                                                                   │
SerialPortController ──→ DefaultSerialConnectionManager ───────────┤
                                                                   ▼
                                                    SettingsService (SettingKey<T>)
                                                     │                        │
                                      InMemorySettingsService       JpaSettingsService
                                      store=memory: CHM             store=jpa (default)
                                      (lost on restart)                       │
                                                                Caffeine cache "settings"
                                                                              │
                                                                     SettingRepository
                                                                              │
                                              app_settings (SQLite: jdbc:sqlite:app.db)
```

### Package Tour

| Class | Package | Role |
|-----|------|-----|
| `SettingsService` | `settings` | Generic typed store: `get` / `getOrDefault` / `set` / `clear` / `isSet`, keyed by `SettingKey<T>` |
| `SettingKey<T>` | `settings` | `record (id, type, defaultValue)` + `of(...)` factories; **not** an enum — adding a setting is one constant |
| `SettingKeys` | `settings` | The append-only registry: `DEVICE_CONFIGURATION` (`device.configuration`), `SERIAL_PORT_ASSIGNMENT` (`serial.port-assignment`) |
| `InMemorySettingsService` | `settings` | `ConcurrentHashMap`-backed store, active for `tileboard.settings.store=memory` |
| `JpaSettingsService` | `settings` | Production store: JSON + cache + `app_settings`, active for `store=jpa` (or when the property is unset) |
| `SettingsPersistenceException` | `settings` | Wraps (de)serialization failures from either store |
| `SettingsSerializationConfig` | `settings.conf` | The settings-only `ObjectMapper` bean (`settingsObjectMapper`) |
| `ApplicationSetting` | `settings.persistence` | The JPA entity (`@Table(name = "app_settings")`, `@Version`) |
| `SettingRepository` | `settings.persistence` | `JpaRepository<ApplicationSetting, String>` (the key is the id) |
| `CacheConfig` / `CacheSettingsProperties` | `config` | `@EnableCaching` + Caffeine cache manager, `tileboard.cache.*` |

### Schema — created by Hibernate

There are no SQL migrations. `spring.jpa.hibernate.ddl-auto: update` makes Hibernate build the table from
`ApplicationSetting` at startup:

```sql
-- what Hibernate generates (approximately; exact types depend on the dialect)
CREATE TABLE app_settings (
    setting_key VARCHAR(200) NOT NULL PRIMARY KEY,
    value_json  TEXT         NOT NULL,
    updated_at  TIMESTAMP    NOT NULL,
    version     BIGINT       NOT NULL
);
```

- `update` only **creates** tables and **adds** missing columns; it never drops/renames columns or changes types. Those changes are manual.
- SQLite cannot add a `NOT NULL` column without a default to an existing table - give new mapped columns a default (`columnDefinition`) or make them nullable.
- `value_json` is `TEXT` (not JSON-typed) on purpose: the DB never parses it, which is exactly why new settings need no schema change.
- Hibernate is the **only** schema owner in this module: there is no Flyway dependency, no `src/main/resources/db/migration` directory and no hand-written SQL. If you point the app at a database that an older, Flyway-based build created, `app_settings` simply already exists and the leftover `flyway_schema_history` table is inert (the app never reads it; drop it whenever you like). Introduce a real versioned-migration strategy *before* making an incompatible entity change.

### The Entity

```java
@Entity
@Table(name = "app_settings")
public class ApplicationSetting {

    @Id
    @Column(name = "setting_key", nullable = false, updatable = false, length = 200)
    private String key;

    @Column(name = "value_json", nullable = false, columnDefinition = "TEXT")
    private String value;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected ApplicationSetting() { /* JPA */ }

    public ApplicationSetting(String key, String value, Instant updatedAt) { ... }
}
```

`@Version` is the interesting line: settings can be written by two admins (or two requests) at the same
time, so JPA optimistic locking is what makes a lost update visible instead of silent — and
`JpaSettingsService` turns that signal into a single retry (below). The `protected` no-arg constructor
is JPA's requirement; application code uses the 3-arg one.

### `JpaSettingsService` — The Production Store

```java
@Service
@ConditionalOnProperty(prefix = "tileboard.settings", name = "store", havingValue = "jpa", matchIfMissing = true)
public class JpaSettingsService implements SettingsService {

    public JpaSettingsService(SettingRepository repository,
                              ObjectMapper settingsObjectMapper,
                              CacheManager cacheManager) {
        this.repository = repository;
        this.objectMapper = settingsObjectMapper;
        this.cache = cacheManager.getCache(CacheConfig.SETTINGS_CACHE);
        if (this.cache == null) throw new IllegalStateException("Cache 'settings' is not configured");
    }

    @Override @Transactional(readOnly = true)
    public <T> Optional<T> get(SettingKey<T> key) {
        Cache.ValueWrapper cached = cache.get(key.id());
        if (cached != null) return (Optional<T>) cached.get();
        Optional<T> loaded = repository.findById(key.id())
                .map(entity -> deserialize(key, entity.getValue()));
        cache.put(key.id(), loaded);          // caches "absent" too
        return loaded;
    }

    @Override @Transactional
    public <T> void set(SettingKey<T> key, T value) {
        String json = serialize(key, value);
        try {
            persist(key.id(), json);
        } catch (OptimisticLockingFailureException e) {
            log.warn("Concurrent update detected for setting '{}', retrying once", key.id());
            persist(key.id(), json);          // last-write-wins is fine for admin settings
        } finally {
            cache.evict(key.id());            // next read sees exactly what we just wrote
        }
    }

    private void persist(String keyId, String json) {
        ApplicationSetting entity = repository.findById(keyId)
                .orElseGet(() -> new ApplicationSetting(keyId, json, Instant.now()));
        entity.setValue(json);
        entity.setUpdatedAt(Instant.now());
        repository.save(entity);
    }
}
```

**Behavior notes (exactly as coded):**

1. **Cache-first reads, cache evicted on write.** Settings are read far more often than written (every connect, every game start), so reads go through Caffeine while every write/clear evicts the key in a `finally` — a failed write can never leave a stale entry.
2. **Negative caching.** An absent key is cached as `Optional.empty()` for the TTL, so a never-written setting does not hit the DB on every call.
3. **`isSet` ignores the cache** (`repository.existsById`) and `getOrDefault` is just `get` + `SettingKey.defaultValue()`.
4. **Retry once on `OptimisticLockingFailureException`,** then evict — the row's `@Version` guards against lost updates without forcing callers to handle a conflict.
5. **The cache is mandatory.** A `CacheManager` that does not know the `settings` cache fails the bean at startup (`IllegalStateException`), not the first read.
6. **Serialization failures are explicit:** unknown type / corrupt JSON / incompatible old value → `SettingsPersistenceException("Failed to deserialize setting '...' of type ... - stored value may be corrupt or from an incompatible version")`.

### `InMemorySettingsService` — The Opt-In Disposable Store

```java
@Service
@ConditionalOnProperty(prefix = "tileboard.settings", name = "store", havingValue = "memory")
public class InMemorySettingsService implements SettingsService {
    private final Map<String, Object> values = new ConcurrentHashMap<>();

    public <T> Optional<T> get(SettingKey<T> key) { return Optional.ofNullable((T) values.get(key.id())); }
    public <T> void set(SettingKey<T> key, T value) { values.put(key.id(), value); }
    ...
}
```

`ConcurrentHashMap` is all the thread-safety this store needs, and it performs no database read or
write at all — which is why it is the store used for demos, hardware-less runs and the unit tests that
build services by hand. It is **not** the checked-in default any more: `application.yml` ships
`tileboard.settings.store: jpa`, and `JpaSettingsService` is the bean that also matches when the
property is absent (`matchIfMissing = true`). Select it explicitly when you want a throwaway run:

```bash
mvn spring-boot:run -pl tileboard-app -Dspring-boot.run.arguments="--tileboard.settings.store=memory"
java -jar tileboard-app/target/tileboard-app-1.0.0.jar --tileboard.settings.store=memory
```

Note the trade-off documented in the class's own Javadoc: values do **not** survive a restart, while the
JPA store's do. JPA/Hibernate still initializes the SQLite datasource in this mode (the datasource is
configured unconditionally), it just never receives a settings write. With any other value (e.g. a typo
such as `redis`) *neither* conditional bean matches and the context fails to start — a deliberate
fail-fast rather than a silent fallback to a store that loses data.

### The Dedicated Settings `ObjectMapper`

```java
@Configuration
public class SettingsSerializationConfig {
    @Bean
    public ObjectMapper settingsObjectMapper() {
        return new ObjectMapper()
                .registerModule(new Jdk8Module())     // Optional<T> fields (e.g. PortAssignment)
                .registerModule(new JavaTimeModule()) // Instant
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
}
```

Settings are a persistence concern, not an API concern, so they get their own mapper instead of the
web layer's — a future custom (de)serializer for an API DTO can never silently change how values are
stored. `FAIL_ON_UNKNOWN_PROPERTIES=false` is what makes an *additive* change to a stored settings
type (a new field) load cleanly from older JSON.

### Cache Configuration

```java
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String SETTINGS_CACHE = "settings";

    @Bean
    public CacheManager cacheManager(CacheSettingsProperties properties) {
        CaffeineCacheManager manager = new CaffeineCacheManager(SETTINGS_CACHE);
        manager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(properties.settingsTtlSeconds()))
                .maximumSize(properties.settingsMaxSize()));
        return manager;
    }
}
```

```yaml
tileboard:
  cache:
    settings-ttl-seconds: 300   # also the worst-case staleness across instances
    settings-max-size: 100
```

`CacheSettingsProperties` is a record whose compact constructor clamps non-positive values back to
`300` / `100`, so a `0` in YAML degrades to the default rather than to an eagerly-expiring (or
unbounded) cache.

### What Is Persisted (and What Deliberately Is Not)

| State | Where it lives | Survives restart? |
|-----|------|------|
| Device geometry | `SettingKeys.DEVICE_CONFIGURATION` → `app_settings` (`{"width":3,"height":3}`) | ✅ with `store=jpa` (the default), ❌ with `memory` |
| Serial port assignment | `SettingKeys.SERIAL_PORT_ASSIGNMENT` → `app_settings` (`{"inPort":"COM3","outPort":"COM3"}`) | ✅ with `store=jpa` (the default), ❌ with `memory` |
| Open OS handles, `TileGatewayClient`, `connectedSince` | the in-memory immutable `LinkSession` (`volatile session` field) | ❌ by design — a live serial link cannot be reattached after a restart |
| Auto-reconnect intent (`autoReconnectArmed`) | in-memory flag in the manager | ❌ by design — but `SerialAutoReconnector` re-arms it at startup as soon as device + IN + OUT are stored |
| Verified link verdicts, scan cache | in-memory (`lastScan`, `SerialLinkStatus` built per call) | ❌ by design — always re-derived from the host |
| Game sessions, board state, SSE subscribers | engine (`GameEngineManager`, `GameSessionImpl`) | ❌ by design |

### Verifying Persistence End-to-End

```bash
# the checked-in default is already persistent (tileboard.settings.store: jpa)
mvn spring-boot:run -pl tileboard-app

curl -X POST http://localhost:8080/api/v1/device -H "Content-Type: application/json" -d '{"width":3,"height":3}'
curl -X POST http://localhost:8080/api/v1/ports/OUT/assign -H "Content-Type: application/json" -d '{"portName":"COM3"}'
curl -X POST http://localhost:8080/api/v1/ports/IN/assign  -H "Content-Type: application/json" -d '{"portName":"COM3"}'

# Ctrl-C, start again from the SAME working directory, then:
curl http://localhost:8080/api/v1/device        # → 200 with {"width":3,"height":3,"tileCount":9}
curl http://localhost:8080/api/v1/ports/status  # → 200 data{state:"DISCONNECTED", inPort:"COM3", outPort:"COM3"}
```

With both ports and the geometry stored, the restart goes one step further on its own: `SerialAutoReconnector`
arms itself at `ApplicationReadyEvent` and attempts the connection immediately, so a board that is still plugged in
comes back without any request (`serialLink` flips to `UP`).

The database file is `app.db`, created on demand in the directory the JVM was started from
(`jdbc:sqlite:app.db` is a relative URL). All the usual locations are listed in the repository `.gitignore`
(`/app.db`, `/tileboard-app/app.db`, `/data/app.db`, `/tileboard-app/data/app.db`, plus the legacy H2 paths).
`TileboardApplicationTests` (`@SpringBootTest`) boots this whole stack, so a broken entity mapping or a datasource
problem fails `mvn test` immediately, without any external database.

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
# `state` is the LIVE-VERIFIED state (linkStatus().state()), so it reads DISCONNECTED after an unplug;
# `inPort`/`outPort` come from the PERSISTED assignment (currentAssignment()).

POST /api/v1/ports/connect
→ 200 (same ConnectionStatusResponse body as /status)
→ 409 ports.not_assigned when OUT was never assigned
→ 409 device.not_configured when no board geometry has been stored yet (checked BEFORE any port is opened)
→ 502 serial.port_operation_failed when a port cannot be opened or the gateway fails to start
# Idempotent, and it (re)arms auto-reconnect. A remembered session is re-verified first: if its port has
# vanished it is released and a fresh connection is established instead of returning a stale "connected".

POST /api/v1/ports/disconnect
→ 200 (same ConnectionStatusResponse body as /status; idempotent, also with no live session)
# Sends STOP best-effort, closes the gateway and every tracked transport, publishes GatewayDisconnectedEvent
# and DISARMS auto-reconnect, so the scheduler will not undo a deliberate disconnect.
```

There is **no** `GET /ports/assignment` endpoint and no `POST /ports/assign` with a `role` body field — assignment is `POST /ports/{role}/assign` with `{portName}` only. `disconnect` returns `200` with the status body (not `204`). None of these endpoints takes a port name, baud rate or board size: those come from the persisted assignment, `tileboard.serial.*` and `POST /api/v1/device`.

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
- It is driven by **three** sources of `GatewayDisconnectedEvent`, all from `DefaultSerialConnectionManager`: an explicit `POST /api/v1/ports/disconnect`, the watchdog's `releaseIfLinkLost()` after a confirmed link loss, and the rollback path of a connect whose gateway failed to start. Symmetrically, both an explicit `connect()` and a scheduler-driven `reconnectIfNeeded()` publish `GatewayConnectedEvent`, so a board that is re-plugged gets a fresh engine (and any running sessions were stopped when the old one was closed).

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

Persistence failures are **not** translated: `SettingsPersistenceException` (and a missing `settings` cache, which throws `IllegalStateException` during bean creation) has no dedicated handler, so it lands in the catch-all → 500 with `server.internal_error` as the localized message and the raw (English) text in `debugMessage`. A corrupt/incompatible stored value therefore looks like a server error, which is exactly what it is — fix the row or the type, then restart.

**i18n:** every error body is `{status: ERROR, message: <Persian>, data: null, extra: null, debugMessage: <raw English>}`. `message` is resolved from `messages_fa.properties` (fallback `messages.properties`, byte-identical content) via `Messages.resolve(errorCode, args, fallback)` — a missing key degrades to the raw English message instead of a 500. The catalogs also contain a `validation.*` block of bean-validation texts intended for `{key}` placeholders; the current DTOs still carry literal English messages (`@Min(value = 1, message = "width must be at least 1")`), so those field texts end up inside the *localized* `validation.failed (...)` message rather than being translated — switch an annotation to `message = "{validation.width.min}"` to use the catalog instead.

---

## Step-by-Step Tutorial

### Prerequisites

- Java 17+, Maven 3.8+
- Tileboard board connected via USB (or a Mock `SerialTransport` for hardware-less tests — every unit test in this module runs without hardware)
- No database server: the app creates its own SQLite file (`jdbc:sqlite:app.db`) through Hibernate on first start, in the directory the JVM is launched from
- A writable working directory (SQLite creates `app.db` there) — the only reason a startup would fail on the persistence side

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

App runs on `http://localhost:8080`. On startup Hibernate creates the `app_settings` table from the entity, so a file named `app.db` appears next to the working directory (`tileboard-app/app.db` when you `cd tileboard-app` first).

- Swagger UI: springdoc default (starter `2.6.0` is on the classpath)
- Actuator: `http://localhost:8080/actuator/health` (only `health,info` are exposed) — before any connection the `serialLink` component reports `DOWN`/`NOT_CONNECTED`, which is expected, not a failure
- Persistence: the base profile uses `tileboard.settings.store: jpa`, so device geometry and port assignment are kept across restarts out of the box; add `--tileboard.settings.store=memory` for a disposable run — see [Persistence, Settings and Cache](#persistence-settings-and-cache)
- Auto-reconnect: on the *first* run nothing is stored yet, so the startup log reads `Serial auto-reconnect NOT armed at startup (deviceConfigured=false, inPortAssigned=false, outPortAssigned=false)`. From the second run on (geometry + IN + OUT stored) the app tries to connect by itself before you send a single request

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
The write goes through `SettingsService` (`{"width":3,"height":3}` in `app_settings`), so with `store=jpa` this step is needed only once per database — and `GameBeansConfig` sizes the sample game from the stored geometry at the next boot.

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

This request also **arms auto-reconnect**, so from now on an unplugged adapter is released by the watchdog and
re-connected by the scheduler once it is back — no new assignment and no second `connect` call needed. Failures here
are precise rather than half-done:

| Situation | Result |
|---|---|
| OUT never assigned | `409` `ports.not_assigned` |
| No stored device geometry | `409` `device.not_configured` (nothing was opened) |
| Port missing / busy / open fails | `502` `serial.port_operation_failed`, and every transport opened by this attempt is closed again |
| Gateway built but `start()` fails | `502` `serial.port_operation_failed`, session rolled back (`GatewayDisconnectedEvent` published) |
| Already genuinely connected | `200` with the current status, nothing re-opened (idempotent) |
| Session remembered but its port is gone | the dead session is released first, then a fresh connection is established |

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

This is the *deliberate* shutdown: it sends `STOP` best-effort, closes the gateway and every open transport,
publishes `GatewayDisconnectedEvent` (the engine unbinds, running sessions are stopped) and **disarms
auto-reconnect**, so the scheduler will not bring the link back behind your back. It is idempotent — calling it with
no live session still disarms and returns `200`.

### Step 13: Watch the link, and let it recover by itself

```bash
# live-verified link health (state, condition, ports, connectedSince, missingPorts, reason)
curl http://localhost:8080/actuator/health | jq .components.serialLink

# coarse state used by UIs
curl http://localhost:8080/api/v1/ports/status
```

Pull the USB adapter while a session is live and watch the three phases (default timings: 5 s watchdog,
2 confirmations, 1 min retry):

```text
# within ~5 s   - reported, not yet acted on
{"status":"DOWN","details":{"state":"DISCONNECTED","condition":"LINK_LOST","missingPorts":["COM3"], ...}}
Serial port(s) [COM3] missing (1/2 checks) - waiting for confirmation

# within ~10 s  - released by the watchdog
Serial link lost: port(s) [COM3] no longer present on the host - releasing the gateway
Serial link to the tile board was lost (port(s) [COM3]); gateway released. ...
Game engine unbound (1 active session(s) stopped)
{"status":"DOWN","details":{"state":"DISCONNECTED","condition":"NOT_CONNECTED", ...}}

# plug it back in - within ~1 min, with no request at all
Serial link re-established by auto-reconnect
Tile board gateway connected (input=COM3, output=COM3)
Game engine bound to the newly connected tile gateway (3x3)
{"status":"UP","details":{"state":"CONNECTED","condition":"HEALTHY", ...}}
```

Game endpoints answer `409 engine.not_ready` while the engine is unbound, and `GET /api/v1/games` keeps listing the
registered games regardless (it is served from the registry, not the engine). Container probes
(`/actuator/health/liveness`, `/actuator/health/readiness`) stay `UP` through all of it — a missing board must never
restart the app.

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

### 1. DefaultSerialConnectionManager - synchronized writes + lock-free verified reads + double rollback

**Problem:** `connect()` may be called concurrently (two admins, or an admin plus the reconnect scheduler). Opening ports may partially fail (first opens, second throws). And a remembered `client != null` says nothing about whether the cable is still plugged in — while `/actuator/health` polls every few seconds and must never queue behind a slow port open.

**Solution:**

- `synchronized` on the *mutating* side only: `connect()`, `disconnect()`, `assign()`, `releaseIfLinkLost()`, `reconnectIfNeeded()`, `armAutoReconnect()` → one thread mutates the link at a time, and the operator and the scheduler can never interleave.
- **Lock-free read side:** `linkStatus()` / `connectionState()` / `currentAssignment()` are *not* synchronized; they read a `volatile LinkSession` (an immutable record holding client + transports + ports + `connectedSince`). A health probe therefore never waits for `connect()`, and never sees a half-built session.
- **Two rollback paths:** `openedThisAttempt` (`EnumMap<PortRole, SerialTransport>`) + `success` flag + `finally { closeQuietly(...) }` covers failures while opening/building/enabling the handshake; a second path (`tearDown(live, false)` + `SerialPortOperationException`) covers a client that was stored but failed to `publishEvent`/`start()`. Either way no OS handle leaks into the next attempt.
- **`session = null` first** inside `tearDown`, before the slow `close()` calls, and `GatewayDisconnectedEvent` published from a `finally` → no reader can observe a half-closed link as connected, and the engine always unbinds.
- Shared-transport detection: IN == OUT name → opened once, `builder.transport(shared)`; different names → two transports; OUT only → loud OUTPUT-ONLY warning.
- `INTRODUCTION` after connect / `STOP` before an *explicit* disconnect (best-effort, warn on failure) — a link that was *lost* is torn down without `STOP`.
- The *assignment* is not an in-service map any more: `assign`/`currentAssignment` go through `SettingsService` (`serial.port-assignment`), so the operator's choice survives a restart while the open handles do not.

### 2. Verified link status - volatile snapshot + scan cache + `tryLock`

**Problem:** the only way to know whether the board is really reachable is to ask the host OS for its port list — an operation that can take hundreds of milliseconds (Windows with Bluetooth serial ports is notorious) and that health probes request constantly.

**Solution:**

- The verdict is derived per call from the live session + a fresh-enough enumeration: `PortScan` is a record of `(names, takenAtNanos, error)`, cached in a `volatile lastScan` and reused while younger than `scan-cache-ttl` (1 s).
- `scanLock.tryLock()`: when another thread is already enumerating, the caller is served the *previous* scan instead of piling up behind it; it only blocks when there is no previous result at all. Double-checked inside the lock, so a queued thread does not re-scan.
- `scanNow()` never throws: a `RuntimeException` from the registry becomes `PortScan.error`, which maps to `UNVERIFIED` — "I could not look" is deliberately **not** "the link is lost".
- Every decision that can *destroy* a session bypasses the cache (`tearDownIfLinkLost` and the reconnect pre-check both call `scanNow()`), and `lastScan` is cleared on connect/teardown so a scan taken before the ports were opened can never judge the new session.
- Result: `linkStatus()` is cheap, non-blocking, never fabricates a loss, and reports `missingPorts` / `connectedSince` / `checkedAt` / an English `detail` for diagnostics.

### 3. Watchdog + auto-reconnect - AtomicInteger confirmations and one shared "armed" intent

**Problem:** a lost link must be released (so the engine stops writing into a dead port) and later restored (so an operator does not have to babysit it) — but one flaky enumeration must not tear down a working session, and an admin's deliberate `disconnect` must not be undone one minute later by the scheduler.

**Solution:**

- `SerialLinkMonitor` keeps an `AtomicInteger consecutiveLost`: only `LINK_LOST` increments it, every other condition resets it to 0, and `releaseIfLinkLost()` is called only at `loss-confirmations` (2 by default). `checkOnce()` catches `RuntimeException` so a failing check can never cancel a `SchedulingConfigurer` fixed-delay task.
- The **intent flag lives in the manager**, not in the scheduler: `autoReconnectArmed` is `volatile` (lock-free reads) and written only under the same monitor as `connect`/`disconnect`/`reconnectIfNeeded`. "Admin disconnected" and "scheduler reconnects" are therefore serialized by construction and cannot race into an unwanted reconnect.
- Arming rules: `connect()` (explicit) arms *before* opening the ports, so a busy/still-enumerating adapter is retried by the scheduler; `disconnect()` disarms *first and unconditionally*, even with no live session; `onApplicationReady()` arms only when device + IN + OUT are all configured; `doConnect(false)` from the scheduler can never re-arm.
- `reconnectIfNeeded()` pre-checks the host scan and returns `false` while the ports are missing, so an unplugged board costs one cheap enumeration per minute instead of a failed port open.
- Both schedulers use **fixed delay** (never fixed rate) → a slow cycle cannot overlap the next one, and neither task needs to be cancelled/re-created at runtime.

### 4. SettingsBackedDeviceConfigurationService + JpaSettingsService - @Transactional + @Version + Caffeine

```java
@Override @Transactional(readOnly = true)
public <T> Optional<T> get(SettingKey<T> key) {
    Cache.ValueWrapper cached = cache.get(key.id());
    if (cached != null) return (Optional<T>) cached.get();          // hit, incl. cached "absent"
    Optional<T> loaded = repository.findById(key.id())
            .map(entity -> deserialize(key, entity.getValue()));
    cache.put(key.id(), loaded);                                    // negative caching
    return loaded;
}

@Override @Transactional
public <T> void set(SettingKey<T> key, T value) {
    String json = serialize(key, value);
    try { persist(key.id(), json); }
    catch (OptimisticLockingFailureException e) { persist(key.id(), json); }  // retry once
    finally { cache.evict(key.id()); }
}
```

- `AtomicReference` (the old in-memory implementation) is gone: the device geometry is now just
  `SettingKeys.DEVICE_CONFIGURATION`, and `SettingsBackedDeviceConfigurationService` is a two-method
  delegate with **no** state of its own.
- **Compile-time typing, runtime JSON:** `SettingKey<T>` carries the `Class<T>`, so `get`/`set` stay
  type-safe while the row stays opaque text.
- **`ConcurrentHashMap` vs. DB + cache:** the memory store is lock-free; the JPA store leans on the
  cache for read throughput, on `@Transactional` for atomic writes and on `@Version` for
  concurrent-writer detection (with a one-shot retry because last-write-wins is acceptable here).
- **Read-your-own-writes (single instance):** every `set`/`clear` evicts the key, so the next read
  hits the DB; other instances converge within `settings-ttl-seconds`.
- **`Optional` models "not yet configured"** (`defaultValue == null` for `DEVICE_CONFIGURATION`),
  which is what `DeviceController` turns into a 409.

### 5. GameEngineManager - volatile + synchronized + null-before-close

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

### 6. BoardChannel - ReentrantLock + gatewayWriteLock + coalescing

Detailed in the game engine README. Summary: `stateLock` guards the buffer, `gatewayWriteLock` serializes wire writes, `sendLatest()` re-reads the snapshot (coalescing), and `BoardFrameBroadcaster` dispatch happens outside the write lock.

### 7. GameState - synchronized HashMap

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

### 8. AnimationSystem - generation + CompletableFuture + SingleThreadExecutor

Detailed in the game engine README: `AtomicLong generation`, `runLock`, single daemon thread, per-animation `CompletableFuture` (normal/cancelled/exceptional).

### 9. ScoreSystem - ConcurrentHashMap + AtomicInteger

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

### 10. GameTimer - volatile + AtomicReference + AtomicBoolean

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

### 11. CORS Filter - FilterRegistrationBean

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

Actual test classes (JUnit 5 + Mockito via `spring-boot-starter-test`; none of them needs hardware):

| Test class | What it proves |
|---|---|
| `TileboardApplicationTests` | `contextLoads` — a full `@SpringBootTest` context (JPA + SQLite + Caffeine + the engine auto-configuration + both schedulers), so a broken entity mapping or datasource fails `mvn test` immediately |
| `TileboardPropertiesTest` | record defaults (115200/8/1/50/50/0) for `0`/negative input, and that explicit values are preserved |
| `ControllerUnitTest` | pure unit tests (Mockito, no MockMvc) for `DeviceController`, `SerialPortController`, `GameController`: device read/update, port list/assign/status/connect/disconnect delegation, game list/start/sessions/get/stop, and the disconnected-engine cases (empty session list, `NoActiveGameException`). The `StreamController`/broadcaster test is commented out in the source, matching the fact that no controller exposes the raw broadcaster |
| `SerialLinkHealthIndicatorTest` | `HEALTHY` → `UP` (with `outPort` detail), `LINK_LOST` → `DOWN` + `missingPorts`, `UNVERIFIED` → `UNKNOWN`, `NOT_CONNECTED` → `DOWN` or `UNKNOWN` per `not-connected-is-down`, and an exploding manager → `DOWN` instead of an exception |
| `DefaultSerialConnectionManagerTest` | distinct-port listing with one enumeration, `DISCONNECTED` before connect, idempotent disconnect with no events, then the verified-link matrix (`HEALTHY` while the port is present, `LINK_LOST` after it disappears, `UNVERIFIED` on a failed scan, `releaseIfLinkLost` closing the client + publishing `GatewayDisconnectedEvent` only when a port is really gone), and the auto-reconnect rules (does nothing until armed, waits while unplugged, survives a failed open, admin disconnect disarms — also on an already-lost link, unexpected loss keeps it armed, healthy session left alone). Uses `attachSessionForTest(...)` and `scan-cache-ttl = 0` |
| `SerialLinkMonitorTest` | release only after the configured number of consecutive lost checks, a healthy check resets the counter, and exceptions never propagate (the schedule survives) |
| `SerialAutoReconnectorTest` | startup arms + connects when device and both ports are configured, does nothing when the device or either port is missing, never fails the application on an exploding dependency, and `runOnce()` delegates + swallows failures |
| `InMemoryDeviceGeneralConfigurationServiceTest` | `DeviceConfiguration` geometry limits (`0x8` and `16x16` rejected, `15x17` = 255 accepted). The settings-backed configure/current test is commented out in the source |

```bash
mvn test                      # whole platform
mvn test -pl tileboard-app    # this module only
```

### Execution

```bash
mvn spring-boot:run -pl tileboard-app
# from the module directory:
cd tileboard-app && mvn spring-boot:run
# or packaged:
mvn clean package -DskipTests
java -jar tileboard-app/target/tileboard-app-1.0.0.jar

# with prod profile (INFO logging instead of DEBUG)
java -jar tileboard-app/target/tileboard-app-1.0.0.jar --spring.profiles.active=prod

# with custom port
java -jar tileboard-app/target/tileboard-app-1.0.0.jar --server.port=9090

# disposable run: no settings are written to SQLite
java -jar tileboard-app/target/tileboard-app-1.0.0.jar --tileboard.settings.store=memory

# fixed database location (recommended for services/containers)
SPRING_DATASOURCE_URL='jdbc:sqlite:/var/lib/tileboard/app.db' \
java -jar tileboard-app/target/tileboard-app-1.0.0.jar

# quieter/slower link supervision, no automatic reconnect
java -jar tileboard-app/target/tileboard-app-1.0.0.jar \
  --tileboard.serial-monitor.interval=15s \
  --tileboard.serial-monitor.loss-confirmations=3 \
  --tileboard.serial-auto-reconnect.enabled=false
```

Every `tileboard.*` knob can be overridden the same way (command line, `SPRING_*`/`TILEBOARD_*` environment
variables, or an external `application.yml`) — nothing has to be recompiled.

### Docker (Optional)

```dockerfile
FROM openjdk:17-jdk-slim
COPY target/tileboard-app-1.0.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app.jar"]
```

```bash
docker build -t tileboard-app .
docker run -p 8080:8080 --device=/dev/ttyUSB0 -v "$PWD/data:/data" \
  -e SPRING_DATASOURCE_URL='jdbc:sqlite:/data/app.db' \
  -e SPRING_PROFILES_ACTIVE=prod \
  tileboard-app
```

The container's working directory is `/`, so the relative `jdbc:sqlite:app.db` from the checked-in
configuration would resolve to `/app.db` and be lost with the container. Pin an absolute path
(`SPRING_DATASOURCE_URL=jdbc:sqlite:/data/app.db`, or `--spring.datasource.url=...`) and mount a volume at
that directory: that is what keeps `app.db` — and with it the persisted device geometry and port assignment —
across container restarts. Keep `--device=/dev/ttyUSB0` (or `--privileged` / a device-cgroup rule) so the serial
port is visible inside the container; the link watchdog and auto-reconnect work exactly as outside, since they only
re-read the host's port list. Serial devices are also the reason to leave `serialLink` out of the liveness/readiness
probes: an unplugged board must not make the orchestrator restart a healthy app.

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
| GET | /api/v1/ports | - | `ApiResponse{message: "N ports are available", data: [SerialPortResponse]}` (one host enumeration for the whole list) |
| POST | /api/v1/ports/{role}/assign | {portName} (`role` = IN/OUT path var) | `ApiResponse` empty success — persisted immediately, opens nothing, takes effect on the next connect |
| GET | /api/v1/ports/status | - | `ApiResponse{data: ConnectionStatusResponse{state, inPort, outPort}}` — `state` live-verified, port names from the persisted assignment |
| POST | /api/v1/ports/connect | - | same as /status; `409 ports.not_assigned` without OUT, `409 device.not_configured` without a stored geometry, `502 serial.port_operation_failed` when a port cannot be opened/started; idempotent and it arms auto-reconnect |
| POST | /api/v1/ports/disconnect | - | same as /status (200, idempotent); sends `STOP` best-effort and disarms auto-reconnect |

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

| Method | Path | Notes |
|--------|------|------|
| GET | /actuator/health | 200 when every component is `UP`/`UNKNOWN`, 503 when one is `DOWN`; includes `serialLink` (+ `db`, `diskSpace`, `ping`) |
| GET | /actuator/info | exposed but empty — no `InfoContributor` is configured |
| GET | /actuator/health/liveness , /actuator/health/readiness | probe groups; they exclude `serialLink` on purpose |

Only these two endpoint ids are exposed (`management.endpoints.web.exposure.include: health,info`). See
[Health](#health) for the indicator itself.

#### `serialLink` health component (verified, not remembered)

`/actuator/health` contains a `serialLink` component built by `com.tileboard.app.health.SerialLinkHealthIndicator`.
It is computed on every call from `SerialConnectionManager.linkStatus()`, which checks that every port of the
live session is still enumerated by the host OS (rate-limited by `tileboard.serial-monitor.scan-cache-ttl`).
Unplugging the adapter therefore turns it `DOWN` without anyone calling `/disconnect`.

| Condition | Health status | Meaning |
|-----------|---------------|---------|
| `HEALTHY` | `UP` | session exists and all its ports are present |
| `LINK_LOST` | `DOWN` | session exists in memory but a port vanished (`missingPorts` lists it) |
| `UNVERIFIED` | `UNKNOWN` | the host port list could not be read - nothing is claimed either way |
| `NOT_CONNECTED` | `DOWN` (or `UNKNOWN` if `not-connected-is-down: false`) | no session |

`SerialLinkMonitor` (scheduled every `tileboard.serial-monitor.interval`) additionally *acts* on `LINK_LOST`: after
`loss-confirmations` consecutive misses it calls `releaseIfLinkLost()`, which closes the dead client and publishes
`GatewayDisconnectedEvent` so the game engine unbinds. After re-plugging, `SerialAutoReconnector` restores the link by
itself within one `tileboard.serial-auto-reconnect.interval` (unless an explicit `disconnect` disarmed it);
`POST /api/v1/ports/connect` works again at any time too.
Use `/actuator/health/liveness|readiness` for container probes - they do not include `serialLink`.

### Error envelope

Every error: `{status: ERROR, message: <Persian>, data: null, extra: null, debugMessage: <raw English>}` with the status from the exception (409/404/502/400/500 — see the Error Handling section).

---

## Summary

This application:

1. **Abstracts hardware:** Only knows `SerialPortRegistry`/`SerialTransport` interfaces, not jSerialComm (single seam: `SerialGatewayConfig`).
2. **Is thread-safe:** Correctly uses `synchronized`, `ConcurrentHashMap`, `volatile` immutable snapshots, `ReentrantLock.tryLock()`, `AtomicInteger`, CAS, Caffeine/`@Transactional`/`@Version` for persistence — documented per class above.
3. **Is extensible:** Adding a new game is just a `@Bean` (auto-registered by the engine); adding a new *setting* is just one `SettingKeys` constant (no schema change, no table).
4. **Reports the truth about the link:** `linkStatus()` re-verifies the host port list behind a scan cache instead of trusting a remembered client, so an unplugged adapter is visible in `GET /api/v1/ports/status` and in the `serialLink` Actuator component immediately — and a failed enumeration is reported as `UNVERIFIED`, never as a fabricated loss.
5. **Recovers by itself:** `SerialLinkMonitor` releases a dead gateway after a configurable number of confirmations (so the engine stops driving a vanished port), and `SerialAutoReconnector` restores the link at startup and on schedule while it is armed — an explicit `disconnect` disarms it, so operator intent always wins.
6. **Is production-ready:** Per-session TTL, two-phase connect rollback, idempotent (dis)connect, `INTRODUCTION`/`START`/`STOP` hardware protocol, CORS, Actuator (`health,info` + liveness/readiness probes), Swagger starter, prod logging profile, localized error catalog, embedded SQLite with a Hibernate-managed schema.
7. **Persists what matters:** Device geometry and serial port assignment live in one generic `app_settings` table (JPA/Hibernate + SQLite) with a Caffeine read cache; live serial handles, the reconnect intent and running sessions deliberately stay in memory.
8. **Is educational:** Sample game `SequentialTouchGame` (3×3 default) demonstrates standby/countdown/win/lose animations and the concurrency patterns.

For more questions, see the READMEs of the `tileboard-serial-protocol` and `tileboard-game-engine` modules, and the
platform-level [README.md](../README.md).

---

**Author:** Tileboard Platform Team  
**Version:** 1.0.0  
**Java:** 17+  
**Spring Boot:** 3.3.4  
**Persistence:** JPA/Hibernate (`ddl-auto: update`) + SQLite (`jdbc:sqlite:app.db`) + Caffeine cache  
**Serial supervision:** verified `linkStatus()` + `SerialLinkMonitor` watchdog + `SerialAutoReconnector` + `serialLink` Actuator health
