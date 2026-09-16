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
is an **optional** dependency: you only need it on your classpath if you use
the ready-made `JSerialCommPortRegistry`/`JSerialCommTransport`. If you talk
to the device through some other means, implement `SerialTransport` yourself
and you never need jSerialComm at all.

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
corrupt the protocol.

For `DATA_IN`/`DATA_OUT` frames the payload is a flat, row-major array of
tiles: one byte per tile, `width * height` bytes total, left-to-right,
top-to-bottom. `Board` (below) is what turns that flat array into 2D
coordinates and back.

---

## Package overview

| Package                          | Responsibility                                                                 |
|-----------------------------------|--------------------------------------------------------------------------------|
| `com.tileboard.serial.protocol`   | Frame model, framing/encoding/decoding (`Command`, `CommandType`, `Frame`, `FrameEncoder`, `FrameDecoder`, `DefaultFrameCodec`, `ProtocolConstants`) |
| `com.tileboard.serial.board`      | The application-facing tile grid (`Board`, `Position`, `TileCodec`, `TileEncoder`, `TileDecoder`) |
| `com.tileboard.serial.transport`  | Abstraction over a physical serial connection (`SerialTransport`, `SerialPortRegistry`, `SerialPortConfig`, `SerialPortInfo`, `Parity`, `DataListener`) + a ready `jserialcomm` implementation |
| `com.tileboard.serial.gateway`    | The high-level client apps actually use (`TileGatewayClient`, `FrameListener`, `BoardListener`, `BoardFrameListener`) |
| `com.tileboard.serial.gateway.handshake` | The tile-id addressing handshake (`AddressResolver`, `DeviceAddress`, `HandshakeCoordinator`, `SequenceValidator`, `SequentialIdSequenceValidator`) |
| `com.tileboard.serial.exception`  | The library's exception hierarchy |

---

## Core concepts

### `protocol` — frames and commands

- **`Frame`** — an immutable, already-validated `(Command, CommandType, payload)` triple. This is the unit everything above the codec works with; nobody above `DefaultFrameCodec` touches raw bytes.
- **`FrameEncoder`** *(functional interface)* — `byte[] encode(Frame frame)`.
- **`FrameDecoder`** *(functional interface)* — `List<Frame> decode(byte[] chunk)`. Stateful: implementations buffer bytes across calls because a serial read can contain half a frame, several frames, or the tail of a previously started one.
- **`DefaultFrameCodec`** — the reference implementation of both, matching the layout above. It resynchronizes automatically on the next `START_BYTE` if bytes don't form a valid frame (a stray `0xFC` in the middle of noise doesn't wedge it), and buffers correctly across split reads. One instance per physical input stream.
- **`ProtocolConstants`** — the fixed wire values (`START_BYTE`, `SEPARATOR_BYTE`, `END_BYTE`, frame overhead, max payload length).

```java
DefaultFrameCodec codec = new DefaultFrameCodec();

byte[] wire = codec.encode(Frame.of(Command.START, CommandType.SET));
// ... send wire bytes on the wire ...

List<Frame> frames = codec.decode(bytesJustRead); // may be empty, 1, or several frames
```

### `board` — the tile grid

- **`Position`** — a zero-based `(row, col)` record.
- **`Board<T>`** — a mutable `height x width` grid over *any* application tile type `T` (a color enum, a boolean, a custom record — the library never dictates what a "tile" is).
  - `get`/`set` by `(row, col)` or `Position`
  - `fill(T tile)`, `copy()`, `forEach(TileConsumer<T>)`
  - `toWireBytes(TileCodec<T>)` — flattens the board into the row-major bytes the controller expects
  - `static Board<T> fromWireBytes(byte[] flat, int width, int height, TileCodec<T> codec)` — the reverse; throws `BoardException` if `flat.length != width * height`
  - `positionsWhere(Predicate<T> predicate)` — every `Position` whose tile matches the predicate, in row-major order. This is the direct answer to *"which tile(s) changed?"* — e.g. `touchBoard.positionsWhere(Boolean.TRUE::equals)` to find touched tiles.
- **`TileEncoder<T>`** / **`TileDecoder<T>`** *(functional interfaces)* — convert one tile to/from its wire byte.
- **`TileCodec<T>`** — pairs an encoder and decoder. Ready-made factories:
  - `TileCodec.identity()` — for boards that already work in raw bytes.
  - `TileCodec.booleanState()` / `TileCodec.booleanState(byte off, byte on)` — the common single-flag-byte-per-tile convention (`0` = off/untouched, non-zero = on/touched). This is exactly what you want for touch/press input.

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

- **`SerialTransport`** — an abstraction over a single, already-open connection: `write(byte[])`, `setDataListener(DataListener)`, `isOpen()`, `portName()`, `close()`. Implement this yourself for a hardware library other than jSerialComm, or for a test double — nothing above this layer cares which one is used.
- **`SerialPortRegistry`** — discovers ports (`listPorts()`) and opens them (`open(String portName, SerialPortConfig config)`), throwing `PortNotFoundException`/`SerialTransportException` as appropriate.
- **`SerialPortConfig`** — baud rate, data bits, stop bits, `Parity`, read/write timeouts. `SerialPortConfig.defaults()` gives `115200-8-N-1`, the settings the tile controller firmware expects.
- **`JSerialCommPortRegistry`** / **`JSerialCommTransport`** — the ready-made implementation backed by [jSerialComm](https://fazecast.github.io/jSerialComm/). This is the only part of the library that imports jSerialComm types.

```java
SerialPortRegistry registry = new JSerialCommPortRegistry();
registry.listPorts().forEach(p -> System.out.println(p.systemName() + " - " + p.description()));

SerialTransport port = registry.open("COM3", SerialPortConfig.defaults());
```

### `gateway` — the high-level client

- **`FrameListener`** *(functional interface)* — `void onFrame(Frame frame)`. Notified for every complete frame received.
- **`BoardListener<T>`** *(functional interface)* — `void onBoard(Board<T> board)`. The board-shaped counterpart of `FrameListener`.
- **`BoardFrameListener<T>`** — a `FrameListener` adapter that only reacts to one `Command`, decodes that frame's payload into a `Board<T>` with a supplied `TileCodec<T>`, and forwards it to a `BoardListener<T>`. Throws `ProtocolException` (wrapping the underlying `BoardException`) if a frame's payload doesn't match the configured board size — e.g. the device reports a different tile count than expected.
- **`TileGatewayClient`** — the class applications actually use day to day. It owns an input transport, an output transport (can be the same object for a full-duplex port), and a `FrameEncoder`/`FrameDecoder` pair (defaults to `DefaultFrameCodec` if you don't supply one). It turns incoming bytes into `Frame` callbacks on any number of registered listeners, running them on a configurable `Executor` (a dedicated daemon thread by default) so a slow listener never blocks the serial reader.

  Key methods:
  - `builder()...build()` — see the full example below.
  - `start()` — begins dispatching incoming bytes.
  - `addFrameListener(FrameListener)` / `removeFrameListener(FrameListener)`
  - `addBoardListener(Command, int width, int height, TileCodec<T>, BoardListener<T>)` — convenience wiring for a `BoardFrameListener` in one line.
  - `enableIdHandshake(AddressResolver, SequenceValidator)` / `enableIdHandshake(AddressResolver, int minimumSequenceLength)` — wires up the tile-id handshake (see below).
  - `send(Command, CommandType[, byte[] payload])` / `sendFrame(Frame)`
  - `sendBoard(Command, CommandType, Board<T>, TileCodec<T>)` — flattens a board and sends it as one frame's payload.
  - `close()` — `AutoCloseable`; releases transports and the callback executor.

### `gateway.handshake` — device addressing

Models the controller's tile-id assignment handshake:

- **`DeviceAddress(int totalTiles, int tilesPerRow)`** — `forBoard(width, height)` factory; `toPayload()` gives the 2-byte payload the wire protocol expects.
- **`AddressResolver`** *(functional interface)* — `DeviceAddress resolveAddress()`, typically `() -> DeviceAddress.forBoard(width, height)`.
- **`SequenceValidator`** *(functional interface)* — `boolean isValid(byte[] payload)`, validating an id assignment the controller reports back.
- **`SequentialIdSequenceValidator`** — the default rule: payload has at least `minimumLength` bytes, and every byte except the last equals its 1-based position (ids handed out in row-major order starting at 1).
- **`HandshakeCoordinator`** — a `FrameListener` that answers `ID`/`CLEAR` with the resolved `DeviceAddress`, and re-sends `ID`/`CLEAR` if a reported assignment fails validation. Normally you don't construct this directly — call `TileGatewayClient.enableIdHandshake(...)`.

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

| Exception                    | Extends              | Thrown when                                                                 |
|-------------------------------|-----------------------|------------------------------------------------------------------------------|
| `ProtocolException`           | `RuntimeException`   | A frame can't be interpreted (unknown `Command`/`CommandType` code, or a `BoardFrameListener` payload/board size mismatch) |
| `InvalidFrameException`       | `ProtocolException`  | An outgoing frame can't be encoded (payload too large) or incoming bytes are irrecoverably malformed |
| `BoardException`               | `RuntimeException`   | Invalid board geometry, out-of-bounds access, or `fromWireBytes` payload length mismatch |
| `SerialTransportException`     | `RuntimeException`   | Opening, writing to, or reading from a serial transport fails |
| `PortNotFoundException`        | `SerialTransportException` | `SerialPortRegistry.open(...)` is called with an unknown port name |

A `FrameListener`/`BoardListener` that throws is caught and logged per
listener by `TileGatewayClient` — one misbehaving listener never stops
others from being notified or blocks the serial reader thread.

---

## Testing

```bash
mvn test
```

Unit tests use plain JUnit 5 (no mocking framework) with hand-written fakes
(`FakeTransport`, in-memory `Consumer<Frame>` collectors, etc.), matching the
style already used in `BoardTest`, `DefaultFrameCodecTest` and
`HandshakeCoordinatorTest`.

A hardware-in-the-loop suite (`TileboardHardwareIT`) exercises every command
against a real, physically connected controller. It is tagged `hardware` and
excluded from the default `mvn test` run; opt in with:

```bash
mvn test -Phardware-tests -Dtileboard.hardware.port=COM3
```
