package com.tileboard.serial.gateway;

import com.tileboard.serial.board.Board;
import com.tileboard.serial.board.TileCodec;
import com.tileboard.serial.exception.BoardException;
import com.tileboard.serial.exception.ProtocolException;
import com.tileboard.serial.protocol.Command;
import com.tileboard.serial.protocol.Frame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * A {@link FrameListener} that only reacts to one {@link Command}, decodes
 * that frame's payload into a {@link Board} using a supplied {@link TileCodec},
 * and forwards the result to a {@link BoardListener}. Frames for any other
 * command are ignored.
 *
 * <p>This is what turns a raw {@code DATA_IN} frame - "here are N bytes" -
 * into "here is which tile was touched", without every application having
 * to re-derive that translation:
 *
 * <pre>{@code
 * client.addBoardListener(Command.DATA_IN, width, height, TileCodec.booleanState(),
 *         touchBoard -> {
 *             for (Position touched : touchBoard.positionsWhere(Boolean.TRUE::equals)) {
 *                 System.out.println("tile touched at " + touched);
 *             }
 *         });
 * }</pre>
 *
 * <p>Register it via {@link TileGatewayClient#addBoardListener(Command, int, int, TileCodec, BoardListener)}
 * rather than constructing it directly, unless finer control (e.g. removing
 * just this listener later) is needed.
 *
 * @param <T> the application's tile type
 */
public final class BoardFrameListener<T> implements FrameListener {

    private static final Logger log = LoggerFactory.getLogger(BoardFrameListener.class);

    private final Command command;
    private final int width;
    private final int height;
    private final TileCodec<T> codec;
    private final BoardListener<T> boardListener;

    public BoardFrameListener(Command command, int width, int height, TileCodec<T> codec, BoardListener<T> boardListener) {
        this.command = Objects.requireNonNull(command, "command");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.boardListener = Objects.requireNonNull(boardListener, "boardListener");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("width and height must both be > 0, got width=" + width + ", height=" + height);
        }
        this.width = width;
        this.height = height;
        log.debug("BoardFrameListener registered for {} ({}x{})", command, width, height);
    }

    @Override
    public void onFrame(Frame frame) {
        if (frame.command() != command) {
            log.trace("Ignoring {} (listening for {})", frame.command(), command);
            return;
        }
        Board<T> board;
        try {
            board = Board.fromWireBytes(frame.payload(), width, height, codec);
        } catch (BoardException e) {
            throw new ProtocolException(
                    "Received a " + command + " frame whose payload doesn't match the configured "
                            + width + "x" + height + " board: " + e.getMessage(), e);
        }
        log.debug("Decoded {} board frame ({} bytes payload) into a {}x{} board", command, frame.payloadLength(), width, height);
        boardListener.onBoard(board);
    }
}
