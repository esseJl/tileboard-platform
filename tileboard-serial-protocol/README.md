# tileboard-serial-protocol - Comprehensive Module Documentation

> **Module Mission:** This is a **transport-agnostic** and **framework-free** Java library implementing the Tileboard serial wire protocol. It has zero dependency on Spring, no hardcoded board size, no hardcoded color palette - just a pure core that can be dropped into any JVM project (CLI, desktop, Spring, Android, etc.).

---

## Table of Contents
1. [Overall Architecture](#overall-architecture)
2. [Package Structure](#package-structure)
3. [Protocol Layer - Framing](#protocol-layer---framing)
4. [Board - Generic Tile Data Structure](#board---generic-tile-data-structure)
5. [TileCodec - Bridge Between Domain and Wire](#tilecodec---bridge-between-domain-and-wire)
6. [SerialTransport - Hardware Abstraction](#serialtransport---hardware-abstraction)
7. [TileGatewayClient - Heart of the Module](#tilegatewayclient---heart-of-the-module)
8. [Handshake - Tile Addressing and Identity](#handshake---tile-addressing-and-identity)
9. [Exceptions - Localizable Hierarchy](#exceptions---localizable-hierarchy)
10. [Step-by-Step Usage Tutorial](#step-by-step-usage-tutorial)
11. [Deep Dive - Concurrency and Thread-Safety](#deep-dive---concurrency-and-thread-safety)
12. [Tests](#tests)
13. [Advanced Topics](#advanced-topics)

---

## Overall Architecture

```
┌─────────────────────────────────────────────────────────┐
│  Application (Spring / CLI / Test)                      │
│  uses Board<T>, TileCodec<T>, Game logic                │
├─────────────────────────────────────────────────────────┤
│  TileGatewayClient (High-level client)                  │
│  ├─ FrameEncoder / FrameDecoder (default: 1 shared codec)│
│  ├─ BoardFrameListener (Board<T> decoding)              │
│  └─ HandshakeCoordinator (ID / CLEAR handshake)         │
├─────────────────────────────────────────────────────────┤
│  Protocol Layer                                          │
│  ├─ DefaultFrameCodec (START/SEPARATOR/END framing)     │
│  ├─ Command, CommandType, Frame                         │
│  ├─ ProtocolConstants, TileTouchCodec                   │
│  └─ LocalizableException hierarchy (exception package)  │
├─────────────────────────────────────────────────────────┤
│  Transport Abstraction                                   │
│  ├─ SerialTransport (interface)                         │
│  ├─ SerialPortRegistry (discovery)                      │
│  └─ JSerialCommTransport (optional impl)                │
└─────────────────────────────────────────────────────────┘
```

**Design Principle:** Each layer depends only on the layer below through an interface. For example, `TileGatewayClient` only knows `SerialTransport`, not `JSerialCommTransport`. This means you can implement `SerialTransport` with RXTX, jSSC, a Mock for tests, or even a network bridge without changing a single line in the protocol layer.

---

## Package Structure

| Package | Responsibility | Key Classes |
|------|---------|---------------|
| `com.tileboard.serial.board` | Generic board data structure | `Board`, `Position`, `TileCodec`, `TileEncoder`, `TileDecoder` |
| `com.tileboard.serial.protocol` | Wire framing | `ProtocolConstants`, `Frame`, `Command`, `CommandType`, `FrameEncoder`, `FrameDecoder`, `DefaultFrameCodec`, `TileTouchCodec` |
| `com.tileboard.serial.transport` | Serial port abstraction | `SerialTransport`, `SerialPortRegistry`, `SerialPortConfig`, `SerialPortInfo`, `Parity`, `FlowControl`, `DataListener` |
| `com.tileboard.serial.transport.jserialcomm` | jSerialComm implementation | `JSerialCommTransport`, `JSerialCommPortRegistry` |
| `com.tileboard.serial.gateway` | High-level client | `TileGatewayClient`, `FrameListener`, `BoardListener`, `BoardFrameListener` |
| `com.tileboard.serial.gateway.handshake` | Addressing handshake | `HandshakeCoordinator`, `DeviceAddress`, `AddressResolver`, `SequenceValidator`, `SequentialIdSequenceValidator` |
| `com.tileboard.serial.exception` | Exceptions | `ProtocolException`, `InvalidFrameException`, `BoardException`, `SerialTransportException`, `PortNotFoundException` |
| `com.tileboard.serial.support.error` | Shared error base | `LocalizableException` |

---

## Protocol Layer - Framing

### Wire Frame Format

```
Byte 0: START_BYTE      = 0xFC
Byte 1: SEPARATOR_BYTE  = ':' (0x3A)
Byte 2: Command code    (see Command table below)
Byte 3: CommandType code (see CommandType table below)
Byte 4: Payload length high byte (big-endian uint16)
Byte 5: Payload length low byte
Byte 6..n-2: Payload (0..N bytes)
Byte n-1: END_BYTE      = '#' (0x23)
```

- **Fixed overhead:** 7 bytes (`FRAME_OVERHEAD_BYTES`: START + SEP + CMD + TYPE + LEN(2) + END)
- **Max payload:** 65535 bytes (`MAX_PAYLOAD_LENGTH = 0xFFFF`, the 16-bit field maximum). In practice boards are tiny (protocol addressing limit: 255 tiles, i.e. at most 255 payload bytes for board frames).

### Command and CommandType codes

`Command` (byte 2):

| Constant | Code |
|------|------|
| `INTRODUCTION` | 0 |
| `DATA_IN` | 1 |
| `DATA_OUT` | 2 |
| `ID` | 3 |
| `STOP` | 4 |
| `START` | 5 |
| `COMMAND` | 6 |
| `RESET_PROGRAM` | 7 |
| `CLEAR_ID` | 8 |

`CommandType` (byte 3):

| Constant | Code |
|------|------|
| `SET` | 0 |
| `GET` | 1 |
| `CLEAR` | 2 |
| `SET_EXTENDED` | 3 |
| `NA` | 4 |
| `WAIT` | 5 |

Each constant carries an explicit `code()` (never `ordinal()`), and `fromCode(int)` resolves a wire byte or throws `ProtocolException` (`protocol.unknown_command` / `protocol.unknown_command_type`).

### DefaultFrameCodec - Reference Implementation

This class implements both `FrameEncoder` and `FrameDecoder`.

#### encode - stateless and pure (with a size guard)
```java
public byte[] encode(Frame frame) {
    byte[] payload = frame.payload();
    if (payload.length > MAX_PAYLOAD_LENGTH) {
        throw new InvalidFrameException("protocol.payload_too_large", ...);
    }
    byte[] out = new byte[payload.length + FRAME_OVERHEAD_BYTES];
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

#### decode - stateful and noise-resistant
`decode` must handle three cases simultaneously:
1. **Fragmented frame:** Half of a frame arrives in one read, the other half in the next read
2. **Multiple frames in one read:** One read may contain 3 complete frames together
3. **Line noise:** Random bytes that look like START but are not real frames

That's why `DefaultFrameCodec` has an internal `ByteArrayOutputStream buffer` that retains bytes between calls. One instance must be dedicated to a single logical input stream (one per `SerialTransport` being read from).

**Resynchronization algorithm:**
```java
public synchronized List<Frame> decode(byte[] chunk) {
    buffer.writeBytes(chunk);
    byte[] data = buffer.toByteArray();
    int consumedUpTo = 0;
    try {
        while (true) {
            int start = indexOfFrameStart(data, consumedUpTo); // find 0xFC ':'
            if (start < 0) { consumedUpTo = data.length; break; }
            if (available < 6) { consumedUpTo = start; break; } // don't have length yet
            int payloadLength = ((data[start+4] & 0xFF) << 8) | (data[start+5] & 0xFF);
            if (payloadLength > 4096) { consumedUpTo = start+1; continue; } // plausible ceiling, noise
            int total = payloadLength + FRAME_OVERHEAD_BYTES;
            if (available < total) { consumedUpTo = start; break; } // frame not complete yet
            if (data[endIndex] != END_BYTE) { consumedUpTo = start+1; continue; } // END mismatch -> noise
            try { parse Command, CommandType } catch { consumedUpTo = start+1; continue; }
            // valid frame -> add to list
            consumedUpTo = start + total;
        }
    } finally {
        buffer.reset(); // always trim, even if exception
        if (consumedUpTo < data.length) buffer.write(remaining);
    }
    return frames;
}
```

**Concurrency note:** `decode` is `synchronized` because `buffer` is stateful and could be called from different threads (serial callback thread and test thread).

**Ceiling 4096:** `MAX_PAYLOAD_LENGTH = 65535` is technically useless as a guard because the length field itself is 16-bit and any 2-byte value is <= 65535. But real boards have at most 255 tiles (one-byte addressing limit in `DeviceAddress`). So if random noise creates a fake START that declares length 30000, the decoder should not wait forever for 30000 bytes and swallow all subsequent real frames as part of that bogus frame. The 4096 ceiling (`PLAUSIBLE_PAYLOAD_LENGTH_CEILING`) solves this.

### TileTouchCodec

`TileTouchCodec.instance()` returns the canonical `TileCodec<Boolean>` for touch state: encode `true -> 0x01`, `false -> 0x00`; decode any non-zero byte to `true`. This is the single place that documents the `DATA_IN` payload convention (row-major, one byte per tile).

---

## Board - Generic Tile Data Structure

`Board<T>` is a mutable grid of `height x width` stored on `Object[][]` (to avoid needing a Class token for T).

```java
public final class Board<T> {
    public Board(int width, int height, Supplier<T> initialTileSupplier) { ... }
    public Board(int width, int height, T initialTile) { this(width, height, () -> initialTile); }

    public int width() { ... }
    public int height() { ... }
    public int area() { return width * height; }
    public T get(int row, int col) { ... }          // throws BoardException out of bounds
    public T get(Position position) { ... }
    public void set(int row, int col, T tile) { ... }
    public void set(Position position, T tile) { ... }
    public void fill(T tile) { ... }
    public void forEach(TileConsumer<T> action) { ... }  // row-major (row, col, tile)
    public List<Position> positionsWhere(Predicate<T> predicate) { ... }
    public Board<T> copy() { ... }                        // deep, independent copy
    public byte[] toWireBytes(TileCodec<T> codec) { ... } // row-major flatten
    public static <T> Board<T> fromWireBytes(byte[] flat, int w, int h, TileCodec<T> codec) { ... }

    @FunctionalInterface
    public interface TileConsumer<T> { void accept(int row, int col, T tile); }
}
```

**Why generic?** Because the library should not hardcode color. One app may see a tile as `enum Color { RED, GREEN, BLUE }`, another as `Boolean` (touched/not touched), or even a custom class with brightness. `Board<T>` makes this possible.

**Key methods:**
- `positionsWhere(Boolean.TRUE::equals)` -> find touched tiles from a `Board<Boolean>`
- `toWireBytes(codec)` -> convert board to row-major byte array for wire
- `fromWireBytes` -> build board from received payload (throws `BoardException` `board.byte_length_mismatch` when `flat.length != width * height`)

**Position:** A `record Position(int row, int col)` with validation `row >= 0 && col >= 0` (`IllegalArgumentException` otherwise). Note it validates non-negativity only, not board bounds — bounds are checked by `Board.get/set` (`BoardException` `board.position_out_of_bounds`).

---

## TileCodec - Bridge Between Domain and Wire

```java
public final class TileCodec<T> {
    public static <T> TileCodec<T> of(TileEncoder<T> encoder, TileDecoder<T> decoder) { ... }
    public static TileCodec<Byte> identity() { ... }                       // raw bytes
    public static TileCodec<Boolean> booleanState(byte off, byte on) { ... } // encode off/on; decode 0=false, non-zero=true
    public static TileCodec<Boolean> booleanState() { booleanState(0, 1) }
    public byte encode(T tile) { ... }
    public T decode(byte wireValue) { ... }
}
```

**TileEncoder / TileDecoder:** Both are `@FunctionalInterface`.

**Example:**
```java
enum MyColor { OFF, RED, GREEN, BLUE }

TileCodec<MyColor> myCodec = TileCodec.of(
    color -> switch(color) { case OFF -> 0; case RED -> 1; case GREEN -> 2; case BLUE -> 3; },
    wire -> switch(wire) { case 1 -> MyColor.RED; case 2 -> MyColor.GREEN; case 3 -> MyColor.BLUE; default -> MyColor.OFF; }
);

Board<MyColor> board = new Board<>(8, 8, MyColor.OFF);
board.set(0, 0, MyColor.RED);
byte[] wireBytes = board.toWireBytes(myCodec); // [1, 0, 0, 0, ...]
```

**booleanState:** For `DATA_IN` payloads indicating which tile was touched, a ready-made Codec: `0` = false, non-zero = true on decode. Equivalent in behavior to `TileTouchCodec.instance()`.

---

## SerialTransport - Hardware Abstraction

```java
public interface SerialTransport extends AutoCloseable {
    String portName();
    boolean isOpen();
    void write(byte[] data); // may block until OS driver accepts
    void setDataListener(DataListener listener); // only one listener, fan-out is caller's responsibility; null removes
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

**SerialPortConfig:** `builder()` with `baudRate` (default 115200), `dataBits` (8), `stopBits` (1), `parity` (`NONE`), `readTimeoutMillis` (50), `writeTimeoutMillis` (50); `defaults()` = `builder().build()` (115200-8-N-1). Note: `flowControl` is fixed to `FlowControl.NONE` — the builder intentionally has no setter for it. `SerialPortInfo` is a `record(systemName, description)`. `Parity`: `NONE, ODD, EVEN, MARK, SPACE`. `FlowControl`: `NONE, RTS_CTS, XON_XOFF`.

**Why two separate interfaces?** `SerialPortRegistry` enumerates the whole system (needs global access), while `SerialTransport` represents an already-open connection. This separation improves testability: you can mock Registry without mocking Transport.

### JSerialComm Ready-Made Implementation

This module ships a ready-made implementation with `com.fazecast:jSerialComm` (`2.11.0`), but its dependency is `optional`:

```xml
<dependency>
    <groupId>com.fazecast</groupId>
    <artifactId>jSerialComm</artifactId>
    <optional>true</optional>
</dependency>
```

If you implement your own `SerialTransport` (e.g., with RXTX or a Mock for tests), you don't need jSerialComm on the classpath at all.

**JSerialCommTransport:**
- `write`: throws `SerialTransportException` (`serial.write_port_not_open`) when closed; logs TX bytes with `HexFormat` if DEBUG enabled; calls `writeBytes` and throws `SerialTransportException` (`serial.short_write`) on short write.
- `setDataListener`: `synchronized`; removes the previous listener first (prevents listener leak), creates an internal `SerialPortDataListener` on `LISTENING_EVENT_DATA_AVAILABLE` that reads `bytesAvailable()` and delivers to your `DataListener` (RX bytes logged as hex at DEBUG).
- `close`: detaches listener (`setDataListener(null)`) then `closePort()`.

**JSerialCommPortRegistry:**
- `listPorts()`: converts `SerialPort.getCommPorts()` to `SerialPortInfo(systemName, descriptiveName)`.
- `open()`: checks existence case-insensitively (`PortNotFoundException` otherwise), applies line parameters, uses `TIMEOUT_READ_SEMI_BLOCKING | TIMEOUT_WRITE_BLOCKING`, clears DTR/RTS (logs a warning if that fails — the device may not receive data even though writes report success), throws `SerialTransportException` (`serial.port_open_failed`) when `openPort()` fails.

---

## TileGatewayClient - Heart of the Module

High-level client wiring transport, codec, and listeners.

### Key Features

- **Supports two topologies:**
  - Single full-duplex port: one `SerialTransport` for both directions (`builder.transport(shared)`)
  - Two half-duplex ports: separate IN and OUT ports (`builder.inputTransport(in).outputTransport(out)`)
  - Either direction may even be `null` (send-only / receive-only client), but at least one transport is required — `build()` throws `IllegalStateException` otherwise.

- **Thread-safe:**
  - `writeLock = new Object()` -> `sendFrame()` (and therefore `send()`/`sendBoard()`) may be called from both caller thread and callback thread (game code via `GameContext.publishBoard`). Without `synchronized(writeLock)`, two concurrent writes could interleave their bytes on the wire and corrupt both frames.
  - `listeners = new CopyOnWriteArrayList<>()` -> optimized for read-heavy, write-rare: adding/removing listener is rare but iterating for dispatch is frequent. COWAL is lock-free for reading.
  - `callbackExecutor`: by default a single daemon thread named `tileboard-gateway-callback`. Every listener invocation is submitted to this executor (in registration order per frame), not run on the serial reader thread. So a slow or buggy listener never blocks the serial reader thread, and a throwing listener is caught and logged per listener.

- **Lifecycle:**
  ```java
  TileGatewayClient client = TileGatewayClient.builder()
      .transport(port)
      .build();
  client.addFrameListener(frame -> System.out.println(frame));
  client.enableIdHandshake(() -> DeviceAddress.forBoard(8, 8), 2);
  client.start(); // setDataListener on inputTransport (only if present)
  // ...
  client.close(); // closes both transports and executor (if owned)
  ```

- **Send methods:**
  - `sendFrame(Frame)` -> encode and write (throws `IllegalStateException` without an output transport)
  - `send(Command, CommandType[, payload])` -> build Frame and send
  - `sendBoard(Command, CommandType, Board<T>, TileCodec<T>)` -> flatten board with codec and send as payload

- **Receive methods:**
  - `addFrameListener(FrameListener)` / `removeFrameListener(FrameListener)` -> for every frame
  - `addBoardListener(Command, width, height, codec, BoardListener)` -> filter by Command and auto-decode payload to Board (throws `ProtocolException` wrapping `BoardException` on size mismatch)

### Builder

```java
public static final class Builder {
    public Builder transport(SerialTransport transport) { /* both directions */ }
    public Builder inputTransport(SerialTransport in) { ... }    // nullable
    public Builder outputTransport(SerialTransport out) { ... }  // nullable
    public <C extends FrameEncoder & FrameDecoder> Builder frameCodec(C codec) { ... }
    public Builder frameEncoder(FrameEncoder e) { ... }
    public Builder frameDecoder(FrameDecoder d) { ... }  // defaults to one shared DefaultFrameCodec
    public Builder callbackExecutor(Executor ex) { ... } // default: single daemon thread
    public TileGatewayClient build() { ... } // IllegalStateException if no transport at all
}
```

### start() and close() - Lifecycle details

- `start()` is `synchronized` and idempotent. It calls `inputTransport.setDataListener(this::handleIncomingBytes)` **only when an input transport is present** — a send-only client has nothing to attach to.
- `close()` is `synchronized` and robust:

```java
public synchronized void close() {
    try { if (inputTransport != null) { try { inputTransport.setDataListener(null); } finally { inputTransport.close(); } } }
    catch (RuntimeException e) { log.warn("Failed to close input transport", e); }
    try { if (outputTransport != null && outputTransport != inputTransport) outputTransport.close(); }
    catch (RuntimeException e) { log.warn("Failed to close output transport", e); }
    try { if (ownsExecutor && callbackExecutor instanceof ExecutorService es) es.shutdown(); }
    finally { started = false; }
}
```

Each step runs even if a previous step throws, otherwise a buggy `transport.close()` could leak the other transport's OS handle and the callback executor thread forever. A caller-supplied `callbackExecutor` is never shut down (`ownsExecutor == false`).

---

## Handshake - Tile Addressing and Identity

When the board powers on, each physical tile must be assigned a logical address. This is done via handshake:

1. Board sends `ID`/`CLEAR` ("tell me the board geometry")
2. `HandshakeCoordinator` (a `FrameListener` that ignores every non-`ID` frame) receives it
3. Asks `AddressResolver`: "For MxN board, what should addresses be?" -> `DeviceAddress.forBoard(width, height)`
4. Sends `ID`/`SET` with `address.toPayload()` (2 bytes: totalTiles, tilesPerRow)
5. When the controller later reports an assignment (any other `ID` frame), `SequenceValidator` (e.g., `SequentialIdSequenceValidator`) checks it; if invalid, `ID`/`CLEAR` is sent again to restart the handshake

```java
// Both arguments are required - there is no single-arg overload:
client.enableIdHandshake(
    () -> DeviceAddress.forBoard(8, 8),
    new SequentialIdSequenceValidator(minimumSequence));
// ...or equivalently:
client.enableIdHandshake(() -> DeviceAddress.forBoard(8, 8), minimumSequence);
```

Register the handshake **before** `start()`, otherwise the board's first `ID`/`CLEAR` frames can arrive before the coordinator is listening and be silently dropped (`dispatch()` only notifies listeners registered at decode time).

**DeviceAddress:** `record DeviceAddress(int totalTiles, int tilesPerRow)`, both in `[1, 255]`. `forBoard(width, height)` = `(width*height, width)`. This single-byte encoding is the source of the 255-tile protocol ceiling.

**SequentialIdSequenceValidator:** Constructor requires `minimumLength >= 1`. A payload is valid when `length >= minimumLength` **and** every byte except the last equals its 1-based position (`payload[i] == i + 1`).

---

## Exceptions - Localizable Hierarchy

Every exception in this module extends `LocalizableException` (in `support.error`), which extends `RuntimeException` and adds a stable `errorCode()` + positional `args()` for message-catalog interpolation, plus the plain English `getMessage()` diagnostic. The error code doubles as the `MessageSource` key that `tileboard-app` resolves to a localized (Persian) message.

| Exception | Extends | errorCode examples |
|------|---------|-------------------|
| `ProtocolException` | `LocalizableException` | `protocol.unknown_command`, `protocol.unknown_command_type`, `protocol.frame_payload_board_mismatch` |
| `InvalidFrameException` | `ProtocolException` | `protocol.payload_too_large` |
| `BoardException` | `LocalizableException` | `board.invalid_dimensions`, `board.byte_length_mismatch`, `board.position_out_of_bounds` |
| `SerialTransportException` | `LocalizableException` | `serial.port_open_failed`, `serial.write_port_not_open`, `serial.short_write` |
| `PortNotFoundException` | `SerialTransportException` | `serial.port_not_found` |

---

## Step-by-Step Usage Tutorial

### Step 1: Maven Dependency

```xml
<dependency>
    <groupId>com.tileboard</groupId>
    <artifactId>tileboard-serial-protocol</artifactId>
    <version>1.0.0</version>
</dependency>
<!-- If you want ready-made jSerialComm impl: -->
<dependency>
    <groupId>com.fazecast</groupId>
    <artifactId>jSerialComm</artifactId>
    <version>2.11.0</version>
</dependency>
```

Requires Java 17+ (`maven.compiler.source/target = 17`).

### Step 2: Discover and Open Port

```java
SerialPortRegistry registry = new JSerialCommPortRegistry();

List<SerialPortInfo> ports = registry.listPorts();
ports.forEach(p -> System.out.println(p.systemName() + " - " + p.description()));

SerialPortConfig config = SerialPortConfig.builder()
    .baudRate(115200)
    .dataBits(8)
    .stopBits(1)
    .parity(Parity.NONE)
    .readTimeoutMillis(50)
    .writeTimeoutMillis(50)
    .build();
// ...or simply SerialPortConfig.defaults() (identical values)

SerialTransport transport = registry.open("COM3", config); // or /dev/ttyUSB0
```

### Step 3: Build Client and Register Listeners

```java
TileGatewayClient client = TileGatewayClient.builder()
    .transport(transport)
    .build();

client.addFrameListener(frame -> {
    System.out.println("Received: " + frame.command() + " " + frame.commandType() + " len=" + frame.payloadLength());
});

TileCodec<Boolean> touchCodec = TileCodec.booleanState(); // or TileTouchCodec.instance()
client.addBoardListener(Command.DATA_IN, 8, 8, touchCodec, touchBoard -> {
    List<Position> touched = touchBoard.positionsWhere(Boolean.TRUE::equals);
    touched.forEach(pos -> System.out.println("Touched: " + pos));
});

// Register BEFORE start():
client.enableIdHandshake(() -> DeviceAddress.forBoard(8, 8), 2);
client.start();
```

### Step 4: Send Board to Hardware

```java
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

### Step 5: Close

```java
client.close(); // detaches listener, closes transports, shuts down owned callback thread
```

### Mock Example for Testing Without Hardware

```java
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

DefaultFrameCodec codec = new DefaultFrameCodec();
Frame fakeFrame = Frame.of(Command.DATA_IN, CommandType.SET, new byte[]{1,0,0});
byte[] wire = codec.encode(fakeFrame);
mock.injectRx(wire);
```

---

## Deep Dive - Concurrency and Thread-Safety

### 1. DefaultFrameCodec.decode - synchronized and stateful buffer

**Problem:** Serial data arrives in random chunks. A frame may be split between two chunks. If `decode` is called concurrently from two threads, `buffer` gets corrupted.

**Solution:**
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
- `synchronized` guarantees only one thread mutates buffer at a time.
- `finally` guarantees even if unexpected exception occurs, buffer is trimmed, otherwise same bad bytes would fail every future call forever.

**Resynchronization logic:** When START is found but END mismatches, payloadLength is implausible (> 4096), or command/type bytes are unknown, `consumedUpTo = start+1` and `continue` -> look for START one byte later. This prevents decoder from getting stuck in an infinite loop or swallowing real frames.

### 2. TileGatewayClient - writeLock and CopyOnWriteArrayList

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
- `sendFrame()` may be called from the main thread (user) and callback thread (game code via `GameContext.publishBoard`) concurrently.
- Without `writeLock`, two writes could interleave bytes: half of frame A, then half of frame B -> both corrupted on wire.

**CopyOnWriteArrayList for listeners:**
- Scenario: adding/removing listener is rare (at startup/shutdown), but iterating for dispatch is frequent (every time data arrives).
- COWAL is lock-free for reading (iteration on snapshot), and only copies whole array on write. Optimal for this pattern.
- Alternative `synchronizedList` would need a lock for every dispatch and lower throughput.

**callbackExecutor:**
- By default `newSingleThreadExecutor` with a daemon thread named `tileboard-gateway-callback`. Listeners run in submission order and never block the serial reader thread (managed by jSerialComm).
- If a listener is slow or throws, only the executor is affected (exceptions are caught and logged per listener), not the transport. A caller-supplied executor is used as-is and never shut down by `close()`.

### 3. JSerialCommTransport.setDataListener - synchronized

```java
public synchronized void setDataListener(DataListener listener) {
    if (activeListener != null) { delegate.removeDataListener(); activeListener = null; }
    if (listener == null) return;
    activeListener = new SerialPortDataListener() { ... };
    delegate.addDataListener(activeListener);
}
```
- `synchronized` so two concurrent `setDataListener` calls don't leave one listener not removed and leaked.
- Holds `activeListener` (a `volatile` field) to be able to `removeDataListener`.

### 4. HandshakeCoordinator - No Extra State

This class is stateless (immutable resolver/validator/sender references) and just delegates. Since `TileGatewayClient` delivers callbacks on a single-thread executor by default, no extra synchronization is needed for the standard wiring.

---

## Tests

```bash
mvn test -pl tileboard-serial-protocol
```

- `BoardTest`: generic Board, copy, positionsWhere, wireBytes
- `DefaultFrameCodecTest`: encode/decode, fragmented frame, noise, resync
- `TileGatewayClientTest`: dispatch, listener, writeLock
- `HandshakeCoordinatorTest`: handshake
- `BoardFrameListenerTest`: Command filtering and Board decoding
- `TileboardHardwareIT` (+ `HardwareTestConfig`, `DemoColor`): integration with real hardware — failsafe-based, only in the `hardware-tests` profile:

```bash
mvn verify -P hardware-tests -pl tileboard-serial-protocol -Dtileboard.hardware.port=COM3
```

Optional system properties (with defaults): `tileboard.hardware.width=3`, `tileboard.hardware.height=3`, `tileboard.hardware.baud=115200`, `tileboard.hardware.stepDelayMs=1000`, `tileboard.hardware.idTimeoutSeconds=5`. Without `tileboard.hardware.port` the suite is skipped, so it is safe to leave in the normal build.

---

## Advanced Topics

### Using Without jSerialComm

```java
SerialTransport myTransport = new MyCustomTransport("/dev/ttyUSB0");
TileGatewayClient client = TileGatewayClient.builder()
    .transport(myTransport)
    .frameEncoder(new MyCustomFrameEncoder())
    .build();
```

### Two-Port Topology

Some boards use two half-duplex adapters (one TX-only, one RX-only):

```java
SerialTransport in = registry.open("COM4", SerialPortConfig.defaults());
SerialTransport out = registry.open("COM3", SerialPortConfig.defaults());
TileGatewayClient client = TileGatewayClient.builder()
    .inputTransport(in)
    .outputTransport(out)
    .build();
```

It is also legal to build a send-only client (only `outputTransport`) or receive-only client (only `inputTransport`); only a client with *neither* is rejected.

### Payload ceilings

`DefaultFrameCodec` enforces `MAX_PAYLOAD_LENGTH` (65535) on encode and a 4096-byte plausibility ceiling while resynchronizing on decode. Board payloads are at most 255 bytes (the addressing limit), far below both. If your protocol variant needs larger payloads, implement your own `FrameDecoder`/`FrameEncoder`.

---

**Author:** Tileboard Platform Team  
**Version:** 1.0.0  
**Java:** 17+  
**License:** Internal
