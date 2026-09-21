package com.tileboard.engine.feature;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameTimerConcurrencyTest {

    @Test
    void onExpireCallback_firesExactlyOnce_underConcurrentCheckExpiry() throws InterruptedException {
        GameTimer timer = new GameTimer();
        AtomicInteger firedCount = new AtomicInteger(0);
        timer.startCountdown(Duration.ofMillis(1), firedCount::incrementAndGet);

        // منتظر می‌مانیم زمان واقعاً منقضی شود
        Awaitility.await().atMost(Duration.ofSeconds(1)).until(timer::isExpired);

        int threads = 64;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException ignored) {
                }
                timer.checkExpiry();
            });
        }

        ready.await();
        go.countDown(); // همه‌ی ترد‌ها را دقیقاً هم‌زمان آزاد می‌کنیم تا race را تحریک کنیم
        pool.shutdown();
        assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS));

        assertEquals(1, firedCount.get(), "onExpire must fire exactly once even under concurrent checkExpiry() calls");
    }
}
