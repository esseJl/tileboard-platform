package com.tileboard.serial.gateway;

import com.tileboard.serial.board.Board;
import com.tileboard.serial.board.Position;
import com.tileboard.serial.board.TileCodec;
import com.tileboard.serial.exception.ProtocolException;
import com.tileboard.serial.protocol.Command;
import com.tileboard.serial.protocol.CommandType;
import com.tileboard.serial.protocol.Frame;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoardFrameListenerTest {

    @Test
    void decodesAMatchingFrameIntoABoard() {
        List<Board<Boolean>> received = new ArrayList<>();
        BoardFrameListener<Boolean> listener = new BoardFrameListener<>(
                Command.DATA_IN, 2, 2, TileCodec.booleanState(), received::add);

        listener.onFrame(Frame.of(Command.DATA_IN, CommandType.SET, new byte[]{0, 1, 0, 1}));

        assertEquals(1, received.size());
        assertEquals(
                List.of(new Position(0, 1), new Position(1, 1)),
                received.get(0).positionsWhere(Boolean.TRUE::equals));
    }

    @Test
    void ignoresFramesForOtherCommands() {
        List<Board<Boolean>> received = new ArrayList<>();
        BoardFrameListener<Boolean> listener = new BoardFrameListener<>(
                Command.DATA_IN, 2, 2, TileCodec.booleanState(), received::add);

        listener.onFrame(Frame.of(Command.STOP, CommandType.SET));

        assertTrue(received.isEmpty());
    }

    @Test
    void wrapsAPayloadSizeMismatchInAProtocolException() {
        BoardFrameListener<Boolean> listener = new BoardFrameListener<>(
                Command.DATA_IN, 2, 2, TileCodec.booleanState(), board -> { });

        assertThrows(ProtocolException.class,
                () -> listener.onFrame(Frame.of(Command.DATA_IN, CommandType.SET, new byte[]{0, 1})));
    }

    @Test
    void rejectsNonPositiveDimensions() {
        assertThrows(IllegalArgumentException.class,
                () -> new BoardFrameListener<>(Command.DATA_IN, 0, 2, TileCodec.booleanState(), board -> { }));
    }
}
