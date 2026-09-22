# tileboard-serial-protocol - مستندات جامع ماژول پروتکل سریال

> **ماموریت ماژول:** این ماژول یک کتابخانه **transport-agnostic** و **framework-free** است که پروتکل سیم‌کشی Tileboard را پیاده‌سازی می‌کند. هیچ وابستگی به Spring، هیچ hardcode برای سایز برد، هیچ رنگ پیش‌فرض - فقط یک هسته خالص که می‌تواند در هر پروژه JVM (CLI، دسکتاپ، Spring، Android) استفاده شود.

---

## فهرست مطالب
1. [معماری کلی](#معماری-کلی)
2. [ساختار پکیج‌ها](#ساختار-پکیجها)
3. [لایه پروتکل - فریم‌بندی](#لایه-پروتکل---فریمبندی)
4. [Board - ساختار داده عمومی تایل](#board---ساختار-داده-عمومی-تایل)
5. [TileCodec - پل بین دامنه و سیم](#tilecodec---پل-بین-دامنه-و-سیم)
6. [SerialTransport - انتزاع سخت‌افزار](#serialtransport---انتزاع-سختافزار)
7. [TileGatewayClient - قلب ماژول](#tilegatewayclient---قلب-ماژول)
8. [Handshake - احراز هویت و آدرس‌دهی تایل‌ها](#handshake---احراز-هویت-و-آدرسدهی-تایلها)
9. [آموزش گام به گام استفاده](#آموزش-گام-به-گام-استفاده)
10. [بررسی کدهای پیچیده - Concurrency و Thread-Safety](#بررسی-کدهای-پیچیده---concurrency-و-thread-safety)
11. [تست‌ها](#تستها)
12. [نکات پیشرفته](#نکات-پیشرفته)

---

## معماری کلی

```
┌─────────────────────────────────────────────────────────┐
│  Application (Spring / CLI / Test)                      │
│  uses Board<T>, TileCodec<T>, Game logic                │
├─────────────────────────────────────────────────────────┤
│  TileGatewayClient (High-level client)                  │
│  ├─ FrameEncoder / FrameDecoder                         │
│  ├─ BoardFrameListener (Board<T> decoding)              │
│  └─ HandshakeCoordinator (ID / CLEAR handshake)         │
├─────────────────────────────────────────────────────────┤
│  Protocol Layer                                          │
│  ├─ DefaultFrameCodec (START/SEPARATOR/END framing)     │
│  ├─ Command, CommandType, Frame                         │
│  └─ ProtocolConstants                                   │
├─────────────────────────────────────────────────────────┤
│  Transport Abstraction                                   │
│  ├─ SerialTransport (interface)                         │
│  ├─ SerialPortRegistry (discovery)                      │
│  └─ JSerialCommTransport (optional impl)                │
└─────────────────────────────────────────────────────────┘
```

**اصل طراحی:** هر لایه فقط به لایه پایین‌تر از طریق interface وابسته است. برای مثال، `TileGatewayClient` فقط `SerialTransport` می‌شناسد، نه `JSerialCommTransport`. این یعنی می‌توانید با پیاده‌سازی `SerialTransport`، از RXTX، jSSC، یک Mock برای تست، یا حتی یک پل شبکه استفاده کنید بدون تغییر یک خط از لایه پروتکل.

---

## ساختار پکیج‌ها

| پکیج | مسئولیت | کلاس‌های کلیدی |
|------|---------|---------------|
| `com.tileboard.serial.board` | ساختار داده برد عمومی | `Board<T>`, `Position`, `TileCodec`, `TileEncoder`, `TileDecoder` |
| `com.tileboard.serial.protocol` | فریم‌بندی سیم | `ProtocolConstants`, `Frame`, `Command`, `CommandType`, `DefaultFrameCodec`, `TileTouchCodec` |
| `com.tileboard.serial.transport` | انتزاع پورت سریال | `SerialTransport`, `SerialPortRegistry`, `SerialPortConfig`, `SerialPortInfo`, `DataListener` |
| `com.tileboard.serial.transport.jserialcomm` | پیاده‌سازی jSerialComm | `JSerialCommTransport`, `JSerialCommPortRegistry` |
| `com.tileboard.serial.gateway` | کلاینت سطح بالا | `TileGatewayClient`, `FrameListener`, `BoardListener`, `BoardFrameListener` |
| `com.tileboard.serial.gateway.handshake` | هندشیک آدرس‌دهی | `HandshakeCoordinator`, `DeviceAddress`, `AddressResolver`, `SequenceValidator` |
| `com.tileboard.serial.exception` | استثناها | `ProtocolException`, `BoardException`, `SerialTransportException` |

---

## لایه پروتکل - فریم‌بندی

### فرمت فریم روی سیم

```
Byte 0: START_BYTE      = 0xFC
Byte 1: SEPARATOR_BYTE  = ':' (0x3A)
Byte 2: Command code    (e.g. DATA_OUT = 0x01)
Byte 3: CommandType code (e.g. SET = 0x01, GET = 0x02)
Byte 4: Payload length high byte (big-endian uint16)
Byte 5: Payload length low byte
Byte 6..n-2: Payload (0..N bytes)
Byte n-1: END_BYTE      = '#' (0x23)
```

- **Overhead ثابت:** 7 بایت (START + SEP + CMD + TYPE + LEN(2) + END)
- **Max payload:** 65535 بایت (فیلد 16 بیتی) اما در عمل بردها حداکثر چند صد بایت هستند (محدودیت پروتکل آدرس‌دهی: 255 تایل)

### DefaultFrameCodec - پیاده‌سازی مرجع

این کلاس هم `FrameEncoder` و هم `FrameDecoder` را پیاده می‌کند.

#### encode - stateless و خالص
```java
public byte[] encode(Frame frame) {
    byte[] payload = frame.payload();
    byte[] out = new byte[payload.length + 7];
    out[0] = START_BYTE;
    out[1] = SEPARATOR_BYTE;
    out[2] = (byte) frame.command().code();
    out[3] = (byte) frame.commandType().code();
    out[4] = (byte) (payload.length >> 8);
    out[5] = (byte) payload.length;
    System.arraycopy(payload, 0, out, 6, payload.length);
    out[out.length - 1] = END_BYTE;
    return out;
}
```

#### decode - stateful و مقاوم به نویز
`decode` باید سه حالت را همزمان مدیریت کند:
1. **فریم تکه‌تکه شده:** نیمی از فریم در یک read و نیم دیگر در read بعدی می‌آید
2. **چند فریم در یک read:** یک read ممکن است 3 فریم کامل را با هم بیاورد
3. **نویز خط:** بایت‌های تصادفی که شبیه START هستند اما فریم واقعی نیستند

برای همین `DefaultFrameCodec` یک `ByteArrayOutputStream buffer` داخلی دارد که بایت‌ها را بین فراخوانی‌ها نگه می‌دارد.

**الگوریتم resynchronization:**
```java
synchronized List<Frame> decode(byte[] chunk) {
    buffer.writeBytes(chunk);
    byte[] data = buffer.toByteArray();
    int consumedUpTo = 0;
    while (true) {
        int start = indexOfFrameStart(data, consumedUpTo); // پیدا کردن 0xFC ':'
        if (start < 0) { consumedUpTo = data.length; break; }
        if (available < 6) { consumedUpTo = start; break; } // هنوز طول payload را نداریم
        int payloadLength = ((data[start+4] & 0xFF) << 8) | (data[start+5] & 0xFF);
        if (payloadLength > 4096) { consumedUpTo = start+1; continue; } // سقف منطقی، نویز است
        int total = payloadLength + 7;
        if (available < total) { consumedUpTo = start; break; } // فریم هنوز کامل نشده
        if (data[endIndex] != END_BYTE) { consumedUpTo = start+1; continue; } // END ناهماهنگ → نویز
        try { Command, CommandType را parse کن } catch { consumedUpTo = start+1; continue; }
        // فریم معتبر → به لیست اضافه کن
        consumedUpTo = start + total;
    }
    buffer.reset(); // همیشه trim کن، حتی اگر exception شد
    if (consumedUpTo < data.length) buffer.write(remaining);
    return frames;
}
```

**نکته concurrency:** متد `decode` با `synchronized` محافظت می‌شود چون `buffer` stateful است و ممکن است از thread های مختلف (callback سریال و تست) صدا زده شود.

**سقف 4096:** `MAX_PAYLOAD_LENGTH = 65535` از نظر فنی بی‌فایده است چون فیلد طول خودش 16 بیتی است و هر مقدار 2 بایتی ≤ 65535 است. اما بردهای واقعی حداکثر 255 تایل دارند (محدودیت آدرس‌دهی یک بایتی در `DeviceAddress`). پس اگر نویز تصادفی یک START کاذب بسازد که طول 30000 را نشان دهد، دیکودر نباید برای همیشه منتظر 30000 بایت بماند و تمام فریم‌های واقعی بعدی را به عنوان بخشی از آن فریم کاذب ببلعد. سقف 4096 این مشکل را حل می‌کند.

---

## Board - ساختار داده عمومی تایل

`Board<T>` یک گرید mutable به ابعاد `height x width` است که روی `Object[][]` ذخیره می‌شود (برای جلوگیری از نیاز به Class token برای T).

```java
public final class Board<T> {
    private final int width, height;
    private final Object[][] tiles;

    public Board(int width, int height, Supplier<T> initialTileSupplier) { ... }
    public Board(int width, int height, T initialTile) { this(width, height, () -> initialTile); }

    public T get(int row, int col) { ... }
    public void set(int row, int col, T tile) { ... }
    public void fill(T tile) { Arrays.fill... }
    public void forEach(TileConsumer<T> action) { row-major iteration }
    public List<Position> positionsWhere(Predicate<T> predicate) { ... }
    public Board<T> copy() { deep copy }
    public byte[] toWireBytes(TileCodec<T> codec) { flatten row-major }
    public static <T> Board<T> fromWireBytes(byte[] flat, int w, int h, TileCodec<T> codec) { ... }
}
```

**چرا generic؟** چون کتابخانه نباید رنگ را hardcode کند. یک برنامه ممکن است تایل را به صورت `enum Color { RED, GREEN, BLUE }` ببیند، برنامه دیگر به صورت `Boolean` (لمس شده/نشده)، یا حتی یک کلاس سفارشی با شدت روشنایی. `Board<T>` این را ممکن می‌کند.

**متدهای کلیدی:**
- `positionsWhere(Boolean.TRUE::equals)` → پیدا کردن تایل‌های لمس شده از یک `Board<Boolean>`
- `toWireBytes(codec)` → تبدیل برد به آرایه بایت row-major برای ارسال روی سیم
- `fromWireBytes` → ساخت برد از payload دریافتی

**Position:** یک `record` ساده با اعتبارسنجی `row >=0 && col >=0`.

---

## TileCodec - پل بین دامنه و سیم

```java
public final class TileCodec<T> {
    private final TileEncoder<T> encoder;
    private final TileDecoder<T> decoder;
    public static <T> TileCodec<T> of(TileEncoder<T> encoder, TileDecoder<T> decoder) { ... }
    public static TileCodec<Byte> identity() { ... }
    public static TileCodec<Boolean> booleanState(byte off, byte on) { ... }
    public static TileCodec<Boolean> booleanState() { booleanState(0, 1) }
}
```

**TileEncoder / TileDecoder:** هر دو `@FunctionalInterface` هستند.

**مثال کاربرد:**
```java
// تعریف رنگ‌های خودتان
enum MyColor { OFF, RED, GREEN, BLUE }

// Codec برای تبدیل MyColor به بایت سیم
TileCodec<MyColor> myCodec = TileCodec.of(
    color -> switch(color) { case OFF -> 0; case RED -> 1; case GREEN -> 2; case BLUE -> 3; },
    wire -> switch(wire) { case 1 -> MyColor.RED; case 2 -> MyColor.GREEN; case 3 -> MyColor.BLUE; default -> MyColor.OFF; }
);

Board<MyColor> board = new Board<>(8, 8, MyColor.OFF);
board.set(0, 0, MyColor.RED);
byte[] wireBytes = board.toWireBytes(myCodec); // [1, 0, 0, 0, ...]
```

**booleanState:** برای payload های `DATA_IN` که نشان می‌دهند کدام تایل لمس شده، یک Codec آماده دارد: `0` = false، غیرصفر = true.

---

## SerialTransport - انتزاع سخت‌افزار

```java
public interface SerialTransport extends AutoCloseable {
    String portName();
    boolean isOpen();
    void write(byte[] data); // ممکن است بلاک کند تا OS driver تحویل بگیرد
    void setDataListener(DataListener listener); // فقط یک listener، fan-out به عهده caller
    void close();
}

public interface DataListener {
    void onDataReceived(byte[] data);
}

public interface SerialPortRegistry {
    List<SerialPortInfo> listPorts();
    SerialTransport open(String portName, SerialPortConfig config);
}
```

**SerialPortConfig:** Builder برای baudRate, dataBits, stopBits, parity, flowControl, timeouts.

**چرا دو interface جدا؟** `SerialPortRegistry` کل سیستم را enumerate می‌کند (نیاز به دسترسی سراسری)، در حالی که `SerialTransport` یک اتصال باز را نشان می‌دهد. این جداسازی تست‌پذیری را بالا می‌برد: می‌توانید Registry را mock کنید بدون نیاز به mock کردن Transport.

### JSerialComm پیاده‌سازی آماده

این ماژول یک پیاده‌سازی آماده با کتابخانه `com.fazecast:jSerialComm` دارد، اما وابستگی آن `optional` است:

```xml
<dependency>
    <groupId>com.fazecast</groupId>
    <artifactId>jSerialComm</artifactId>
    <optional>true</optional>
</dependency>
```

اگر شما `SerialTransport` خودتان را پیاده کنید (مثلا با RXTX یا یک Mock برای تست)، اصلا نیازی به jSerialComm روی classpath ندارید.

**JSerialCommTransport:**
- `write`: بایت‌ها را با `HexFormat` لاگ می‌کند اگر DEBUG فعال باشد، سپس `writeBytes` را صدا می‌زند و short write را چک می‌کند
- `setDataListener`: یک `SerialPortDataListener` داخلی می‌سازد که `bytesAvailable` را می‌خواند و به `DataListener` شما تحویل می‌دهد. `synchronized` است تا listener قبلی را درست حذف کند.
- `close`: listener را detach می‌کند سپس پورت را می‌بندد

**JSerialCommPortRegistry:**
- `listPorts()`: `SerialPort.getCommPorts()` را به `SerialPortInfo` تبدیل می‌کند
- `open()`: وجود پورت را چک می‌کند (PortNotFoundException)، پارامترها را set می‌کند (baud, dataBits, stopBits, parity, flowControl, timeouts)، `openPort()` را صدا می‌زند، DTR/RTS را clear می‌کند (با لاگ هشدار اگر موفق نشود)

---

## TileGatewayClient - قلب ماژول

کلاینت سطح بالا که transport، codec و listener ها را به هم وصل می‌کند.

### ویژگی‌های کلیدی

- **دو توپولوژی را پشتیبانی می‌کند:**
  - تک پورت full-duplex: یک `SerialTransport` برای هر دو جهت (با `builder.transport(shared)`)
  - دو پورت half-duplex: یک پورت IN و یک پورت OUT جدا (با `builder.inputTransport(in).outputTransport(out)`)

- **Thread-safe:**
  - `writeLock = new Object()` → `send()` و `sendBoard()` از هر thread (caller thread و callback thread بازی) ممکن است صدا زده شوند. بدون `synchronized(writeLock)`، دو write همزمان می‌توانند بایت‌هایشان را روی سیم interleave کنند و هر دو فریم را خراب کنند.
  - `listeners = new CopyOnWriteArrayList<>()` → مناسب برای سناریوی read-heavy, write-rare: اضافه/حذف listener نادر است اما iteration برای dispatch فریم‌ها مکرر است. COWAL بدون lock برای خواندن کار می‌کند.
  - `callbackExecutor`: به صورت پیش‌فرض یک daemon thread به نام `tileboard-gateway-callback`. تمام `FrameListener` ها روی این executor اجرا می‌شوند، نه روی serial reader thread. این یعنی یک listener کند یا buggy هرگز thread خواندن سریال را بلاک نمی‌کند.

- **Lifecycle:**
  ```java
  TileGatewayClient client = TileGatewayClient.builder()
      .transport(port)
      .build();
  client.addFrameListener(frame -> System.out.println(frame));
  client.enableIdHandshake(() -> DeviceAddress.forBoard(8, 8));
  client.start(); // setDataListener روی inputTransport
  // ...
  client.close(); // هر دو transport و executor را می‌بندد
  ```

- **متدهای ارسال:**
  - `sendFrame(Frame)` → encode و write
  - `send(Command, CommandType, payload)` → ساخت Frame و ارسال
  - `sendBoard(Command, CommandType, Board<T>, TileCodec<T>)` → flatten برد با codec و ارسال به عنوان payload

- **متدهای دریافت:**
  - `addFrameListener(FrameListener)` → برای هر فریم
  - `addBoardListener(Command, width, height, codec, BoardListener)` → فیلتر بر اساس Command و decode خودکار payload به Board

### Builder

```java
public static final class Builder {
    public Builder transport(SerialTransport transport) { /* هر دو جهت */ }
    public Builder inputTransport(SerialTransport in) { ... }
    public Builder outputTransport(SerialTransport out) { ... }
    public Builder frameEncoder(FrameEncoder e) { ... }
    public Builder frameDecoder(FrameDecoder d) { ... }
    public Builder callbackExecutor(Executor ex) { ... }
    public TileGatewayClient build() {
        if (encoder==null || decoder==null) default = new DefaultFrameCodec();
        if (callbackExecutor==null) newSingleThreadExecutor(daemon, "tileboard-gateway-callback")
    }
}
```

### close() - تمیزکاری مقاوم

```java
public synchronized void close() {
    try { inputTransport.setDataListener(null); inputTransport.close(); } catch { log.warn }
    try { if (output != input) output.close(); } catch { log.warn }
    try { if (ownsExecutor && executor is ExecutorService) shutdown(); } finally { started=false; }
}
```

هر مرحله حتی اگر مرحله قبلی exception داد اجرا می‌شود، وگرنه یک `transport.close()` buggy می‌توانست handle پورت دیگر و thread executor را برای همیشه leak کند.

---

## Handshake - احراز هویت و آدرس‌دهی تایل‌ها

وقتی برد روشن می‌شود، باید به هر تایل فیزیکی یک آدرس منطقی اختصاص داده شود. این کار از طریق handshake انجام می‌شود:

1. برد فریم `ID` (یا `CLEAR`) می‌فرستد
2. `HandshakeCoordinator` (که یک `FrameListener` است) آن را دریافت می‌کند
3. از `AddressResolver` می‌پرسد: "برای برد MxN، آدرس‌ها چه باید باشند؟" → `DeviceAddress.forBoard(width, height)`
4. فریم پاسخ را با `sendFrame` برمی‌گرداند
5. `SequenceValidator` (مثلا `SequentialIdSequenceValidator`) چک می‌کند که توالی گزارش شده توسط برد معتبر است (حداقل طول، ترتیب صعودی)

```java
client.enableIdHandshake(() -> DeviceAddress.forBoard(8, 8));
// معادل:
client.enableIdHandshake(addressResolver, new SequentialIdSequenceValidator(minimumSequence));
```

**DeviceAddress:** آدرس کل برد را در یک بایت کد می‌کند (محدودیت 255 تایل).

**SequentialIdSequenceValidator:** چک می‌کند که توالی ID ها حداقل `minimumSequence` طول داشته باشد و ترتیبی باشد.

---

## آموزش گام به گام استفاده

### گام 1: وابستگی Maven

```xml
<dependency>
    <groupId>com.tileboard</groupId>
    <artifactId>tileboard-serial-protocol</artifactId>
    <version>1.0.0</version>
</dependency>
<!-- اگر می‌خواهی از پیاده‌سازی آماده jSerialComm استفاده کنی: -->
<dependency>
    <groupId>com.fazecast</groupId>
    <artifactId>jSerialComm</artifactId>
    <version>2.11.0</version>
</dependency>
```

### گام 2: کشف و باز کردن پورت

```java
SerialPortRegistry registry = new JSerialCommPortRegistry();

// لیست پورت‌های موجود
List<SerialPortInfo> ports = registry.listPorts();
ports.forEach(p -> System.out.println(p.systemName() + " - " + p.description()));

// باز کردن پورت
SerialPortConfig config = SerialPortConfig.builder()
    .baudRate(115200)
    .dataBits(8)
    .stopBits(1)
    .parity(Parity.NONE)
    .readTimeoutMillis(50)
    .writeTimeoutMillis(50)
    .build();

SerialTransport transport = registry.open("COM3", config); // یا /dev/ttyUSB0
```

### گام 3: ساخت کلاینت و ثبت Listener

```java
TileGatewayClient client = TileGatewayClient.builder()
    .transport(transport)
    .build();

// Listener برای فریم‌های خام
client.addFrameListener(frame -> {
    System.out.println("Received: " + frame.command() + " " + frame.commandType() + " len=" + frame.payload().length);
});

// Listener برای برد لمس شده (DATA_IN)
TileCodec<Boolean> touchCodec = TileCodec.booleanState();
client.addBoardListener(Command.DATA_IN, 8, 8, touchCodec, touchBoard -> {
    List<Position> touched = touchBoard.positionsWhere(Boolean.TRUE::equals);
    touched.forEach(pos -> System.out.println("Touched: " + pos));
});

client.enableIdHandshake(() -> DeviceAddress.forBoard(8, 8));
client.start();
```

### گام 4: ارسال برد به سخت‌افزار

```java
// تعریف رنگ‌ها
enum TileColor { OFF(0), RED(1), GREEN(2), BLUE(3); 
    final int code; TileColor(int c){code=c;}
}

TileCodec<TileColor> colorCodec = TileCodec.of(
    color -> (byte) color.code,
    wire -> TileColor.values()[wire & 0xFF]
);

Board<TileColor> board = new Board<>(8, 8, TileColor.OFF);
board.set(0, 0, TileColor.RED);
board.set(7, 7, TileColor.BLUE);

client.sendBoard(Command.DATA_OUT, CommandType.SET, board, colorCodec);
```

### گام 5: بستن

```java
client.close(); // transport ها و thread callback را می‌بندد
```

### مثال Mock برای تست بدون سخت‌افزار

```java
// یک Transport جعلی که write را لاگ می‌کند و می‌تواند data جعلی inject کند
class MockTransport implements SerialTransport {
    DataListener listener;
    public String portName() { return "MOCK"; }
    public boolean isOpen() { return true; }
    public void write(byte[] data) { System.out.println("MOCK TX: " + HexFormat.of().formatHex(data)); }
    public void setDataListener(DataListener l) { this.listener = l; }
    public void close() {}
    public void injectRx(byte[] data) { if (listener != null) listener.onDataReceived(data); }
}

MockTransport mock = new MockTransport();
TileGatewayClient client = TileGatewayClient.builder().transport(mock).build();
client.addFrameListener(f -> System.out.println("RX frame: " + f));
client.start();

// شبیه‌سازی فریم دریافتی
DefaultFrameCodec codec = new DefaultFrameCodec();
Frame fakeFrame = Frame.of(Command.DATA_IN, CommandType.SET, new byte[]{1,0,0});
byte[] wire = codec.encode(fakeFrame);
mock.injectRx(wire);
```

---

## بررسی کدهای پیچیده - Concurrency و Thread-Safety

### 1. DefaultFrameCodec.decode - synchronized و stateful buffer

**مشکل:** سریال دیتا به صورت chunk های تصادفی می‌آید. یک فریم ممکن است بین دو chunk نصف شود. اگر `decode` همزمان از دو thread صدا زده شود، `buffer` خراب می‌شود.

**راه حل:**
```java
public synchronized List<Frame> decode(byte[] chunk) {
    buffer.writeBytes(chunk);
    // ... parsing
    finally {
        buffer.reset();
        if (consumedUpTo < data.length) buffer.write(remaining);
    }
}
```
- `synchronized` تضمین می‌کند فقط یک thread در یک لحظه buffer را تغییر دهد.
- `finally` تضمین می‌کند حتی اگر exception غیرمنتظره رخ داد، buffer trim شود وگرنه همان بایت‌های خراب برای همیشه هر فراخوانی بعدی را fail می‌کنند.

**Resynchronization logic:** وقتی START پیدا می‌شود اما END هماهنگ نیست یا payloadLength غیرمنطقی است، `consumedUpTo = start+1` و `continue` → یک بایت جلوتر دوباره دنبال START بگرد. این از گیر کردن decoder در حلقه بی‌نهایت جلوگیری می‌کند.

### 2. TileGatewayClient - writeLock و CopyOnWriteArrayList

**writeLock:**
```java
private final Object writeLock = new Object();
public void sendFrame(Frame frame) {
    byte[] wireBytes = encoder.encode(frame);
    synchronized (writeLock) {
        outputTransport.write(wireBytes);
    }
}
```
- `send()` ممکن است از thread اصلی (کاربر) و از callback thread (کد بازی از طریق GameContext.publish) همزمان صدا زده شود.
- بدون `writeLock`، دو write می‌توانند بایت‌هایشان را interleave کنند: مثلا نیمی از فریم A، سپس نیمی از فریم B → هر دو فریم روی سیم خراب.

**CopyOnWriteArrayList برای listeners:**
- سناریو: اضافه/حذف listener نادر است (در startup و shutdown)، اما iteration برای dispatch فریم‌ها بسیار مکرر است (هر بار که دیتا می‌آید).
- COWAL برای خواندن بدون lock است (iteration روی snapshot)، و فقط هنگام write کل آرایه را کپی می‌کند. این برای این الگو بهینه است.
- جایگزین `synchronizedList` برای هر dispatch نیاز به lock داشت و throughput را پایین می‌آورد.

**callbackExecutor:**
- به صورت پیش‌فرض `newSingleThreadExecutor(daemon thread)`. این تضمین می‌کند listener ها به ترتیب دریافت فریم‌ها اجرا شوند (single thread) و هرگز thread خواندن سریال (که توسط jSerialComm مدیریت می‌شود) را بلاک نکنند.
- اگر یک listener کند باشد یا exception دهد، فقط executor کند می‌شود، نه transport.

### 3. JSerialCommTransport.setDataListener - synchronized

```java
public synchronized void setDataListener(DataListener listener) {
    if (activeListener != null) { delegate.removeDataListener(); activeListener=null; }
    if (listener==null) return;
    activeListener = new SerialPortDataListener() { ... };
    delegate.addDataListener(activeListener);
}
```
- `synchronized` است تا دو فراخوانی همزمان `setDataListener` باعث نشود یک listener حذف نشود و leak کند.
- `activeListener` را نگه می‌دارد تا بتواند `removeDataListener` کند.

### 4. HandshakeCoordinator - بدون state اضافی

این کلاس stateless است و فقط به `AddressResolver` و `SequenceValidator` delegate می‌کند. چون `TileGatewayClient` تضمین می‌کند callback ها روی یک thread (callbackExecutor) اجرا شوند، نیازی به synchronized اضافی نیست.

---

## تست‌ها

```bash
mvn test -pl tileboard-serial-protocol
```

- `BoardTest`: تست Board generic، copy، positionsWhere، wireBytes
- `DefaultFrameCodecTest`: تست encode/decode، تکه‌تکه شدن فریم، نویز، resync
- `TileGatewayClientTest`: تست dispatch، listener، writeLock
- `HandshakeCoordinatorTest`: تست handshake
- `BoardFrameListenerTest`: تست فیلتر Command و decode به Board
- `TileboardHardwareIT`: تست یکپارچه با سخت‌افزار واقعی (فقط با پروفایل `hardware-tests`)

```bash
mvn verify -P hardware-tests -pl tileboard-serial-protocol
```

---

## نکات پیشرفته

### استفاده بدون jSerialComm

```java
SerialTransport myTransport = new MyCustomTransport("/dev/ttyUSB0");
TileGatewayClient client = TileGatewayClient.builder()
    .transport(myTransport)
    .frameEncoder(new MyCustomFrameEncoder()) // حتی می‌توانی codec را هم عوض کنی
    .build();
```

### توپولوژی دو پورت

برخی بردها از دو آداپتور half-duplex استفاده می‌کنند (یکی فقط TX، یکی فقط RX):

```java
SerialTransport in = registry.open("COM4", config);
SerialTransport out = registry.open("COM3", config);
TileGatewayClient client = TileGatewayClient.builder()
    .inputTransport(in)
    .outputTransport(out)
    .build();
```

### تنظیمات پیشرفته FrameCodec

`DefaultFrameCodec` یک سقف 4096 برای payload دارد. اگر برد شما payload بزرگتر دارد (مثلا 10x10=100 بایت، هنوز زیر 4096)، مشکلی نیست. اگر پروتکل شما payload های بزرگتر دارد، می‌توانید `FrameDecoder` خودتان را پیاده کنید.

---

**نویسنده:** تیم Tileboard Platform  
**نسخه:** 1.0.0  
**جاوا:** 17+  
**لایسنس:** داخلی
