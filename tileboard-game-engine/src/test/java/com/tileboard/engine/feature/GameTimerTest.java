package com.tileboard.engine.feature;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GameTimerTest {

    @Test
    void elapsedIsZeroBeforeStart() {
        assertEquals(Duration.ZERO, new GameTimer().elapsed());
    }

    @Test
    void stopFreezesElapsed() throws InterruptedException {
        GameTimer timer = new GameTimer();
        timer.start();
        Thread.sleep(30);
        timer.stop();
        Duration frozen = timer.elapsed();
        Thread.sleep(30);
        assertEquals(frozen, timer.elapsed());
    }

    @Test
    void countdownFiresCallbackOnlyOnce() {
        GameTimer timer = new GameTimer();
        AtomicInteger fired = new AtomicInteger();
        timer.startCountdown(Duration.ofMillis(1), fired::incrementAndGet);
        Awaitility.await().until(timer::isExpired);
        timer.checkExpiry();
        timer.checkExpiry();
        assertEquals(1, fired.get());
    }

    @Test
    void remainingNeverNegative() {
        GameTimer timer = new GameTimer();
        timer.startCountdown(Duration.ofMillis(1), () -> {
        });
        Awaitility.await().until(timer::isExpired);
        assertEquals(Duration.ZERO, timer.remaining());
    }
}
