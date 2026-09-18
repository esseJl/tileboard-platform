package com.tileboard.engine.codec;

import com.tileboard.serial.board.Board;
import com.tileboard.serial.protocol.Command;
import com.tileboard.serial.protocol.CommandType;
import com.tileboard.serial.protocol.Frame;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class EngineFrameRouterTest {
    @Test
    void validatesDimensionsAndRoutesOnlyValidDataInFrames() {
        assertThrows(IllegalArgumentException.class, () -> new EngineFrameRouter(0, 2, b -> {}));
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Board<Boolean>> boardRef = new AtomicReference<>();
        EngineFrameRouter router = new EngineFrameRouter(2, 2, b -> { calls.incrementAndGet(); boardRef.set(b); });

        router.onFrame(Frame.of(Command.DATA_OUT, CommandType.SET, new byte[]{1,1,1,1}));
        router.onFrame(Frame.of(Command.DATA_IN, CommandType.SET, new byte[0]));
        router.onFrame(Frame.of(Command.DATA_IN, CommandType.SET, new byte[]{1,0,1}));
        assertEquals(0, calls.get());

        router.onFrame(Frame.of(Command.DATA_IN, CommandType.SET, new byte[]{1,0,0,1}));
        assertEquals(1, calls.get());
        Board<Boolean> board = boardRef.get();
        assertTrue(board.get(0,0));
        assertFalse(board.get(0,1));
        assertTrue(board.get(1,1));
    }
}
