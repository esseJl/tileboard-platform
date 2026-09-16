package com.tileboard.gamekit.time;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Clock abstraction for deterministic tests and production schedulers. */
public interface GameClock {
    default Instant now() { return Instant.now(); }
    Cancellable scheduleOnce(Duration delay, Runnable task);
    Cancellable scheduleAtFixedRate(Duration period, Runnable task);

    default Duration elapsedSince(Instant start) {
        Objects.requireNonNull(start, "start");
        return Duration.between(start, now());
    }

    default boolean hasElapsed(Duration duration, Instant start) {
        return !elapsedSince(start).minus(duration).isNegative();
    }
}
