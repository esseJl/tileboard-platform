package com.tileboard.engine.feature;


import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.*;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ComboTrackerConcurrencyTest {

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void concurrentHitsNeverLoseOrDuplicateACount() throws InterruptedException {
        ComboTracker tracker = new ComboTracker();
        tracker.setComboTimeout(60_000); // long timeout: this test is about atomicity, not expiry
        int threads = 16;
        int hitsPerThread = 500;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);

        List<? extends Future<?>> futures = IntStream.range(0, threads).mapToObj(i -> pool.submit(() -> {
            ready.countDown();
            await(go);
            for (int j = 0; j < hitsPerThread; j++) tracker.hit();
        })).toList();

        ready.await();
        go.countDown();
        futures.forEach(f -> {
            try {
                f.get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        pool.shutdown();

        // Every hit() call must be reflected exactly once — no lost updates from the compound
        // read-lastHit / reset-combo / increment-combo sequence that existed in the original code.
        assertEquals(threads * hitsPerThread, tracker.current());
        assertEquals(threads * hitsPerThread, tracker.max());
    }
}
