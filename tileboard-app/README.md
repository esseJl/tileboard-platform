# tileboard-app - مستندات جامع اپلیکیشن Spring Boot بک‌اند

> **ماموریت ماژول:** اپلیکیشن Spring Boot که برد LED تایل را از طریق سریال کنترل می‌کند. روی `tileboard-serial-protocol` و `tileboard-game-engine` ساخته شده. شامل REST API برای کانفیگ دستگاه، مدیریت پورت‌های سریال، کنترل بازی‌ها و استریم SSE است. این ماژول نقطه اتصال سخت‌افزار به دنیای وب است.

---

## فهرست مطالب
1. [معماری کلی و جایگاه در پلتفرم](#معماری-کلی)
2. [تکنولوژی‌ها](#تکنولوژیها)
3. [ساختار پکیج‌ها](#ساختار-پکیجها)
4. [کانفیگ - application.yml و TileboardProperties](#کانفیگ)
5. [DeviceConfiguration - هندسه برد](#deviceconfiguration)
6. [SerialGatewayConfig - انتزاع سخت‌افزار](#serialgatewayconfig)
7. [سرویس‌ها - لایه بیزینس](#سرویسها)
8. [کنترلرها - REST API](#کنترلرها)
9. [استریم SSE - BoardStateBroadcaster](#استریم-sse)
10. [GameEngineManager - پل Spring و موتور](#gameenginemanager)
11. [مدیریت خطا - GlobalExceptionHandler](#مدیریت-خطا)
12. [آموزش گام به گام اجرا و استفاده از API](#آموزش-گام-به-گام)
13. [آموزش جامع ساخت بازی - مثال عملی SequentialTouchGame](#آموزش-جامع-ساخت-بازی)
14. [استفاده از انیمیشن‌های win/lose/standby/countdown](#انیمیشنها)
15. [بررسی کدهای پیچیده - Concurrency و Complex Logic](#بررسی-کدهای-پیچیده)
16. [تست‌ها و اجرا](#تستها-و-اجرا)

---

## معماری کلی

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

**جریان داده معمول:**

1. Operator دستگاه را کانفیگ می‌کند: `POST /devices/configure {width, height}`
2. پورت‌های سریال را لیست می‌کند: `GET /ports`
3. پورت‌ها را assign می‌کند: `POST /ports/assign {role, portName}`
4. وصل می‌شود: `POST /ports/connect` → `DefaultSerialConnectionManager.connect()` → `TileGatewayClient` ساخته می‌شود → `GatewayConnectedEvent` publish می‌شود → `GameEngineManager` یک `GameEngineImpl` جدید می‌سازد
5. بازی‌ها را لیست می‌کند: `GET /games` (از `GameRegistry`)
6. بازی را شروع می‌کند: `POST /games/sessions {gameId, players}` → `GameEngine.startGame()` → `GameSessionImpl` ساخته می‌شود → `onStart()` بازی صدا زده می‌شود
7. SSE وصل می‌شود: `GET /stream/board` یا `/games/events` → برد و امتیاز و رویدادها به صورت real-time
8. بازیکن تایل‌ها را لمس می‌کند → `TileGatewayClient` فریم `DATA_IN` می‌گیرد → `EngineFrameRouter` → `TouchFrameRouter` → `GameSessionImpl.handleTileEvent` → `game.onTileEvent`
9. بازی برد/باخت می‌شود → `winSession`/`loseSession` → `finishSession` → `GameResult` → برد خاموش → SSE `SESSION_FINISHED`

---

## تکنولوژی‌ها

- **Java 17**, **Spring Boot 3.3.4**, **Spring MVC**, **Spring Actuator**
- **jSerialComm 2.11.0** برای ارتباط سریال
- **springdoc-openapi 2.6.0** برای Swagger UI
- **Jackson** برای JSON
- **SLF4J** برای لاگ
- **Maven** برای بیلد

---

## ساختار پکیج‌ها

| پکیج | مسئولیت |
|------|---------|
| `com.tileboard.app` | `TileboardApplication` (main) |
| `config` | `TileboardProperties`, `DeviceConfiguration`, `SerialGatewayConfig`, `GeneralConfiguration` (CORS) |
| `controller` | REST controllers: `DeviceController`, `SerialPortController`, `GameController`, `StreamController` |
| `dto` | DTO های API: `DeviceConfigurationRequest`, `AssignPortRequest`, `StartGameRequest`, `GameSessionResponse`, `ApiResponse`, ... |
| `service.device` | `DeviceConfigurationService` + `InMemoryDeviceConfigurationService` |
| `service.serial` | `SerialConnectionManager` + `DefaultSerialConnectionManager`, `PortRole`, `ConnectionState` |
| `service.streaming` | `BoardStateBroadcaster` + `SseBoardStateBroadcaster` |
| `exception` | `ApiException` و زیرکلاس‌ها + `GlobalExceptionHandler` |
| `game` | **بازی‌های نمونه**: `SequentialTouchGame`, `GameBeansConfig` (جدید) |

---

## کانفیگ

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
# با --spring.profiles.active=prod فعال می‌شود
logging:
  level:
    root: INFO
    com.tileboard: INFO
```

**چرا DEBUG در dev؟** چون `JSerialCommTransport` در سطح DEBUG بایت‌های TX/RX را با hex لاگ می‌کند، که برای دیباگ پروتکل مفید است اما در production پر سر و صدا.

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

- `record` با compact constructor برای اعمال defaults
- `@ConfigurationPropertiesScan` در `TileboardApplication` آن را فعال می‌کند

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

- هندسه فیزیکی برد: چند تایل عرض و ارتفاع
- محدودیت 255 از `DeviceAddress` می‌آید که تعداد کل تایل‌ها را در یک بایت کد می‌کند (سقف پروتکل)
- این تنها اطلاعاتی است که هر ماژول دیگر (handshake، game engine) قبل از هر کار مفید نیاز دارد

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

**این تنها جایی در کل اپلیکیشن است که می‌داند `JSerialCommPortRegistry` استفاده می‌شود.** اگر بخواهی کتابخانه سریال را عوض کنی (یا یک Mock برای demo بدون سخت‌افزار بسازی)، فقط همین Bean را عوض می‌کنی. بقیه کد فقط `SerialPortRegistry` interface را می‌شناسد.

---

## سرویس‌ها

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

- `AtomicReference` → thread-safe بدون synchronized، چون فقط یک value را نگه می‌دارد
- `Optional` برای حالت "هنوز کانفیگ نشده"
- TODO: در آینده با یک implementation مبتنی بر repository (DB) جایگزین شود، چون تمام consumer ها فقط interface را می‌شناسند

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

#### DefaultSerialConnectionManager - پیاده‌سازی

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

**نکات concurrency و complex logic:**

1. **synchronized روی متدهای mutating:** `assign`, `connectionState`, `connect`, `disconnect` همگی `synchronized` هستند. چون این‌ها عملیات admin هستند (operator-driven) و نباید همزمان از چند thread صدا زده شوند، synchronized ساده کافی است و از پیچیدگی lock های دیگر جلوگیری می‌کند.

2. **EnumMap:** برای `assignedPorts` و `openTransports` از `EnumMap` استفاده شده که برای کلیدهای enum بهینه است (آرایه داخلی، نه hash).

3. **دو توپولوژی شفاف:**
   - اگر IN و OUT یک نام داشته باشند → یک `SerialTransport` shared باز می‌شود و با `builder.transport(shared)` استفاده می‌شود (full-duplex)
   - اگر جدا باشند → دو transport جدا باز می‌شوند
   - اگر فقط OUT assign شده باشد → هشدار لاگ می‌شود که "OUTPUT ONLY" است و لمس‌ها هرگز دریافت نمی‌شوند. این بهتر از سکوت و نیمه‌کار کردن است.

4. **Rollback در صورت شکست connect:**
   ```java
   Map<PortRole, SerialTransport> openedThisAttempt = new EnumMap<>();
   boolean success = false;
   try {
       // باز کردن پورت‌ها
       success = true;
   } finally {
       if (!success) closeQuietly(openedThisAttempt.values());
   }
   ```
   - `openedThisAttempt` فقط transport هایی که در این تلاش باز شده‌اند را نگه می‌دارد
   - اگر باز کردن پورت دوم fail شد، `finally` transport اولی را می‌بندد تا OS handle leak نشود. بدون این، یک `connect` ناموفق handle پورت را برای همیشه باز نگه می‌داشت و تلاش بعدی `connect` دوباره fail می‌شد چون پورت هنوز توسط JVM قبلی اشغال است.

5. **ترتیب handshake و start:**
   ```java
   enableHandshakeIfDeviceKnown(newClient); // اول listener ها را ثبت کن
   // ...
   newClient.start(); // بعد input pipe را باز کن
   ```
   - اگر `start()` اول صدا زده شود، اولین فریم‌های برد (مثلا درخواست اولیه handshake ID/CLEAR) ممکن است قبل از ثبت `HandshakeCoordinator` برسند و چون `TileGatewayClient.dispatch()` فقط listener هایی که تا آن لحظه ثبت شده‌اند را notify می‌کند، آن فریم‌ها بی‌صدا drop می‌شوند.

6. **Event publishing:** بعد از ساخت client، `GatewayConnectedEvent` publish می‌شود که `GameEngineManager` را بیدار می‌کند تا engine بسازد. سپس `client.start()` صدا زده می‌شود تا دیتا شروع به آمدن کند.

7. **closeQuietly:** حتی اگر `close()` یک transport exception دهد، بقیه transport ها بسته می‌شوند.

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

این سرویس برد را به تمام SSE client های متصل broadcast می‌کند.

---

## کنترلرها

### DeviceController

```
POST /api/v1/devices/configure
Body: { "width": 8, "height": 8 }
Response: { "width": 8, "height": 8, "tileCount": 64 }

GET /api/v1/devices/configuration
Response: { "width": 8, "height": 8, ... } یا 404 اگر کانفیگ نشده
```

- `DeviceConfigurationRequest` با validation (`@Min(1)`, `@Max(255)`)
- `DeviceConfigurationResponse` از `DeviceConfiguration`

### SerialPortController

```
GET /api/v1/ports
Response: [{ "systemName": "COM3", "description": "USB Serial Port" }, ...]

POST /api/v1/ports/assign
Body: { "role": "OUT", "portName": "COM3" }  # role = IN یا OUT
Response: { "inPort": "COM3", "outPort": "COM3" }

GET /api/v1/ports/assignment
Response: { "inPort": "...", "outPort": "..." }

POST /api/v1/ports/connect
Response: { "status": "CONNECTED", "inPort": "...", "outPort": "..." }

POST /api/v1/ports/disconnect
Response: 204 No Content

GET /api/v1/ports/status
Response: { "status": "CONNECTED" یا "DISCONNECTED", "assignment": {...} }
```

### GameController

```
GET /api/v1/games
Response: [{ "gameId": "sequential-touch", "displayName": "Sequential Touch Challenge", "category": "TUTORIAL", ... }, ...]
# حتی قبل از connect برد هم کار می‌کند (از GameRegistry)

POST /api/v1/games/sessions
Body: { "gameId": "sequential-touch", "players": [{ "name": "Ali" }] }
Response: { "sessionId": "uuid", "gameId": "sequential-touch", "status": "RUNNING", "players": [...], "scores": {...} }

GET /api/v1/games/sessions
Response: لیست session های فعال

GET /api/v1/games/sessions/{sessionId}
Response: یک session

POST /api/v1/games/sessions/{sessionId}/stop
Response: 204
```

- `engineManager.require()` → اگر engine هنوز bound نشده (برد وصل نیست)، `EngineNotReadyException` می‌دهد که توسط `GlobalExceptionHandler` به 409 Conflict تبدیل می‌شود
- `StartGameRequest` با validation

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

- از `SseEmitter` Spring استفاده می‌کند
- Heartbeat هر 15 ثانیه برای جلوگیری از timeout proxy

---

## مدیریت خطا

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

تمام خطاها به فرمت یکسان `ApiResponse` برمی‌گردند:

```json
{
  "status": "ERROR",
  "message": "توضیح خطا",
  "data": null
}
```

---

## آموزش گام به گام

### پیش‌نیازها

- Java 17+
- Maven 3.8+
- یک برد Tileboard متصل به USB (یا Mock برای تست بدون سخت‌افزار)

### گام 1: بیلد

```bash
git clone <repo>
cd tileboard-platform
mvn clean install -DskipTests
```

### گام 2: اجرا

```bash
cd tileboard-app
mvn spring-boot:run
# یا
java -jar target/tileboard-app-1.0.0.jar

# با پروفایل prod:
java -jar target/tileboard-app-1.0.0.jar --spring.profiles.active=prod
```

اپلیکیشن روی `http://localhost:8080` بالا می‌آید.

Swagger UI: `http://localhost:8080/swagger-ui.html`

Actuator: `http://localhost:8080/actuator/health`

### گام 3: کانفیگ دستگاه

```bash
curl -X POST http://localhost:8080/api/v1/devices/configure \
  -H "Content-Type: application/json" \
  -d '{"width":8,"height":8}'
```

پاسخ:
```json
{
  "status": "SUCCESS",
  "data": { "width": 8, "height": 8, "tileCount": 64 }
}
```

### گام 4: لیست پورت‌ها

```bash
curl http://localhost:8080/api/v1/ports
```

پاسخ:
```json
{
  "status": "SUCCESS",
  "data": [
    { "systemName": "COM3", "description": "USB Serial Port" },
    { "systemName": "COM4", "description": "USB Serial Port" }
  ]
}
```

### گام 5: Assign پورت‌ها

اگر برد شما یک پورت full-duplex دارد (معمول):

```bash
curl -X POST http://localhost:8080/api/v1/ports/assign \
  -H "Content-Type: application/json" \
  -d '{"role":"OUT","portName":"COM3"}'

curl -X POST http://localhost:8080/api/v1/ports/assign \
  -H "Content-Type: application/json" \
  -d '{"role":"IN","portName":"COM3"}'
```

اگر دو آداپتور half-duplex دارید:

```bash
curl -X POST http://localhost:8080/api/v1/ports/assign -d '{"role":"OUT","portName":"COM3"}'
curl -X POST http://localhost:8080/api/v1/ports/assign -d '{"role":"IN","portName":"COM4"}'
```

### گام 6: Connect

```bash
curl -X POST http://localhost:8080/api/v1/ports/connect
```

پاسخ:
```json
{
  "status": "SUCCESS",
  "data": { "status": "CONNECTED", "inPort": "COM3", "outPort": "COM3" }
}
```

در لاگ‌ها باید ببینی:
```
Enabling id handshake for a 8x8 board (minimumSequence=2)
Tile board gateway connected (in=COM3, out=COM3)
Game engine bound to the newly connected tile gateway (8x8)
```

### گام 7: لیست بازی‌ها

```bash
curl http://localhost:8080/api/v1/games
```

پاسخ:
```json
{
  "status": "SUCCESS",
  "data": [
    {
      "gameId": "sequential-touch",
      "displayName": "Sequential Touch Challenge",
      "category": "TUTORIAL",
      "description": "به ترتیب هر تایل روشن می‌شود؛ با لمس آن امتیاز بگیر...",
      "requiredWidth": 8,
      "requiredHeight": 8,
      "minPlayers": 1,
      "maxPlayers": 1
    }
  ]
}
```

### گام 8: شروع بازی

```bash
curl -X POST http://localhost:8080/api/v1/games/sessions \
  -H "Content-Type: application/json" \
  -d '{
    "gameId": "sequential-touch",
    "players": [{"name":"Ali"}]
  }'
```

پاسخ:
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

در این لحظه روی برد:
1. انیمیشن standby (BREATHING) 2 ثانیه
2. انیمیشن countdown (3→2→1) حدود 2.1 ثانیه
3. اولین تایل روشن می‌شود (مثلا (0,0) قرمز)

### گام 9: SSE - دیدن برد به صورت real-time

در یک ترمینال دیگر:

```bash
curl -N -H "Accept: text/event-stream" http://localhost:8080/api/v1/games/events
```

یا با JS در مرورگر:

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

### گام 10: بازی کردن

- تایل روشن را لمس کن → امتیاز +10، تایل خاموش، تایل بعدی روشن
- اگر تایل اشتباه لمس کنی → انیمیشن FADE_TO_RED کوتاه، سپس تایل درست دوباره روشن
- اگر 90 ثانیه طول بدهی → انیمیشن DESCENDING_CURTAIN و باخت
- اگر همه 64 تایل را به ترتیب لمس کنی → انیمیشن RADIAL_BURST و برد

### گام 11: توقف بازی

```bash
curl -X POST http://localhost:8080/api/v1/games/sessions/{sessionId}/stop
```

### گام 12: Disconnect

```bash
curl -X POST http://localhost:8080/api/v1/ports/disconnect
```

---

## آموزش جامع ساخت بازی

این بخش مهم‌ترین بخش مستندات است و به صورت گام به گام ساخت بازی **SequentialTouchGame** را که تمام انیمیشن‌های درخواستی را دارد توضیح می‌دهد.

### سناریو بازی

> به ترتیب هر تایل با رنگی روشن شود و به محض لمس شدن با اضافه شدن امتیاز بازیکن همراه شود و نوبت تایل بعدی بشود تا وقتی که همه‌ی تایل‌ها روشن و تاچ شوند سپس بازی خاتمه یابد. همچنین از انیمیشن‌های lose, win, standby در بازی و قبل از شروع بازی انیمیشن countdown استفاده کن.

### گام 1: ساخت کلاس بازی

فایل: `src/main/java/com/tileboard/app/game/SequentialTouchGame.java`

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

    // کلیدهای GameState
    private static final String KEY_POSITIONS = "sequential.positions";
    private static final String KEY_INDEX = "sequential.index";

    // پالت رنگی
    private static final TileColor[] PALETTE = {
        TileColor.RED, TileColor.GREEN, TileColor.BLUE,
        TileColor.YELLOW, TileColor.PINK, TileColor.LIGHT_BLUE, TileColor.WHITE
    };

    private final GameDescriptor descriptor;

    public SequentialTouchGame() {
        this.descriptor = GameDescriptor.builder("sequential-touch", "Sequential Touch Challenge")
            .category("TUTORIAL")
            .description("به ترتیب هر تایل روشن می‌شود؛ با لمس آن امتیاز بگیر و به تایل بعدی برو. شامل countdown، standby، win و lose انیمیشن.")
            .boardSize(8, 8)
            .players(1, 1)
            .build();
    }

    public SequentialTouchGame(int width, int height) {
        this.descriptor = GameDescriptor.builder("sequential-touch", "Sequential Touch Challenge")
            .category("TUTORIAL")
            .description("به ترتیب هر تایل روشن می‌شود؛ با لمس آن امتیاز بگیر و به تایل بعدی برو.")
            .boardSize(width, height)
            .players(1, 1)
            .build();
    }

    @Override public GameDescriptor descriptor() { return descriptor; }
```

### گام 2: پیاده‌سازی onStart - شامل standby و countdown

```java
    @Override
    public void onStart(GameContext ctx) {
        log.info("[{}] Game onStart - board {}x{}", ctx.sessionId(), ctx.boardWidth(), ctx.boardHeight());

        // برد را خاموش و امتیاز را ریست کن
        ctx.fillBoard(TileColor.OFF);
        ctx.scores().resetAll();
        ctx.state().clear();

        // 1. انیمیشن standby: BREATHING به مدت 2 ثانیه
        // این انیمیشن بی‌نهایت است تا cancel شود
        try {
            log.info("[{}] Playing STANDBY (BREATHING) for 2 seconds...", ctx.sessionId());
            ctx.animations().playStandbyAnimation(AnimationSystem.StandbyAnimationType.BREATHING)
                .get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Timeout → انیمیشن هنوز در حال اجراست، cancel کن
            ctx.animations().cancelCurrent();
            log.info("[{}] Standby cancelled, moving to countdown", ctx.sessionId());
        }

        // 2. انیمیشن countdown: 3 → 2 → 1 → چشمک سبز
        // playCountdown روی SingleThreadExecutor انیمیشن اجرا می‌شود
        // join() تا پایان countdown صبر می‌کند
        try {
            log.info("[{}] Playing COUNTDOWN...", ctx.sessionId());
            ctx.animations().playCountdown(700).join(); // هر رقم 700ms
        } catch (Exception e) {
            log.warn("[{}] Countdown interrupted", ctx.sessionId(), e);
        }

        // 3. لیست تمام موقعیت‌ها row-major
        List<Position> allPositions = new ArrayList<>();
        for (int r = 0; r < ctx.boardHeight(); r++) {
            for (int c = 0; c < ctx.boardWidth(); c++) {
                allPositions.add(new Position(r, c));
            }
        }

        ctx.state().put(KEY_POSITIONS, allPositions);
        ctx.state().put(KEY_INDEX, 0);

        // 4. تایمر کلی: اگر در 90 ثانیه تمام نشد، باخت
        // GameTimer از AtomicReference<Runnable> برای onExpire استفاده می‌کند
        // checkExpiry() هر tick (100ms) توسط GameSessionImpl.runTick() صدا زده می‌شود
        ctx.timer().startCountdown(Duration.ofSeconds(90), () -> {
            log.info("[{}] Timer expired - LOST", ctx.sessionId());
            ctx.animations().playLoseAnimation(AnimationSystem.LoseAnimationType.DESCENDING_CURTAIN)
                .thenRun(() -> ctx.loseSession());
        });

        // 5. اولین تایل را روشن کن
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

        // BoardChannel.setTile thread-safe است:
        // - stateLock (ReentrantLock) برای بافر داخلی
        // - gatewayWriteLock (synchronized) برای سریالایز کردن write روی سیم
        ctx.setTile(pos.row(), pos.col(), color);
    }
```

**توضیحات concurrency در onStart:**

- `onStart` روی thread ای که `startGame` را صدا زده اجرا می‌شود (معمولا HTTP request thread). پس `get(2, SECONDS)` و `join()` که بلاک می‌کنند مشکلی ندارند چون tick thread را بلاک نمی‌کنند.
- `ctx.state()` یک `GameState` است که تمام متدهایش `synchronized` هستند → thread-safe
- `ctx.animations()` یک `AnimationSystem` است که فقط یک انیمیشن همزمان دارد و با generation-based cancellation کار می‌کند
- `ctx.timer()` یک `GameTimer` است که `volatile` و `AtomicReference` دارد

### گام 3: پیاده‌سازی onTileEvent - منطق اصلی بازی

```java
    @Override
    public void onTileEvent(GameContext ctx, TileEvent event) {
        // فقط وقتی RUNNING است صدا زده می‌شود (چک در GameSessionImpl.handleTileEvent)

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

        // ScoreSystem از ConcurrentHashMap<String, AtomicInteger> استفاده می‌کند
        // add() با AtomicInteger.addAndGet thread-safe است
        int newScore = ctx.scores().add(playerId, 10);
        log.info("[{}] Correct! Tile {}/{} touched, score={}", ctx.sessionId(), currentIndex+1, positions.size(), newScore);

        // تایل فعلی را خاموش کن
        Position justTouched = positions.get(currentIndex);
        ctx.setTile(justTouched.row(), justTouched.col(), TileColor.OFF);

        // برو تایل بعدی
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

        // انیمیشن lose کوتاه: FADE_TO_RED
        // چون AnimationSystem فقط یک انیمیشن همزمان دارد، این تایل فعلی را موقتا override می‌کند
        // بعد از اتمام، دوباره تایل جاری را روشن می‌کنیم
        ctx.animations().playLoseAnimation(AnimationSystem.LoseAnimationType.FADE_TO_RED)
            .thenRun(() -> lightCurrentTile(ctx));
    }

    private void handleWin(GameContext ctx) {
        log.info("[{}] All tiles touched! WINS", ctx.sessionId());
        ctx.timer().stop();

        // انیمیشن win: RADIAL_BURST
        // سپس winSession که باعث finishSession در GameSessionImpl می‌شود
        // finishSession با CAS تضمین می‌کند فقط یک بار اجرا شود
        ctx.animations().playWinAnimation(AnimationSystem.WinAnimationType.RADIAL_BURST)
            .thenRun(() -> ctx.winSession(ctx.players()));
    }
```

### گام 4: پیاده‌سازی onStop و onError

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

### گام 5: ثبت به عنوان Spring Bean

فایل: `src/main/java/com/tileboard/app/game/GameBeansConfig.java`

```java
package com.tileboard.app.game;

import com.tileboard.app.config.DeviceConfiguration;
import com.tileboard.app.service.device.DeviceConfigurationService;
import com.tileboard.engine.core.Game;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GameBeansConfig {

    private static final Logger log = LoggerFactory.getLogger(GameBeansConfig.class);
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
            log.info("Creating SequentialTouchGame with device size {}x{} from current config", width, height);
        } else {
            log.info("No device config yet, creating SequentialTouchGame with default {}x{}", width, height);
        }
        return new SequentialTouchGame(width, height);
    }
}
```

**چرا این کار می‌کند؟** چون `TileboardEngineAutoConfiguration.gameRegistry()` تمام Bean های نوع `Game` را auto-register می‌کند:

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

پس کافی است بازی را به عنوان `@Bean` تعریف کنی تا در `GET /api/v1/games` ظاهر شود.

### گام 6: بیلد و اجرا

```bash
mvn clean install -DskipTests
cd tileboard-app
mvn spring-boot:run
```

### گام 7: تست بازی

```bash
# کانفیگ دستگاه
curl -X POST http://localhost:8080/api/v1/devices/configure -H "Content-Type: application/json" -d '{"width":8,"height":8}'

# لیست پورت‌ها
curl http://localhost:8080/api/v1/ports

# assign و connect (فرض COM3)
curl -X POST http://localhost:8080/api/v1/ports/assign -H "Content-Type: application/json" -d '{"role":"OUT","portName":"COM3"}'
curl -X POST http://localhost:8080/api/v1/ports/assign -H "Content-Type: application/json" -d '{"role":"IN","portName":"COM3"}'
curl -X POST http://localhost:8080/api/v1/ports/connect

# لیست بازی‌ها - باید sequential-touch را ببینی
curl http://localhost:8080/api/v1/games

# شروع بازی
curl -X POST http://localhost:8080/api/v1/games/sessions -H "Content-Type: application/json" -d '{"gameId":"sequential-touch","players":[{"name":"Ali"}]}'

# SSE برای دیدن رویدادها
curl -N -H "Accept: text/event-stream" http://localhost:8080/api/v1/games/events
```

### جریان کامل بازی از دید بازیکن

1. **Standby (BREATHING):** گوشه‌های برد با آبی روشن/تیره چشمک می‌زنند (2 ثانیه) - حالت انتظار
2. **Countdown:** 
   - برد کامل قرمز (3)
   - برد کامل زرد (2)
   - برد کامل سبز (1)
   - چشمک سبز 3 بار (GO!)
3. **بازی:** تایل (0,0) قرمز روشن می‌شود
4. بازیکن (0,0) را لمس می‌کند → امتیاز 10، (0,0) خاموش، (0,1) سبز روشن
5. بازیکن (0,1) را لمس می‌کند → امتیاز 20، (0,1) خاموش، (0,2) آبی روشن
6. ... تا (7,7)
7. اگر بازیکن تایل اشتباه لمس کند → انیمیشن FADE_TO_RED (برد کم‌کم قرمز می‌شود) → دوباره تایل درست روشن
8. اگر 90 ثانیه طول بدهد → DESCENDING_CURTAIN (پرده قرمز از بالا به پایین) → باخت
9. اگر همه 64 تایل درست لمس شوند → RADIAL_BURST (موج رنگی از مرکز) → برد

---

## انیمیشن‌ها

### لیست انیمیشن‌های موجود

#### Countdown

```java
ctx.animations().playCountdown() // پیش‌فرض 1000ms هر رقم
ctx.animations().playCountdown(700) // سفارشی 700ms
```

- اگر برد کوچکتر از 3x5 باشد: کل برد به رنگ‌های قرمز، زرد، سبز روشن می‌شود (simple)
- اگر برد بزرگتر باشد: رقم‌های 3،2،1 با الگوی 5x3 در مرکز رندر می‌شوند و سپس چشمک سبز

#### Win

```java
public enum WinAnimationType {
    RADIAL_BURST,    // موج رنگی از مرکز به بیرون
    RAINBOW_SWEEP,   // جاروی رنگین‌کمانی ستونی
    SPARKLE,         // جرقه‌های تصادفی
    FIREWORKS        // آتش‌بازی در نقاط تصادفی
}

ctx.animations().playWinAnimation() // پیش‌فرض RADIAL_BURST
ctx.animations().playWinAnimation(WinAnimationType.FIREWORKS)
```

#### Lose

```java
public enum LoseAnimationType {
    FADE_TO_RED,          // محو شدن به قرمز با نویز
    DESCENDING_CURTAIN,   // پرده قرمز از بالا
    CRUMBLE,              // فروپاشی از زرد به قرمز
    PULSE_RED             // چشمک قرمز
}

ctx.animations().playLoseAnimation()
ctx.animations().playLoseAnimation(LoseAnimationType.CRUMBLE)
```

#### Standby

```java
public enum StandbyAnimationType {
    BREATHING,      // گوشه‌ها و مرز با تنفس آبی
    CORNER_PULSE,   // پالس رنگی در گوشه‌ها
    WAVE_BORDER,    // موج روی مرز
    RANDOM_TWINKLE  // چشمک تصادفی سفید
}

ctx.animations().playStandbyAnimation()
ctx.animations().playStandbyAnimation(StandbyAnimationType.WAVE_BORDER)
```

**ویژگی خاص standby:** این انیمیشن‌ها بی‌نهایت اجرا می‌شوند تا cancel شوند. برای استفاده به عنوان "حالت انتظار قبل از شروع" باید:

```java
try {
    ctx.animations().playStandbyAnimation(StandbyAnimationType.BREATHING)
        .get(2, TimeUnit.SECONDS); // 2 ثانیه اجرا
} catch (TimeoutException e) {
    ctx.animations().cancelCurrent(); // cancel
}
```

یا:

```java
CompletableFuture<Void> standby = ctx.animations().playStandbyAnimation(...);
Thread.sleep(2000);
standby.cancel(false);
ctx.animations().cancelCurrent();
```

### پیاده‌سازی فنی انیمیشن‌ها

تمام انیمیشن‌ها روی یک `SingleThreadExecutor` به نام `tileboard-animation` اجرا می‌شوند. هر انیمیشن جدید انیمیشن قبلی را با الگوی generation-based cooperative cancellation کنسل می‌کند (توضیح کامل در README موتور بازی).

```java
// داخل AnimationSystem
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

این یعنی انیمیشن‌ها به صورت cooperative چک می‌کنند که آیا کنسل شده‌اند و اگر بله، خودشان تمیز خارج می‌شوند بدون اینکه thread را force kill کنیم.

### استفاده از انیمیشن در اپ اسپرینگ

در اپ اسپرینگ، انیمیشن‌ها از طریق `GameContext.animations()` در دسترس هستند که در `FeatureBundle` ساخته می‌شود و `boardPublisher` آن همان `BoardChannel.publish` است که در نهایت به `TileGatewayClient.sendBoard` می‌رسد.

برای استفاده خارج از بازی (مثلا در یک کنترلر ادمین برای تست برد):

```java
@RestController
public class AdminAnimationController {

    private final GameEngineManager engineManager;

    @PostMapping("/api/v1/admin/animations/countdown")
    public void playCountdown() {
        GameEngine engine = engineManager.require();
        // گرفتن یک session فعال یا ساخت یک session موقت برای تست
        // ...
    }
}
```

اما توصیه می‌شود انیمیشن‌ها فقط داخل بازی‌ها استفاده شوند، چون `AnimationSystem` per-session است.

---

## بررسی کدهای پیچیده

### 1. DefaultSerialConnectionManager - synchronized + rollback + dual topology

**مشکل:** `connect()` ممکن است از چند thread همزمان صدا زده شود (دو ادمین همزمان). همچنین باز کردن پورت‌ها ممکن است نیمه‌کاره fail شود (پورت اول باز می‌شود، دومی fail).

**راه حل:**

- `synchronized` روی `connect()`, `disconnect()`, `assign()` → فقط یک thread در یک لحظه می‌تواند state را تغییر دهد
- `openedThisAttempt` + `finally` rollback → اگر هر مرحله fail شد، تمام transport هایی که در این تلاش باز شده‌اند بسته می‌شوند تا OS handle leak نشود
- `EnumMap` برای `assignedPorts` → بهینه برای enum keys
- `shared transport` detection: اگر IN و OUT یک نام باشند، فقط یک بار باز می‌شود

```java
Map<PortRole, SerialTransport> openedThisAttempt = new EnumMap<>(PortRole.class);
boolean success = false;
try {
    if (inPort != null && inPort.equals(outPort)) {
        SerialTransport shared = openPort(outPort, config);
        openedThisAttempt.put(OUT, shared);
        builder.transport(shared);
    } else {
        // باز کردن OUT و IN جدا
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

- `AtomicReference` بدون `synchronized` thread-safe است برای یک value
- `get()` و `set()` هر دو اتمی و visible بین thread ها هستند
- `Optional` برای حالت "هنوز کانفیگ نشده" (null)

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
    this.engine = null; // فوری visible
    try { current.close(); } catch (RuntimeException e) { log.warn }
}

public synchronized Optional<GameEngine> current() {
    return Optional.ofNullable(engine);
}
```

- `volatile` برای `engine` → خواندن بدون synchronized هم visibility دارد
- `synchronized` برای نوشتن → جلوگیری از race بین connect و disconnect همزمان
- `engine = null` قبل از `close()` → `current()`/`require()` هرگز engine نیمه-bسته را نمی‌بینند (اگر اول close و سپس null کنیم، بین این دو لحظه یک thread دیگر ممکن است engine نیمه-bسته را بگیرد)
- `shutdownCurrentEngine` null-safe و idempotent → اگر دو بار disconnect بیاید، NPE نمی‌دهد

### 4. BoardChannel - ReentrantLock + gatewayWriteLock + coalescing

توضیح کامل در README موتور بازی داده شد. خلاصه:

- `stateLock` (ReentrantLock) از `buffer` محافظت می‌کند
- `gatewayWriteLock` (synchronized Object) write ها روی سیم را سریالایز می‌کند
- `sendLatest()` دوباره `snapshot()` می‌خواند → coalescing semantics: آخرین وضعیت سازگار ارسال می‌شود، نه وضعیت قدیمی

### 5. GameState - synchronized HashMap

```java
public final class GameState {
    private final Map<String, Object> store = new HashMap<>();
    public synchronized <T> void put(String key, T value) { store.put(key, value); }
    public synchronized <T> Optional<T> get(String key, Class<T> type) { return Optional.of(type.cast(store.get(key))); }
}
```

- `HashMap` معمولی با `synchronized` روی متدها → thread-safe برای دسترسی همزمان tick thread و callback thread
- `snapshot()` یک کپی unmodifiable برمی‌گرداند برای SSE

### 6. AnimationSystem - generation + CompletableFuture + SingleThreadExecutor

توضیح کامل در README موتور بازی.

### 7. ScoreSystem - ConcurrentHashMap + AtomicInteger

```java
private final Map<String, AtomicInteger> scores = new ConcurrentHashMap<>();
public int add(String playerId, int delta) { return getOrCreate(playerId).addAndGet(delta); }
private AtomicInteger getOrCreate(String playerId) { return scores.computeIfAbsent(playerId, k -> new AtomicInteger(0)); }
```

- `ConcurrentHashMap` برای خواندن/نوشتن همزمان thread-safe
- `computeIfAbsent` اتمی
- `AtomicInteger.addAndGet` با CAS، بدون lock سراسری

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

- `volatile` برای visibility بدون lock
- `getAndSet(null)` تضمین می‌کند callback فقط یک بار اجرا شود

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

- `HIGHEST_PRECEDENCE` → CORS قبل از هر فیلتر دیگری چک می‌شود
- `*` برای origins, methods, headers → برای development آسان، در production باید محدود شود

---

## تست‌ها و اجرا

### تست

```bash
mvn test -pl tileboard-app
```

- `TileboardApplicationTests`: contextLoads
- `TileboardPropertiesTest`: تست defaults و validation
- `ControllerUnitTest`: تست unit کنترلرها با MockMvc
- `InMemoryDeviceGeneralConfigurationServiceTest`: تست AtomicReference
- `DefaultSerialConnectionManagerTest`: تست connect/disconnect, rollback, dual topology

### اجرا

```bash
mvn spring-boot:run -pl tileboard-app
# یا
mvn clean package -DskipTests
java -jar tileboard-app/target/tileboard-app-1.0.0.jar

# با prod profile
java -jar tileboard-app/target/tileboard-app-1.0.0.jar --spring.profiles.active=prod

# با پورت سفارشی
java -jar tileboard-app/target/tileboard-app-1.0.0.jar --server.port=9090
```

### Docker (اختیاری)

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

## API Reference کامل

### Device

| Method | Path | Body | Response |
|--------|------|------|----------|
| POST | /api/v1/devices/configure | {width, height} | DeviceConfigurationResponse |
| GET | /api/v1/devices/configuration | - | DeviceConfigurationResponse یا 404 |

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

## جمع‌بندی

این اپلیکیشن:

1. **سخت‌افزار را انتزاع می‌کند:** فقط `SerialPortRegistry` interface را می‌شناسد، نه jSerialComm را
2. **Thread-safe است:** از `AtomicReference`, `synchronized`, `ConcurrentHashMap`, `volatile`, `CAS` به درستی استفاده می‌کند
3. **قابل توسعه است:** اضافه کردن بازی جدید فقط یک `@Bean` است
4. **Production-ready است:** TTL برای session ها، rollback برای connect، idempotent disconnect، CORS، Actuator، Swagger، logging قابل تنظیم
5. **آموزشی است:** بازی نمونه `SequentialTouchGame` تمام انیمیشن‌ها و الگوهای concurrency را نشان می‌دهد

برای سوالات بیشتر، README های ماژول‌های `tileboard-serial-protocol` و `tileboard-game-engine` را ببینید.

---

**نویسنده:** تیم Tileboard Platform  
**نسخه:** 1.0.0  
**جاوا:** 17+  
**Spring Boot:** 3.3.4
