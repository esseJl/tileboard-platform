package com.tileboard.gamekit.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable observation of one physical tile touch.
 * It carries the information most games otherwise calculate repeatedly:
 * position, sequence number, absolute time, delta from the previous touch,
 * current tile colour and target colour.
 */
public record TileObservation(
        TilePosition position,
        long sequence,
        Instant timestamp,
        Duration sincePreviousTouch,
        String tileColor,
        String targetColor) {

    public TileObservation {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(timestamp, "timestamp");
        sincePreviousTouch = sincePreviousTouch == null ? Duration.ZERO : sincePreviousTouch;
        if (sequence < 1) throw new IllegalArgumentException("sequence must be >= 1");
    }

    public boolean matchesTarget() {
        return Objects.equals(tileColor, targetColor);
    }
}
