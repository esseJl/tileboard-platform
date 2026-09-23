# Tileboard Platform - Comprehensive Platform Documentation

> **Tileboard Platform** is a complete system for controlling an LED tile board (m x n, max 255 tiles) over serial and running interactive games on it. It consists of three Maven modules: serial protocol, game engine, and a Spring Boot backend application (REST + SSE + JPA/Hibernate/Flyway persistence).

---

## Table of Contents
1. [Platform Introduction](#platform-introduction)
2. [Overall Architecture](#overall-architecture)
3. [Modules](#modules)
4. [Prerequisites](#prerequisites)
5. [Quick Start](#quick-start)
6. [Persistence, Settings and Cache](#persistence-settings-and-cache)
7. [Typical Workflow](#typical-workflow)
8. [Practical Example - SequentialTouchGame](#practical-example)
9. [Animations](#animations)
10. [Concurrency and Thread-Safety Across Platform](#concurrency)
11. [Tests and Build](#tests-and-build)
12. [Repository Structure](#repository-structure)
13. [Roadmap](#roadmap)

---

## Platform Introduction

Tileboard Platform was built to solve these problems:

- **Communication with LED Tile Board hardware** over serial port (115200-8-N-1) with a noise-resistant framing protocol (`0xFC … '#'` frames, resync + plausibility ceiling)
- **Hardware abstraction:** Game code never knows whether jSerialComm, RXTX, or a Mock is used (`SerialTransport` interface; single seam `SerialGatewayConfig`)
- **Production-ready game engine:** Scoring, health, levels, combos, timers, touch history, neighbor finding, patterns, waves, memory, reaction stats, graph queries, animations, SSE — framework-free core with an optional Spring adapter
- **Spring Boot backend:** REST API for device configuration, port management, game control, and real-time SSE streaming, with a localized (Persian) error catalog
- **Persistent configuration:** JPA/Hibernate (`ddl-auto: validate`) + Flyway migrations + SQLite (prod) / H2 file DB (dev), behind one generic, strongly-typed settings store (`SettingsService` + `SettingKey`) with a Caffeine read cache — device geometry and port assignment survive a restart, and adding a new setting needs no migration and no new table
- **Extensibility:** Adding a new game is just a `@Bean` (auto-registered by `TileboardEngineAutoConfiguration`), without changing engine or protocol

### Key Features

- ✅ **Transport-agnostic:** Protocol has zero dependency on a serial library (jSerialComm `2.11.0` is `optional`; the app pulls it in)
- ✅ **Framework-free core:** Game engine works without Spring; the Spring layer (`spring` package + `AutoConfiguration.imports`) is an optional adapter
- ✅ **Thread-safe:** `ConcurrentHashMap`, `AtomicReference`/`AtomicLong`/`AtomicBoolean`, `ReentrantLock`, `synchronized`, CAS, `CopyOnWriteArrayList`, `Semaphore` — each documented per class
- ✅ **Built-in animations:** `countdown` (+ win: 4 types, lose: 4 types, standby: 4 types, all cooperative-cancellation based)
- ✅ **Event-driven:** `GameEventBus` with `BLOCK` / `DROP_OLDEST` (default) policies; SSE to the frontend with `SseGameEvent` JSON payloads
- ✅ **Production-ready:** Per-session TTL (30 m in app, 1 h engine default), connect rollback, idempotent (dis)connect, `INTRODUCTION`/`START`/`STOP` hardware protocol, Actuator (`health,info`), Swagger starter, Micrometer gauges (when a `MeterRegistry` exists)
- ✅ **Persistent:** One `app_settings` table stores every configurable value as opaque JSON (`@Version` optimistic locking, Flyway-owned schema, `tileboard.settings.store=jpa` in prod / `memory` in dev), with a Caffeine cache in front of reads
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
│  ├─ Services: DeviceConfig (settings-backed), SerialConnection (sync),  │
│  │            SseBoardStateBroadcaster, Messages (fixed-fa i18n)        │
│  ├─ Settings: SettingsService (SettingKey registry) = InMemory | Jpa    │
│  ├─ GlobalExceptionHandler → ApiResponse{message fa, debugMessage en}   │
│  └─ GameBeansConfig: SequentialTouchGame @Bean (sized from device cfg)  │
├─────────────────────────────────────────────────────────────────────────┤
│  Persistence: JPA/Hibernate (ddl-auto=validate) + Flyway migrations     │
│  ├─ app_settings(setting_key PK, value_json, updated_at, version)       │
│  ├─ Dev: H2 file DB (./data/tileboard, MODE=PostgreSQL, env-overridable)│
│  ├─ Prod: SQLite (./data/app.db + hibernate-community-dialects)         │
│  └─ Read cache: Caffeine (cache "settings", TTL/max-size configurable)  │
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
8. In parallel with all of the above: configuration writes (`POST /api/v1/device`, `POST /api/v1/ports/{role}/assign`) go `SettingsBackedDeviceConfigurationService`/`DefaultSerialConnectionManager` → `SettingsService.set(SettingKey)` → JSON via `settingsObjectMapper` → `ApplicationSetting` row (`@Version`) → cache eviction; reads come back through the Caffeine cache or `InMemorySettingsService` (dev)

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
- `TileboardProperties` (`tileboard.serial`), `DeviceConfiguration` (≤255 tiles), `SerialGatewayConfig` (single jSerialComm seam), `GeneralConfiguration` (CORS), `CacheConfig` + `CacheSettingsProperties` (`tileboard.cache`, Caffeine `settings` cache): Config
- `DeviceController` (`/api/v1/device`), `SerialPortController` (`/api/v1/ports/{role}/assign`, `/status`, `/connect`, `/disconnect`), `GameController` (`/api/v1/games…`), `StreamController` (`/api/v1/stream/board[…]`): REST + SSE API
- `DeviceConfigurationRequest/Response`, `AssignPortRequest({portName})`, `SerialPortResponse`, `ConnectionStatusResponse({state,inPort,outPort})`, `GameDescriptorResponse`, `StartGameRequest({gameId, players:[{name,role}]})`, `PlayerRequest`, `GameSessionResponse({sessionId,gameId,status})`, `ApiResponse({status,message,data,extra,debugMessage})`, `ApiResponses`, `Status`: DTOs
- `DeviceConfigurationService`/`SettingsBackedDeviceConfigurationService`, `SerialConnectionManager`/`DefaultSerialConnectionManager` (+ `PortRole`, `PortAssignment`, `ConnectionState`, persisted assignment), `BoardStateBroadcaster`/`SseBoardStateBroadcaster` (`board-frame` events): Services
- `SettingsService` (`get`/`getOrDefault`/`set`/`clear`/`isSet`), `InMemorySettingsService` (`store=memory`), `JpaSettingsService` (`store=jpa`, default), `SettingKey<T>` + `SettingKeys` (append-only registry), `SettingsSerializationConfig` (`settingsObjectMapper`), `ApplicationSetting` (`@Entity @Table(app_settings)`, `@Version`) + `SettingRepository`, `SettingsPersistenceException`, `db/migration/V1__create_app_settings.sql`: Persistent settings
- `Messages` (fixed-`fa`), `ApiException` + 5 subclasses, `GlobalExceptionHandler` (409/404/502/400/500 mapping): i18n error handling
- `GameBeansConfig` (sizes the sample game from the persisted device configuration at startup), `SequentialTouchGame` (default 3×3): Sample tutorial game

**Full docs:** [tileboard-app/README.md](tileboard-app/README.md)

---

## Prerequisites

- **Java 17+** (`maven.compiler.source/target = 17`, engine uses `release = 17`)
- **Maven 3.8+**
- **Tileboard board** connected via USB (or a Mock `SerialTransport` — all unit tests run hardware-less)
- **Git**
- **No database server to install:** the app ships its own file databases — H2 (dev default, `./data/tileboard`) and SQLite (prod profile, `./data/app.db`, via `sqlite-jdbc`). The schema is created by Flyway migrations on startup and validated by Hibernate (`spring.jpa.hibernate.ddl-auto: validate`), so there is no manual SQL step.

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
- On first start Flyway migrates the embedded DB and the file appears next to the module (`tileboard-app/data/tileboard.mv.db` in dev, `tileboard-app/data/app.db` with the `prod` profile — the `.gitignore` already excludes the H2 file)
- Datasource is overridable without touching code: `TILEBOARD_DB_URL`, `TILEBOARD_DB_USER`, `TILEBOARD_DB_PASSWORD` (base `application.yml`); the `prod` profile pins the SQLite URL instead

### 3. Persistence in One Line

Dev default is an in-memory settings store (`tileboard.settings.store: memory`), prod persists (`jpa`). To make a dev run persistent without editing YAML:

```bash
TILEBOARD_SETTINGS_STORE=jpa java -jar target/tileboard-app-1.0.0.jar
```

See [Persistence, Settings and Cache](#persistence-settings-and-cache) for the full story.

### 4. Quick Test Without Hardware (Mock)

All unit tests pass without hardware:

```bash
mvn test
```

Only `TileboardHardwareIT` needs a real board (failsafe-based, skipped without a port):

```bash
mvn verify -P hardware-tests -pl tileboard-serial-protocol -Dtileboard.hardware.port=COM3
```

---

## Persistence, Settings and Cache

### Why one generic settings store instead of a table per feature?

The application has, and will keep getting, small pieces of configuration (board geometry
today, serial port assignment today, display theme / calibration offsets / notification
preferences tomorrow). Giving each one its own table, entity, repository and migration means
every new knob is a schema change. Instead, `tileboard-app` stores **every** configurable value
in a single `app_settings` table as opaque JSON, addressed by a stable string key, behind one
generic interface:

```
Controller → Service → SettingsService.set/get(SettingKey<T>) → (Jpa | InMemory)
                                       │
                                       ├─ JpaSettingsService: Caffeine cache → SettingRepository → app_settings row
                                       └─ InMemorySettingsService: ConcurrentHashMap (no DB at all)
```

### Storage Stack

| Concern | Choice | Where |
|-----|--------|-------|
| JPA provider | Hibernate ORM via `spring-boot-starter-data-jpa`, `spring.jpa.hibernate.ddl-auto: validate` | `tileboard-app/pom.xml`, `application.yml` |
| Schema owner | Flyway (`flyway-core`, `classpath:db/migration`, `spring.flyway.enabled: true`) | `application.yml`, `src/main/resources/db/migration/` |
| Dev database | H2 file DB — `jdbc:h2:file:./data/tileboard;MODE=PostgreSQL`, user `sa`, empty password; overridable with `TILEBOARD_DB_URL`/`TILEBOARD_DB_USER`/`TILEBOARD_DB_PASSWORD` | `application.yml` |
| Prod database | SQLite — `jdbc:sqlite:./data/app.db`, driver `org.sqlite.JDBC`, dialect `org.hibernate.community.dialect.SQLiteDialect` (from `hibernate-community-dialects`) | `application-prod.yml` |
| Read cache | Caffeine (`spring-boot-starter-cache` + `caffeine`), cache name `settings` | `CacheConfig`, `CacheSettingsProperties` |
| Persisted entity | `ApplicationSetting` (`@Entity @Table(name = "app_settings")`), `SettingRepository extends JpaRepository<ApplicationSetting, String>` | `settings/persistence/` |

> Note: Flyway 10 moved most databases into separate modules, but **H2 and SQLite stay inside
> `flyway-core`** — that is why both profiles migrate with the single dependency declared in the POM.

### The `app_settings` Table (migration `V1__create_app_settings.sql`)

```sql
CREATE TABLE app_settings
(
    setting_key VARCHAR(200) PRIMARY KEY,
    value_json  TEXT      NOT NULL,
    updated_at  TIMESTAMP NOT NULL,
    version     BIGINT    NOT NULL DEFAULT 0
);
```

- `setting_key` is the stable id of a `SettingKey` — treat it as a wire/DB contract: **add new ids, never rename or reuse one**.
- `value_json` is opaque JSON, so no schema change is ever needed for a new setting (the DB never interprets it).
- `updated_at` and `version` are maintained by the entity: `updated_at` is set on every write, `version` is a JPA `@Version` column (optimistic locking, see below).
- Flyway's own `flyway_schema_history` table is created alongside it; Hibernate only *validates* the schema at startup, so a mismatch between entity and migration fails the boot instead of silently altering tables.

### `SettingsService` — the single typed seam

```java
public interface SettingsService {
    <T> Optional<T> get(SettingKey<T> key);
    <T> T getOrDefault(SettingKey<T> key);      // falls back to SettingKey.defaultValue()
    <T> void set(SettingKey<T> key, T value);   // replaces any previous value
    void clear(SettingKey<?> key);              // back to "never set"
    boolean isSet(SettingKey<?> key);
}
```

Two implementations, mutually exclusive via `tileboard.settings.store`:

| Value of `tileboard.settings.store` | Bean | Behavior | When to use |
|-----|------|----------|-------|
| `memory` | `InMemorySettingsService` (`@ConditionalOnProperty(... havingValue = "memory")`) | `ConcurrentHashMap<String, Object>`; no DB read/write, values lost on restart | the default in `application.yml` — dev, demos, tests |
| `jpa` (or property absent) | `JpaSettingsService` (`havingValue = "jpa", matchIfMissing = true`) | JSON (de)serialization + `app_settings` + Caffeine cache; survives restarts | `application-prod.yml` (and the real default when no profile is active *and* the property is not set) |

Anything else (e.g. a typo like `redis`): **neither** bean matches, so no `SettingsService` exists and the context fails to start — a deliberate fail-fast rather than silently falling back to memory.

### `SettingKey` + `SettingKeys` — an append-only registry

```java
public record SettingKey<T>(String id, Class<T> type, T defaultValue) {
    public static <T> SettingKey<T> of(String id, Class<T> type) { ... }               // no default ("unset" is a real state)
    public static <T> SettingKey<T> of(String id, Class<T> type, T defaultValue) { ... }
}

public final class SettingKeys {
    public static final SettingKey<DeviceConfiguration> DEVICE_CONFIGURATION =
            SettingKey.of("device.configuration", DeviceConfiguration.class);
    public static final SettingKey<PortAssignment> SERIAL_PORT_ASSIGNMENT =
            SettingKey.of("serial.port-assignment", PortAssignment.class, PortAssignment.empty());
    private SettingKeys() { }
}
```

| Key id | Java type | Default | Stored JSON example |
|-----|-------|-------|--------|
| `device.configuration` | `DeviceConfiguration` | none (`null` → "not configured", the state behind `DeviceNotConfiguredException`) | `{"width":3,"height":3}` |
| `serial.port-assignment` | `PortAssignment` (`Optional<String> inPort`, `Optional<String> outPort`) | `PortAssignment.empty()` | `{"inPort":"COM3","outPort":"COM3"}` (an unassigned role is `null`) |

`SettingKey` is deliberately **not** an enum: a new setting is one more `public static final` constant, and no switch statement, repository, controller or migration has to change.

### `JpaSettingsService` — reads, writes, cache, optimistic locking

```java
@Override
@Transactional(readOnly = true)
public <T> Optional<T> get(SettingKey<T> key) {
    Cache.ValueWrapper cached = cache.get(key.id());
    if (cached != null) return (Optional<T>) cached.get();      // cache hit (also caches "absent")
    Optional<T> loaded = repository.findById(key.id())
            .map(entity -> deserialize(key, entity.getValue()));
    cache.put(key.id(), loaded);
    return loaded;
}

@Override
@Transactional
public <T> void set(SettingKey<T> key, T value) {
    String json = serialize(key, value);
    try {
        persist(key.id(), json);                                 // findById → mutate → save
    } catch (OptimisticLockingFailureException e) {
        log.warn("Concurrent update detected for setting '{}', retrying once", key.id());
        persist(key.id(), json);                                 // one retry against the fresh row
    } finally {
        cache.evict(key.id());                                   // next read is always consistent
    }
}
```

**Behavior notes (exactly as coded):**

1. **Cache in front of reads.** Settings are read far more often than written (every connect, every game start), so `get` is cache-first. `getOrDefault` is a plain `get` + `defaultValue()` fallback (no second cache entry).
2. **Negative caching.** `Optional.empty()` results are cached too, so a key that has never been written keeps answering "absent" for up to the TTL rather than hitting the DB every time.
3. **Writes evict, they don't update.** `set`/`clear` evict the key in a `finally`, so a failed write can never leave a stale entry behind.
4. **Optimistic locking + one retry.** The entity has `@Version version`; a lost update surfaces as `OptimisticLockingFailureException` (two admins saving at once). Because last-write-wins is acceptable for admin-driven settings, the service retries `persist(...)` exactly once instead of failing the request.
5. **`isSet` bypasses the cache** (`repository.existsById`), so it is always the DB's answer.
6. **Cache is mandatory.** The constructor resolves `CacheConfig.SETTINGS_CACHE` ("settings") from the injected `CacheManager` and throws `IllegalStateException` if it is missing — a misconfigured cache manager fails at startup, not at the first read.
7. **(De)serialization failures are wrapped** in `SettingsPersistenceException` with the key id, the expected type and a hint that the stored value may be corrupt or written by an incompatible version. It is a `RuntimeException` with no dedicated handler, so it reaches the client as a generic 500 (`server.internal_error`).
8. **JSON mapper is settings-only.** `SettingsSerializationConfig` exposes `settingsObjectMapper()` (a private `ObjectMapper` with `Jdk8Module` for `Optional`, `JavaTimeModule` for `Instant`, `FAIL_ON_UNKNOWN_PROPERTIES=false`) — independent of the web layer's mapper, since persistence is not a wire/API concern.

Writes go through `persist(keyId, json)`: `repository.findById(keyId)` → `orElseGet(new ApplicationSetting(...))` → `setValue` + `setUpdatedAt(Instant.now())` → `save`.

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
    settings-ttl-seconds: 300   # how long another instance's write stays invisible (multi-instance)
    settings-max-size: 100      # at most a handful of keys exist, so this is a safety bound
```

`CacheSettingsProperties(long settingsTtlSeconds, long settingsMaxSize)` is a record with a compact
constructor that clamps non-positive values back to `300` / `100`, so a `0` in YAML (or a missing
property) can never produce an eagerly-expiring or unbounded cache.

### Who Reads and Writes What

| Flow | Path |
|-----|------|
| `POST /api/v1/device` | `DeviceController` → `SettingsBackedDeviceConfigurationService.configure(w,h)` → `settingsService.set(DEVICE_CONFIGURATION, config)` |
| `GET /api/v1/device` | `SettingsBackedDeviceConfigurationService.current()` → `settingsService.get(DEVICE_CONFIGURATION)` → empty ⇒ `DeviceNotConfiguredException` (409) |
| `POST /api/v1/ports/{role}/assign` | `DefaultSerialConnectionManager.assign(role, portName)` → `withRole(...)` → `settingsService.set(SERIAL_PORT_ASSIGNMENT, updated)` |
| `GET /api/v1/ports/status`, `POST /ports/connect` | `DefaultSerialConnectionManager.currentAssignment()` → `settingsService.getOrDefault(SERIAL_PORT_ASSIGNMENT)` |
| Game registration at startup | `GameBeansConfig` reads `deviceConfigurationService.current()` to size `SequentialTouchGame` — with `store=jpa` the game is reborn with the **persisted** geometry after a restart |

`SettingsBackedDeviceConfigurationService` itself knows nothing about JPA, JSON or caching — swapping
the store never touches it or its callers.

### What Is and Is Not Persisted

| State | Persisted? | Why |
|-----|-------|-----|
| Device geometry (`device.configuration`) | ✅ with `store=jpa` (`memory` in dev) | read at every connect, at game registration, by the handshake |
| Serial port assignment (`serial.port-assignment`) | ✅ with `store=jpa` (`memory` in dev) | which OS port is IN/OUT is operator intent, not runtime state |
| Open OS handles, `TileGatewayClient` | ❌ never | a live serial connection cannot be reattached after a JVM restart |
| Game sessions, scores, engine gauges, SSE subscribers | ❌ never | in-memory by design (see `GameEngineImpl`'s TTL reaper) |

### Proving It Survives a Restart

```bash
# 1. persist in dev
TILEBOARD_SETTINGS_STORE=jpa mvn spring-boot:run -pl tileboard-app

# 2. configure the board once
curl -X POST http://localhost:8080/api/v1/device \
  -H "Content-Type: application/json" -d '{"width":3,"height":3}'
curl -X POST http://localhost:8080/api/v1/ports/OUT/assign \
  -H "Content-Type: application/json" -d '{"portName":"COM3"}'

# 3. stop the app (Ctrl-C), start it again, then:
curl http://localhost:8080/api/v1/device
# → 200 {status:SUCCESS, message:"Device successfully configured.", data:{width:3,height:3,tileCount:9}}
curl http://localhost:8080/api/v1/ports/status
# → 200 {status:SUCCESS, data:{state:"DISCONNECTED", inPort:null, outPort:"COM3"}}
#      ↑ assignment remembered, live link (correctly) not
```

With the dev default (`store=memory`) step 3 returns `409 device.not_configured` instead — the same
endpoints behave differently only because of the store switch.

### Adding a New Setting (no migration, no new table)

```java
// 1. declare it once, in the append-only registry
public static final SettingKey<ThemeSettings> THEME =
        SettingKey.of("ui.theme", ThemeSettings.class, ThemeSettings.defaults());

// 2. use it anywhere, typed
settingsService.set(SettingKeys.THEME, requested);
ThemeSettings theme = settingsService.getOrDefault(SettingKeys.THEME);
```

That is the whole change: `SettingsService` already stores/retrieves *any* type through the same
`app_settings` row shape, and the `settingsObjectMapper` handles the JSON.

A genuinely new **table** (e.g. a match-history table) is the only case that needs a migration:
add `V2__...sql` under `src/main/resources/db/migration/`, then the `@Entity` + `JpaRepository`.
`ddl-auto: validate` will refuse to start if the two disagree.

---

## Typical Workflow

### Step 1: Configure Device

```bash
curl -X POST http://localhost:8080/api/v1/device \
  -H "Content-Type: application/json" \
  -d '{"width":3,"height":3}'
```

The geometry is written through `SettingsService` → `app_settings` (`{"width":3,"height":3}`), so with
`tileboard.settings.store=jpa` this is a **one-time** call: it is still there after a restart, and
`GameBeansConfig` sizes the sample game from it on the next boot. With the dev default
(`store=memory`) it is replayed after every restart.

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
| `DefaultSerialConnectionManager` | `synchronized` + `EnumMap` + rollback + idempotent (dis)connect | Leak-free connect, dual topologies, handshake-before-start; IN/OUT assignment read from and written to `SettingsService` |
| `InMemorySettingsService` | `ConcurrentHashMap` | Store for the dev default (`tileboard.settings.store=memory`); no DB, no locking |
| `JpaSettingsService` | Caffeine cache + `@Transactional` + JPA `@Version` + eviction in `finally` | Cache-first reads (negative results cached too), one retry on `OptimisticLockingFailureException`, writes evict instead of update |
| `CacheConfig` (Caffeine) | `expireAfterWrite` + `maximumSize` (clamped in `CacheSettingsProperties`) | Bounds cross-instance staleness and memory; a missing cache fails fast at startup |
| `SettingsBackedDeviceConfigurationService` | Stateless delegate (`SettingsService` handles all thread-safety) | No cached copy of its own — every read reflects the store (and its cache) |
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

`TileboardApplicationTests` (`@SpringBootTest`) boots the real context, so it also exercises Flyway
migration + Hibernate `validate` against the dev H2 file DB — no external database or container is
required. The settings-backed test in `InMemoryDeviceGeneralConfigurationServiceTest` is currently
commented out (the class now only pins the `DeviceConfiguration` geometry limits), so persistence
behavior itself is expected to be checked with the restart walkthrough above.

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
    ├── pom.xml (spring-boot 3.3.4 parent; web, validation, actuator, springdoc 2.6.0, jSerialComm 2.11.0,
    │            data-jpa, cache + caffeine, sqlite-jdbc, h2 (runtime), flyway-core, hibernate-community-dialects)
    ├── README.md (comprehensive app docs + game tutorial)
    ├── data/ (created at runtime, git-ignored: tileboard.mv.db in dev / app.db in prod)
    └── src/main/
        ├── java/com/tileboard/app/
        │   ├── TileboardApplication.java
        │   ├── config/ (TileboardProperties, DeviceConfiguration, SerialGatewayConfig, GeneralConfiguration, CacheConfig, CacheSettingsProperties)
        │   ├── controller/ (DeviceController, SerialPortController, GameController, StreamController)
        │   ├── dto/ (ApiResponse, ApiResponses, Status, DeviceConfigurationRequest/Response, AssignPortRequest, SerialPortResponse, ConnectionStatusResponse, GameDescriptorResponse, StartGameRequest, PlayerRequest, GameSessionResponse)
        │   ├── service/device/ (DeviceConfigurationService, SettingsBackedDeviceConfigurationService)
        │   ├── service/serial/ (SerialConnectionManager, DefaultSerialConnectionManager, PortRole, PortAssignment, ConnectionState, SerialPortSummary)
        │   ├── service/streaming/ (BoardStateBroadcaster, SseBoardStateBroadcaster)
        │   ├── settings/ (SettingsService, SettingKey, SettingKeys, InMemorySettingsService, JpaSettingsService, SettingsPersistenceException)
        │   │   ├── conf/ (SettingsSerializationConfig → settingsObjectMapper)
        │   │   └── persistence/ (ApplicationSetting @Entity, SettingRepository)
        │   ├── exception/ (ApiException + 5 subclasses), exception/handler/ (GlobalExceptionHandler)
        │   ├── i18n/ (Messages)
        │   └── game/ (SequentialTouchGame, GameBeansConfig)
        └── resources/
            ├── application.yml / application-prod.yml (datasource, JPA validate, Flyway, settings store, cache, serial, engine)
            ├── db/migration/V1__create_app_settings.sql
            └── messages[_fa].properties
```

---

## Roadmap

- [x] Transport-agnostic serial protocol (framing, gateway, handshake, localizable errors)
- [x] Production-ready game engine (14 features, animations, event bus, SSE DTOs, Spring adapter)
- [x] Spring Boot backend (device/ports/games REST, SSE streaming, Persian error catalog)
- [x] win/lose/standby/countdown animations (4+4+4 types + scalable countdown)
- [x] Sample game SequentialTouchGame (3×3 default, device-sized)
- [x] Micrometer gauges (`tileboard.engine.active_sessions`, `tileboard.eventbus.*` — bound when a `MeterRegistry` exists; Prometheus endpoint not exposed)
- [x] Persistence for configuration (JPA/Hibernate `validate` + Flyway + SQLite/H2) behind the generic `SettingsService`/`SettingKeys` store, with a Caffeine read cache — device geometry and port assignment survive a restart
- [ ] Web frontend (React/Vue) for board display and game control
- [ ] Raw board-mirror SSE endpoint on top of `SseBoardStateBroadcaster` (seam is ready, unexposed)
- [ ] First `V2` migration + a real second table (e.g. match history / audit trail) — today the schema is a single settings table
- [ ] Settings admin API (list/clear keys over HTTP; today settings are only written through the feature endpoints)
- [ ] Distributed settings store / cluster-safe cache (Redis or etcd behind the same `SettingsService`) — with several instances the Caffeine TTL bounds staleness at 300 s
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

**Q: Where is my configuration stored, and does it survive a restart?**

A: Everything configurable goes through `SettingsService` into the single `app_settings` table as JSON: `device.configuration` (`{"width":3,"height":3}`) and `serial.port-assignment` (`{"inPort":"COM3","outPort":"COM3"}`) today. Whether it survives depends on one switch, `tileboard.settings.store`: `memory` (the value in the dev `application.yml`) keeps it in a `ConcurrentHashMap` and loses it on restart, `jpa` (the `prod` profile, and the default when the property is unset) persists it through Hibernate + Flyway with a Caffeine cache in front of reads. Live state — open serial handles and running game sessions — is never persisted.

**Q: Do I need to install a database?**

A: No. Dev runs on an H2 file DB (`./data/tileboard`, `MODE=PostgreSQL`) and prod on SQLite (`./data/app.db`) — both embedded, both created by Flyway on first start, and `spring.jpa.hibernate.ddl-auto: validate` guarantees the entities match what the migrations built. Point `TILEBOARD_DB_URL`/`TILEBOARD_DB_USER`/`TILEBOARD_DB_PASSWORD` elsewhere (e.g. PostgreSQL) if you would rather run a server; with Flyway 10 you would then also add the matching `flyway-database-*` module.

**Q: How do I add a new persisted setting?**

A: Declare one constant in `SettingKeys` (`SettingKey.of("ui.theme", ThemeSettings.class, ThemeSettings.defaults())`) and call `settingsService.set/getOrDefault(...)`. No migration, entity, repository or controller change — the value rides in the same `app_settings` row shape (`SettingKeys` is append-only: add ids, never rename them).

---

**Version:** 1.0.0  
**Java:** 17+  
**Spring Boot:** 3.3.4 (app) / 3.3.2 (engine Spring adapter)  
**Persistence:** JPA/Hibernate (`ddl-auto: validate`) + Flyway + H2 file DB (dev) / SQLite (prod) + Caffeine cache  
**Date:** 2026-09-23
