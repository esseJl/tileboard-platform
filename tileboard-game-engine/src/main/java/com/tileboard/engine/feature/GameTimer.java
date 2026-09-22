package com.tileboard.engine.feature;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A wall-clock timer that supports elapsed time, countdown and optional
 * on-expire callbacks. Thread-safe.
 */
public final class GameTimer {

    private final AtomicReference<Runnable> onExpire = new AtomicReference<>();
    private final AtomicBoolean expiryNotified = new AtomicBoolean(false);
    private final Runnable engineExpiryNotifier;
    private volatile Instant startedAt;
    private volatile Instant stoppedAt;
    private volatile Duration countdownTarget;

    public GameTimer() {
        this(() -> {
        });
    }

    /**
     * @param engineExpiryNotifier invoked (on the caller's thread, exactly once per
     *                             expiry) the first time {@link #checkExpiry()} observes
     *                             the countdown has run out, so callers such as the
     *                             engine can publish a {@code TIMER_EXPIRED} game event.
     *                             This fires independently of the per-countdown callback
     *                             passed to {@link #startCountdown(Duration, Runnable)}.
     */
    public GameTimer(Runnable engineExpiryNotifier) {
        this.engineExpiryNotifier = Objects.requireNonNull(engineExpiryNotifier, "engineExpiryNotifier");
    }

    public void start() {
        startedAt = Instant.now();
        stoppedAt = null;
        expiryNotified.set(false);
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
        if (expiryNotified.compareAndSet(false, true)) {
            engineExpiryNotifier.run();
        }
        Runnable cb = onExpire.getAndSet(null);
        if (cb != null) cb.run();
    }

    public void reset() {
        startedAt = null;
        stoppedAt = null;
        countdownTarget = null;
        onExpire.set(null);
        expiryNotified.set(false);
    }
}