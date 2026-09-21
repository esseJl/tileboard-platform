package com.tileboard.engine.event;

import com.tileboard.engine.core.SessionSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GameEventBusImplBackpressureTest {

    @Test
    void aStuckBlockSubscriberCannotFreezeThePublisherForever() {
        GameEventBusImpl bus = new GameEventBusImpl(1, GameEventBusImpl.OverflowPolicy.BLOCK, Duration.ofMillis(100));
        CountDownLatch neverDrains = new CountDownLatch(1);

        bus.subscribe(event -> {
            try {
                neverDrains.await(10, TimeUnit.SECONDS); // شبیه‌سازی کلاینت گیرکرده/قطع‌شده
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, null, null, 1, GameEventBusImpl.OverflowPolicy.BLOCK);

        long start = System.nanoTime();
        for (int i = 0; i < 5; i++) {
            bus.publish(GameEvent.of(GameEventType.TICK, "s1", "g1",
                    new SessionSnapshot(Map.of(), 1, "RUNNING", 0)));
        }
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertTrue(elapsedMs < 3000, "publish() blocked for " + elapsedMs + "ms");
        neverDrains.countDown();
        bus.close();
    }
}
