package com.tileboard.app.gameengine;

/**
 * A handle returned by {@link GameContext#scheduleAtFixedRate} to stop a
 * recurring tick early (e.g. once a game has been won or lost and no longer
 * needs to animate). Deliberately a single-method, game-agnostic contract -
 * a game holds onto whatever {@code Cancellable} its scheduling call
 * returned and invokes {@link #cancel()} on it; it never sees the
 * {@code ScheduledExecutorService} underneath.
 */
@FunctionalInterface
public interface Cancellable {

    /** Idempotent: calling this more than once, or after the tick already finished on its own, is a no-op. */
    void cancel();
}
