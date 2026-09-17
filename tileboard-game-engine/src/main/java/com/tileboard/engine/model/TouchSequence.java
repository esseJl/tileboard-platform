package com.tileboard.engine.model;

import com.tileboard.serial.board.Position;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * An ordered, immutable snapshot of positions touched so far in a session,
 * together with per-touch timing information. Built by {@link com.tileboard.engine.feature.TouchHistory}.
 */
public record TouchSequence(List<Position> positions, List<Instant> timestamps) {

    public TouchSequence {
        Objects.requireNonNull(positions,   "positions");
        Objects.requireNonNull(timestamps,  "timestamps");
        if (positions.size() != timestamps.size()) {
            throw new IllegalArgumentException("positions and timestamps must have equal size");
        }
        positions  = List.copyOf(positions);
        timestamps = List.copyOf(timestamps);
    }

    public static TouchSequence empty() {
        return new TouchSequence(List.of(), List.of());
    }

    /** Number of touches recorded. */
    public int size() { return positions.size(); }

    public boolean isEmpty() { return positions.isEmpty(); }

    /**
     * Duration between consecutive touches at index {@code i} and {@code i+1}.
     *
     * @throws IndexOutOfBoundsException if {@code i < 0 || i >= size()-1}
     */
    public Duration gapBetween(int i) {
        return Duration.between(timestamps.get(i), timestamps.get(i + 1));
    }

    /** All inter-touch durations, in order. Empty if fewer than two touches. */
    public List<Duration> gaps() {
        if (size() < 2) return List.of();
        var gaps = new java.util.ArrayList<Duration>(size() - 1);
        for (int i = 0; i < size() - 1; i++) gaps.add(gapBetween(i));
        return Collections.unmodifiableList(gaps);
    }

    /** Average gap between consecutive touches, or {@link Duration#ZERO} if fewer than two touches. */
    public Duration averageGap() {
        var gs = gaps();
        if (gs.isEmpty()) return Duration.ZERO;
        long totalNanos = gs.stream().mapToLong(Duration::toNanos).sum();
        return Duration.ofNanos(totalNanos / gs.size());
    }
}
