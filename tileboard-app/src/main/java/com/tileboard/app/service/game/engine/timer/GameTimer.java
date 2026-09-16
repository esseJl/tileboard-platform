package com.tileboard.app.service.game.engine.timer;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Built-in session timer + countdown.
 * Supports both elapsed-time queries and a one-shot countdown that fires a callback.
 */
public final class GameTimer {

    private final AtomicReference<Instant> startedAt = new AtomicReference<>();
    private final AtomicReference<Instant> countdownDeadline = new AtomicReference<>();
    private volatile Consumer<GameTimer> onCountdownFinished;

    public void start() {
        startedAt.set(Instant.now());
    }

    public void stop() {
        startedAt.set(null);
        countdownDeadline.set(null);
    }

    public Optional<Duration> elapsed() {
        Instant s = startedAt.get();
        return s == null ? Optional.empty() : Optional.of(Duration.between(s, Instant.now()));
    }

    public long elapsedMillis() {
        return elapsed().map(Duration::toMillis).orElse(0L);
    }

    /**
     * Starts (or restarts) a countdown of the given duration.
     * When the deadline is reached, {@code onFinished} is invoked once
     * (from the thread that calls {@link #tick()}).
     */
    public void startCountdown(Duration duration, Consumer<GameTimer> onFinished) {
        countdownDeadline.set(Instant.now().plus(duration));
        this.onCountdownFinished = onFinished;
    }

    public Optional<Duration> remaining() {
        Instant d = countdownDeadline.get();
        if (d == null) {
            return Optional.empty();
        }
        Duration left = Duration.between(Instant.now(), d);
        return left.isNegative() ? Optional.of(Duration.ZERO) : Optional.of(left);
    }

    /**
     * Must be called periodically (e.g. from the game loop or input handler)
     * so that countdown callbacks can fire.
     */
    public void tick() {
        Instant d = countdownDeadline.get();
        if (d != null && !Instant.now().isBefore(d)) {
            countdownDeadline.set(null);
            Consumer<GameTimer> cb = onCountdownFinished;
            onCountdownFinished = null;
            if (cb != null) {
                cb.accept(this);
            }
        }
    }

    public boolean isCountdownActive() {
        Instant d = countdownDeadline.get();
        return d != null && Instant.now().isBefore(d);
    }
}
