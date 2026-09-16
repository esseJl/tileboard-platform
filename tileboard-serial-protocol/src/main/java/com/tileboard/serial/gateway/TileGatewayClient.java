package com.tileboard.serial.gateway;

import com.tileboard.serial.board.Board;
import com.tileboard.serial.board.TileCodec;
import com.tileboard.serial.gateway.handshake.AddressResolver;
import com.tileboard.serial.gateway.handshake.HandshakeCoordinator;
import com.tileboard.serial.gateway.handshake.SequenceValidator;
import com.tileboard.serial.gateway.handshake.SequentialIdSequenceValidator;
import com.tileboard.serial.protocol.Command;
import com.tileboard.serial.protocol.CommandType;
import com.tileboard.serial.protocol.DefaultFrameCodec;
import com.tileboard.serial.protocol.Frame;
import com.tileboard.serial.protocol.FrameDecoder;
import com.tileboard.serial.protocol.FrameEncoder;
import com.tileboard.serial.transport.SerialTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * High level, transport-agnostic client for talking to the tile controller.
 *
 * <p>A client owns an input {@link SerialTransport} (bytes come in), an
 * output {@link SerialTransport} (bytes go out) - which may be the very same
 * object for a single full-duplex port - and a {@link FrameEncoder}/{@link FrameDecoder}
 * pair. It turns raw bytes into {@link Frame} callbacks for any number of
 * registered {@link FrameListener}s, and turns outgoing {@link Frame}s /
 * {@link Board}s back into bytes.
 *
 * <p>Instances are safe to share across threads: sending is synchronous on
 * the calling thread, and listener notification runs on the configured
 * callback {@link Executor} (a dedicated single daemon thread by default) so
 * a slow or misbehaving listener never blocks the serial reader thread.
 *
 * <pre>{@code
 * SerialPortRegistry registry = new JSerialCommPortRegistry();
 * SerialTransport port = registry.open("COM3", SerialPortConfig.defaults());
 *
 * TileGatewayClient client = TileGatewayClient.builder()
 *         .transport(port)
 *         .build();
 *
 * client.enableIdHandshake(() -> DeviceAddress.forBoard(8, 8));
 * client.addFrameListener(frame -> System.out.println("received " + frame));
 * client.start();
 *
 * Board<TileColor> board = new Board<>(8, 8, TileColor.OFF);
 * board.set(0, 0, TileColor.RED);
 * client.sendBoard(Command.DATA_OUT, CommandType.SET, board, tileColorCodec);
 * }</pre>
 */
public final class TileGatewayClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TileGatewayClient.class);

    private final SerialTransport inputTransport;
    private final SerialTransport outputTransport;
    private final FrameEncoder encoder;
    private final FrameDecoder decoder;
    private final Executor callbackExecutor;
    private final boolean ownsExecutor;
    private final List<FrameListener> listeners = new CopyOnWriteArrayList<>();
    /** Serializes writes to the output transport: send()/sendBoard() are called from
     *  both the caller's own thread and, indirectly, from callback-thread game code
     *  (via GameContext.publish); without this, two concurrent writes could interleave
     *  their bytes on the wire and corrupt both frames. */
    private final Object writeLock = new Object();

    private volatile boolean started = false;

    private TileGatewayClient(Builder builder, Executor callbackExecutor, boolean ownsExecutor) {
        this.inputTransport = builder.inputTransport;
        this.outputTransport = builder.outputTransport;
        this.encoder = builder.encoder;
        this.decoder = builder.decoder;
        this.callbackExecutor = callbackExecutor;
        this.ownsExecutor = ownsExecutor;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Starts dispatching incoming bytes from the input transport to registered listeners. */
    public synchronized void start() {
        if (started) {
            return;
        }
        if (inputTransport != null) {
            inputTransport.setDataListener(this::handleIncomingBytes);
        }
        started = true;
    }

    public void addFrameListener(FrameListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Convenience wiring for reacting to a board-shaped command (typically
     * {@code DATA_IN}) as a decoded {@link Board} rather than a raw payload:
     * registers a {@link BoardFrameListener} that only fires for {@code command}
     * and decodes its payload with {@code codec} before calling {@code boardListener}.
     *
     * <pre>{@code
     * client.addBoardListener(Command.DATA_IN, width, height, TileCodec.booleanState(),
     *         touchBoard -> touchBoard.positionsWhere(Boolean.TRUE::equals)
     *                 .forEach(pos -> System.out.println("touched " + pos)));
     * }</pre>
     */
    public <T> void addBoardListener(Command command, int width, int height, TileCodec<T> codec, BoardListener<T> boardListener) {
        addFrameListener(new BoardFrameListener<>(command, width, height, codec, boardListener));
    }

    public void removeFrameListener(FrameListener listener) {
        listeners.remove(listener);
    }

    /**
     * Convenience wiring for the tile-id assignment handshake: registers a
     * {@link HandshakeCoordinator} that answers {@code ID}/{@code CLEAR}
     * requests using {@code addressResolver} and validates reported
     * assignments with {@code sequenceValidator}.
     */
    public void enableIdHandshake(AddressResolver addressResolver, SequenceValidator sequenceValidator) {
        addFrameListener(new HandshakeCoordinator(addressResolver, sequenceValidator, this::sendFrame));
    }

    /** Same as {@link #enableIdHandshake(AddressResolver, SequenceValidator)} using the default sequential validator. */
    public void enableIdHandshake(AddressResolver addressResolver, int minimumSequenceLength) {
        enableIdHandshake(addressResolver, new SequentialIdSequenceValidator(minimumSequenceLength));
    }

    public void sendFrame(Frame frame) {
        requireOutputTransport();
        byte[] wireBytes = encoder.encode(frame);
        synchronized (writeLock) {
            outputTransport.write(wireBytes);
        }
    }

    public void send(Command command, CommandType commandType, byte[] payload) {
        sendFrame(Frame.of(command, commandType, payload));
    }

    public void send(Command command, CommandType commandType) {
        sendFrame(Frame.of(command, commandType));
    }

    /** Flattens {@code board} with {@code codec} and sends it as the payload of a single frame. */
    public <T> void sendBoard(Command command, CommandType commandType, Board<T> board, TileCodec<T> codec) {
        sendFrame(Frame.of(command, commandType, board.toWireBytes(codec)));
    }

    private void handleIncomingBytes(byte[] data) {
        List<Frame> frames;
        try {
            frames = decoder.decode(data);
        } catch (RuntimeException e) {
            log.warn("Failed to decode incoming serial data, dropping {} byte(s)", data.length, e);
            return;
        }
        log.debug("Decoded {} frame(s) from {} incoming byte(s)", frames.size(), data.length);
        for (Frame frame : frames) {
            dispatch(frame);
        }
    }

    private void dispatch(Frame frame) {
        log.debug("Dispatching {} to {} registered listener(s)", frame, listeners.size());
        for (FrameListener listener : listeners) {
            callbackExecutor.execute(() -> {
                try {
                    listener.onFrame(frame);
                } catch (RuntimeException e) {
                    log.warn("Frame listener {} threw while handling {}", listener, frame, e);
                }
            });
        }
    }

    private void requireOutputTransport() {
        if (outputTransport == null) {
            throw new IllegalStateException("This TileGatewayClient was built without an output transport");
        }
    }

    @Override
    public synchronized void close() {
        // Each cleanup step runs even if an earlier one throws - otherwise a
        // single misbehaving transport.close() would leak the other
        // transport's OS handle and/or the callback executor thread forever.
        try {
            if (inputTransport != null) {
                try {
                    inputTransport.setDataListener(null);
                } finally {
                    inputTransport.close();
                }
            }
        } catch (RuntimeException e) {
            log.warn("Failed to close input transport", e);
        }
        try {
            if (outputTransport != null && outputTransport != inputTransport) {
                outputTransport.close();
            }
        } catch (RuntimeException e) {
            log.warn("Failed to close output transport", e);
        }
        try {
            if (ownsExecutor && callbackExecutor instanceof ExecutorService executorService) {
                executorService.shutdown();
            }
        } finally {
            started = false;
        }
    }

    public static final class Builder {
        private SerialTransport inputTransport;
        private SerialTransport outputTransport;
        private FrameEncoder encoder;
        private FrameDecoder decoder;
        private Executor callbackExecutor;

        /** Use one transport for both directions (a single full-duplex serial port). */
        public Builder transport(SerialTransport transport) {
            this.inputTransport = transport;
            this.outputTransport = transport;
            return this;
        }

        public Builder inputTransport(SerialTransport inputTransport) {
            this.inputTransport = inputTransport;
            return this;
        }

        public Builder outputTransport(SerialTransport outputTransport) {
            this.outputTransport = outputTransport;
            return this;
        }

        /** Convenience for supplying a single object that acts as both encoder and decoder, e.g. {@link DefaultFrameCodec}. */
        public <C extends FrameEncoder & FrameDecoder> Builder frameCodec(C codec) {
            this.encoder = codec;
            this.decoder = codec;
            return this;
        }

        public Builder frameEncoder(FrameEncoder encoder) {
            this.encoder = encoder;
            return this;
        }

        public Builder frameDecoder(FrameDecoder decoder) {
            this.decoder = decoder;
            return this;
        }

        /** Thread(s) on which {@link FrameListener} callbacks run. Defaults to a dedicated daemon thread. */
        public Builder callbackExecutor(Executor callbackExecutor) {
            this.callbackExecutor = callbackExecutor;
            return this;
        }

        public TileGatewayClient build() {
            if (inputTransport == null && outputTransport == null) {
                throw new IllegalStateException("At least one of inputTransport/outputTransport (or transport(...)) is required");
            }
            if (encoder == null || decoder == null) {
                DefaultFrameCodec defaultCodec = new DefaultFrameCodec();
                if (encoder == null) {
                    encoder = defaultCodec;
                }
                if (decoder == null) {
                    decoder = defaultCodec;
                }
            }

            boolean ownsExecutor = callbackExecutor == null;
            Executor executor = callbackExecutor != null ? callbackExecutor : Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "tileboard-gateway-callback");
                thread.setDaemon(true);
                return thread;
            });

            return new TileGatewayClient(this, executor, ownsExecutor);
        }
    }
}
