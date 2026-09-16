package com.tileboard.gamekit.time;

/**
 * A handle returned by a scheduling call ({@link GameClock#scheduleAtFixedRate}
 * or {@link GameClock#scheduleOnce}) to stop it early. Deliberately a
 * single-method, source-agnostic contract - callers hold onto whatever
 * {@code Cancellable} a scheduling call returned and invoke {@link #cancel()}
 * on it; they never see the underlying executor.
 */
@FunctionalInterface
public interface Cancellable {

    /** Idempotent: calling this more than once, or after the task already finished on its own, is a no-op. */
    void cancel();

    /** A {@code Cancellable} that does nothing - useful as a default/no-op return value. */
    static Cancellable noop() {
        return () -> {
        };
    }
}
