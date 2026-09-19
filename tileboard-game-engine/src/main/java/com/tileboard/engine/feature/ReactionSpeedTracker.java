package com.tileboard.engine.feature;

import com.tileboard.engine.model.TileEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * Measures how quickly a player reacts. A "stimulus" is set by the game
 * (e.g. lighting a tile); the next touch records the reaction time.
 */
public final class ReactionSpeedTracker {

    private final AtomicReference<Instant> stimulusAt = new AtomicReference<>();
    private final LongAdder totalNanos = new LongAdder();
    private final AtomicLong count = new AtomicLong(0L);
    private volatile long lastNanos = -1L;
    private volatile long bestNanos = Long.MAX_VALUE;

    /**
     * Marks the moment a stimulus (e.g. lit tile) was presented.
     */
    public void stimulus() {
        stimulusAt.set(Instant.now());
    }

    /**
     * Called by the engine for every tile event; records reaction if a stimulus is pending.
     */
    public void record(TileEvent event) {
        Instant s = stimulusAt.getAndSet(null);
        if (s == null) return;
        long nanos = Duration.between(s, event.occurredAt()).toNanos();
        if (nanos < 0) nanos = 0;
        lastNanos = nanos;
        if (nanos < bestNanos) bestNanos = nanos;
        totalNanos.add(nanos);
        count.incrementAndGet();
    }

    public Duration lastReaction() {
        return lastNanos < 0 ? Duration.ZERO : Duration.ofNanos(lastNanos);
    }

    public Duration bestReaction() {
        return bestNanos == Long.MAX_VALUE ? Duration.ZERO : Duration.ofNanos(bestNanos);
    }

    public OptionalDouble averageReactionMillis() {
        long n = count.get();
        if (n == 0) return OptionalDouble.empty();
        return OptionalDouble.of(totalNanos.sum() / (double) n / 1_000_000.0);
    }

    public long reactionCount() {
        return count.get();
    }

    public void reset() {
        stimulusAt.set(null);
        totalNanos.reset();
        count.set(0L);
        lastNanos = -1L;
        bestNanos = Long.MAX_VALUE;
    }
}