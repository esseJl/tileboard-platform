package com.tileboard.engine.codec;

import com.tileboard.serial.board.Board;
import com.tileboard.serial.protocol.Command;
import com.tileboard.serial.protocol.CommandType;
import com.tileboard.serial.protocol.Frame;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineFrameRouterReassemblyTest {

    @Test
    void staleChunk_isDropped_afterReassemblyTimeout() throws InterruptedException {
        List<Board<Boolean>> received = new CopyOnWriteArrayList<>();
        EngineFrameRouter router = new EngineFrameRouter(4, 4, received::add, Duration.ofMillis(50));

        // نیمی از فریم را می‌فرستیم (۸ از ۱۶ بایت)
        router.onFrame(Frame.of(Command.DATA_IN, CommandType.GET, new byte[8]));
        Thread.sleep(80); // بیشتر از reassemblyTimeout صبر می‌کنیم

        // نیمه‌ی دوم را می‌فرستیم؛ باید به‌عنوان یک تکه‌ی جدید (نه ادامه‌ی قبلی) درنظر گرفته شود
        router.onFrame(Frame.of(Command.DATA_IN, CommandType.GET, new byte[8]));

        assertTrue(received.isEmpty(), "a stale half-frame must be dropped, not silently completed with fresh bytes");
    }

    @Test
    void fullFrame_isDecodedImmediately_evenWithPendingPartialData() {
        List<Board<Boolean>> received = new CopyOnWriteArrayList<>();
        EngineFrameRouter router = new EngineFrameRouter(2, 2, received::add, Duration.ofSeconds(5));

        router.onFrame(Frame.of(Command.DATA_IN, CommandType.GET, new byte[2])); // partial
        router.onFrame(Frame.of(Command.DATA_IN, CommandType.GET, new byte[4])); // full frame -> should reset & decode immediately

        assertEquals(1, received.size());
    }
}
