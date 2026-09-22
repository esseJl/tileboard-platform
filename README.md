# Tileboard Platform - مستندات جامع پلتفرم

> **پلتفرم Tileboard** یک سیستم کامل برای کنترل برد LED تایل (m x n) از طریق سریال و اجرای بازی‌های تعاملی روی آن است. شامل سه ماژول Maven: پروتکل سریال، موتور بازی و اپلیکیشن Spring Boot بک‌اند.

---

## فهرست مطالب
1. [معرفی پلتفرم](#معرفی-پلتفرم)
2. [معماری کلی](#معماری-کلی)
3. [ماژول‌ها](#ماژولها)
4. [پیش‌نیازها](#پیشنیازها)
5. [شروع سریع](#شروع-سریع)
6. [جریان کاری معمول](#جریان-کاری-معمول)
7. [مثال عملی - بازی SequentialTouchGame](#مثال-عملی)
8. [انیمیشن‌ها](#انیمیشنها)
9. [Concurrency و Thread-Safety در کل پلتفرم](#concurrency)
10. [تست و بیلد](#تست-و-بیلد)
11. [ساختار ریپازیتوری](#ساختار-ریپازیتوری)
12. [نقشه راه توسعه](#نقشه-راه)

---

## معرفی پلتفرم

Tileboard Platform برای حل این مسائل ساخته شده:

- **ارتباط با سخت‌افزار LED Tile Board** از طریق پورت سریال (115200 baud) با پروتکل فریم‌بندی مقاوم به نویز
- **انتزاع سخت‌افزار:** کد بازی نباید بداند از jSerialComm، RXTX یا Mock استفاده می‌شود
- **موتور بازی production-ready:** امتیاز، جان، لول، کمبو، تایمر، تاریخچه لمس، همسایه‌یابی، الگو، موج، انیمیشن، SSE
- **بک‌اند Spring Boot:** REST API برای کانفیگ، مدیریت پورت، کنترل بازی، استریم real-time
- **قابل توسعه:** اضافه کردن بازی جدید فقط یک `@Bean` است، بدون تغییر موتور یا پروتکل

### ویژگی‌های کلیدی

- ✅ **Transport-agnostic:** پروتکل هیچ وابستگی به کتابخانه سریال ندارد (jSerialComm optional)
- ✅ **Framework-free core:** موتور بازی بدون Spring هم کار می‌کند، لایه Spring optional است
- ✅ **Thread-safe:** تمام بخش‌های حساس با `ConcurrentHashMap`, `AtomicReference`, `ReentrantLock`, `synchronized`, `CAS` محافظت شده‌اند
- ✅ **انیمیشن‌های داخلی:** `countdown`, `win` (4 نوع), `lose` (4 نوع), `standby` (4 نوع)
- ✅ **Event-driven:** EventBus با دو سیاست `BLOCK` و `DROP_OLDEST`, SSE برای فرانت‌اند
- ✅ **Production-ready:** TTL برای session ها، rollback برای connect، idempotent disconnect، health check، Swagger
- ✅ **تست‌پذیر:** Mock Transport، تست‌های concurrency، تست‌های unit برای تمام feature ها

---

## معماری کلی

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

**جریان داده:**

1. `TileGatewayClient` بایت‌ها را از `SerialTransport` می‌خواند
2. `DefaultFrameCodec.decode` بایت‌ها را به `Frame` تبدیل می‌کند (stateful, resync)
3. `EngineFrameRouter` فریم را به `Board<Boolean>` (لمس‌ها) تبدیل می‌کند
4. `TouchFrameRouter` برد لمس را به `TileEvent` تبدیل و به `GameSessionImpl` درست route می‌کند
5. `GameSessionImpl.handleTileEvent` تاریخچه و سرعت واکنش را record می‌کند و `game.onTileEvent` را صدا می‌زند
6. بازی `ctx.setTile` / `publishBoard` را صدا می‌زند → `BoardChannel` → `TileGatewayClient.sendBoard` → `DefaultFrameCodec.encode` → `SerialTransport.write` → سخت‌افزار
7. همزمان `eventBus.publish(BOARD_UPDATED)` → `SseGameEventPublisher` → `SseEmitter` → فرانت‌اند

---

## ماژول‌ها

### 1. tileboard-serial-protocol

**مسئولیت:** کتابخانه خالص پروتکل سریال، بدون وابستگی به فریم‌ورک

**کلاس‌های کلیدی:**
- `Board<T>`: گرید generic mutable
- `TileCodec<T>`: پل بین دامنه و سیم
- `ProtocolConstants`, `Frame`, `Command`, `CommandType`: فریم‌بندی
- `DefaultFrameCodec`: encode stateless, decode stateful با resynchronization
- `SerialTransport`, `SerialPortRegistry`: انتزاع سخت‌افزار
- `JSerialCommTransport`, `JSerialCommPortRegistry`: پیاده‌سازی آماده (optional)
- `TileGatewayClient`: کلاینت سطح بالا (thread-safe, COWAL, writeLock, callbackExecutor)
- `HandshakeCoordinator`, `DeviceAddress`: هندشیک آدرس‌دهی

**مستندات کامل:** [tileboard-serial-protocol/README.md](tileboard-serial-protocol/README.md)

### 2. tileboard-game-engine

**مسئولیت:** موتور بازی production-ready، framework-free، با امکانات غنی

**کلاس‌های کلیدی:**
- `Game`, `GameDescriptor`, `GameContext`, `GameState`: قرارداد بازی
- `GameEngine`, `GameEngineImpl`, `GameSession`, `GameSessionImpl`: چرخه حیات
- `BoardChannel`: انتشار برد با coalescing semantics
- `FeatureBundle`: تمام feature ها (Score, Health, Level, Combo, Timer, TouchHistory, NeighborFinder, WaveGenerator, AnimationSystem, ...)
- `AnimationSystem`: انیمیشن‌های win/lose/standby/countdown با generation-based cancellation
- `GameEventBusImpl`: EventBus thread-safe با دو سیاست BLOCK و DROP_OLDEST
- `TileboardEngineAutoConfiguration`, `GameEngineManager`: لایه Spring

**مستندات کامل:** [tileboard-game-engine/README.md](tileboard-game-engine/README.md)

### 3. tileboard-app

**مسئولیت:** اپلیکیشن Spring Boot بک‌اند که سخت‌افزار را به وب وصل می‌کند

**کلاس‌های کلیدی:**
- `TileboardApplication`: main
- `TileboardProperties`, `DeviceConfiguration`, `SerialGatewayConfig`: کانفیگ
- `DeviceController`, `SerialPortController`, `GameController`, `StreamController`: REST API
- `DeviceConfigurationService`, `SerialConnectionManager`, `BoardStateBroadcaster`: سرویس‌ها
- `GameBeansConfig`, `SequentialTouchGame`: بازی نمونه آموزشی
- `GlobalExceptionHandler`: مدیریت خطا

**مستندات کامل:** [tileboard-app/README.md](tileboard-app/README.md)

---

## پیش‌نیازها

- **Java 17+** (پروژه با `maven.compiler.source=17` بیلد می‌شود)
- **Maven 3.8+**
- **برد Tileboard** متصل به USB (یا بدون سخت‌افزار با Mock برای تست)
- **Git**

---

## شروع سریع

### 1. کلون و بیلد

```bash
git clone <repo-url>
cd tileboard-platform
mvn clean install -DskipTests
```

### 2. اجرای بک‌اند

```bash
cd tileboard-app
mvn spring-boot:run
# یا
java -jar target/tileboard-app-1.0.0.jar
# با prod profile:
java -jar target/tileboard-app-1.0.0.jar --spring.profiles.active=prod
```

اپلیکیشن روی `http://localhost:8080` بالا می‌آید.

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- Health: `http://localhost:8080/actuator/health`

### 3. تست سریع بدون سخت‌افزار (Mock)

اگر برد نداری، می‌توانی یک Mock Transport بسازی و بدون سریال تست کنی. برای تست‌های unit نیازی به سخت‌افزار نیست:

```bash
mvn test
```

تمام تست‌ها بدون سخت‌افزار پاس می‌شوند. فقط `TileboardHardwareIT` نیاز به سخت‌افزار دارد و با پروفایل `hardware-tests` اجرا می‌شود:

```bash
mvn verify -P hardware-tests -pl tileboard-serial-protocol
```

---

## جریان کاری معمول

### مرحله 1: کانفیگ دستگاه

```bash
curl -X POST http://localhost:8080/api/v1/devices/configure \
  -H "Content-Type: application/json" \
  -d '{"width":8,"height":8}'
```

### مرحله 2: لیست و assign پورت‌ها

```bash
curl http://localhost:8080/api/v1/ports

curl -X POST http://localhost:8080/api/v1/ports/assign \
  -H "Content-Type: application/json" \
  -d '{"role":"OUT","portName":"COM3"}'

curl -X POST http://localhost:8080/api/v1/ports/assign \
  -H "Content-Type: application/json" \
  -d '{"role":"IN","portName":"COM3"}'
```

### مرحله 3: Connect

```bash
curl -X POST http://localhost:8080/api/v1/ports/connect
```

لاگ‌ها:

```
Enabling id handshake for a 8x8 board (minimumSequence=2)
Tile board gateway connected (in=COM3, out=COM3)
Game engine bound to the newly connected tile gateway (8x8)
```

### مرحله 4: لیست بازی‌ها

```bash
curl http://localhost:8080/api/v1/games
```

پاسخ شامل `sequential-touch` (بازی نمونه) است.

### مرحله 5: شروع بازی

```bash
curl -X POST http://localhost:8080/api/v1/games/sessions \
  -H "Content-Type: application/json" \
  -d '{"gameId":"sequential-touch","players":[{"name":"Ali"}]}'
```

### مرحله 6: SSE برای دیدن رویدادها

```bash
curl -N -H "Accept: text/event-stream" http://localhost:8080/api/v1/games/events
```

یا در JS:

```javascript
const es = new EventSource('/api/v1/games/events');
es.addEventListener('BOARD_UPDATED', e => console.log(JSON.parse(e.data)));
es.addEventListener('SESSION_FINISHED', e => { console.log('Finished', JSON.parse(e.data)); es.close(); });
```

---

## مثال عملی

### بازی SequentialTouchGame - خلاصه

این بازی تمام نیازمندی‌های شما را پیاده می‌کند:

- **به ترتیب هر تایل با رنگی روشن شود:** لیست موقعیت‌ها row-major ساخته می‌شود، هر تایل با رنگی از پالت روشن می‌شود
- **به محض لمس شدن با اضافه شدن امتیاز:** `ctx.scores().add(playerId, 10)` و `ctx.setTile(..., OFF)` و نوبت تایل بعدی
- **تا وقتی همه تایل‌ها روشن و تاچ شوند:** ایندکس تا `positions.size()` پیش می‌رود
- **سپس بازی خاتمه یابد:** `ctx.winSession(players)` بعد از انیمیشن win
- **انیمیشن‌های lose, win, standby:** 
  - `standby` (BREATHING) 2 ثانیه قبل از شروع
  - `countdown` (3→2→1) قبل از شروع
  - `lose` (FADE_TO_RED) برای لمس اشتباه، `DESCENDING_CURTAIN` برای timeout
  - `win` (RADIAL_BURST) برای برد
- **قبل از شروع بازی انیمیشن countDown**

**کد کامل:** `tileboard-app/src/main/java/com/tileboard/app/game/SequentialTouchGame.java`

**ثبت به عنوان Bean:** `tileboard-app/src/main/java/com/tileboard/app/game/GameBeansConfig.java`

**مستندات گام به گام کامل:** بخش "آموزش جامع ساخت بازی" در [tileboard-app/README.md](tileboard-app/README.md)

### جریان بازی از دید بازیکن

1. **Standby (BREATHING):** گوشه‌های برد آبی چشمک می‌زنند (2 ثانیه)
2. **Countdown:** کل برد قرمز (3) → زرد (2) → سبز (1) → چشمک سبز 3 بار (GO!)
3. **بازی:** تایل (0,0) قرمز روشن → بازیکن لمس می‌کند → +10 امتیاز → (0,0) خاموش → (0,1) سبز روشن → ...
4. **لمس اشتباه:** FADE_TO_RED کوتاه → دوباره تایل درست روشن
5. **Timeout 90 ثانیه:** DESCENDING_CURTAIN → باخت
6. **همه 64 تایل درست:** RADIAL_BURST → برد

---

## انیمیشن‌ها

### انواع

| دسته | انواع | توضیح |
|------|-------|-------|
| **Countdown** | `playCountdown()` | 3→2→1 با رندر رقم یا رنگ کامل برد + چشمک سبز |
| **Win** | `RADIAL_BURST`, `RAINBOW_SWEEP`, `SPARKLE`, `FIREWORKS` | انیمیشن برد |
| **Lose** | `FADE_TO_RED`, `DESCENDING_CURTAIN`, `CRUMBLE`, `PULSE_RED` | انیمیشن باخت |
| **Standby** | `BREATHING`, `CORNER_PULSE`, `WAVE_BORDER`, `RANDOM_TWINKLE` | حالت انتظار بی‌نهایت تا cancel |

### استفاده

```java
// Countdown قبل از شروع
ctx.animations().playCountdown(700).join();

// Win
ctx.animations().playWinAnimation(AnimationSystem.WinAnimationType.RADIAL_BURST)
    .thenRun(() -> ctx.winSession(ctx.players()));

// Lose
ctx.animations().playLoseAnimation(AnimationSystem.LoseAnimationType.FADE_TO_RED)
    .thenRun(() -> lightCurrentTile(ctx));

// Standby 2 ثانیه
try {
    ctx.animations().playStandbyAnimation(AnimationSystem.StandbyAnimationType.BREATHING)
        .get(2, TimeUnit.SECONDS);
} catch (TimeoutException e) {
    ctx.animations().cancelCurrent();
}
```

**پیاده‌سازی فنی:** تمام انیمیشن‌ها روی یک `SingleThreadExecutor` اجرا می‌شوند، با `AtomicLong generation` برای cooperative cancellation. هر انیمیشن جدید انیمیشن قبلی را کنسل می‌کند. `CompletableFuture` برمی‌گرداند که می‌توان chain کرد.

**مستندات کامل:** بخش AnimationSystem در [tileboard-game-engine/README.md](tileboard-game-engine/README.md) و بخش انیمیشن‌ها در [tileboard-app/README.md](tileboard-app/README.md)

---

## Concurrency

این پلتفرم به شدت concurrent است و تمام بخش‌های حساس thread-safe هستند:

| بخش | تکنیک | توضیح |
|-----|--------|-------|
| `DefaultFrameCodec.decode` | `synchronized` + `ByteArrayOutputStream` | buffer stateful، resync logic |
| `TileGatewayClient` | `CopyOnWriteArrayList` + `writeLock` + `callbackExecutor` | listener ها COWAL، write ها synchronized، callback روی daemon thread |
| `JSerialCommTransport.setDataListener` | `synchronized` | جلوگیری از leak listener |
| `GameEngineImpl` | `ConcurrentHashMap` + `AtomicReference` + `CAS` + `ScheduledExecutor` | exclusiveSessionId با compareAndSet، reaper با TTL |
| `SessionLifecycle` | `AtomicReference` + CAS loop | finish دقیقا یک بار |
| `BoardChannel` | `ReentrantLock` + `gatewayWriteLock` + coalescing | stateLock برای buffer، gatewayWriteLock برای سیم، snapshot دوباره برای coalescing |
| `GameState` | `synchronized` methods + `HashMap` | کیف thread-safe |
| `ScoreSystem` | `ConcurrentHashMap` + `AtomicInteger` | add با CAS |
| `GameTimer` | `volatile` + `AtomicReference` | visibility بدون lock، callback فقط یک بار |
| `AnimationSystem` | `AtomicLong generation` + `SingleThreadExecutor` + `CompletableFuture` | cooperative cancellation |
| `GameEventBusImpl` | `CopyOnWriteArrayList` + `ArrayDeque` + `ReentrantLock` + `Semaphore` + `AtomicLong` | دو سیاست BLOCK و DROP_OLDEST، Deque برای drop قدیمی‌ترین |
| `DefaultSerialConnectionManager` | `synchronized` + `EnumMap` + rollback | assign/connect/disconnect synchronized، rollback برای leak |
| `InMemoryDeviceConfigurationService` | `AtomicReference` | thread-safe بدون synchronized |
| `GameEngineManager` | `volatile` + `synchronized` + null-before-close | engine volatile، null قبل از close |

**مستندات کامل برای هر کدام:** در README هر ماژول بخش "بررسی کدهای پیچیده" را ببینید.

---

## تست و بیلد

### بیلد کل پلتفرم

```bash
mvn clean install -DskipTests
```

### تست

```bash
mvn test
# یا برای یک ماژول:
mvn test -pl tileboard-serial-protocol
mvn test -pl tileboard-game-engine
mvn test -pl tileboard-app
```

### تست با سخت‌افزار

```bash
mvn verify -P hardware-tests -pl tileboard-serial-protocol
```

### اجرای بک‌اند

```bash
cd tileboard-app
mvn spring-boot:run
```

### پکیج

```bash
mvn clean package -DskipTests
java -jar tileboard-app/target/tileboard-app-1.0.0.jar
```

---

## ساختار ریپازیتوری

```
tileboard-platform/
├── pom.xml (parent, packaging pom, modules: serial-protocol, game-engine, app)
├── README.md (این فایل)
├── .gitignore
├── tileboard-serial-protocol/
│   ├── pom.xml
│   ├── README.md (مستندات جامع پروتکل)
│   └── src/main/java/com/tileboard/serial/
│       ├── board/ (Board, Position, TileCodec)
│       ├── protocol/ (Frame, DefaultFrameCodec, ProtocolConstants)
│       ├── transport/ (SerialTransport, SerialPortRegistry)
│       ├── transport/jserialcomm/ (JSerialComm impl)
│       ├── gateway/ (TileGatewayClient)
│       └── gateway/handshake/ (HandshakeCoordinator)
├── tileboard-game-engine/
│   ├── pom.xml
│   ├── README.md (مستندات جامع موتور)
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
    ├── README.md (مستندات جامع اپ + آموزش بازی)
    └── src/main/java/com/tileboard/app/
        ├── TileboardApplication.java
        ├── config/ (TileboardProperties, DeviceConfiguration)
        ├── controller/ (Device, SerialPort, Game, Stream)
        ├── dto/ (Request/Response DTOs)
        ├── service/ (DeviceConfig, SerialConnection, Streaming)
        ├── exception/ (ApiException, GlobalExceptionHandler)
        └── game/ (SequentialTouchGame, GameBeansConfig) ← جدید
```

---

## نقشه راه

- [x] پروتکل سریال transport-agnostic
- [x] موتور بازی با feature های غنی
- [x] بک‌اند Spring Boot با REST و SSE
- [x] انیمیشن‌های win/lose/standby/countdown
- [x] بازی نمونه SequentialTouchGame
- [ ] فرانت‌اند وب (React/Vue) برای نمایش برد و کنترل بازی
- [ ] Persistence برای DeviceConfiguration (DB)
- [ ] احراز هویت و امنیت (Spring Security)
- [ ] بازی‌های بیشتر (Color Match, Memory, Reaction, ...)
- [ ] پشتیبانی از چند برد همزمان
- [ ] متریک‌های Micrometer + Prometheus
- [ ] Docker و Kubernetes deployment

---

## مشارکت

1. Fork کن
2. Branch جدید بساز (`git checkout -b feature/amazing-game`)
3. Commit کن (`git commit -m 'Add amazing game'`)
4. Push کن (`git push origin feature/amazing-game`)
5. Pull Request بساز

---

## لایسنس

داخلی - تیم Tileboard Platform

---

## نویسندگان

تیم Tileboard Platform

---

## سوالات متداول

**س: آیا بدون سخت‌افزار می‌توانم تست کنم؟**

ج: بله، تمام تست‌های unit بدون سخت‌افزار کار می‌کنند. برای تست integration بدون برد، می‌توانی یک Mock Transport بسازی (مثال در README پروتکل).

**س: چطور بازی جدید بسازم؟**

ج: بخش "آموزش جامع ساخت بازی" در [tileboard-app/README.md](tileboard-app/README.md) را ببین. خلاصه: یک کلاس که `Game` را implement می‌کند بساز، به عنوان `@Bean` ثبت کن، تمام.

**س: انیمیشن‌ها چطور کار می‌کنند؟**

ج: بخش AnimationSystem در [tileboard-game-engine/README.md](tileboard-game-engine/README.md) و بخش انیمیشن‌ها در [tileboard-app/README.md](tileboard-app/README.md)

**س: چطور concurrency را بفهمم؟**

ج: هر README بخش "بررسی کدهای پیچیده" دارد که تمام الگوهای concurrency را با کد و توضیحات کامل شرح می‌دهد.

---

**نسخه:** 1.0.0  
**جاوا:** 17+  
**Spring Boot:** 3.3.4  
**تاریخ:** 2026-09-22
