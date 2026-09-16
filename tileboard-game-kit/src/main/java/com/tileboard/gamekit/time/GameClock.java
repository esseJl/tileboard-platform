package com.tileboard.gamekit.time;

import java.time.Duration;

/**
 * The minimal "time" surface every timer/countdown/wave/rhythm building
 * block in this kit is written against, instead of each one hand-rolling
 * its own {@code ScheduledExecutorService} (and forgetting to shut it
 * down). A game session's own clock - e.g. the app's
 * {@code GameContext<T>} - implements this directly; nothing in this kit
 * ever depends on Spring, servlets, or the serial layer to get a tick.
 *
 * <p>Implementations must be thread-safe: {@link #scheduleAtFixedRate} and
 * {@link #scheduleOnce} may be called from one thread (e.g. an HTTP request
 * thread during {@code start()}) while a previously scheduled task fires on
 * another (this clock's own background thread), and {@link #elapsed()} may
 * be read from yet another (e.g. a touch-input callback thread).
 */
public interface GameClock {

    /** How long this clock has been running. */
    Duration elapsed();

    /**
     * Runs {@code task} every {@code period}, starting one {@code period}
     * from now, until either the returned {@link Cancellable} is used or
     * the clock itself is torn down. An exception escaping {@code task}
     * must be caught and logged by the implementation rather than silently
     * killing all future executions.
     */
    Cancellable scheduleAtFixedRate(Duration period, Runnable task);

    /** Runs {@code task} exactly once, after {@code delay}, unless cancelled first. */
    Cancellable scheduleOnce(Duration delay, Runnable task);

    /**
     * Convenience countdown: runs {@code onElapsed} once, after
     * {@code duration}. Equivalent to {@link #scheduleOnce}, named
     * separately because "countdown" is one of this kit's first-class,
     * commonly requested building blocks (round timers, reveal timers,
     * "get ready" delays).
     */
    default Cancellable countDown(Duration duration, Runnable onElapsed) {
        return scheduleOnce(duration, onElapsed);
    }

    /**
     * Convenience "has at least {@code duration} elapsed?" check, for
     * clocks driven by their own periodic tick (e.g. {@code JumpGame}'s
     * round-duration check) rather than a one-shot callback.
     */
    default boolean hasElapsed(Duration duration) {
        return elapsed().compareTo(duration) >= 0;
    }
}
