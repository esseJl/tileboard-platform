package com.tileboard.engine.codec;

import com.tileboard.serial.board.Board;
import com.tileboard.serial.protocol.Command;
import com.tileboard.serial.protocol.CommandType;
import com.tileboard.serial.protocol.Frame;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineFrameRouterTest {

    @Test
    void reassemblesChunkedPayloadIntoBoard() {
        List<Board<Boolean>> received = new ArrayList<>();
        EngineFrameRouter router = new EngineFrameRouter(2, 2, received::add, Duration.ofMillis(500));

        router.onFrame(frame(new byte[]{1, 0}));
        assertTrue(received.isEmpty());
        router.onFrame(frame(new byte[]{0, 1}));

        assertEquals(1, received.size());
    }

    @Test
    void staleChunkIsDroppedAfterTimeout() throws InterruptedException {
        List<Board<Boolean>> received = new ArrayList<>();
        EngineFrameRouter router = new EngineFrameRouter(2, 2, received::add, Duration.ofMillis(20));

        router.onFrame(frame(new byte[]{1, 0}));
        Thread.sleep(50);
        router.onFrame(frame(new byte[]{0, 1}));

        assertTrue(received.isEmpty());
    }

    @Test
    void oversizedPayloadIsDiscarded() {
        List<Board<Boolean>> received = new ArrayList<>();
        EngineFrameRouter router = new EngineFrameRouter(2, 2, received::add, Duration.ofMillis(500));

        router.onFrame(frame(new byte[]{1, 1, 1, 1, 1}));
        assertTrue(received.isEmpty());
    }

    private Frame frame(byte[] payload) {
        return Frame.of(Command.DATA_IN, CommandType.GET, payload);
    }
}
