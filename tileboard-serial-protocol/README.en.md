# Tileboard Serial Protocol

A transport-agnostic, framework-free Java library implementing the serial
wire protocol used to talk to an `m x n` LED/touch tile matrix controller.
It handles framing, command dispatch, board (de)serialization and the
device-address handshake — nothing about a specific serial library, UI
framework or tile palette is hard-coded. Every extension point is a small
functional interface, so the library drops into a Spring app, a plain CLI
tool, or a desktop app equally well.

> **Requires Java 17+.** Not published to Maven Central — build and install
> it locally (see [Installation](#installation)).

---

## Table of contents

- [Installation](#installation)
- [Wire protocol](#wire-protocol)
- [Package overview](#package-overview)
- [Core concepts](#core-concepts)
  - [`protocol` — frames and commands](#protocol--frames-and-commands)
  - [`board` — the tile grid](#board--the-tile-grid)
  - [`transport` — serial ports](#transport--serial-ports)
  - [`gateway` — the high-level client](#gateway--the-high-level-client)
  - [`gateway.handshake` — device addressing](#gatewayhandshake--device-addressing)
  - [`support.error` — localizable exceptions](#supporterror--localizable-exceptions)
- [Full usage example](#full-usage-example)
- [Detecting which tile was touched](#detecting-which-tile-was-touched)
- [Exceptions](#exceptions)
- [Testing](#testing)

---

## Installation

The library isn't on a public repository, so build and install it into your
local `.m2` cache first:

```bash
cd tileboard-serial-protocol
mvn install
```

Then depend on it from your application's `pom.xml`:

```xml
<dependency>
    <groupId>com.tileboard</groupId>
    <artifactId>tileboard-serial-protocol</artifactId>
    <version>1.0.0</version>
</dependency>
```

The library's only mandatory dependency is `slf4j-api` (a logging facade —
bring whatever logging backend you already use). `com.fazecast:jSerialComm`
(`2.11.0`) is an **optional** dependency: you only need it on your classpath
if you use the ready-made `JSerialCommPortRegistry`/`JSerialCommTransport`.
If you talk to the device through some other means, implement
`SerialTransport` yourself and you never need jSerialComm at all.

---

## Wire protocol

Every frame exchanged with the controller, in both directions, has this
layout (see `ProtocolConstants`):

```
byte 0      : START_BYTE            (0xFC)
byte 1      : SEPARATOR             (':')
byte 2      : command code          (see Command)
byte 3      : command type code     (see CommandType)
byte 4      : payload length, high byte (big endian, unsigned 16 bit)
byte 5      : payload length, low byte
byte 6..n-2 : payload (0..N bytes)
byte n-1    : END_BYTE              ('#')
```

Fixed overhead is 7 bytes (`FRAME_OVERHEAD_BYTES`). The length field is a
16-bit unsigned value, so the technical maximum payload is `0xFFFF`
(`MAX_PAYLOAD_LENGTH`).

`Command` (byte 2) is the high-level operation:

| Constant        | Code | Meaning                                          |
|-----------------|------|---------------------------------------------------|
| `INTRODUCTION`  | 0    | Handshake / "hello" from either side              |
| `DATA_IN`       | 1    | Tile state reported **by** the controller (input) |
| `DATA_OUT`      | 2    | Tile state sent **to** the controller (output)    |
| `ID`            | 3    | Tile-id addressing handshake                      |
| `STOP`          | 4    | Stop the current program/game                     |
| `START`         | 5    | Start a program/game                              |
| `COMMAND`       | 6    | Generic command                                   |
| `RESET_PROGRAM` | 7    | Reset the running program                          |
| `CLEAR_ID`      | 8    | Clear assigned tile ids                            |

`CommandType` (byte 3) qualifies the command:

| Constant       | Code | Meaning                          |
|----------------|------|-----------------------------------|
| `SET`          | 0    | Set a value                       |
| `GET`          | 1    | Request a value                   |
| `CLEAR`        | 2    | Clear a value / ask for a reset   |
| `SET_EXTENDED` | 3    | Extended/large set operation      |
| `NA`           | 4    | Not applicable                    |
| `WAIT`         | 5    | Controller is asking to wait      |

Each enum binds its wire value **explicitly** rather than relying on
`ordinal()` — reordering or inserting a constant later can never silently
corrupt the protocol. Unknown codes resolve via `fromCode(int)` which throws
`ProtocolException` (`protocol.unknown_command` /
`protocol.unknown_command_type`).

For `DATA_IN`/`DATA_OUT` frames the payload is a flat, row-major array of
tiles: one byte per tile, `width * height` bytes total, left-to-right,
top-to-bottom. `Board` (below) is what turns that flat array into 2D
coordinates and back.

---

## Package overview

| Package                          | Responsibility                                                                 |
|-----------------------------------|--------------------------------------------------------------------------------|
| `com.tileboard.serial.protocol`   | Frame model, framing/encoding/decoding (`Command`, `CommandType`, `Frame`, `FrameEncoder`, `FrameDecoder`, `DefaultFrameCodec`, `ProtocolConstants`, `TileTouchCodec`) |
| `com.tileboard.serial.board`      | The application-facing tile grid (`Board`, `Position`, `TileCodec`, `TileEncoder`, `TileDecoder`) |
| `com.tileboard.serial.transport`  | Abstraction over a physical serial connection (`SerialTransport`, `SerialPortRegistry`, `SerialPortConfig`, `SerialPortInfo`, `Parity`, `FlowControl`, `DataListener`) + a ready `jserialcomm` implementation |
| `com.tileboard.serial.gateway`    | The high-level client apps actually use (`TileGatewayClient`, `FrameListener`, `BoardListener`, `BoardFrameListener`) |
| `com.tileboard.serial.gateway.handshake` | The tile-id addressing handshake (`AddressResolver`, `DeviceAddress`, `HandshakeCoordinator`, `SequenceValidator`, `SequentialIdSequenceValidator`) |
| `com.tileboard.serial.exception`  | The library's exception hierarchy (`ProtocolException`, `InvalidFrameException`, `BoardException`, `SerialTransportException`, `PortNotFoundException`) |
| `com.tileboard.serial.support.error` | Shared `LocalizableException` base class used by every tileboard module |

---

## Core concepts

### `protocol` — frames and commands

- **`Frame`** — an immutable, already-validated `(Command, CommandType, payload)` triple. This is the unit everything above the codec works with; nobody above `DefaultFrameCodec` touches raw bytes. `payload()` returns a defensive copy; `payloadLength()` avoids copying.
- **`FrameEncoder`** *(functional interface)* — `byte[] encode(Frame frame)`.
- **`FrameDecoder`** *(functional interface)* — `List<Frame> decode(byte[] chunk)`. Stateful: implementations buffer bytes across calls because a serial read can contain half a frame, several frames, or the tail of a previously started one.
- **`DefaultFrameCodec`** — the reference implementation of both, matching the layout above.
  - `encode` is pure and stateless, but rejects payloads larger than `MAX_PAYLOAD_LENGTH` (65535) with `InvalidFrameException` (`protocol.payload_too_large`).
  - `decode` is `synchronized` and stateful: it accumulates bytes in an internal buffer, handles split reads and multiple frames per read, and resynchronizes on the next `START_BYTE` (`0xFC` + `':'`) when bytes don't form a valid frame. While resynchronizing it applies a **4096-byte plausibility ceiling** (`PLAUSIBLE_PAYLOAD_LENGTH_CEILING`): a stray START match declaring a huge length is treated as noise and skipped one byte later instead of swallowing all subsequent real frames. The `finally` block always trims the buffer, so a malformed chunk can never wedge the decoder forever.
  - One instance per physical input stream (i.e. one per `SerialTransport` being read from); never share one across independent connections.
- **`ProtocolConstants`** — the fixed wire values (`START_BYTE`, `SEPARATOR_BYTE`, `END_BYTE`, `FRAME_OVERHEAD_BYTES`, `MAX_PAYLOAD_LENGTH`).
- **`TileTouchCodec`** — `instance()` returns the canonical `TileCodec<Boolean>` for touch state: `0x00` = not touched, `0x01` = touched on encode; any non-zero byte decodes to `true`.

```java
DefaultFrameCodec codec = new DefaultFrameCodec();

byte[] wire = codec.encode(Frame.of(Command.START, CommandType.SET));
// ... send wire bytes on the wire ...

List<Frame> frames = codec.decode(bytesJustRead); // may be empty, 1, or several frames
```

### `board` — the tile grid

- **`Position`** — a zero-based `(row, col)` record. The compact constructor rejects negative values with `IllegalArgumentException`.
- **`Board<T>`** — a mutable `height x width` grid over *any* application tile type `T` (a color enum, a boolean, a custom record — the library never dictates what a "tile" is). Stored as `Object[][]` internally so no `Class<T>` token is needed.
  - `width()`, `height()`, `area()` (`width * height`)
  - `get`/`set` by `(row, col)` or `Position` (out-of-bounds access throws `BoardException` with `board.position_out_of_bounds`)
  - `fill(T tile)`, `copy()` (deep, independent copy), `forEach(TileConsumer<T>)` (row-major `(row, col, tile)` callback)
  - `toWireBytes(TileCodec<T>)` — flattens the board into the row-major bytes the controller expects
  - `static Board<T> fromWireBytes(byte[] flat, int width, int height, TileCodec<T> codec)` — the reverse; throws `BoardException` (`board.byte_length_mismatch`) if `flat.length != width * height`
  - `positionsWhere(Predicate<T> predicate)` — every `Position` whose tile matches the predicate, in row-major order. This is the direct answer to *"which tile(s) changed?"* — e.g. `touchBoard.positionsWhere(Boolean.TRUE::equals)` to find touched tiles.
- **`TileEncoder<T>`** / **`TileDecoder<T>`** *(functional interfaces)* — convert one tile to/from its wire byte.
- **`TileCodec<T>`** — pairs an encoder and decoder. Ready-made factories:
  - `TileCodec.identity()` — for boards that already work in raw bytes.
  - `TileCodec.booleanState()` / `TileCodec.booleanState(byte off, byte on)` — the common single-flag-byte-per-tile convention (encode: `off`/`on`; decode: `0` = `false`, any non-zero = `true`). This is exactly what you want for touch/press input.

```java
enum Color { OFF, RED, GREEN }

TileCodec<Color> colorCodec = TileCodec.of(
        color -> switch (color) { case OFF -> (byte) 0; case RED -> (byte) 1; case GREEN -> (byte) 2; },
        wire -> switch (wire) { case 1 -> Color.RED; case 2 -> Color.GREEN; default -> Color.OFF; });

Board<Color> board = new Board<>(8, 8, Color.OFF);
board.set(0, 0, Color.RED);
byte[] outgoing = board.toWireBytes(colorCodec);
```

### `transport` — serial ports

- **`SerialTransport`** — an abstraction over a single, already-open connection: `portName()`, `isOpen()`, `write(byte[])`, `setDataListener(DataListener)` (a single listener; `null` removes it — fan-out is the caller's responsibility), `close()`. Implement this yourself for a hardware library other than jSerialComm, or for a test double — nothing above this layer cares which one is used.
- **`SerialPortRegistry`** — discovers ports (`listPorts()`) and opens them (`open(String portName, SerialPortConfig config)`), throwing `PortNotFoundException`/`SerialTransportException` as appropriate.
- **`SerialPortConfig`** — line settings. `SerialPortConfig.defaults()` gives `115200-8-N-1` with 50 ms read/write timeouts, the settings the tile controller firmware expects. The `Builder` exposes `baudRate`, `dataBits`, `stopBits`, `parity`, `readTimeoutMillis`, `writeTimeoutMillis`. Note `flowControl` is fixed to `FlowControl.NONE` (there is intentionally no builder setter for it).
- **`JSerialCommPortRegistry`** / **`JSerialCommTransport`** — the ready-made implementation backed by [jSerialComm](https://fazecast.github.io/jSerialComm/). This is the only part of the library that imports jSerialComm types.
  - `open` checks the port exists (case-insensitive, else `PortNotFoundException`), applies line parameters, uses `TIMEOUT_READ_SEMI_BLOCKING | TIMEOUT_WRITE_BLOCKING`, clears DTR/RTS (logs a warning if that fails), and throws `SerialTransportException` (`serial.port_open_failed`) when `openPort()` fails.
  - `write` throws `SerialTransportException` when the port is closed (`serial.write_port_not_open`) or on short writes (`serial.short_write`); TX bytes are logged as hex at DEBUG level.
  - `setDataListener` is `synchronized` (prevents listener leaks on concurrent calls), reads `bytesAvailable()` on `LISTENING_EVENT_DATA_AVAILABLE`, and logs RX bytes as hex at DEBUG level.

```java
SerialPortRegistry registry = new JSerialCommPortRegistry();
registry.listPorts().forEach(p -> System.out.println(p.systemName() + " - " + p.description()));

SerialTransport port = registry.open("COM3", SerialPortConfig.defaults());
```

### `gateway` — the high-level client

- **`FrameListener`** *(functional interface)* — `void onFrame(Frame frame)`. Notified for every complete frame received.
- **`BoardListener<T>`** *(functional interface)* — `void onBoard(Board<T> board)`. The board-shaped counterpart of `FrameListener`.
- **`BoardFrameListener<T>`** — a `FrameListener` adapter that only reacts to one `Command`, decodes that frame's payload into a `Board<T>` with a supplied `TileCodec<T>`, and forwards it to a `BoardListener<T>`. Throws `ProtocolException` (`protocol.frame_payload_board_mismatch`, wrapping the underlying `BoardException`) if a frame's payload doesn't match the configured board size — e.g. the device reports a different tile count than expected. Frames for any other command are ignored (TRACE log).
- **`TileGatewayClient`** — the class applications actually use day to day. It owns an input transport, an output transport (can be the same object for a full-duplex port, or either may be `null` for a send-only / receive-only client — but at least one is required at `build()` time), and a `FrameEncoder`/`FrameDecoder` pair (defaults to a shared `DefaultFrameCodec` if you don't supply one). It turns incoming bytes into `Frame` callbacks on any number of registered listeners, running them on a configurable `Executor` (a dedicated single daemon thread named `tileboard-gateway-callback` by default) so a slow listener never blocks the serial reader.

  Key methods:
  - `builder()...build()` — `transport(shared)` for full duplex, or `inputTransport(in)` + `outputTransport(out)` for two half-duplex adapters; `frameCodec(combined)` / `frameEncoder(e)` / `frameDecoder(d)`; `callbackExecutor(ex)`. `build()` throws `IllegalStateException` when neither transport is set.
  - `start()` — `synchronized`, idempotent; attaches the internal dispatch (`setDataListener`) to the input transport **only if one is present** (a send-only client simply has nothing to read).
  - `addFrameListener(FrameListener)` / `removeFrameListener(FrameListener)` — held in a `CopyOnWriteArrayList` (add/remove is rare, dispatch-per-frame is frequent, so lock-free iteration wins).
  - `addBoardListener(Command, int width, int height, TileCodec<T>, BoardListener<T>)` — convenience wiring for a `BoardFrameListener` in one line.
  - `enableIdHandshake(AddressResolver, SequenceValidator)` / `enableIdHandshake(AddressResolver, int minimumSequenceLength)` — wires up the tile-id handshake (see below). Both arguments are required; there is no single-argument overload.
  - `send(Command, CommandType[, byte[] payload])` / `sendFrame(Frame)` — serialized on an internal `writeLock` so concurrent sends from the caller thread and callback-thread game code can never interleave bytes on the wire. Throws `IllegalStateException` when built without an output transport.
  - `sendBoard(Command, CommandType, Board<T>, TileCodec<T>)` — flattens a board and sends it as one frame's payload.
  - `close()` — `synchronized` `AutoCloseable`; detaches the input listener, closes both transports (skipping the output when it is the same object as the input), and shuts down the callback executor **only if it owns it** (a caller-supplied executor is left alone). Each step runs even if an earlier one throws, so one misbehaving `close()` can never leak the other transport's OS handle or the callback thread.

### `gateway.handshake` — device addressing

Models the controller's tile-id assignment handshake:

- **`DeviceAddress(int totalTiles, int tilesPerRow)`** — `forBoard(width, height)` factory; both components must be in `[1, 255]` (this is where the 255-tile protocol ceiling comes from: the total tile count is encoded in a single byte). `toPayload()` gives the 2-byte payload the wire protocol expects for an `ID`/`SET` frame.
- **`AddressResolver`** *(functional interface)* — `DeviceAddress resolveAddress()`, typically `() -> DeviceAddress.forBoard(width, height)`.
- **`SequenceValidator`** *(functional interface)* — `boolean isValid(byte[] payload)`, validating an id assignment the controller reports back.
- **`SequentialIdSequenceValidator`** — the default rule: the constructor requires `minimumLength >= 1`; a payload is valid when it has at least `minimumLength` bytes **and** every byte *except the last one* equals its 1-based position (`payload[i] == i + 1`), mirroring tile ids handed out in row-major order starting at 1.
- **`HandshakeCoordinator`** — a `FrameListener` that ignores every non-`ID` frame; answers `ID`/`CLEAR` ("tell me the board geometry") with `ID`/`SET` carrying the resolved `DeviceAddress`; and validates any other `ID` frame with the `SequenceValidator`, re-sending `ID`/`CLEAR` when the reported assignment is invalid. It only depends on a `Consumer<Frame>` to send frames, so it is unit-testable in isolation. Normally you don't construct this directly — call `TileGatewayClient.enableIdHandshake(...)`, and do so **before** `start()` so the board's very first handshake frames are never dropped.

### `support.error` — localizable exceptions

- **`LocalizableException`** — abstract `RuntimeException` base carrying a stable machine-readable `errorCode()` plus positional `args()` for message-catalog interpolation, in addition to the plain English `getMessage()` diagnostic. The `errorCode` doubles as the `MessageSource` key that `tileboard-app`'s `GlobalExceptionHandler` resolves to a localized (Persian) user-facing message. It lives in this module because `tileboard-serial-protocol` is the most upstream module, so every other module can depend on it without a new shared module.

---

## Full usage example

```java
SerialPortRegistry registry = new JSerialCommPortRegistry();
SerialTransport port = registry.open("COM3", SerialPortConfig.defaults());

int width = 8, height = 8;

TileGatewayClient client = TileGatewayClient.builder()
        .transport(port)             // same transport for in and out (full duplex)
        .build();

// Answer the controller's tile-id addressing handshake automatically.
// NOTE: both arguments are required (there is no single-arg overload).
client.enableIdHandshake(() -> DeviceAddress.forBoard(width, height), /* minimumSequenceLength */ 2);

// React to touch input as decoded Board coordinates, not raw bytes.
client.addBoardListener(Command.DATA_IN, width, height, TileCodec.booleanState(), touchBoard -> {
    for (Position touched : touchBoard.positionsWhere(Boolean.TRUE::equals)) {
        System.out.println("tile touched at " + touched);
    }
});

client.start();
client.send(Command.START, CommandType.SET);

// Light up a tile.
Board<Boolean> lit = new Board<>(width, height, false);
lit.set(0, 0, true);
client.sendBoard(Command.DATA_OUT, CommandType.SET, lit, TileCodec.booleanState());

// ... later ...
client.close();
```

Two-port (half-duplex adapters) variant:

```java
SerialTransport in = registry.open("COM4", SerialPortConfig.defaults());
SerialTransport out = registry.open("COM3", SerialPortConfig.defaults());
TileGatewayClient client = TileGatewayClient.builder()
        .inputTransport(in)
        .outputTransport(out)
        .build();
```

---

## Detecting which tile was touched

This is the single most common integration need, so here is the end-to-end
path spelled out:

1. The controller sends a `DATA_IN` frame whose payload is `width * height`
   bytes, one per tile, `0x00` (not touched) or `0x01` (touched).
2. `DefaultFrameCodec` (used internally by `TileGatewayClient`) turns that
   raw byte stream into a `Frame` — handling partial reads, multiple frames
   in one read, and resyncing after any corrupted bytes.
3. `BoardFrameListener`, registered via `addBoardListener(Command.DATA_IN, ...)`,
   only fires for `DATA_IN` frames and decodes the payload into a
   `Board<Boolean>` using `TileCodec.booleanState()`.
4. `Board.positionsWhere(Boolean.TRUE::equals)` gives you the list of
   `Position(row, col)` for every touched tile.

```java
client.addBoardListener(Command.DATA_IN, width, height, TileCodec.booleanState(), touchBoard -> {
    List<Position> touchedTiles = touchBoard.positionsWhere(Boolean.TRUE::equals);
    // touchedTiles is empty if nothing is touched, or has one entry per touched tile
});
```

No application code has to parse header bytes, compute the payload length,
or index into a flat array by hand.

---

## Exceptions

All exceptions extend `LocalizableException` (which extends
`RuntimeException`) and therefore carry an `errorCode` + `args` in addition
to the English diagnostic message:

| Exception                    | Extends                     | Thrown when                                                                 |
|-------------------------------|-----------------------------|------------------------------------------------------------------------------|
| `ProtocolException`           | `LocalizableException`     | A frame can't be interpreted (unknown `Command`/`CommandType` code, or a `BoardFrameListener` payload/board size mismatch) |
| `InvalidFrameException`       | `ProtocolException`        | An outgoing frame can't be encoded (payload exceeds 65535 bytes) |
| `BoardException`               | `LocalizableException`     | Invalid board geometry, out-of-bounds access, or `fromWireBytes` payload length mismatch |
| `SerialTransportException`     | `LocalizableException`     | Opening, writing to, or reading from a serial transport fails |
| `PortNotFoundException`        | `SerialTransportException` | `SerialPortRegistry.open(...)` is called with an unknown port name |

A `FrameListener`/`BoardListener` that throws is caught and logged per
listener by `TileGatewayClient` — one misbehaving listener never stops
others from being notified or blocks the serial reader thread.

---

## Testing

```bash
mvn test
```

Unit tests use plain JUnit 5 (5.11.0, no mocking framework) with hand-written
fakes, matching the style already used in `BoardTest`,
`DefaultFrameCodecTest`, `TileGatewayClientTest`, `HandshakeCoordinatorTest`
and `BoardFrameListenerTest`.

A hardware-in-the-loop suite (`TileboardHardwareIT`) exercises the protocol
against a real, physically connected controller. It runs via
maven-failsafe in the `hardware-tests` profile (so it needs `verify`, not
`test`), reads its configuration from system properties, and is skipped when
no port is given:

```bash
mvn verify -P hardware-tests -Dtileboard.hardware.port=COM3
```

Optional properties (with defaults): `tileboard.hardware.width=3`,
`tileboard.hardware.height=3`, `tileboard.hardware.baud=115200`,
`tileboard.hardware.stepDelayMs=1000`,
`tileboard.hardware.idTimeoutSeconds=5`.
