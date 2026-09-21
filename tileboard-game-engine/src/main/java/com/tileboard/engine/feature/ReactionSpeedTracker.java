package com.tileboard.engine.feature;

import com.tileboard.engine.model.TileEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

public final class ReactionSpeedTracker {

    private final AtomicReference<Instant> stimulusAt = new AtomicReference<>();
    private final LongAdder totalNanos = new LongAdder();
    private final AtomicLong count = new AtomicLong(0L);
    private final AtomicLong lastNanos = new AtomicLong(-1L);
    private final AtomicLong bestNanos = new AtomicLong(Long.MAX_VALUE);

    public void stimulus() {
        stimulusAt.set(Instant.now());
    }

    public void record(TileEvent event) {
        Instant s = stimulusAt.getAndSet(null);
        if (s == null) return;
        long nanos = Math.max(0, Duration.between(s, event.occurredAt()).toNanos());
        lastNanos.set(nanos);
        bestNanos.updateAndGet(prev -> Math.min(prev, nanos));
        totalNanos.add(nanos);
        count.incrementAndGet();
    }

    public Duration lastReaction() {
        long v = lastNanos.get();
        return v < 0 ? Duration.ZERO : Duration.ofNanos(v);
    }

    public Duration bestReaction() {
        long v = bestNanos.get();
        return v == Long.MAX_VALUE ? Duration.ZERO : Duration.ofNanos(v);
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
        lastNanos.set(-1L);
        bestNanos.set(Long.MAX_VALUE);
    }
}