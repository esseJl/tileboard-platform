package com.tileboard.engine.codec;

import com.tileboard.serial.board.Board;
import com.tileboard.serial.board.TileCodec;
import com.tileboard.serial.gateway.FrameListener;
import com.tileboard.serial.protocol.Command;
import com.tileboard.serial.protocol.Frame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A {@link FrameListener} that intercepts {@code DATA_IN} frames, decodes
 * them into a {@code Board<Boolean>} (touch state) and forwards them to the
 * engine's session fan-out logic.
 *
 * <p>Board dimensions are supplied by the caller (as reported by the gateway).
 * A payload of exactly {@code width * height} bytes is decoded immediately.
 * Shorter payloads are treated as chunks of one board and reassembled; stale
 * or inconsistent chunks are dropped so the stream can always resynchronise.
 */
public final class EngineFrameRouter implements FrameListener {

    private static final Logger log = LoggerFactory.getLogger(EngineFrameRouter.class);

    private final int width;
    private final int height;
    private final int expectedSize;
    private final long reassemblyTimeoutNanos;
    private final Consumer<Board<Boolean>> touchBoardConsumer;

    private final Object lock = new Object();
    private final byte[] buffer;
    private int buffered;
    private long lastChunkNanos;

    public EngineFrameRouter(int width, int height, Consumer<Board<Boolean>> touchBoardConsumer,
                             Duration reassemblyTimeout) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("width and height must be > 0");
        }
        Objects.requireNonNull(reassemblyTimeout, "reassemblyTimeout");
        if (reassemblyTimeout.isNegative() || reassemblyTimeout.isZero()) {
            throw new IllegalArgumentException("reassemblyTimeout must be > 0");
        }
        this.width = width;
        this.height = height;
        this.expectedSize = Math.multiplyExact(width, height);
        this.reassemblyTimeoutNanos = reassemblyTimeout.toNanos();
        this.touchBoardConsumer = Objects.requireNonNull(touchBoardConsumer, "touchBoardConsumer");
        this.buffer = new byte[expectedSize];
    }

    @Override
    public void onFrame(Frame frame) {
        if (frame.command() != Command.DATA_IN) return;
        byte[] payload = frame.payload();
        if (payload.length == 0) return;

        byte[] complete = null;
        synchronized (lock) {
            long now = System.nanoTime();
            if (buffered > 0 && now - lastChunkNanos > reassemblyTimeoutNanos) {
                log.warn("Dropping stale partial board ({} of {} bytes)", buffered, expectedSize);
                buffered = 0;
            }

            if (payload.length == expectedSize) {
                if (buffered > 0) {
                    log.warn("Full frame arrived while {} partial bytes were buffered; dropping partial data", buffered);
                    buffered = 0;
                }
                complete = payload.clone(); // defensive copy
            } else if (payload.length > expectedSize) {
                log.warn("Discarding DATA_IN payload of {} bytes: larger than expected {}x{}={} bytes",
                        payload.length, width, height, expectedSize);
                buffered = 0;
            } else {
                if (buffered + payload.length > expectedSize) {
                    log.warn("Chunk overflows board ({}+{}>{}); resynchronising", buffered, payload.length, expectedSize);
                    buffered = 0;
                }
                System.arraycopy(payload, 0, buffer, buffered, payload.length);
                buffered += payload.length;
                lastChunkNanos = now;
                if (buffered == expectedSize) {
                    complete = buffer.clone();
                    buffered = 0;
                }
            }
        }

        if (complete == null) return; // waiting for more chunks
        try {
            Board<Boolean> board = Board.fromWireBytes(complete, width, height, TileCodec.booleanState());
            touchBoardConsumer.accept(board);
        } catch (RuntimeException e) {
            log.warn("Failed to decode DATA_IN payload of {} bytes", complete.length, e);
        }
    }
}