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
9. [Step-by-Step Usage Tutorial](#step-by-step-usage-tutorial)
10. [Deep Dive - Concurrency and Thread-Safety](#deep-dive---concurrency-and-thread-safety)
11. [Tests](#tests)
12. [Advanced Topics](#advanced-topics)

---

## Overall Architecture

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

**Design Principle:** Each layer depends only on the layer below through an interface. For example, `TileGatewayClient` only knows `SerialTransport`, not `JSerialCommTransport`. This means you can implement `SerialTransport` with RXTX, jSSC, a Mock for tests, or even a network bridge without changing a single line in the protocol layer.

---

## Package Structure

| Package | Responsibility | Key Classes |
|------|---------|---------------|
| `com.tileboard.serial.board` | Generic board data structure | `Board<T>`, `Position`, `TileCodec`, `TileEncoder`, `TileDecoder` |
| `com.tileboard.serial.protocol` | Wire framing | `ProtocolConstants`, `Frame`, `Command`, `CommandType`, `DefaultFrameCodec`, `TileTouchCodec` |
| `com.tileboard.serial.transport` | Serial port abstraction | `SerialTransport`, `SerialPortRegistry`, `SerialPortConfig`, `SerialPortInfo`, `DataListener` |
| `com.tileboard.serial.transport.jserialcomm` | jSerialComm implementation | `JSerialCommTransport`, `JSerialCommPortRegistry` |
| `com.tileboard.serial.gateway` | High-level client | `TileGatewayClient`, `FrameListener`, `BoardListener`, `BoardFrameListener` |
| `com.tileboard.serial.gateway.handshake` | Addressing handshake | `HandshakeCoordinator`, `DeviceAddress`, `AddressResolver`, `SequenceValidator` |
| `com.tileboard.serial.exception` | Exceptions | `ProtocolException`, `BoardException`, `SerialTransportException` |

---

## Protocol Layer - Framing

### Wire Frame Format

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

- **Fixed overhead:** 7 bytes (START + SEP + CMD + TYPE + LEN(2) + END)
- **Max payload:** 65535 bytes (16-bit field) but in practice boards are at most a few hundred bytes (protocol addressing limit: 255 tiles)

### DefaultFrameCodec - Reference Implementation

This class implements both `FrameEncoder` and `FrameDecoder`.

#### encode - stateless and pure
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

#### decode - stateful and noise-resistant
`decode` must handle three cases simultaneously:
1. **Fragmented frame:** Half of a frame arrives in one read, the other half in the next read
2. **Multiple frames in one read:** One read may contain 3 complete frames together
3. **Line noise:** Random bytes that look like START but are not real frames

That's why `DefaultFrameCodec` has an internal `ByteArrayOutputStream buffer` that retains bytes between calls.

**Resynchronization algorithm:**
```java
synchronized List<Frame> decode(byte[] chunk) {
    buffer.writeBytes(chunk);
    byte[] data = buffer.toByteArray();
    int consumedUpTo = 0;
    while (true) {
        int start = indexOfFrameStart(data, consumedUpTo); // find 0xFC ':'
        if (start < 0) { consumedUpTo = data.length; break; }
        if (available < 6) { consumedUpTo = start; break; } // don't have length yet
        int payloadLength = ((data[start+4] & 0xFF) << 8) | (data[start+5] & 0xFF);
        if (payloadLength > 4096) { consumedUpTo = start+1; continue; } // plausible ceiling, noise
        int total = payloadLength + 7;
        if (available < total) { consumedUpTo = start; break; } // frame not complete yet
        if (data[endIndex] != END_BYTE) { consumedUpTo = start+1; continue; } // END mismatch -> noise
        try { parse Command, CommandType } catch { consumedUpTo = start+1; continue; }
        // valid frame -> add to list
        consumedUpTo = start + total;
    }
    buffer.reset(); // always trim, even if exception
    if (consumedUpTo < data.length) buffer.write(remaining);
    return frames;
}
```

**Concurrency note:** `decode` is `synchronized` because `buffer` is stateful and could be called from different threads (serial callback thread and test thread).

**Ceiling 4096:** `MAX_PAYLOAD_LENGTH = 65535` is technically useless as a guard because the length field itself is 16-bit and any 2-byte value is <= 65535. But real boards have at most 255 tiles (one-byte addressing limit in `DeviceAddress`). So if random noise creates a fake START that declares length 30000, the decoder should not wait forever for 30000 bytes and swallow all subsequent real frames as part of that bogus frame. The 4096 ceiling solves this.

---

## Board - Generic Tile Data Structure

`Board<T>` is a mutable grid of `height x width` stored on `Object[][]` (to avoid needing a Class token for T).

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

**Why generic?** Because the library should not hardcode color. One app may see a tile as `enum Color { RED, GREEN, BLUE }`, another as `Boolean` (touched/not touched), or even a custom class with brightness. `Board<T>` makes this possible.

**Key methods:**
- `positionsWhere(Boolean.TRUE::equals)` -> find touched tiles from a `Board<Boolean>`
- `toWireBytes(codec)` -> convert board to row-major byte array for wire
- `fromWireBytes` -> build board from received payload

**Position:** A simple `record` with validation `row >=0 && col >=0`.

---

## TileCodec - Bridge Between Domain and Wire

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

**booleanState:** For `DATA_IN` payloads indicating which tile was touched, a ready-made Codec: `0` = false, non-zero = true.

---

## SerialTransport - Hardware Abstraction

```java
public interface SerialTransport extends AutoCloseable {
    String portName();
    boolean isOpen();
    void write(byte[] data); // may block until OS driver accepts
    void setDataListener(DataListener listener); // only one listener, fan-out is caller's responsibility
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

**SerialPortConfig:** Builder for baudRate, dataBits, stopBits, parity, flowControl, timeouts.

**Why two separate interfaces?** `SerialPortRegistry` enumerates the whole system (needs global access), while `SerialTransport` represents an already-open connection. This separation improves testability: you can mock Registry without mocking Transport.

### JSerialComm Ready-Made Implementation

This module ships a ready-made implementation with `com.fazecast:jSerialComm`, but its dependency is `optional`:

```xml
<dependency>
    <groupId>com.fazecast</groupId>
    <artifactId>jSerialComm</artifactId>
    <optional>true</optional>
</dependency>
```

If you implement your own `SerialTransport` (e.g., with RXTX or a Mock for tests), you don't need jSerialComm on the classpath at all.

**JSerialCommTransport:**
- `write`: logs bytes with `HexFormat` if DEBUG enabled, then calls `writeBytes` and checks short write
- `setDataListener`: creates an internal `SerialPortDataListener` that reads `bytesAvailable` and delivers to your `DataListener`. It's `synchronized` to correctly remove previous listener.
- `close`: detaches listener then closes port

**JSerialCommPortRegistry:**
- `listPorts()`: converts `SerialPort.getCommPorts()` to `SerialPortInfo`
- `open()`: checks existence (PortNotFoundException), sets parameters, calls `openPort()`, clears DTR/RTS (logs warning if fails)

---

## TileGatewayClient - Heart of the Module

High-level client wiring transport, codec, and listeners.

### Key Features

- **Supports two topologies:**
  - Single full-duplex port: one `SerialTransport` for both directions (`builder.transport(shared)`)
  - Two half-duplex ports: separate IN and OUT ports (`builder.inputTransport(in).outputTransport(out)`)

- **Thread-safe:**
  - `writeLock = new Object()` -> `send()` and `sendBoard()` may be called from both caller thread and callback thread (game code via GameContext.publish). Without `synchronized(writeLock)`, two concurrent writes could interleave their bytes on the wire and corrupt both frames.
  - `listeners = new CopyOnWriteArrayList<>()` -> optimized for read-heavy, write-rare: adding/removing listener is rare but iterating for dispatch is frequent. COWAL is lock-free for reading.
  - `callbackExecutor`: by default a daemon thread named `tileboard-gateway-callback`. All `FrameListener`s run on this executor, not on the serial reader thread. So a slow or buggy listener never blocks the serial reader thread.

- **Lifecycle:**
  ```java
  TileGatewayClient client = TileGatewayClient.builder()
      .transport(port)
      .build();
  client.addFrameListener(frame -> System.out.println(frame));
  client.enableIdHandshake(() -> DeviceAddress.forBoard(8, 8));
  client.start(); // setDataListener on inputTransport
  // ...
  client.close(); // closes both transports and executor
  ```

- **Send methods:**
  - `sendFrame(Frame)` -> encode and write
  - `send(Command, CommandType, payload)` -> build Frame and send
  - `sendBoard(Command, CommandType, Board<T>, TileCodec<T>)` -> flatten board with codec and send as payload

- **Receive methods:**
  - `addFrameListener(FrameListener)` -> for every frame
  - `addBoardListener(Command, width, height, codec, BoardListener)` -> filter by Command and auto-decode payload to Board

### Builder

```java
public static final class Builder {
    public Builder transport(SerialTransport transport) { /* both directions */ }
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

### close() - Robust Cleanup

```java
public synchronized void close() {
    try { inputTransport.setDataListener(null); inputTransport.close(); } catch { log.warn }
    try { if (output != input) output.close(); } catch { log.warn }
    try { if (ownsExecutor && executor is ExecutorService) shutdown(); } finally { started=false; }
}
```

Each step runs even if previous step throws, otherwise a buggy `transport.close()` could leak the other transport's OS handle and the callback executor thread forever.

---

## Handshake - Tile Addressing and Identity

When the board powers on, each physical tile must be assigned a logical address. This is done via handshake:

1. Board sends `ID` (or `CLEAR`) frame
2. `HandshakeCoordinator` (a `FrameListener`) receives it
3. Asks `AddressResolver`: "For MxN board, what should addresses be?" -> `DeviceAddress.forBoard(width, height)`
4. Sends response frame with `sendFrame`
5. `SequenceValidator` (e.g., `SequentialIdSequenceValidator`) checks that reported sequence is valid (minimum length, ascending order)

```java
client.enableIdHandshake(() -> DeviceAddress.forBoard(8, 8));
// equivalent to:
client.enableIdHandshake(addressResolver, new SequentialIdSequenceValidator(minimumSequence));
```

**DeviceAddress:** Encodes whole board address in one byte (255 tile limit).

**SequentialIdSequenceValidator:** Checks that ID sequence has at least `minimumSequence` length and is ordered.

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

SerialTransport transport = registry.open("COM3", config); // or /dev/ttyUSB0
```

### Step 3: Build Client and Register Listeners

```java
TileGatewayClient client = TileGatewayClient.builder()
    .transport(transport)
    .build();

client.addFrameListener(frame -> {
    System.out.println("Received: " + frame.command() + " " + frame.commandType() + " len=" + frame.payload().length);
});

TileCodec<Boolean> touchCodec = TileCodec.booleanState();
client.addBoardListener(Command.DATA_IN, 8, 8, touchCodec, touchBoard -> {
    List<Position> touched = touchBoard.positionsWhere(Boolean.TRUE::equals);
    touched.forEach(pos -> System.out.println("Touched: " + pos));
});

client.enableIdHandshake(() -> DeviceAddress.forBoard(8, 8));
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
client.close(); // closes transports and callback thread
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

**Resynchronization logic:** When START is found but END mismatches or payloadLength is implausible, `consumedUpTo = start+1` and `continue` -> look for START one byte later. This prevents decoder from getting stuck in infinite loop.

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
- `send()` may be called from main thread (user) and callback thread (game code via GameContext.publish) concurrently.
- Without `writeLock`, two writes could interleave bytes: half of frame A, then half of frame B -> both corrupted on wire.

**CopyOnWriteArrayList for listeners:**
- Scenario: adding/removing listener is rare (at startup/shutdown), but iterating for dispatch is frequent (every time data arrives).
- COWAL is lock-free for reading (iteration on snapshot), and only copies whole array on write. Optimal for this pattern.
- Alternative `synchronizedList` would need lock for every dispatch and lower throughput.

**callbackExecutor:**
- By default `newSingleThreadExecutor(daemon thread)`. Guarantees listeners run in order of frame arrival (single thread) and never block serial reader thread (managed by jSerialComm).
- If a listener is slow or throws, only executor slows, not transport.

### 3. JSerialCommTransport.setDataListener - synchronized

```java
public synchronized void setDataListener(DataListener listener) {
    if (activeListener != null) { delegate.removeDataListener(); activeListener=null; }
    if (listener==null) return;
    activeListener = new SerialPortDataListener() { ... };
    delegate.addDataListener(activeListener);
}
```
- `synchronized` so two concurrent `setDataListener` calls don't leave one listener not removed and leaked.
- Holds `activeListener` to be able to `removeDataListener`.

### 4. HandshakeCoordinator - No Extra State

This class is stateless and just delegates to `AddressResolver` and `SequenceValidator`. Since `TileGatewayClient` guarantees callbacks run on one thread (callbackExecutor), no extra synchronization needed.

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
- `TileboardHardwareIT`: integration with real hardware (only with `hardware-tests` profile)

```bash
mvn verify -P hardware-tests -pl tileboard-serial-protocol
```

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
SerialTransport in = registry.open("COM4", config);
SerialTransport out = registry.open("COM3", config);
TileGatewayClient client = TileGatewayClient.builder()
    .inputTransport(in)
    .outputTransport(out)
    .build();
```

### Advanced FrameCodec Settings

`DefaultFrameCodec` has a 4096 payload ceiling. If your board has larger payload (e.g., 10x10=100 bytes, still below 4096), no problem. If your protocol has larger payloads, implement your own `FrameDecoder`.

---

**Author:** Tileboard Platform Team  
**Version:** 1.0.0  
**Java:** 17+  
**License:** Internal
