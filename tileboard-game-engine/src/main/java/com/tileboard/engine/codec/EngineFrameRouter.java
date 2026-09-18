package com.tileboard.engine.codec;

import com.tileboard.serial.board.Board;
import com.tileboard.serial.board.TileCodec;
import com.tileboard.serial.gateway.FrameListener;
import com.tileboard.serial.protocol.Command;
import com.tileboard.serial.protocol.Frame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * A {@link FrameListener} that intercepts {@code DATA_IN} frames, decodes
 * them into a {@code Board<Boolean>} (touch state) and forwards to the
 * engine's session fan-out logic.
 *
 * <p>Board dimensions are read from the frame payload length, so the router
 * works for any board geometry without configuration.
 */
public final class EngineFrameRouter implements FrameListener {

    private static final Logger log = LoggerFactory.getLogger(EngineFrameRouter.class);

    private final int width;
    private final int height;
    private final Consumer<Board<Boolean>> touchBoardConsumer;

    /**
     * @param width  actual physical board width (columns), as reported by the connected gateway
     * @param height actual physical board height (rows), as reported by the connected gateway
     */
    public EngineFrameRouter(int width, int height, Consumer<Board<Boolean>> touchBoardConsumer) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("width and height must be > 0");
        }
        this.width = width;
        this.height = height;
        this.touchBoardConsumer = touchBoardConsumer;
    }

    @Override
    public void onFrame(Frame frame) {
        if (frame.command() != Command.DATA_IN) return;

        byte[] payload = frame.payload();
        if (payload.length == 0) return;

        if (payload.length != width * height) {
            log.warn("Discarding DATA_IN payload of {} bytes: expected {}x{}={} bytes for the connected board",
                    payload.length, width, height, width * height);
            return;
        }

        try {
            Board<Boolean> board = Board.fromWireBytes(payload, width, height, TileCodec.booleanState());
            touchBoardConsumer.accept(board);
        } catch (RuntimeException e) {
            log.warn("Failed to decode DATA_IN payload of {} bytes", payload.length, e);
        }
    }
}