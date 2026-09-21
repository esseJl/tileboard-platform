package com.tileboard.engine.feature;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A wall-clock timer that supports elapsed time, countdown and optional
 * on-expire callbacks. Thread-safe.
 */
public final class GameTimer {

    private final AtomicReference<Runnable> onExpire = new AtomicReference<>();
    private volatile Instant startedAt;
    private volatile Instant stoppedAt;
    private volatile Duration countdownTarget;

    public void start() {
        startedAt = Instant.now();
        stoppedAt = null;
    }

    public void stop() {
        if (startedAt != null && stoppedAt == null) stoppedAt = Instant.now();
    }

    public void startCountdown(Duration duration, Runnable onExpireCallback) {
        this.countdownTarget = duration;
        this.onExpire.set(onExpireCallback);
        start();
    }

    public Duration elapsed() {
        if (startedAt == null) return Duration.ZERO;
        Instant end = stoppedAt != null ? stoppedAt : Instant.now();
        return Duration.between(startedAt, end);
    }

    public Duration remaining() {
        if (countdownTarget == null || startedAt == null) return Duration.ZERO;
        Duration rem = countdownTarget.minus(elapsed());
        return rem.isNegative() ? Duration.ZERO : rem;
    }

    public boolean isExpired() {
        return countdownTarget != null && remaining().isZero();
    }

    public void checkExpiry() {
        if (!isExpired()) return;
        Runnable cb = onExpire.getAndSet(null);
        if (cb != null) cb.run();
    }

    public void reset() {
        startedAt = null;
        stoppedAt = null;
        countdownTarget = null;
        onExpire.set(null);
    }
}