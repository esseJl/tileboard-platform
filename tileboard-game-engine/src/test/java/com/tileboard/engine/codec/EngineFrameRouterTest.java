package com.tileboard.engine.codec;

import com.tileboard.serial.board.Board;
import com.tileboard.serial.protocol.Command;
import com.tileboard.serial.protocol.CommandType;
import com.tileboard.serial.protocol.Frame;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class EngineFrameRouterTest {

    @Test
    void mutatingThePayloadArrayAfterDeliveryDoesNotCorruptTheDecodedBoard() {
        AtomicReference<Board<Boolean>> received = new AtomicReference<>();
        EngineFrameRouter router = new EngineFrameRouter(2, 2, received::set);

        byte[] payload = {1, 0, 0, 1}; // full 2x2 frame
        router.onFrame(Frame.of(Command.DATA_IN, CommandType.GET, payload));

        // Simulate the transport reusing/zeroing its internal buffer right after onFrame() returns.
        java.util.Arrays.fill(payload, (byte) 0);

        assertNotNull(received.get());
        assertTrue(received.get().get(0, 0)); // must reflect the ORIGINAL bytes, proving a defensive copy was made
    }

    @Test
    void chunksAreReassembledInOrder() {
        AtomicReference<Board<Boolean>> received = new AtomicReference<>();
        EngineFrameRouter router = new EngineFrameRouter(4, 1, received::set);

        router.onFrame(Frame.of(Command.DATA_IN, CommandType.GET, new byte[]{1, 0}));
        assertNull(received.get(), "should still be waiting for the rest of the board");
        router.onFrame(Frame.of(Command.DATA_IN, CommandType.GET, new byte[]{0, 1}));

        assertNotNull(received.get());
        assertTrue(received.get().get(0, 0));
        assertTrue(received.get().get(0, 3));
    }

    @Test
    void staleChunkIsDroppedAfterReassemblyTimeout() throws InterruptedException {
        AtomicReference<Board<Boolean>> received = new AtomicReference<>();
        EngineFrameRouter router = new EngineFrameRouter(4, 1, received::set, Duration.ofMillis(50));

        router.onFrame(Frame.of(Command.DATA_IN, CommandType.GET, new byte[]{1, 0}));
        Thread.sleep(80);
        router.onFrame(Frame.of(Command.DATA_IN, CommandType.GET, new byte[]{1, 1})); // treated as a fresh 2-byte chunk

        assertNull(received.get(), "stale partial data must be dropped, not merged with new data");
    }
}