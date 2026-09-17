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

    private final Consumer<Board<Boolean>> touchBoardConsumer;

    public EngineFrameRouter(Consumer<Board<Boolean>> touchBoardConsumer) {
        this.touchBoardConsumer = touchBoardConsumer;
    }

    @Override
    public void onFrame(Frame frame) {
        if (frame.command() != Command.DATA_IN) return;

        byte[] payload = frame.payload();
        if (payload.length == 0) return;

        // Derive dimensions: treat payload as a square when possible,
        // otherwise assume width == payload.length (single row).
        int side = (int) Math.sqrt(payload.length);
        int w, h;
        if (side * side == payload.length) {
            w = h = side;
        } else {
            w = payload.length;
            h = 1;
        }

        try {
            Board<Boolean> board = Board.fromWireBytes(payload, w, h, TileCodec.booleanState());
            touchBoardConsumer.accept(board);
        } catch (RuntimeException e) {
            log.warn("Failed to decode DATA_IN payload of {} bytes", payload.length, e);
        }
    }
}