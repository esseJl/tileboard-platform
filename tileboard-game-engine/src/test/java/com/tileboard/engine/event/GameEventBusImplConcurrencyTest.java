package com.tileboard.engine.event;

import com.tileboard.engine.core.SessionSnapshot;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameEventBusImplConcurrencyTest {

    @Test
    void dropOldestPolicy_neverLosesMoreEventsThanCapacityAllows() throws InterruptedException {
        GameEventBusImpl bus = new GameEventBusImpl(8, EventOverflowPolicy.DROP_OLDEST);
        AtomicInteger delivered = new AtomicInteger(0);
        CountDownLatch listenerBlock = new CountDownLatch(1);

        // شنونده‌ی عمداً کند تا صف پر شود و مسیر drop فعال شود
        bus.subscribe(event -> {
            delivered.incrementAndGet();
            try {
                listenerBlock.await(50, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
            }
        });

        int publishers = 16;
        int perPublisher = 200;
        ExecutorService pool = Executors.newFixedThreadPool(publishers);
        CountDownLatch done = new CountDownLatch(publishers);

        for (int p = 0; p < publishers; p++) {
            pool.submit(() -> {
                for (int i = 0; i < perPublisher; i++) {
                    bus.publish(GameEvent.of(GameEventType.TICK, "session-1", "game-1",
                            new SessionSnapshot(Map.of(), 1, "RUNNING", 0)));
                }
                done.countDown();
            });
        }
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        long totalPublished = (long) publishers * perPublisher;
        // منتظر می‌مانیم صف باقی‌مانده هم پردازش شود
        Awaitility.await().atMost(Duration.ofSeconds(5))
                .until(() -> delivered.get() + bus.droppedEventCount() >= totalPublished);

        assertEquals(totalPublished, delivered.get() + bus.droppedEventCount(),
                "delivered + dropped must exactly account for every published event — no silent loss beyond capacity");
    }
}
