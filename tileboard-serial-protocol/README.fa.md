# کتابخانه‌ی Tileboard Serial Protocol

یک کتابخانه‌ی جاوای مستقل از فریم‌ورک و مستقل از هر transport خاص، برای پیاده‌سازی پروتکل سریال ارتباط با یک کنترلر ماتریس تایل (LED/لمسی) به ابعاد `m x n`. این کتابخانه فریم‌بندی (framing)، تفکیک دستورها، سریالایز/دیسریالایز صفحه‌ی تایل‌ها (board) و handshake آدرس‌دهی دستگاه را انجام می‌دهد — بدون این‌که هیچ‌چیز مربوط به یک کتابخانه‌ی سریال خاص، فریم‌ورک UI یا پالت رنگی خاص در آن hard-code شده باشد. هر نقطه‌ی توسعه یک اینترفیس تابعی (functional interface) کوچک است، بنابراین این کتابخانه هم داخل یک اپ اسپرینگ، هم یک ابزار CLI ساده و هم یک اپ دسکتاپ به یک اندازه خوب جا می‌افتد.

> **نیازمند Java 17+.** در Maven Central منتشر نشده — باید آن را لوکال بیلد و نصب کنید (بخش [نصب](#نصب)).

---

## فهرست مطالب

- [نصب](#نصب)
- [پروتکل سیمی (Wire Protocol)](#پروتکل-سیمی-wire-protocol)
- [نمای کلی پکیج‌ها](#نمای-کلی-پکیج‌ها)
- [مفاهیم اصلی](#مفاهیم-اصلی)
  - [`protocol` — فریم‌ها و دستورها](#protocol--فریم‌ها-و-دستورها)
  - [`board` — شبکه‌ی تایل‌ها](#board--شبکه‌ی-تایل‌ها)
  - [`transport` — پورت‌های سریال](#transport--پورت‌های-سریال)
  - [`gateway` — کلاینت سطح‌بالا](#gateway--کلاینت-سطح‌بالا)
  - [`gateway.handshake` — آدرس‌دهی دستگاه](#gatewayhandshake--آدرس‌دهی-دستگاه)
- [مثال کامل استفاده](#مثال-کامل-استفاده)
- [تشخیص این‌که کدام تایل لمس شده](#تشخیص-این‌که-کدام-تایل-لمس-شده)
- [Exception‌ها](#exceptionها)
- [تست‌ها](#تست‌ها)

---

## نصب

این کتابخانه در هیچ رجیستری عمومی منتشر نشده، پس ابتدا باید آن را در `.m2` لوکال خودتان نصب کنید:

```bash
cd tileboard-serial-protocol
mvn install
```

سپس در `pom.xml` پروژه‌ی خودتان به آن وابسته شوید:

```xml
<dependency>
    <groupId>com.tileboard</groupId>
    <artifactId>tileboard-serial-protocol</artifactId>
    <version>1.0.0</version>
</dependency>
```

تنها وابستگی اجباری کتابخانه `slf4j-api` است (یک facade لاگ — هر backend لاگی که خودتان استفاده می‌کنید را می‌توانید وصل کنید). `com.fazecast:jSerialComm` یک وابستگی **اختیاری** است: فقط زمانی روی classpath لازمتان است که از `JSerialCommPortRegistry`/`JSerialCommTransport` آماده استفاده کنید. اگر با روش دیگری با دستگاه صحبت می‌کنید، خودتان `SerialTransport` را پیاده‌سازی کنید و اصلاً نیازی به jSerialComm نخواهید داشت.

---

## پروتکل سیمی (Wire Protocol)

هر فریمی که در هر دو جهت با کنترلر رد و بدل می‌شود، این ساختار را دارد (نگاه کنید به `ProtocolConstants`):

```
بایت 0      : START_BYTE            (0xFC)
بایت 1      : SEPARATOR             (':')
بایت 2      : کد دستور              (نگاه کنید به Command)
بایت 3      : کد نوع دستور          (نگاه کنید به CommandType)
بایت 4      : طول payload، بایت پرارزش (big endian، ۱۶ بیتی بدون علامت)
بایت 5      : طول payload، بایت کم‌ارزش
بایت 6..n-2 : payload (۰ تا N بایت)
بایت n-1    : END_BYTE              ('#')
```

`Command` (بایت ۲) عملیات سطح‌بالا را مشخص می‌کند:

| ثابت            | کد | معنی                                                |
|------------------|----|------------------------------------------------------|
| `INTRODUCTION`   | 0  | handshake / "hello" از هر دو طرف                     |
| `DATA_IN`        | 1  | وضعیت تایل‌ها که **از سمت** کنترلر گزارش می‌شود (ورودی) |
| `DATA_OUT`       | 2  | وضعیت تایل‌ها که **به** کنترلر فرستاده می‌شود (خروجی)  |
| `ID`             | 3  | handshake آدرس‌دهی شناسه‌ی تایل‌ها                    |
| `STOP`           | 4  | توقف برنامه/بازی جاری                                |
| `START`          | 5  | شروع یک برنامه/بازی                                   |
| `COMMAND`        | 6  | دستور عمومی                                          |
| `RESET_PROGRAM`  | 7  | ریست برنامه‌ی در حال اجرا                             |
| `CLEAR_ID`       | 8  | پاک کردن شناسه‌های اختصاص‌داده‌شده به تایل‌ها          |

`CommandType` (بایت ۳) نوع دستور را مشخص می‌کند:

| ثابت           | کد | معنی                              |
|-----------------|----|-------------------------------------|
| `SET`           | 0  | تنظیم یک مقدار                     |
| `GET`           | 1  | درخواست یک مقدار                   |
| `CLEAR`         | 2  | پاک کردن مقدار / درخواست ریست       |
| `SET_EXTENDED`  | 3  | عملیات تنظیم گسترده/بزرگ            |
| `NA`            | 4  | قابل‌اعمال نیست                     |
| `WAIT`          | 5  | کنترلر درخواست انتظار داده          |

هر enum مقدار سیمی خودش را **صریحاً** نگه می‌دارد و به `ordinal()` متکی نیست — یعنی جابه‌جایی ترتیب یا افزودن یک ثابت جدید در آینده هیچ‌وقت به‌طور خاموش پروتکل را خراب نمی‌کند.

برای فریم‌های `DATA_IN`/`DATA_OUT`، payload یک آرایه‌ی صاف و row-major از تایل‌هاست: یک بایت به‌ازای هر تایل، مجموعاً `width * height` بایت، از چپ به راست و از بالا به پایین. کلاس `Board` (در ادامه) دقیقاً همین آرایه‌ی صاف را به مختصات دوبعدی و برعکس تبدیل می‌کند.

---

## نمای کلی پکیج‌ها

| پکیج                               | مسئولیت                                                                        |
|--------------------------------------|----------------------------------------------------------------------------------|
| `com.tileboard.serial.protocol`      | مدل فریم، فریم‌بندی/انکود/دیکود (`Command`، `CommandType`، `Frame`، `FrameEncoder`، `FrameDecoder`، `DefaultFrameCodec`، `ProtocolConstants`) |
| `com.tileboard.serial.board`         | شبکه‌ی تایل در سطح اپلیکیشن (`Board`، `Position`، `TileCodec`، `TileEncoder`، `TileDecoder`) |
| `com.tileboard.serial.transport`     | انتزاع روی یک اتصال سریال فیزیکی (`SerialTransport`، `SerialPortRegistry`، `SerialPortConfig`، `SerialPortInfo`، `Parity`، `DataListener`) به‌همراه یک پیاده‌سازی آماده با `jserialcomm` |
| `com.tileboard.serial.gateway`       | کلاینت سطح‌بالایی که اپلیکیشن‌ها واقعاً از آن استفاده می‌کنند (`TileGatewayClient`، `FrameListener`، `BoardListener`، `BoardFrameListener`) |
| `com.tileboard.serial.gateway.handshake` | handshake آدرس‌دهی شناسه‌ی تایل‌ها (`AddressResolver`، `DeviceAddress`، `HandshakeCoordinator`، `SequenceValidator`، `SequentialIdSequenceValidator`) |
| `com.tileboard.serial.exception`     | سلسله‌مراتب exception‌های کتابخانه |

---

## مفاهیم اصلی

### `protocol` — فریم‌ها و دستورها

- **`Frame`** — یک سه‌تایی غیرقابل‌تغییر و از‌پیش‌اعتبارسنجی‌شده‌ی `(Command, CommandType, payload)`. این همان واحدی است که همه‌چیز بالاتر از codec با آن کار می‌کند؛ هیچ‌کدام از لایه‌های بالاتر از `DefaultFrameCodec` مستقیماً با بایت خام سروکار ندارند.
- **`FrameEncoder`** *(اینترفیس تابعی)* — `byte[] encode(Frame frame)`.
- **`FrameDecoder`** *(اینترفیس تابعی)* — `List<Frame> decode(byte[] chunk)`. Stateful است: پیاده‌سازی‌ها بایت‌ها را بین فراخوانی‌ها بافر می‌کنند، چون یک خواندن سریال ممکن است نصف یک فریم، چند فریم، یا دنباله‌ی یک فریم شروع‌شده‌ی قبلی را داشته باشد.
- **`DefaultFrameCodec`** — پیاده‌سازی مرجع هر دوی این‌ها، دقیقاً مطابق ساختار بالا. اگر بایت‌ها فریم معتبری نسازند، خودش روی بعدی `START_BYTE` دوباره sync می‌شود (یک `0xFC` پرت وسط نویز آن را قفل نمی‌کند)، و در خواندن‌های تکه‌تکه‌شده هم درست بافر می‌کند. برای هر جریان ورودی فیزیکی، یک نمونه.
- **`ProtocolConstants`** — مقادیر ثابت سیمی (`START_BYTE`، `SEPARATOR_BYTE`، `END_BYTE`، سربار فریم، حداکثر طول payload).

```java
DefaultFrameCodec codec = new DefaultFrameCodec();

byte[] wire = codec.encode(Frame.of(Command.START, CommandType.SET));
// ... ارسال wire روی خط سریال ...

List<Frame> frames = codec.decode(bytesJustRead); // ممکن است خالی، ۱ یا چند فریم باشد
```

### `board` — شبکه‌ی تایل‌ها

- **`Position`** — رکورد `(row, col)` صفر-پایه.
- **`Board<T>`** — یک شبکه‌ی قابل‌تغییر `height x width` روی *هر* نوع تایل اپلیکیشنی `T` (یک enum رنگ، یک boolean، یک رکورد سفارشی — کتابخانه هیچ‌وقت مشخص نمی‌کند "تایل" چیست).
  - `get`/`set` با `(row, col)` یا `Position`
  - `fill(T tile)`، `copy()`، `forEach(TileConsumer<T>)`
  - `toWireBytes(TileCodec<T>)` — صفحه را به بایت‌های row-major مورد انتظار کنترلر تبدیل می‌کند
  - `static Board<T> fromWireBytes(byte[] flat, int width, int height, TileCodec<T> codec)` — برعکس آن؛ اگر `flat.length != width * height` باشد `BoardException` پرتاب می‌کند
  - `positionsWhere(Predicate<T> predicate)` — تمام `Position`هایی که تایل‌شان با predicate تطابق دارد، به ترتیب row-major. این دقیقاً پاسخ سوال *«کدام تایل(ها) تغییر کرده؟»* است — مثلاً `touchBoard.positionsWhere(Boolean.TRUE::equals)` برای پیدا کردن تایل‌های لمس‌شده.
- **`TileEncoder<T>`** / **`TileDecoder<T>`** *(اینترفیس‌های تابعی)* — تبدیل یک تایل به/از بایت سیمی‌اش.
- **`TileCodec<T>`** — یک encoder و decoder را جفت می‌کند. factoryهای آماده:
  - `TileCodec.identity()` — برای صفحاتی که از قبل با بایت خام کار می‌کنند.
  - `TileCodec.booleanState()` / `TileCodec.booleanState(byte off, byte on)` — قرارداد رایج «یک بایت پرچم به‌ازای هر تایل» (`0` = خاموش/لمس‌نشده، غیرصفر = روشن/لمس‌شده). این دقیقاً همان چیزی است که برای ورودی لمسی لازم دارید.

```java
enum Color { OFF, RED, GREEN }

TileCodec<Color> colorCodec = TileCodec.of(
        color -> switch (color) { case OFF -> (byte) 0; case RED -> (byte) 1; case GREEN -> (byte) 2; },
        wire -> switch (wire) { case 1 -> Color.RED; case 2 -> Color.GREEN; default -> Color.OFF; });

Board<Color> board = new Board<>(8, 8, Color.OFF);
board.set(0, 0, Color.RED);
byte[] outgoing = board.toWireBytes(colorCodec);
```

### `transport` — پورت‌های سریال

- **`SerialTransport`** — انتزاع روی یک اتصال از‌پیش‌بازشده: `write(byte[])`، `setDataListener(DataListener)`، `isOpen()`، `portName()`، `close()`. اگر از کتابخانه‌ی سختافزاری دیگری غیر از jSerialComm استفاده می‌کنید یا برای تست به یک نمونه‌ی جعلی نیاز دارید، خودتان این اینترفیس را پیاده‌سازی کنید — هیچ لایه‌ی بالاتری اهمیت نمی‌دهد کدام پیاده‌سازی استفاده شده.
- **`SerialPortRegistry`** — پورت‌ها را کشف می‌کند (`listPorts()`) و بازشان می‌کند (`open(String portName, SerialPortConfig config)`)، و در صورت لزوم `PortNotFoundException`/`SerialTransportException` پرتاب می‌کند.
- **`SerialPortConfig`** — نرخ باود، تعداد data bits، stop bits، `Parity`، تایم‌اوت خواندن/نوشتن. `SerialPortConfig.defaults()` تنظیمات `115200-8-N-1` را می‌دهد، همان چیزی که فریم‌ور کنترلر تایل انتظار دارد.
- **`JSerialCommPortRegistry`** / **`JSerialCommTransport`** — پیاده‌سازی آماده مبتنی بر [jSerialComm](https://fazecast.github.io/jSerialComm/). این تنها بخشی از کتابخانه است که تایپ‌های jSerialComm را import می‌کند.

```java
SerialPortRegistry registry = new JSerialCommPortRegistry();
registry.listPorts().forEach(p -> System.out.println(p.systemName() + " - " + p.description()));

SerialTransport port = registry.open("COM3", SerialPortConfig.defaults());
```

### `gateway` — کلاینت سطح‌بالا

- **`FrameListener`** *(اینترفیس تابعی)* — `void onFrame(Frame frame)`. برای هر فریم کاملی که دریافت شود فراخوانی می‌شود.
- **`BoardListener<T>`** *(اینترفیس تابعی)* — `void onBoard(Board<T> board)`. نسخه‌ی board-محور `FrameListener`.
- **`BoardFrameListener<T>`** — یک آداپتور `FrameListener` که فقط برای یک `Command` مشخص واکنش نشان می‌دهد، payload آن فریم را با یک `TileCodec<T>` داده‌شده به `Board<T>` دیکود می‌کند و آن را به یک `BoardListener<T>` می‌فرستد. اگر payload یک فریم با ابعاد صفحه‌ی پیکربندی‌شده تطابق نداشته باشد (مثلاً دستگاه تعداد تایل متفاوتی گزارش کرده)، یک `ProtocolException` (که `BoardException` زیرینش را wrap می‌کند) پرتاب می‌کند.
- **`TileGatewayClient`** — کلاسی که اپلیکیشن‌ها روزمره واقعاً با آن کار می‌کنند. یک transport ورودی، یک transport خروجی (می‌تواند برای یک پورت full-duplex همان شیء باشد) و یک جفت `FrameEncoder`/`FrameDecoder` (اگر خودتان ندهید، پیش‌فرض `DefaultFrameCodec` است) را نگه می‌دارد. بایت‌های ورودی را به callback روی هر تعداد listener ثبت‌شده تبدیل می‌کند و آن‌ها را روی یک `Executor` قابل‌تنظیم (پیش‌فرض یک ترد daemon اختصاصی) اجرا می‌کند، تا یک listener کند هیچ‌وقت ترد خواننده‌ی سریال را قفل نکند.

  متدهای کلیدی:
  - `builder()...build()` — به مثال کامل زیر نگاه کنید.
  - `start()` — پخش بایت‌های ورودی را شروع می‌کند.
  - `addFrameListener(FrameListener)` / `removeFrameListener(FrameListener)`
  - `addBoardListener(Command, int width, int height, TileCodec<T>, BoardListener<T>)` — سیم‌کشی یک‌خطی برای یک `BoardFrameListener`.
  - `enableIdHandshake(AddressResolver, SequenceValidator)` / `enableIdHandshake(AddressResolver, int minimumSequenceLength)` — handshake شناسه‌ی تایل را سیم‌کشی می‌کند (پایین‌تر توضیح داده شده).
  - `send(Command, CommandType[, byte[] payload])` / `sendFrame(Frame)`
  - `sendBoard(Command, CommandType, Board<T>, TileCodec<T>)` — یک صفحه را صاف می‌کند و به‌عنوان payload یک فریم می‌فرستد.
  - `close()` — `AutoCloseable` است؛ transportها و executor callback را آزاد می‌کند.

### `gateway.handshake` — آدرس‌دهی دستگاه

مدل‌سازی handshake اختصاص شناسه‌ی تایل توسط کنترلر:

- **`DeviceAddress(int totalTiles, int tilesPerRow)`** — factory با نام `forBoard(width, height)`؛ `toPayload()` همان payload دو‌بایتی مورد انتظار پروتکل را می‌دهد.
- **`AddressResolver`** *(اینترفیس تابعی)* — `DeviceAddress resolveAddress()`، معمولاً به‌شکل `() -> DeviceAddress.forBoard(width, height)`.
- **`SequenceValidator`** *(اینترفیس تابعی)* — `boolean isValid(byte[] payload)`، اعتبارسنجی اختصاص شناسه‌ای که کنترلر گزارش می‌دهد.
- **`SequentialIdSequenceValidator`** — قاعده‌ی پیش‌فرض: payload حداقل `minimumLength` بایت داشته باشد و هر بایت به‌جز آخری برابر با موقعیت یک‌پایه‌ی خودش باشد (شناسه‌ها به‌ترتیب row-major و از ۱ داده می‌شوند).
- **`HandshakeCoordinator`** — یک `FrameListener` که به `ID`/`CLEAR` با `DeviceAddress` حل‌شده پاسخ می‌دهد، و اگر یک اختصاص گزارش‌شده اعتبارسنجی نشود دوباره `ID`/`CLEAR` می‌فرستد. معمولاً خودتان مستقیم آن را نمی‌سازید — `TileGatewayClient.enableIdHandshake(...)` را صدا بزنید.

---

## مثال کامل استفاده

```java
SerialPortRegistry registry = new JSerialCommPortRegistry();
SerialTransport port = registry.open("COM3", SerialPortConfig.defaults());

int width = 8, height = 8;

TileGatewayClient client = TileGatewayClient.builder()
        .transport(port)             // همان transport برای ورودی و خروجی (full duplex)
        .build();

// پاسخ خودکار به handshake آدرس‌دهی شناسه‌ی تایل کنترلر.
client.enableIdHandshake(() -> DeviceAddress.forBoard(width, height), /* minimumSequenceLength */ 2);

// واکنش به ورودی لمسی به‌شکل مختصات Board دیکودشده، نه بایت خام.
client.addBoardListener(Command.DATA_IN, width, height, TileCodec.booleanState(), touchBoard -> {
    for (Position touched : touchBoard.positionsWhere(Boolean.TRUE::equals)) {
        System.out.println("tile touched at " + touched);
    }
});

client.start();
client.send(Command.START, CommandType.SET);

// روشن کردن یک تایل.
Board<Boolean> lit = new Board<>(width, height, false);
lit.set(0, 0, true);
client.sendBoard(Command.DATA_OUT, CommandType.SET, lit, TileCodec.booleanState());

// ... بعداً ...
client.close();
```

---

## تشخیص این‌که کدام تایل لمس شده

این رایج‌ترین نیاز یکپارچه‌سازی است، پس مسیر کامل آن را قدم‌به‌قدم توضیح می‌دهیم:

1. کنترلر یک فریم `DATA_IN` می‌فرستد که payload آن `width * height` بایت است، یک بایت به‌ازای هر تایل، `0x00` (لمس‌نشده) یا `0x01` (لمس‌شده).
2. `DefaultFrameCodec` (که به‌طور داخلی توسط `TileGatewayClient` استفاده می‌شود) آن جریان بایت خام را به یک `Frame` تبدیل می‌کند — خواندن‌های ناقص، چند فریم در یک خواندن، و دوباره‌sync‌شدن بعد از هر بایت خراب را خودش مدیریت می‌کند.
3. `BoardFrameListener`، که از طریق `addBoardListener(Command.DATA_IN, ...)` ثبت شده، فقط برای فریم‌های `DATA_IN` فعال می‌شود و payload را با `TileCodec.booleanState()` به یک `Board<Boolean>` دیکود می‌کند.
4. `Board.positionsWhere(Boolean.TRUE::equals)` لیست `Position(row, col)` هر تایل لمس‌شده را می‌دهد.

```java
client.addBoardListener(Command.DATA_IN, width, height, TileCodec.booleanState(), touchBoard -> {
    List<Position> touchedTiles = touchBoard.positionsWhere(Boolean.TRUE::equals);
    // اگر هیچ تایلی لمس نشده باشد touchedTiles خالی است، وگرنه یک عضو به‌ازای هر تایل لمس‌شده دارد
});
```

هیچ کد اپلیکیشنی مجبور نیست بایت‌های هدر را دستی پارس کند، طول payload را حساب کند یا داخل یک آرایه‌ی صاف دستی index‌گذاری کند.

---

## Exception‌ها

| Exception                    | ارث‌بری از            | زمان پرتاب                                                                    |
|-------------------------------|------------------------|---------------------------------------------------------------------------------|
| `ProtocolException`           | `RuntimeException`    | یک فریم قابل تفسیر نیست (کد `Command`/`CommandType` ناشناخته، یا mismatch اندازه‌ی payload/صفحه در `BoardFrameListener`) |
| `InvalidFrameException`       | `ProtocolException`   | یک فریم خروجی قابل انکود نیست (payload خیلی بزرگ) یا بایت‌های ورودی به‌طور جبران‌ناپذیری خراب‌اند |
| `BoardException`               | `RuntimeException`    | ابعاد صفحه نامعتبر، دسترسی خارج از محدوده، یا mismatch طول payload در `fromWireBytes` |
| `SerialTransportException`     | `RuntimeException`    | باز کردن، نوشتن، یا خواندن از یک transport سریال شکست بخورد |
| `PortNotFoundException`        | `SerialTransportException` | `SerialPortRegistry.open(...)` با نام پورت ناشناخته صدا زده شود |

اگر یک `FrameListener`/`BoardListener` استثنا پرتاب کند، `TileGatewayClient` آن را به‌ازای هر listener جداگانه می‌گیرد و لاگ می‌کند — یک listener بدرفتار هیچ‌وقت مانع اطلاع‌رسانی به بقیه نمی‌شود و ترد خواننده‌ی سریال را هم قفل نمی‌کند.

---

## تست‌ها

```bash
mvn test
```

تست‌های واحد از JUnit 5 خالص (بدون فریم‌ورک mock) با fakeهای دست‌نویس (`FakeTransport`، جمع‌کننده‌های `Consumer<Frame>` درون‌حافظه‌ای و غیره) استفاده می‌کنند، هم‌راستا با سبکی که در `BoardTest`، `DefaultFrameCodecTest` و `HandshakeCoordinatorTest` از قبل استفاده شده.

یک مجموعه تست hardware-in-the-loop (`TileboardHardwareIT`) هر دستور را روی یک کنترلر فیزیکی واقعاً متصل امتحان می‌کند. این مجموعه با تگ `hardware` علامت‌گذاری شده و از اجرای پیش‌فرض `mvn test` مستثنی است؛ برای فعال‌سازی آن:

```bash
mvn test -Phardware-tests -Dtileboard.hardware.port=COM3
```
