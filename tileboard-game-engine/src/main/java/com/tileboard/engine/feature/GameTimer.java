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

    /**
     * Sets a countdown duration and callback fired when elapsed >= target.
     */
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

    /**
     * Remaining time in a countdown, or {@link Duration#ZERO} if expired / not started.
     */
    public Duration remaining() {
        if (countdownTarget == null || startedAt == null) return Duration.ZERO;
        Duration elapsed = elapsed();
        Duration rem = countdownTarget.minus(elapsed);
        return rem.isNegative() ? Duration.ZERO : rem;
    }

    public boolean isExpired() {
        return countdownTarget != null && remaining().isZero();
    }

    /**
     * Should be called on every tick to fire the expire callback exactly once.
     */
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